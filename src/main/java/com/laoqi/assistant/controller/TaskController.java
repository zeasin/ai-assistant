package com.laoqi.assistant.controller;

import com.laoqi.assistant.model.TaskData.*;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.TaskService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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

    @GetMapping("/tasks")
    public String taskBoardPage(@RequestParam(required = false) Long kbId, Model model) {
        try {
            String notesDir = configService.getNotesDir(kbId);
            List<TaskItem> tasks = taskService.getAllTasks(notesDir);
            model.addAttribute("tasks", tasks);
            model.addAttribute("kbId", kbId);
        } catch (Exception e) {
            model.addAttribute("tasks", List.of());
            model.addAttribute("error", e.getMessage());
        }
        return "kb_tasks";
    }

    @PostMapping("/api/tasks/add")
    @ResponseBody
    public Map<String, Object> addTask(
            @RequestParam(required = false) Long kbId,
            @RequestParam String title,
            @RequestParam(required = false, defaultValue = "") String description,
            @RequestParam(required = false, defaultValue = "mid") String priority,
            @RequestParam(required = false, defaultValue = "") String dueDate) {
        try {
            String notesDir = configService.getNotesDir(kbId);
            TaskItem task = taskService.addTask(notesDir, title, description, priority, dueDate);
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
            String notesDir = configService.getNotesDir(kbId);
            TaskItem task = taskService.updateTask(notesDir, id, title, description, status, priority, dueDate);
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
            String notesDir = configService.getNotesDir(kbId);
            boolean ok = taskService.deleteTask(notesDir, id);
            if (ok) {
                logService.add("任务看板", "成功", "删除任务: " + id);
            }
            return Map.of("ok", ok);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
