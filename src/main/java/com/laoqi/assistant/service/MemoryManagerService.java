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
 * Agent 记忆管理器 — 持久化存储用户画像、关键事实、项目里程碑等语义记忆（L3/L4）。
 * 数据存储在 SQLite 的 agent_memories 表中，按知识库隔离。
 */
@Service
public class MemoryManagerService {

    private static final Logger log = LoggerFactory.getLogger(MemoryManagerService.class);

    private final DataSource dataSource;

    public MemoryManagerService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        createTable();
    }

    private void createTable() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS agent_memories (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    kb_id       INTEGER NOT NULL DEFAULT 0,
                    category    TEXT NOT NULL DEFAULT 'general',
                    key_name    TEXT NOT NULL,
                    value       TEXT NOT NULL,
                    importance  INTEGER NOT NULL DEFAULT 1,
                    created_at  TEXT NOT NULL,
                    updated_at  TEXT NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_kb ON agent_memories(kb_id, category)");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_key ON agent_memories(kb_id, key_name)");
            log.info("Table agent_memories initialized");
        } catch (SQLException e) {
            log.warn("Failed to create agent_memories table: {}", e.getMessage());
        }
    }

    /** 存储一条记忆（不存在则插入，存在则更新） */
    public void put(Long kbId, String category, String key, String value, int importance) {
        String now = TimeUtil.nowStr();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO agent_memories (kb_id, category, key_name, value, importance, created_at, updated_at) "
                   + "VALUES (?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM agent_memories WHERE kb_id=? AND key_name=?), ?), ?)")) {
            ps.setLong(1, kbId != null ? kbId : 0);
            ps.setString(2, category);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.setInt(5, importance);
            ps.setLong(6, kbId != null ? kbId : 0);
            ps.setString(7, key);
            ps.setString(8, now);
            ps.setString(9, now);
            ps.executeUpdate();
            log.debug("[Memory] 已保存记忆: kbId={}, key={}, value={}", kbId, key, value);
        } catch (SQLException e) {
            log.warn("[Memory] 保存失败: {}", e.getMessage());
        }
    }

    public void put(Long kbId, String category, String key, String value) {
        put(kbId, category, key, value, 1);
    }

    /** 读取单条记忆 */
    public String get(Long kbId, String key) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT value FROM agent_memories WHERE kb_id=? AND key_name=?")) {
            ps.setLong(1, kbId != null ? kbId : 0);
            ps.setString(2, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("value");
            }
        } catch (SQLException e) {
            log.warn("[Memory] 读取失败: {}", e.getMessage());
        }
        return null;
    }

    /** 按分类列出所有记忆 */
    public List<MemoryEntry> list(Long kbId, String category) {
        List<MemoryEntry> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT key_name, value, importance, category, created_at, updated_at "
                   + "FROM agent_memories WHERE kb_id=? AND category=? ORDER BY importance DESC, updated_at DESC")) {
            ps.setLong(1, kbId != null ? kbId : 0);
            ps.setString(2, category);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new MemoryEntry(
                        rs.getString("key_name"),
                        rs.getString("value"),
                        rs.getInt("importance"),
                        rs.getString("category"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ));
            }
        } catch (SQLException e) {
            log.warn("[Memory] 列出失败: {}", e.getMessage());
        }
        return result;
    }

    /** 列出所有记忆分类 */
    public List<String> listCategories(Long kbId) {
        List<String> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT category FROM agent_memories WHERE kb_id=? ORDER BY category")) {
            ps.setLong(1, kbId != null ? kbId : 0);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(rs.getString("category"));
            }
        } catch (SQLException e) {
            log.warn("[Memory] 列出分类失败: {}", e.getMessage());
        }
        return result;
    }

    /** 搜索记忆内容 */
    public List<MemoryEntry> search(Long kbId, String keyword) {
        List<MemoryEntry> result = new ArrayList<>();
        String like = "%" + keyword + "%";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT key_name, value, importance, category, created_at, updated_at "
                   + "FROM agent_memories WHERE kb_id=? AND (key_name LIKE ? OR value LIKE ?) "
                   + "ORDER BY importance DESC, updated_at DESC LIMIT 20")) {
            ps.setLong(1, kbId != null ? kbId : 0);
            ps.setString(2, like);
            ps.setString(3, like);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new MemoryEntry(
                        rs.getString("key_name"),
                        rs.getString("value"),
                        rs.getInt("importance"),
                        rs.getString("category"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ));
            }
        } catch (SQLException e) {
            log.warn("[Memory] 搜索失败: {}", e.getMessage());
        }
        return result;
    }

    /** 删除一条记忆 */
    public void delete(Long kbId, String key) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM agent_memories WHERE kb_id=? AND key_name=?")) {
            ps.setLong(1, kbId != null ? kbId : 0);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[Memory] 删除失败: {}", e.getMessage());
        }
    }

    /** 格式化输出所有记忆供 AI 上下文使用 */
    public String formatMemories(Long kbId) {
        List<MemoryEntry> entries = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT key_name, value, importance, category, created_at, updated_at "
                   + "FROM agent_memories WHERE kb_id=? ORDER BY importance DESC, updated_at DESC LIMIT 50")) {
            ps.setLong(1, kbId != null ? kbId : 0);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entries.add(new MemoryEntry(
                        rs.getString("key_name"),
                        rs.getString("value"),
                        rs.getInt("importance"),
                        rs.getString("category"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ));
            }
        } catch (SQLException e) {
            log.warn("[Memory] 格式化失败: {}", e.getMessage());
        }
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("== 我记住的关于你的信息 ==\n\n");
        String currentCategory = "";
        for (MemoryEntry e : entries) {
            if (!e.category.equals(currentCategory)) {
                currentCategory = e.category;
                sb.append("【").append(categoryLabel(currentCategory)).append("】\n");
            }
            sb.append("- ").append(e.key).append(": ").append(e.value);
            if (e.importance >= 3) sb.append(" ⭐");
            sb.append("\n");
        }
        sb.append("\n---\n");
        return sb.toString();
    }

    private String categoryLabel(String c) {
        return switch (c) {
            case "user_profile" -> "用户画像";
            case "preference" -> "偏好设置";
            case "project" -> "项目信息";
            case "fact" -> "关键事实";
            case "goal" -> "目标跟踪";
            default -> c;
        };
    }

    public record MemoryEntry(String key, String value, int importance, String category, String createdAt, String updatedAt) {}
}
