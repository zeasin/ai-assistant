package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.AiAnalysisEntity;
import com.laoqi.assistant.datacenter.DataSetService;
import com.laoqi.assistant.datacenter.model.DataSet;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.db.AiAnalysisDbService;
import com.laoqi.assistant.util.MarkdownUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final String DEFAULT_PROMPT = "现在是{date} {weekday}。请根据我的工作笔记生成今天的综合日报。内容需要涵盖：今日重点工作、客户沟通情况、开发进展、文章发布情况、明日计划。请按以下格式输出：\n\n【今日重点】\n...\n\n【客户沟通】\n...\n\n【开发进展】\n...\n\n【文章发布】\n...\n\n【明日计划】\n...\n\n注意：如果某个板块没有相关信息，请写\"暂无\"。请使用中文回复。";

    private final AppConfig appConfig;
    private final FeishuService feishuService;
    private final LogService logService;
    private final AgentAnalysisService agentAnalysisService;
    private final ConfigService configService;
    private final KnowledgeBaseService kbService;
    private final AiAnalysisDbService aiAnalysisDbService;
    private final DataSetService dataSetService;

    private volatile String latestReport = "";
    private volatile String latestReportTime = "";
    private volatile String latestError = "";

    public ReportService(AppConfig appConfig,
                          FeishuService feishuService,
                          LogService logService,
                          AgentAnalysisService agentAnalysisService,
                          ConfigService configService,
                          KnowledgeBaseService kbService,
                          AiAnalysisDbService aiAnalysisDbService,
                          DataSetService dataSetService) {
        this.appConfig = appConfig;
        this.feishuService = feishuService;
        this.logService = logService;
        this.agentAnalysisService = agentAnalysisService;
        this.configService = configService;
        this.kbService = kbService;
        this.aiAnalysisDbService = aiAnalysisDbService;
        this.dataSetService = dataSetService;
    }

    // ==================== 提示词 ====================

    public String readPrompt() {
        return readPrompt((Long) null);
    }

    public String readPrompt(Long kbId) {
        if (kbId != null) {
            AiAnalysisEntity entity = aiAnalysisDbService.getDailyReportPrompt(kbId);
            if (entity != null) {
                return entity.getContent();
            }
        }
        return DEFAULT_PROMPT;
    }

    public void writePrompt(String content) {
        writePrompt(content, (Long) null);
    }

    public void writePrompt(String content, Long kbId) {
        if (kbId != null) {
            aiAnalysisDbService.saveDailyReportPrompt(kbId, content);
            log.info("已保存日报提示词到 SQLite, kbId={}", kbId);
        }
    }

    // ==================== 日报生成 ====================

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

            String systemPrompt = "你是一个日报生成助手。你拥有以下工具：\n"
                + "1. 文件工具（listDir / searchFiles / readFile）— 读取笔记库中的文件、AGENTS.md、工作日报、记忆文件等\n"
                + "2. 数据集工具（listDatasets / queryRecords）— 查询结构化数据（任务、Bug、项目管理等）\n\n"
                + "请先用 listDir 探索目录结构，用 searchFiles 搜索相关文件，"
                + "用 readFile 读取需要的内容。\n"
                + "同时用 listDatasets 查看有哪些数据集，用 queryRecords 按条件查询（如{\"状态\":\"待修复\"}、{\"类型\":\"Bug\"}等），"
                + "将笔记非结构化数据与数据集结构化数据结合，生成更全面的日报。\n"
                + "用中文回复。";

            // 主动读取所有数据集内容，注入到对话上下文中，确保日报包含结构化数据
            String datasetContext = buildDatasetContext();

            String report = agentAnalysisService.analyze(kbDir, prompt + "\n\n" + datasetContext, systemPrompt);

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

    // ==================== 日报读取（纯 SQLite） ====================

    public String readTodayReport() {
        return readTodayReport((Long) null);
    }

    public String readTodayReport(Long kbId) {
        if (kbId == null) return null;
        AiAnalysisEntity entity = aiAnalysisDbService.getTodayReport(kbId);
        if (entity != null) {
            return MarkdownUtil.stripFrontmatter(entity.getContent());
        }
        return null;
    }

    public String readLatestReport(Long kbId) {
        if (kbId == null) return null;
        AiAnalysisEntity entity = aiAnalysisDbService.getLatestReport(kbId);
        if (entity != null) {
            return MarkdownUtil.stripFrontmatter(entity.getContent());
        }
        return null;
    }

    public String getLatestReportDate(Long kbId) {
        if (kbId == null) return null;
        return aiAnalysisDbService.getLatestReportDate(kbId);
    }

    // ==================== 数据集读取 ====================

    /**
     * 主动读取所有数据集内容，格式化为上下文字符串注入到 prompt 中。
     * 这样 AI 无需额外调用工具即可直接使用结构化数据生成日报。
     */
    private String buildDatasetContext() {
        try {
            List<DataSet> datasets = dataSetService.getAllDatasets();
            if (datasets.isEmpty()) {
                return "\n===== 数据集信息 =====\n当前没有数据集，仅根据笔记文件生成日报。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n===== 数据集信息（已自动读取，无需再调用数据集工具） =====\n\n");

            for (DataSet ds : datasets) {
                sb.append("📦 数据集：「").append(ds.getName()).append("」");
                if (ds.getDescription() != null && !ds.getDescription().isEmpty()) {
                    sb.append("（").append(ds.getDescription()).append("）");
                }
                sb.append("\n");

                // 读取该数据集的所有记录（最多 50 条）
                List<Map<String, Object>> records = dataSetService.queryRecords(ds.getId(), null);
                if (records.isEmpty()) {
                    sb.append("  暂无记录\n\n");
                    continue;
                }

                sb.append("  共 ").append(records.size()).append(" 条记录：\n");
                for (int i = 0; i < Math.min(records.size(), 50); i++) {
                    Map<String, Object> record = records.get(i);
                    sb.append("  [").append(i + 1).append("] ");
                    record.forEach((k, v) -> {
                        if (!k.startsWith("_") && v != null) {
                            sb.append(k).append(": ").append(v).append(" | ");
                        }
                    });
                    sb.append("\n");
                }
                if (records.size() > 50) {
                    sb.append("  ... 还有 ").append(records.size() - 50).append(" 条记录未显示\n");
                }
                sb.append("\n");
            }

            sb.append("===== 数据集信息结束 =====\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[日报] 读取数据集失败，继续生成：{}", e.getMessage());
            return "\n（数据集读取失败：" + e.getMessage() + "）";
        }
    }

    // ==================== 日报推送与保存 ====================

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

            boolean feishuPush = kb != null && (kb.getFeishuPush() == null || kb.getFeishuPush() == 1);

            if (feishuPush) {
                String markdownContent = r.report.replace("\\n", "\n");
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
        String today = TimeUtil.todayStr();
        String now = TimeUtil.nowStr();

        AiAnalysisEntity entity = new AiAnalysisEntity();
        entity.setKbId(kbId);
        entity.setType("daily_report");
        entity.setContent(report);
        entity.setReportDate(today);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        aiAnalysisDbService.save(entity);

        // 自动清理7天前的日报
        aiAnalysisDbService.cleanOldReports(kbId, 7);

        log.info("日报已保存到 SQLite, kbId={}, date={}", kbId, today);
    }
}
