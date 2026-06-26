package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
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
    private final AgentAnalysisService agentAnalysisService;
    private final ConfigService configService;
    private final KnowledgeBaseService kbService;

    private volatile String latestReport = "";
    private volatile String latestReportTime = "";
    private volatile String latestError = "";

    public ReportService(AppConfig appConfig,
                          FeishuService feishuService,
                          LogService logService,
                          AgentAnalysisService agentAnalysisService,
                          ConfigService configService,
                          KnowledgeBaseService kbService) {
        this.appConfig = appConfig;
        this.feishuService = feishuService;
        this.logService = logService;
        this.agentAnalysisService = agentAnalysisService;
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
            String notesDir = kbService.getNotesDirById(kbId);
            Path kbDir = Paths.get(notesDir);

            String prompt = readPrompt(kbId);
            prompt = prompt.replace("{date}", TimeUtil.todayStr());
            prompt = prompt.replace("{weekday}", TimeUtil.weekdayCn(TimeUtil.now()));

            if (!agentAnalysisService.isAvailable()) {
                result.error = "LLM API Key 未配置";
                latestReport = "";
                latestError = result.error;
                return result;
            }

            String systemPrompt = "你是一个日报生成助手。你拥有文件操作工具，可以自主读取笔记库中的文件。\n"
                + "请先用 listDir 探索目录结构，用 searchFiles 搜索相关文件，"
                + "用 readFile 读取需要的内容（AGENTS.md、记忆文件、工作日报、笔记文档等），"
                + "然后根据提示词生成今日综合日报。用中文回复。";

            String report = agentAnalysisService.analyze(kbDir, prompt, systemPrompt);

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
        return readLatestReport(notesDir);
    }

    public String readLatestReport(String notesDir) {
        Path dir = getComprehensiveReportDir(notesDir);
        if (!java.nio.file.Files.isDirectory(dir)) return null;

        // 优先读取今天的
        String date = TimeUtil.todayStr();
        Path todayFile = dir.resolve(date + ".md");
        if (FileUtil.exists(todayFile)) {
            String raw = FileUtil.readText(todayFile);
            return MarkdownUtil.stripFrontmatter(raw);
        }

        // 没有今天的，读取最近一份（排除提示词文件）
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals(PROMPT_FILENAME))
                    .sorted(java.util.Comparator.reverseOrder())
                    .findFirst()
                    .map(p -> {
                        String raw = FileUtil.readText(p);
                        return MarkdownUtil.stripFrontmatter(raw);
                    })
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public String getLatestReportDate(String notesDir) {
        Path dir = getComprehensiveReportDir(notesDir);
        if (!java.nio.file.Files.isDirectory(dir)) return null;

        // 优先今天的
        String today = TimeUtil.todayStr();
        if (FileUtil.exists(dir.resolve(today + ".md"))) {
            return today;
        }

        // 否则取最近文件名（排除提示词文件）
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals(PROMPT_FILENAME))
                    .sorted(java.util.Comparator.reverseOrder())
                    .findFirst()
                    .map(p -> p.getFileName().toString().replace(".md", ""))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
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
            KnowledgeBaseEntity kb = null;
            String kbLabel = "";
            if (kbId != null) {
                kb = kbService.getById(kbId);
                if (kb != null) kbLabel = "【" + kb.getName() + "】";
            }
            String title = TimeUtil.greetingEmoji() + " 老齐" + TimeUtil.greetingText() + " · " + today + " · " + wd + kbLabel;

            // 检查飞书推送开关（默认开启）
            boolean feishuPush = kb != null && (kb.getFeishuPush() == null || kb.getFeishuPush() == 1);

            if (feishuPush) {
                // 使用卡片格式推送
                String markdownContent = r.report
                    .replace("\\n", "\n")
                    .replace("\n", "\\n")
                    .replace("\"", "\\\"")
                    .replace("|", "\\|");
                feishuService.sendCard(title, markdownContent);
            } else {
                log.info("[日报推送] 知识库「{}」已关闭飞书推送，跳过", kbLabel.replaceAll("[【】]", ""));
            }

            saveComprehensiveReport(r.report, kbId);
            logService.add("日报生成", "成功", "AI 日报已生成并推送" + kbLabel);
        } else {
            log.error("日报生成失败: {}", r.error);
            logService.add("日报生成", "失败", r.error);
        }
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
