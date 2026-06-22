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
    private final ToolRegistry toolRegistry;
    private final SessionService sessionService;
    private final ContextBuilder contextBuilder;

    private volatile ChatClient defaultClient;
    private volatile String cachedConfigKey = "";
    private final ConcurrentHashMap<String, ChatClient> modelClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> modelConfigKeys = new ConcurrentHashMap<>();

    public NoteAssistantService(LlmConfigResolver configResolver, ToolRegistry toolRegistry, 
                               SessionService sessionService, ContextBuilder contextBuilder) {
        this.configResolver = configResolver;
        this.toolRegistry = toolRegistry;
        this.sessionService = sessionService;
        this.contextBuilder = contextBuilder;
    }

    public boolean isAvailable() {
        return configResolver.isAvailable();
    }

    public boolean needsOrchestration(String userMessage) {
        return true;
    }

    public String chat(String sessionId, String userMessage, String mode) throws Exception {
        return chat(sessionId, userMessage, mode, null, null);
    }

    public String chat(String sessionId, String userMessage, String mode, Long kbId) throws Exception {
        return chat(sessionId, userMessage, mode, kbId, null);
    }

    public String chat(String sessionId, String userMessage, String mode, Long kbId, String modelName) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("ChatClient 初始化失败");
        }

        NoteTools.setCurrentKbId(kbId);
        try {
            ContextBuilder.ChatContext context = contextBuilder.build(sessionId, userMessage, kbId, mode);
            ContextBuilder.MergedContext merged = contextBuilder.merge(context, userMessage);

            int noteCount = context.relevantNotes() != null ? context.relevantNotes().size() : 0;
            log.info("[编排] 上下文构建完成，system长度={}, user长度={}, 相关笔记={}, mode={}",
                    merged.systemContext().length(), merged.userMessage().length(), noteCount, mode);

            log.info("[编排] 用户: {} (session={}, kbId={}, model={})", userMessage, sessionId, kbId,
                    modelName != null ? modelName : "default");

            String reply = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .system(merged.systemContext())
                    .user(merged.userMessage())
                    .call()
                    .content();

            log.info("[编排] 回复长度: {}", reply != null ? reply.length() : 0);
            return reply != null ? reply : "（AI 未返回回复）";
        } finally {
            NoteTools.clearCurrentKbId();
        }
    }

    public String chat(String sessionId, String userMessage) throws Exception {
        return chat(sessionId, userMessage, "knowledge", null, null);
    }

    public String streamChat(String sessionId, String userMessage, String mode, Long kbId, String modelName, Consumer<String> chunkCallback) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("ChatClient 初始化失败");
        }

        NoteTools.setCurrentKbId(kbId);
        try {
            ContextBuilder.ChatContext context = contextBuilder.build(sessionId, userMessage, kbId, mode);
            ContextBuilder.MergedContext merged = contextBuilder.merge(context, userMessage);

            int noteCount = context.relevantNotes() != null ? context.relevantNotes().size() : 0;
            log.info("[编排] 上下文构建完成，system长度={}, user长度={}, 相关笔记={}, mode={}",
                    merged.systemContext().length(), merged.userMessage().length(), noteCount, mode);

            log.info("[编排] 用户: {} (session={}, kbId={}, model={})", userMessage, sessionId, kbId,
                    modelName != null ? modelName : "default");

            StringBuilder fullReply = new StringBuilder();
            boolean[] isFirstChunk = {true};
            client.prompt()
                    .system(SYSTEM_PROMPT)
                    .system(merged.systemContext())
                    .user(merged.userMessage())
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
        } finally {
            NoteTools.clearCurrentKbId();
        }
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
                .defaultTools(toolRegistry.getToolArray())
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
                .defaultTools(toolRegistry.getToolArray())
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
            你是一个笔记库助手，基于当前知识库的内容帮助用户。

            == 核心工具 ==
            1. searchNotes(query, limit) - 当系统提供的笔记内容不足时搜索笔记
            2. searchFiles(keyword) - 按文件名搜索
            3. readFile(path) / readNote(path) - 读取文件内容
            4. writeFile(path, content) - 写入文件
            5. listDir(path) - 列出目录

            == 工作流程 ==
            1. 理解用户意图
            2. 系统消息中已注入：
               - AGENTS.md（当前知识库的规则文件）
               - 相关笔记内容（已按语义排序的搜索结果）
               - 最近对话历史
            3. **优先使用系统消息中已注入的内容回答**，无需重复调用工具搜索
            4. 只有当注入的信息确实不足以回答时，才调用 searchNotes 补充搜索
            5. 需要时用 readFile 读取特定文件了解格式或内容
            6. 需要时用 writeFile 保存新笔记

            == 重要原则 ==
            - 系统消息中已包含自动搜索到的相关笔记，请优先基于这些内容回答
            - 如果搜索无结果，再用 searchFiles 按文件名搜索
            - 引用笔记时标注来源：[来源: 文件路径]
            - **writeFile 不要修改 AGENTS.md，除非用户明确要求**
            - 用中文回复
            """;
}