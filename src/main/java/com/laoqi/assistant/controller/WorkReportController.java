package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.MarkdownUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Controller
public class WorkReportController {

    private final AppConfig appConfig;
    private final LogService logService;
    private final ConfigService configService;

    public WorkReportController(AppConfig appConfig, LogService logService, ConfigService configService) {
        this.appConfig = appConfig;
        this.logService = logService;
        this.configService = configService;
    }

    private Path getDailyDir() {
        Config config = configService.load();
        String baseDir = config.getBaseDir();
        if (baseDir == null || baseDir.isEmpty()) baseDir = "D:\\projects\\richie_learning_notes";
        String dailyDir = config.getDailyDir();
        if (dailyDir == null || dailyDir.isEmpty()) dailyDir = "工作\\日报";
        return Paths.get(baseDir).resolve(dailyDir);
    }

    private Path getWeeklyDir() {
        Config config = configService.load();
        String baseDir = config.getBaseDir();
        if (baseDir == null || baseDir.isEmpty()) baseDir = "D:\\projects\\richie_learning_notes";
        String weeklyDir = config.getWeeklyDir();
        if (weeklyDir == null || weeklyDir.isEmpty()) weeklyDir = "工作\\周报";
        return Paths.get(baseDir).resolve(weeklyDir);
    }

    record ReportItem(String name, String modified, String content) {}

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

    @GetMapping("/work-reports")
    public String workReportsPage(@RequestParam(required = false, defaultValue = "daily") String tab,
                                   Model model) {
        List<ReportItem> dailyReports = loadReports(getDailyDir());
        List<ReportItem> weeklyReports = loadReports(getWeeklyDir());
        model.addAttribute("daily_reports", dailyReports);
        model.addAttribute("weekly_reports", weeklyReports);
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
