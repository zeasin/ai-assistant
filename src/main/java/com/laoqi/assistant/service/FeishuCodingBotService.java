package com.laoqi.assistant.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.CodingRecordEntity;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.db.CodingRecordDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 编程AI 飞书机器人 WebSocket 长连接
 * 独立于知识库机器人，接收消息后调用 pi CLI 排查代码
 * 对应 Python 版 app.py 的飞书 WebSocket + process_bug_report()
 */
@Service
public class FeishuCodingBotService {

    private static final Logger log = LoggerFactory.getLogger(FeishuCodingBotService.class);

    private final ConfigService configService;
    private final CodePiService codePiService;
    private final LogService logService;
    private final CodingRecordDbService recordDbService;
    @SuppressWarnings("unused")
    private final AppConfig appConfig;

    private com.lark.oapi.ws.Client wsClient;
    private com.lark.oapi.Client client;
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();

    private final ExecutorService messageExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "coding-msg");
        t.setDaemon(true);
        return t;
    });

    // 飞书 API token 缓存
    private volatile String cachedToken;
    private volatile long tokenExpiresAt;
    private final Object tokenLock = new Object();

    public FeishuCodingBotService(ConfigService configService,
                                  CodePiService codePiService,
                                  LogService logService,
                                  CodingRecordDbService recordDbService,
                                  AppConfig appConfig) {
        this.configService = configService;
        this.codePiService = codePiService;
        this.logService = logService;
        this.recordDbService = recordDbService;
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void init() {
        Config config = configService.load();
        if (!Boolean.TRUE.equals(config.isCodingPiEnabled())) {
            log.info("[编程AI] 未启用，跳过初始化");
            return;
        }

        String appId = config.getCodingFeishuAppId();
        String appSecret = config.getCodingFeishuAppSecret();

        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            log.warn("[编程AI] 配置不完整，跳过初始化");
            codingLog("配置", "App ID/Secret 未配置");
            return;
        }

        try {
            startLongConnection(appId, appSecret);
        } catch (Exception e) {
            log.error("[编程AI] 初始化失败: {}", e.getMessage(), e);
            codingLog("初始化", e.getMessage());
        }
    }

    public void startLongConnection(String appId, String appSecret) {
        try {
            log.info("[编程AI] 正在启动 WebSocket... appId={}",
                    appId.substring(0, Math.min(8, appId.length())) + "...");

            client = new com.lark.oapi.Client.Builder(appId, appSecret).build();

            com.lark.oapi.event.EventDispatcher dispatcher =
                    com.lark.oapi.event.EventDispatcher.newBuilder("", "")
                    .onP2MessageReceiveV1(new com.lark.oapi.service.im.ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(com.lark.oapi.service.im.v1.model.P2MessageReceiveV1 event) throws Exception {
                            handleMessage(event);
                        }
                    })
                    .build();

            wsClient = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                    .eventHandler(dispatcher)
                    .build();

            new Thread(() -> {
                try {
                    wsClient.start();
                    log.info("[编程AI] ✅ WebSocket 启动成功");
                    codingLog("WebSocket", "启动成功");
                } catch (Exception e) {
                    log.error("[编程AI] ❌ WebSocket 启动失败: {}", e.getMessage(), e);
                    codingLog("WebSocket", "启动失败: " + e.getMessage());
                }
            }, "coding-ws-connector").start();

        } catch (Exception e) {
            log.error("[编程AI] ❌ 初始化失败: {}", e.getMessage(), e);
            codingLog("初始化", e.getMessage());
        }
    }

    private void handleMessage(com.lark.oapi.service.im.v1.model.P2MessageReceiveV1 event) {
        try {
            String msgType = event.getEvent().getMessage().getMessageType();
            String chatId = event.getEvent().getMessage().getChatId();
            String content = event.getEvent().getMessage().getContent();
            String chatType = event.getEvent().getMessage().getChatType();
            String messageId = event.getEvent().getMessage().getMessageId();

            if (processedMessages.contains(messageId)) return;
            processedMessages.add(messageId);
            if (processedMessages.size() > 1000) {
                var it = processedMessages.iterator();
                int toRemove = 500;
                while (it.hasNext() && toRemove-- > 0) { it.next(); it.remove(); }
            }

            if (!"p2p".equals(chatType)) {
                var mentions = event.getEvent().getMessage().getMentions();
                if (mentions == null || mentions.length == 0) return;
            }

            String text = "";
            String imagePath = null;

            if ("text".equals(msgType)) {
                try {
                    Map<String, String> contentMap = new Gson().fromJson(content,
                            new TypeToken<Map<String, String>>() {}.getType());
                    text = contentMap.get("text");
                    String imageKey = contentMap.get("image_key");
                    if (imageKey != null && !imageKey.isEmpty()) {
                        imagePath = downloadFeishuMedia(imageKey, "image", messageId);
                    }
                } catch (Exception e) {
                    log.warn("[编程AI] 解析文本消息失败: {}", e.getMessage());
                    return;
                }
            } else if ("post".equals(msgType)) {
                try {
                    Map<String, Object> contentMap = new Gson().fromJson(content,
                            new TypeToken<Map<String, Object>>() {}.getType());
                    Object contentObj = contentMap.get("content");
                    if (contentObj instanceof List) {
                        for (Object para : (List<?>) contentObj) {
                            if (para instanceof List) {
                                for (Object node : (List<?>) para) {
                                    if (node instanceof Map) {
                                        Map<String, Object> nodeMap = (Map<String, Object>) node;
                                        String tag = (String) nodeMap.get("tag");
                                        if ("text".equals(tag) && nodeMap.get("text") != null) {
                                            text += nodeMap.get("text");
                                        } else if ("img".equals(tag) && nodeMap.get("image_key") != null) {
                                            if (imagePath == null) {
                                                imagePath = downloadFeishuMedia(
                                                        (String) nodeMap.get("image_key"), "post", messageId);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[编程AI] 解析富文本失败: {}", e.getMessage());
                    return;
                }
            } else if ("image".equals(msgType)) {
                try {
                    Map<String, String> contentMap = new Gson().fromJson(content,
                            new TypeToken<Map<String, String>>() {}.getType());
                    String imageKey = contentMap.get("image_key");
                    if (imageKey != null) {
                        imagePath = downloadFeishuMedia(imageKey, "image", messageId);
                    }
                } catch (Exception e) {
                    log.warn("[编程AI] 解析图片消息失败: {}", e.getMessage());
                    return;
                }
            } else {
                return;
            }

            if (text == null) text = "";
            text = text.replaceAll("@[^\\s]+\\s*", "").trim();
            if (text.isEmpty() && imagePath == null) return;

            String userMsg = (imagePath != null ? "(截图: " + imagePath + ")\n" : "") + text;

            log.info("[编程AI] 收到消息: {}... | chatId={} | chatType={} | msgId={}",
                    userMsg.length() > 60 ? userMsg.substring(0, 60) + "..." : userMsg,
                    chatId, chatType, messageId);
            log.debug("[编程AI] 原始消息: msgType={} | content={}",
                    msgType, content.length() > 200 ? content.substring(0, 200) + "..." : content);

            String fChatId = chatId, fChatType = chatType, fMsgId = messageId, fMsg = userMsg;
            messageExecutor.execute(() -> processCodingRequest(fMsg, fChatId, fChatType, fMsgId));

        } catch (Exception e) {
            log.error("[编程AI] handleMessage异常: {}", e.getMessage(), e);
        }
    }

    private void processCodingRequest(String userMsg, String chatId, String chatType, String messageId) {
        String projectDir = "";
        try {
            sendImmediateReply(chatId, chatType, messageId);
            Config config = configService.load();
            projectDir = config.getCodingProjectDir();
            int timeout = config.getCodingPiTimeout() != null ? config.getCodingPiTimeout() : 300;

            if (projectDir == null || projectDir.trim().isEmpty()) {
                sendReply(chatId, chatType, messageId, "⚠️ 请先在配置页面设置「项目目录」");
                return;
            }

            log.info("[编程AI] 开始排查: chatId={}, msgId={}, dir={}", chatId, messageId, projectDir);
            CodePiService.CodePiResult result = codePiService.analyze(userMsg, projectDir, timeout);
            log.info("[编程AI] 排查完成: result={}, elapsed={}, output_len={}",
                    result.isSuccess() ? "成功" : "失败", result.getElapsedStr(),
                    result.isSuccess() ? result.getOutput().length() : 0);
            codingLog("排查", String.format("耗时 %s | %s | dir=%s", result.getElapsedStr(),
                    result.isSuccess() ? "成功" : "失败", projectDir));

            String source = "feishu";
            if (result.isSuccess()) {
                String output = result.getOutput();
                if (output.length() > 8000) output = output.substring(0, 8000) + "\n\n...（截断）";
                String cardBody = CodePiService.mdToFeishuCard("**排查结果如下：**\n" + output);
                sendCardReply(chatId, chatType, messageId, cardBody, "编程AI ⏱" + result.getElapsedStr());
                saveRecord(userMsg, output, result.getElapsedStr(), true, source, projectDir);
            } else {
                String err = result.getError() != null ? result.getError() : "未知错误";
                sendReply(chatId, chatType, messageId, "⚠️ 排查失败（" + result.getElapsedStr() + "）：" + err);
                saveRecord(userMsg, "失败: " + err, result.getElapsedStr(), false, source, projectDir);
            }
        } catch (Exception e) {
            log.error("[编程AI] 排查异常: {}", e.getMessage(), e);
            sendReply(chatId, chatType, messageId, "❌ 排查异常: " + e.getMessage());
            saveRecord(userMsg, "异常: " + e.getMessage(), "0s", false, "feishu", projectDir);
        }
    }

    // ===== 消息发送 =====

    private void sendImmediateReply(String chatId, String chatType, String messageId) {
        try {
            String waitingMsg = "{\"text\":\"🔍 编程AI正在排查中，请稍候...\"}";
            if ("p2p".equals(chatType)) {
                var req = com.lark.oapi.service.im.v1.model.CreateMessageReq.newBuilder()
                        .receiveIdType(com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum.CHAT_ID.getValue())
                        .createMessageReqBody(com.lark.oapi.service.im.v1.model.CreateMessageReqBody.newBuilder()
                                .receiveId(chatId).msgType("text").content(waitingMsg).build())
                        .build();
                var resp = client.im().message().create(req);
                if (resp.getData() != null && resp.getData().getMessageId() != null)
                    processedMessages.add(resp.getData().getMessageId());
            } else {
                var req = com.lark.oapi.service.im.v1.model.ReplyMessageReq.newBuilder()
                        .messageId(messageId)
                        .replyMessageReqBody(com.lark.oapi.service.im.v1.model.ReplyMessageReqBody.newBuilder()
                                .content(waitingMsg).msgType("text").build())
                        .build();
                client.im().message().reply(req);
            }
        } catch (Exception e) {
            log.warn("[编程AI] 发送等待提示失败: {}", e.getMessage());
        }
    }

    private void sendReply(String chatId, String chatType, String messageId, String text) {
        try {
            String content = "{\"text\":\"" + escapeJson(text) + "\"}";
            if ("p2p".equals(chatType)) {
                var req = com.lark.oapi.service.im.v1.model.CreateMessageReq.newBuilder()
                        .receiveIdType(com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum.CHAT_ID.getValue())
                        .createMessageReqBody(com.lark.oapi.service.im.v1.model.CreateMessageReqBody.newBuilder()
                                .receiveId(chatId).msgType("text").content(content).build())
                        .build();
                var resp = client.im().message().create(req);
                if (resp.getData() != null && resp.getData().getMessageId() != null)
                    processedMessages.add(resp.getData().getMessageId());
            } else {
                var req = com.lark.oapi.service.im.v1.model.ReplyMessageReq.newBuilder()
                        .messageId(messageId)
                        .replyMessageReqBody(com.lark.oapi.service.im.v1.model.ReplyMessageReqBody.newBuilder()
                                .content(content).msgType("text").build())
                        .build();
                client.im().message().reply(req);
            }
        } catch (Exception e) {
            log.error("[编程AI] 发送回复失败: {}", e.getMessage());
        }
    }

    private void sendCardReply(String chatId, String chatType, String messageId, String md, String title) {
        String card = String.format(
                "{\"config\":{\"wide_screen_mode\":true},\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"%s\"},\"template\":\"blue\"},\"elements\":[{\"tag\":\"markdown\",\"content\":\"%s\"}]}",
                escapeJson(title), escapeJson(md));
        try {
            if ("p2p".equals(chatType)) {
                var req = com.lark.oapi.service.im.v1.model.CreateMessageReq.newBuilder()
                        .receiveIdType(com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum.CHAT_ID.getValue())
                        .createMessageReqBody(com.lark.oapi.service.im.v1.model.CreateMessageReqBody.newBuilder()
                                .receiveId(chatId).msgType("interactive").content(card).build())
                        .build();
                var resp = client.im().message().create(req);
                if (resp.getData() != null && resp.getData().getMessageId() != null)
                    processedMessages.add(resp.getData().getMessageId());
            } else {
                var req = com.lark.oapi.service.im.v1.model.ReplyMessageReq.newBuilder()
                        .messageId(messageId)
                        .replyMessageReqBody(com.lark.oapi.service.im.v1.model.ReplyMessageReqBody.newBuilder()
                                .content(card).msgType("interactive").build())
                        .build();
                client.im().message().reply(req);
            }
        } catch (Exception e) {
            log.warn("[编程AI] 卡片发送失败，降级为文本: {}", e.getMessage());
            sendReply(chatId, chatType, messageId, md);
        }
    }

    public void sendTestMessage(String chatId, String text) throws Exception {
        String content = "{\"text\":\"" + escapeJson(text) + "\"}";
        var req = com.lark.oapi.service.im.v1.model.CreateMessageReq.newBuilder()
                .receiveIdType(com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum.CHAT_ID.getValue())
                .createMessageReqBody(com.lark.oapi.service.im.v1.model.CreateMessageReqBody.newBuilder()
                        .receiveId(chatId).msgType("text").content(content).build())
                .build();
        var resp = client.im().message().create(req);
        if (resp.getCode() != 0) {
            throw new RuntimeException("飞书API错误: " + resp.getCode() + " " + resp.getMsg());
        }
    }

    // ===== 飞书文件下载 =====

    private String downloadFeishuMedia(String fileKey, String msgType, String messageId) {
        Config config = configService.load();
        String projectDir = config.getCodingProjectDir();
        if (projectDir == null || projectDir.trim().isEmpty()) return null;

        File uploadDir = new File(projectDir, ".feishu_uploads");
        uploadDir.mkdirs();

        String token = getTenantToken();
        if (token == null) return null;

        try {
            String url;
            if (messageId != null && ("image".equals(msgType) || "post".equals(msgType))) {
                url = "https://open.feishu.cn/open-apis/im/v1/messages/"
                        + URLEncoder.encode(messageId, "UTF-8") + "/resources/"
                        + URLEncoder.encode(fileKey, "UTF-8") + "?type=image";
            } else if ("image".equals(msgType)) {
                url = "https://open.feishu.cn/open-apis/im/v1/images/" + URLEncoder.encode(fileKey, "UTF-8");
            } else {
                url = "https://open.feishu.cn/open-apis/im/v1/files/" + URLEncoder.encode(fileKey, "UTF-8") + "?type=file";
            }

            var conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            if (code != 200) {
                try (InputStream es = conn.getErrorStream()) {
                    String errBody = es != null ? new String(es.readAllBytes(), StandardCharsets.UTF_8) : "";
                    log.warn("[编程AI] 下载媒体失败 HTTP {}: {}", code, errBody.substring(0, Math.min(100, errBody.length())));
                }
                return null;
            }

            byte[] data;
            try (InputStream is = conn.getInputStream()) {
                data = is.readAllBytes();
            }
            if (data.length == 0) return null;

            String ext = guessExt(data);
            String filename = fileKey.substring(0, Math.min(20, fileKey.length())) + "_"
                    + System.currentTimeMillis() + "." + ext;
            File outFile = new File(uploadDir, filename);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(data);
            }
            return outFile.getAbsolutePath();

        } catch (Exception e) {
            log.warn("[编程AI] 下载媒体失败: {}", e.getMessage());
            return null;
        }
    }

    private String guessExt(byte[] data) {
        if (data.length < 4) return "bin";
        if (data[0] == (byte)0x89 && data[1] == (byte)0x50 && data[2] == (byte)0x4E && data[3] == (byte)0x47) return "png";
        if (data[0] == (byte)0xFF && data[1] == (byte)0xD8) return "jpg";
        if (data[0] == (byte)0x47 && data[1] == (byte)0x49 && data[2] == (byte)0x46) return "gif";
        if (data[0] == (byte)0x52 && data[1] == (byte)0x49 && data[2] == (byte)0x46 && data[3] == (byte)0x46) return "webp";
        return "bin";
    }

    private String getTenantToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }
        synchronized (tokenLock) {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
                return cachedToken;
            }
            try {
                Config config = configService.load();
                String appId = config.getCodingFeishuAppId();
                String appSecret = config.getCodingFeishuAppSecret();
                String body = "{\"app_id\":\"" + appId + "\",\"app_secret\":\"" + appSecret + "\"}";
                var conn = (java.net.HttpURLConnection)
                        new java.net.URL("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                        .openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                String resp;
                try (InputStream is = conn.getInputStream()) {
                    resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> json = new Gson().fromJson(resp, Map.class);
                String token = (String) json.get("tenant_access_token");
                if (token != null) {
                    cachedToken = token;
                    tokenExpiresAt = System.currentTimeMillis() + 5400_000;
                    return token;
                }
            } catch (Exception e) {
                log.warn("[编程AI] 获取token失败: {}", e.getMessage());
            }
            return null;
        }
    }

    // ===== 记录管理 =====

    public void codingLog(String action, String detail) {
        log.info("[编程AI] {} | {}", action, detail);
        logService.add("编程AI", action, detail);
    }

    public Long saveRecord(String message, String response, String elapsed, boolean success, String source, String projectDir) {
        try {
            CodingRecordEntity entity = new CodingRecordEntity();
            entity.setTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            entity.setMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
            entity.setResponse(response.length() > 5000 ? response.substring(0, 5000) : response);
            entity.setElapsed(elapsed);
            entity.setSuccess(success);
            entity.setSource(source);
            entity.setProjectDir(projectDir != null && projectDir.length() > 200 ? projectDir.substring(0, 200) : projectDir);
            recordDbService.save(entity);
            log.debug("[编程AI] 记录已保存: source={}, success={}, elapsed={}, id={}", source, success, elapsed, entity.getId());
            return entity.getId();
        } catch (Exception e) {
            log.error("[编程AI] 保存记录失败: {}", e.getMessage());
            return null;
        }
    }

    public List<CodingRecordEntity> getRecentRecords(int limit) {
        try {
            return recordDbService.findRecent(limit);
        } catch (Exception e) {
            log.warn("[编程AI] 查询记录失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<CodingRecordEntity> getAllRecords() {
        try {
            return recordDbService.list();
        } catch (Exception e) {
            log.warn("[编程AI] 查询全部记录失败: {}", e.getMessage());
            return List.of();
        }
    }

    public void updateRecord(CodingRecordEntity entity) {
        try {
            recordDbService.updateById(entity);
        } catch (Exception e) {
            log.error("[编程AI] 更新记录失败: {}", e.getMessage());
        }
    }

    public CodingRecordEntity getRecordById(Long id) {
        try {
            return recordDbService.getById(id);
        } catch (Exception e) {
            log.warn("[编程AI] 查询记录失败: {}", e.getMessage());
            return null;
        }
    }

    // ===== 工具方法 =====

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @PreDestroy
    public void destroy() {
        if (wsClient != null) {
            try {
                wsClient.getClass().getMethod("stop").invoke(wsClient);
            } catch (Exception e) {
                log.info("[编程AI] SDK 未提供 stop()，依赖 JVM 清理");
            }
            wsClient = null;
        }
        messageExecutor.shutdown();
        log.info("[编程AI] 已关闭");
    }

    private void closeWsOnly() {
        if (wsClient != null) {
            try { wsClient.getClass().getMethod("stop").invoke(wsClient); } catch (Exception ignored) {}
            wsClient = null;
        }
    }

    public void restart() {
        closeWsOnly();
        Config config = configService.load();
        String appId = config.getCodingFeishuAppId();
        String appSecret = config.getCodingFeishuAppSecret();
        if (appId != null && !appId.isEmpty() && appSecret != null && !appSecret.isEmpty()) {
            startLongConnection(appId, appSecret);
        }
    }

    public boolean isConnected() {
        return wsClient != null;
    }
}