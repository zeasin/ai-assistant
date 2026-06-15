package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ModuleDefinition;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.ModuleDataService;
import com.laoqi.assistant.service.ModuleService;
import com.laoqi.assistant.service.OpenCodeService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.MarkdownUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
@RequestMapping("/module")
public class ModuleController {

    private static final Logger log = LoggerFactory.getLogger(ModuleController.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ModuleService moduleService;
    private final ModuleDataService moduleDataService;
    private final OpenCodeService openCodeService;
    private final AppConfig appConfig;
    private final ConfigService configService;

    public ModuleController(ModuleService moduleService, ModuleDataService moduleDataService,
                            OpenCodeService openCodeService, AppConfig appConfig,
                            ConfigService configService) {
        this.moduleService = moduleService;
        this.moduleDataService = moduleDataService;
        this.openCodeService = openCodeService;
        this.appConfig = appConfig;
        this.configService = configService;
    }

    @GetMapping("/{id}")
    public String modulePage(@PathVariable String id, Model model) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) {
            model.addAttribute("error", "模块不存在: " + id);
            return "module";
        }
        model.addAttribute("module", mod);
        return "module";
    }

    @GetMapping(value = "/{id}/data/files", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> listDataFiles(@PathVariable String id) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dataDir = moduleService.getModuleDataDir(mod);
        if (!Files.exists(dataDir)) return Map.of("ok", true, "files", List.of(), "dir", dataDir.toString());

        var files = moduleDataService.listJsonFiles(dataDir);
        return Map.of("ok", true, "files", files, "dir", dataDir.toString());
    }

    @GetMapping(value = "/{id}/data/file", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getFileData(@PathVariable String id,
                                           @RequestParam String file) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dataDir = moduleService.getModuleDataDir(mod);
        var result = moduleDataService.getFileData(dataDir, file);
        if (result == null) return Map.of("ok", false, "error", "文件不存在");
        Map<String, Object> resp = new HashMap<>(result);
        resp.put("ok", true);
        return resp;
    }

    @GetMapping(value = "/{id}/data/group", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getGroupData(@PathVariable String id,
                                            @RequestParam String file,
                                            @RequestParam String group) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dataDir = moduleService.getModuleDataDir(mod);
        var result = moduleDataService.getGroupData(dataDir, file, group);
        if (result == null) return Map.of("ok", false, "error", "数据不存在");
        Map<String, Object> resp = new HashMap<>(result);
        resp.put("ok", true);
        return resp;
    }

    @PostMapping(value = "/{id}/data/add", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> addRecord(@PathVariable String id,
                                         @RequestParam String file,
                                         @RequestParam String group,
                                         @RequestBody Map<String, Object> record,
                                         @RequestParam(required = false) String subGroup) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dataDir = moduleService.getModuleDataDir(mod);
        return moduleDataService.addRecord(dataDir, file, group, record, subGroup);
    }

    @PostMapping(value = "/{id}/data/update", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> updateRecord(@PathVariable String id,
                                            @RequestParam String file,
                                            @RequestParam String group,
                                            @RequestParam String idField,
                                            @RequestParam String idValue,
                                            @RequestBody Map<String, Object> updates) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dataDir = moduleService.getModuleDataDir(mod);
        return moduleDataService.updateRecord(dataDir, file, group, idField, idValue, updates);
    }

    @PostMapping(value = "/{id}/data/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> deleteRecord(@PathVariable String id,
                                            @RequestParam String file,
                                            @RequestParam String group,
                                            @RequestParam String idField,
                                            @RequestParam String idValue) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dataDir = moduleService.getModuleDataDir(mod);
        return moduleDataService.deleteRecord(dataDir, file, group, idField, idValue);
    }

    @GetMapping(value = "/{id}/docs/tree", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getDocTree(@PathVariable String id,
                                          @RequestParam(required = false, defaultValue = "") String path) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dir = moduleService.getModuleDir(mod);
        Path target = path.isEmpty() ? dir : dir.resolve(path).normalize();
        if (!target.startsWith(dir)) return Map.of("ok", false, "error", "路径越界");
        if (!Files.exists(target)) return Map.of("ok", true, "tree", null);

        String parentPath = "";
        if (!path.isEmpty()) {
            int idx = path.lastIndexOf('/');
            parentPath = idx >= 0 ? path.substring(0, idx) : "";
        }

        return Map.of("ok", true, "tree", buildTree(dir, target, 0),
                "currentPath", path, "parentPath", parentPath);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildTree(Path root, Path current, int depth) {
        Map<String, Object> node = new LinkedHashMap<>();
        String name = current.getFileName() != null ? current.getFileName().toString() : "";
        node.put("name", current.equals(root) ? "" : name);
        node.put("path", root.relativize(current).toString().replace("\\", "/"));

        if (Files.isDirectory(current)) {
            if (current.equals(root)) {
                node.put("path", "");
            }
            node.put("type", "dir");
            List<Map<String, Object>> children = new ArrayList<>();
            try (Stream<Path> stream = Files.list(current)) {
                stream
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("data")
                            && !p.getFileName().toString().equals("AI分析"))
                    .filter(p -> depth < 3 || Files.isRegularFile(p))
                    .sorted()
                    .forEach(p -> children.add(buildTree(root, p, depth + 1)));
            } catch (Exception ignored) {}
            node.put("children", children);
        } else {
            node.put("type", "file");
            node.put("ext", name.contains(".") ? name.substring(name.lastIndexOf('.')) : "");
        }
        return node;
    }

    @GetMapping(value = "/{id}/docs/content", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getDocContent(@PathVariable String id,
                                             @RequestParam String path) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dir = moduleService.getModuleDir(mod);
        Path file = dir.resolve(path).normalize();
        if (!file.startsWith(dir)) return Map.of("ok", false, "error", "路径越界");
        if (!Files.isRegularFile(file)) return Map.of("ok", false, "error", "文件不存在");

        String content = FileUtil.readText(file);
        String html = MarkdownUtil.toHtml(MarkdownUtil.stripFrontmatter(content));
        return Map.of("ok", true, "content", content, "html", html,
                "name", file.getFileName().toString().replace(".md", ""));
    }

    @GetMapping("/{id}/analysis")
    public SseEmitter aiAnalysis(@PathVariable String id,
                                 @RequestParam(required = false, defaultValue = "false") boolean force) {
        SseEmitter emitter = new SseEmitter(300_000L);
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) {
            try {
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "error", "content", "模块不存在"))));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Map<String, Object> statusEvent = Map.of("type", "status",
                        "content", "⏳ AI 正在分析 " + mod.getName() + " 数据...");
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(statusEvent)));

                String result = doAiAnalyze(mod);

                Map<String, Object> textEvent = Map.of("type", "text", "content", result);
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(textEvent)));

                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("type", "done"))));
                emitter.complete();
            } catch (Exception e) {
                try {
                    Map<String, Object> errorEvent = Map.of("type", "error",
                            "content", "AI 分析失败: " + e.getMessage());
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(errorEvent)));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });
        return emitter;
    }

    @GetMapping(value = "/{id}/analysis/report", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getAnalysisReport(@PathVariable String id) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");

        Path dir = moduleService.getModuleDir(mod);
        Path analysisDir = dir.resolve("AI分析");
        if (!Files.exists(analysisDir)) return Map.of("ok", false, "report", "");

        // 优先读取当天分析文件
        String date = TimeUtil.todayStr();
        Path todayFile = analysisDir.resolve(date + ".md");
        if (Files.exists(todayFile)) {
            String content = FileUtil.readText(todayFile);
            return Map.of("ok", true, "report", content, "date", date);
        }

        // 当天不存在则读取 AI分析 目录下最后一份 markdown 文件
        try (Stream<Path> stream = Files.list(analysisDir)) {
            Path latest = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.reverseOrder())
                    .findFirst()
                    .orElse(null);
            if (latest != null) {
                String content = FileUtil.readText(latest);
                String fileName = latest.getFileName().toString();
                String fileDate = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
                return Map.of("ok", true, "report", content, "date", fileDate);
            }
        } catch (Exception ignored) {}

        return Map.of("ok", false, "report", "");
    }

    @GetMapping(value = "/{id}/prompt", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getPrompt(@PathVariable String id) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");
        String prompt = moduleService.readPrompt(mod);
        return Map.of("ok", true, "prompt", prompt);
    }

    @PostMapping(value = "/{id}/prompt", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> savePrompt(@PathVariable String id,
                                          @RequestBody Map<String, String> body) {
        ModuleDefinition mod = moduleService.getModule(id);
        if (mod == null) return Map.of("ok", false, "error", "模块不存在");
        moduleService.writePrompt(mod, body.getOrDefault("prompt", ""));
        return Map.of("ok", true);
    }

    private String doAiAnalyze(ModuleDefinition mod) {
        if (!openCodeService.isHealthy()) {
            return "⚠️ opencode serve 未启动，无法进行 AI 分析。请确保 opencode serve --port "
                    + appConfig.getNotesPort() + " 已运行。";
        }

        String prompt = moduleService.readPrompt(mod);

        Path dataDir = moduleService.getModuleDataDir(mod);
        StringBuilder fullPrompt = new StringBuilder(prompt);
        fullPrompt.append("\n\n以下是可用的数据文件：\n");

        for (String fileName : mod.getDataFiles()) {
            Path filePath = dataDir.resolve(fileName);
            if (Files.exists(filePath)) {
                String content = FileUtil.readText(filePath);
                fullPrompt.append("\n--- ").append(fileName).append(" ---\n");
                fullPrompt.append(content);
            }
        }

        fullPrompt.append("\n\n请根据以上数据进行分析，给出具体的洞察、趋势和建议。");

        try {
            String sessionId = openCodeService.createSession(mod.getName() + "分析");
            String result = openCodeService.sendMessage(sessionId, fullPrompt.toString());

            Path dir = moduleService.getModuleDir(mod);
            Path analysisDir = dir.resolve("AI分析");
            String date = TimeUtil.todayStr();
            FileUtil.writeText(analysisDir.resolve(date + ".md"), result);
            return result;
        } catch (Exception e) {
            log.error("AI analysis failed for module {}", mod.getId(), e);
            return "❌ AI 分析失败：" + e.getMessage();
        }
    }

    @GetMapping(value = "/{id}/column-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getColumnSettings(@PathVariable String id) {
        var config = configService.load();
        var allSettings = config.getColumnSettings();
        if (allSettings == null) allSettings = new HashMap<>();
        var moduleSettings = allSettings.getOrDefault(id, new HashMap<>());
        return Map.of("ok", true, "settings", moduleSettings);
    }

    @PostMapping(value = "/{id}/column-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> saveColumnSettings(@PathVariable String id,
                                                  @RequestBody Map<String, List<String>> settings) {
        try {
            var config = configService.load();
            var allSettings = config.getColumnSettings();
            if (allSettings == null) allSettings = new HashMap<>();
            allSettings.put(id, settings);
            config.setColumnSettings(allSettings);
            configService.save(config);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping(value = "/save", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> saveModules(@RequestBody List<ModuleDefinition> modules) {
        try {
            moduleService.saveModules(modules);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
