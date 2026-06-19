package com.laoqi.assistant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.model.ReminderData.Reminder;
import com.laoqi.assistant.model.TaskData.TaskItem;
import com.laoqi.assistant.service.AgentAnalysisService;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.DirectoryDataService;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ReportService;
import com.laoqi.assistant.service.TaskService;
import com.laoqi.assistant.service.ReminderService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.MarkdownUtil;
import com.laoqi.assistant.util.TimeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    private static final TypeReference<Map<String, String>> LABELS_TYPE = new TypeReference<>() {};
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".obsidian", "__pycache__", ".DS_Store",
            ".claude", ".playwright-mcp", ".sisyphus");

    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.ExecutorService analysisExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final KnowledgeBaseService kbService;
    private final LogService logService;
    private final TaskService taskService;
    private final ReminderService reminderService;
    private final ReportService reportService;
    private final ConfigService configService;
    private final AgentAnalysisService agentAnalysisService;
    private final DirectoryDataService directoryDataService;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   LogService logService, TaskService taskService,
                                   ReminderService reminderService, ReportService reportService,
                                   ConfigService configService,
                                   AgentAnalysisService agentAnalysisService,
                                   DirectoryDataService directoryDataService) {
        this.kbService = kbService;
        this.logService = logService;
        this.taskService = taskService;
        this.reminderService = reminderService;
        this.reportService = reportService;
        this.configService = configService;
        this.agentAnalysisService = agentAnalysisService;
        this.directoryDataService = directoryDataService;
    }

    private Path kbDir(KnowledgeBaseEntity kb) {
        return Paths.get(kb.getNotesDir());
    }

    private Path safeResolve(Path base, String rel) {
        Path normalized = base.normalize();
        Path resolved = normalized.resolve(rel != null ? rel : "").normalize();
        if (!resolved.startsWith(normalized)) return normalized;
        return resolved;
    }

    // ========== 页面路由 ==========

    @GetMapping("/kb/{id}")
    public String overview(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));

        long activeTaskCount = taskService.getAllTasks(kb.getNotesDir()).stream()
                .filter(t -> !"done".equals(t.status))
                .count();
        model.put("taskCount", activeTaskCount);
        model.put("reminderCount", reminderService.getAllReminders(kb.getNotesDir()).size());

        String todayReport = reportService.readTodayReport(kb.getNotesDir());
        if (todayReport != null) {
            model.put("report", MarkdownUtil.toHtml(todayReport));
            model.put("report_time", reportService.getLatestReportTime().isEmpty() ? "今日已生成" : reportService.getLatestReportTime());
            model.put("report_error", "");
        } else {
            String report = reportService.getLatestReport();
            String rt = reportService.getLatestReportTime();
            String err = reportService.getLatestError();
            if (report != null && !report.isEmpty()) {
                model.put("report", MarkdownUtil.toHtml(report));
            } else {
                model.put("report", "");
            }
            model.put("report_time", rt.isEmpty() ? "尚未生成" : rt);
            model.put("report_error", err);
        }

        return "kb_overview";
    }

    // 任务/提醒页面已迁移到 /planner

    // ========== 目录分析 ==========

    @GetMapping("/kb/{id}/notes")
    public String notes(@PathVariable Long id,
                        @RequestParam(required = false, defaultValue = "") String dir,
                        Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));

        if (kb.getNotesDir() == null || kb.getNotesDir().isBlank()) {
            model.put("error", "未配置笔记库路径");
            return "kb_modules";
        }

        Path base = kbDir(kb);

        // dir 为空 → 显示一级目录列表
        if (dir.isEmpty()) {
            model.put("topDirs", listTopDirs(base, kb.getDirSettings()));
            model.put("allDirInfos", listAllDirInfos(base, kb.getDirSettings()));
            model.put("dirSettings", parseDirSettings(kb.getDirSettings()));
            model.put("currentDir", "");
            return "kb_modules";
        }

        // dir 不为空 → 显示目录内容 + AI 分析
        Path target = safeResolve(base, dir);
        if (!Files.isDirectory(target)) {
            return "redirect:/kb/" + id + "/notes";
        }

        model.put("topDirs", listTopDirs(base, kb.getDirSettings()));
        model.put("allDirInfos", listAllDirInfos(base, kb.getDirSettings()));
        model.put("dirSettings", parseDirSettings(kb.getDirSettings()));
        model.put("currentDir", dir);
        model.put("files", listDirContents(target));
        model.put("breadcrumbs", buildBreadcrumbs(dir));
        model.put("breadcrumbLinks", buildBreadcrumbLinks(dir));
        model.put("parent", parentDir(dir));

        // 读取该目录的历史分析报告
        Path analysisDir = target.resolve("AI分析");
        model.put("latestReport", readLatestReport(analysisDir));

        // 读取 JSON 数据文件列表
        Path dataDir = target.resolve("data");
        model.put("jsonFiles", directoryDataService.listJsonFiles(dataDir));

        return "kb_module_detail";
    }

    @GetMapping("/kb/{id}/notes/view")
    public String viewFile(@PathVariable Long id, @RequestParam(defaultValue = "") String path,
                           Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));

        if (path.isEmpty()) return "redirect:/kb/" + id + "/notes";

        Path target = safeResolve(kbDir(kb), path);
        if (!Files.isRegularFile(target) || !target.toString().endsWith(".md"))
            return "redirect:/kb/" + id + "/notes";

        String content = FileUtil.readText(target);
        content = MarkdownUtil.stripFrontmatter(content);
        String html = MarkdownUtil.toHtml(content);
        String displayName = target.getFileName().toString().replace(".md", "");
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";

        model.put("title", displayName);
        model.put("content", html);
        model.put("parent", parent);
        model.put("breadcrumbLinks", buildBreadcrumbLinks(parent));
        return "view";
    }

    // ========== 目录分析 API ==========

    @GetMapping("/kb/{id}/api/analyze-dir")
    public SseEmitter analyzeDir(@PathVariable Long id,
                                 @RequestParam String dir,
                                 @RequestParam(required = false, defaultValue = "") String prompt) {
        SseEmitter emitter = new SseEmitter(300_000L);
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) {
            try {
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "error", "content", "知识库不存在"))));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        Path scopeDir = safeResolve(kbDir(kb), dir);

        analysisExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "status", "content", "⏳ AI 正在分析 " + dir + "..."))));

                String systemPrompt = "你是一个数据分析助手。你拥有文件操作工具，可以自主读取目录下的文件。\n"
                    + "请先用 listDir 探索目录结构，用 readFile 读取相关文件（JSON 数据、Markdown 文档等），"
                    + "然后给出分析结果。用中文回复。";

                String result = agentAnalysisService.analyze(scopeDir, prompt, systemPrompt);

                // 保存分析结果
                Path analysisDir = scopeDir.resolve("AI分析");
                java.nio.file.Files.createDirectories(analysisDir);
                FileUtil.writeText(analysisDir.resolve(TimeUtil.todayStr() + ".md"), result);

                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "text", "content", result))));
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "done"))));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                            Map.of("type", "error", "content", "AI 分析失败: " + e.getMessage()))));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });
        return emitter;
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/dir-settings")
    public Map<String, Object> saveDirSettings(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            kb.setDirSettings(mapper.writeValueAsString(body));
            kbService.save(Map.of("id", id, "dirSettings", kb.getDirSettings()));
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/dirs")
    public Map<String, Object> listDirs(@PathVariable Long id) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        if (kb.getNotesDir() == null || kb.getNotesDir().isBlank()) {
            return Map.of("ok", true, "dirs", List.of());
        }
        Path base = kbDir(kb);
        List<String> allDirs = scanTopDirs(base);
        return Map.of("ok", true, "dirs", allDirs);
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/dir-settings")
    public Map<String, Object> getDirSettings(@PathVariable Long id) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false);
        Map<String, Object> settings = parseDirSettings(kb.getDirSettings());
        return Map.of("ok", true, "settings", settings);
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/dir-rename")
    public Map<String, Object> renameDir(@PathVariable Long id, @RequestBody Map<String, String> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        String oldName = body.getOrDefault("oldName", "").trim();
        String newName = body.getOrDefault("newName", "").trim();
        if (oldName.isEmpty() || newName.isEmpty()) {
            return Map.of("ok", false, "error", "目录名不能为空");
        }

        Path base = kbDir(kb);
        Path oldPath = safeResolve(base, oldName);
        Path newPath = safeResolve(base, newName);

        if (!Files.isDirectory(oldPath)) {
            return Map.of("ok", false, "error", "原目录不存在");
        }
        if (Files.exists(newPath)) {
            return Map.of("ok", false, "error", "目标目录名已存在");
        }

        try {
            Files.move(oldPath, newPath);

            // 更新 dirSettings 中的排序和隐藏列表
            String dirSettings = kb.getDirSettings();
            if (dirSettings != null && !dirSettings.isBlank()) {
                Map<String, Object> settings = parseDirSettings(dirSettings);
                @SuppressWarnings("unchecked")
                List<String> sort = (List<String>) settings.getOrDefault("sort", new ArrayList<>());
                @SuppressWarnings("unchecked")
                List<String> hidden = (List<String>) settings.getOrDefault("hidden", new ArrayList<>());

                int idx = sort.indexOf(oldName);
                if (idx >= 0) sort.set(idx, newName);
                idx = hidden.indexOf(oldName);
                if (idx >= 0) hidden.set(idx, newName);

                settings.put("sort", sort);
                settings.put("hidden", hidden);
                kb.setDirSettings(mapper.writeValueAsString(settings));
                kbService.save(Map.of("id", id, "dirSettings", kb.getDirSettings()));
            }

            logService.add("目录管理", "重命名", oldName + " → " + newName);
            return Map.of("ok", true);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "重命名失败: " + e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/dir-delete")
    public Map<String, Object> deleteDir(@PathVariable Long id, @RequestBody Map<String, String> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        String dirName = body.getOrDefault("name", "").trim();
        if (dirName.isEmpty()) {
            return Map.of("ok", false, "error", "目录名不能为空");
        }

        Path base = kbDir(kb);
        Path target = safeResolve(base, dirName);

        if (!Files.isDirectory(target)) {
            return Map.of("ok", false, "error", "目录不存在");
        }

        try {
            deleteRecursively(target);

            // 更新 dirSettings
            String dirSettings = kb.getDirSettings();
            if (dirSettings != null && !dirSettings.isBlank()) {
                Map<String, Object> settings = parseDirSettings(dirSettings);
                @SuppressWarnings("unchecked")
                List<String> sort = (List<String>) settings.getOrDefault("sort", new ArrayList<>());
                @SuppressWarnings("unchecked")
                List<String> hidden = (List<String>) settings.getOrDefault("hidden", new ArrayList<>());

                sort.remove(dirName);
                hidden.remove(dirName);

                settings.put("sort", sort);
                settings.put("hidden", hidden);
                kb.setDirSettings(mapper.writeValueAsString(settings));
                kbService.save(Map.of("id", id, "dirSettings", kb.getDirSettings()));
            }

            logService.add("目录管理", "删除", dirName);
            return Map.of("ok", true);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "删除失败: " + e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
    }

    // ========== 笔记 API ==========

    @ResponseBody
    @PostMapping("/kb/{id}/notes/new")
    public Map<String, Object> createFile(@PathVariable Long id,
                                          @RequestParam(required = false, defaultValue = "") String dir,
                                          @RequestParam String filename,
                                          @RequestParam(required = false, defaultValue = "") String content) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path targetDir = safeResolve(kbDir(kb), dir);
        if (!Files.isDirectory(targetDir)) return Map.of("ok", false, "error", "目录不存在");

        if (!filename.endsWith(".md")) filename += ".md";
        Path file = targetDir.resolve(filename);
        if (Files.exists(file)) return Map.of("ok", false, "error", "文件已存在");

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
            logService.add("新建笔记", "成功", (dir.isEmpty() ? "" : dir + "/") + filename);
            return Map.of("ok", true, "path", (dir.isEmpty() ? "" : dir + "/") + filename);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "创建失败: " + e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/notes/delete")
    public Map<String, Object> deleteFile(@PathVariable Long id, @RequestParam String path) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path target = safeResolve(kbDir(kb), path);
        if (!Files.isRegularFile(target) || !target.toString().endsWith(".md"))
            return Map.of("ok", false, "error", "只能删除 .md 文件");
        try {
            Files.delete(target);
            logService.add("删除笔记", "成功", path);
            return Map.of("ok", true);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "删除失败: " + e.getMessage());
        }
    }

    @ResponseBody
    @GetMapping("/kb/{id}/notes/raw")
    public Map<String, Object> readRawFile(@PathVariable Long id, @RequestParam String path) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path target = safeResolve(kbDir(kb), path);
        if (!Files.isRegularFile(target))
            return Map.of("ok", false, "error", "文件不存在");
        try {
            String content = Files.readString(target, java.nio.charset.StandardCharsets.UTF_8);
            return Map.of("ok", true, "content", content);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "读取失败: " + e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/notes/save")
    public Map<String, Object> saveFile(@PathVariable Long id, @RequestBody Map<String, String> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        String path = body.getOrDefault("path", "").trim();
        String content = body.getOrDefault("content", "");
        if (path.isEmpty()) return Map.of("ok", false, "error", "文件路径不能为空");

        Path target = safeResolve(kbDir(kb), path);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, java.nio.charset.StandardCharsets.UTF_8);
            logService.add("保存笔记", "成功", path);
            return Map.of("ok", true);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "保存失败: " + e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/notes/rename")
    public Map<String, Object> renameFile(@PathVariable Long id, @RequestBody Map<String, String> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        String oldPath = body.getOrDefault("oldPath", "").trim();
        String newPath = body.getOrDefault("newPath", "").trim();
        if (oldPath.isEmpty() || newPath.isEmpty()) {
            return Map.of("ok", false, "error", "路径不能为空");
        }

        Path base = kbDir(kb);
        Path old = safeResolve(base, oldPath);
        Path nw = safeResolve(base, newPath);

        if (!Files.exists(old)) {
            return Map.of("ok", false, "error", "原文件不存在");
        }
        if (Files.exists(nw)) {
            return Map.of("ok", false, "error", "目标文件已存在");
        }

        try {
            Files.createDirectories(nw.getParent());
            Files.move(old, nw);
            logService.add("重命名", "成功", oldPath + " → " + newPath);
            return Map.of("ok", true);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "重命名失败: " + e.getMessage());
        }
    }

    // ========== JSON 数据管理 API ==========

    @ResponseBody
    @GetMapping("/kb/{id}/api/data/list")
    public Map<String, Object> listJsonData(@PathVariable Long id, @RequestParam String dir) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path dataDir = safeResolve(kbDir(kb), dir).resolve("data");
        return Map.of("ok", true, "files", directoryDataService.listJsonFiles(dataDir));
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/data/read")
    public Map<String, Object> readJsonData(@PathVariable Long id, @RequestParam String dir,
                                            @RequestParam String file) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path dataDir = safeResolve(kbDir(kb), dir).resolve("data");
        return Map.of("ok", true, "data", directoryDataService.getFileData(dataDir, file));
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/data/add")
    public Map<String, Object> addRecord(@PathVariable Long id, @RequestParam String dir,
                                         @RequestParam String file, @RequestParam String group,
                                         @RequestBody Map<String, Object> record) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path dataDir = safeResolve(kbDir(kb), dir).resolve("data");
        return directoryDataService.addRecord(dataDir, file, group, record, null);
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/data/update")
    public Map<String, Object> updateRecord(@PathVariable Long id, @RequestParam String dir,
                                            @RequestParam String file, @RequestParam String group,
                                            @RequestParam String idField, @RequestParam String idValue,
                                            @RequestBody Map<String, Object> updates) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path dataDir = safeResolve(kbDir(kb), dir).resolve("data");
        return directoryDataService.updateRecord(dataDir, file, group, idField, idValue, updates);
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/data/delete")
    public Map<String, Object> deleteRecord(@PathVariable Long id, @RequestParam String dir,
                                            @RequestParam String file, @RequestParam String group,
                                            @RequestParam String idField, @RequestParam String idValue) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path dataDir = safeResolve(kbDir(kb), dir).resolve("data");
        return directoryDataService.deleteRecord(dataDir, file, group, idField, idValue);
    }

    @GetMapping("/kb/{id}/config")
    public String config(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        return "kb_ai_guide";
    }

    // ========== KB 范围的任务 API ==========

    @ResponseBody
    @PostMapping("/kb/{id}/api/tasks/add")
    public Map<String, Object> kbAddTask(@PathVariable Long id, @RequestParam String title,
                                          @RequestParam(required = false, defaultValue = "") String description,
                                          @RequestParam(required = false, defaultValue = "mid") String priority,
                                          @RequestParam(required = false, defaultValue = "") String dueDate) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            TaskItem task = taskService.addTask(kb.getNotesDir(), title, description, priority, dueDate);
            logService.add("任务看板", "成功", "添加任务: " + title);
            return Map.of("ok", true, "task", task);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/tasks/update")
    public Map<String, Object> kbUpdateTask(@PathVariable Long id, @RequestParam String taskId,
                                              @RequestParam(required = false) String title,
                                              @RequestParam(required = false) String description,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String priority,
                                              @RequestParam(required = false) String dueDate) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            TaskItem task = taskService.updateTask(kb.getNotesDir(), taskId, title, description, status, priority, dueDate);
            if (task == null) return Map.of("ok", false, "error", "任务不存在");
            logService.add("任务看板", "成功", "更新任务: " + task.title);
            return Map.of("ok", true, "task", task);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/tasks/delete")
    public Map<String, Object> kbDeleteTask(@PathVariable Long id, @RequestParam String taskId) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            boolean ok = taskService.deleteTask(kb.getNotesDir(), taskId);
            if (ok) logService.add("任务看板", "成功", "删除任务: " + taskId);
            return Map.of("ok", ok);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ========== KB 范围的提醒 API ==========

    @ResponseBody
    @PostMapping("/kb/{id}/api/reminders/add")
    public Map<String, Object> kbAddReminder(@PathVariable Long id,
                                              @RequestParam String name,
                                              @RequestParam(required = false, defaultValue = "") String message,
                                              @RequestParam String type,
                                              @RequestParam(required = false, defaultValue = "09:00") String time,
                                              @RequestParam(required = false, defaultValue = "") String date,
                                              @RequestParam(required = false, defaultValue = "") String dayOfWeek,
                                              @RequestParam(required = false, defaultValue = "") String dayOfMonth,
                                              @RequestParam(required = false, defaultValue = "") String monthDay) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            Reminder r = reminderService.addReminder(kb.getNotesDir(), name, message, type, time, date, dayOfWeek, dayOfMonth, monthDay);
            logService.add("提醒管理", "成功", "添加提醒: " + name);
            return Map.of("ok", true, "reminder", r);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/reminders/update")
    public Map<String, Object> kbUpdateReminder(@PathVariable Long id,
                                                 @RequestParam String reminderId,
                                                 @RequestParam(required = false) String name,
                                                 @RequestParam(required = false) String message,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String time,
                                                 @RequestParam(required = false) String date,
                                                 @RequestParam(required = false) String dayOfWeek,
                                                 @RequestParam(required = false) String dayOfMonth,
                                                 @RequestParam(required = false) String monthDay,
                                                 @RequestParam(required = false) Boolean enabled) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            boolean ok = reminderService.updateReminder(kb.getNotesDir(), reminderId, name, message, type, time, date, dayOfWeek, dayOfMonth, monthDay, enabled);
            if (!ok) return Map.of("ok", false, "error", "提醒不存在");
            logService.add("提醒管理", "成功", "更新提醒: " + name);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/reminders/delete")
    public Map<String, Object> kbDeleteReminder(@PathVariable Long id, @RequestParam String reminderId) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            boolean ok = reminderService.deleteReminder(kb.getNotesDir(), reminderId);
            if (ok) logService.add("提醒管理", "成功", "删除提醒");
            return Map.of("ok", ok);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/reminders/toggle")
    public Map<String, Object> kbToggleReminder(@PathVariable Long id, @RequestParam String reminderId) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            boolean ok = reminderService.toggleReminder(kb.getNotesDir(), reminderId);
            return Map.of("ok", ok);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/reminders/trigger")
    public Map<String, Object> kbTriggerReminder(@PathVariable Long id, @RequestParam String reminderId) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            Reminder r = reminderService.getAllReminders(kb.getNotesDir()).stream()
                    .filter(x -> x.id.equals(reminderId)).findFirst().orElse(null);
            if (r == null) return Map.of("ok", false, "error", "提醒不存在");
            reminderService.triggerReminder(kb.getNotesDir(), r);
            logService.add("提醒管理", "成功", "手动触发: " + r.name);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ========== KB 范围的 AI 指南 API ==========

    @ResponseBody
    @GetMapping("/kb/{id}/api/ai-guide/agents")
    public Map<String, Object> kbGetAgents(@PathVariable Long id) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path file = Paths.get(kb.getNotesDir(), "AGENTS.md");
        if (!Files.exists(file)) return Map.of("ok", true, "content", "", "path", file.toString());
        return Map.of("ok", true, "content", FileUtil.readText(file), "path", file.toString());
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/ai-guide/agents")
    public Map<String, Object> kbSaveAgents(@PathVariable Long id, @RequestBody Map<String, String> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path file = Paths.get(kb.getNotesDir(), "AGENTS.md");
        try {
            FileUtil.writeText(file, body.getOrDefault("content", ""));
            logService.add("AI指南", "保存AGENTS.md", file.toString());
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/ai-guide/memory")
    public Map<String, Object> kbListMemory(@PathVariable Long id) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path dir = Paths.get(kb.getNotesDir(), "AI", "记忆");
        if (!Files.exists(dir)) return Map.of("ok", true, "files", List.of(), "path", dir.toString());
        try (var files = Files.list(dir)) {
            List<Map<String, String>> fileList = files
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(p -> Map.<String, String>of("name", p.getFileName().toString(), "path", p.toString()))
                    .collect(Collectors.toList());
            return Map.of("ok", true, "files", fileList, "path", dir.toString());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/ai-guide/memory/{name}")
    public Map<String, Object> kbGetMemory(@PathVariable Long id, @PathVariable String name) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path dir = Paths.get(kb.getNotesDir(), "AI", "记忆");
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir)) return Map.of("ok", false, "error", "非法文件名");
        if (!Files.exists(file)) return Map.of("ok", true, "content", "");
        return Map.of("ok", true, "content", FileUtil.readText(file));
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/ai-guide/memory")
    public Map<String, Object> kbSaveMemory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        String name = body.getOrDefault("name", "");
        String content = body.getOrDefault("content", "");
        if (name.isEmpty()) return Map.of("ok", false, "error", "文件名不能为空");
        if (!name.endsWith(".md")) name += ".md";
        Path dir = Paths.get(kb.getNotesDir(), "AI", "记忆");
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name).normalize();
            if (!file.startsWith(dir)) return Map.of("ok", false, "error", "非法文件名");
            FileUtil.writeText(file, content);
            logService.add("AI指南", "保存记忆", name);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @ResponseBody
    @DeleteMapping("/kb/{id}/api/ai-guide/memory/{name}")
    public Map<String, Object> kbDeleteMemory(@PathVariable Long id, @PathVariable String name) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path dir = Paths.get(kb.getNotesDir(), "AI", "记忆");
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir)) return Map.of("ok", false, "error", "非法文件名");
        try {
            Files.deleteIfExists(file);
            logService.add("AI指南", "删除记忆", name);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ========== 日报 API ==========

    @PostMapping("/kb/{id}/api/generate")
    @ResponseBody
    public Map<String, Object> generate(@PathVariable Long id) {
        try {
            KnowledgeBaseEntity kb = kbService.getById(id);
            if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
            var r = reportService.generate(kb.getId());
            if (r.report != null) {
                reportService.saveComprehensiveReport(r.report, kb.getId());
                logService.add("手动生成日报", "成功", "知识库: " + kb.getName());
                return Map.of("ok", true);
            } else {
                logService.add("手动生成日报", "失败", r.error);
                return Map.of("ok", false, "error", r.error != null ? r.error : "AI 分析不可用");
            }
        } catch (Exception e) {
            logService.add("手动生成日报", "失败", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @GetMapping("/kb/{id}/api/report/prompt")
    @ResponseBody
    public Map<String, Object> getPrompt(@PathVariable Long id) {
        return Map.of("ok", true, "prompt", reportService.readPrompt(id));
    }

    @PostMapping("/kb/{id}/api/report/prompt")
    @ResponseBody
    public Map<String, Object> savePrompt(@PathVariable Long id,
                                          @RequestBody Map<String, String> body) {
        reportService.writePrompt(body.getOrDefault("prompt", ""), id);
        logService.add("综合日报", "保存提示词", "成功");
        return Map.of("ok", true);
    }

    // ========== KB API ==========

    @ResponseBody
    @GetMapping("/api/kb/list")
    public Map<String, Object> list() {
        List<KnowledgeBaseEntity> list = kbService.getAll();
        List<Map<String, Object>> result = list.stream().map(this::toMap).collect(Collectors.toList());
        return Map.of("ok", true, "list", result);
    }

    @ResponseBody
    @GetMapping("/api/kb/current")
    public Map<String, Object> current() {
        KnowledgeBaseEntity kb = kbService.getFirst();
        if (kb == null) {
            return Map.of("ok", false, "error", "未配置任何知识库");
        }
        return Map.of("ok", true, "kb", toMap(kb));
    }

    @ResponseBody
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

    @ResponseBody
    @DeleteMapping("/api/kb/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        kbService.delete(id);
        logService.add("知识库", "删除", "知识库已删除: id=" + id);
        return Map.of("ok", true);
    }

    @ResponseBody
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
        m.put("sortOrder", e.getSortOrder());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    private Map<String, String> parseLabels(String labelsJson) {
        if (labelsJson == null || labelsJson.isBlank()) return defaultLabels();
        try {
            Map<String, String> parsed = FileUtil.readJson(labelsJson, LABELS_TYPE, null);
            if (parsed == null || parsed.isEmpty()) return defaultLabels();
            Map<String, String> result = defaultLabels();
            result.putAll(parsed);
            return result;
        } catch (Exception e) {
            return defaultLabels();
        }
    }

    private Map<String, String> defaultLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put("tasks", "任务");
        labels.put("reminders", "提醒");
        labels.put("notes", "笔记");
        labels.put("config", "配置");
        return labels;
    }

    // ========== 辅助方法 ==========

    private static final Set<String> DEFAULT_IGNORED = Set.of(".git", ".obsidian", "__pycache__",
            ".DS_Store", ".claude", ".playwright-mcp", ".sisyphus", "AI");

    private List<String> scanTopDirs(Path base) {
        try (var stream = Files.list(base)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !DEFAULT_IGNORED.contains(p.getFileName().toString()))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> listTopDirs(Path base, String dirSettings) {
        return listAllDirInfos(base, dirSettings).stream()
                .filter(m -> !Boolean.TRUE.equals(m.get("hidden")))
                .map(m -> (String) m.get("name"))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> listAllDirInfos(Path base, String dirSettings) {
        List<String> allDirs = scanTopDirs(base);
        if (allDirs.isEmpty()) return List.of();

        // 解析用户设置
        Map<String, Object> settings = parseDirSettings(dirSettings);
        @SuppressWarnings("unchecked")
        List<String> hidden = (List<String>) settings.getOrDefault("hidden", List.of());
        @SuppressWarnings("unchecked")
        List<String> sort = (List<String>) settings.getOrDefault("sort", List.of());

        // 构建完整目录信息
        Map<String, String> orderMap = new HashMap<>();
        for (int i = 0; i < sort.size(); i++) orderMap.put(sort.get(i), String.valueOf(i));

        return allDirs.stream().map(name -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("hidden", hidden.contains(name));
            m.put("sortOrder", orderMap.getOrDefault(name, "999"));
            return m;
        })
        .sorted(Comparator.comparing(m -> (String) m.get("sortOrder")))
        .collect(Collectors.toList());
    }

    private List<Map<String, Object>> listDirContents(Path dir) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !IGNORED_DIRS.contains(p.getFileName().toString()))
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", p.getFileName().toString());
                        try {
                            entry.put("modified", java.time.LocalDateTime
                                    .ofInstant(Files.getLastModifiedTime(p).toInstant(),
                                            ZoneId.of("Asia/Shanghai"))
                                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")));
                        } catch (IOException e) {
                            entry.put("modified", "");
                        }
                        entry.put("is_dir", Files.isDirectory(p));
                        result.add(entry);
                    });
        } catch (Exception e) {
            log.error("遍历目录失败: {}", dir, e);
        }
        return result;
    }

    private List<String> buildBreadcrumbs(String dir) {
        if (dir == null || dir.isEmpty()) return List.of();
        return Arrays.asList(dir.split("/"));
    }

    private List<Map<String, String>> buildBreadcrumbLinks(String dir) {
        if (dir == null || dir.isEmpty()) return List.of();
        String[] parts = dir.split("/");
        List<Map<String, String>> links = new ArrayList<>();
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (path.length() > 0) path.append("/");
            path.append(parts[i]);
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", parts[i]);
            item.put("path", path.toString());
            item.put("isLast", String.valueOf(i == parts.length - 1));
            links.add(item);
        }
        return links;
    }

    private String parentDir(String dir) {
        if (dir == null || dir.isEmpty()) return "";
        int lastSlash = dir.lastIndexOf('/');
        return lastSlash > 0 ? dir.substring(0, lastSlash) : "";
    }

    private String readLatestReport(Path analysisDir) {
        if (!Files.exists(analysisDir)) return null;

        // 优先读取当天
        String date = TimeUtil.todayStr();
        Path todayFile = analysisDir.resolve(date + ".md");
        if (FileUtil.exists(todayFile)) {
            return MarkdownUtil.toHtml(FileUtil.readText(todayFile));
        }

        // 否则读取最新的
        try (var stream = Files.list(analysisDir)) {
            Path latest = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.reverseOrder())
                    .findFirst()
                    .orElse(null);
            if (latest != null) {
                return MarkdownUtil.toHtml(FileUtil.readText(latest));
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDirSettings(String dirSettings) {
        if (dirSettings == null || dirSettings.isBlank()) return Map.of();
        try {
            return FileUtil.readJson(dirSettings, new TypeReference<>() {}, Map.of());
        } catch (Exception e) {
            return Map.of();
        }
    }
}
