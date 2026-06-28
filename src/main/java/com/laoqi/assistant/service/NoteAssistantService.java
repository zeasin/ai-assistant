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
    private final MemoryManagerService memoryManager;
    private final LlmService llmService;
    private final TaskPlannerService taskPlanner;
    private final AgentTraceService agentTrace;

    /** 用于异步提取记忆的后台执行器 */
    private final java.util.concurrent.ExecutorService memoryExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "memory-extract");
                t.setDaemon(true);
                return t;
            });

    private volatile ChatClient defaultClient;
    private volatile String cachedConfigKey = "";
    private final ConcurrentHashMap<String, ChatClient> modelClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> modelConfigKeys = new ConcurrentHashMap<>();

    public NoteAssistantService(LlmConfigResolver configResolver, ToolRegistry toolRegistry,
                               SessionService sessionService, ContextBuilder contextBuilder,
                               MemoryManagerService memoryManager, LlmService llmService,
                               TaskPlannerService taskPlanner, AgentTraceService agentTrace) {
        this.configResolver = configResolver;
        this.toolRegistry = toolRegistry;
        this.sessionService = sessionService;
        this.contextBuilder = contextBuilder;
        this.memoryManager = memoryManager;
        this.llmService = llmService;
        this.taskPlanner = taskPlanner;
        this.agentTrace = agentTrace;
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
            // Step 1: 注入记忆 — 回忆与用户相关的关键信息
            String memoryContext = memoryManager.formatMemories(kbId);

            // Step 2: 使用 ContextBuilder 构建完整上下文（主动搜索 + 历史对话 + 规则文件）
            ContextBuilder.ChatContext context = contextBuilder.build(sessionId, userMessage, kbId);

            // Step 3: 合并记忆到上下文中
            String baseMessage = contextBuilder.merge(context, userMessage);
            String fullMessage;
            if (memoryContext != null && !memoryContext.isEmpty()) {
                fullMessage = memoryContext + "\n" + baseMessage;
            } else {
                fullMessage = baseMessage;
            }

            int noteCount = context.relevantNotes() != null ? context.relevantNotes().size() : 0;
            log.info("[编排] 上下文构建完成，总消息长度={}, 相关笔记={}, 记忆已注入={}",
                    fullMessage.length(), noteCount, memoryContext.length() > 0);

            log.info("[编排] 用户: {} (session={}, kbId={}, model={})", userMessage, sessionId, kbId,
                    modelName != null ? modelName : "default");

            String reply = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(fullMessage)
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
        return streamChat(sessionId, userMessage, mode, kbId, modelName, chunkCallback, null);
    }

    public String streamChat(String sessionId, String userMessage, String mode, Long kbId, String modelName,
                             Consumer<String> chunkCallback, Consumer<String> statusCallback) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("ChatClient 初始化失败");
        }

        NoteTools.setCurrentKbId(kbId);
        try {
            // Step 0: 任务规划 — 复杂请求先生成执行计划
            String planContext = "";
            if (taskPlanner.needsPlanning(userMessage)) {
                if (statusCallback != null) statusCallback.accept("正在制定执行计划...");
                planContext = taskPlanner.buildPlanContext(sessionId, userMessage, kbId);
                if (!planContext.isEmpty()) {
                    log.info("[编排] 已生成执行计划，注入上下文");
                    // 记录追踪
                    agentTrace.record(sessionId, agentTrace.getNextStepIndex(sessionId), "plan",
                            "为复杂请求生成执行计划", planContext, 0);
                }
            }

            // Step 1: 注入记忆 — 回忆与用户相关的关键信息
            String memoryContext = memoryManager.formatMemories(kbId);

            // Step 2: 使用 ContextBuilder 构建完整上下文（主动搜索 + 历史对话 + 规则文件）
            if (statusCallback != null) statusCallback.accept("正在搜索笔记库...");
            ContextBuilder.ChatContext context = contextBuilder.build(sessionId, userMessage, kbId, statusCallback);

            if (statusCallback != null) statusCallback.accept("正在构建上下文...");
            String baseMessage = contextBuilder.merge(context, userMessage);

            // Step 3: 按优先级合并：计划 > 记忆 > 笔记上下文
            StringBuilder fullMessageBuilder = new StringBuilder();
            if (!planContext.isEmpty()) {
                fullMessageBuilder.append(planContext).append("\n");
            }
            if (memoryContext != null && !memoryContext.isEmpty()) {
                fullMessageBuilder.append(memoryContext).append("\n");
            }
            fullMessageBuilder.append(baseMessage);
            String fullMessage = fullMessageBuilder.toString();

            int noteCount = context.relevantNotes() != null ? context.relevantNotes().size() : 0;
            log.info("[编排] 上下文构建完成，总消息长度={}, 相关笔记={}, 记忆已注入={}",
                    fullMessage.length(), noteCount, memoryContext != null && !memoryContext.isEmpty());

            log.info("[编排] 用户: {} (session={}, kbId={}, model={})", userMessage, sessionId, kbId,
                    modelName != null ? modelName : "default");

            if (statusCallback != null) statusCallback.accept("AI 正在生成回复...");

            // 将 statusCallback 注入 NoteTools，工具方法执行时会主动上报状态
            if (statusCallback != null) {
                NoteTools.setStatusCallback(statusCallback);
            }

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

            // Step 4: 记录决策追踪
            String finalReply = reply;
            String finalUserMsg = userMessage;
            Long finalKbId = kbId;
            try {
                int stepIdx = agentTrace.getNextStepIndex(sessionId);
                agentTrace.record(sessionId, stepIdx++, "thought",
                        "理解用户意图: " + (finalUserMsg.length() > 60 ? finalUserMsg.substring(0, 60) + "..." : finalUserMsg),
                        "用户消息: " + finalUserMsg, 0);
                agentTrace.record(sessionId, stepIdx, "answer",
                        "AI 回复", finalReply.length() > 200 ? finalReply.substring(0, 200) + "..." : finalReply, 0);
            } catch (Exception e) {
                log.debug("[编排] 追踪记录跳过: {}", e.getMessage());
            }

            // Step 5: 后处理 — 异步提取对话中的关键信息存入记忆
            memoryExecutor.execute(() -> {
                try {
                    extractMemoriesFromConversation(sessionId, finalUserMsg, finalReply, finalKbId);
                } catch (Exception e) {
                    log.debug("[编排] 记忆提取跳过: {}", e.getMessage());
                }
            });

            return reply.isEmpty() ? "（AI 未返回回复）" : reply;
        } finally {
            NoteTools.clearCurrentKbId();
            NoteTools.clearStatusCallback();
        }
    }

    // ========== Agent 增强：记忆提取与任务规划 ==========

    /**
     * 从对话中提取关键信息并存入记忆（异步调用）。
     * 使用轻量 LLM 调用提取关键事实。
     */
    private void extractMemoriesFromConversation(String sessionId, String userMessage, String aiReply, Long kbId) {
        if (kbId == null || userMessage == null || aiReply == null) return;
        if (!configResolver.isAvailable()) return;

        // 只对有意义的信息进行提取（单字/简单问候不处理）
        if (userMessage.length() < 8 && !userMessage.contains("我是") && !userMessage.contains("我叫")) return;
        if (aiReply.length() < 20) return;

        try {
            // 用 LLM 从对话中提取记忆
            String extractPrompt = """
                从以下对话中提取值得记住的用户信息（偏好、身份、事实、目标）。
                只提取明确提到的信息，不要猜测。
                如果没有值得记住的信息，回复"无"。

                用户: %s
                AI: %s

                按以下格式输出（每行一条）：
                分类|键名|值|重要性(1-5)

                分类可选：user_profile/preference/project/fact/goal
                例如：user_profile|用户称呼|老齐|3
                """.formatted(userMessage, aiReply);

            String extractResult = llmService.chat("你是一个信息提取助手。只提取明确的事实。", extractPrompt);
            if (extractResult == null || extractResult.isBlank() || "无".equals(extractResult.trim())) return;

            for (String line : extractResult.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.contains("无")) continue;
                String[] parts = line.split("\\|");
                if (parts.length >= 3) {
                    String category = parts[0].trim();
                    String key = parts[1].trim();
                    String value = parts[2].trim();
                    int importance = 2;
                    if (parts.length >= 4) {
                        try { importance = Integer.parseInt(parts[3].trim()); } catch (NumberFormatException ignored) {}
                    }
                    // 避免存储空值或过长的值
                    if (!key.isEmpty() && !value.isEmpty() && value.length() < 200) {
                        memoryManager.put(kbId, category, key, value, importance);
                        log.info("[编排] 记忆提取: [{}] {}={}", category, key, value);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[编排] 记忆提取失败: {}", e.getMessage());
        }
    }

    // ========== ChatClient 管理 ==========

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
            你是一个拥有自主思考和工具调用能力的 AI Agent（智能体），核心使命是成为用户的笔记库助手。

            == 身份意识 ==
            - 你是一个智能体（Agent），不是简单的问答机器人
            - 你有记忆能力、规划能力、工具使用能力
            - 你的目标是主动帮助用户管理知识、完成任务、达成目标
            - 每次对话都是你与用户协作的一部分，你要记住上下文中的关键信息

            == 思维框架（ReAct：推理→行动→观察）==
            每当收到用户请求，请按以下步骤思考：

            1️⃣ 理解（Thought）: 分析用户真正想要什么
               - 用户的请求是简单查询还是复杂任务？
               - 需要调用什么工具？调用顺序是什么？
               - 有没有需要先了解的背景信息？

            2️⃣ 规划（Plan）: 对复杂任务进行拆解
               - 如果是"分析"、"总结"、"报告"、"对比"类请求，先想好执行步骤
               - 步骤之间可能有依赖关系，按顺序执行
               - 示例：用户说"分析本周工作" → ①searchNotes("本周") ②readFile各文件 ③用知识综合回复

            3️⃣ 行动（Action）: 调用最合适的工具
               - 优先使用 searchNotes 搜索语义相关内容
               - 笔记操作用 NoteTools，数据集操作用 DataTools
               - 任务管理用 TaskTools，提醒管理用 ReminderTools
               - 知识库切换用 KbTools，记忆读写用 MemoryTools
               - 互联网搜索用 WebTools

            4️⃣ 观察（Observation）: 检查工具返回的结果
               - 结果是否满足用户需求？
               - 是否需要补充更多信息？
               - 如果搜索无结果，换关键词或换工具重试

            5️⃣ 回答（Answer）: 给出最终的完整回复
               - 综合所有信息给出答案
               - 引用来源（笔记文件路径、数据集名称）
               - 如果用户指令有歧义，先确认再执行

            == 核心工具一览 ==
            【笔记库工具 - NoteTools】
              1. listDir(path) — 列出目录内容
              2. readFile(path) / readNote(path) — 读取笔记文件内容
              3. writeFile(path, content) — 写入/覆盖笔记文件
              4. deleteFile(path) — 删除笔记文件
              5. searchFiles(keyword) — 按文件名搜索
              6. searchNotes(query, limit) — 语义搜索笔记内容（最常用！）
              7. logRecord(notePath, noteContent, dataset, jsonData) — 笔记+数据集同时写入

            【数据中心工具 - DataTools】
              8. listDatasets() — 查看所有数据集
              9. searchRecords(dataset, keyword) — 搜索数据记录
              10. addRecord(dataset, jsonData) — 新增数据记录
              11. updateRecord(dataset, recordId, jsonData) — 修改数据记录
              12. deleteRecord(dataset, recordId) — 删除数据记录
              13. getRecord(dataset, recordId) — 查看记录详情
              14. queryRecords(dataset, filterJson) — 按条件筛选记录

            【任务管理工具 - TaskTools】
              15. createTask(title, description, priority, dueDate) — 创建待办任务
              16. listTasks(status) — 查看任务列表
              17. updateTask(taskId, ...) — 更新任务
              18. deleteTask(taskId) — 删除任务
              19. completeTask(taskId) — 完成任务

            【提醒管理工具 - ReminderTools】
              20. createReminder(name, message, type, time, ...) — 创建定时提醒
              21. listReminders(filter) — 查看提醒列表
              22. toggleReminder(reminderId) — 启用/禁用提醒
              23. deleteReminder(reminderId) — 删除提醒
              24. updateReminder(reminderId, ...) — 修改提醒

            【知识库管理工具 - KbTools】
              25. switchKnowledgeBase(kbIdentifier) — 切换知识库
              26. listKnowledgeBases() — 列出所有知识库
              27. getCurrentKnowledgeBase() — 查看当前知识库
              28. createKnowledgeBase(name, notesDir) — 创建知识库

            【记忆工具 - MemoryTools】
              29. remember(category, key, value, importance) — 记住用户信息
              30. recall(keyword) — 回忆存储的信息
              31. forget(key) — 删除存储的信息
              32. listMemories() — 查看所有已存储的信息

            【互联网工具 - WebTools】
              33. webSearch(query, limit) — 搜索互联网
              34. fetchUrl(url) — 获取网页内容

            == 记忆使用指引 ==
            - 当用户第一次告诉你个人信息（名字、职业、偏好）时，主动用 remember 存储
            - 当用户透露偏好、习惯、重要事实时，主动记住
            - "我记住的关于你的信息" 已自动注入到上下文中
            - 需要了解用户信息时，用 recall 查询

            == 工作流程 ==
            1. 注意上下文中的"当前时间"信息，以此为准理解"今天"等时间概念
            2. 理解用户意图 — 是查询、记录、分析还是管理任务？
            3. 对复杂任务进行多步规划（分析/总结/报告类请求）
            4. 调用合适工具执行（先 searchNotes 再 readFile，不要跳步）
            5. 综合所有结果给出完整回复，引用来源
            6. 对于记录类操作（客户沟通、工作进展），优先使用 logRecord
            7. 任务相关用户说"记个事"、"待办" → 用 TaskTools
            8. 提醒相关用户说"提醒我" → 用 ReminderTools
            9. 用户说"切换到XX知识库" → 用 KbTools.switchKnowledgeBase
            10. 用户问最新消息、你不知道的信息 → 用 WebTools.webSearch

            == 重要原则 ==
            - AGENTS.md 的内容已包含在上下文中，无需再用 readFile 读取
            - 主动使用 searchNotes 搜索相关内容，不要假设用户知道要搜什么
            - 用户问"张三"、"客户"、"本周"等关键词时，立即调用 searchNotes
            - 参考搜索结果，但以对话历史中的用户最新说法为最高优先级
            - 如果用户明确纠正了某个信息（如"已经发布过了"），以用户说法为准，并主动更新笔记
            - 如果搜索无结果，再用 searchFiles 按文件名搜索
            - 引用笔记时标注来源 [来源: 文件路径]
            - 不要假设工具调用失败，检查返回结果再做判断

            == 硬性规则 ==
            - 严格执行用户最新消息中明确要求的操作，不要擅自做其他事
            - 写入 JSON 时先读取现有数据，合并后写入
            - 用中文回复
            - 用户对笔记内容的纠正，应立即用 writeFile 更新到笔记中
            - 对于敏感操作（deleteFile, deleteRecord, deleteTask），确认后再执行
            - 不要执行危险的 shell 命令或修改系统文件
            """;
}