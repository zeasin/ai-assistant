package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.model.OperationsData;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.MediaDataCollectorService;
import com.laoqi.assistant.service.OperationsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.Executors;

@Controller
public class OperationsController {

    private final OperationsService operationsService;
    private final LogService logService;
    private final MediaDataCollectorService mediaDataCollectorService;
    private static final ObjectMapper mapper = new ObjectMapper();

    public OperationsController(OperationsService operationsService, LogService logService,
                                MediaDataCollectorService mediaDataCollectorService) {
        this.operationsService = operationsService;
        this.logService = logService;
        this.mediaDataCollectorService = mediaDataCollectorService;
    }

    @GetMapping("/operations")
    public String operationsPage(Model model) {
        try {
            OperationsData.Root data = operationsService.loadData();
            String lastUpdated = data.meta != null ?
                    (String) data.meta.getOrDefault("lastUpdated", "未知") : "未知";

            // Convert POJOs to Maps for dynamic template rendering
            Map<String, List<Map<String, Object>>> dailyStatsMaps = new LinkedHashMap<>();
            if (data.dailyStats != null) {
                for (var entry : data.dailyStats.entrySet()) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (var ds : entry.getValue()) {
                        list.add(mapper.convertValue(ds, Map.class));
                    }
                    dailyStatsMaps.put(entry.getKey(), list);
                }
            }

            Map<String, Map<String, Map<String, Object>>> accountsMaps = new LinkedHashMap<>();
            if (data.accounts != null) {
                for (var accEntry : data.accounts.entrySet()) {
                    Map<String, Map<String, Object>> platforms = new LinkedHashMap<>();
                    for (var platEntry : accEntry.getValue().entrySet()) {
                        if (platEntry.getValue() instanceof Map) {
                            try {
                                Map<String, Object> info = mapper.convertValue(
                                        platEntry.getValue(), Map.class);
                                platforms.put(platEntry.getKey(), info);
                            } catch (Exception ignored) {}
                        }
                    }
                    accountsMaps.put(accEntry.getKey(), platforms);
                }
            }

            Map<String, List<Map<String, Object>>> articlesMaps = new LinkedHashMap<>();
            if (data.articles != null) {
                for (var entry : data.articles.entrySet()) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (var a : entry.getValue()) {
                        list.add(mapper.convertValue(a, Map.class));
                    }
                    articlesMaps.put(entry.getKey(), list);
                }
            }

            model.addAttribute("last_updated", lastUpdated);
            model.addAttribute("accounts", accountsMaps);
            model.addAttribute("articles", articlesMaps);
            model.addAttribute("daily_stats", dailyStatsMaps);

            OperationsData.AnalysisResult analysis = operationsService.analyze(data.articles);
            model.addAttribute("analysis", analysis);
        } catch (Exception e) {
            logService.add("运营看板", "失败", e.getMessage());
            model.addAttribute("last_updated", "加载失败");
            model.addAttribute("accounts", Map.of());
            model.addAttribute("articles", Map.of());
            model.addAttribute("daily_stats", Map.of());
        }
        return "operations";
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

    @PostMapping("/api/operations/article/add")
    @ResponseBody
    public Map<String, Object> addArticle(
            @RequestParam String accountName,
            @RequestParam String topic,
            @RequestParam(required = false) String series,
            @RequestParam String publishDate,
            @RequestParam(required = false) String fansGained,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false, defaultValue = "0") Integer wechatReads,
            @RequestParam(required = false, defaultValue = "0") Integer wechatLikes,
            @RequestParam(required = false, defaultValue = "0") Integer wechatShares,
            @RequestParam(required = false, defaultValue = "0") Integer csdnReads,
            @RequestParam(required = false, defaultValue = "0") Integer csdnLikes,
            @RequestParam(required = false, defaultValue = "0") Integer zhihuReads,
            @RequestParam(required = false, defaultValue = "0") Integer zhihuLikes) {
        try {
            // Generate a simple article ID
            String id = "A" + System.currentTimeMillis() % 100000;
            Map<String, OperationsData.PlatformArticleData> platformData = new LinkedHashMap<>();
            if (wechatReads > 0) {
                OperationsData.PlatformArticleData pd = new OperationsData.PlatformArticleData();
                pd.reads = wechatReads; pd.likes = wechatLikes; pd.shares = wechatShares;
                platformData.put("wechat", pd);
            }
            if (csdnReads > 0) {
                OperationsData.PlatformArticleData pd = new OperationsData.PlatformArticleData();
                pd.reads = csdnReads; pd.likes = csdnLikes;
                platformData.put("csdn", pd);
            }
            if (zhihuReads > 0) {
                OperationsData.PlatformArticleData pd = new OperationsData.PlatformArticleData();
                pd.reads = zhihuReads; pd.likes = zhihuLikes;
                platformData.put("zhihu", pd);
            }
            operationsService.addArticle(accountName, id, topic, series, publishDate, null, fansGained, notes, platformData);
            logService.add("运营数据", "成功", "添加文章: " + topic);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/operations/daily-stats/add")
    @ResponseBody
    public Map<String, Object> addDailyStats(
            @RequestParam String accountName,
            @RequestParam String date,
            @RequestParam(required = false, defaultValue = "0") Integer fans,
            @RequestParam(required = false, defaultValue = "0") Integer reads,
            @RequestParam(required = false, defaultValue = "0") Integer readArticles,
            @RequestParam(required = false, defaultValue = "0") Integer sourceRecommend,
            @RequestParam(required = false, defaultValue = "0") Integer sourceSearch) {
        try {
            OperationsData.DailyStats stats = new OperationsData.DailyStats();
            stats.date = date; stats.fans = fans; stats.reads = reads;
            stats.readArticles = readArticles;
            stats.sourceRecommend = sourceRecommend;
            stats.sourceSearch = sourceSearch;
            operationsService.addDailyStats(accountName, stats);
            logService.add("运营数据", "成功", "添加日统计: " + accountName + " " + date);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/operations/account/save")
    @ResponseBody
    public Map<String, Object> saveAccount(
            @RequestParam String accountName,
            @RequestParam String platformKey,
            @RequestParam(required = false) String name,
            @RequestParam(required = false, defaultValue = "0") Integer fans,
            @RequestParam(required = false, defaultValue = "0") Integer totalViews,
            @RequestParam(required = false, defaultValue = "0") Integer totalArticles) {
        try {
            operationsService.saveAccount(accountName, platformKey,
                    name != null ? name : accountName,
                    fans > 0 ? fans : null,
                    totalViews > 0 ? totalViews : null,
                    totalArticles > 0 ? totalArticles : null);
            logService.add("运营数据", "成功", "保存账号: " + accountName + "/" + platformKey);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}