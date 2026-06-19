package com.laoqi.assistant.controller;

import com.laoqi.assistant.model.TaskData.*;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.TaskService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
public class TaskController {

    private final TaskService taskService;
    private final LogService logService;
    private final ConfigService configService;

    public TaskController(TaskService taskService, LogService logService, ConfigService configService) {
        this.taskService = taskService;
        this.logService = logService;
        this.configService = configService;
    }

    // 页面路由已迁移到 /planner

    @PostMapping("/api/tasks/add")
    @ResponseBody
    public Map<String, Object> addTask(
            @RequestParam(required = false) Long kbId,
            @RequestParam String title,
            @RequestParam(required = false, defaultValue = "") String description,
            @RequestParam(required = false, defaultValue = "mid") String priority,
            @RequestParam(required = false, defaultValue = "") String dueDate) {
        try {
            TaskItem task = taskService.addTask(title, description, priority, dueDate, kbId);
            logService.add("任务看板", "成功", "添加任务: " + title);
            return Map.of("ok", true, "task", task);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/tasks/update")
    @ResponseBody
    public Map<String, Object> updateTask(
            @RequestParam(required = false) Long kbId,
            @RequestParam String id,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String dueDate) {
        try {
            TaskItem task = taskService.updateTask(id, title, description, status, priority, dueDate, kbId);
            if (task == null) {
                return Map.of("ok", false, "error", "任务不存在");
            }
            logService.add("任务看板", "成功", "更新任务: " + task.title);
            return Map.of("ok", true, "task", task);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/tasks/delete")
    @ResponseBody
    public Map<String, Object> deleteTask(
            @RequestParam(required = false) Long kbId,
            @RequestParam String id) {
        try {
            boolean ok = taskService.deleteTask(id, kbId);
            if (ok) {
                logService.add("任务看板", "成功", "删除任务: " + id);
            }
            return Map.of("ok", ok);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
