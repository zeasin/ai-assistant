package com.laoqi.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Service;

/**
 * 笔记库 AI 编排服务 — Spring AI 2.0 版。
 *
 * 通过 ChatClient + @Tool 注解实现工具编排，替代了之前 300 行的手动 ReAct 循环。
 * AI 自动通过 ToolCallingAdvisor 判断是否调用工具，无需 Java 代码手动路由。
 *
 * ██████████████████████████████████████████████████████
 * 底层使用 Spring AI DeepSeekChatModel 作为
 * 通用 OpenAI 兼容客户端（提供商无关）。
 *
 * baseUrl / model 通过 /config 页面配置即可切换：
 *   DeepSeek     → https://api.deepseek.com
 *   商汤 SenseNova → https://token.sensenova.cn/v1
 * ██████████████████████████████████████████████████████
 *
 * needsOrchestration() 保持兼容（始终返回 true，让 AI 自己判断）。
 */
@Service
public class NoteAssistantService {

    private static final Logger log = LoggerFactory.getLogger(NoteAssistantService.class);

    private final LlmConfigResolver configResolver;
    private final NoteTools noteTools;
    private final SessionService sessionService;
    private final Object configLock = new Object();

    // 懒初始化 ChatClient，配置变更时自动重建
    private volatile ChatClient chatClient;
    private volatile String cachedConfigKey = "";

    public NoteAssistantService(LlmConfigResolver configResolver, NoteTools noteTools, SessionService sessionService) {
        this.configResolver = configResolver;
        this.noteTools = noteTools;
        this.sessionService = sessionService;
    }

    public boolean isAvailable() {
        return configResolver.isAvailable();
    }

    /**
     * 判断是否需要工具编排。
     * 在 Spring AI 2.0 中，AI 通过 ToolCallingAdvisor 自动判断是否使用工具，
     * 不再需要 Java 代码手动路由。此方法保留兼容性，始终返回 true。
     */
    public boolean needsOrchestration(String userMessage) {
        return true;
    }

    /**
     * 执行 AI 编排 — 使用 ChatClient + @Tool。
     * AI 自动决定是否调用工具（读文件/写文件/搜索等），无需手写 ReAct 循环。
     * 自动注入历史对话上下文（含向量召回语义检索）。
     */
    public String chat(String sessionId, String userMessage, String mode) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        ChatClient client = getOrCreateClient();
        if (client == null) {
            throw new IllegalStateException("ChatClient 初始化失败");
        }

        // 构建历史上下文（含向量召回语义检索）
        String context = sessionService.buildHistoryContext(sessionId, mode, userMessage);
        String fullMessage;
        if (context != null) {
            fullMessage = context + "\n\n---\n\n用户最新消息:\n" + userMessage;
            log.info("[编排] 注入了历史上下文（含语义检索），总消息长度={}", fullMessage.length());
        } else {
            fullMessage = userMessage;
            log.info("[编排] 无历史上下文，仅当前消息");
        }

        log.info("[编排] 用户: {} (session={})", userMessage, sessionId);

        String reply = client.prompt()
                .system(SYSTEM_PROMPT)
                .user(fullMessage)
                .call()
                .content();

        log.info("[编排] 回复长度: {}", reply != null ? reply.length() : 0);
        return reply != null ? reply : "（AI 未返回回复）";
    }

    /**
     * 兼容旧签名，不注入历史上下文（仅用于内部工具调用场景）。
     */
    public String chat(String sessionId, String userMessage) throws Exception {
        return chat(sessionId, userMessage, "knowledge");
    }

    // ========== 内部方法（ChatClient 懒初始化 + 自动重建） ==========

    private ChatClient getOrCreateClient() {
        if (!isAvailable()) return null;

        String currentKey = buildConfigKey();
        if (chatClient == null || !currentKey.equals(cachedConfigKey)) {
            synchronized (configLock) {
                String keyAfterLock = buildConfigKey();
                if (chatClient == null || !keyAfterLock.equals(cachedConfigKey)) {
                    chatClient = createChatClient();
                    cachedConfigKey = keyAfterLock;
                    log.info("NoteAssistant ChatClient 已重建 (model={})", configResolver.resolveModel());
                }
            }
        }
        return chatClient;
    }

    private String buildConfigKey() {
        return configResolver.resolveBaseUrl() + "|"
                + configResolver.resolveModel() + "|"
                + configResolver.resolveApiKey().hashCode();
    }

    private ChatClient createChatClient() {
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
