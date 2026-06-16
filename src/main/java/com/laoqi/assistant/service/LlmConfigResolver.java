package com.laoqi.assistant.service;

import com.laoqi.assistant.model.Config;
import org.springframework.stereotype.Component;

/**
 * 统一解析 LLM 配置（API Key、Base URL、模型名、超时），
 * 按优先级：config.json Web 配置 > 环境变量
 * 不读取 application.yml，LLM 配置仅通过 config.json 或环境变量指定。
 *
 * 被 LlmService 和 NoteAssistantService 共用，消除重复代码。
 */
@Component
public class LlmConfigResolver {

    private final ConfigService configService;

    public LlmConfigResolver(ConfigService configService) {
        this.configService = configService;
    }

    private Config loadConfig() {
        try {
            return configService.load();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 API Key，按优先级：
     * 1. config.json 中 Web 页面配置的 llmApiKey
     * 2. 环境变量 LLM_API_KEY
     * 3. 环境变量 DEEPSEEK_API_KEY
     */
    public String resolveApiKey() {
        Config cfg = loadConfig();
        if (cfg != null) {
            String key = cfg.getLlmApiKey();
            if (key != null && !key.isEmpty()) return key;
        }
        String key = System.getenv("LLM_API_KEY");
        if (key != null && !key.isEmpty()) return key;
        key = System.getenv("DEEPSEEK_API_KEY");
        if (key != null && !key.isEmpty()) return key;
        return "";
    }

    /**
     * 获取 API Base URL
     */
    public String resolveBaseUrl() {
        Config cfg = loadConfig();
        if (cfg != null) {
            String url = cfg.getLlmBaseUrl();
            if (url != null && !url.isEmpty()) return url;
        }
        String envUrl = System.getenv("LLM_BASE_URL");
        if (envUrl != null && !envUrl.isEmpty()) return envUrl;
        return "https://api.deepseek.com";
    }

    /**
     * 获取模型名
     */
    public String resolveModel() {
        Config cfg = loadConfig();
        if (cfg != null) {
            String model = cfg.getLlmModel();
            if (model != null && !model.isEmpty()) return model;
        }
        return "deepseek-chat";
    }

    /**
     * 获取超时秒数
     */
    public int resolveTimeout() {
        Config cfg = loadConfig();
        if (cfg != null && cfg.getLlmTimeout() > 0) return cfg.getLlmTimeout();
        return 60;
    }

    /**
     * 检查 LLM 直连是否可用（API Key 已配置）
     */
    public boolean isAvailable() {
        String apiKey = resolveApiKey();
        return apiKey != null && !apiKey.isEmpty();
    }
}
