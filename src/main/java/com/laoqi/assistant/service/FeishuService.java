package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ChatSession;
import com.laoqi.assistant.model.ChatSession.ChatMessage;
import com.laoqi.assistant.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FeishuService {

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final LogService logService;
    private final CustomerService customerService;
    private final ChatSessionService chatSessionService;

    private String cachedToken;
    private long tokenExpiresAt;

    public FeishuService(AppConfig appConfig, ConfigService configService,
                          LogService logService, CustomerService customerService,
                          ChatSessionService chatSessionService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.logService = logService;
        this.customerService = customerService;
        this.chatSessionService = chatSessionService;
    }

    public String getTenantToken(String appId, String appSecret) throws IOException {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }
        String body = "{\"app_id\":\"" + appId + "\",\"app_secret\":\"" + appSecret + "\"}";
        String response = httpPost("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                body, "application/json; charset=utf-8");
        Map<String, Object> json = parseJson(response);
        if (!"0".equals(String.valueOf(json.get("code")))) {
            throw new RuntimeException("获取tenant_token失败: " + json.get("msg"));
        }
        String token = (String) json.get("tenant_access_token");
        cachedToken = token;
        tokenExpiresAt = System.currentTimeMillis() + 5400_000; // 1.5h
        return token;
    }

    public String sendTextMessage(String token, String chatId, String text) throws IOException {
        String innerContent = "{\"text\":\"" + escapeJson(text) + "\"}";
        String body = "{\"receive_id\":\"" + chatId + "\",\"msg_type\":\"text\",\"content\":\"" + escapeJson(innerContent) + "\"}";
        String response = httpPost("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id",
                body, "application/json; charset=utf-8", token);
        Map<String, Object> json = parseJson(response);
        if (!"0".equals(String.valueOf(json.get("code")))) {
            throw new RuntimeException("发送消息失败: " + json.get("msg"));
        }
        return response;
    }

    public Map<String, Object> listMessages(String token, String chatId, int pageSize) throws IOException {
        String url = "https://open.feishu.cn/open-apis/im/v1/messages" +
                "?container_id_type=chat&container_id=" + chatId +
                "&page_size=" + pageSize + "&sort_type=ByCreateTimeDesc";
        String response = httpGet(url, token);
        return parseJson(response);
    }

    public List<Map<String, Object>> listChats(String token) throws IOException {
        String response = httpGet(
                "https://open.feishu.cn/open-apis/im/v1/chats?page_size=50", token);
        Map<String, Object> json = parseJson(response);
        if (!"0".equals(String.valueOf(json.get("code")))) {
            throw new RuntimeException("查询群列表失败: " + json.get("msg"));
        }
        Map<String, Object> data = (Map<String, Object>) json.get("data");
        return (List<Map<String, Object>>) data.get("items");
    }

    // Webhook-based message push
    public void sendPost(String title, List<List<Map<String, String>>> paragraphs) {
        var config = configService.load();
        String webhookUrl = config.getFeishuWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logService.add("飞书推送", "跳过", "未配置 Webhook URL");
            return;
        }
        try {
            String body = "{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{\"title\":\"" +
                    escapeJson(title) + "\",\"content\":" + toJson(paragraphs) + "}}}}";
            httpPost(webhookUrl, body, "application/json; charset=utf-8");
        } catch (IOException e) {
            logService.add("飞书推送", "失败", e.getMessage());
        }
    }

    public List<List<Map<String, String>>> reportToParagraphs(String report) {
        List<List<Map<String, String>>> paras = new ArrayList<>();
        for (String line : report.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                paras.add(List.of(Map.of("tag", "text", "text", " ")));
                continue;
            }
            if (stripped.startsWith("━━━")) {
                paras.add(List.of(Map.of("tag", "text", "text", stripped)));
                continue;
            }
            if (stripped.startsWith("【") && stripped.endsWith("】")) {
                paras.add(List.of(Map.of("tag", "text", "text", stripped)));
                continue;
            }
            // Check for emoji section headers
            if (stripped.contains("—") && List.of("📊", "📚", "🏠", "🎯", "⚡").stream().anyMatch(stripped::contains)) {
                paras.add(List.of(Map.of("tag", "text", "text", stripped)));
                continue;
            }
            paras.add(List.of(Map.of("tag", "text", "text", stripped)));
        }
        return paras;
    }

    public void articleReminder(String name, String day) {
        sendPost("📝 " + name + " 发文提醒", List.of(
                List.of(Map.of("tag", "text", "text", "今天是" + day + "，该发「" + name + "」公众号了！")),
                List.of(Map.of("tag", "text", "text", "写完记得多平台分发：知乎 / CSDN / 开源中国"))
        ));
    }

    public void dailyReportReminder() {
        sendPost("📝 下班日报提醒", List.of(
                List.of(Map.of("tag", "text", "text", "到6点了老齐，写一下今天的工作记录！")),
                List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")),
                List.of(Map.of("tag", "text", "text", "写完后我来帮你更新记忆文件，明天综合日报就会包含这些内容。")),
                List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")),
                List.of(Map.of("tag", "text", "text", "内容包括：\n- 今天做了什么事\n- 客户沟通情况\n- 开发/文章进展\n- 明天计划"))
        ));
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String toJson(Object obj) {
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(list.get(i)));
            }
            return sb.append("]").toString();
        }
        if (obj instanceof Map) {
            Map<String, ?> map = (Map<String, ?>) obj;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(e.getKey())).append("\":");
                Object v = e.getValue();
                if (v instanceof String) sb.append("\"").append(escapeJson((String) v)).append("\"");
                else sb.append(v);
                first = false;
            }
            return sb.append("}").toString();
        }
        return String.valueOf(obj);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        // Simple JSON parsing for known Feishu structures
        Map<String, Object> result = new HashMap<>();
        // Use Jackson or manual simple parsing for Feishu responses
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON parse error: " + e.getMessage());
        }
    }

    private String httpPost(String url, String body, String contentType) throws IOException {
        return httpPost(url, body, contentType, null);
    }

    private String httpPost(String url, String body, String contentType, String token) throws IOException {
        var conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Content-Type", contentType);
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        try (InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String httpGet(String url, String token) throws IOException {
        var conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        try (InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}