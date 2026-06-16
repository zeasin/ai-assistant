package com.laoqi.assistant.datacenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.datacenter.model.DataField;
import com.laoqi.assistant.datacenter.model.DataSchema;
import com.laoqi.assistant.datacenter.model.DataSet;
import com.laoqi.assistant.datacenter.model.ImportConfig;
import com.laoqi.assistant.entity.DataSetEntity;
import com.laoqi.assistant.entity.DataSetRecordEntity;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.db.DataSetDbService;
import com.laoqi.assistant.service.db.DataSetRecordDbService;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DataSetService {

    private static final Logger log = LoggerFactory.getLogger(DataSetService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RECORDS = 10000;
    private static final Set<String> META_FIELDS = Set.of("_id", "_source", "_importTime", "_hash");

    private final ConfigService configService;
    private final LogService logService;
    private final DataSource dataSource;
    private final DataSetDbService dataSetDbService;
    private final DataSetRecordDbService recordDbService;

    public DataSetService(ConfigService configService, LogService logService,
                          DataSource dataSource, DataSetDbService dataSetDbService,
                          DataSetRecordDbService recordDbService) {
        this.configService = configService;
        this.logService = logService;
        this.dataSource = dataSource;
        this.dataSetDbService = dataSetDbService;
        this.recordDbService = recordDbService;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing DataSetService");
        createTables();
        migrateFromFiles();
        log.info("DataSetService initialized");
    }

    private void createTables() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS data_center_datasets (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    dataset_id      TEXT NOT NULL UNIQUE,
                    name            TEXT NOT NULL,
                    description     TEXT,
                    schema_json     TEXT,
                    import_configs_json TEXT,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS data_center_records (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    record_id       TEXT NOT NULL,
                    dataset_id      TEXT NOT NULL,
                    data_json       TEXT NOT NULL,
                    source          TEXT,
                    content_hash    TEXT,
                    created_at      TEXT NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_records_dataset ON data_center_records(dataset_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_records_hash ON data_center_records(dataset_id, content_hash)");
            log.info("Data center tables initialized");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create data center tables", e);
        }
    }

    private void migrateFromFiles() {
        try {
            String baseDir = configService.getBaseDir();
            if (baseDir == null || baseDir.isEmpty()) return;

            Path fileDir = java.nio.file.Paths.get(baseDir).resolve("AI").resolve("数据中心");
            Path datasetsFile = fileDir.resolve("datasets.json");
            if (!java.nio.file.Files.exists(datasetsFile)) return;

            List<DataSet> fileDatasets = mapper.readValue(datasetsFile.toFile(),
                    new TypeReference<List<DataSet>>() {});
            if (fileDatasets == null || fileDatasets.isEmpty()) return;

            long dbCount = dataSetDbService.count();
            if (dbCount > 0) {
                log.info("Database already has {} datasets, skipping file migration", dbCount);
                return;
            }

            log.info("Migrating {} datasets from files to database", fileDatasets.size());
            for (DataSet ds : fileDatasets) {
                saveDatasetToDb(ds);

                Path dsDir = fileDir.resolve(ds.getId());
                Path dataFile = dsDir.resolve("data.json");
                if (java.nio.file.Files.exists(dataFile)) {
                    List<Map<String, Object>> records = mapper.readValue(dataFile.toFile(),
                            new TypeReference<List<Map<String, Object>>>() {});
                    if (records != null) {
                        for (Map<String, Object> record : records) {
                            saveRecordToDb(ds.getId(), record, "migrated");
                        }
                    }
                }
            }
            log.info("File migration completed");
        } catch (Exception e) {
            log.warn("File migration failed: {}", e.getMessage());
        }
    }

    public List<DataSet> getAllDatasets() {
        List<DataSet> result = new ArrayList<>();
        List<DataSetEntity> entities = dataSetDbService.list();
        for (DataSetEntity entity : entities) {
            DataSet ds = toModel(entity);
            ds.setRecordCount(recordDbService.countByDataset(ds.getId()));
            result.add(ds);
        }
        return result;
    }

    public DataSet getDataset(String datasetId) {
        LambdaQueryWrapper<DataSetEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataSetEntity::getDatasetId, datasetId);
        DataSetEntity entity = dataSetDbService.getOne(wrapper);
        if (entity == null) return null;
        DataSet ds = toModel(entity);
        ds.setRecordCount(recordDbService.countByDataset(datasetId));
        return ds;
    }

    public DataSet createDataset(DataSet ds) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ds.setId(id);
        ds.setCreatedAt(TimeUtil.nowStr());
        ds.setUpdatedAt(TimeUtil.nowStr());
        if (ds.getSchema() == null) ds.setSchema(new DataSchema());

        saveDatasetToDb(ds);

        logService.add("数据中心", "创建数据集", ds.getName());
        log.info("Created dataset: {} ({})", ds.getName(), id);
        return ds;
    }

    public DataSet updateDataset(String id, DataSet update) {
        DataSet existing = getDataset(id);
        if (existing == null) return null;

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getSchema() != null) existing.setSchema(update.getSchema());
        if (update.getImportConfigs() != null) existing.setImportConfigs(update.getImportConfigs());
        existing.setUpdatedAt(TimeUtil.nowStr());

        saveDatasetToDb(existing);
        log.info("Updated dataset: {}", id);
        return existing;
    }

    public boolean deleteDataset(String datasetId) {
        DataSet ds = getDataset(datasetId);
        if (ds == null) return false;

        LambdaQueryWrapper<DataSetRecordEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataSetRecordEntity::getDatasetId, datasetId);
        recordDbService.remove(wrapper);

        LambdaQueryWrapper<DataSetEntity> dsWrapper = new LambdaQueryWrapper<>();
        dsWrapper.eq(DataSetEntity::getDatasetId, datasetId);
        dataSetDbService.remove(dsWrapper);

        logService.add("数据中心", "删除数据集", ds.getName());
        log.info("Deleted dataset: {} ({})", ds.getName(), datasetId);
        return true;
    }

    public List<Map<String, Object>> loadRecords(String datasetId) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<DataSetRecordEntity> entities = recordDbService.listByDataset(datasetId);
        for (DataSetRecordEntity entity : entities) {
            try {
                Map<String, Object> record = mapper.readValue(entity.getDataJson(),
                        new TypeReference<Map<String, Object>>() {});
                record.put("_id", entity.getRecordId());
                record.put("_source", entity.getSource());
                record.put("_importTime", entity.getCreatedAt());
                result.add(record);
            } catch (Exception e) {
                log.warn("Failed to parse record: {}", e.getMessage());
            }
        }
        return result;
    }

    public int addRecords(String datasetId, List<Map<String, Object>> newRecords, String source) {
        DataSet ds = getDataset(datasetId);
        if (ds == null) throw new IllegalArgumentException("Dataset not found: " + datasetId);

        int skipped = 0;
        int imported = 0;
        for (Map<String, Object> record : newRecords) {
            String hash = computeHash(record);
            if (hash != null && recordDbService.existsByHash(datasetId, hash)) {
                skipped++;
                continue;
            }
            saveRecordToDb(datasetId, record, source);
            imported++;
        }

        logService.add("数据中心", "导入数据", ds.getName() + " (" + source + ", " + imported + "条" +
                (skipped > 0 ? ", 跳过" + skipped + "条重复" : "") + ")");
        log.info("Added {} records (skipped {} duplicates) to dataset {} from {}", imported, skipped, datasetId, source);
        return imported;
    }

    public boolean deleteRecord(String datasetId, String recordId) {
        LambdaQueryWrapper<DataSetRecordEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataSetRecordEntity::getDatasetId, datasetId)
               .eq(DataSetRecordEntity::getRecordId, recordId);
        return recordDbService.remove(wrapper);
    }

    public List<Map<String, Object>> searchRecords(String datasetId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return loadRecords(datasetId);
        }
        List<Map<String, Object>> all = loadRecords(datasetId);
        String lower = keyword.toLowerCase();
        return all.stream()
                .filter(r -> r.values().stream()
                        .anyMatch(v -> v != null && v.toString().toLowerCase().contains(lower)))
                .toList();
    }

    private void saveDatasetToDb(DataSet ds) {
        LambdaQueryWrapper<DataSetEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataSetEntity::getDatasetId, ds.getId());
        DataSetEntity existing = dataSetDbService.getOne(wrapper);

        try {
            String schemaJson = ds.getSchema() != null ? mapper.writeValueAsString(ds.getSchema()) : null;
            String importJson = ds.getImportConfigs() != null ? mapper.writeValueAsString(ds.getImportConfigs()) : null;

            if (existing != null) {
                existing.setName(ds.getName());
                existing.setDescription(ds.getDescription());
                existing.setSchemaJson(schemaJson);
                existing.setImportConfigsJson(importJson);
                existing.setUpdatedAt(ds.getUpdatedAt());
                dataSetDbService.updateById(existing);
            } else {
                DataSetEntity entity = new DataSetEntity();
                entity.setDatasetId(ds.getId());
                entity.setName(ds.getName());
                entity.setDescription(ds.getDescription());
                entity.setSchemaJson(schemaJson);
                entity.setImportConfigsJson(importJson);
                entity.setCreatedAt(ds.getCreatedAt());
                entity.setUpdatedAt(ds.getUpdatedAt());
                dataSetDbService.save(entity);
            }
        } catch (Exception e) {
            log.error("Failed to save dataset to DB: {}", e.getMessage());
        }
    }

    private void saveRecordToDb(String datasetId, Map<String, Object> record, String source) {
        String recordId = UUID.randomUUID().toString().substring(0, 8);
        String hash = computeHash(record);
        try {
            String dataJson = mapper.writeValueAsString(record);
            DataSetRecordEntity entity = new DataSetRecordEntity();
            entity.setRecordId(recordId);
            entity.setDatasetId(datasetId);
            entity.setDataJson(dataJson);
            entity.setSource(source);
            entity.setContentHash(hash);
            entity.setCreatedAt(TimeUtil.nowStr());
            recordDbService.save(entity);
        } catch (Exception e) {
            log.error("Failed to save record to DB: {}", e.getMessage());
        }
    }

    private DataSet toModel(DataSetEntity entity) {
        DataSet ds = new DataSet();
        ds.setId(entity.getDatasetId());
        ds.setName(entity.getName());
        ds.setDescription(entity.getDescription());
        ds.setCreatedAt(entity.getCreatedAt());
        ds.setUpdatedAt(entity.getUpdatedAt());
        try {
            if (entity.getSchemaJson() != null) {
                ds.setSchema(mapper.readValue(entity.getSchemaJson(), DataSchema.class));
            }
            if (entity.getImportConfigsJson() != null) {
                ds.setImportConfigs(mapper.readValue(entity.getImportConfigsJson(),
                        new TypeReference<Map<String, ImportConfig>>() {}));
            }
        } catch (Exception e) {
            log.warn("Failed to parse schema: {}", e.getMessage());
        }
        return ds;
    }

    @SuppressWarnings("unchecked")
    private String computeHash(Map<String, Object> record) {
        try {
            TreeMap<String, String> data = new TreeMap<>();
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                if (!META_FIELDS.contains(entry.getKey())) {
                    data.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
                }
            }
            if (data.isEmpty()) return null;
            String json = mapper.writeValueAsString(data);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
