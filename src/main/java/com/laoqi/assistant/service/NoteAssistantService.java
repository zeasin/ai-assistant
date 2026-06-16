package com.laoqi.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 笔记库 AI 编排服务。
 * 直接调用 OpenAI 兼容 API（DeepSeek/商汤）的 tool_calls 功能，实现 ReAct 循环。
 * AI 通过 listDir / readFile / writeFile 三个工具自主探索并写入笔记库。
 */
@Service
public class NoteAssistantService {

    private static final Logger log = LoggerFactory.getLogger(NoteAssistantService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConfigService configService;
    private final NoteTools noteTools;
    private final HttpClient httpClient;

    // 每个会话的消息历史
    private final Map<String, List<Map<String, Object>>> sessionHistories = new ConcurrentHashMap<>();

    // 工具定义（OpenAI tools 格式 JSON）
    private static final String TOOLS_JSON = """
        [{
          "type": "function",
          "function": {
            "name": "listDir",
            "description": "列出笔记库指定目录下的所有文件和子目录",
            "parameters": {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "目录路径，相对于笔记库根目录" }
              },
              "required": ["path"]
            }
          }
        },{
          "type": "function",
          "function": {
            "name": "readFile",
            "description": "读取笔记库中指定文件的内容",
            "parameters": {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "文件路径，相对于笔记库根目录" }
              },
              "required": ["path"]
            }
          }
        },{
          "type": "function",
          "function": {
            "name": "writeFile",
            "description": "将数据写入笔记库指定文件。如果文件已存在，需要先读取原有内容，合并后再写入",
            "parameters": {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "文件路径，相对于笔记库根目录" },
                "content": { "type": "string", "description": "要写入的完整文件内容" }
              },
              "required": ["path", "content"]
            }
          }
        },{
          "type": "function",
          "function": {
            "name": "searchFiles",
            "description": "在笔记库中搜索文件名包含指定关键词的文件和目录（仅搜索文件名，不搜索文件内容）",
            "parameters": {
              "type": "object",
              "properties": {
                "keyword": { "type": "string", "description": "搜索关键词，如 BUG、客户、日报" }
              },
              "required": ["keyword"]
            }
          }
        }]
        """;

    private static final int MAX_TOOL_ROUNDS = 25;

    public NoteAssistantService(ConfigService configService, NoteTools noteTools) {
        this.configService = configService;
        this.noteTools = noteTools;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ========== 配置读取 ==========

    private String getApiKey() {
        var cfg = configService.load();
        String key = cfg.getLlmApiKey();
        if (key != null && !key.isEmpty()) return key;
        key = System.getenv("LLM_API_KEY");
        if (key != null && !key.isEmpty()) return key;
        key = System.getenv("DEEPSEEK_API_KEY");
        return key;
    }

    private String getBaseUrl() {
        var cfg = configService.load();
        String url = cfg.getLlmBaseUrl();
        if (url != null && !url.isEmpty()) return url;
        url = System.getenv("LLM_BASE_URL");
        if (url != null && !url.isEmpty()) return url;
        return "https://api.deepseek.com";
    }

    private String getModel() {
        var cfg = configService.load();
        String m = cfg.getLlmModel();
        if (m != null && !m.isEmpty()) return m;
        return "deepseek-chat";
    }

    private int getTimeout() {
        var cfg = configService.load();
        return cfg.getLlmTimeout() > 0 ? cfg.getLlmTimeout() : 60;
    }

    public boolean isAvailable() {
        String key = getApiKey();
        return key != null && !key.isEmpty();
    }

    // ========== 公开 API ==========

    /**
     * 判断是否需要工具编排
     */
    public boolean needsOrchestration(String userMessage) {
        if (userMessage == null) return false;
        String msg = userMessage.toLowerCase();
        return msg.contains("记录") || msg.contains("保存") || msg.contains("写入")
                || msg.contains("记一条") || msg.contains("添加") || msg.contains("新增")
                || msg.contains("创建") || msg.contains("写一条");
    }

    /**
     * 执行 AI 编排（ReAct 循环）
     */
    public String chat(String sessionId, String userMessage) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        String apiKey = getApiKey();
        String baseUrl = getBaseUrl();
        String model = getModel();
        int timeout = getTimeout();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = mapper.readValue(TOOLS_JSON, List.class);

        // 获取或创建会话历史
        List<Map<String, Object>> messages = sessionHistories.computeIfAbsent(sessionId, k -> {
            List<Map<String, Object>> history = new ArrayList<>();
            history.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            return history;
        });

        // 添加用户消息
        messages.add(Map.of("role", "user", "content", userMessage));

        // ReAct 循环
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Map<String, Object> response = callLlm(apiKey, baseUrl, model, timeout, messages, tools);

            @SuppressWarnings("unchecked")
            Map<String, Object> choice = ((List<Map<String, Object>>) response.get("choices")).get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMessage = (Map<String, Object>) choice.get("message");

            String content = (String) responseMessage.get("content");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) responseMessage.get("tool_calls");

            if (toolCalls != null && !toolCalls.isEmpty()) {
                messages.add(responseMessage);

                for (Map<String, Object> toolCall : toolCalls) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> func = (Map<String, Object>) toolCall.get("function");
                    String toolName = (String) func.get("name");
                    String toolArgs = (String) func.get("arguments");
                    String toolCallId = (String) toolCall.get("id");

                    log.info("[编排] 第{}轮 执行工具: {}({})", round + 1, toolName, toolArgs);
                    String result = executeTool(toolName, toolArgs);
                    log.info("[编排] 结果: {}...", result.substring(0, Math.min(100, result.length())));

                    Map<String, Object> toolResult = new LinkedHashMap<>();
                    toolResult.put("role", "tool");
                    toolResult.put("tool_call_id", toolCallId);
                    toolResult.put("content", result);
                    messages.add(toolResult);
                }
            } else {
                String reply = content != null ? content : "（AI 未返回文本）";
                messages.add(responseMessage);
                return reply;
            }
        }

        return "❌ 处理超时，工具调用次数过多，请简化问题";
    }

    // ========== 内部方法 ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> callLlm(String apiKey, String baseUrl, String model, int timeout,
                                         List<Map<String, Object>> messages,
                                         List<Map<String, Object>> tools) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("temperature", 0.3);
        body.put("stream", false);

        String jsonBody = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(timeout))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("LLM API 错误: status={} body={}", response.statusCode(), response.body());
            throw new RuntimeException("LLM API 返回错误 " + response.statusCode() + ": " + response.body());
        }

        return mapper.readValue(response.body(), Map.class);
    }

    private String executeTool(String toolName, String toolArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = mapper.readValue(toolArgs, Map.class);

            return switch (toolName) {
                case "listDir" -> noteTools.listDir((String) args.getOrDefault("path", ""));
                case "readFile" -> noteTools.readFile((String) args.get("path"));
                case "writeFile" -> noteTools.writeFile((String) args.get("path"), (String) args.get("content"));
                case "searchFiles" -> noteTools.searchFiles((String) args.get("keyword"));
                default -> "未知工具: " + toolName;
            };
        } catch (Exception e) {
            log.error("[编排] 工具执行失败: {} args={}", e.getMessage(), toolArgs);
            return "执行失败: " + e.getMessage();
        }
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
