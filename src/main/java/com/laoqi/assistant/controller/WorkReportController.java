package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.OpenCodeService;
import com.laoqi.assistant.service.PromptService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.MarkdownUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Controller
public class WorkReportController {

    private final AppConfig appConfig;
    private final LogService logService;
    private final ConfigService configService;
    private final OpenCodeService openCodeService;
    private final PromptService promptService;
    private static final ObjectMapper mapper = new ObjectMapper();

    public WorkReportController(AppConfig appConfig, LogService logService, ConfigService configService, OpenCodeService openCodeService, PromptService promptService) {
        this.appConfig = appConfig;
        this.logService = logService;
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.promptService = promptService;
    }

    private Path getDailyDir() {
        Config config = configService.load();
        String workDir = config.getWorkDir();
        if (workDir == null || workDir.isEmpty()) workDir = "工作";
        String dailyDir = config.getDailyDir();
        if (dailyDir == null || dailyDir.isEmpty()) dailyDir = "日报";
        return Paths.get(configService.getBaseDir()).resolve(workDir).resolve(dailyDir);
    }

    private Path getWeeklyDir() {
        Config config = configService.load();
        String workDir = config.getWorkDir();
        if (workDir == null || workDir.isEmpty()) workDir = "工作";
        String weeklyDir = config.getWeeklyDir();
        if (weeklyDir == null || weeklyDir.isEmpty()) weeklyDir = "周报";
        return Paths.get(configService.getBaseDir()).resolve(workDir).resolve(weeklyDir);
    }

    record ReportItem(String name, String modified, String content) {}

    private Path getAnalysisDir() {
        Config config = configService.load();
        String workDir = config.getWorkDir();
        if (workDir == null || workDir.isEmpty()) workDir = "工作";
        return Paths.get(configService.getBaseDir()).resolve(workDir).resolve("AI分析");
    }

    private String readTodayAnalysis() {
        Path dir = getAnalysisDir();
        String date = com.laoqi.assistant.util.TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        if (FileUtil.exists(file)) {
            return FileUtil.readText(file);
        }
        return null;
    }

    private void saveAnalysis(String result) {
        Path dir = getAnalysisDir();
        String date = com.laoqi.assistant.util.TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        FileUtil.writeText(file, result);
    }

    private List<ReportItem> loadReports(Path dir) {
        List<ReportItem> items = new ArrayList<>();
        if (!Files.isDirectory(dir)) return items;

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(p -> {
                        String raw = FileUtil.readText(p);
                        raw = MarkdownUtil.stripFrontmatter(raw);
                        String html = MarkdownUtil.toHtml(raw);
                        String mtime = "";
                        try {
                            mtime = LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(p).toInstant(), ZoneId.of("Asia/Shanghai"))
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                        } catch (IOException ignored) {}
                        items.add(new ReportItem(
                                p.getFileName().toString().replace(".md", ""),
                                mtime, html));
                    });
        } catch (IOException ignored) {}
        return items;
    }

    @GetMapping("/api/work-reports/ai-report")
    @ResponseBody
    public Map<String, Object> getAiReport() {
        String report = readTodayAnalysis();
        if (report != null && !report.isEmpty()) {
            return Map.of("ok", true, "report", report);
        }
        return Map.of("ok", false, "report", "");
    }

    /**
     * Generate and save work report analysis (for scheduled task use)
     */
    public String generateAndSaveAnalysis() {
        String result = buildAiReportSummary();
        if (result != null && !result.isEmpty()) {
            saveAnalysis(result);
        }
        return result;
    }

    @GetMapping("/api/work-reports/ai-analysis")
    public SseEmitter aiAnalysis(@RequestParam(required = false, defaultValue = "false") boolean force) {
        SseEmitter emitter = new SseEmitter(300_000L);

        if (!force) {
            String cached = readTodayAnalysis();
            if (cached != null && !cached.isEmpty()) {
                try {
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                            Map.of("type", "text", "content", cached))));
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("type", "done"))));
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "status", "content", "⏳ AI 正在分析工作日报..."))));

                String result = buildAiReportSummary();
                if (result != null && !result.isEmpty()) {
                    saveAnalysis(result);
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

    private String buildAiReportSummary() {
        String prompt = promptService.getTemplate("work-report-analysis");

        try {
            if (!openCodeService.isHealthy()) {
                return "⚠️ opencode serve 未启动（端口 " + appConfig.getNotesPort() + "），无法进行 AI 分析。";
            }
            String sessionId = openCodeService.createSession(promptService.getSessionTitle("work-report-analysis"));
            return openCodeService.sendMessage(sessionId, prompt);
        } catch (Exception e) {
            return "❌ AI 分析失败：" + e.getMessage();
        }
    }

    @GetMapping("/work-reports")
    public String workReportsPage(Model model) {
        model.addAttribute("daily_reports", loadReports(getDailyDir()));
        model.addAttribute("weekly_reports", loadReports(getWeeklyDir()));
        return "work_reports";
    }

    @GetMapping("/daily-reports")
    public String dailyReportsPage(Model model) {
        model.addAttribute("reports", loadReports(getDailyDir()));
        return "daily_reports";
    }

    @GetMapping("/weekly-reports")
    public String weeklyReportsPage(Model model) {
        model.addAttribute("reports", loadReports(getWeeklyDir()));
        return "weekly_reports";
    }

    @PostMapping("/api/daily-reports/add")
    @ResponseBody
    public Map<String, Object> addDailyReport(
            @RequestParam String content,
            @RequestParam(required = false, defaultValue = "") String date) {
        String dateStr = date.isEmpty() ? com.laoqi.assistant.util.TimeUtil.todayStr() : date;
        if (!dateStr.matches("\\d{4}-\\d{2}-\\d{2}"))
            return Map.of("ok", false, "error", "日期格式错误，应为 YYYY-MM-DD");

        Path file = getDailyDir().resolve(dateStr + ".md");
        if (Files.exists(file)) return Map.of("ok", false, "error", "日报文件已存在: " + dateStr + ".md");

        String md = """
---
tags:
  - 日报
created: %s
modified: %s
---

# %s 日报

%s
""".formatted(dateStr, dateStr, dateStr, content);

        FileUtil.writeText(file, md);
        logService.add("添加日报", "成功", dateStr);
        return Map.of("ok", true, "date", dateStr);
    }

    @PostMapping("/api/daily-reports/update")
    @ResponseBody
    public Map<String, Object> updateDailyReport(
            @RequestParam String date,
            @RequestParam String content) {
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}"))
            return Map.of("ok", false, "error", "日期格式错误，应为 YYYY-MM-DD");

        Path file = getDailyDir().resolve(date + ".md");
        if (!Files.exists(file))
            return Map.of("ok", false, "error", "日报文件不存在: " + date + ".md");

        String raw = FileUtil.readText(file);
        String created = com.laoqi.assistant.util.TimeUtil.todayStr();
        if (raw.contains("created:")) {
            int start = raw.indexOf("created:") + 9;
            int end = raw.indexOf("\n", start);
            if (end > start) {
                String orig = raw.substring(start, end).trim();
                if (orig.matches("\\d{4}-\\d{2}-\\d{2}")) created = orig;
            }
        }

        String md = """
---
tags:
  - 日报
created: %s
modified: %s
---

# %s 日报

%s
""".formatted(created, com.laoqi.assistant.util.TimeUtil.todayStr(), date, content);

        FileUtil.writeText(file, md);
        logService.add("编辑日报", "成功", date);
        return Map.of("ok", true, "date", date);
    }

    @GetMapping("/api/daily-reports/content")
    @ResponseBody
    public Map<String, Object> getDailyReportContent(@RequestParam String date) {
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}"))
            return Map.of("ok", false, "error", "日期格式错误");

        Path file = getDailyDir().resolve(date + ".md");
        if (!Files.exists(file))
            return Map.of("ok", false, "error", "日报文件不存在");

        String raw = FileUtil.readText(file);
        String content = com.laoqi.assistant.util.MarkdownUtil.stripFrontmatter(raw);
        
        // 去掉标题行，避免重复标题
        String titlePattern = "^#\\s+" + Pattern.quote(date) + "\\s+日报\\s*";
        content = content.replaceFirst(titlePattern, "").strip();
        
        return Map.of("ok", true, "content", content);
    }

    @PostMapping("/api/weekly-reports/add")
    @ResponseBody
    public Map<String, Object> addWeeklyReport(
            @RequestParam String content,
            @RequestParam(required = false, defaultValue = "") String yearWeek,
            @RequestParam(required = false, defaultValue = "") String dateRange) {
        String yw = yearWeek.isEmpty() ?
                com.laoqi.assistant.util.TimeUtil.now().format(DateTimeFormatter.ofPattern("GGGG-VV"))
                        .replace(" ", "-") : yearWeek;
        if (!yw.matches("\\d{4}-\\d{1,2}"))
            return Map.of("ok", false, "error", "年周格式错误，应为 YYYY-WW");

        Path file = getWeeklyDir().resolve(yw + ".md");
        if (Files.exists(file)) return Map.of("ok", false, "error", "周报文件已存在: " + yw + ".md");

        String display = dateRange.isEmpty() ? yw + "周" : yw + "周（" + dateRange + "）";
        String md = """
---
tags:
  - 工作计划
  - 周报
created: %s
---

# %s工作计划

%s
""".formatted(com.laoqi.assistant.util.TimeUtil.todayStr(), display, content);

        FileUtil.writeText(file, md);
        logService.add("添加周报", "成功", yw);
        return Map.of("ok", true, "year_week", yw);
    }
}
