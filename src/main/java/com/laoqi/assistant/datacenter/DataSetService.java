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

    private final LogService logService;
    private final DataSource dataSource;
    private final DataSetDbService dataSetDbService;
    private final DataSetRecordDbService recordDbService;

    public DataSetService(LogService logService,
                          DataSource dataSource, DataSetDbService dataSetDbService,
                          DataSetRecordDbService recordDbService) {
        this.logService = logService;
        this.dataSource = dataSource;
        this.dataSetDbService = dataSetDbService;
        this.recordDbService = recordDbService;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing DataSetService");
        createTables();
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
                    type            TEXT,
                    status          TEXT,
                    schema_json     TEXT,
                    import_configs_json TEXT,
                    module_id       TEXT,
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
                    record_num     TEXT,
                    record_type     TEXT,
                    record_status   TEXT,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_records_dataset ON data_center_records(dataset_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_records_hash ON data_center_records(dataset_id, content_hash)");

            try { stmt.execute("ALTER TABLE data_center_datasets ADD COLUMN module_id TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE data_center_datasets ADD COLUMN type TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE data_center_datasets ADD COLUMN status TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE data_center_records ADD COLUMN record_num TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE data_center_records ADD COLUMN record_type TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE data_center_records ADD COLUMN record_status TEXT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE data_center_records ADD COLUMN updated_at TEXT"); } catch (Exception ignored) {}

            log.info("Data center tables initialized");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create data center tables", e);
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

    private static final Set<String> RECORD_SYSTEM_FIELDS = Set.of("id", "recordNum", "编号", "recordType", "recordStatus", "type", "类型", "status", "状态", "创建时间", "更新时间");

    private static final Set<String> TYPE_ALIASES = Set.of("type", "类型");
    private static final Set<String> STATUS_ALIASES = Set.of("status", "状态");

    private String resolveType(Map<String, Object> raw, String datasetId) {
        for (String alias : TYPE_ALIASES) {
            Object v = raw.get(alias);
            if (v != null && !"".equals(v)) return String.valueOf(v);
        }
        return null;
    }

    private String resolveStatus(Map<String, Object> raw, String datasetId) {
        for (String alias : STATUS_ALIASES) {
            Object v = raw.get(alias);
            if (v != null && !"".equals(v)) return String.valueOf(v);
        }
        return null;
    }

    private String fallthroughType(String type, String datasetId) {
        if (type != null) return type;
        DataSet ds = getDataset(datasetId);
        if (ds != null && ds.getType() != null && !ds.getType().isBlank()) return ds.getType();
        return null;
    }

    private String fallthroughStatus(String status, String datasetId) {
        if (status != null) return status;
        DataSet ds = getDataset(datasetId);
        if (ds != null && ds.getStatus() != null && !ds.getStatus().isBlank()) return ds.getStatus();
        return null;
    }

    private String resolveRecordNum(Map<String, Object> raw) {
        Object v = raw.get("recordNum");
        if (v == null || "".equals(v)) v = raw.get("编号");
        if (v != null && !"".equals(v)) return String.valueOf(v);
        return null;
    }

    private Map<String, Object> stripSystemFields(Map<String, Object> raw) {
        Map<String, Object> out = new HashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (RECORD_SYSTEM_FIELDS.contains(e.getKey())) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private DataSchema normalizeSchema(DataSchema schema) {
        if (schema == null) return null;
        if (schema.getFields() == null) return schema;
        List<DataField> cleaned = new ArrayList<>();
        for (DataField f : schema.getFields()) {
            if (f == null || f.getName() == null) continue;
            if (RECORD_SYSTEM_FIELDS.contains(f.getName().trim())) continue;
            cleaned.add(f);
        }
        schema.setFields(cleaned);
        return schema;
    }

    public DataSet createDataset(DataSet ds) {
        String id = UUID.randomUUID().toString().substring(0, 12);
        ds.setId(id);
        ds.setCreatedAt(TimeUtil.nowStr());
        ds.setUpdatedAt(TimeUtil.nowStr());
        ds.setSchema(normalizeSchema(ds.getSchema()));

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
        if (update.getType() != null) existing.setType(update.getType());
        if (update.getStatus() != null) existing.setStatus(update.getStatus());
        if (update.getSchema() != null) existing.setSchema(normalizeSchema(update.getSchema()));
        if (update.getImportConfigs() != null) existing.setImportConfigs(update.getImportConfigs());
        if (update.getModuleId() != null) existing.setModuleId(update.getModuleId());
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
        DataSet ds = getDataset(datasetId);
        for (DataSetRecordEntity entity : entities) {
            try {
                Map<String, Object> record = mapper.readValue(entity.getDataJson(),
                        new TypeReference<Map<String, Object>>() {});
                record.put("_id", entity.getRecordId());
                record.put("_source", entity.getSource());
                record.put("_importTime", entity.getCreatedAt());
                record.put("recordNum", entity.getRecordNum());
                record.put("编号", entity.getRecordNum());
                String type = fallthroughType(entity.getRecordType(), datasetId);
                String status = fallthroughStatus(entity.getRecordStatus(), datasetId);
                record.put("type", type);
                record.put("类型", type);
                record.put("status", status);
                record.put("状态", status);
                record.put("创建时间", entity.getCreatedAt());
                String updatedAt = entity.getUpdatedAt();
                if (updatedAt == null || updatedAt.isBlank()) updatedAt = entity.getCreatedAt();
                record.put("更新时间", updatedAt);
                record.put("_datasetType", ds != null ? ds.getType() : null);
                record.put("_datasetStatus", ds != null ? ds.getStatus() : null);
                result.add(record);
            } catch (Exception e) {
                log.warn("Failed to parse record: {}", e.getMessage());
            }
        }
        return result;
    }

    public int addRecords(String datasetId, List<Map<String, Object>> newRecords, String source) {
        long existingCount = recordDbService.countByDataset(datasetId);

        int skipped = 0;
        int imported = 0;
        for (Map<String, Object> raw : newRecords) {
            if (existingCount + imported >= MAX_RECORDS) {
                log.warn("Dataset {} reached MAX_RECORDS ({}), stopping import", datasetId, MAX_RECORDS);
                skipped += (newRecords.size() - imported - skipped);
                break;
            }
            saveRecordToDb(datasetId, raw, source);
            imported++;
        }

        DataSet ds = getDataset(datasetId);
        logService.add("数据中心", "导入数据", ds != null ? ds.getName() : datasetId + " (" + source + ", " + imported + "条" +
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

    public Map<String, Object> updateRecord(String datasetId, String recordId, Map<String, Object> newData) {
        LambdaQueryWrapper<DataSetRecordEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataSetRecordEntity::getDatasetId, datasetId)
               .eq(DataSetRecordEntity::getRecordId, recordId);
        DataSetRecordEntity entity = recordDbService.getOne(wrapper);
        if (entity == null) return null;

        String rawType = resolveType(newData, datasetId);
        String rawStatus = resolveStatus(newData, datasetId);
        String type = fallthroughType(rawType, datasetId);
        String status = fallthroughStatus(rawStatus, datasetId);
        String userRecordNum = resolveRecordNum(newData);
        Map<String, Object> business = stripSystemFields(newData);
        String now = TimeUtil.nowStr();
        String created = entity.getCreatedAt();
        if (created == null || created.isBlank()) created = now;
        try {
            String dataJson = mapper.writeValueAsString(business);
            entity.setDataJson(dataJson);
            entity.setRecordType(type);
            entity.setRecordStatus(status);
            if (userRecordNum != null) entity.setRecordNum(userRecordNum);
            entity.setUpdatedAt(now);
            String hash = computeHash(business);
            entity.setContentHash(hash);
            recordDbService.updateById(entity);

            Map<String, Object> result = new HashMap<>(business);
            result.put("recordNum", entity.getRecordNum());
            result.put("编号", entity.getRecordNum());
            result.put("type", type);
            result.put("类型", type);
            result.put("status", status);
            result.put("状态", status);
            result.put("创建时间", created);
            result.put("更新时间", now);
            return result;
        } catch (Exception e) {
            log.error("Failed to update record: {}", e.getMessage());
            return null;
        }
    }

    public boolean updateRecordField(String datasetId, String recordId, String field, String value) {
        LambdaQueryWrapper<DataSetRecordEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataSetRecordEntity::getDatasetId, datasetId)
               .eq(DataSetRecordEntity::getRecordId, recordId);
        DataSetRecordEntity entity = recordDbService.getOne(wrapper);
        if (entity == null) return false;

        try {
            Map<String, Object> record = mapper.readValue(entity.getDataJson(),
                    new TypeReference<Map<String, Object>>() {});
            record.put(field, value);
            String now = TimeUtil.nowStr();
            if (TYPE_ALIASES.contains(field)) {
                entity.setRecordType(value == null || value.isBlank() ? null : value);
                record.remove(field);
            } else if (STATUS_ALIASES.contains(field)) {
                entity.setRecordStatus(value == null || value.isBlank() ? null : value);
                record.remove(field);
            }
            String dataJson = mapper.writeValueAsString(record);
            entity.setDataJson(dataJson);
            entity.setUpdatedAt(now);
            String hash = computeHash(record);
            entity.setContentHash(hash);
            recordDbService.updateById(entity);
            return true;
        } catch (Exception e) {
            log.error("Failed to update record field: {}", e.getMessage());
            return false;
        }
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

    public List<Map<String, Object>> queryRecords(String datasetId, Map<String, String> filters) {
        List<Map<String, Object>> all = loadRecords(datasetId);
        if (filters == null || filters.isEmpty()) return all;
        return all.stream()
                .filter(r -> filters.entrySet().stream()
                        .allMatch(e -> {
                            Object v = r.get(e.getKey());
                            if (v == null) return false;
                            String target = e.getValue();
                            if (target == null || target.isBlank()) return false;
                            return v.toString().equals(target);
                        }))
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
                existing.setType(ds.getType());
                existing.setStatus(ds.getStatus());
                existing.setSchemaJson(schemaJson);
                existing.setImportConfigsJson(importJson);
                existing.setModuleId(ds.getModuleId());
                existing.setUpdatedAt(ds.getUpdatedAt());
                dataSetDbService.updateById(existing);
            } else {
                DataSetEntity entity = new DataSetEntity();
                entity.setDatasetId(ds.getId());
                entity.setName(ds.getName());
                entity.setDescription(ds.getDescription());
                entity.setType(ds.getType());
                entity.setStatus(ds.getStatus());
                entity.setSchemaJson(schemaJson);
                entity.setImportConfigsJson(importJson);
                entity.setModuleId(ds.getModuleId());
                entity.setCreatedAt(ds.getCreatedAt());
                entity.setUpdatedAt(ds.getUpdatedAt());
                dataSetDbService.save(entity);
            }
        } catch (Exception e) {
            log.error("Failed to save dataset to DB: {}", e.getMessage());
        }
    }

    private void saveRecordToDb(String datasetId, Map<String, Object> raw, String source) {
        String recordId = UUID.randomUUID().toString().substring(0, 12);
        String rawType = resolveType(raw, datasetId);
        String rawStatus = resolveStatus(raw, datasetId);
        String type = fallthroughType(rawType, datasetId);
        String status = fallthroughStatus(rawStatus, datasetId);
        String userRecordNum = resolveRecordNum(raw);
        Map<String, Object> business = stripSystemFields(raw);
        String now = TimeUtil.nowStr();
        String recordNum = (userRecordNum != null) ? userRecordNum
                : String.format("%04d", recordDbService.countByDataset(datasetId) + 1);
        try {
            String dataJson = mapper.writeValueAsString(business);
            String hash = computeHash(business);
            DataSetRecordEntity entity = new DataSetRecordEntity();
            entity.setRecordId(recordId);
            entity.setDatasetId(datasetId);
            entity.setDataJson(dataJson);
            entity.setSource(source);
            entity.setContentHash(hash);
            entity.setRecordNum(recordNum);
            entity.setRecordType(type);
            entity.setRecordStatus(status);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
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
        ds.setType(entity.getType());
        ds.setStatus(entity.getStatus());
        ds.setModuleId(entity.getModuleId());
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
