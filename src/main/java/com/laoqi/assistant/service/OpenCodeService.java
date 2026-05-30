package com.laoqi.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class OpenCodeService {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AppConfig appConfig;
    private final HttpClient httpClient;

    public OpenCodeService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private String getBaseUrl() {
        return "http://127.0.0.1:" + appConfig.getNotesPort();
    }

    private String getCodeBaseUrl() {
        return "http://127.0.0.1:" + appConfig.getCodePort();
    }

    /**
     * Create a new opencode session
     */
    public String createSession(String title) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (title != null) {
            body.put("title", title);
        }

        String url = getBaseUrl() + "/session";
        String requestBody = mapper.writeValueAsString(body);

        log.info("[opencode] 创建会话 POST {} body={}", url, requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("[opencode] 创建会话响应 status={} body={}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("opencode createSession error: " + response.statusCode());
        }

        JsonNode json = mapper.readTree(response.body());
        return json.get("id").asText();
    }

    /**
     * Send a message synchronously via POST /session/:id/message.
     * This blocks until the AI finishes generating the reply.
     */
    public String sendMessage(String sessionId, String message) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parts", List.of(Map.of("type", "text", "text", message)));

        String url = getBaseUrl() + "/session/" + sessionId + "/message";
        String requestBody = mapper.writeValueAsString(body);

        log.info("[opencode] 发送消息 POST {} sessionId={} message={}", url, sessionId, message);

        long start = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(600))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        long elapsed = System.currentTimeMillis() - start;

        log.info("[opencode] 消息响应 status={} 耗时={}ms", response.statusCode(), elapsed);
        log.info("[opencode] 响应体: {}", truncate(response.body(), 2000));

        if (response.statusCode() != 200) {
            throw new RuntimeException("opencode sendMessage error: " + response.statusCode() + " body=" + response.body());
        }

        return extractReplyText(response.body());
    }

    /**
     * Extract the assistant's text reply from the response JSON
     */
    private String extractReplyText(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode parts = root.get("parts");
        if (parts == null || !parts.isArray()) {
            log.warn("[opencode] 响应中未找到 parts 字段, keys={}", root.fieldNames());
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String type = part.get("type").asText();
            if ("text".equals(type)) {
                sb.append(part.get("text").asText());
            }
        }

        String reply = sb.toString();
        log.info("[opencode] 提取回复文本 长度={}", reply.length());

        return reply;
    }

    /**
     * Get the first available session ID (reuse existing session)
     */
    public String findIdleSession() {
        try {
            String sessionsUrl = getBaseUrl() + "/session";
            log.debug("[opencode] 查询所有 session GET {}", sessionsUrl);

            HttpRequest sessionsRequest = HttpRequest.newBuilder()
                    .uri(URI.create(sessionsUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> sessionsResponse = httpClient.send(sessionsRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode sessionsJson = mapper.readTree(sessionsResponse.body());

            if (sessionsJson.isArray() && sessionsJson.size() > 0) {
                String sessionId = sessionsJson.get(0).get("id").asText();
                log.info("[opencode] 使用已有 session: {}", sessionId);
                return sessionId;
            }

            log.info("[opencode] 没有找到已有 session");
            return null;
        } catch (Exception e) {
            log.warn("[opencode] 查询 session 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if the opencode serve is healthy
     */
    public boolean isHealthy() {
        try {
            String url = getBaseUrl() + "/global/health";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.debug("[opencode] 健康检查响应 status={} body={}", response.statusCode(), response.body());

            JsonNode json = mapper.readTree(response.body());
            return json.get("healthy").asBoolean();
        } catch (Exception e) {
            log.warn("[opencode] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    public String createCodeSession(String title) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (title != null) {
            body.put("title", title);
        }

        String url = getCodeBaseUrl() + "/session";
        String requestBody = mapper.writeValueAsString(body);

        log.info("[opencode-code] 创建会话 POST {} body={}", url, requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("[opencode-code] 创建会话响应 status={} body={}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("opencode createCodeSession error: " + response.statusCode());
        }

        JsonNode json = mapper.readTree(response.body());
        return json.get("id").asText();
    }

    public String sendCodeMessage(String sessionId, String message) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parts", List.of(Map.of("type", "text", "text", message)));

        String url = getCodeBaseUrl() + "/session/" + sessionId + "/message";
        String requestBody = mapper.writeValueAsString(body);

        log.info("[opencode-code] 发送消息 POST {} sessionId={} message={}", url, sessionId, message);

        long start = System.currentTimeMillis();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(300))
                    .build();

            log.debug("[opencode-code] 请求已构建，准备发送...");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("[opencode-code] 请求已发送，等待响应...");

            long elapsed = System.currentTimeMillis() - start;

            log.info("[opencode-code] 消息响应 status={} 耗时={}ms", response.statusCode(), elapsed);
            log.info("[opencode-code] 响应体: {}", truncate(response.body(), 2000));

            if (response.statusCode() != 200) {
                throw new RuntimeException("opencode sendCodeMessage error: " + response.statusCode() + " body=" + response.body());
            }

            String reply = extractReplyText(response.body());
            log.info("[opencode-code] 提取回复文本 长度={}", reply.length());
            return reply;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[opencode-code] 发送消息失败，耗时={}ms: {}", elapsed, e.getMessage(), e);
            throw e;
        }
    }

    public String findIdleCodeSession() {
        try {
            String sessionsUrl = getCodeBaseUrl() + "/session";
            log.debug("[opencode-code] 查询所有 session GET {}", sessionsUrl);

            HttpRequest sessionsRequest = HttpRequest.newBuilder()
                    .uri(URI.create(sessionsUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> sessionsResponse = httpClient.send(sessionsRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode sessionsJson = mapper.readTree(sessionsResponse.body());

            if (sessionsJson.isArray() && sessionsJson.size() > 0) {
                String sessionId = sessionsJson.get(0).get("id").asText();
                log.info("[opencode-code] 使用已有 session: {}", sessionId);
                return sessionId;
            }

            log.info("[opencode-code] 没有找到已有 session");
            return null;
        } catch (Exception e) {
            log.warn("[opencode-code] 查询 session 失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean isCodeHealthy() {
        try {
            String url = getCodeBaseUrl() + "/global/health";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.debug("[opencode-code] 健康检查响应 status={} body={}", response.statusCode(), response.body());

            JsonNode json = mapper.readTree(response.body());
            return json.get("healthy").asBoolean();
        } catch (Exception e) {
            log.warn("[opencode-code] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() <= maxLen ? s : (s == null ? "null" : s.substring(0, maxLen) + "...");
    }
}
