package com.laoqi.assistant.datacenter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.datacenter.model.*;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DataSetService {

    private static final Logger log = LoggerFactory.getLogger(DataSetService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RECORDS = 10000;

    private final ConfigService configService;
    private final LogService logService;
    private final Map<String, DataSet> datasets = new ConcurrentHashMap<>();

    public DataSetService(ConfigService configService, LogService logService) {
        this.configService = configService;
        this.logService = logService;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing DataSetService");
        loadDatasets();
        log.info("Loaded {} datasets", datasets.size());
    }

    private Path getDataCenterDir() {
        try {
            String baseDir = configService.getBaseDir();
            if (baseDir == null || baseDir.isEmpty()) {
                throw new IllegalStateException("baseDir is empty");
            }
            Path dir = Paths.get(baseDir).resolve("数据中心");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (Exception e) {
            log.warn("Failed to get data center dir: {}, using fallback", e.getMessage());
            Path fallback = Paths.get(System.getProperty("user.dir")).resolve("数据中心");
            try {
                if (!Files.exists(fallback)) {
                    Files.createDirectories(fallback);
                }
            } catch (Exception ex) {
                log.error("Failed to create fallback dir: {}", ex.getMessage());
            }
            return fallback;
        }
    }

    private Path getDatasetsFile() {
        return getDataCenterDir().resolve("datasets.json");
    }

    private Path getDatasetDir(String datasetId) {
        return getDataCenterDir().resolve(datasetId);
    }

    private Path getDataFile(String datasetId) {
        return getDatasetDir(datasetId).resolve("data.json");
    }

    private Path getImportsDir(String datasetId) {
        return getDatasetDir(datasetId).resolve("imports");
    }

    private void loadDatasets() {
        try {
            Path file = getDatasetsFile();
            if (FileUtil.exists(file)) {
                List<DataSet> loaded = FileUtil.readJson(file,
                        new TypeReference<List<DataSet>>() {}, new ArrayList<>());
                for (DataSet ds : loaded) {
                    datasets.put(ds.getId(), ds);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load datasets: {}", e.getMessage());
        }
    }

    private void saveDatasets() {
        try {
            FileUtil.writeJson(getDatasetsFile(), new ArrayList<>(datasets.values()));
        } catch (Exception e) {
            log.error("Failed to save datasets: {}", e.getMessage());
        }
    }

    public List<DataSetInfo> getAllDatasets() {
        return datasets.values().stream()
                .map(ds -> {
                    List<Map<String, Object>> records = loadRecords(ds.getId());
                    String lastImport = getLastImportTime(ds.getId());
                    return new DataSetInfo(ds, records.size(), lastImport);
                })
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .collect(Collectors.toList());
    }

    public DataSet getDataset(String id) {
        return datasets.get(id);
    }

    public DataSet createDataset(DataSet ds) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ds.setId(id);
        ds.setCreatedAt(TimeUtil.nowStr());
        ds.setUpdatedAt(TimeUtil.nowStr());
        if (ds.getSchema() == null) {
            ds.setSchema(new DataSchema());
        }
        if (ds.getOutputDir() == null || ds.getOutputDir().isEmpty()) {
            ds.setOutputDir("数据中心/" + id);
        }

        datasets.put(id, ds);
        saveDatasets();

        Path dsDir = getDatasetDir(id);
        try {
            Files.createDirectories(dsDir);
            Files.createDirectories(getImportsDir(id));
            FileUtil.writeJson(getDataFile(id), new ArrayList<>());
        } catch (Exception e) {
            log.error("Failed to create dataset directory: {}", e.getMessage());
        }

        logService.add("数据中心", "创建数据集", ds.getName());
        log.info("Created dataset: {} ({})", ds.getName(), id);
        return ds;
    }

    public DataSet updateDataset(String id, DataSet update) {
        DataSet existing = datasets.get(id);
        if (existing == null) return null;

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getSchema() != null) existing.setSchema(update.getSchema());
        if (update.getOutputDir() != null) existing.setOutputDir(update.getOutputDir());
        if (update.getImportConfigs() != null) existing.setImportConfigs(update.getImportConfigs());
        existing.setUpdatedAt(TimeUtil.nowStr());

        saveDatasets();
        log.info("Updated dataset: {}", id);
        return existing;
    }

    public boolean deleteDataset(String id) {
        DataSet removed = datasets.remove(id);
        if (removed == null) return false;

        saveDatasets();
        try {
            Path dsDir = getDatasetDir(id);
            if (Files.exists(dsDir)) {
                deleteRecursively(dsDir);
            }
        } catch (Exception e) {
            log.warn("Failed to delete dataset directory: {}", e.getMessage());
        }

        logService.add("数据中心", "删除数据集", removed.getName());
        log.info("Deleted dataset: {} ({})", removed.getName(), id);
        return true;
    }

    private void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    public List<Map<String, Object>> loadRecords(String datasetId) {
        Path file = getDataFile(datasetId);
        if (!FileUtil.exists(file)) return new ArrayList<>();
        return FileUtil.readJson(file, new TypeReference<List<Map<String, Object>>>() {}, new ArrayList<>());
    }

    public void saveRecords(String datasetId, List<Map<String, Object>> records) {
        FileUtil.writeJson(getDataFile(datasetId), records);
    }

    public int addRecords(String datasetId, List<Map<String, Object>> newRecords, String source) {
        DataSet ds = datasets.get(datasetId);
        if (ds == null) throw new IllegalArgumentException("Dataset not found: " + datasetId);

        List<Map<String, Object>> existing = loadRecords(datasetId);

        for (Map<String, Object> record : newRecords) {
            record.put("_id", UUID.randomUUID().toString().substring(0, 8));
            record.put("_source", source);
            record.put("_importTime", TimeUtil.nowStr());
            existing.add(record);
        }

        if (existing.size() > MAX_RECORDS) {
            existing = new ArrayList<>(existing.subList(existing.size() - MAX_RECORDS, existing.size()));
        }

        saveRecords(datasetId, existing);

        String importFile = TimeUtil.todayStr() + "_" + source + ".json";
        Path importPath = getImportsDir(datasetId).resolve(importFile);
        Map<String, Object> importRecord = new LinkedHashMap<>();
        importRecord.put("source", source);
        importRecord.put("time", TimeUtil.nowStr());
        importRecord.put("count", newRecords.size());
        importRecord.put("data", newRecords);
        FileUtil.writeJson(importPath, importRecord);

        logService.add("数据中心", "导入数据", ds.getName() + " (" + source + ", " + newRecords.size() + "条)");
        log.info("Added {} records to dataset {} from {}", newRecords.size(), datasetId, source);
        return newRecords.size();
    }

    public boolean deleteRecord(String datasetId, String recordId) {
        List<Map<String, Object>> records = loadRecords(datasetId);
        boolean removed = records.removeIf(r -> recordId.equals(r.get("_id")));
        if (removed) {
            saveRecords(datasetId, records);
        }
        return removed;
    }

    public List<Map<String, Object>> searchRecords(String datasetId, String keyword) {
        List<Map<String, Object>> records = loadRecords(datasetId);
        if (keyword == null || keyword.isBlank()) return records;

        String lower = keyword.toLowerCase();
        return records.stream()
                .filter(r -> r.values().stream()
                        .anyMatch(v -> v != null && v.toString().toLowerCase().contains(lower)))
                .collect(Collectors.toList());
    }

    private String getLastImportTime(String datasetId) {
        try {
            Path importsDir = getImportsDir(datasetId);
            if (!Files.exists(importsDir)) return null;
            try (var files = Files.list(importsDir)) {
                return files
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted(Comparator.reverseOrder())
                        .map(p -> p.getFileName().toString().replace(".json", ""))
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
