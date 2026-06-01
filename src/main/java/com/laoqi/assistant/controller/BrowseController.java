package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.MarkdownUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class BrowseController {

    private static final Set<String> IGNORED = Set.of(
            ".git", ".obsidian", "__pycache__", ".DS_Store",
            ".claude", ".playwright-mcp", ".sisyphus");

    private final AppConfig appConfig;
    private final LogService logService;
    private final ConfigService configService;

    public BrowseController(AppConfig appConfig, LogService logService, ConfigService configService) {
        this.appConfig = appConfig;
        this.logService = logService;
        this.configService = configService;
    }

    private Path getBaseDir() {
        return Paths.get(configService.getBaseDir());
    }

    private Path safeResolve(String rel) {
        Path base = getBaseDir().normalize();
        Path resolved = base.resolve(rel != null ? rel : "").normalize();
        if (!resolved.startsWith(base)) return base;
        return resolved;
    }

    @GetMapping("/browse")
    public String browsePage(@RequestParam(required = false, defaultValue = "") String dir,
                              Model model) {
        Path target = safeResolve(dir);
        if (!Files.isDirectory(target)) {
            model.addAttribute("error", "目录不存在");
            return "browse";
        }

        List<Map<String, Object>> dirs = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();

        try (var stream = Files.list(target)) {
            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !IGNORED.contains(p.getFileName().toString()))
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
        } catch (IOException e) {
            model.addAttribute("error", "读取目录失败");
        }

        String rel = dir != null && !dir.isEmpty() ? dir : "";
        String parent = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
        List<String> breadcrumbs = rel.isEmpty() ? List.of() : Arrays.asList(rel.split("/"));

        model.addAttribute("dirs", dirs);
        model.addAttribute("files", files);
        model.addAttribute("rel", rel);
        model.addAttribute("parent", parent);
        model.addAttribute("breadcrumbs", breadcrumbs);
        return "browse";
    }

    @PostMapping("/browse/new")
    @ResponseBody
    public Map<String, Object> createFile(@RequestParam(required = false, defaultValue = "") String dir,
                                           @RequestParam String filename,
                                           @RequestParam(required = false, defaultValue = "") String content) {
        Path targetDir = safeResolve(dir);
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

    @PostMapping("/browse/delete")
    @ResponseBody
    public Map<String, Object> deleteFile(@RequestParam String path) {
        Path target = safeResolve(path);
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

    @GetMapping("/view")
    public String viewFile(@RequestParam(defaultValue = "") String path, Model model) {
        if (path.isEmpty()) return "redirect:/browse";

        Path target = safeResolve(path);
        if (!Files.isRegularFile(target) || !target.toString().endsWith(".md"))
            return "redirect:/browse";

        String content = FileUtil.readText(target);
        content = MarkdownUtil.stripFrontmatter(content);
        String html = MarkdownUtil.toHtml(content);
        String displayName = target.getFileName().toString().replace(".md", "");
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";

        model.addAttribute("title", displayName);
        model.addAttribute("content", html);
        model.addAttribute("parent", parent);
        return "view";
    }
}
