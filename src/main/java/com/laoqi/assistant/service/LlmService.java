package com.laoqi.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 配置读取统一委托给 LlmConfigResolver，消除与 NoteAssistantService 的重复。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final LlmConfigResolver configResolver;
    private final HttpClient httpClient;

    public LlmService(LlmConfigResolver configResolver) {
        this.configResolver = configResolver;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 检查 LLM 直连是否可用（API Key 已配置）
     */
    public boolean isAvailable() {
        return configResolver.isAvailable();
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
        String apiKey = configResolver.resolveApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写，或设置环境变量 LLM_API_KEY / DEEPSEEK_API_KEY");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", configResolver.resolveModel());
        body.put("messages", messages);
        body.put("temperature", 0.7);
        body.put("stream", false);

        String jsonBody = mapper.writeValueAsString(body);

        String baseUrl = configResolver.resolveBaseUrl();
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
                .timeout(Duration.ofSeconds(configResolver.resolveTimeout()))
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
                    configResolver.resolveModel(), elapsed, usage);
        } else {
            log.info("[llm] 模型={} 耗时={}ms", configResolver.resolveModel(), elapsed);
        }

        return content != null ? content : "";
    }


}
