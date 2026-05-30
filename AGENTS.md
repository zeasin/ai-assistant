# 老齐AI个人助理 V2

## Quick start

```powershell
# build
mvn package -q

# run (opencode serve must be running on port 14096)
java -jar target/assistant-v2-2.0.0.jar

# web UI at http://localhost:6790
# health check at http://localhost:6790/health
```

No tests, no linter, no CI.

## Architecture

Spring Boot 3.4.4 + Thymeleaf + Java 17, single-module Maven.

- **Web port**: 6790 (config: `server.port`)
- **opencode serve**: required on port 14096 (`app.notes-port`) for AI chat
- **Notes library**: port 14099 (`app.code-port`) — file-based notes repo
- **External deps**: `opencode serve` must be running (`opencode serve --port 14096`)
- **Data persistence**: JSON files under `app.base-dir` (default: `D:\projects\richie_learning_notes`)
  - `chat_sessions.json` — chat history
  - `config.json` — feishu webhook etc.
  - `assistant_log.json` — operation logs
- **No database** — all state is file-based JSON

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

All use Asia/Shanghai timezone.

## Key source layout

```
src/main/java/com/laoqi/assistant/
├── AssistantApplication.java       — @SpringBootApplication, @EnableScheduling
├── config/
│   ├── AppConfig.java              — @ConfigurationProperties(prefix="app")
│   └── PortHealthChecker.java      — Socket health check, polls every 30s
├── controller/                     — 12 controllers, all use `@Controller` or `@RestController`
│   ├── ChatController.java         — SSE streaming, /chat GET+POST, session save/delete
│   ├── IndexController.java        — / (daily report), /generate (manual)
│   ├── HealthController.java       — /health
│   └── ...
├── service/                        — 10 services
│   ├── OpenCodeService.java        — HTTP client to opencode serve API
│   ├── ChatSessionService.java     — read/write chat_sessions.json
│   ├── FeishuService.java          — webhook + bot API (raw HttpURLConnection)
│   ├── ReportService.java          — daily report (currently stubbed)
│   └── ...
├── model/                          — POJOs for JSON persistence
└── util/                           — FileUtil, TimeUtil, MarkdownUtil, ThymeleafUtil

src/main/resources/
├── application.yml                 — all config
└── templates/                      — 15 Thymeleaf templates
```

## Gotchas

- `ReportService.generate()` is **stubbed** — always returns "AI 引擎未配置". Needs opencode serve integration to work.
- Chinese path names in config: `工作/日报`, `工作/综合日报`, `自媒体/运营数据.json`
- FeishuService uses raw `HttpURLConnection` (not RestTemplate or HttpClient), manually escapes JSON
- No `opencode.json` or `.opencode/` config — this repo does not configure OpenCode itself
- `app.log` at project root — runtime log file, gitignored
