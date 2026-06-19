package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.MarkdownUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
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
    private final LlmService llmService;
    private final ConfigService configService;
    private final KnowledgeBaseService kbService;

    private volatile String latestReport = "";
    private volatile String latestReportTime = "";
    private volatile String latestError = "";

    public ReportService(AppConfig appConfig,
                          FeishuService feishuService,
                          LogService logService,
                          LlmService llmService,
                          ConfigService configService,
                          KnowledgeBaseService kbService) {
        this.appConfig = appConfig;
        this.feishuService = feishuService;
        this.logService = logService;
        this.llmService = llmService;
        this.configService = configService;
        this.kbService = kbService;
    }

    private Path getComprehensiveReportDir() {
        return getComprehensiveReportDir(configService.getNotesDir());
    }

    private Path getComprehensiveReportDir(Long kbId) {
        return getComprehensiveReportDir(kbService.getNotesDirById(kbId));
    }

    private Path getComprehensiveReportDir(String notesDir) {
        return Paths.get(notesDir).resolve("AI").resolve("综合日报");
    }

    private Path getPromptsDir() {
        return getComprehensiveReportDir();
    }

    private Path getPromptsDir(Long kbId) {
        return getComprehensiveReportDir(kbId);
    }

    public String readPrompt() {
        return readPrompt((Long) null);
    }

    public String readPrompt(Long kbId) {
        Path dir = getPromptsDir(kbId);
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
        writePrompt(content, (Long) null);
    }

    public void writePrompt(String content, Long kbId) {
        Path dir = getPromptsDir(kbId);
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
        return generate((Long) null);
    }

    public ReportResult generate(Long kbId) {
        ReportResult result = new ReportResult();
        try {
            String prompt = readPrompt(kbId);
            prompt = prompt.replace("{date}", TimeUtil.todayStr());
            prompt = prompt.replace("{weekday}", TimeUtil.weekdayCn(TimeUtil.now()));

            String report;
            if (!llmService.isAvailable()) {
                result.error = "LLM API Key 未配置";
                latestReport = "";
                latestError = result.error;
                return result;
            }
            // 收集笔记库上下文
            String context = collectNoteContext(kbId);
            String fullPrompt = prompt;
            if (context != null && !context.isEmpty()) {
                fullPrompt += "\n\n以下是我的工作笔记和记忆文件内容，请基于这些内容生成日报：\n\n" + context;
            }
            report = llmService.chat("你是一个日报生成助手，根据工作笔记生成综合日报。请用中文回复。", fullPrompt);

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
        return readTodayReport(configService.getNotesDir());
    }

    public String readTodayReport(String notesDir) {
        Path dir = getComprehensiveReportDir(notesDir);
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
        generateAndPush((Long) null);
    }

    public void generateAndPush(Long kbId) {
        ReportResult r = generate(kbId);
        if (r.report != null) {
            String today = TimeUtil.todayStr();
            String wd = TimeUtil.weekdayCn(TimeUtil.now());
            String kbLabel = "";
            if (kbId != null) {
                var kb = kbService.getById(kbId);
                if (kb != null) kbLabel = "【" + kb.getName() + "】";
            }
            String title = TimeUtil.greetingEmoji() + " 老齐" + TimeUtil.greetingText() + " · " + today + " · " + wd + kbLabel;
            var paras = feishuService.reportToParagraphs(r.report);
            feishuService.sendPost(title, paras);
            saveComprehensiveReport(r.report, kbId);
            logService.add("日报生成", "成功", "AI 日报已生成并推送" + kbLabel);
        } else {
            log.error("日报生成失败: {}", r.error);
            logService.add("日报生成", "失败", r.error);
        }
    }

    /**
     * 收集笔记库上下文，供 LLM 直连模式使用。
     * 读取 AGENTS.md、AI/记忆/ 目录下的文件，以及今天的工作日报。
     */
    private String collectNoteContext() {
        return collectNoteContext((Long) null);
    }

    private String collectNoteContext(Long kbId) {
        StringBuilder sb = new StringBuilder();
        String notesDir;
        try {
            notesDir = kbService.getNotesDirById(kbId);
        } catch (Exception e) {
            return "";
        }
        Path base = Paths.get(notesDir);

        // 1. AGENTS.md
        Path agents = base.resolve("AGENTS.md");
        if (FileUtil.exists(agents)) {
            String content = FileUtil.readText(agents);
            if (!content.isBlank()) {
                sb.append("--- AGENTS.md ---\n");
                sb.append(content, 0, Math.min(content.length(), 3000));
                sb.append("\n\n");
            }
        }

        // 2. AI/记忆/ 目录下的文件
        Path memoryDir = base.resolve("AI").resolve("记忆");
        if (Files.exists(memoryDir)) {
            try (var files = Files.list(memoryDir)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                        .forEach(p -> {
                            String content = FileUtil.readText(p);
                            if (!content.isBlank()) {
                                sb.append("--- 记忆/").append(p.getFileName()).append(" ---\n");
                                sb.append(content, 0, Math.min(content.length(), 2000));
                                sb.append("\n\n");
                            }
                        });
            } catch (Exception ignored) {}
        }

        // 3. 今天的工作日报（工作/日报/{date}.md）
        Path dailyDir = base.resolve("工作").resolve("日报");
        if (Files.exists(dailyDir)) {
            String date = TimeUtil.todayStr();
            Path todayNote = dailyDir.resolve(date + ".md");
            if (FileUtil.exists(todayNote)) {
                String content = FileUtil.readText(todayNote);
                if (!content.isBlank()) {
                    sb.append("--- 今日工作记录 ---\n");
                    sb.append(content, 0, Math.min(content.length(), 4000));
                    sb.append("\n\n");
                }
            }
        }

        return sb.toString();
    }

    public void saveComprehensiveReport(String report) {
        saveComprehensiveReport(report, (Long) null);
    }

    public void saveComprehensiveReport(String report, Long kbId) {
        Path dir = getComprehensiveReportDir(kbId);
        String date = TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        FileUtil.writeText(file, report);
    }
}
