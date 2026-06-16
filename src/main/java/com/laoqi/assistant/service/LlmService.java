package com.laoqi.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 直连 LLM API（兼容 OpenAI 格式），不走 opencode serve。
 * 支持任意兼容 OpenAI Chat Completions 接口的提供商：
 * DeepSeek、商汤日日新、阿里通义、百度千帆、智谱、OpenAI 等。
 * 通过 application.yml 配置 base-url 切换。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final HttpClient httpClient;

    public LlmService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 加载 LLM 配置（优先使用 config.json 中的 Web 配置，其次 application.yml）
     */
    private Config loadLlmConfig() {
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
     * 4. application.yml 的 app.llm.api-key
     */
    private String resolveApiKey() {
        // 1. Web 配置
        Config cfg = loadLlmConfig();
        if (cfg != null) {
            String key = cfg.getLlmApiKey();
            if (key != null && !key.isEmpty()) return key;
        }
        // 2. 环境变量
        String key = System.getenv("LLM_API_KEY");
        if (key != null && !key.isEmpty()) return key;
        key = System.getenv("DEEPSEEK_API_KEY");
        if (key != null && !key.isEmpty()) return key;
        // 3. yml 配置（兜底）
        key = appConfig.getLlmApiKey();
        if (key != null && !key.isEmpty() && !key.startsWith("$")) return key;
        return "";
    }

    /**
     * 获取 API Base URL
     */
    private String resolveBaseUrl() {
        Config cfg = loadLlmConfig();
        if (cfg != null) {
            String url = cfg.getLlmBaseUrl();
            if (url != null && !url.isEmpty()) return url;
        }
        String envUrl = System.getenv("LLM_BASE_URL");
        if (envUrl != null && !envUrl.isEmpty()) return envUrl;
        String url = appConfig.getLlmBaseUrl();
        if (url != null && !url.isEmpty() && !url.startsWith("$")) return url;
        return "https://api.deepseek.com";
    }

    /**
     * 获取模型名
     */
    public String resolveModel() {
        Config cfg = loadLlmConfig();
        if (cfg != null) {
            String model = cfg.getLlmModel();
            if (model != null && !model.isEmpty()) return model;
        }
        String m = appConfig.getLlmModel();
        if (m != null && !m.isEmpty()) return m;
        return "deepseek-chat";
    }

    /**
     * 获取超时秒数
     */
    private int resolveTimeout() {
        Config cfg = loadLlmConfig();
        if (cfg != null && cfg.getLlmTimeout() > 0) return cfg.getLlmTimeout();
        int t = appConfig.getLlmTimeoutSeconds();
        return t > 0 ? t : 60;
    }

    /**
     * 检查 LLM 直连是否可用（API Key 已配置）
     */
    public boolean isAvailable() {
        String apiKey = resolveApiKey();
        return apiKey != null && !apiKey.isEmpty();
    }

    /**
     * 同步调用 LLM，返回完整回复文本
     */
    public String chat(String systemPrompt, String userMessage) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        return chat(messages);
    }

    /**
     * 同步调用 LLM，返回完整回复文本
     */
    public String chat(List<Map<String, String>> messages) throws Exception {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("LLM API Key 未配置，请在 application.yml 中设置 app.llm.api-key" +
                    "，或设置环境变量 LLM_API_KEY / DEEPSEEK_API_KEY");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveModel());
        body.put("messages", messages);
        body.put("temperature", 0.7);
        body.put("stream", false);

        String jsonBody = mapper.writeValueAsString(body);

        String baseUrl = resolveBaseUrl();
        // 确保不以 / 结尾
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String apiUrl = baseUrl + "/chat/completions";
        log.debug("[llm] 请求 URL: {}", apiUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(resolveTimeout()))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        if (response.statusCode() != 200) {
            log.error("LLM API 错误: status={} body={}", response.statusCode(), response.body());
            throw new RuntimeException("LLM API 返回错误 " + response.statusCode() + ": " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> json = mapper.readValue(response.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");

        if (choices == null || choices.isEmpty()) {
            log.warn("LLM 返回空 choices: {}", response.body());
            return "";
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = message != null ? (String) message.get("content") : "";

        // Token 统计
        Map<String, Object> usage = (Map<String, Object>) json.get("usage");
        if (usage != null) {
            log.info("[llm] 模型={} 耗时={}ms token={}",
                    resolveModel(), elapsed, usage);
        } else {
            log.info("[llm] 模型={} 耗时={}ms", resolveModel(), elapsed);
        }

        return content != null ? content : "";
    }

    /**
     * 判断当前是否使用"新模式"（Java 直连 LLM）
     */
    public boolean isDirectMode(ConfigService configService) {
        var config = configService.load();
        return "direct".equals(config.getAiProvider());
    }
}
