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
public class DirectoryDataService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryDataService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

    /** 读取 JSON 文件，兼容 Map 和 List 格式 */
    private Object readJsonFile(Path filePath) {
        if (!Files.exists(filePath)) return null;
        // 先尝试读取为 Map
        Map<String, Object> asMap = FileUtil.readJson(filePath, MAP_TYPE, null);
        if (asMap != null) return asMap;
        // 再尝试读取为 List
        List<Object> asList = FileUtil.readJson(filePath, LIST_TYPE, null);
        if (asList != null) return asList;
        return null;
    }

    /** 解析文件路径：先尝试直接路径 dir/file.json，再尝试 dir/data/file.json */
    private Path resolveJsonFile(Path baseDir, String dir, String fileName) {
        String file = fileName + ".json";
        // 尝试直接路径
        Path direct = dir.isEmpty() ? baseDir.resolve(file) : baseDir.resolve(dir).resolve(file);
        if (Files.isRegularFile(direct)) return direct;
        // 尝试 data 子目录
        Path dataDir = dir.isEmpty() ? baseDir.resolve("data") : baseDir.resolve(dir).resolve("data");
        Path inData = dataDir.resolve(file);
        if (Files.isRegularFile(inData)) return inData;
        // 返回直接路径（即使不存在，用于报错）
        return direct;
    }

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

    public Map<String, Object> getFileData(Path baseDir, String dir, String fileName) {
        Path filePath = resolveJsonFile(baseDir, dir, fileName);
        Object data = readJsonFile(filePath);
        if (data == null) return null;

        if (data instanceof List) {
            List<?> list = (List<?>) data;
            return Map.of("data", data, "groups", Map.of("数据", Map.of("count", list.size(), "type", "array")));
        }

        Map<String, Object> mapData = (Map<String, Object>) data;
        Map<String, Object> groups = extractGroups(mapData);
        return Map.of("data", mapData, "groups", groups);
    }

    public Map<String, Object> getGroupData(Path baseDir, String dir, String fileName, String group) {
        Path filePath = resolveJsonFile(baseDir, dir, fileName);
        Object data = readJsonFile(filePath);
        if (data == null) return null;

        // 当文件是顶层数组时，整个数组作为一个虚拟分组
        if (data instanceof List) {
            List<?> list = (List<?>) data;
            return Map.of("count", list.size(), "data", list);
        }

        Map<String, Object> mapData = (Map<String, Object>) data;
        Object groupData = mapData.get(group);
        if (groupData == null) return null;

        if (groupData instanceof List) {
            return Map.of("count", ((List<?>) groupData).size(), "data", groupData);
        } else if (groupData instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) groupData;
            boolean hasArrayValues = map.values().stream().anyMatch(v -> v instanceof List);
            if (hasArrayValues) {
                List<Object> flatList = new ArrayList<>();
                for (Object val : map.values()) {
                    if (val instanceof List) flatList.addAll((List<?>) val);
                }
                return Map.of("count", flatList.size(), "data", flatList);
            }
            return Map.of("data", groupData);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> addRecord(Path baseDir, String dir, String fileName, String group,
                                          Map<String, Object> record, String subGroup) {
        Path filePath = resolveJsonFile(baseDir, dir, fileName);
        if (!Files.exists(filePath)) return Map.of("ok", false, "error", "文件不存在");

        Object raw = readJsonFile(filePath);
        if (raw == null) return Map.of("ok", false, "error", "文件为空或格式错误");

        // 顶层数组: 直接添加
        if (raw instanceof List) {
            ((List<Object>) raw).add(record);
            FileUtil.writeJson(filePath, raw);
            return Map.of("ok", true, "message", "添加成功");
        }

        Map<String, Object> fileData = (Map<String, Object>) raw;
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
    public Map<String, Object> updateRecord(Path baseDir, String dir, String fileName, String group,
                                             String idField, String idValue,
                                             Map<String, Object> updates) {
        Path filePath = resolveJsonFile(baseDir, dir, fileName);
        if (!Files.exists(filePath)) return Map.of("ok", false, "error", "文件不存在");

        Object raw = readJsonFile(filePath);
        if (raw == null) return Map.of("ok", false, "error", "文件为空或格式错误");

        // 顶层数组: 在数组中查找并更新
        if (raw instanceof List) {
            for (Map<String, Object> r : (List<Map<String, Object>>) raw) {
                if (idValue.equals(String.valueOf(r.get(idField)))) {
                    r.putAll(updates);
                    FileUtil.writeJson(filePath, raw);
                    return Map.of("ok", true, "message", "更新成功");
                }
            }
            return Map.of("ok", false, "error", "未找到记录");
        }

        Map<String, Object> fileData = (Map<String, Object>) raw;
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
    public Map<String, Object> deleteRecord(Path baseDir, String dir, String fileName, String group,
                                             String idField, String idValue) {
        Path filePath = resolveJsonFile(baseDir, dir, fileName);
        if (!Files.exists(filePath)) return Map.of("ok", false, "error", "文件不存在");

        Object raw = readJsonFile(filePath);
        if (raw == null) return Map.of("ok", false, "error", "文件为空或格式错误");

        // 顶层数组: 在数组中查找并删除
        if (raw instanceof List) {
            boolean removed = ((List<Map<String, Object>>) raw)
                .removeIf(r -> idValue.equals(String.valueOf(r.get(idField))));
            if (!removed) return Map.of("ok", false, "error", "未找到记录");
            FileUtil.writeJson(filePath, raw);
            return Map.of("ok", true, "message", "删除成功");
        }

        Map<String, Object> fileData = (Map<String, Object>) raw;
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
