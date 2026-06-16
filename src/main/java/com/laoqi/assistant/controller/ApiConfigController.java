package com.laoqi.assistant.controller;

import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.FeishuService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.OllamaEmbeddingService;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
public class ApiConfigController {

    private final ConfigService configService;
    private final FeishuService feishuService;
    private final LogService logService;
    private final OllamaEmbeddingService ollamaEmbeddingService;

    public ApiConfigController(ConfigService configService, FeishuService feishuService,
                                LogService logService,
                                OllamaEmbeddingService ollamaEmbeddingService) {
        this.configService = configService;
        this.feishuService = feishuService;
        this.logService = logService;
        this.ollamaEmbeddingService = ollamaEmbeddingService;
    }

    @GetMapping("/api/config")
    public Config getConfig() {
        return configService.load();
    }

    @PostMapping("/api/config/webhook")
    public Map<String, Object> updateWebhook(@RequestParam String url) {
        if (!url.startsWith("https://"))
            return Map.of("ok", false, "error", "URL 必须以 https:// 开头");
        Config cfg = configService.load();
        cfg.setFeishuWebhookUrl(url);
        configService.save(cfg);
        logService.add("配置更新", "成功", "飞书 Webhook URL 已更新");
        return Map.of("ok", true);
    }

    @PostMapping("/api/config/baseDir")
    public Map<String, Object> updateBaseDir(@RequestParam String baseDir) {
        try {
            Config cfg = configService.load();
            cfg.setBaseDir(baseDir);
            configService.save(cfg);
            logService.add("配置更新", "成功", "笔记库根目录已更新为: " + baseDir);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/config/feishu")
    public Map<String, Object> updateFeishu(
            @RequestParam(required = false, defaultValue = "") String appId,
            @RequestParam(required = false, defaultValue = "") String appSecret,
            @RequestParam(required = false, defaultValue = "") String chatId,
            @RequestParam(required = false, defaultValue = "off") String pollingEnabled) {
        Config cfg = configService.load();
        cfg.setFeishuAppId(appId);
        cfg.setFeishuAppSecret(appSecret);
        cfg.setFeishuChatId(chatId);
        cfg.setFeishuPollingEnabled("on".equals(pollingEnabled));
        configService.save(cfg);
        logService.add("配置更新", "成功", "飞书消息接收配置已更新");
        return Map.of("ok", true);
    }

    @PostMapping("/api/config/feishu/chats")
    public Map<String, Object> listFeishuChats(
            @RequestParam String appId,
            @RequestParam String appSecret) {
        try {
            String token = feishuService.getTenantToken(appId, appSecret);
            List<Map<String, Object>> chats = feishuService.listChats(token);
            List<Map<String, Object>> result = chats.stream().map(c -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chat_id", c.get("chat_id"));
                item.put("name", c.get("name"));
                item.put("type", "group".equals(c.get("chat_type")) ? "群" : "单聊");
                return item;
            }).collect(Collectors.toList());
            return Map.of("ok", true, "chats", result);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/config/feishu/test")
    public Map<String, Object> testFeishu(
            @RequestParam String appId,
            @RequestParam String appSecret,
            @RequestParam String chatId) {
        try {
            String token = feishuService.getTenantToken(appId, appSecret);
            feishuService.sendTextMessage(token, chatId,
                    "🧪 老齐AI助理连接测试成功！AI助理已就绪，现在可以开始在群里与我对话啦 🎉");
            logService.add("飞书测试", "成功", "向 " + chatId.substring(0, Math.min(20, chatId.length())) + " 发送测试消息成功");
            return Map.of("ok", true);
        } catch (Exception e) {
            logService.add("飞书测试", "失败", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @GetMapping("/api/config/labels")
    public Map<String, Object> getLabels() {
        return Map.of("ok", true, "labels", configService.load().getKeyLabels());
    }

    @PostMapping("/api/config/labels")
    public Map<String, Object> updateLabels(@RequestBody Map<String, String> labels) {
        Config cfg = configService.load();
        cfg.setKeyLabels(labels);
        configService.save(cfg);
        logService.add("配置更新", "成功", "字段标签映射已更新");
        return Map.of("ok", true);
    }

    @PostMapping("/api/config/collector-dir")
    public Map<String, Object> updateCollectorDir(@RequestParam String dir) {
        return Map.of("ok", false, "error", "该功能已废弃，采集器数据现保存至数据中心");
    }

    @PostMapping("/api/config/ai-provider")
    public Map<String, Object> updateAiProvider(@RequestParam String provider) {
        if (!"opencode".equals(provider) && !"direct".equals(provider)) {
            return Map.of("ok", false, "error", "无效的 provider: " + provider + "，仅支持 opencode / direct");
        }
        Config cfg = configService.load();
        cfg.setAiProvider(provider);
        configService.save(cfg);
        logService.add("配置更新", "成功", "AI 引擎切换为: " + provider);
        return Map.of("ok", true, "provider", provider);
    }

    @PostMapping("/api/config/llm")
    public Map<String, Object> updateLlmConfig(@RequestBody Map<String, Object> body) {
        Config cfg = configService.load();
        if (body.containsKey("apiKey")) cfg.setLlmApiKey((String) body.get("apiKey"));
        if (body.containsKey("baseUrl")) cfg.setLlmBaseUrl((String) body.get("baseUrl"));
        if (body.containsKey("model")) cfg.setLlmModel((String) body.get("model"));
        if (body.containsKey("timeout")) cfg.setLlmTimeout(((Number) body.get("timeout")).intValue());
        configService.save(cfg);
        logService.add("配置更新", "成功", "LLM 配置已更新");
        return Map.of("ok", true);
    }

    @PostMapping("/api/config/media-collect")
    public Map<String, Object> updateMediaCollect(
            @RequestParam(name = "enabled", defaultValue = "off") String enabled,
            @RequestParam(name = "time", defaultValue = "08:00") String time) {
        return Map.of("ok", false, "error", "该功能已迁移至独立采集器模块，请使用 /api/collector/tasks API");
    }

    @GetMapping("/api/ollama/status")
    public Map<String, Object> ollamaStatus() {
        return Map.of("available", ollamaEmbeddingService.isAvailable());
    }
}
