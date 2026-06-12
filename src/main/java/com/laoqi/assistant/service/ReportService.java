package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.MarkdownUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final AppConfig appConfig;
    private final FeishuService feishuService;
    private final TodoService todoService;
    private final LogService logService;
    private final OpenCodeService openCodeService;
    private final ConfigService configService;

    private String latestReport = "";
    private String latestReportTime = "";
    private String latestError = "";

    public ReportService(AppConfig appConfig,
                          FeishuService feishuService, TodoService todoService,
                          LogService logService, OpenCodeService openCodeService,
                          ConfigService configService) {
        this.appConfig = appConfig;
        this.feishuService = feishuService;
        this.todoService = todoService;
        this.logService = logService;
        this.openCodeService = openCodeService;
        this.configService = configService;
    }

    private Path getComprehensiveReportDir() {
        Config config = configService.load();
        String workDir = config.getWorkDir();
        if (workDir == null || workDir.isEmpty()) workDir = "工作";
        return Paths.get(configService.getBaseDir()).resolve(workDir).resolve("综合日报");
    }

    public static class ReportResult {
        public String report;
        public String error;
    }

    public ReportResult generate() {
        ReportResult result = new ReportResult();
        result.error = "日报生成功能已移除，请使用模块系统进行分析";
        latestError = result.error;
        return result;
    }

    public String getLatestReport() { return latestReport; }
    public String getLatestReportTime() { return latestReportTime; }
    public String getLatestError() { return latestError; }

    public String readTodayReport() {
        Path dir = getComprehensiveReportDir();
        String date = TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        if (FileUtil.exists(file)) {
            String raw = FileUtil.readText(file);
            return MarkdownUtil.stripFrontmatter(raw);
        }
        return null;
    }

    public Path getTodayReportPath() {
        Path dir = getComprehensiveReportDir();
        String date = TimeUtil.todayStr();
        return dir.resolve(date + ".md");
    }

    public void generateAndPush() {
        ReportResult r = generate();
        if (r.report != null) {
            String today = TimeUtil.todayStr();
            String wd = TimeUtil.weekdayCn(TimeUtil.now());
            String title = TimeUtil.greetingEmoji() + " 老齐" + TimeUtil.greetingText() + " · " + today + " · " + wd;
            var paras = feishuService.reportToParagraphs(r.report);
            feishuService.sendPost(title, paras);
            saveComprehensiveReport(r.report);
            todoService.clearTempReminders();
            logService.add("日报生成", "成功", "AI 日报已生成并推送");
        } else {
            log.error("日报生成失败: {}", r.error);
            logService.add("日报生成", "失败", r.error);
        }
    }

    public void saveComprehensiveReport(String report) {
        Path dir = getComprehensiveReportDir();
        String date = TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        FileUtil.writeText(file, report);
    }
}
