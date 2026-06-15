package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.ArrayList;

@Service
public class OpenCodeService {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeService.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final AppConfig appConfig;
    private final HttpClient httpClient;

    public OpenCodeService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private String getBaseUrl() {
        return "http://127.0.0.1:" + appConfig.getNotesPort();
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
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, java.nio.charset.StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));

        log.info("[opencode] 创建会话响应 status={} body={}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("opencode createSession error: " + response.statusCode() + " body=" + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        return json.get("id").asText();
    }

    /**
     * Send a message via POST /session/:id/message.
     * Streams the response and logs each part in real-time to the console,
     * then returns the complete text reply.
     */
    public String sendMessage(String sessionId, String message) throws Exception {
        return sendMessageStreamed(getBaseUrl(), sessionId, message, null);
    }

    public String sendMessageWithImage(String sessionId, String message, String imageBase64, String imageType) throws Exception {
        return sendMessageStreamed(getBaseUrl(), sessionId, message, imageBase64 != null ? Map.of("data", imageBase64, "type", imageType) : null);
    }

    /**
     * Streamed version: reads the HTTP response body as a stream,
     * uses Jackson streaming parser to extract parts as they arrive,
     * logs each part in real-time to the backend console.
     */
    private String sendMessageStreamed(String baseUrl, String sessionId, String message, Map<String, String> imageData) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", message));

        if (imageData != null) {
            String base64Data = imageData.get("data");
            String imageType = imageData.getOrDefault("type", "image/jpeg");
            parts.add(Map.of("type", "file", "mime", imageType, "url", "data:" + imageType + ";base64," + base64Data));
        }

        body.put("parts", parts);

        String url = baseUrl + "/session/" + sessionId + "/message";
        String requestBody = mapper.writeValueAsString(body);
        String label = "[opencode]";

        log.info("{} 发送消息 POST {} sessionId={}", label, url, sessionId);

        long start = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, java.nio.charset.StandardCharsets.UTF_8))
                .timeout(Duration.ofMinutes(10))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        long elapsed = System.currentTimeMillis() - start;
        log.info("{} 消息响应 status={} 耗时={}ms", label, response.statusCode(), elapsed);

        if (response.statusCode() != 200) {
            String errorBody = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new RuntimeException(label + " error: " + response.statusCode() + " body=" + errorBody);
        }

        // 流式读取响应体，逐 part 实时打印到控制台
        StringBuilder textReply = new StringBuilder();
        try (InputStream is = response.body()) {
            // 先用额外线程读取网络流到字节缓冲区，当前线程逐块解析
            // 这样一旦有数据到达就处理，而非等全部读完
            java.util.concurrent.LinkedBlockingQueue<byte[]> chunks = new java.util.concurrent.LinkedBlockingQueue<>();
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicReference<Exception> readError = new java.util.concurrent.atomic.AtomicReference<>();

            // 后台线程持续读取网络流
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf, 0, buf.length)) != -1) {
                        byte[] chunk = new byte[len];
                        System.arraycopy(buf, 0, chunk, 0, len);
                        chunks.put(chunk);
                    }
                } catch (Exception e) {
                    readError.set(e);
                } finally {
                    done.set(true);
                }
            }, label + "-stream-reader");
            reader.setDaemon(true);
            reader.start();

            // 主线程从队列取数据，追加到缓冲区，当检测到完整 parts 时打印
            StringBuilder jsonBuf = new StringBuilder();
            int partsLogged = 0;

            while (!done.get() || !chunks.isEmpty()) {
                byte[] chunk = chunks.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    jsonBuf.append(new String(chunk, java.nio.charset.StandardCharsets.UTF_8));
                }
                // 尝试从当前缓冲区提取所有可解析的 parts
                partsLogged = tryExtractParts(jsonBuf, partsLogged, textReply, label);
            }
            // 最后再尝试解析一次（可能还有剩余数据）
            tryExtractParts(jsonBuf, partsLogged, textReply, label);

            Exception err = readError.get();
            if (err != null) {
                log.warn("{} 流读取异常: {}", label, err.getMessage());
            }

            if (textReply.length() == 0) {
                // 如果没解析出 text，尝试从完整 JSON 中提取
                String fullJson = jsonBuf.toString();
                if (!fullJson.isEmpty()) {
                    log.info("{} 尝试从完整响应提取文本, 长度={}", label, fullJson.length());
                    JsonNode root = mapper.readTree(fullJson);
                    if (root.has("text")) {
                        textReply.append(root.get("text").asText());
                    } else if (root.has("parts")) {
                        // 重新解析一次确保不漏
                    }
                    // 诊断日志
                    Set<String> fields = new LinkedHashSet<>();
                    root.fieldNames().forEachRemaining(fields::add);
                    log.info("{} 响应根字段: {}", label, fields);
                }
            }
        }

        long totalElapsed = System.currentTimeMillis() - start;
        String result = textReply.toString();
        log.info("{} 处理完成, 文本长度={}, 总耗时={}ms", label, result.length(), totalElapsed);
        return result;
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
     * Check if the opencode serve is healthy via TCP port check
     */
    public boolean isHealthy() {
        return checkPort(appConfig.getNotesPort());
    }

    /**
     * Try to extract and log parts from the JSON buffer incrementally.
     * Looks for the parts array and logs any new parts found.
     * Returns the number of parts logged so far.
     */
    private int tryExtractParts(StringBuilder jsonBuf, int alreadyLogged, StringBuilder textReply, String label) {
        String json = jsonBuf.toString();
        if (!json.contains("\"parts\"")) {
            return alreadyLogged;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode parts = root.get("parts");
            if (parts == null || !parts.isArray() || parts.size() <= alreadyLogged) {
                return alreadyLogged;
            }

            for (int i = alreadyLogged; i < parts.size(); i++) {
                JsonNode part = parts.get(i);
                String type = part.has("type") ? part.get("type").asText() : "unknown";

                switch (type) {
                    case "text":
                        String text = part.get("text").asText();
                        log.info("{} [{}][part={}] {}", label, type, i, truncate(text, 500));
                        textReply.append(text);
                        break;
                    case "reasoning":
                    case "thinking":
                        String reasoning = part.has("reasoning") ? part.get("reasoning").asText()
                                                : part.has("thinking") ? part.get("thinking").asText() : "";
                        if (!reasoning.isEmpty()) {
                            log.info("{} [{}][part={}] {}", label, type, i, truncate(reasoning, 500));
                        }
                        break;
                    case "tool_use":
                        String toolName = part.has("name") ? part.get("name").asText() : "?";
                        String toolInput = part.has("input") ? part.get("input").toString() : "";
                        log.info("{} [{}][part={}] name={} input={}",
                                label, type, i, toolName, truncate(toolInput, 300));
                        break;
                    case "tool_result":
                        String result = part.has("content") ? part.get("content").toString()
                                                : part.has("result") ? part.get("result").toString() : "";
                        log.info("{} [{}][part={}] result={}",
                                label, type, i, truncate(result, 300));
                        break;
                    default:
                        log.info("{} [{}][part={}] {}", label, type, i, truncate(part.toString(), 300));
                        break;
                }
            }
            return parts.size();
        } catch (Exception e) {
            // JSON 还没完整，或者 parts 还没解析完，忽略
            return alreadyLogged;
        }
    }

    private boolean checkPort(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            return true;
        } catch (Exception e) {
            log.warn("[opencode] 端口 {} 检查失败: {}", port, e.getMessage());
            return false;
        }
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() <= maxLen ? s : (s == null ? "null" : s.substring(0, maxLen) + "...");
    }
}
