package com.laoqi.assistant.collector;

import com.laoqi.assistant.collector.model.CollectorLog;
import com.laoqi.assistant.collector.model.CollectorResult;
import com.laoqi.assistant.collector.model.CollectorTask;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/collector")
public class CollectorController {

    private final CollectorService collectorService;
    private final CollectorScheduler collectorScheduler;

    public CollectorController(CollectorService collectorService, CollectorScheduler collectorScheduler) {
        this.collectorService = collectorService;
        this.collectorScheduler = collectorScheduler;
    }

    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getAllTasks() {
        return ResponseEntity.ok(Map.of("ok", true, "data", collectorService.getAllTasks()));
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String id) {
        CollectorTask task = collectorService.getTask(id);
        if (task == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "Task not found"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", task));
    }

    @PostMapping("/tasks")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody CollectorTask task) {
        try {
            CollectorTask created = collectorService.createTask(task);
            collectorScheduler.scheduleTask(created);
            return ResponseEntity.ok(Map.of("ok", true, "data", created));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> updateTask(@PathVariable String id, @RequestBody CollectorTask task) {
        CollectorTask updated = collectorService.updateTask(id, task);
        if (updated == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "Task not found"));
        }
        collectorScheduler.scheduleTask(updated);
        return ResponseEntity.ok(Map.of("ok", true, "data", updated));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable String id) {
        boolean deleted = collectorService.deleteTask(id);
        if (!deleted) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "Task not found"));
        }
        collectorScheduler.unscheduleTask(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/tasks/{id}/run")
    public ResponseEntity<Map<String, Object>> runTask(@PathVariable String id) {
        try {
            CollectorResult result = collectorService.executeTask(id);
            return ResponseEntity.ok(Map.of("ok", true, "data", result));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/tasks/{id}/logs")
    public ResponseEntity<Map<String, Object>> getTaskLogs(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", true, "data", collectorService.getTaskLogs(id)));
    }

    @GetMapping("/tasks/{id}/results")
    public ResponseEntity<Map<String, Object>> getTaskResults(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", true, "data", collectorService.getTaskResults(id)));
    }

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getAllLogs() {
        return ResponseEntity.ok(Map.of("ok", true, "data", collectorService.getAllLogs()));
    }

    @PostMapping("/reschedule")
    public ResponseEntity<?> rescheduleAll() {
        collectorScheduler.rescheduleAll();
        return ResponseEntity.ok(Map.of("ok", true, "message", "All tasks rescheduled"));
    }

    @GetMapping("/prompts/{key}")
    public ResponseEntity<Map<String, Object>> getPrompt(@PathVariable String key) {
        String template = collectorService.getPromptTemplate(key);
        return ResponseEntity.ok(Map.of("ok", true, "template", template != null ? template : ""));
    }

    @PostMapping("/prompts/{key}")
    public ResponseEntity<Map<String, Object>> savePrompt(@PathVariable String key, @RequestBody Map<String, String> body) {
        String template = body.getOrDefault("template", "");
        collectorService.savePromptTemplate(key, template);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}