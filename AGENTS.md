# 老齐AI个人助理 V2

## Quick start

```powershell
# 1. 先启动两个 opencode serve 服务（必须）
opencode serve --port 14096  # AI聊天/笔记库服务
opencode serve --port 14099  # 代码分析服务

# 2. 编译项目
mvn package -q

# 3. 启动应用
java -jar target/assistant-v2-2.0.0.jar

# 访问地址
# - Web UI: http://localhost:6790
# - Health check: http://localhost:6790/health
```

No tests, no linter, no CI.

## Architecture

Spring Boot 3.4.4 + Thymeleaf + Java 17, single-module Maven.

- **Web port**: 6790 (config: `server.port`)
- **opencode serve (AI聊天/笔记库)**: port 14096 (`app.notes-port`) — 综合日报、智能问答、数据采集、客户跟进分析
- **opencode serve (代码分析)**: port 14099 (`app.code-port`) — Java项目代码分析
- **External deps**: 两个 `opencode serve` **都必须**运行
- **Data persistence**: JSON files under notes library base directory (set via `/config` page)
  - `{config-dir}/config.json` — app config (feishu webhook, paths, etc.)
  - `{config-dir}/assistant_log.json` — operation logs
  - `{baseDir}/chat/chat_sessions.json` — chat history
  - `{baseDir}/自媒体/data/*.json` — self-media operation data
  - `{baseDir}/企业/客户管理/*.json` — customer data
- **No database** — all state is file-based JSON
- **No hardcoded paths** — notes library root is configured via web UI `/config`

## First-time setup

1. Start two `opencode serve` instances (ports 14096 + 14099)
2. Start the app (`java -jar ...`)
3. Go to http://localhost:6790/config and set the **笔记库根目录** (notes library root path)
4. All features (chat, reports, operations, customers) will then work

## AI Chat flow

```
Frontend (POST /chat) → ChatController → OpenCodeService → opencode serve (POST /session/:id/message)
                                                                    ↓
Frontend (SSE stream)  ← ChatController ← OpenCodeService extracts text parts
```

Key details:
- `ChatController` returns `SseEmitter` (300s timeout)
- `OpenCodeService.sendMessage()` calls `POST /session/:id/message` synchronously (600s timeout)
- `OpenCodeService.findIdleSession()` reuses the most recent opencode session
- opencode serve appears single-threaded — avoid concurrent requests to the same session
- SSE events from `SseEmitter` use `data:{"type":"...",...}\n\n` (no space after `data:`)
  → Frontend parses with `replace(/^data:\s*/, '')` to handle both `data:` and `data: `

## SSE event types (chat)

| type | fields | meaning |
|------|--------|---------|
| `status` | `content` | progress indicator |
| `text` | `content` | AI reply text |
| `error` | `content` | error message |
| `done` | — | streaming complete |

## Scheduled tasks

| time | task |
|------|------|
| Mon-Fri 09:00 | generate comprehensive daily report |
| Mon-Fri 18:00 |下班 report reminder to Feishu |
| Tue 09:00 | article reminder for "码农老齐" |
| Thu 09:00 | article reminder for "启航电商ERP" |
| daily configurable | CSDN data collection |

All use Asia/Shanghai timezone.

## Key source layout

```
src/main/java/com/laoqi/assistant/
├── AssistantApplication.java       — @SpringBootApplication, @EnableScheduling
├── config/
│   ├── AppConfig.java              — @ConfigurationProperties(prefix="app")
│   └── PortHealthChecker.java      — Socket health check, polls every 30s
├── controller/                     — 12 controllers
│   ├── ChatController.java         — SSE streaming, /chat GET+POST, session save/delete
│   ├── DataController.java         — Generic JSON data CRUD (/api/data/*)
│   ├── IndexController.java        — / (daily report)
│   └── ...
├── service/                        — 9 services
│   ├── OpenCodeService.java        — HTTP client to opencode serve API
│   ├── ConfigService.java          — single source of truth for config + baseDir
│   ├── ChatSessionService.java     — read/write chat_sessions.json
│   ├── FeishuService.java          — webhook + bot API (raw HttpURLConnection)
│   ├── MediaDataCollectorService   — CSDN/zhihu data scraping via AI
│   └── ...
├── model/                          — POJOs for JSON persistence
└── util/                           — FileUtil, TimeUtil, MarkdownUtil, ThymeleafUtil

src/main/resources/
├── application.yml                 — all config
└── templates/                      — 15 Thymeleaf templates
```

## Gotchas

- **必须先启动 opencode serve**: 应用启动前必须先启动两个 opencode serve 实例（14096 和 14099），否则相关功能不可用
- **必须先配笔记库路径**: 首次使用必须去 `/config` 页面配置笔记库根目录，否则任何需要读笔记库的操作都会提示配置
- **无硬编码路径**: 所有路径都来自 config.json 配置，无任何代码级硬编码
- Chinese path names in config: `工作/日报`, `工作/综合日报`, `自媒体/data/`
- FeishuService uses raw `HttpURLConnection` (not RestTemplate or HttpClient), manually escapes JSON
- Self-media JSON uses flat format (articles grouped by `{account}-{platform}`, no nested `data` object)
- No `opencode.json` or `.opencode/` config — this repo does not configure OpenCode itself
- `app.log` at project root — runtime log file, gitignored
