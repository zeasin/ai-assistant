---
tags:
  - AI助理
  - 解决方案
  - 工作量评估
created: 2026-05-30
modified: 2026-05-30
---

# AI助理 2.0 解决方案（最终版）

> Java + opencode server 混合AI架构

---

## 一、项目概述

### 1.1 背景

AI助理 1.0（Python）存在以下问题：

| 问题 | 说明 |
|------|------|
| **CLI模式无上下文** | opencode/claudecode 以 CLI 启动，每次对话独立，无法保持会话状态 |
| **Python技术栈** | 与用户 Java 技术栈不匹配，维护成本高 |
| **功能分散** | AI能力、工具调用、会话管理分散在不同模块 |

### 1.2 2.0升级目标

| 目标 | 说明 |
|------|------|
| **统一AI服务层** | 所有AI交互通过 opencode server，支持多模型切换 |
| **Java技术栈** | 使用 Spring Boot，与用户技术栈匹配 |
| **会话持久化** | 支持多轮对话，上下文保持 |
| **常驻服务** | opencode server 作为后台服务运行 |

### 1.3 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| **开发语言** | Java | 用户主栈，扩展性强 |
| **AI服务** | opencode server | 架构简单，HTTP调用 |
| **日常模型** | opencode zen | 免费 |
| **编码模型** | DeepSeek | 按量付费，性价比高 |
| **不集成Claude Code** | 是 | Java对接CLI复杂，不必要 |

---

## 二、架构设计

### 2.1 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                  Java AI Assistant 2.0                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    │
│  │  日常任务    │    │  编码任务    │    │  飞书推送    │    │
│  │ (日报/笔记)  │    │ (Java开发)   │    │  (Webhook)   │    │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘    │
│         │                  │                  │            │
│         └──────────────────┼──────────────────┘            │
│                            │                               │
│                     ┌──────▼──────┐                        │
│                     │  Task Router │                        │
│                     │  (模型选择)   │                        │
│                     └──────┬──────┘                        │
│                            │                               │
│                     ┌──────▼──────┐                        │
│                     │ OpenCode    │                        │
│                     │ Client      │                        │
│                     │ (HTTP API)  │                        │
│                     └──────┬──────┘                        │
│                            │                               │
└────────────────────────────┼───────────────────────────────┘
                             │
                             │ HTTP
                             │
                    ┌────────▼────────┐
                    │  opencode       │
                    │  server         │
                    │  (localhost:4096)│
                    └────────┬────────┘
                             │
                ┌────────────┼────────────┐
                ▼            ▼            ▼
         ┌──────────┐ ┌──────────┐ ┌──────────┐
         │ opencode │ │ DeepSeek │ │  其他    │
         │ zen(免费)│ │ (付费)   │ │  模型    │
         └──────────┘ └──────────┘ └──────────┘
```

### 2.2 模块职责

| 模块 | 职责 | 技术 |
|------|------|------|
| **OpenCode Client** | 封装opencode server调用 | OkHttp + SSE |
| **Task Router** | 任务路由、模型选择 | Java Switch |
| **Session Manager** | 管理会话生命周期 | 内存缓存 |
| **Dashboard Service** | 看板服务（JSON/MD读取） | Jackson + Files |
| **定时任务** | 日报自动生成 | Spring Scheduler |
| **飞书推送** | 消息推送到飞书 | Webhook |
| **Web UI** | 对话/看板界面 | Thymeleaf |

---

## 三、技术选型

### 3.1 后端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17+ | 核心语言 |
| Spring Boot | 3.2+ | Web框架 |
| OkHttp | 4.x | HTTP客户端 |
| Jackson | 2.x | JSON处理 |
| Lombok | - | 简化代码 |

### 3.2 前端技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Thymeleaf | 3.x | 模板引擎 |
| Bootstrap | 5.x | UI框架 |
| jQuery | 3.x | AJAX交互 |

### 3.3 外部依赖

| 依赖 | 说明 | 费用 |
|------|------|------|
| opencode server | AI服务层，需预先安装并运行 | 免费 |
| opencode zen | 日常任务模型 | 免费 |
| DeepSeek API | 编码任务模型 | 按量付费 |
| 飞书Webhook | 消息推送 | 免费 |

---

## 四、核心模块设计

### 4.1 OpenCode Client

#### 4.1.1 接口定义

```java
/**
 * OpenCode Server 客户端接口
 */
public interface OpenCodeClient {

    /**
     * 检查server健康状态
     */
    HealthResponse health();

    /**
     * 创建新会话
     */
    Session createSession(String title);

    /**
     * 获取会话详情
     */
    Session getSession(String sessionId);

    /**
     * 删除会话
     */
    void deleteSession(String sessionId);

    /**
     * 发送消息（指定模型）
     */
    Message sendMessage(String sessionId, String content, String model);

    /**
     * 获取会话消息列表
     */
    List<Message> getMessages(String sessionId, int limit);
}
```

#### 4.1.2 实现类

```java
@Component
@Slf4j
public class OpenCodeClientImpl implements OpenCodeClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public OpenCodeClientImpl(
            @Value("${opencode.server.url:http://localhost:4096}") String baseUrl,
            @Value("${opencode.server.username:opencode}") String username,
            @Value("${opencode.server.password:}") String password) {
        this.baseUrl = baseUrl;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .authInterceptor(new BasicAuthInterceptor(username, password))
                .build();
    }

    @Override
    public HealthResponse health() {
        Request request = new Request.Builder()
                .url(baseUrl + "/global/health")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return objectMapper.readValue(response.body().string(), HealthResponse.class);
        } catch (IOException e) {
            throw new OpenCodeException("健康检查失败", e);
        }
    }

    @Override
    public Session createSession(String title) {
        Map<String, String> body = Map.of("title", title);
        Request request = new Request.Builder()
                .url(baseUrl + "/session")
                .post(RequestBody.create(
                        objectMapper.writeValueAsString(body),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return objectMapper.readValue(response.body().string(), Session.class);
        } catch (IOException e) {
            throw new OpenCodeException("创建会话失败", e);
        }
    }

    @Override
    public Message sendMessage(String sessionId, String content, String model) {
        Map<String, Object> body = new HashMap<>();
        body.put("parts", List.of(Map.of("type", "text", "text", content)));
        if (model != null) {
            body.put("model", model);
        }

        Request request = new Request.Builder()
                .url(baseUrl + "/session/" + sessionId + "/message")
                .post(RequestBody.create(
                        objectMapper.writeValueAsString(body),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return objectMapper.readValue(response.body().string(), Message.class);
        } catch (IOException e) {
            throw new OpenCodeException("发送消息失败", e);
        }
    }

    @Override
    public List<Message> getMessages(String sessionId, int limit) {
        Request request = new Request.Builder()
                .url(baseUrl + "/session/" + sessionId + "/message?limit=" + limit)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return objectMapper.readValue(
                response.body().string(),
                new TypeReference<List<Message>>() {}
            );
        } catch (IOException e) {
            throw new OpenCodeException("获取消息失败", e);
        }
    }
}
```

### 4.2 Task Router

#### 4.2.1 任务类型定义

```java
/**
 * 任务类型枚举
 */
public enum TaskType {
    // 日常任务（opencode zen免费模型）
    DAILY_REPORT("daily_report", "日报生成", "opencode/zen"),
    NOTE_ANALYSIS("note_analysis", "笔记分析", "opencode/zen"),
    FEISHU_PUSH("feishu_push", "飞书推送", "opencode/zen"),
    SUMMARY("summary", "内容摘要", "opencode/zen"),

    // 编码任务（DeepSeek付费模型）
    JAVA_CODING("java_coding", "Java编码", "deepseek/deepseek-chat"),
    BUG_FIX("bug_fix", "BUG修复", "deepseek/deepseek-chat"),
    NEW_FEATURE("new_feature", "新功能开发", "deepseek/deepseek-chat"),
    CODE_REVIEW("code_review", "代码审查", "deepseek/deepseek-chat"),
    REFACTOR("refactor", "代码重构", "deepseek/deepseek-chat");

    private final String code;
    private final String description;
    private final String defaultModel;

    TaskType(String code, String description, String defaultModel) {
        this.code = code;
        this.description = description;
        this.defaultModel = defaultModel;
    }

    /**
     * 根据code获取枚举
     */
    public static TaskType fromCode(String code) {
        for (TaskType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知任务类型: " + code);
    }
}
```

#### 4.2.2 任务路由器

```java
@Component
@Slf4j
public class TaskRouter {

    private final OpenCodeClient openCodeClient;
    private final SessionManager sessionManager;

    /**
     * 执行任务
     */
    public TaskResult execute(String taskTypeCode, String prompt, Map<String, Object> params) {
        TaskType taskType = TaskType.fromCode(taskTypeCode);

        log.info("执行任务: type={}, model={}", taskType.getCode(), taskType.getDefaultModel());

        // 1. 创建会话
        ChatSession session = sessionManager.createSession(
            params.getOrDefault("userId", "system").toString(),
            taskType.getDescription()
        );

        // 2. 发送消息（使用对应模型）
        Message message = openCodeClient.sendMessage(
            session.getId(),
            prompt,
            taskType.getDefaultModel()
        );

        // 3. 提取结果
        String content = extractContent(message);

        return TaskResult.builder()
            .success(true)
            .sessionId(session.getId())
            .content(content)
            .model(taskType.getDefaultModel())
            .build();
    }

    private String extractContent(Message message) {
        if (message.getParts() == null) {
            return "";
        }
        return message.getParts().stream()
            .filter(p -> "text".equals(p.getType()))
            .map(Part::getText)
            .findFirst()
            .orElse("");
    }
}
```

### 4.3 Session Manager

```java
@Component
@Slf4j
public class SessionManager {

    private final OpenCodeClient openCodeClient;
    private final Map<String, ChatSession> sessionCache = new ConcurrentHashMap<>();

    /**
     * 创建新会话
     */
    public ChatSession createSession(String userId, String title) {
        Session session = openCodeClient.createSession(title);

        ChatSession chatSession = ChatSession.builder()
                .id(session.getId())
                .title(title)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();

        sessionCache.put(session.getId(), chatSession);
        log.info("创建会话: userId={}, sessionId={}", userId, session.getId());

        return chatSession;
    }

    /**
     * 获取会话
     */
    public ChatSession getSession(String sessionId) {
        return sessionCache.get(sessionId);
    }

    /**
     * 发送消息
     */
    public ChatMessage sendMessage(String sessionId, String content, String model) {
        ChatSession session = sessionCache.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException(sessionId);
        }

        Message message = openCodeClient.sendMessage(sessionId, content, model);
        session.setLastActiveAt(LocalDateTime.now());

        return ChatMessage.builder()
                .id(message.getId())
                .sessionId(sessionId)
                .role("assistant")
                .content(extractTextContent(message))
                .timestamp(LocalDateTime.now())
                .build();
    }

    private String extractTextContent(Message message) {
        if (message.getParts() == null) {
            return "";
        }
        return message.getParts().stream()
                .filter(p -> "text".equals(p.getType()))
                .map(Part::getText)
                .findFirst()
                .orElse("");
    }
}
```

### 4.4 数据模型

```java
/**
 * 会话信息
 */
@Data
@Builder
public class ChatSession {
    private String id;
    private String title;
    private String userId;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
}

/**
 * 消息信息
 */
@Data
@Builder
public class ChatMessage {
    private String id;
    private String sessionId;
    private String role;
    private String content;
    private LocalDateTime timestamp;
}

/**
 * 任务结果
 */
@Data
@Builder
public class TaskResult {
    private boolean success;
    private String sessionId;
    private String content;
    private String model;
    private String errorMessage;
}

/**
 * OpenCode响应模型
 */
@Data
public class Session {
    private String id;
    private String title;
}

@Data
public class Message {
    private String id;
    private List<Part> parts;
}

@Data
public class Part {
    private String type;
    private String text;
}

@Data
public class HealthResponse {
    private boolean healthy;
    private String version;
}
```

### 4.5 Dashboard Service（看板服务）

#### 4.5.1 业务场景

| 看板 | 数据源 | 更新频率 | 处理方式 |
|------|--------|----------|----------|
| 客户看板 | 客户数据.json | 实时 | Java直接读取 |
| 运营看板 | 自媒体数据.json + 自媒体文章.json + 自媒体账号.json | 每日 | Java直接读取 |
| 日报 | AI生成 | 每日 | opencode处理 |
| 笔记分析 | 笔记库 | 按需 | opencode处理 |

#### 4.5.2 看板数据模型

```java
/**
 * 客户看板数据
 */
@Data
public class CustomerDashboard {
    private List<Customer> customers;
    private CustomerStats stats;
}

@Data
public class Customer {
    private String code;        // 客户编码
    private String name;        // 客户名称
    private String stage;       // 阶段：已签约/进行中/已交付
    private BigDecimal amount;  // 合同金额
    private BigDecimal paid;    // 已付金额
    private String product;     // 产品
    private String lastContact; // 最后联系时间
}

@Data
public class CustomerStats {
    private int totalCustomers;      // 总客户数
    private int activeCustomers;     // 活跃客户数
    private BigDecimal totalAmount;  // 总合同金额
    private BigDecimal totalPaid;    // 总已付金额
}

/**
 * 运营看板数据
 */
@Data
public class OperationDashboard {
    private GiteeStats gitee;        // Gitee数据
    private GitHubStats github;      // GitHub数据
    private WechatStats wechat;      // 公众号数据
    private RevenueStats revenue;    // 营收数据
}

@Data
public class GiteeStats {
    private int totalStars;          // 总star数
    private List<ProjectStats> projects;
}

@Data
public class ProjectStats {
    private String name;
    private int stars;
    private int forks;
}
```

#### 4.5.3 看板服务实现

```java
@Service
@Slf4j
public class DashboardService {

    private final ObjectMapper mapper;
    private final String dataPath;

    public DashboardService(
            @Value("${data.path:./data}") String dataPath) {
        this.dataPath = dataPath;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * 读取客户看板数据
     */
    public CustomerDashboard getCustomerDashboard() {
        try {
            Path path = Path.of(dataPath, "客户管理/data/客户数据.json");
            return mapper.readValue(path.toFile(), CustomerDashboard.class);
        } catch (IOException e) {
            log.error("读取客户数据失败", e);
            return new CustomerDashboard();
        }
    }

    /**
     * 读取运营看板数据
     */
    public OperationDashboard getOperationDashboard() {
        try {
            Path path = Path.of(dataPath, "自媒体/data/自媒体数据.json");
            return mapper.readValue(path.toFile(), OperationDashboard.class);
        } catch (IOException e) {
            log.error("读取运营数据失败", e);
            return new OperationDashboard();
        }
    }

    /**
     * 读取Markdown看板
     */
    public String getMarkdownDashboard(String fileName) {
        try {
            Path path = Path.of(dataPath, fileName);
            return Files.readString(path);
        } catch (IOException e) {
            log.error("读取Markdown看板失败: {}", fileName, e);
            return "# 看板加载失败";
        }
    }

    /**
     * 生成客户看板HTML
     */
    public String renderCustomerDashboard() {
        CustomerDashboard data = getCustomerDashboard();

        StringBuilder html = new StringBuilder();
        html.append("<div class='dashboard'>");
        html.append("<h2>客户看板</h2>");

        // 统计信息
        if (data.getStats() != null) {
            html.append("<div class='stats'>");
            html.append("<span>总客户: ").append(data.getStats().getTotalCustomers()).append("</span>");
            html.append("<span>活跃客户: ").append(data.getStats().getActiveCustomers()).append("</span>");
            html.append("<span>总金额: ¥").append(data.getStats().getTotalAmount()).append("</span>");
            html.append("</div>");
        }

        // 客户列表
        if (data.getCustomers() != null && !data.getCustomers().isEmpty()) {
            html.append("<table>");
            html.append("<tr><th>编码</th><th>名称</th><th>阶段</th><th>金额</th><th>产品</th></tr>");
            for (Customer c : data.getCustomers()) {
                html.append("<tr>");
                html.append("<td>").append(c.getCode()).append("</td>");
                html.append("<td>").append(c.getName()).append("</td>");
                html.append("<td>").append(c.getStage()).append("</td>");
                html.append("<td>¥").append(c.getAmount()).append("</td>");
                html.append("<td>").append(c.getProduct()).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");
        }

        html.append("</div>");
        return html.toString();
    }
}
```

#### 4.5.4 看板Controller

```java
@RestController
@RequestMapping("/api/dashboard")
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取客户看板
     */
    @GetMapping("/customer")
    public ResponseEntity<CustomerDashboard> getCustomerDashboard() {
        return ResponseEntity.ok(dashboardService.getCustomerDashboard());
    }

    /**
     * 获取客户看板HTML
     */
    @GetMapping("/customer/html")
    public ResponseEntity<String> getCustomerDashboardHtml() {
        String html = dashboardService.renderCustomerDashboard();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /**
     * 获取运营看板
     */
    @GetMapping("/operation")
    public ResponseEntity<OperationDashboard> getOperationDashboard() {
        return ResponseEntity.ok(dashboardService.getOperationDashboard());
    }

    /**
     * 获取Markdown看板
     */
    @GetMapping("/markdown/{fileName}")
    public ResponseEntity<String> getMarkdownDashboard(@PathVariable String fileName) {
        String content = dashboardService.getMarkdownDashboard(fileName + ".md");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_MARKDOWN)
                .body(content);
    }
}
```

#### 4.5.5 任务处理分工

```java
@Component
public class TaskRouter {

    /**
     * 根据任务类型选择处理方式
     */
    public Object execute(String taskType, Map<String, Object> params) {
        return switch (taskType) {
            // 固定格式看板 → Java直接读取
            case "customer_dashboard" -> dashboardService.getCustomerDashboard();
            case "operation_dashboard" -> dashboardService.getOperationDashboard();
            case "customer_dashboard_html" -> dashboardService.renderCustomerDashboard();

            // AI任务 → opencode处理
            case "daily_report" -> openCodeTaskService.generateDailyReport(params);
            case "note_analysis" -> openCodeTaskService.analyzeNotes(params);

            default -> throw new IllegalArgumentException("未知任务类型: " + taskType);
        };
    }
}
```

---

## 五、页面与API设计

### 5.1 页面路由（Thymeleaf）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 首页（对话界面） |
| GET | `/chat` | AI对话页面 |
| GET | `/chat/{sessionId}` | 指定会话页面 |
| GET | `/dashboard/customer` | 客户看板页面 |
| GET | `/dashboard/operation` | 运营看板页面 |
| GET | `/dashboard/markdown/{name}` | Markdown看板页面 |
| GET | `/task` | 任务中心页面 |

### 5.2 REST API（AJAX调用）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/session` | 创建会话 |
| GET | `/api/chat/session/list` | 获取会话列表 |
| DELETE | `/api/chat/session/{id}` | 删除会话 |
| POST | `/api/chat/message` | 发送消息（返回JSON） |
| GET | `/api/chat/message/{sessionId}` | 获取消息列表 |
| POST | `/api/task/execute` | 执行任务 |
| GET | `/api/dashboard/customer` | 客户看板（JSON） |
| GET | `/api/dashboard/operation` | 运营看板（JSON） |
| GET | `/api/health` | 健康检查 |

### 5.2 请求/响应示例

#### 发送消息

```json
// POST /api/chat/message
{
    "sessionId": "sess_abc123",
    "content": "帮我分析一下今天的笔记",
    "model": "opencode/zen"
}

// Response
{
    "code": 0,
    "data": {
        "id": "msg_xyz789",
        "sessionId": "sess_abc123",
        "role": "assistant",
        "content": "根据分析，您今天主要关注了...",
        "timestamp": "2026-05-30T10:01:30"
    }
}
```

#### 执行任务

```json
// POST /api/task/execute
{
    "taskType": "daily_report",
    "prompt": "生成今日工作日报",
    "params": {
        "userId": "laoqi",
        "date": "2026-05-30"
    }
}

// Response
{
    "code": 0,
    "data": {
        "success": true,
        "sessionId": "sess_abc123",
        "content": "# 2026-05-30 工作日报\n\n## 今日完成工作\n...",
        "model": "opencode/zen"
    }
}
```

---

## 六、配置管理

### 6.1 application.yml

```yaml
# application.yml
server:
  port: 8080

# Thymeleaf配置
spring:
  thymeleaf:
    cache: false
    prefix: classpath:/templates/
    suffix: .html
    encoding: UTF-8

# opencode配置
opencode:
  server:
    url: http://localhost:4096
    username: opencode
    password: ${OPENCODE_PASSWORD:}
    timeout: 300000

# 数据目录配置
data:
  path: ${DATA_PATH:./data}

# 任务配置
task:
  daily-report:
    enabled: true
    cron: "0 0 18 * * ?"
  note-analysis:
    enabled: true
    cron: "0 0 20 * * ?"

# 飞书配置
feishu:
  webhook:
    daily-report: ${FEISHU_WEBHOOK_DAILY:}
    alert: ${FEISHU_WEBHOOK_ALERT:}
```

### 6.2 opencode配置

```json
// ~/.config/opencode/opencode.json
{
    "$schema": "https://opencode.ai/config.json",
    "model": "opencode/zen",
    "provider": {
        "opencode": {
            "models": {
                "zen": {
                    "name": "OpenCode Zen (Free)"
                }
            }
        },
        "deepseek": {
            "npm": "@ai-sdk/openai-compatible",
            "options": {
                "baseURL": "https://api.deepseek.com/v1",
                "apiKey": "{env:DEEPSEEK_API_KEY}"
            },
            "models": {
                "deepseek-chat": {
                    "name": "DeepSeek Chat"
                }
            }
        }
    }
}
```

---

## 六、Thymeleaf页面模板

### 6.1 对话页面模板

```html
<!-- src/main/resources/templates/chat.html -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>AI助理 - 对话</title>
    <link href="https://cdn.bootcdn.net/ajax/libs/twitter-bootstrap/5.3.0/css/bootstrap.min.css" rel="stylesheet">
    <style>
        .chat-container { height: calc(100vh - 200px); overflow-y: auto; }
        .message { margin: 10px 0; padding: 10px; border-radius: 10px; }
        .message-user { background: #007bff; color: white; margin-left: 20%; }
        .message-ai { background: #f8f9fa; margin-right: 20%; }
    </style>
</head>
<body>
    <div class="container">
        <h2>AI助理对话</h2>
        
        <!-- 会话选择 -->
        <div class="mb-3">
            <select id="sessionSelect" class="form-select" onchange="loadSession(this.value)">
                <option value="">选择会话...</option>
                <option th:each="session : ${sessions}" th:value="${session.id}" th:text="${session.title}"></option>
            </select>
        </div>
        
        <!-- 消息列表 -->
        <div id="chatContainer" class="chat-container border p-3 mb-3">
            <div th:each="message : ${messages}" 
                 th:classappend="${message.role == 'user'} ? 'message message-user' : 'message message-ai'">
                <span th:text="${message.content}"></span>
            </div>
        </div>
        
        <!-- 输入框 -->
        <div class="input-group">
            <input type="text" id="messageInput" class="form-control" placeholder="输入消息...">
            <select id="modelSelect" class="form-select" style="max-width: 200px;">
                <option value="opencode/zen">OpenCode Zen (免费)</option>
                <option value="deepseek/deepseek-chat">DeepSeek (付费)</option>
            </select>
            <button class="btn btn-primary" onclick="sendMessage()">发送</button>
        </div>
    </div>

    <script src="https://cdn.bootcdn.net/ajax/libs/jquery/3.7.1/jquery.min.js"></script>
    <script>
        let currentSessionId = '${currentSessionId}';
        
        function sendMessage() {
            const content = $('#messageInput').val();
            const model = $('#modelSelect').val();
            
            if (!currentSessionId) {
                // 创建新会话
                $.post('/api/chat/session', { title: content.substring(0, 20) }, function(session) {
                    currentSessionId = session.id;
                    doSend(content, model);
                });
            } else {
                doSend(content, model);
            }
        }
        
        function doSend(content, model) {
            $.ajax({
                url: '/api/chat/message',
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    sessionId: currentSessionId,
                    content: content,
                    model: model
                }),
                success: function(response) {
                    // 添加消息到页面
                    appendMessage('user', content);
                    appendMessage('assistant', response.data.content);
                    $('#messageInput').val('');
                }
            });
        }
        
        function appendMessage(role, content) {
            const cssClass = role === 'user' ? 'message message-user' : 'message message-ai';
            const html = '<div class="' + cssClass + '">' + content + '</div>';
            $('#chatContainer').append(html);
        }
        
        function loadSession(sessionId) {
            if (sessionId) {
                window.location.href = '/chat/' + sessionId;
            }
        }
    </script>
</body>
</html>
```

### 6.2 客户看板模板

```html
<!-- src/main/resources/templates/dashboard/customer.html -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>客户看板</title>
    <link href="https://cdn.bootcdn.net/ajax/libs/twitter-bootstrap/5.3.0/css/bootstrap.min.css" rel="stylesheet">
</head>
<body>
    <div class="container">
        <h2>客户看板</h2>
        
        <!-- 统计信息 -->
        <div class="row mb-4">
            <div class="col-md-3">
                <div class="card">
                    <div class="card-body text-center">
                        <h5 th:text="${stats.totalCustomers}">0</h5>
                        <p>总客户数</p>
                    </div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="card">
                    <div class="card-body text-center">
                        <h5 th:text="${stats.activeCustomers}">0</h5>
                        <p>活跃客户</p>
                    </div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="card">
                    <div class="card-body text-center">
                        <h5>¥<span th:text="${stats.totalAmount}">0</span></h5>
                        <p>总合同金额</p>
                    </div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="card">
                    <div class="card-body text-center">
                        <h5>¥<span th:text="${stats.totalPaid}">0</span></h5>
                        <p>已收金额</p>
                    </div>
                </div>
            </div>
        </div>
        
        <!-- 客户列表 -->
        <table class="table table-striped">
            <thead>
                <tr>
                    <th>编码</th>
                    <th>名称</th>
                    <th>阶段</th>
                    <th>合同金额</th>
                    <th>已付金额</th>
                    <th>产品</th>
                    <th>最后联系</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="customer : ${customers}">
                    <td th:text="${customer.code}"></td>
                    <td th:text="${customer.name}"></td>
                    <td>
                        <span class="badge" 
                              th:classappend="${customer.stage == '已签约'} ? 'bg-success' : 
                                             (${customer.stage == '进行中'} ? 'bg-warning' : 'bg-secondary')"
                              th:text="${customer.stage}"></span>
                    </td>
                    <td>¥<span th:text="${customer.amount}"></span></td>
                    <td>¥<span th:text="${customer.paid}"></span></td>
                    <td th:text="${customer.product}"></td>
                    <td th:text="${customer.lastContact}"></td>
                </tr>
            </tbody>
        </table>
    </div>
</body>
</html>
```

### 6.3 页面Controller

```java
@Controller
@Slf4j
public class PageController {

    private final DashboardService dashboardService;
    private final SessionManager sessionManager;

    /**
     * 首页
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("sessions", sessionManager.listSessions());
        return "index";
    }

    /**
     * 对话页面
     */
    @GetMapping("/chat")
    public String chat(Model model) {
        model.addAttribute("sessions", sessionManager.listSessions());
        model.addAttribute("messages", Collections.emptyList());
        return "chat";
    }

    /**
     * 指定会话页面
     */
    @GetMapping("/chat/{sessionId}")
    public String chatSession(@PathVariable String sessionId, Model model) {
        model.addAttribute("sessions", sessionManager.listSessions());
        model.addAttribute("currentSessionId", sessionId);
        model.addAttribute("messages", sessionManager.getMessages(sessionId));
        return "chat";
    }

    /**
     * 客户看板页面
     */
    @GetMapping("/dashboard/customer")
    public String customerDashboard(Model model) {
        CustomerDashboard data = dashboardService.getCustomerDashboard();
        model.addAttribute("customers", data.getCustomers());
        model.addAttribute("stats", data.getStats());
        return "dashboard/customer";
    }

    /**
     * 运营看板页面
     */
    @GetMapping("/dashboard/operation")
    public String operationDashboard(Model model) {
        OperationDashboard data = dashboardService.getOperationDashboard();
        model.addAttribute("gitee", data.getGitee());
        model.addAttribute("github", data.getGithub());
        model.addAttribute("wechat", data.getWechat());
        return "dashboard/operation";
    }

    /**
     * Markdown看板页面
     */
    @GetMapping("/dashboard/markdown/{name}")
    public String markdownDashboard(@PathVariable String name, Model model) {
        String content = dashboardService.getMarkdownDashboard(name + ".md");
        model.addAttribute("content", content);
        return "dashboard/markdown";
    }
}
```

---

## 七、工作量评估

### 7.1 模块清单

| 模块 | 工作内容 | 工时 | 优先级 |
|------|----------|------|:------:|
| **1. 项目搭建** | Spring Boot基础结构、依赖配置 | 0.5天 | P0 |
| **2. OpenCode Client** | HTTP客户端封装 | 2天 | P0 |
| **3. Session Manager** | 会话创建、查询、删除 | 1天 | P0 |
| **4. Task Router** | 任务路由、模型切换 | 1天 | P0 |
| **5. Dashboard Service** | 看板服务（JSON/MD读取） | 1天 | P0 |
| **6. Page Controller** | 页面路由 + Thymeleaf模板 | 1天 | P0 |
| **7. Chat Page** | 对话页面（Thymeleaf + AJAX） | 1天 | P0 |
| **8. Dashboard Pages** | 看板页面（客户/运营/MD） | 1天 | P0 |
| **9. 定时任务** | 日报自动生成 | 1天 | P1 |
| **10. 飞书推送** | Webhook推送 | 0.5天 | P1 |
| **11. 测试** | 单元测试、集成测试 | 1天 | P1 |
| | | | |
| **合计** | | **12天** | |

### 7.2 里程碑

```
Day 1: 项目搭建
    └── 产出：可运行的空Spring Boot项目

Day 2-3: OpenCode Client
    └── 产出：能调通opencode server

Day 4: Session Manager
    └── 产出：会话管理功能

Day 5: Task Router + Dashboard Service
    └── 产出：任务路由 + 看板数据服务

Day 6: Page Controller + Thymeleaf配置
    └── 产出：页面路由框架

Day 7: Chat Page
    └── 产出：对话页面（Thymeleaf + AJAX）

Day 8-9: Dashboard Pages
    └── 产出：客户看板、运营看板页面

Day 10: 定时任务 + 飞书
    └── 产出：自动化功能

Day 11-12: 测试优化
    └── 产出：可上线版本
```

### 7.3 文件清单

```
ai-assistant-2.0/
├── src/main/java/com/qihangerp/ai/
│   ├── AiAssistantApplication.java
│   ├── config/
│   │   └── AppConfig.java
│   ├── client/
│   │   └── OpenCodeClient.java
│   ├── service/
│   │   ├── SessionManager.java
│   │   ├── TaskRouter.java
│   │   └── DashboardService.java
│   ├── controller/
│   │   ├── PageController.java        # 页面路由
│   │   ├── ChatController.java        # AJAX接口
│   │   └── DashboardController.java   # 看板API
│   ├── scheduler/
│   │   └── DailyReportScheduler.java
├── src/main/resources/
│   ├── templates/
│   │   ├── index.html
│   │   ├── chat.html
│   │   └── dashboard/
│   │       ├── customer.html
│   │       ├── operation.html
│   │       └── markdown.html
│   ├── static/
│   │   ├── css/
│   │   └── js/
│   └── application.yml
│   └── model/
│       ├── ChatSession.java
│       ├── ChatMessage.java
│       ├── TaskResult.java
│       ├── Session.java
│       ├── Message.java
│       └── Part.java
├── src/main/resources/
│   └── application.yml
├── src/test/java/
│   └── ... (测试类)
└── pom.xml
```

---

## 八、部署方案

### 8.1 环境要求

| 组件 | 要求 |
|------|------|
| JDK | 17+ |
| Maven | 3.8+ |
| opencode | 已安装并配置 |
| 网络 | 能访问DeepSeek API |

### 8.2 部署步骤

```bash
# 1. 启动opencode server
opencode serve --port 4096 --hostname 127.0.0.1

# 2. 配置环境变量
export DEEPSEEK_API_KEY=your-api-key
export OPENCODE_PASSWORD=your-password

# 3. 构建项目
mvn clean package -DskipTests

# 4. 启动Java应用
java -jar target/ai-assistant-2.0.jar

# 5. 访问应用
open http://localhost:8080
```

---

## 九、费用估算

| 项目 | 费用 | 说明 |
|------|------|------|
| opencode server | 免费 | 开源工具 |
| opencode zen | 免费 | 日常任务模型 |
| DeepSeek API | ≈¥10-30/月 | 编码任务模型 |
| 飞书Webhook | 免费 | 消息推送 |
| **合计** | **≈¥10-30/月** | |

---

## 十、与1.0对比

| 维度 | 1.0 (Python) | 2.0 (Java) |
|------|-------------|------------|
| **技术栈** | Python + Flask | Java + Spring Boot |
| **AI服务** | CLI调用 | Server API |
| **会话管理** | 无状态 | 多轮对话 |
| **模型支持** | 固定 | 可切换 |
| **维护成本** | 高 | 低 |
| **扩展性** | 一般 | 强 |
| **费用** | - | ≈¥10-30/月 |

---

## 十一、后续规划

| 阶段 | 功能 | 说明 |
|------|------|------|
| **2.1** | 多用户支持 | 权限管理、用户隔离 |
| **2.2** | 任务编排 | 可视化任务流程 |
| **2.3** | 知识库集成 | RAG检索增强 |
| **3.0** | 商业化 | 作为启航ERP增值模块 |

---

> 文档版本：v2.0（最终版）
> 创建日期：2026-05-30
> 作者：老齐
