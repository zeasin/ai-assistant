package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
public class AiGuideController {

    private static final Logger log = LoggerFactory.getLogger(AiGuideController.class);
    private final ConfigService configService;
    private final LogService logService;

    public AiGuideController(ConfigService configService, LogService logService) {
        this.configService = configService;
        this.logService = logService;
    }

    @GetMapping("/ai-guide")
    public String aiGuidePage() {
        return "ai_guide";
    }

    // ---- AGENTS.md ----

    @GetMapping("/api/ai-guide/agents")
    @ResponseBody
    public Map<String, Object> getAgents() {
        Path file = getAgentsFile();
        if (file == null) {
            return Map.of("ok", false, "error", "未配置笔记库根目录，请先在「设置」页面配置");
        }
        if (!Files.exists(file)) {
            return Map.of("ok", true, "content", "", "path", file.toString());
        }
        String content = FileUtil.readText(file);
        return Map.of("ok", true, "content", content, "path", file.toString());
    }

    @PostMapping("/api/ai-guide/agents")
    @ResponseBody
    public Map<String, Object> saveAgents(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        Path file = getAgentsFile();
        if (file == null) {
            return Map.of("ok", false, "error", "未配置笔记库根目录，请先在「设置」页面配置");
        }
        try {
            FileUtil.writeText(file, content);
            logService.add("AI指南", "保存AGENTS.md", file.toString());
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ---- AI 记忆 ----

    @GetMapping("/api/ai-guide/memory")
    @ResponseBody
    public Map<String, Object> listMemory() {
        Path dir = getMemoryDir();
        if (dir == null || !Files.exists(dir)) {
            return Map.of("ok", true, "files", List.of(), "path", dir != null ? dir.toString() : "");
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Map<String, String>> fileList = files
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(p -> {
                        Map<String, String> info = new LinkedHashMap<>();
                        info.put("name", p.getFileName().toString());
                        info.put("path", p.toString());
                        return info;
                    })
                    .collect(Collectors.toList());
            return Map.of("ok", true, "files", fileList, "path", dir.toString());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @GetMapping("/api/ai-guide/memory/{name}")
    @ResponseBody
    public Map<String, Object> getMemory(@PathVariable String name) {
        Path dir = getMemoryDir();
        if (dir == null) {
            return Map.of("ok", false, "error", "无法确定记忆目录");
        }
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir)) {
            return Map.of("ok", false, "error", "非法文件名");
        }
        if (!Files.exists(file)) {
            return Map.of("ok", true, "content", "");
        }
        String content = FileUtil.readText(file);
        return Map.of("ok", true, "content", content);
    }

    @PostMapping("/api/ai-guide/memory")
    @ResponseBody
    public Map<String, Object> saveMemory(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "");
        String content = body.getOrDefault("content", "");
        if (name.isEmpty()) {
            return Map.of("ok", false, "error", "文件名不能为空");
        }
        if (!name.endsWith(".md")) {
            name = name + ".md";
        }
        Path dir = getMemoryDir();
        if (dir == null) {
            return Map.of("ok", false, "error", "无法确定记忆目录");
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name).normalize();
            if (!file.startsWith(dir)) {
                return Map.of("ok", false, "error", "非法文件名");
            }
            FileUtil.writeText(file, content);
            logService.add("AI指南", "保存记忆", name);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @DeleteMapping("/api/ai-guide/memory/{name}")
    @ResponseBody
    public Map<String, Object> deleteMemory(@PathVariable String name) {
        Path dir = getMemoryDir();
        if (dir == null) {
            return Map.of("ok", false, "error", "无法确定记忆目录");
        }
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir)) {
            return Map.of("ok", false, "error", "非法文件名");
        }
        try {
            Files.deleteIfExists(file);
            logService.add("AI指南", "删除记忆", name);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ---- helpers ----

    private Path getAgentsFile() {
        try {
            String baseDir = configService.getBaseDir();
            return Paths.get(baseDir, "AGENTS.md");
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private Path getMemoryDir() {
        try {
            String baseDir = configService.getBaseDir();
            return Paths.get(baseDir, "AI", "记忆");
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
