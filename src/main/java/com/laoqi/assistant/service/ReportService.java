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
    private static final String PROMPT_FILENAME = "分析提示词.md";
    private static final String DEFAULT_PROMPT = "现在是{date} {weekday}。请根据我的工作笔记生成今天的综合日报。内容需要涵盖：今日重点工作、客户沟通情况、开发进展、文章发布情况、明日计划。请按以下格式输出：\n\n【今日重点】\n...\n\n【客户沟通】\n...\n\n【开发进展】\n...\n\n【文章发布】\n...\n\n【明日计划】\n...\n\n注意：如果某个板块没有相关信息，请写\"暂无\"。请使用中文回复。";

    private final AppConfig appConfig;
    private final FeishuService feishuService;
    private final LogService logService;
    private final OpenCodeService openCodeService;
    private final ConfigService configService;

    private String latestReport = "";
    private String latestReportTime = "";
    private String latestError = "";

    public ReportService(AppConfig appConfig,
                          FeishuService feishuService,
                          LogService logService, OpenCodeService openCodeService,
                          ConfigService configService) {
        this.appConfig = appConfig;
        this.feishuService = feishuService;
        this.logService = logService;
        this.openCodeService = openCodeService;
        this.configService = configService;
    }

    private Path getComprehensiveReportDir() {
        return Paths.get(configService.getBaseDir()).resolve("AI").resolve("综合日报");
    }

    private Path getPromptsDir() {
        return getComprehensiveReportDir();
    }

    public String readPrompt() {
        Path dir = getPromptsDir();
        if (dir == null) return DEFAULT_PROMPT;
        Path file = dir.resolve(PROMPT_FILENAME);
        if (FileUtil.exists(file)) {
            return FileUtil.readText(file);
        }
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("Failed to create prompts dir: {}", e.getMessage());
        }
        FileUtil.writeText(file, DEFAULT_PROMPT);
        log.info("已创建综合日报提示词文件: {}", file);
        return DEFAULT_PROMPT;
    }

    public void writePrompt(String content) {
        Path dir = getPromptsDir();
        if (dir == null) return;
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("Failed to create prompts dir: {}", e.getMessage());
        }
        Path file = dir.resolve(PROMPT_FILENAME);
        FileUtil.writeText(file, content);
    }

    public static class ReportResult {
        public String report;
        public String error;
    }

    public ReportResult generate() {
        ReportResult result = new ReportResult();
        try {
            if (!openCodeService.isHealthy()) {
                result.error = "opencode serve 未启动";
                latestReport = "";
                latestError = result.error;
                return result;
            }

            String prompt = readPrompt();
            prompt = prompt.replace("{date}", TimeUtil.todayStr());
            prompt = prompt.replace("{weekday}", TimeUtil.weekdayCn(TimeUtil.now()));

            String sessionId = openCodeService.findIdleSession();
            if (sessionId == null) {
                sessionId = openCodeService.createSession("综合日报");
            }

            String report = openCodeService.sendMessage(sessionId, prompt);
            if (report != null && !report.isEmpty()) {
                result.report = report;
                latestReport = report;
                latestReportTime = TimeUtil.nowStr();
                latestError = "";
            } else {
                result.error = "AI 返回内容为空";
                latestReport = "";
                latestError = result.error;
            }
        } catch (Exception e) {
            log.error("生成日报失败", e);
            result.error = e.getMessage();
            latestReport = "";
            latestError = e.getMessage();
        }
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
