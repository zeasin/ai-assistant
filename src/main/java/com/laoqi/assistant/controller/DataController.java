package com.laoqi.assistant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.model.ModuleDefinition;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.ModuleService;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private static final Logger log = LoggerFactory.getLogger(DataController.class);
    private static final TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};

    private static final Map<String, String> TYPE_CONFIG_MAP = new LinkedHashMap<>();
    static {
        TYPE_CONFIG_MAP.put("customer", "客户数据目录");
        TYPE_CONFIG_MAP.put("operations", "运营数据目录");
    }

    private final ConfigService configService;
    private final AppConfig appConfig;
    private final ModuleService moduleService;

    public DataController(ConfigService configService, AppConfig appConfig, ModuleService moduleService) {
        this.configService = configService;
        this.appConfig = appConfig;
        this.moduleService = moduleService;
    }

    private Path getBaseDir() {
        String baseDir = configService.load().getBaseDir();
        if (baseDir == null || baseDir.isEmpty()) {
            throw new IllegalStateException("baseDir 未配置，请在 config.json 中设置");
        }
        return Paths.get(baseDir);
    }

    private String getDataDirByType(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        ModuleDefinition mod = moduleService.getModule(type);
        return mod != null ? mod.getDir() : null;
    }

    private String getTypeLabel(String type) {
        return TYPE_CONFIG_MAP.getOrDefault(type, type);
    }

    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listData(
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "false") boolean includeData) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        Path baseDir;
        try {
            baseDir = getBaseDir();
        } catch (IllegalStateException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
        
        String dataDir = getDataDirByType(type);
        if (dataDir == null || dataDir.isEmpty()) {
            result.put("ok", false);
            result.put("error", "「" + getTypeLabel(type) + "」未配置，请在 config.json 中设置");
            return ResponseEntity.ok(result);
        }
        
        Path targetDir = resolveDataDir(baseDir, dataDir);
        
        result.put("type", type);
        result.put("directory", dataDir);
        result.put("fullPath", targetDir.toString());
        
        if (!Files.exists(targetDir)) {
            result.put("ok", false);
            result.put("error", "目录不存在: " + targetDir);
            result.put("exists", false);
            return ResponseEntity.ok(result);
        }
        
        result.put("exists", true);
        
        try {
            List<Map<String, Object>> fileList = new ArrayList<>();
            List<Map<String, Object>> dataGroups = new ArrayList<>();
            
            try (java.util.stream.Stream<Path> stream = Files.list(targetDir)) {
                List<Path> jsonFiles = stream
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
                
                for (Path f : jsonFiles) {
                    String fileName = f.getFileName().toString().replace(".json", "");
                    Map<String, Object> fileInfo = new LinkedHashMap<>();
                    fileInfo.put("name", fileName);
                    fileInfo.put("path", f.toString());
                    fileInfo.put("size", Files.size(f));
                    fileInfo.put("lastModified", Files.getLastModifiedTime(f).toMillis());
                    fileList.add(fileInfo);
                    
                    if (includeData) {
                        Map<String, Object> fileData = FileUtil.readJson(f, mapType, new HashMap<>());
                        Map<String, Object> groupInfo = new LinkedHashMap<>();
                        groupInfo.put("fileName", fileName);
                        groupInfo.put("groups", extractGroups(fileData));
                        dataGroups.add(groupInfo);
                    }
                }
            }
            
            result.put("ok", true);
            result.put("files", fileList);
            result.put("fileCount", fileList.size());
            
            if (includeData) {
                result.put("dataGroups", dataGroups);
            }
            
        } catch (Exception e) {
            log.error("读取目录失败: {}", targetDir, e);
            result.put("ok", false);
            result.put("error", "读取目录失败: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/file/{fileName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getFileData(
            @PathVariable String fileName,
            @RequestParam(required = false) String type) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        Path baseDir;
        try {
            baseDir = getBaseDir();
        } catch (IllegalStateException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
        
        String dataDir = getDataDirByType(type);
        if (dataDir == null || dataDir.isEmpty()) {
            result.put("ok", false);
            result.put("error", "「" + getTypeLabel(type) + "」未配置，请在 config.json 中设置");
            return ResponseEntity.ok(result);
        }
        
        Path targetDir = resolveDataDir(baseDir, dataDir);
        Path filePath = safeResolveFile(targetDir, fileName);
        
        if (!Files.exists(filePath)) {
            result.put("ok", false);
            result.put("error", "文件不存在");
            return ResponseEntity.ok(result);
        }
        
        try {
            Map<String, Object> data = FileUtil.readJson(filePath, mapType, new HashMap<>());
            result.put("ok", true);
            result.put("fileName", fileName);
            result.put("data", data);
            result.put("groups", extractGroups(data));
        } catch (Exception e) {
            log.error("读取文件失败: {}", filePath, e);
            result.put("ok", false);
            result.put("error", "读取文件失败: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/group", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getGroupData(
            @RequestParam String fileName,
            @RequestParam String group,
            @RequestParam(required = false) String type) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        Path baseDir;
        try {
            baseDir = getBaseDir();
        } catch (IllegalStateException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
        
        String dataDir = getDataDirByType(type);
        if (dataDir == null || dataDir.isEmpty()) {
            result.put("ok", false);
            result.put("error", "「" + getTypeLabel(type) + "」未配置，请在 config.json 中设置");
            return ResponseEntity.ok(result);
        }
        
        Path targetDir = resolveDataDir(baseDir, dataDir);
        Path filePath = safeResolveFile(targetDir, fileName);
        
        if (!Files.exists(filePath)) {
            result.put("ok", false);
            result.put("error", "文件不存在");
            return ResponseEntity.ok(result);
        }
        
        try {
            Map<String, Object> data = FileUtil.readJson(filePath, mapType, new HashMap<>());
            Object groupData = data.get(group);
            
            if (groupData == null) {
                result.put("ok", false);
                result.put("error", "分组不存在: " + group);
                return ResponseEntity.ok(result);
            }
            
            result.put("ok", true);
            result.put("fileName", fileName);
            result.put("group", group);

            if (groupData instanceof List) {
                result.put("count", ((List<?>) groupData).size());
                result.put("data", groupData);
            } else if (groupData instanceof Map) {
                // 处理 Map<String, List<...>> 结构，展平为列表
                Map<?, ?> mapData = (Map<?, ?>) groupData;
                boolean hasArrayValues = mapData.values().stream().anyMatch(v -> v instanceof List);
                if (hasArrayValues) {
                    List<Object> flatList = new ArrayList<>();
                    for (Object val : mapData.values()) {
                        if (val instanceof List) {
                            flatList.addAll((List<?>) val);
                        }
                    }
                    result.put("count", flatList.size());
                    result.put("data", flatList);
                } else {
                    result.put("data", groupData);
                }
            } else {
                result.put("data", groupData);
            }
            
        } catch (Exception e) {
            log.error("读取分组数据失败: {}", filePath, e);
            result.put("ok", false);
            result.put("error", "读取数据失败: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> updateRecord(
            @RequestParam String fileName,
            @RequestParam String group,
            @RequestParam String idField,
            @RequestParam String idValue,
            @RequestBody Map<String, Object> updates,
            @RequestParam(required = false) String type) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        Path baseDir;
        try {
            baseDir = getBaseDir();
        } catch (IllegalStateException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
        
        String dataDir = getDataDirByType(type);
        if (dataDir == null || dataDir.isEmpty()) {
            result.put("ok", false);
            result.put("error", "「" + getTypeLabel(type) + "」未配置，请在 config.json 中设置");
            return ResponseEntity.ok(result);
        }
        
        Path targetDir = resolveDataDir(baseDir, dataDir);
        Path filePath = safeResolveFile(targetDir, fileName);
        
        if (!Files.exists(filePath)) {
            result.put("ok", false);
            result.put("error", "文件不存在");
            return ResponseEntity.ok(result);
        }
        
        try {
            Map<String, Object> fileData = FileUtil.readJson(filePath, mapType, new HashMap<>());
            Object groupData = fileData.get(group);
            
            boolean found = false;
            
            if (groupData instanceof List) {
                List<Map<String, Object>> records = (List<Map<String, Object>>) groupData;
                for (int i = 0; i < records.size(); i++) {
                    Map<String, Object> record = records.get(i);
                    if (idValue.equals(String.valueOf(record.get(idField)))) {
                        for (Map.Entry<String, Object> entry : updates.entrySet()) {
                            record.put(entry.getKey(), entry.getValue());
                        }
                        found = true;
                        break;
                    }
                }
            } else if (groupData instanceof Map) {
                Map<?, ?> mapData = (Map<?, ?>) groupData;
                for (Object val : mapData.values()) {
                    if (val instanceof List) {
                        List<Map<String, Object>> records = (List<Map<String, Object>>) val;
                        for (int i = 0; i < records.size(); i++) {
                            Map<String, Object> record = records.get(i);
                            if (idValue.equals(String.valueOf(record.get(idField)))) {
                                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                                    record.put(entry.getKey(), entry.getValue());
                                }
                                found = true;
                                break;
                            }
                        }
                        if (found) break;
                    }
                }
            } else {
                result.put("ok", false);
                result.put("error", "分组不存在或不是数组类型");
                return ResponseEntity.ok(result);
            }
            
            if (!found) {
                result.put("ok", false);
                result.put("error", "未找到记录: " + idField + "=" + idValue);
                return ResponseEntity.ok(result);
            }
            
            FileUtil.writeJson(filePath, fileData);
            result.put("ok", true);
            result.put("message", "更新成功");
            
        } catch (Exception e) {
            log.error("更新数据失败: {}", filePath, e);
            result.put("ok", false);
            result.put("error", "更新失败: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/add", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> addRecord(
            @RequestParam String fileName,
            @RequestParam String group,
            @RequestBody Map<String, Object> record,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String subGroup) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        Path baseDir;
        try {
            baseDir = getBaseDir();
        } catch (IllegalStateException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
        
        String dataDir = getDataDirByType(type);
        if (dataDir == null || dataDir.isEmpty()) {
            result.put("ok", false);
            result.put("error", "「" + getTypeLabel(type) + "」未配置，请在 config.json 中设置");
            return ResponseEntity.ok(result);
        }
        
        Path targetDir = resolveDataDir(baseDir, dataDir);
        Path filePath = safeResolveFile(targetDir, fileName);
        
        if (!Files.exists(filePath)) {
            result.put("ok", false);
            result.put("error", "文件不存在");
            return ResponseEntity.ok(result);
        }
        
        try {
            Map<String, Object> fileData = FileUtil.readJson(filePath, mapType, new HashMap<>());
            Object groupData = fileData.get(group);
            
            boolean added = false;
            
            if (groupData instanceof List) {
                List<Map<String, Object>> records = (List<Map<String, Object>>) groupData;
                records.add(record);
                added = true;
            } else if (groupData instanceof Map) {
                Map<String, Object> mapData = (Map<String, Object>) groupData;
                
                if (subGroup != null && !subGroup.isEmpty()) {
                    Object subGroupData = mapData.get(subGroup);
                    if (subGroupData instanceof List) {
                        List<Map<String, Object>> records = (List<Map<String, Object>>) subGroupData;
                        records.add(record);
                        added = true;
                    }
                } else {
                    for (Map.Entry<String, Object> entry : mapData.entrySet()) {
                        if (entry.getValue() instanceof List) {
                            List<Map<String, Object>> records = (List<Map<String, Object>>) entry.getValue();
                            records.add(record);
                            added = true;
                            break;
                        }
                    }
                }
            } else {
                result.put("ok", false);
                result.put("error", "分组不存在或不是数组类型");
                return ResponseEntity.ok(result);
            }
            
            if (!added) {
                result.put("ok", false);
                result.put("error", "未能添加记录，请检查数据结构");
                return ResponseEntity.ok(result);
            }
            
            FileUtil.writeJson(filePath, fileData);
            result.put("ok", true);
            result.put("message", "添加成功");
            
        } catch (Exception e) {
            log.error("添加数据失败: {}", filePath, e);
            result.put("ok", false);
            result.put("error", "添加失败: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deleteRecord(
            @RequestParam String fileName,
            @RequestParam String group,
            @RequestParam String idField,
            @RequestParam String idValue,
            @RequestParam(required = false) String type) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        Path baseDir;
        try {
            baseDir = getBaseDir();
        } catch (IllegalStateException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
        
        String dataDir = getDataDirByType(type);
        if (dataDir == null || dataDir.isEmpty()) {
            result.put("ok", false);
            result.put("error", "「" + getTypeLabel(type) + "」未配置，请在 config.json 中设置");
            return ResponseEntity.ok(result);
        }
        
        Path targetDir = resolveDataDir(baseDir, dataDir);
        Path filePath = safeResolveFile(targetDir, fileName);
        
        if (!Files.exists(filePath)) {
            result.put("ok", false);
            result.put("error", "文件不存在");
            return ResponseEntity.ok(result);
        }
        
        try {
            Map<String, Object> fileData = FileUtil.readJson(filePath, mapType, new HashMap<>());
            Object groupData = fileData.get(group);
            
            boolean removed = false;
            
            if (groupData instanceof List) {
                List<Map<String, Object>> records = (List<Map<String, Object>>) groupData;
                removed = records.removeIf(r -> idValue.equals(String.valueOf(r.get(idField))));
            } else if (groupData instanceof Map) {
                Map<?, ?> mapData = (Map<?, ?>) groupData;
                for (Object val : mapData.values()) {
                    if (val instanceof List) {
                        List<Map<String, Object>> records = (List<Map<String, Object>>) val;
                        removed = records.removeIf(r -> idValue.equals(String.valueOf(r.get(idField))));
                        if (removed) break;
                    }
                }
            } else {
                result.put("ok", false);
                result.put("error", "分组不存在或不是数组类型");
                return ResponseEntity.ok(result);
            }
            
            if (!removed) {
                result.put("ok", false);
                result.put("error", "未找到记录: " + idField + "=" + idValue);
                return ResponseEntity.ok(result);
            }
            
            FileUtil.writeJson(filePath, fileData);
            result.put("ok", true);
            result.put("message", "删除成功");
            
        } catch (Exception e) {
            log.error("删除数据失败: {}", filePath, e);
            result.put("ok", false);
            result.put("error", "删除失败: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    private Path resolveDataDir(Path baseDir, String dataDir) {
        Path target = baseDir.resolve(dataDir).resolve("data").normalize();
        Path base = baseDir.normalize();
        if (!target.startsWith(base)) {
            throw new SecurityException("路径越界: " + target);
        }
        return target;
    }

    private Path safeResolveFile(Path dir, String fileName) {
        Path resolved = dir.resolve(fileName + ".json").normalize();
        if (!resolved.startsWith(dir.normalize())) {
            throw new SecurityException("文件名越界: " + fileName);
        }
        return resolved;
    }

    private Map<String, Object> extractGroups(Map<String, Object> data) {
        Map<String, Object> groups = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (e.getValue() instanceof List) {
                List<?> list = (List<?>) e.getValue();
                Map<String, Object> groupInfo = new LinkedHashMap<>();
                groupInfo.put("count", list.size());
                groupInfo.put("type", "array");
                groups.put(e.getKey(), groupInfo);
            } else if (e.getValue() instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) e.getValue();
                // 检查是否 Map 的值包含数组（例如 articles: { "账号名": [...] }）
                boolean hasArrayValues = map.values().stream().anyMatch(v -> v instanceof List);
                if (hasArrayValues) {
                    int totalCount = map.values().stream()
                        .filter(v -> v instanceof List)
                        .mapToInt(v -> ((List<?>) v).size())
                        .sum();
                    Map<String, Object> groupInfo = new LinkedHashMap<>();
                    groupInfo.put("count", totalCount);
                    groupInfo.put("type", "array");
                    groups.put(e.getKey(), groupInfo);
                } else {
                    Map<String, Object> groupInfo = new LinkedHashMap<>();
                    groupInfo.put("keys", new ArrayList<>(map.keySet()));
                    groupInfo.put("type", "object");
                    groups.put(e.getKey(), groupInfo);
                }
            }
        }

        return groups;
    }

    @GetMapping(value = "/column-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getColumnSettings(@RequestParam(required = false, defaultValue = "customer") String type) {
        Config config = configService.load();
        Map<String, Map<String, List<String>>> allSettings = config.getColumnSettings();
        if (allSettings == null) {
            allSettings = new HashMap<>();
        }
        Map<String, List<String>> typeSettings = allSettings.getOrDefault(type, new HashMap<>());
        return Map.of("ok", true, "settings", typeSettings);
    }

    @PostMapping(value = "/column-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveColumnSettings(
            @RequestParam(required = false, defaultValue = "customer") String type,
            @RequestBody Map<String, List<String>> settings) {
        try {
            Config config = configService.load();
            Map<String, Map<String, List<String>>> allSettings = config.getColumnSettings();
            if (allSettings == null) {
                allSettings = new HashMap<>();
            }
            allSettings.put(type, settings);
            config.setColumnSettings(allSettings);
            configService.save(config);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
