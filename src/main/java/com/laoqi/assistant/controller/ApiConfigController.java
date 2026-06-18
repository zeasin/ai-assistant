package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.FeishuService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.LlmConfigResolver;
import com.laoqi.assistant.service.OllamaEmbeddingService;
import com.laoqi.assistant.service.db.LlmProfileDbService;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
public class ApiConfigController {

    private final ConfigService configService;
    private final FeishuService feishuService;
    private final LogService logService;
    private final OllamaEmbeddingService ollamaEmbeddingService;
    private final LlmProfileDbService llmProfileDbService;
    private final LlmConfigResolver llmConfigResolver;

    public ApiConfigController(ConfigService configService, FeishuService feishuService,
                                LogService logService,
                                OllamaEmbeddingService ollamaEmbeddingService,
                                LlmProfileDbService llmProfileDbService,
                                LlmConfigResolver llmConfigResolver) {
        this.configService = configService;
        this.feishuService = feishuService;
        this.logService = logService;
        this.ollamaEmbeddingService = ollamaEmbeddingService;
        this.llmProfileDbService = llmProfileDbService;
        this.llmConfigResolver = llmConfigResolver;
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

    @PostMapping("/api/config/coding")
    public Map<String, Object> updateCoding(
            @RequestParam(required = false, defaultValue = "") String appId,
            @RequestParam(required = false, defaultValue = "") String appSecret,
            @RequestParam(required = false, defaultValue = "") String chatId,
            @RequestParam(required = false, defaultValue = "") String projectDir,
            @RequestParam(required = false, defaultValue = "off") String enabled,
            @RequestParam(required = false, defaultValue = "300") int timeout) {
        Config cfg = configService.load();
        cfg.setCodingFeishuAppId(appId);
        cfg.setCodingFeishuAppSecret(appSecret);
        cfg.setCodingFeishuChatId(chatId);
        cfg.setCodingProjectDir(projectDir);
        cfg.setCodingPiEnabled("on".equals(enabled));
        cfg.setCodingPiTimeout(timeout > 0 ? timeout : 300);
        configService.save(cfg);
        logService.add("配置更新", "成功", "编程AI配置已更新");
        return Map.of("ok", true);
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
        if (!"direct".equals(provider)) {
            return Map.of("ok", false, "error", "v0.4.0 仅支持 direct 模式（opencode 已移除）");
        }
        Config cfg = configService.load();
        cfg.setAiProvider(provider);
        configService.save(cfg);
        logService.add("配置更新", "成功", "AI 引擎已固定为 direct");
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

        // Also sync to llm_profiles table
        List<LlmProfileEntity> profiles = llmProfileDbService.listAllOrdered();
        if (profiles.isEmpty()) {
            LlmProfileEntity p = new LlmProfileEntity();
            p.setName("default");
            p.setApiKey((String) body.get("apiKey"));
            p.setBaseUrl((String) body.get("baseUrl"));
            p.setModel((String) body.get("model"));
            p.setTimeout(body.containsKey("timeout") ? ((Number) body.get("timeout")).intValue() : 600);
            p.setIsDefault(true);
            llmProfileDbService.save(p);
        } else {
            LlmProfileEntity p = profiles.get(0);
            if (body.containsKey("apiKey")) p.setApiKey((String) body.get("apiKey"));
            if (body.containsKey("baseUrl")) p.setBaseUrl((String) body.get("baseUrl"));
            if (body.containsKey("model")) p.setModel((String) body.get("model"));
            if (body.containsKey("timeout")) p.setTimeout(((Number) body.get("timeout")).intValue());
            llmProfileDbService.updateById(p);
        }

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

    // ========== LLM Profile endpoints (SQLite) ==========

    @GetMapping("/api/config/llm-profiles")
    public Map<String, Object> listLlmProfiles() {
        List<LlmProfileEntity> profiles = llmProfileDbService.listAllOrdered();
        return Map.of("ok", true, "profiles", profiles);
    }

    @PostMapping("/api/config/llm-profiles")
    public Map<String, Object> saveLlmProfile(@RequestBody LlmProfileEntity profile) {
        if (profile.getName() == null || profile.getName().trim().isEmpty()) {
            return Map.of("ok", false, "error", "模型名称不能为空");
        }
        profile.setName(profile.getName().trim());

        // 向后兼容：modelType 为空时从 visionSupport 推断
        if (profile.getModelType() == null || profile.getModelType().isEmpty()) {
            if (Boolean.TRUE.equals(profile.getVisionSupport())) {
                profile.setModelType(LlmProfileEntity.TYPE_MULTIMODAL);
            } else {
                profile.setModelType(LlmProfileEntity.TYPE_TEXT);
            }
        }
        // 同步 visionSupport 向后兼容
        if (LlmProfileEntity.TYPE_MULTIMODAL.equals(profile.getModelType())) {
            profile.setVisionSupport(true);
        }

        // Check for duplicate name
        LlmProfileEntity existing = llmProfileDbService.findByName(profile.getName());
        if (profile.getId() != null) {
            // Update existing
            if (existing != null && !existing.getId().equals(profile.getId())) {
                return Map.of("ok", false, "error", "模型名称已存在");
            }
            if (profile.getTimeout() == null) profile.setTimeout(600);
            llmProfileDbService.updateById(profile);
            logService.add("配置更新", "成功", "LLM 模型已更新: " + profile.getName());
        } else {
            // Create new
            if (existing != null) {
                return Map.of("ok", false, "error", "模型名称已存在");
            }
            if (profile.getTimeout() == null) profile.setTimeout(600);
            if (profile.getIsDefault() == null) profile.setIsDefault(false);

            // If this is the only profile, make it default
            List<LlmProfileEntity> all = llmProfileDbService.listAllOrdered();
            if (all.isEmpty()) {
                profile.setIsDefault(true);
            }

            // If setting as default, unset others
            if (Boolean.TRUE.equals(profile.getIsDefault())) {
                clearDefaultFlag();
            }

            llmProfileDbService.save(profile);
            logService.add("配置更新", "成功", "LLM 模型已添加: " + profile.getName());
        }
        return Map.of("ok", true, "profile", profile);
    }

    @DeleteMapping("/api/config/llm-profiles/{id}")
    public Map<String, Object> deleteLlmProfile(@PathVariable Long id) {
        LlmProfileEntity profile = llmProfileDbService.getById(id);
        if (profile == null) {
            return Map.of("ok", false, "error", "模型不存在");
        }
        llmProfileDbService.removeById(id);
        logService.add("配置更新", "成功", "LLM 模型已删除: " + profile.getName());

        // If the deleted profile was the default, assign next one
        if (Boolean.TRUE.equals(profile.getIsDefault())) {
            List<LlmProfileEntity> remaining = llmProfileDbService.listAllOrdered();
            if (!remaining.isEmpty()) {
                LlmProfileEntity newDefault = remaining.get(0);
                newDefault.setIsDefault(true);
                llmProfileDbService.updateById(newDefault);
            }
        }
        return Map.of("ok", true);
    }

    @PostMapping("/api/config/llm-profiles/{id}/default")
    public Map<String, Object> setDefaultLlmProfile(@PathVariable Long id) {
        LlmProfileEntity profile = llmProfileDbService.getById(id);
        if (profile == null) {
            return Map.of("ok", false, "error", "模型不存在");
        }
        clearDefaultFlag();
        profile.setIsDefault(true);
        llmProfileDbService.updateById(profile);
        logService.add("配置更新", "成功", "默认 LLM 模型已切换为: " + profile.getName());
        return Map.of("ok", true, "profile", profile);
    }

    private void clearDefaultFlag() {
        List<LlmProfileEntity> all = llmProfileDbService.listAllOrdered();
        for (LlmProfileEntity p : all) {
            if (Boolean.TRUE.equals(p.getIsDefault())) {
                p.setIsDefault(false);
                llmProfileDbService.updateById(p);
            }
        }
    }

    @PostMapping("/api/config/embedding-model")
    public Map<String, Object> saveEmbeddingModel(@RequestBody Map<String, String> body) {
        String model = body.getOrDefault("model", "");
        String baseUrl = body.getOrDefault("baseUrl", "");
        String apiKey = body.getOrDefault("apiKey", "");
        String provider = body.getOrDefault("provider", "");
        Config config = configService.load();
        config.setEmbeddingModel(model);
        config.setEmbeddingBaseUrl(baseUrl);
        config.setEmbeddingApiKey(apiKey);
        config.setEmbeddingProvider(provider);
        configService.save(config);
        logService.add("配置更新", "成功", "语义向量模型已配置: " + model);
        ollamaEmbeddingService.reloadConfig();
        return Map.of("ok", true);
    }

    @GetMapping("/api/config/embedding-model")
    public Map<String, Object> getEmbeddingModel() {
        Config config = configService.load();
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("model", config.getEmbeddingModel());
        result.put("baseUrl", config.getEmbeddingBaseUrl());
        result.put("apiKey", config.getEmbeddingApiKey());
        result.put("provider", config.getEmbeddingProvider());
        result.put("providerLabel", ollamaEmbeddingService.getProviderLabel());
        return result;
    }
}
