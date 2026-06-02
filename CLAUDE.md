# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```powershell
# build
mvn package -q

# run (opencode serve must be running on port 14096 first)
java -jar target/ai-assistant-2.0.0.jar

# web UI at http://localhost:6790
# health check at http://localhost:6790/health
```

No tests, no linter, no CI in this project.

## Prerequisites

- **Java 17+** and Maven
- **opencode serve** must be running: `opencode serve --port 14096` (AI backend on port 14096)
- **Notes library** on port 14099 (file-based notes repo, used by the "编程AI" feature)
- Data directory: `D:\projects\richie_learning_notes` (set via `app.baseDir` in config.json)

## Project Architecture

Spring Boot 3.4.4 + Thymeleaf + Java 17, single-module Maven. No database — all state is file-based JSON.

### Config

- `application.yml` — server port (6790), Thymeleaf, logging, app settings
- `config.json` at project root or `ASSISTANT_CONFIG_DIR` — Feishu credentials, baseDir, feature flags
- `AppConfig.java` (`config/AppConfig.java`) — `@ConfigurationProperties(prefix="app")`, maps notes-port, code-port, timezone, max-history-chars

### Key Services

| Service | Responsibility |
|---------|---------------|
| `OpenCodeService` | HTTP client to opencode serve API (port 14096 for chat, 14099 for code). Uses `java.net.http.HttpClient`. Creates sessions, sends messages (600s timeout for chat, 300s for code), extracts text replies from JSON |
| `ChatSessionService` | Manages `chat_sessions.json` — CRUD for chat history |
| `FeishuService` | Feishu webhook + bot API using raw `HttpURLConnection`. Sends report reminders, article reminders, custom messages |
| `FeishuLongConnectionService` | WebSocket-based long connection for receiving Feishu messages |
| `ReportService` | Daily report generation. **Stubbed** — `generate()` always returns "AI 引擎未配置" |
| `SchedulerService` | `@Scheduled` tasks in Asia/Shanghai: Mon-Fri 09:00 daily report, 18:00下班 reminder, Tue 09:00 article reminder, Thu 09:00 article reminder |
| `ConfigService` | Reads/writes `config.json` |
| `LogService` | Manages `assistant_log.json` operation logs |
| `TodoService` | TODO management |
| `CustomerService` / `OperationsService` | Data CRUD for customer/operations JSON files |
| `StartupReportGenerator` | Generates a report on app startup |

### AI Chat Flow

```
Frontend (POST /chat) → ChatController → OpenCodeService → opencode serve (POST /session/:id/message)
                                                                    ↓
Frontend (SSE stream)  ← ChatController ← OpenCodeService extracts text parts
```

- `ChatController` returns `SseEmitter` (300s timeout)
- `OpenCodeService.sendMessage()` calls `POST /session/:id/message` synchronously (600s timeout)
- `OpenCodeService.findIdleSession()` reuses the most recent opencode session
- SSE events: `status` (progress), `text` (AI reply), `error`, `done`
- Frontend parses SSE data with `replace(/^data:\s*/, '')` to handle both `data:` and `data: `

### Controllers (13 total)

All use `@Controller` or `@RestController`:
- `IndexController` — `/` (daily report page), `/generate` (manual report trigger)
- `ChatController` — `/chat` GET+POST, SSE streaming, session save/delete
- `HealthController` — `/health` endpoint
- `ConfigController` / `ApiConfigController` — web UI and API for config editing
- `WorkReportController` — daily/weekly report views
- `BrowseController` — file/directory browsing of the notes library
- `CustomerController`, `OperationsController` — data CRUD views
- `LogController` — operation log viewer
- `HelpController` — help page
- `GlobalModelAdvice` — global Thymeleaf model attributes (feishu status, ports, time)

### Templates (15 Thymeleaf .html)

Layout-based with `layout.html` as the common wrapper. Key pages: `index.html` (daily report dashboard), `chat.html` (AI chat), `config.html`, `browse.html`, `view.html`, `work_reports.html`, `daily_reports.html`, `weekly_reports.html`, `customers.html`, `operations.html`, `log.html`, `help.html`.

### Port Health Checking

`PortHealthChecker` (`config/PortHealthChecker.java`) polls port 14096 and 14099 every 30 seconds via socket connection. Results exposed via `GlobalModelAdvice` for UI display.

### Data Files (under `app.baseDir`)

- `chat_sessions.json` — AI chat history
- `config.json` — Feishu webhook, app ID/secret, chat ID, feature flags
- `assistant_log.json` — operation logs
- Various JSON files for customers, operations, etc.

### Key Dependencies (pom.xml)

- Spring Boot 3.4.4 (web, thymeleaf, jackson)
- commons-io 2.18.0
- commonmark 0.24.0 (Markdown rendering)
- Java-WebSocket 1.6.0 (Feishu long connection)
- Feishu OpenAPI SDK 2.3.2 (larksuite oapi)
- thymeleaf-layout-dialect

## Gotchas

- `ReportService.generate()` is **stubbed** — always returns "AI 引擎未配置". Needs opencode serve integration to function.
- Chinese path names in config: `工作/日报`, `工作/综合日报`, `自媒体/运营数据.json`
- `FeishuService` uses raw `HttpURLConnection` (not RestTemplate or HttpClient), manually escapes JSON
- opencode serve appears single-threaded — avoid concurrent requests to the same session
- No `opencode.json` or `.opencode/` config in this repo
- `app.log` at project root — runtime log file, gitignored
