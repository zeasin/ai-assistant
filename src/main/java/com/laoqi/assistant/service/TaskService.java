package com.laoqi.assistant.service;

import com.laoqi.assistant.model.TaskData.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ConfigService configService;
    private final DataSource dataSource;

    public TaskService(ConfigService configService, DataSource dataSource) {
        this.configService = configService;
        this.dataSource = dataSource;
    }


    // ========== SQLite CRUD ==========

    private List<TaskItem> getAllTasksFromDb() {
        List<TaskItem> tasks = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks ORDER BY created_at DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                tasks.add(mapRowToTask(rs));
            }
        } catch (SQLException e) {
            log.error("[任务] 查询失败", e);
        }
        return tasks;
    }

    private void insertTaskToDb(TaskItem task, Long kbId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO tasks (id, title, description, status, priority, due_date, created_at, updated_at, kb_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, task.id);
            ps.setString(2, task.title);
            ps.setString(3, task.description);
            ps.setString(4, task.status);
            ps.setString(5, task.priority);
            ps.setString(6, task.dueDate);
            ps.setString(7, task.createdAt);
            ps.setString(8, task.updatedAt);
            if (kbId != null) {
                ps.setLong(9, kbId);
            } else {
                ps.setNull(9, Types.INTEGER);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[任务] 插入失败", e);
        }
    }

    private TaskItem mapRowToTask(ResultSet rs) throws SQLException {
        TaskItem task = new TaskItem();
        task.id = rs.getString("id");
        task.title = rs.getString("title");
        task.description = rs.getString("description");
        task.status = rs.getString("status");
        task.priority = rs.getString("priority");
        task.dueDate = rs.getString("due_date");
        task.createdAt = rs.getString("created_at");
        task.updatedAt = rs.getString("updated_at");
        return task;
    }

    private TaskItem getTaskFromDb(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapRowToTask(rs);
            }
        } catch (SQLException e) {
            log.error("[任务] 查询失败", e);
        }
        return null;
    }

    private void updateTaskInDb(TaskItem task) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE tasks SET title=?, description=?, status=?, priority=?, due_date=?, updated_at=? WHERE id=?")) {
            ps.setString(1, task.title);
            ps.setString(2, task.description);
            ps.setString(3, task.status);
            ps.setString(4, task.priority);
            ps.setString(5, task.dueDate);
            ps.setString(6, task.updatedAt);
            ps.setString(7, task.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[任务] 更新失败", e);
        }
    }

    private void deleteTaskFromDb(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[任务] 删除失败", e);
        }
    }

    // ========== Public API ==========

    public List<TaskItem> getAllTasks() {
        return getAllTasksFromDb();
    }

    public List<TaskItem> getAllTasks(String notesDir) {
        return getAllTasksFromDb();
    }

    public TaskItem addTask(String title, String description, String priority, String dueDate) {
        return addTaskInternal(title, description, priority, dueDate, null);
    }

    public TaskItem addTask(String notesDir, String title, String description, String priority, String dueDate) {
        return addTaskInternal(title, description, priority, dueDate, null);
    }

    public TaskItem addTask(String title, String description, String priority, String dueDate, Long kbId) {
        return addTaskInternal(title, description, priority, dueDate, kbId);
    }

    private TaskItem addTaskInternal(String title, String description, String priority, String dueDate, Long kbId) {
        TaskItem task = new TaskItem();
        task.id = "T" + System.currentTimeMillis();
        task.title = title;
        task.description = (description != null && !description.isEmpty()) ? description : null;
        task.priority = (priority != null && !priority.isEmpty()) ? priority : "mid";
        task.status = "pending";
        String now = java.time.LocalDate.now().toString();
        task.createdAt = now;
        task.updatedAt = now;
        task.dueDate = (dueDate != null && !dueDate.isEmpty()) ? dueDate : null;

        insertTaskToDb(task, kbId);

        if (kbId != null) {
        }

        return task;
    }

    public TaskItem updateTask(String id, String title, String description, String status,
                                String priority, String dueDate) {
        return updateTaskInternal(id, title, description, status, priority, dueDate, null);
    }

    public TaskItem updateTask(String notesDir, String id, String title, String description, String status,
                                String priority, String dueDate) {
        return updateTaskInternal(id, title, description, status, priority, dueDate, null);
    }

    public TaskItem updateTask(String id, String title, String description, String status,
                                String priority, String dueDate, Long kbId) {
        return updateTaskInternal(id, title, description, status, priority, dueDate, kbId);
    }

    private TaskItem updateTaskInternal(String id, String title, String description, String status,
                                String priority, String dueDate, Long kbId) {
        TaskItem task = getTaskFromDb(id);
        if (task == null) return null;

        if (title != null) task.title = title;
        if (description != null) task.description = description.isEmpty() ? null : description;
        if (status != null) task.status = status;
        if (priority != null) task.priority = priority;
        if (dueDate != null) task.dueDate = dueDate.isEmpty() ? null : dueDate;
        task.updatedAt = java.time.LocalDate.now().toString();

        updateTaskInDb(task);

        if (kbId != null) {
        }

        return task;
    }

    public boolean deleteTask(String id) {
        return deleteTaskInternal(id, null);
    }

    public boolean deleteTask(String notesDir, String id) {
        return deleteTaskInternal(id, null);
    }

    public boolean deleteTask(String id, Long kbId) {
        return deleteTaskInternal(id, kbId);
    }

    private boolean deleteTaskInternal(String id, Long kbId) {
        deleteTaskFromDb(id);

        // 同步到笔记库 JSON
        if (kbId != null) {
        }

        return true;
    }


    private List<TaskItem> getTasksByKbId(Long kbId) {
        List<TaskItem> tasks = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks WHERE kb_id = ? ORDER BY created_at DESC")) {
            if (kbId != null) {
                ps.setLong(1, kbId);
            } else {
                ps.setObject(1, null);
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                tasks.add(mapRowToTask(rs));
            }
        } catch (SQLException e) {
            log.error("[任务] 按KB查询失败", e);
        }
        return tasks;
    }
}
