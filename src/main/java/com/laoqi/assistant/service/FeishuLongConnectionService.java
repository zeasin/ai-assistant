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

    private com.lark.oapi.ws.Client wsClient;
    private Client client;
    private String feishuSessionId;
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();

    public FeishuLongConnectionService(ConfigService configService,
                                        OpenCodeService openCodeService,
                                        LogService logService) {
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.logService = logService;
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

        startLongConnection(appId, appSecret);
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

            wsClient.start();
            log.info("[飞书长连接] ✅ 启动成功，等待消息...");
            logService.add("飞书长连接", "启动成功", "");

        } catch (Exception e) {
            log.error("[飞书长连接] ❌ 启动失败: {}", e.getMessage(), e);
            logService.add("飞书长连接", "启动失败", e.getMessage());
        }
    }

    private void handleMessage(P2MessageReceiveV1 event) throws Exception {
        try {
            String msgType = event.getEvent().getMessage().getMessageType();
            String chatId = event.getEvent().getMessage().getChatId();
            String content = event.getEvent().getMessage().getContent();
            String chatType = event.getEvent().getMessage().getChatType();

            log.info("[飞书长连接] 收到消息: msgType={}, chatType={}", msgType, chatType);
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

            String finalChatId = chatId;
            String finalChatType = chatType;
            String finalMessageId = messageId;
            new Thread(() -> {
                try {
                    sendImmediateReply(finalChatId, finalChatType, finalMessageId);
                    String reply = processMessage(text);
                    if (reply != null && !reply.isEmpty()) {
                        sendReply(finalChatId, reply, finalChatType, finalMessageId);
                    }
                } catch (Exception e) {
                    log.error("[飞书长连接] 处理消息失败: {}", e.getMessage(), e);
                }
            }).start();

        } catch (Exception e) {
            log.error("[飞书长连接] 处理消息失败: {}", e.getMessage(), e);
        }
    }

    private String processMessage(String text) {
        try {
            if (!openCodeService.isHealthy()) {
                log.warn("[飞书长连接] AI 服务未启动");
                return "⚠️ AI 服务未启动，请稍后再试";
            }

            if (feishuSessionId == null) {
                feishuSessionId = openCodeService.findIdleSession();
                if (feishuSessionId == null) {
                    feishuSessionId = openCodeService.createSession("飞书对话");
                }
            }

            log.info("[飞书长连接] 发送给 AI: {}", text);
            String reply = openCodeService.sendMessage(feishuSessionId, text);
            log.info("[飞书长连接] AI 回复长度: {}", reply != null ? reply.length() : 0);

            return reply;

        } catch (Exception e) {
            log.error("[飞书长连接] 处理消息失败: {}", e.getMessage(), e);
            logService.add("飞书消息", "处理失败", e.getMessage());
            return "❌ 处理失败: " + e.getMessage();
        }
    }

    private void sendImmediateReply(String chatId, String chatType, String messageId) {
        try {
            String waitingMsg = "🤖 收到消息，正在思考中，请稍候...";
            String replyContent = "{\"text\":\"" + escapeJson(waitingMsg) + "\"}";

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
            String replyContent = "{\"text\":\"" + escapeJson(reply) + "\"}";

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
            log.info("[飞书长连接] 已关闭");
            wsClient = null;
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
