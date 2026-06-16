package com.laoqi.assistant.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lark.oapi.Client;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.enums.MsgTypeEnum;
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ext.MessageText;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeishuLongConnectionService {

    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnectionService.class);

    private final ConfigService configService;
    private final OpenCodeService openCodeService;
    private final LogService logService;
    private final FeishuChatSessionService feishuChatSessionService;
    private final AppConfig appConfig;

    private com.lark.oapi.ws.Client wsClient;
    private Client client;
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();

    public FeishuLongConnectionService(ConfigService configService,
                                        OpenCodeService openCodeService,
                                        LogService logService,
                                        FeishuChatSessionService feishuChatSessionService,
                                        AppConfig appConfig) {
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.logService = logService;
        this.feishuChatSessionService = feishuChatSessionService;
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void init() {
        Config config = configService.load();
        if (!config.isFeishuPollingEnabled()) {
            log.info("[飞书长连接] 未启用，跳过初始化");
            return;
        }

        String appId = config.getFeishuAppId();
        String appSecret = config.getFeishuAppSecret();

        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            log.warn("[飞书长连接] 配置不完整，跳过初始化");
            return;
        }

        try {
            startLongConnection(appId, appSecret);
        } catch (Exception e) {
            log.error("[飞书长连接] 初始化失败: {}", e.getMessage(), e);
            logService.add("飞书长连接", "初始化失败", e.getMessage());
        }
    }

    public void startLongConnection(String appId, String appSecret) {
        try {
            log.info("[飞书长连接] 正在启动... appId={}", appId.substring(0, Math.min(8, appId.length())) + "...");

            client = new Client.Builder(appId, appSecret).build();

            EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                    .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) throws Exception {
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
                    log.info("[飞书长连接] ✅ 启动成功，等待消息...");
                    logService.add("飞书长连接", "启动成功", "");
                } catch (Exception e) {
                    log.error("[飞书长连接] ❌ 启动失败: {}", e.getMessage(), e);
                    logService.add("飞书长连接", "启动失败", e.getMessage());
                }
            }, "feishu-ws-connector").start();

        } catch (Exception e) {
            log.error("[飞书长连接] ❌ 初始化失败: {}", e.getMessage(), e);
            logService.add("飞书长连接", "初始化失败", e.getMessage());
        }
    }

    private void handleMessage(P2MessageReceiveV1 event) throws Exception {
        try {
            String msgType = event.getEvent().getMessage().getMessageType();
            String chatId = event.getEvent().getMessage().getChatId();
            String content = event.getEvent().getMessage().getContent();
            String chatType = event.getEvent().getMessage().getChatType();

            // 获取发送者信息
            String senderOpenId = null;
            try {
                senderOpenId = event.getEvent().getSender().getSenderId().getOpenId();
            } catch (Exception e) {
                log.warn("[飞书长连接] 获取发送者信息失败", e);
            }
            if (senderOpenId == null) senderOpenId = "unknown";

            log.info("[飞书长连接] 收到消息: msgType={}, chatType={}, sender={}", msgType, chatType, senderOpenId);
            log.info("[飞书长连接] 消息内容: {}", content);

            if (!"text".equals(msgType)) {
                log.debug("[飞书长连接] 非文本消息，跳过");
                return;
            }

            Map<String, String> contentMap;
            try {
                contentMap = new Gson().fromJson(content, new TypeToken<Map<String, String>>() {}.getType());
            } catch (Exception e) {
                log.warn("[飞书长连接] 解析消息内容失败");
                return;
            }

            String text = contentMap.get("text");
            if (text == null || text.trim().isEmpty()) {
                log.warn("[飞书长连接] 文本内容为空");
                return;
            }

            log.info("[飞书长连接] ✅ 收到用户消息: {}", text);
            logService.add("飞书消息", "收到", text);

            String messageId = event.getEvent().getMessage().getMessageId();

            if (processedMessages.contains(messageId)) {
                log.info("[飞书长连接] 消息已处理过，跳过: {}", messageId);
                return;
            }
            processedMessages.add(messageId);

            if (processedMessages.size() > 1000) {
                processedMessages.clear();
            }

            // 构造用户标识: p2p/openId 或 group/chatId/openId
            String userKey = "p2p".equals(chatType)
                    ? "p2p:" + senderOpenId
                    : "group:" + chatId + ":" + senderOpenId;

            // 获取或创建持久化会话
            feishuChatSessionService.getOrCreate(userKey, chatId, chatType);

            String finalChatId = chatId;
            String finalChatType = chatType;
            String finalMessageId = messageId;
            String finalText = text;
            String finalUserKey = userKey;
            new Thread(() -> {
                try {
                    log.info("[飞书长连接] 开始处理消息: {}", finalText);
                    boolean isCodeRelated = false;
                    sendImmediateReply(finalChatId, finalChatType, finalMessageId, isCodeRelated);
                    String reply = processMessage(finalText, finalUserKey);
                    log.info("[飞书长连接] 消息处理完成，回复长度: {}", reply != null ? reply.length() : 0);
                    if (reply != null && !reply.isEmpty()) {
                        sendReply(finalChatId, reply, finalChatType, finalMessageId);
                    } else {
                        log.warn("[飞书长连接] 回复为空，跳过发送");
                        sendReply(finalChatId, "⚠️ 处理失败，未获取到回复", finalChatType, finalMessageId);
                    }
                } catch (Exception e) {
                    log.error("[飞书长连接] 处理消息失败: {}", e.getMessage(), e);
                    try {
                        sendReply(finalChatId, "❌ 处理消息时发生错误: " + e.getMessage(), finalChatType, finalMessageId);
                    } catch (Exception ex) {
                        log.error("[飞书长连接] 发送错误消息失败: {}", ex.getMessage(), ex);
                    }
                }
            }, "feishu-message-processor").start();

        } catch (Exception e) {
            log.error("[飞书长连接] 处理消息失败: {}", e.getMessage(), e);
        }
    }

    private String processMessage(String text, String userKey) {
        try {
            log.info("[飞书长连接] 处理普通消息");
            if (!openCodeService.isHealthy()) {
                log.warn("[飞书长连接] AI 服务(14096)未启动");
                return "⚠️ AI 服务未启动，请先启动 14096 端口的 opencode 服务";
            }
            return processNormalMessage(text, userKey);

        } catch (Exception e) {
            log.error("[飞书长连接] 处理消息失败: {}", e.getMessage(), e);
            logService.add("飞书消息", "处理失败", e.getMessage());
            return "❌ 处理失败: " + e.getMessage();
        }
    }

    private String processNormalMessage(String text, String userKey) {
        // 保存用户消息，标注为 knowledge 模式
        feishuChatSessionService.saveMessage(userKey, "user", text, "knowledge");
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                // 每次新建 session，恢复历史上下文
                String sessionId = openCodeService.createSession("飞书-" + userKey);
                String context = feishuChatSessionService.buildHistoryContext(userKey, "knowledge", text);
                StringBuilder fullText = new StringBuilder();
                if (context != null) {
                    log.info("[飞书长连接] 恢复知识库历史上下文");
                    fullText.append(context).append("\n\n---\n\n");
                }
                fullText.append("用户最新消息:\n").append(text);

                log.info("[飞书长连接] 发送给 AI: {}", fullText);
                String reply = openCodeService.sendMessage(sessionId, fullText.toString());
                log.info("[飞书长连接] AI 回复长度: {}", reply != null ? reply.length() : 0);

                feishuChatSessionService.saveMessage(userKey, "assistant", reply, "knowledge");

                return reply;

            } catch (Exception e) {
                log.warn("[飞书长连接] 处理普通消息失败(第{}次): {}", attempt + 1, e.getMessage());
                lastError = e;
            }
        }
        log.error("[飞书长连接] 处理普通消息失败", lastError);
        logService.add("飞书消息", "处理失败", lastError.getMessage());
        return "❌ 处理失败: " + lastError.getMessage();
    }

    private void sendImmediateReply(String chatId, String chatType, String messageId, boolean isCodeRelated) {
        try {
            String waitingMsg = "📚 收到消息，AI正在思考中，请稍候...";
            Map<String, String> contentMap = Map.of("text", waitingMsg);
            String replyContent = new Gson().toJson(contentMap);

            if ("p2p".equals(chatType)) {
                CreateMessageReq req = CreateMessageReq.newBuilder()
                        .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                        .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                .receiveId(chatId)
                                .msgType(MsgTypeEnum.MSG_TYPE_TEXT.getValue())
                                .content(replyContent)
                                .build())
                        .build();

                client.im().message().create(req);
                log.info("[飞书长连接] 已发送等待提示");
            } else {
                var req = com.lark.oapi.service.im.v1.model.ReplyMessageReq.newBuilder()
                        .messageId(messageId)
                        .replyMessageReqBody(com.lark.oapi.service.im.v1.model.ReplyMessageReqBody.newBuilder()
                                .content(replyContent)
                                .msgType("text")
                                .build())
                        .build();

                client.im().message().reply(req);
                log.info("[飞书长连接] 已发送等待提示");
            }
        } catch (Exception e) {
            log.warn("[飞书长连接] 发送等待提示失败: {}", e.getMessage());
        }
    }

    private void sendReply(String chatId, String reply, String chatType, String messageId) {
        try {
            Map<String, String> contentMap = Map.of("text", reply);
            String replyContent = new Gson().toJson(contentMap);

            if ("p2p".equals(chatType)) {
                CreateMessageReq req = CreateMessageReq.newBuilder()
                        .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                        .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                .receiveId(chatId)
                                .msgType(MsgTypeEnum.MSG_TYPE_TEXT.getValue())
                                .content(replyContent)
                                .build())
                        .build();

                CreateMessageResp resp = client.im().message().create(req);
                if (resp.getCode() != 0) {
                    log.warn("[飞书长连接] 发送消息失败: {}", resp.getMsg());
                } else {
                    log.info("[飞书长连接] ✅ 已回复");
                    logService.add("飞书消息", "已回复", "");
                }
            } else {
                var req = com.lark.oapi.service.im.v1.model.ReplyMessageReq.newBuilder()
                        .messageId(messageId)
                        .replyMessageReqBody(com.lark.oapi.service.im.v1.model.ReplyMessageReqBody.newBuilder()
                                .content(replyContent)
                                .msgType("text")
                                .build())
                        .build();

                var resp = client.im().message().reply(req);
                if (resp.getCode() != 0) {
                    log.warn("[飞书长连接] 回复消息失败: {}", resp.getMsg());
                } else {
                    log.info("[飞书长连接] ✅ 已回复");
                    logService.add("飞书消息", "已回复", "");
                }
            }
        } catch (Exception e) {
            log.error("[飞书长连接] 发送回复失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (wsClient != null) {
            wsClient = null;
            log.info("[飞书长连接] 已关闭（SDK 未提供公开关闭方法，依赖 JVM 清理连接）");
        }
    }

    public void restart() {
        destroy();
        Config config = configService.load();
        String appId = config.getFeishuAppId();
        String appSecret = config.getFeishuAppSecret();
        if (appId != null && !appId.isEmpty() && appSecret != null && !appSecret.isEmpty()) {
            startLongConnection(appId, appSecret);
        }
    }

    public boolean isConnected() {
        return wsClient != null;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
