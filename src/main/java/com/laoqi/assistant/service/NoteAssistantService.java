package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.LlmProfileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class NoteAssistantService {

    private static final Logger log = LoggerFactory.getLogger(NoteAssistantService.class);

    private final LlmConfigResolver configResolver;
    private final NoteTools noteTools;
    private final SessionService sessionService;

    private volatile ChatClient defaultClient;
    private volatile String cachedConfigKey = "";
    private final ConcurrentHashMap<String, ChatClient> modelClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> modelConfigKeys = new ConcurrentHashMap<>();

    public NoteAssistantService(LlmConfigResolver configResolver, NoteTools noteTools, SessionService sessionService) {
        this.configResolver = configResolver;
        this.noteTools = noteTools;
        this.sessionService = sessionService;
    }

    public boolean isAvailable() {
        return configResolver.isAvailable();
    }

    public boolean needsOrchestration(String userMessage) {
        return true;
    }

    public String chat(String sessionId, String userMessage, String mode) throws Exception {
        return chat(sessionId, userMessage, mode, null);
    }

    public String chat(String sessionId, String userMessage, String mode, String modelName) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("ChatClient 初始化失败");
        }

        String context = sessionService.buildHistoryContext(sessionId, mode, userMessage);
        String fullMessage;
        if (context != null) {
            fullMessage = context + "\n\n---\n\n用户最新消息:\n" + userMessage;
            log.info("[编排] 注入了历史上下文（含语义检索），总消息长度={}", fullMessage.length());
        } else {
            fullMessage = userMessage;
            log.info("[编排] 无历史上下文，仅当前消息");
        }

        log.info("[编排] 用户: {} (session={}, model={})", userMessage, sessionId,
                modelName != null ? modelName : "default");

        String reply = client.prompt()
                .system(SYSTEM_PROMPT)
                .user(fullMessage)
                .call()
                .content();

        log.info("[编排] 回复长度: {}", reply != null ? reply.length() : 0);
        return reply != null ? reply : "（AI 未返回回复）";
    }

    public String chat(String sessionId, String userMessage) throws Exception {
        return chat(sessionId, userMessage, "knowledge", null);
    }

    public String streamChat(String sessionId, String userMessage, String mode, String modelName, Consumer<String> chunkCallback) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("ChatClient 初始化失败");
        }

        String context = sessionService.buildHistoryContext(sessionId, mode, userMessage);
        String fullMessage;
        if (context != null) {
            fullMessage = context + "\n\n---\n\n用户最新消息:\n" + userMessage;
            log.info("[编排] 注入了历史上下文（含语义检索），总消息长度={}", fullMessage.length());
        } else {
            fullMessage = userMessage;
            log.info("[编排] 无历史上下文，仅当前消息");
        }

        log.info("[编排] 用户: {} (session={}, model={})", userMessage, sessionId,
                modelName != null ? modelName : "default");

        StringBuilder fullReply = new StringBuilder();
        boolean[] isFirstChunk = {true};
        client.prompt()
                .system(SYSTEM_PROMPT)
                .user(fullMessage)
                .stream()
                .content()
                .toStream()
                .forEach(chunk -> {
                    if (chunk != null && !chunk.isEmpty()) {
                        String processedChunk = chunk;
                        if (isFirstChunk[0]) {
                            processedChunk = chunk.replaceFirst("^[\\s\\r\\n]+", "");
                            isFirstChunk[0] = false;
                        }
                        processedChunk = processedChunk.replaceAll("\\n{3,}", "\n\n");
                        fullReply.append(processedChunk);
                        if (chunkCallback != null) {
                            chunkCallback.accept(processedChunk);
                        }
                        log.debug("[编排] 流式接收: {} chars", processedChunk.length());
                    }
                });

        String reply = fullReply.toString().trim().replaceAll("\\n{3,}", "\n\n");
        log.info("[编排] 回复长度: {}", reply.length());
        return reply.isEmpty() ? "（AI 未返回回复）" : reply;
    }

    private ChatClient getOrCreateClient(String modelName) {
        if (!isAvailable()) return null;

        if (modelName == null || modelName.isEmpty()) {
            String currentKey = buildConfigKey();
            if (defaultClient == null || !currentKey.equals(cachedConfigKey)) {
                defaultClient = createDefaultClient();
                cachedConfigKey = currentKey;
            }
            return defaultClient;
        }

        String cacheKey = modelName;
        String currentKey = buildConfigKey(modelName);
        String cachedKey = modelConfigKeys.get(cacheKey);
        if (cachedKey == null || !cachedKey.equals(currentKey)) {
            ChatClient client = createChatClientFromName(modelName);
            modelClients.put(cacheKey, client);
            modelConfigKeys.put(cacheKey, currentKey);
            log.info("NoteAssistant ChatClient 已重建 (model={})", modelName);
        }
        return modelClients.get(cacheKey);
    }

    private String buildConfigKey() {
        return configResolver.resolveBaseUrl() + "|"
                + configResolver.resolveModel() + "|"
                + configResolver.resolveApiKey().hashCode();
    }

    private String buildConfigKey(String modelName) {
        return configResolver.resolveBaseUrl(modelName) + "|"
                + configResolver.resolveModel(modelName) + "|"
                + configResolver.resolveApiKey(modelName).hashCode();
    }

    private ChatClient createDefaultClient() {
        String apiKey = configResolver.resolveApiKey();
        String baseUrl = configResolver.resolveBaseUrl();
        String model = configResolver.resolveModel();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(configResolver.buildRestClientBuilder())
                .build();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .build();

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(options)
                .build();

        return ChatClient.builder(chatModel)
                .defaultTools(noteTools)
                .build();
    }

    private ChatClient createChatClient(String modelName, LlmProfileEntity profile) {
        String apiKey = profile.getApiKey();
        String baseUrl = profile.getBaseUrl();
        String model = profile.getModel();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(configResolver.buildRestClientBuilder())
                .build();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .build();

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(options)
                .build();

        return ChatClient.builder(chatModel)
                .defaultTools(noteTools)
                .build();
    }

    private ChatClient createChatClientFromName(String modelName) {
        LlmProfileEntity profile = configResolver.getProfileByName(modelName);
        if (profile != null) {
            return createChatClient(modelName, profile);
        }
        return createDefaultClient();
    }

    private static final String SYSTEM_PROMPT = """
            你是一个笔记库助手。每次处理请求前，必须先读取笔记库的规则文件。

            == 不可跳过的第一步：读取规则 ==
            调用 readFile("AGENTS.md") 了解：
            - 笔记库目录结构
            - 各类数据的保存位置和字段格式
            - 编号规则（如 BUG 编号 B001 递增）

            如果 AGENTS.md 中引用了其他规则文件（如 AI/记忆/README.md），也要一并读取。

            == 第二步：处理请求 ==
            了解规则后，严格按 AGENTS.md 中定义的规则执行：
            1. 按规则找目标目录（searchFiles/listDir）
            2. 读取现有数据（data.json）
            3. 按规则格式生成 JSON
            4. 用 writeFile 保存
            5. 用中文告知用户

            == 硬性规则 ==
            - 必须先读规则，再执行操作。不假设路径，不猜格式
            - 读取规则文件是第一步，不可跳过
            - AGENTS.md 可能随时更新，每次都要重新读取
            - 写入 JSON 时先读取现有数据，合并后写入，不能覆盖
            - 有些记录需要同时更新多个文件（如规则中定义的）
            """;
}