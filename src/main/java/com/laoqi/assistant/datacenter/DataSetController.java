package com.laoqi.assistant.datacenter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.datacenter.model.*;
import com.laoqi.assistant.service.OpenCodeService;
import com.laoqi.assistant.service.ModuleService;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.model.ModuleDefinition;
import com.laoqi.assistant.util.FileUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/datacenter")
public class DataSetController {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");

    private final DataSetService dataSetService;
    private final DataSetImportService importService;
    private final OpenCodeService openCodeService;
    private final ModuleService moduleService;
    private final ConfigService configService;

    public DataSetController(DataSetService dataSetService,
                             DataSetImportService importService,
                             OpenCodeService openCodeService,
                             ModuleService moduleService,
                             ConfigService configService) {
        this.dataSetService = dataSetService;
        this.importService = importService;
        this.openCodeService = openCodeService;
        this.moduleService = moduleService;
        this.configService = configService;
    }

    @GetMapping("/datasets")
    public ResponseEntity<Map<String, Object>> listDatasets() {
        return ResponseEntity.ok(Map.of("ok", true, "data", dataSetService.getAllDatasets()));
    }

    @GetMapping("/datasets/{id}")
    public ResponseEntity<Map<String, Object>> getDataset(@PathVariable String id) {
        DataSet ds = dataSetService.getDataset(id);
        if (ds == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "数据集不存在"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", ds));
    }

    @PostMapping("/datasets")
    public ResponseEntity<Map<String, Object>> createDataset(@RequestBody DataSet ds) {
        try {
            DataSet created = dataSetService.createDataset(ds);
            return ResponseEntity.ok(Map.of("ok", true, "data", created));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/datasets/{id}")
    public ResponseEntity<Map<String, Object>> updateDataset(@PathVariable String id, @RequestBody DataSet ds) {
        DataSet updated = dataSetService.updateDataset(id, ds);
        if (updated == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "数据集不存在"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", updated));
    }

    @DeleteMapping("/datasets/{id}")
    public ResponseEntity<Map<String, Object>> deleteDataset(@PathVariable String id) {
        boolean deleted = dataSetService.deleteDataset(id);
        if (!deleted) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "数据集不存在"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/datasets/{id}/records")
    public ResponseEntity<Map<String, Object>> getRecords(
            @PathVariable String id,
            @RequestParam(required = false) String keyword) {
        try {
            List<Map<String, Object>> records;
            if (keyword != null && !keyword.isBlank()) {
                records = dataSetService.searchRecords(id, keyword);
            } else {
                records = dataSetService.loadRecords(id);
            }
            return ResponseEntity.ok(Map.of("ok", true, "data", records, "total", records.size()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/datasets/{id}/records/{recordId}")
    public ResponseEntity<Map<String, Object>> deleteRecord(@PathVariable String id, @PathVariable String recordId) {
        boolean deleted = dataSetService.deleteRecord(id, recordId);
        if (!deleted) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "记录不存在"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/datasets/{id}/import/excel")
    public ResponseEntity<Map<String, Object>> importExcel(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mappingJson) {
        try {
            DataSet ds = dataSetService.getDataset(id);
            if (ds == null) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "数据集不存在"));
            }

            Map<String, String> mapping = new HashMap<>();
            if (mappingJson != null && !mappingJson.isEmpty()) {
                mapping = mapper.readValue(mappingJson, new TypeReference<Map<String, String>>() {});
            }

            List<Map<String, Object>> records;
            if (mapping.isEmpty()) {
                records = importService.importExcelWithAutoDetect(file, ds.getSchema());
            } else {
                records = importService.importExcel(file, mapping);
            }

            int count = dataSetService.addRecords(id, records, "excel");
            return ResponseEntity.ok(Map.of("ok", true, "count", count, "message", "成功导入 " + count + " 条记录"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "导入失败: " + e.getMessage()));
        }
    }

    @PostMapping("/datasets/import/excel/preview")
    public ResponseEntity<Map<String, Object>> previewExcel(@RequestParam("file") MultipartFile file) {
        try {
            DataSetImportService.ExcelPreview preview = importService.previewExcel(file);
            return ResponseEntity.ok(Map.of("ok", true, "data", preview));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "预览失败: " + e.getMessage()));
        }
    }

    @PostMapping("/datasets/{id}/import/json")
    public ResponseEntity<Map<String, Object>> importJson(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        try {
            DataSet ds = dataSetService.getDataset(id);
            if (ds == null) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "数据集不存在"));
            }

            Object dataObj = body.get("data");
            List<Map<String, Object>> records = new ArrayList<>();

            if (dataObj instanceof List<?> dataList) {
                for (Object item : dataList) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> record = new HashMap<>();
                        map.forEach((k, v) -> record.put(String.valueOf(k), v));
                        records.add(record);
                    }
                }
            } else if (dataObj instanceof Map<?, ?> map) {
                Map<String, Object> record = new HashMap<>();
                map.forEach((k, v) -> record.put(String.valueOf(k), v));
                records.add(record);
            }

            int count = dataSetService.addRecords(id, records, "manual");
            return ResponseEntity.ok(Map.of("ok", true, "count", count, "message", "成功导入 " + count + " 条记录"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "导入失败: " + e.getMessage()));
        }
    }

    @PostMapping("/datasets/{id}/import/url")
    public ResponseEntity<Map<String, Object>> importFromUrl(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        try {
            DataSet ds = dataSetService.getDataset(id);
            if (ds == null) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "数据集不存在"));
            }

            String url = body.get("url");
            if (url == null || url.isBlank()) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "URL不能为空"));
            }

            if (!openCodeService.isHealthy()) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "AI服务未运行，请先启动 opencode serve"));
            }

            String prompt = "请访问以下URL，提取页面中的结构化数据。\n" +
                    "URL: " + url + "\n\n" +
                    "**重要：不要保存到文件，不要写入任何文件。只在回复中直接输出JSON数据。**\n\n" +
                    "输出格式要求：\n" +
                    "1. 直接输出JSON数组，不要包含其他文字说明\n" +
                    "2. 用 ```json 包裹\n" +
                    "3. 每个元素是一个对象，包含页面中的数据字段";

            if (ds.getSchema() != null && ds.getSchema().getFields() != null && !ds.getSchema().getFields().isEmpty()) {
                prompt += "\n\n必须使用以下字段名（保持英文）：\n";
                for (DataField field : ds.getSchema().getFields()) {
                    prompt += "- " + field.getName();
                    if (field.getDisplayName() != null) {
                        prompt += "（" + field.getDisplayName() + "）";
                    }
                    prompt += "\n";
                }
                prompt += "\n示例格式：[{\"title\":\"文章标题\",\"views\":123}, ...]";
            }

            String sessionId = openCodeService.findIdleSession();
            if (sessionId == null) {
                sessionId = openCodeService.createSession("URL数据获取");
            }

            String rawResponse = openCodeService.sendMessage(sessionId, prompt);
            String parsedData = extractJsonFromResponse(rawResponse);

            Object jsonData = mapper.readValue(parsedData, Object.class);
            List<Map<String, Object>> records = new ArrayList<>();

            if (jsonData instanceof List<?> dataList) {
                for (Object item : dataList) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> record = new HashMap<>();
                        map.forEach((k, v) -> record.put(String.valueOf(k), v));
                        records.add(record);
                    }
                }
            }

            if (records.isEmpty()) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "未能从URL提取到有效数据"));
            }

            int count = dataSetService.addRecords(id, records, "url");
            return ResponseEntity.ok(Map.of("ok", true, "count", count, "message", "成功从URL导入 " + count + " 条记录"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "URL导入失败: " + e.getMessage()));
        }
    }

    private String extractJsonFromResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) return "[]";

        Matcher m = JSON_BLOCK.matcher(rawResponse);
        if (m.find()) {
            return m.group(1).trim();
        }

        int start = rawResponse.indexOf('[');
        int end = rawResponse.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return rawResponse.substring(start, end + 1);
        }

        int startObj = rawResponse.indexOf('{');
        int endObj = rawResponse.lastIndexOf('}');
        if (startObj >= 0 && endObj > startObj) {
            return "[" + rawResponse.substring(startObj, endObj + 1) + "]";
        }

        return "[]";
    }

    @GetMapping("/modules")
    public ResponseEntity<Map<String, Object>> getModules() {
        List<ModuleDefinition> modules = moduleService.getModules();
        List<Map<String, String>> result = new ArrayList<>();
        for (ModuleDefinition mod : modules) {
            result.add(Map.of(
                "id", mod.getId() != null ? mod.getId() : "",
                "name", mod.getName() != null ? mod.getName() : "",
                "dir", mod.getDir() != null ? mod.getDir() : ""
            ));
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", result));
    }

    @PostMapping("/datasets/{id}/export")
    public ResponseEntity<Map<String, Object>> exportToModule(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        try {
            DataSet ds = dataSetService.getDataset(id);
            if (ds == null) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "数据集不存在"));
            }

            String moduleId = (String) body.get("moduleId");
            if (moduleId == null || moduleId.isEmpty()) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "请选择目标模块"));
            }

            ModuleDefinition mod = moduleService.getModule(moduleId);
            if (mod == null) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "模块不存在: " + moduleId));
            }

            List<Map<String, Object>> allRecords = dataSetService.loadRecords(id);

            @SuppressWarnings("unchecked")
            List<String> recordIds = (List<String>) body.get("recordIds");
            List<Map<String, Object>> records;
            if (recordIds != null && !recordIds.isEmpty()) {
                Set<String> idSet = new HashSet<>(recordIds);
                records = allRecords.stream()
                    .filter(r -> idSet.contains(r.get("_id")))
                    .toList();
            } else {
                records = allRecords;
            }

            if (records.isEmpty()) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "没有可导出的数据"));
            }

            String customPrompt = (String) body.get("prompt");

            Path dataDir = moduleService.getModuleDataDir(mod);
            String sampleData = readSampleData(dataDir, mod);

            String prompt = buildExportPrompt(records, ds, mod, sampleData, customPrompt);

            if (!openCodeService.isHealthy()) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "AI服务未运行"));
            }

            String sessionId = openCodeService.findIdleSession();
            if (sessionId == null) {
                sessionId = openCodeService.createSession("数据导出");
            }

            String rawResponse = openCodeService.sendMessage(sessionId, prompt);
            String jsonData = extractJsonFromResponse(rawResponse);

            Object parsed = mapper.readValue(jsonData, Object.class);
            String fileName = "data_export_" + System.currentTimeMillis() + ".json";
            Path filePath = dataDir.resolve(fileName);
            FileUtil.writeJson(filePath, parsed);

            return ResponseEntity.ok(Map.of(
                "ok", true,
                "count", records.size(),
                "file", fileName,
                "module", mod.getName(),
                "message", "成功导出 " + records.size() + " 条记录到 " + mod.getName()
            ));

        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "导出失败: " + e.getMessage()));
        }
    }

    private String readSampleData(Path dataDir, ModuleDefinition mod) {
        StringBuilder sb = new StringBuilder();
        if (mod.getDataFiles() != null) {
            for (String fileName : mod.getDataFiles()) {
                Path file = dataDir.resolve(fileName);
                if (FileUtil.exists(file)) {
                    String content = FileUtil.readText(file);
                    if (!content.isEmpty()) {
                        sb.append("--- ").append(fileName).append(" ---\n");
                        sb.append(content, 0, Math.min(content.length(), 2000));
                        sb.append("\n\n");
                    }
                }
            }
        }
        if (sb.isEmpty() && FileUtil.exists(dataDir)) {
            try {
                var files = java.nio.file.Files.list(dataDir);
                files.filter(p -> p.toString().endsWith(".json"))
                    .limit(2)
                    .forEach(p -> {
                        String content = FileUtil.readText(p);
                        sb.append("--- ").append(p.getFileName()).append(" ---\n");
                        sb.append(content, 0, Math.min(content.length(), 2000));
                        sb.append("\n\n");
                    });
            } catch (Exception e) {}
        }
        return sb.toString();
    }

    private String buildExportPrompt(List<Map<String, Object>> records, DataSet ds,
                                      ModuleDefinition mod, String sampleData, String customPrompt) {
        StringBuilder sb = new StringBuilder();

        if (customPrompt != null && !customPrompt.isBlank()) {
            sb.append(customPrompt).append("\n\n");
        } else {
            sb.append("请将以下数据转换为业务数据格式。\n\n");
        }

        sb.append("目标模块: ").append(mod.getName()).append("\n");
        sb.append("目标目录: ").append(mod.getDir()).append("/data/\n\n");

        if (!sampleData.isEmpty()) {
            sb.append("现有业务数据格式样例（请保持一致）：\n");
            sb.append("```\n").append(sampleData).append("\n```\n\n");
        }

        sb.append("待导出数据（共").append(records.size()).append("条）：\n");
        try {
            String recordsJson = mapper.writeValueAsString(records);
            sb.append("```json\n");
            sb.append(recordsJson, 0, Math.min(recordsJson.length(), 8000));
            if (recordsJson.length() > 8000) sb.append("...");
            sb.append("\n```\n\n");
        } catch (Exception e) {
            sb.append("（数据序列化失败）\n\n");
        }

        sb.append("要求：\n");
        sb.append("1. 参考现有业务数据格式，将待导出数据转换为相同格式\n");
        sb.append("2. 直接输出JSON数组，用 ```json 包裹\n");
        sb.append("3. 不要输出其他说明文字\n");

        return sb.toString();
    }
}
