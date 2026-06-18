package com.laoqi.assistant.service;

import com.laoqi.assistant.model.TaskData.*;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ConfigService configService;

    public TaskService(ConfigService configService) {
        this.configService = configService;
    }

    private Path dataFile() {
        return dataFile(configService.getNotesDir());
    }

    private Path dataFile(String notesDir) {
        Path taskDir = Paths.get(notesDir).resolve("AI").resolve("任务");
        if (!java.nio.file.Files.exists(taskDir)) {
            try {
                java.nio.file.Files.createDirectories(taskDir);
            } catch (Exception e) {
                log.warn("[任务] 创建目录失败: {}", taskDir);
            }
        }
        return taskDir.resolve("data.json");
    }

    public Root loadData() {
        return loadData(configService.getNotesDir());
    }

    public Root loadData(String notesDir) {
        return FileUtil.readJson(dataFile(notesDir), Root.class, new Root());
    }

    private void saveData(Root root) {
        FileUtil.writeJson(dataFile(), root);
    }

    public List<TaskItem> getAllTasks() {
        return getAllTasks(configService.getNotesDir());
    }

    public List<TaskItem> getAllTasks(String notesDir) {
        Root root = loadData(notesDir);
        if (root.tasks == null) root.tasks = new ArrayList<>();
        if (root.meta == null) root.meta = new LinkedHashMap<>();
        return root.tasks;
    }

    public TaskItem addTask(String title, String description, String priority, String dueDate) {
        return addTask(configService.getNotesDir(), title, description, priority, dueDate);
    }

    public TaskItem addTask(String notesDir, String title, String description, String priority, String dueDate) {
        Root root = loadData(notesDir);
        if (root.tasks == null) root.tasks = new ArrayList<>();
        if (root.meta == null) root.meta = new LinkedHashMap<>();

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

        root.tasks.add(task);
        root.meta.put("lastUpdated", now);
        saveData(root);

        return task;
    }

    public TaskItem updateTask(String id, String title, String description, String status,
                                String priority, String dueDate) {
        Root root = loadData();
        if (root.tasks == null) return null;
        if (root.meta == null) root.meta = new LinkedHashMap<>();

        for (TaskItem task : root.tasks) {
            if (task.id.equals(id)) {
                if (title != null) task.title = title;
                if (description != null) task.description = description.isEmpty() ? null : description;
                if (status != null) task.status = status;
                if (priority != null) task.priority = priority;
                if (dueDate != null) task.dueDate = dueDate.isEmpty() ? null : dueDate;
                task.updatedAt = java.time.LocalDate.now().toString();
                root.meta.put("lastUpdated", task.updatedAt);
                saveData(root);
                return task;
            }
        }
        return null;
    }

    public boolean deleteTask(String id) {
        Root root = loadData();
        if (root.tasks == null) return false;
        if (root.meta == null) root.meta = new LinkedHashMap<>();

        boolean removed = root.tasks.removeIf(t -> t.id.equals(id));
        if (removed) {
            root.meta.put("lastUpdated", java.time.LocalDate.now().toString());
            saveData(root);
        }
        return removed;
    }
}
