package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.MediaDataCollectorService;
import com.laoqi.assistant.service.OperationsService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;

@Controller
public class OperationsController {

    private static final Logger log = LoggerFactory.getLogger(OperationsController.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final OperationsService operationsService;
    private final LogService logService;
    private final MediaDataCollectorService mediaDataCollectorService;
    private final ConfigService configService;

    public OperationsController(OperationsService operationsService, LogService logService,
                                MediaDataCollectorService mediaDataCollectorService,
                                ConfigService configService) {
        this.operationsService = operationsService;
        this.logService = logService;
        this.mediaDataCollectorService = mediaDataCollectorService;
        this.configService = configService;
    }

    @GetMapping("/operations")
    public String operationsPage() {
        return "operations";
    }

    @GetMapping("/api/operations/ai-report")
    @ResponseBody
    public Map<String, Object> getAiReport() {
        String report = operationsService.readTodayAnalysis();
        if (report != null && !report.isEmpty()) {
            return Map.of("ok", true, "report", report);
        }
        return Map.of("ok", false, "report", "");
    }

    @GetMapping("/api/operations/ai-analysis")
    public SseEmitter aiAnalysis(@RequestParam(required = false, defaultValue = "false") boolean force) {
        SseEmitter emitter = new SseEmitter(300_000L);

        // Check cache first (non-forced)
        if (!force) {
            String cached = operationsService.getCachedAnalysis();
            if (cached != null) {
                try {
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                            Map.of("type", "text", "content", cached))));
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("type", "done"))));
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Map<String, Object> statusEvent = Map.of("type", "status", "content", "⏳ AI 正在分析运营数据...");
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(statusEvent)));

                String result = operationsService.aiAnalyze(force);

                Map<String, Object> textEvent = Map.of("type", "text", "content", result);
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(textEvent)));

                Map<String, Object> doneEvent = Map.of("type", "done");
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(doneEvent)));
                emitter.complete();
            } catch (Exception e) {
                try {
                    Map<String, Object> errorEvent = Map.of("type", "error", "content", "AI 分析失败: " + e.getMessage());
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(errorEvent)));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });

        return emitter;
    }

    @PostMapping("/api/operations/collect")
    @ResponseBody
    public Map<String, Object> triggerCollect() {
        logService.add("运营数据", "手动触发采集", "");
        return mediaDataCollectorService.collectSync();
    }
    
    @PostMapping("/api/operations/test-scheduled-collect")
    @ResponseBody
    public Map<String, Object> testScheduledCollect() {
        logService.add("运营数据", "测试定时采集", "");
        Config config = configService.load();
        return mediaDataCollectorService.collectSync();
    }

    @GetMapping("/api/operations/collect/preview")
    @ResponseBody
    public Map<String, Object> previewCollect() {
        return mediaDataCollectorService.dryRun();
    }

    @PostMapping("/api/operations/request-data")
    @ResponseBody
    public Map<String, Object> requestData() {
        logService.add("运营数据", "手动请求数据", "");
        return mediaDataCollectorService.requestSync();
    }

    @PostMapping("/api/operations/import-excel")
    @ResponseBody
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file,
                                           @RequestParam("account") String account) {
        if (file.isEmpty()) {
            return Map.of("ok", false, "error", "请选择文件");
        }
        try {
            var result = mediaDataCollectorService.importWechatExcel(file.getInputStream(), account);
            logService.add("运营数据", "Excel导入", result.getOrDefault("error", "成功").toString());
            return result;
        } catch (Exception e) {
            log.error("Excel导入失败", e);
            return Map.of("ok", false, "error", "文件读取失败: " + e.getMessage());
        }
    }

    @GetMapping("/api/operations/last-collect")
    @ResponseBody
    public Map<String, Object> getLastCollectTime() {
        String time = mediaDataCollectorService.getLastCollectTime();
        return Map.of("ok", true, "lastCollectTime", time != null ? time : "从未采集");
    }

    @PostMapping("/api/operations/save-record")
    @ResponseBody
    public Map<String, Object> saveRecord(
            @RequestParam String recordType,
            @RequestParam Map<String, String> params) {
        try {
            Config config = configService.load();
            String baseDir = config.getBaseDir();
            String opsDir = config.getOperationsDataDir();
            if (baseDir == null || baseDir.isEmpty() || opsDir == null || opsDir.isEmpty()) {
                return Map.of("ok", false, "error", "运营数据目录未配置");
            }
            Path targetDir = Paths.get(baseDir).resolve(opsDir).resolve("data");

            switch (recordType) {
                case "article":
                    return saveArticle(targetDir, params);
                case "stats":
                    return saveDailyStats(targetDir, params);
                case "account":
                    return saveAccount(targetDir, params);
                default:
                    return Map.of("ok", false, "error", "未知的记录类型: " + recordType);
            }
        } catch (Exception e) {
            log.error("保存记录失败", e);
            return Map.of("ok", false, "error", "保存失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saveArticle(Path dataDir, Map<String, String> params) {
        Path filePath = dataDir.resolve("自媒体文章.json");
        Map<String, Object> fileData = FileUtil.readJson(filePath, Map.class, new LinkedHashMap<>());

        String group = params.get("accountName");
        if (group == null || group.isEmpty()) {
            return Map.of("ok", false, "error", "账号不能为空");
        }

        List<Map<String, Object>> records = (List<Map<String, Object>>) fileData.computeIfAbsent(group, k -> new ArrayList<>());

        // Generate next article ID
        int maxId = 0;
        for (Map<String, Object> r : records) {
            Object idObj = r.get("文章id");
            if (idObj != null) {
                try {
                    int id = Integer.parseInt(idObj.toString());
                    if (id > maxId) maxId = id;
                } catch (NumberFormatException ignored) {}
            }
        }
        String newId = String.format("%02d", maxId + 1);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("文章id", newId);
        record.put("文章名", params.getOrDefault("topic", ""));
        record.put("日期", params.getOrDefault("publishDate", ""));
        record.put("阅读", parseInt(params.get("reads"), 0));
        record.put("点赞", parseInt(params.get("likes"), 0));
        record.put("转发收藏", parseInt(params.get("shareFav"), 0));
        record.put("推荐", parseInt(params.get("recommend"), 0));
        record.put("评论", parseInt(params.get("comments"), 0));

        records.add(record);
        FileUtil.writeJson(filePath, fileData);
        log.info("文章记录已保存: group={}, 文章名={}", group, record.get("文章名"));

        return Map.of("ok", true, "message", "文章添加成功");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saveDailyStats(Path dataDir, Map<String, String> params) {
        Path filePath = dataDir.resolve("自媒体日数据.json");
        Map<String, Object> fileData = FileUtil.readJson(filePath, Map.class, new LinkedHashMap<>());

        String group = params.get("accountName");
        if (group == null || group.isEmpty()) {
            return Map.of("ok", false, "error", "账号不能为空");
        }

        List<Map<String, Object>> records = (List<Map<String, Object>>) fileData.computeIfAbsent(group, k -> new ArrayList<>());

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("日期", params.getOrDefault("date", ""));
        record.put("粉丝", parseInt(params.get("fans"), 0));
        record.put("阅读", parseInt(params.get("reads"), 0));
        record.put("分享收藏", parseInt(params.get("shareFav"), 0));
        record.put("推荐", parseDouble(params.get("sourceRecommend"), 0.0));
        record.put("搜一搜", parseDouble(params.get("sourceSearch"), 0.0));
        record.put("主页", parseDouble(params.get("sourceHome"), 0.0));
        record.put("消息", parseDouble(params.get("sourceMessage"), 0.0));
        record.put("聊天会话", parseDouble(params.get("sourceChat"), 0.0));
        record.put("朋友圈", parseDouble(params.get("sourceMoments"), 0.0));
        record.put("其他", parseDouble(params.get("sourceOther"), 0.0));

        records.add(record);
        FileUtil.writeJson(filePath, fileData);
        log.info("日统计已保存: group={}, 日期={}", group, record.get("日期"));

        return Map.of("ok", true, "message", "日统计添加成功");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saveAccount(Path dataDir, Map<String, String> params) {
        Path filePath = dataDir.resolve("自媒体账号.json");
        Map<String, Object> fileData = FileUtil.readJson(filePath, Map.class, new LinkedHashMap<>());

        String group = params.get("accountName");
        if (group == null || group.isEmpty()) {
            return Map.of("ok", false, "error", "账号名不能为空");
        }

        List<Map<String, Object>> records = (List<Map<String, Object>>) fileData.computeIfAbsent(group, k -> new ArrayList<>());

        // Generate next account ID (format: P01, P02...)
        int maxId = 0;
        for (Map<String, Object> r : records) {
            Object idObj = r.get("账号ID");
            if (idObj != null) {
                String idStr = idObj.toString().replaceAll("[^0-9]", "");
                if (!idStr.isEmpty()) {
                    try {
                        int id = Integer.parseInt(idStr);
                        if (id > maxId) maxId = id;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        String newId = "P" + String.format("%02d", maxId + 1);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("账号ID", newId);
        record.put("平台", params.getOrDefault("platformKey", ""));
        record.put("名称", params.getOrDefault("name", group));
        record.put("粉丝", parseInt(params.get("fans"), 0));
        String link = params.get("link");
        if (link != null && !link.isEmpty()) {
            record.put("链接", link);
        }
        record.put("总访问", parseInt(params.get("totalViews"), 0));
        record.put("文章数", parseInt(params.get("totalArticles"), 0));
        record.put("更新日期", TimeUtil.todayStr());

        records.add(record);
        FileUtil.writeJson(filePath, fileData);
        log.info("账号已保存: group={}, 平台={}", group, record.get("平台"));

        return Map.of("ok", true, "message", "账号添加成功");
    }

    private int parseInt(String val, int defaultVal) {
        if (val == null || val.isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private double parseDouble(String val, double defaultVal) {
        if (val == null || val.isEmpty()) return defaultVal;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

}