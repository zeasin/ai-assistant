package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.MediaDataCollectorService;
import com.laoqi.assistant.service.OperationsService;
import org.springframework.stereotype.Controller;
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
    private final ConfigService configService;
    private static final ObjectMapper mapper = new ObjectMapper();

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

    @GetMapping("/api/operations/last-collect")
    @ResponseBody
    public Map<String, Object> getLastCollectTime() {
        String time = mediaDataCollectorService.getLastCollectTime();
        return Map.of("ok", true, "lastCollectTime", time != null ? time : "从未采集");
    }

}