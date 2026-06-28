package com.laoqi.assistant.service;

import com.laoqi.assistant.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;


/**
 * Agent 决策追踪 — 记录每次对话的思考链、工具调用、结果，支持回溯查看 AI 推理过程。
 * 数据存储在 SQLite 的 agent_traces 表中。
 */
@Service
public class AgentTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceService.class);

    private final DataSource dataSource;

    public AgentTraceService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        createTable();
    }

    private void createTable() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS agent_traces (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  TEXT NOT NULL,
                    step_index  INTEGER NOT NULL,
                    step_type   TEXT NOT NULL,
                    content     TEXT NOT NULL,
                    details     TEXT,
                    duration_ms INTEGER DEFAULT 0,
                    created_at  TEXT NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_traces_session ON agent_traces(session_id)");
            log.info("Table agent_traces initialized");
        } catch (SQLException e) {
            log.warn("Failed to create agent_traces table: {}", e.getMessage());
        }
    }

    /** 记录一个追踪步骤 */
    public void record(String sessionId, int stepIndex, String stepType, String content, String details, long durationMs) {
        String now = TimeUtil.nowStr();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO agent_traces (session_id, step_index, step_type, content, details, duration_ms, created_at) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setInt(2, stepIndex);
            ps.setString(3, stepType);
            ps.setString(4, content);
            ps.setString(5, details);
            ps.setLong(6, durationMs);
            ps.setString(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[Trace] 记录失败: {}", e.getMessage());
        }
    }

    /** 快速记录（自动当前时间戳） */
    public void record(String sessionId, int stepIndex, String stepType, String content) {
        record(sessionId, stepIndex, stepType, content, null, 0);
    }

    /** 获取会话的完整决策链 */
    public List<TraceStep> getTrace(String sessionId) {
        List<TraceStep> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT step_index, step_type, content, details, duration_ms, created_at "
                   + "FROM agent_traces WHERE session_id=? ORDER BY step_index ASC")) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new TraceStep(
                        rs.getInt("step_index"),
                        rs.getString("step_type"),
                        rs.getString("content"),
                        rs.getString("details"),
                        rs.getLong("duration_ms"),
                        rs.getString("created_at")
                ));
            }
        } catch (SQLException e) {
            log.warn("[Trace] 查询失败: {}", e.getMessage());
        }
        return result;
    }

    /** 清除会话的追踪记录 */
    public void clearTrace(String sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM agent_traces WHERE session_id=?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[Trace] 清除失败: {}", e.getMessage());
        }
    }

    /** 格式化决策链为可读文本（给前端展示） */
    public String formatTrace(String sessionId) {
        List<TraceStep> steps = getTrace(sessionId);
        if (steps.isEmpty()) return "暂无决策记录";

        StringBuilder sb = new StringBuilder();
        sb.append("🧠 决策追踪\n");
        sb.append("━━━━━━━━━━━━━━━━━━\n\n");
        for (TraceStep step : steps) {
            String icon = switch (step.type) {
                case "thought" -> "🤔";
                case "plan" -> "📋";
                case "tool_call" -> "🔧";
                case "tool_result" -> "📥";
                case "observation" -> "👀";
                case "answer" -> "💬";
                default -> "•";
            };
            sb.append(icon).append(" Step ").append(step.index).append(" [").append(stepLabel(step.type)).append("]\n");
            sb.append("   ").append(step.content).append("\n");
            if (step.details != null && !step.details.isEmpty()) {
                String detailPreview = step.details.length() > 200 ? step.details.substring(0, 200) + "..." : step.details;
                sb.append("   详情: ").append(detailPreview).append("\n");
            }
            if (step.durationMs > 0) {
                sb.append("   耗时: ").append(step.durationMs).append("ms\n");
            }
            sb.append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━\n");
        return sb.toString();
    }

    /** 获取最新 step_index（用于追加） */
    public int getNextStepIndex(String sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(MAX(step_index), -1) + 1 FROM agent_traces WHERE session_id=?")) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("[Trace] 获取步数失败: {}", e.getMessage());
        }
        return 0;
    }

    private String stepLabel(String type) {
        return switch (type) {
            case "thought" -> "思考";
            case "plan" -> "规划";
            case "tool_call" -> "工具调用";
            case "tool_result" -> "工具结果";
            case "observation" -> "观察";
            case "answer" -> "回答";
            default -> type;
        };
    }

    public record TraceStep(int index, String type, String content, String details, long durationMs, String createdAt) {}
}
