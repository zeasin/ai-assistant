package com.laoqi.assistant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.AiAnalysisEntity;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.model.ReminderData.Reminder;
import com.laoqi.assistant.model.TaskData.TaskItem;
import com.laoqi.assistant.service.AgentAnalysisService;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.DirectoryDataService;
import com.laoqi.assistant.service.NoteIndexService;
import com.laoqi.assistant.service.NoteIndexService.IndexStats;
import com.laoqi.assistant.service.db.MessageDbService;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ReportService;
import com.laoqi.assistant.service.TaskService;
import com.laoqi.assistant.service.ReminderService;
import com.laoqi.assistant.service.db.AiAnalysisDbService;
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
import java.util.stream.Stream;

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
    private final com.laoqi.assistant.service.LlmService llmService;
    private final AiAnalysisDbService aiAnalysisDbService;
    private final NoteIndexService noteIndexService;
    private final MessageDbService messageDbService;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   LogService logService, TaskService taskService,
                                   ReminderService reminderService, ReportService reportService,
                                   ConfigService configService,
                                   AgentAnalysisService agentAnalysisService,
                                   DirectoryDataService directoryDataService,
                                   com.laoqi.assistant.service.LlmService llmService,
                                   AiAnalysisDbService aiAnalysisDbService,
                                   NoteIndexService noteIndexService,
                                   MessageDbService messageDbService) {
        this.kbService = kbService;
        this.logService = logService;
        this.taskService = taskService;
        this.reminderService = reminderService;
        this.reportService = reportService;
        this.configService = configService;
        this.agentAnalysisService = agentAnalysisService;
        this.directoryDataService = directoryDataService;
        this.llmService = llmService;
        this.aiAnalysisDbService = aiAnalysisDbService;
        this.noteIndexService = noteIndexService;
        this.messageDbService = messageDbService;
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
    public String overview(@PathVariable Long id) {
        return "redirect:/kb/" + id + "/ai";
    }

    @GetMapping("/kb/{id}/ai")
    public String aiHub(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));

        // 笔记库统计
        try {
            var stats = noteIndexService.getIndexStats(kb.getId());
            model.put("kbFileCount", stats.fileCount());
            model.put("kbIndexCount", stats.chunkCount());
        } catch (Exception e) {
            model.put("kbFileCount", 0);
            model.put("kbIndexCount", 0);
        }
        try {
            model.put("kbTotalMessages", messageDbService.countByKb(id.intValue()));
        } catch (Exception e) {
            model.put("kbTotalMessages", 0);
        }

        return "2.0/kb_ai_guide";
    }

    // 任务/提醒页面已迁移到 /planner

    @GetMapping("/kb/{id}/index")
    public String kbIndex(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("kbId", id);
        model.put("labels", parseLabels(kb.getLabels()));

        return "1.0/kb_index";
    }

    @GetMapping("/kb/{id}/search")
    public String kbSearch(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));

        return "2.0/kb_search";
    }

    @GetMapping("/kb/{id}/data")
    public String kbData(@PathVariable Long id, Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        model.put("kbId", id);

        return "1.0/kb_data_overview";
    }

    @GetMapping("/kb/{id}/data/detail")
    public String kbDataDetail(@PathVariable Long id,
                                @RequestParam(defaultValue = "") String dir,
                                @RequestParam(defaultValue = "") String file,
                                Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        model.put("kbId", id);
        model.put("dir", dir);
        model.put("file", file);

        return "1.0/kb_data_detail";
    }

    // ========== 笔记库选择页面 ==========
    @GetMapping("/notes")
    public String notesIndex(Map<String, Object> model) {
        var kbList = kbService.getAll();
        model.put("kbList", kbList);
        return "2.0/notes_select";
    }

    // ========== 笔记浏览页面（树结构） ==========
    @GetMapping("/kb/{id}/notes")
    public String notesTree(@PathVariable Long id,
                            @RequestParam(required = false, defaultValue = "") String dir,
                            Map<String, Object> model) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return "redirect:/config";

        model.put("kb", kb);
        model.put("labels", parseLabels(kb.getLabels()));
        model.put("rel", dir);
        return "2.0/notes";
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
        return "1.0/view";
    }

    // ========== 目录分析 API ==========

    // 目录分析内存缓存: key = "kbId:dirPath", value = 分析结果
    private final Map<String, String> dirAnalysisCache = new java.util.concurrent.ConcurrentHashMap<>();

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

                // 缓存到内存（不落库）
                dirAnalysisCache.put(id + ":" + dir, result);

                // 仅保存提示词（用户自定义的提示词需要持久化）
                if (!prompt.isBlank()) {
                    aiAnalysisDbService.saveDirAnalysisPrompt(id, prompt);
                }

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
    @GetMapping("/kb/{id}/api/notes/list")
    public Map<String, Object> listNotes(@PathVariable Long id) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        Path base = kbDir(kb);
        if (kb.getNotesDir() == null || kb.getNotesDir().isBlank()) {
            return Map.of("ok", true, "files", List.of());
        }

        List<Map<String, String>> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(base, 3)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .filter(p -> !p.toString().contains("/AI/") && !p.toString().contains("\\AI\\"))
                  .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("\\.git\\"))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .limit(50)
                  .forEach((Path p) -> {
                      Map<String, String> file = new LinkedHashMap<>();
                      file.put("name", p.getFileName().toString());
                      file.put("path", base.relativize(p).toString().replace("\\", "/"));
                      try {
                          var lastModified = Files.getLastModifiedTime(p);
                          file.put("modified", lastModified.toInstant()
                              .atZone(ZoneId.systemDefault())
                              .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                      } catch (IOException e) {
                          file.put("modified", "");
                      }
                      files.add(file);
                  });
        } catch (IOException e) {
            return Map.of("ok", false, "error", "扫描文件失败");
        }
        return Map.of("ok", true, "files", files);
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/notes/tree")
    public Map<String, Object> getNotesTree(@PathVariable Long id) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        Path base = kbDir(kb);
        if (kb.getNotesDir() == null || kb.getNotesDir().isBlank()) {
            return Map.of("ok", true, "tree", Map.of());
        }

        Map<String, Object> tree = buildFileTree(base, base);
        return Map.of("ok", true, "tree", tree);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFileTree(Path root, Path current) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> dirs = new ArrayList<>();
        List<Map<String, String>> files = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entries::add);
            
            // 排序：目录在前，文件在后，各自按名称排序
            entries.sort((a, b) -> {
                boolean aIsDir = Files.isDirectory(a);
                boolean bIsDir = Files.isDirectory(b);
                if (aIsDir != bIsDir) return aIsDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            });

            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                
                // 跳过隐藏文件和特殊目录
                if (name.startsWith(".") || name.equals("__pycache__")) continue;
                
                String relativePath = root.relativize(entry).toString().replace("\\", "/");

                if (Files.isDirectory(entry)) {
                    // 跳过AI、.git等目录
                    if (name.equals("AI") || name.equals(".git") || name.equals(".obsidian")) continue;
                    
                    Map<String, Object> dir = new LinkedHashMap<>();
                    dir.put("name", name);
                    dir.put("path", relativePath);
                    dir.put("children", buildFileTree(root, entry));
                    dirs.add(dir);
                } else if (name.endsWith(".md")) {
                    Map<String, String> file = new LinkedHashMap<>();
                    file.put("name", name);
                    file.put("path", relativePath);
                    files.add(file);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list directory: {}", current);
        }

        result.put("dirs", dirs);
        result.put("files", files);
        return result;
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/notes/read")
    public Map<String, Object> readNote(@PathVariable Long id, @RequestParam String path) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        Path base = kbDir(kb);
        Path file = safeResolve(base, path);
        
        if (!Files.isRegularFile(file)) {
            return Map.of("ok", false, "error", "文件不存在");
        }

        try {
            String content = FileUtil.readText(file);
            content = MarkdownUtil.stripFrontmatter(content);
            return Map.of("ok", true, "content", content);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "读取失败");
        }
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/notes/stats")
    public Map<String, Object> getNotesStats(@PathVariable Long id) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        Path base = kbDir(kb);
        if (kb.getNotesDir() == null || kb.getNotesDir().isBlank()) {
            return Map.of("ok", true, "totalChars", 0);
        }

        long totalChars = 0;
        try (Stream<Path> stream = Files.walk(base, 5)) {
            totalChars = stream
                .filter(p -> p.toString().endsWith(".md"))
                .filter(p -> !p.toString().contains("/AI/") && !p.toString().contains("\\AI\\"))
                .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("\\.git\\"))
                .mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
        } catch (IOException e) {
            return Map.of("ok", false, "error", "统计失败");
        }
        return Map.of("ok", true, "totalChars", totalChars);
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/notes/ai-report")
    public Map<String, Object> generateAiReport(@PathVariable Long id, @RequestBody Map<String, String> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

        try {
            Path base = kbDir(kb);
            StringBuilder summary = new StringBuilder();
            summary.append("笔记库名称：").append(kb.getName()).append("\n\n");
            summary.append("笔记库路径：").append(kb.getNotesDir()).append("\n\n");
            
            // 收集文件列表和部分内容
            List<String> fileList = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(base, 3)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                      .filter(p -> !p.toString().contains("/AI/") && !p.toString().contains("\\AI\\"))
                      .filter(p -> !p.toString().contains("/.git/"))
                      .limit(20)
                      .forEach(p -> {
                          String relPath = base.relativize(p).toString().replace("\\", "/");
                          fileList.add(relPath);
                          try {
                              String content = FileUtil.readText(p);
                              if (content.length() > 500) content = content.substring(0, 500) + "...";
                              summary.append("文件：").append(relPath).append("\n");
                              summary.append("内容摘要：").append(content).append("\n\n");
                          } catch (Exception e) {}
                      });
            }
            
            if (fileList.isEmpty()) {
                return Map.of("ok", true, "report", "笔记库为空，暂无内容可分析。");
            }
            
            String prompt = body.getOrDefault("prompt", "请分析这个笔记库的内容结构，给出总结和建议。");
            String systemPrompt = "你是一个笔记分析助手。请根据提供的笔记库内容，给出简要的分析报告。包括：内容概览、主要主题、结构建议。用中文回复，使用HTML格式。";
            String userMessage = prompt + "\n\n笔记库包含 " + fileList.size() + " 个文件：\n" + String.join("\n", fileList) + "\n\n" + summary.toString();
            
            String report = llmService.chat(systemPrompt, userMessage);
            return Map.of("ok", true, "report", report != null ? report : "暂无分析结果");
        } catch (Exception e) {
            return Map.of("ok", false, "error", "生成报告失败: " + e.getMessage());
        }
    }

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
    public Map<String, Object> listJsonData(@PathVariable Long id, @RequestParam(defaultValue = "") String dir) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        
        Path baseDir = kbDir(kb);
        Path targetDir = dir.isEmpty() ? baseDir : safeResolve(baseDir, dir);
        
        List<Map<String, Object>> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(targetDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .filter(p -> {
                    // 排除 . 开头的文件和目录
                    Path relative = baseDir.relativize(p);
                    for (Path pathSegment : relative) {
                        if (pathSegment.toString().startsWith(".")) return false;
                    }
                    return true;
                })
                .sorted()
                .forEach(p -> {
                    try {
                        Map<String, Object> file = new LinkedHashMap<>();
                        file.put("name", p.getFileName().toString());
                        file.put("path", baseDir.relativize(p).toString().replace("\\", "/"));
                        file.put("size", Files.size(p));
                        file.put("lastModified", Files.getLastModifiedTime(p).toMillis());
                        files.add(file);
                    } catch (Exception ignored) {}
                });
        } catch (Exception e) {
            log.error("扫描JSON文件失败: {}", targetDir, e);
        }
        
        return Map.of("ok", true, "files", files);
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/data/read")
    public Map<String, Object> readJsonData(@PathVariable Long id, @RequestParam String dir,
                                            @RequestParam String file) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        
        // 自动补全 .json 后缀
        if (!file.endsWith(".json")) {
            file = file + ".json";
        }
        
        Path baseDir = kbDir(kb);
        Path filePath = dir.isEmpty() ? baseDir.resolve(file) : baseDir.resolve(dir).resolve(file);
        
        // 尝试直接读取
        if (Files.isRegularFile(filePath)) {
            try {
                String content = new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.UTF_8);
                // 去除 BOM (Byte Order Mark) 字符 ﻿，避免 Jackson 解析报错
                if (!content.isEmpty() && content.charAt(0) == '﻿') {
                    content = content.substring(1);
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Object data = mapper.readValue(content, Object.class);
                return Map.of("ok", true, "data", data);
            } catch (Exception e) {
                return Map.of("ok", false, "error", "读取文件失败: " + e.getMessage());
            }
        }

        // 尝试在 data 子目录查找
        Path dataDir = dir.isEmpty() ? baseDir.resolve("data") : baseDir.resolve(dir).resolve("data");
        Path dataFilePath = dataDir.resolve(file);
        if (Files.isRegularFile(dataFilePath)) {
            try {
                String content = new String(Files.readAllBytes(dataFilePath), java.nio.charset.StandardCharsets.UTF_8);
                // 去除 BOM (Byte Order Mark) 字符 ﻿，避免 Jackson 解析报错
                if (!content.isEmpty() && content.charAt(0) == '﻿') {
                    content = content.substring(1);
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Object data = mapper.readValue(content, Object.class);
                return Map.of("ok", true, "data", data);
            } catch (Exception e) {
                return Map.of("ok", false, "error", "读取文件失败: " + e.getMessage());
            }
        }
        
        return Map.of("ok", false, "error", "文件不存在: " + file);
    }

    @ResponseBody
    @GetMapping("/kb/{id}/api/data/group")
    public Map<String, Object> readGroupData(@PathVariable Long id, @RequestParam String dir,
                                             @RequestParam String file, @RequestParam String group) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Map<String, Object> result = directoryDataService.getGroupData(kbDir(kb), dir, file, group);
        if (result == null) return Map.of("ok", false, "error", "分组不存在");
        return Map.of("ok", true, "count", result.getOrDefault("count", 0), "data", result.getOrDefault("data", List.of()));
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/data/add")
    public Map<String, Object> addRecord(@PathVariable Long id, @RequestParam String dir,
                                         @RequestParam String file, @RequestParam String group,
                                         @RequestBody Map<String, Object> record) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        return directoryDataService.addRecord(kbDir(kb), dir, file, group, record, null);
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/data/update")
    public Map<String, Object> updateRecord(@PathVariable Long id, @RequestParam String dir,
                                            @RequestParam String file, @RequestParam String group,
                                            @RequestParam String idField, @RequestParam String idValue,
                                            @RequestBody Map<String, Object> updates) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        return directoryDataService.updateRecord(kbDir(kb), dir, file, group, idField, idValue, updates);
    }

    @ResponseBody
    @PostMapping("/kb/{id}/api/data/delete")
    public Map<String, Object> deleteRecord(@PathVariable Long id, @RequestParam String dir,
                                            @RequestParam String file, @RequestParam String group,
                                            @RequestParam String idField, @RequestParam String idValue) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        return directoryDataService.deleteRecord(kbDir(kb), dir, file, group, idField, idValue);
    }

    @GetMapping("/kb/{id}/config")
    public String config(@PathVariable Long id) {
        return "redirect:/kb/" + id + "/ai";
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

    @GetMapping("/kb/{id}/api/report/config")
    @ResponseBody
    public Map<String, Object> getReportConfig(@PathVariable Long id) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        boolean autoReport = kb.getAutoReport() == null || kb.getAutoReport() == 1;
        boolean feishuPush = kb.getFeishuPush() == null || kb.getFeishuPush() == 1;
        return Map.of(
            "ok", true,
            "autoReport", autoReport,
            "feishuPush", feishuPush
        );
    }

    @PostMapping("/kb/{id}/api/report/config")
    @ResponseBody
    public Map<String, Object> saveReportConfig(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        KnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        try {
            Map<String, Object> update = new HashMap<>();
            update.put("id", id);
            if (body.containsKey("autoReport")) {
                update.put("autoReport", Boolean.TRUE.equals(body.get("autoReport")));
            }
            if (body.containsKey("feishuPush")) {
                update.put("feishuPush", Boolean.TRUE.equals(body.get("feishuPush")));
            }
            kbService.save(update);
            logService.add("日报配置", "更新",
                    (body.containsKey("autoReport") ? "自动生成:" + update.get("autoReport") : "")
                    + (body.containsKey("feishuPush") ? " 飞书推送:" + update.get("feishuPush") : ""));
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : "保存配置失败");
        }
    }

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

    @GetMapping("/kb/{id}/api/report/latest")
    @ResponseBody
    public Map<String, Object> getLatestReport(@PathVariable Long id) {
        String content = reportService.readTodayReport(id);
        if (content == null) content = reportService.readLatestReport(id);
        return Map.of("ok", true, "content", content != null ? content : "");
    }

    // ========== AI 分析 API ==========

    @GetMapping("/kb/{id}/api/analysis/list")
    @ResponseBody
    public Map<String, Object> listAnalysis(@PathVariable Long id) {
        List<AiAnalysisEntity> list = aiAnalysisDbService.lambdaQuery()
                .eq(AiAnalysisEntity::getKbId, id)
                .eq(AiAnalysisEntity::getType, "dir_analysis")
                .orderByDesc(AiAnalysisEntity::getCreatedAt)
                .list();
        List<Map<String, Object>> result = list.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("dirPath", e.getDirPath());
            m.put("content", e.getContent());
            m.put("createdAt", e.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        return Map.of("ok", true, "list", result);
    }

    @GetMapping("/kb/{id}/api/analysis/get")
    @ResponseBody
    public Map<String, Object> getAnalysis(@PathVariable Long id, @RequestParam Long aid) {
        AiAnalysisEntity entity = aiAnalysisDbService.getById(aid);
        if (entity == null || !id.equals(entity.getKbId())) {
            return Map.of("ok", false, "error", "分析记录不存在");
        }
        return Map.of("ok", true, "content", entity.getContent(), "dirPath", entity.getDirPath() != null ? entity.getDirPath() : "");
    }

    @PostMapping("/kb/{id}/api/analysis/save")
    @ResponseBody
    public Map<String, Object> saveAnalysis(@PathVariable Long id,
                                             @RequestBody Map<String, String> body) {
        String dirPath = body.getOrDefault("dirPath", "");
        String content = body.getOrDefault("content", "");
        String prompt = body.getOrDefault("prompt", "");
        if (content.isEmpty()) return Map.of("ok", false, "error", "内容不能为空");

        AiAnalysisEntity entity = new AiAnalysisEntity();
        entity.setKbId(id);
        entity.setType("dir_analysis");
        entity.setDirPath(dirPath);
        entity.setContent(content);
        entity.setPrompt(prompt);
        String now = TimeUtil.nowStr();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        aiAnalysisDbService.save(entity);
        logService.add("AI分析", "保存", "目录: " + dirPath);
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

    private List<Map<String, Object>> listFiles(Path dir) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
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
                        result.add(entry);
                    });
        } catch (Exception e) {
            log.error("遍历文件失败: {}", dir, e);
        }
        return result;
    }

    private List<String> buildBreadcrumbPaths(String dir) {
        if (dir == null || dir.isEmpty()) return List.of();
        String[] parts = dir.split("/");
        List<String> paths = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append("/");
            sb.append(part);
            paths.add(sb.toString());
        }
        return paths;
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
