package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.LogService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final LogService logService;

    public KnowledgeBaseController(KnowledgeBaseService kbService, LogService logService) {
        this.kbService = kbService;
        this.logService = logService;
    }

    @GetMapping("/api/kb/list")
    public Map<String, Object> list() {
        List<KnowledgeBaseEntity> list = kbService.getAll();
        List<Map<String, Object>> result = list.stream().map(this::toMap).collect(Collectors.toList());
        return Map.of("ok", true, "list", result);
    }

    @GetMapping("/api/kb/current")
    public Map<String, Object> current() {
        KnowledgeBaseEntity kb = kbService.getActiveKb();
        if (kb == null) {
            kb = kbService.getFirst();
        }
        if (kb == null) {
            return Map.of("ok", false, "error", "未配置任何知识库");
        }
        return Map.of("ok", true, "kb", toMap(kb));
    }

    @PostMapping("/api/kb/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String notesDir = (String) body.get("notesDir");

        if (name == null || name.isBlank() || notesDir == null || notesDir.isBlank()) {
            return Map.of("ok", false, "error", "名称和笔记库路径不能为空");
        }

        kbService.save(body);
        logService.add("知识库", "保存", "知识库已保存: " + name);
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/kb/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        kbService.delete(id);
        logService.add("知识库", "删除", "知识库已删除: id=" + id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/kb/active/{id}")
    public Map<String, Object> setActive(@PathVariable Long id) {
        kbService.setActive(id);
        KnowledgeBaseEntity kb = kbService.getById(id);
        logService.add("知识库", "切换", "切换到知识库: " + (kb != null ? kb.getName() : id));
        return Map.of("ok", true);
    }

    @PostMapping("/api/kb/reorder")
    public Map<String, Object> reorder(@RequestBody List<Long> ids) {
        kbService.reorder(ids);
        logService.add("知识库", "排序", "知识库排序已更新");
        return Map.of("ok", true);
    }

    private Map<String, Object> toMap(KnowledgeBaseEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("notesDir", e.getNotesDir());
        m.put("labels", e.getLabels());
        m.put("isActive", e.getIsActive());
        m.put("sortOrder", e.getSortOrder());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }
}
