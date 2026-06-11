package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ModuleDataService {

    private static final Logger log = LoggerFactory.getLogger(ModuleDataService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public List<FileInfo> listJsonFiles(Path dataDir) {
        if (!Files.exists(dataDir)) return List.of();
        try (var stream = Files.list(dataDir)) {
            return stream
                .filter(f -> f.getFileName().toString().endsWith(".json"))
                .sorted()
                .map(f -> {
                    FileInfo info = new FileInfo();
                    info.fileName = f.getFileName().toString().replace(".json", "");
                    try {
                        info.size = Files.size(f);
                        info.lastModified = Files.getLastModifiedTime(f).toMillis();
                    } catch (Exception ignored) {}
                    return info;
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list JSON files in {}", dataDir, e);
            return List.of();
        }
    }

    public Map<String, Object> getFileData(Path dataDir, String fileName) {
        Path filePath = dataDir.resolve(fileName + ".json");
        if (!Files.exists(filePath)) return null;
        Map<String, Object> data = FileUtil.readJson(filePath, MAP_TYPE, new LinkedHashMap<>());
        Map<String, Object> groups = extractGroups(data);
        return Map.of("data", data, "groups", groups);
    }

    public Map<String, Object> getGroupData(Path dataDir, String fileName, String group) {
        Path filePath = dataDir.resolve(fileName + ".json");
        if (!Files.exists(filePath)) return null;
        Map<String, Object> data = FileUtil.readJson(filePath, MAP_TYPE, new LinkedHashMap<>());
        Object groupData = data.get(group);
        if (groupData == null) return null;

        if (groupData instanceof List) {
            return Map.of("count", ((List<?>) groupData).size(), "data", groupData);
        } else if (groupData instanceof Map) {
            Map<?, ?> mapData = (Map<?, ?>) groupData;
            boolean hasArrayValues = mapData.values().stream().anyMatch(v -> v instanceof List);
            if (hasArrayValues) {
                List<Object> flatList = new ArrayList<>();
                for (Object val : mapData.values()) {
                    if (val instanceof List) flatList.addAll((List<?>) val);
                }
                return Map.of("count", flatList.size(), "data", flatList);
            }
            return Map.of("data", groupData);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> addRecord(Path dataDir, String fileName, String group,
                                          Map<String, Object> record, String subGroup) {
        Path filePath = dataDir.resolve(fileName + ".json");
        if (!Files.exists(filePath)) return Map.of("ok", false, "error", "文件不存在");

        Map<String, Object> fileData = FileUtil.readJson(filePath, MAP_TYPE, new LinkedHashMap<>());
        Object groupData = fileData.get(group);
        boolean added = false;

        if (groupData instanceof List) {
            ((List<Map<String, Object>>) groupData).add(record);
            added = true;
        } else if (groupData instanceof Map) {
            Map<String, Object> mapData = (Map<String, Object>) groupData;
            if (subGroup != null && !subGroup.isEmpty()) {
                Object subData = mapData.get(subGroup);
                if (subData instanceof List) {
                    ((List<Map<String, Object>>) subData).add(record);
                    added = true;
                }
            } else {
                for (Map.Entry<String, Object> e : mapData.entrySet()) {
                    if (e.getValue() instanceof List) {
                        ((List<Map<String, Object>>) e.getValue()).add(record);
                        added = true;
                        break;
                    }
                }
            }
        }

        if (!added) return Map.of("ok", false, "error", "分组不存在或不是数组类型");

        FileUtil.writeJson(filePath, fileData);
        return Map.of("ok", true, "message", "添加成功");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> updateRecord(Path dataDir, String fileName, String group,
                                             String idField, String idValue,
                                             Map<String, Object> updates) {
        Path filePath = dataDir.resolve(fileName + ".json");
        if (!Files.exists(filePath)) return Map.of("ok", false, "error", "文件不存在");

        Map<String, Object> fileData = FileUtil.readJson(filePath, MAP_TYPE, new LinkedHashMap<>());
        Object groupData = fileData.get(group);
        boolean found = false;

        if (groupData instanceof List) {
            for (Map<String, Object> r : (List<Map<String, Object>>) groupData) {
                if (idValue.equals(String.valueOf(r.get(idField)))) {
                    r.putAll(updates);
                    found = true;
                    break;
                }
            }
        } else if (groupData instanceof Map) {
            for (Object val : ((Map<?, ?>) groupData).values()) {
                if (val instanceof List) {
                    for (Map<String, Object> r : (List<Map<String, Object>>) val) {
                        if (idValue.equals(String.valueOf(r.get(idField)))) {
                            r.putAll(updates);
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
            }
        }

        if (!found) return Map.of("ok", false, "error", "未找到记录");
        FileUtil.writeJson(filePath, fileData);
        return Map.of("ok", true, "message", "更新成功");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> deleteRecord(Path dataDir, String fileName, String group,
                                             String idField, String idValue) {
        Path filePath = dataDir.resolve(fileName + ".json");
        if (!Files.exists(filePath)) return Map.of("ok", false, "error", "文件不存在");

        Map<String, Object> fileData = FileUtil.readJson(filePath, MAP_TYPE, new LinkedHashMap<>());
        Object groupData = fileData.get(group);
        boolean removed = false;

        if (groupData instanceof List) {
            removed = ((List<Map<String, Object>>) groupData)
                .removeIf(r -> idValue.equals(String.valueOf(r.get(idField))));
        } else if (groupData instanceof Map) {
            for (Object val : ((Map<?, ?>) groupData).values()) {
                if (val instanceof List) {
                    removed = ((List<Map<String, Object>>) val)
                        .removeIf(r -> idValue.equals(String.valueOf(r.get(idField))));
                    if (removed) break;
                }
            }
        }

        if (!removed) return Map.of("ok", false, "error", "未找到记录");
        FileUtil.writeJson(filePath, fileData);
        return Map.of("ok", true, "message", "删除成功");
    }

    private Map<String, Object> extractGroups(Map<String, Object> data) {
        Map<String, Object> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (e.getValue() instanceof List) {
                groups.put(e.getKey(), Map.of("count", ((List<?>) e.getValue()).size(), "type", "array"));
            } else if (e.getValue() instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) e.getValue();
                boolean hasArrayValues = map.values().stream().anyMatch(v -> v instanceof List);
                if (hasArrayValues) {
                    int total = map.values().stream()
                        .filter(v -> v instanceof List)
                        .mapToInt(v -> ((List<?>) v).size()).sum();
                    groups.put(e.getKey(), Map.of("count", total, "type", "array"));
                } else {
                    groups.put(e.getKey(), Map.of("keys", new ArrayList<>(map.keySet()), "type", "object"));
                }
            }
        }
        return groups;
    }

    public static class FileInfo {
        public String fileName;
        public long size;
        public long lastModified;
    }
}
