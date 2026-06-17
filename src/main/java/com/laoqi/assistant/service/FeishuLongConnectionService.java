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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class FeishuLongConnectionService {

    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnectionService.class);

    private final ConfigService configService;
    private final LlmService llmService;
    private final NoteAssistantService noteAssistantService;
    private final LogService logService;
    private final FeishuChatSessionService feishuChatSessionService;
    private final AppConfig appConfig;

    private com.lark.oapi.ws.Client wsClient;
    private Client client;
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();

    // 固定大小线程池处理飞书消息，防止高并发时创建大量线程
    private final ExecutorService messageExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "feishu-msg");
        t.setDaemon(true);
        return t;
    });

    public FeishuLongConnectionService(ConfigService configService,
                                        LlmService llmService,
                                        NoteAssistantService noteAssistantService,
                                        LogService logService,
                                        FeishuChatSessionService feishuChatSessionService,
                                        AppConfig appConfig) {
        this.configService = configService;
        this.llmService = llmService;
        this.noteAssistantService = noteAssistantService;
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

            // 群聊消息必须 @机器人 才处理，私聊消息全部处理
            if (!"p2p".equals(chatType)) {
                var mentions = event.getEvent().getMessage().getMentions();
                if (mentions == null || mentions.length == 0) {
                    log.debug("[飞书长连接] 群聊消息未 @机器人，跳过");
                    return;
                }
            }

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
                // 只移除最老的 500 条，而非全部清空，防止飞书重推时重复处理
                var it = processedMessages.iterator();
                int toRemove = 500;
                while (it.hasNext() && toRemove-- > 0) {
                    it.next();
                    it.remove();
                }
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
            messageExecutor.execute(() -> {
                try {
                    log.info("[飞书长连接] 开始处理消息: {}", finalText);
                    sendImmediateReply(finalChatId, finalChatType, finalMessageId);
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
            });

        } catch (Exception e) {
            log.error("[飞书长连接] 处理消息失败: {}", e.getMessage(), e);
        }
    }

    private String processMessage(String text, String userKey) {
        try {
            log.info("[飞书长连接] 处理普通消息");
            if (!llmService.isAvailable()) {
                return "⚠️ LLM API Key 未配置，请在配置页填写";
            }
            // 用 NoteAssistantService（含工具编排），AI 自动决定是否使用工具
            return processNoteAssistant(text, userKey);

        } catch (Exception e) {
            log.error("[飞书长连接] 处理消息失败: {}", e.getMessage(), e);
            logService.add("飞书消息", "处理失败", e.getMessage());
            return "❌ 处理失败: " + e.getMessage();
        }
    }

    private String processNoteAssistant(String text, String userKey) {
        // 工具编排模式
        feishuChatSessionService.saveMessage(userKey, "user", text, "knowledge");
        try {
            log.info("[飞书长连接] NoteAssistant 编排: {}", text);
            String reply = noteAssistantService.chat(userKey, text);
            log.info("[飞书长连接] NoteAssistant 回复长度: {}", reply != null ? reply.length() : 0);
            feishuChatSessionService.saveMessage(userKey, "assistant", reply, "knowledge");
            return reply;
        } catch (Exception e) {
            log.error("[飞书长连接] NoteAssistant 处理失败: {}", e.getMessage(), e);
            logService.add("飞书消息", "处理失败", e.getMessage());
            return "❌ 编排处理失败: " + e.getMessage();
        }
    }

    private String processNormalMessageDirect(String text, String userKey) {
        // 新模式：Java 直连 LLM（纯问答）
        feishuChatSessionService.saveMessage(userKey, "user", text, "knowledge");
        try {
            String context = feishuChatSessionService.buildHistoryContext(userKey, "knowledge", text);
            StringBuilder fullText = new StringBuilder();
            if (context != null) {
                log.info("[飞书长连接] 恢复知识库历史上下文");
                fullText.append(context).append("\n\n---\n\n");
            }
            fullText.append("用户最新消息:\n").append(text);

            log.info("[飞书长连接] 发送给 LLM 直连: {}", fullText);
            String reply = llmService.chat("你是一个知识库助手，基于笔记库上下文回答问题。请用中文回答。", fullText.toString());
            log.info("[飞书长连接] LLM 回复长度: {}", reply != null ? reply.length() : 0);

            feishuChatSessionService.saveMessage(userKey, "assistant", reply, "knowledge");
            return reply;

        } catch (Exception e) {
            log.error("[飞书长连接] LLM 直连处理失败: {}", e.getMessage(), e);
            logService.add("飞书消息", "处理失败", e.getMessage());
            return "❌ 处理失败: " + e.getMessage();
        }
    }

    private void sendImmediateReply(String chatId, String chatType, String messageId) {
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

                var immediateResp = client.im().message().create(req);
                // 记录已发送消息 ID，防止循环处理
                if (immediateResp.getData() != null && immediateResp.getData().getMessageId() != null) {
                    processedMessages.add(immediateResp.getData().getMessageId());
                }
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
                    // 记录已发送消息 ID，防止 WebSocket 回推导致循环处理
                    String sentMsgId = resp.getData() != null ? resp.getData().getMessageId() : null;
                    if (sentMsgId != null) {
                        processedMessages.add(sentMsgId);
                        log.info("[飞书长连接] ✅ 已回复 (msgId={})", sentMsgId);
                    } else {
                        log.info("[飞书长连接] ✅ 已回复");
                    }
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
            try {
                // 尝试调用 SDK 的关闭方法
                wsClient.getClass().getMethod("stop").invoke(wsClient);
            } catch (Exception e) {
                log.info("[飞书长连接] SDK 未提供 stop() 方法，依赖 JVM 清理连接");
            }
            wsClient = null;
        }
        // 关闭消息处理线程池
        messageExecutor.shutdown();
        log.info("[飞书长连接] 已关闭");
    }

    /**
     * 仅关闭 WebSocket（用于 restart），不关闭线程池。
     */
    private void closeWsOnly() {
        if (wsClient != null) {
            try {
                wsClient.getClass().getMethod("stop").invoke(wsClient);
            } catch (Exception e) {
                // ignore
            }
            wsClient = null;
        }
    }

    public void restart() {
        closeWsOnly();
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
