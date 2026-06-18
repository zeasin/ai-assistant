package com.laoqi.assistant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.model.ModuleDefinition;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ModuleService;
import com.laoqi.assistant.service.ReportService;
import com.laoqi.assistant.service.TaskService;
import com.laoqi.assistant.service.ReminderService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.MarkdownUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

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

    private final KnowledgeBaseService kbService;
    private final ModuleService moduleService;
    private final LogService logService;
    private final TaskService taskService;
    private final ReminderService reminderService;
    private final ReportService reportService;
    private final ConfigService configService;

    public KnowledgeBaseController(KnowledgeBaseService kbService, ModuleService moduleService,
                                   LogService logService, TaskService taskService,
                                   ReminderService reminderService, ReportService reportService,
                                   ConfigService configService) {
        this.kbService = kbService;
        this.moduleService = moduleService;
        this.logService = logService;
        this.taskService = taskService;
        this.reminderService = reminderService;
        this.reportService = reportService;
        this.configService = configService;
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
        model.put("modules", moduleService.getModulesByKb(id));

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

    @GetMapping("/kb/{id}/tasks")
    public String tasks(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        return "kb_tasks";
    }

    @GetMapping("/kb/{id}/reminders")
    public String reminders(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        return "kb_reminders";
    }

    @GetMapping("/kb/{id}/modules")
    public String modulesPage(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        model.put("kbModules", moduleService.getModulesByKb(id));
        return "kb_modules";
    }

    @GetMapping("/kb/{id}/notes")
    public String notes(@PathVariable Long id, @RequestParam(required = false, defaultValue = "") String dir,
                        Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));

        Path base = kbDir(kb);
        Path target = safeResolve(base, dir);
        if (!Files.isDirectory(target)) {
            model.put("error", "目录不存在");
            model.put("dirs", List.of());
            model.put("files", List.of());
            model.put("rel", "");
            model.put("parent", "");
            model.put("breadcrumbs", List.of());
            model.put("breadcrumbPaths", List.of());
            return "kb_browse";
        }

        List<Map<String, Object>> dirs = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();

        try (var stream = Files.list(target)) {
            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !IGNORED_DIRS.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
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
                        if (Files.isDirectory(p)) dirs.add(entry);
                        else files.add(entry);
                    });
        } catch (DirectoryIteratorException e) {
            log.error("遍历目录失败: target={}", target, e);
            model.put("error", "读取目录失败: " + e.getCause().getMessage());
        } catch (IOException e) {
            log.error("读取目录失败: target={}", target, e);
            model.put("error", "读取目录失败: " + e.getMessage());
        }

        String rel = dir != null && !dir.isEmpty() ? dir : "";
        String parent = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
        String[] parts = rel.isEmpty() ? new String[0] : rel.split("/");
        List<String> breadcrumbs = Arrays.asList(parts);
        List<String> breadcrumbPaths = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            breadcrumbPaths.add(String.join("/", Arrays.copyOf(parts, i + 1)));
        }

        model.put("dirs", dirs);
        model.put("files", files);
        model.put("rel", rel);
        model.put("parent", parent);
        model.put("breadcrumbs", breadcrumbs);
        model.put("breadcrumbPaths", breadcrumbPaths);
        return "kb_browse";
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
        return "view";
    }

    @GetMapping("/kb/{id}/config")
    public String config(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";
        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        return "kb_ai_guide";
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
        labels.put("modules", "模块");
        labels.put("notes", "笔记");
        labels.put("config", "配置");
        return labels;
    }
}
