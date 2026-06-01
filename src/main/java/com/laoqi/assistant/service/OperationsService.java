package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class OperationsService {

    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);
    private static final TypeReference<Map<String, List<Map<String, Object>>>> MAP_LIST_TYPE = new TypeReference<>() {};

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final OpenCodeService openCodeService;

    private String cachedAnalysis = "";
    private String cachedDate = "";

    public OperationsService(AppConfig appConfig, ConfigService configService, OpenCodeService openCodeService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.openCodeService = openCodeService;
    }

    private Path getBaseDir() {
        return Paths.get(configService.getBaseDir());
    }

    private Path getDataDir() {
        var config = configService.load();
        String dir = config.getOperationsDataDir();
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException("运营数据目录未配置");
        }
        return getBaseDir().resolve(dir).resolve("data");
    }

    private Map<String, List<Map<String, Object>>> readJsonFile(String fileName) {
        Path file = getDataDir().resolve(fileName);
        return FileUtil.readJson(file, MAP_LIST_TYPE, new LinkedHashMap<>());
    }

    /**
     * Build a text summary of the operations data for use as AI prompt context
     */
    public String buildDataSummary() {
        var accounts = readJsonFile("自媒体账号.json");
        var articles = readJsonFile("自媒体文章.json");
        var dailyStats = readJsonFile("自媒体数据.json");

        StringBuilder sb = new StringBuilder();

        // Account summary
        if (accounts != null && !accounts.isEmpty()) {
            sb.append("## 账号概况\n\n");
            for (var accEntry : accounts.entrySet()) {
                sb.append("账号：").append(accEntry.getKey()).append("\n");
                List<Map<String, Object>> platforms = accEntry.getValue();
                if (platforms != null) {
                    for (Map<String, Object> info : platforms) {
                        sb.append("  - 平台：").append(info.get("platform"));
                        if (info.get("name") != null) sb.append("，名称：").append(info.get("name"));
                        if (info.get("fans") != null) sb.append("，粉丝：").append(info.get("fans"));
                        if (info.get("totalViews") != null) sb.append("，总访问：").append(info.get("totalViews"));
                        if (info.get("totalArticles") != null) sb.append("，总文章：").append(info.get("totalArticles"));
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        // Recent articles
        if (articles != null && !articles.isEmpty()) {
            sb.append("## 近期文章\n\n");
            for (var artEntry : articles.entrySet()) {
                sb.append("账号：").append(artEntry.getKey()).append("\n");
                List<Map<String, Object>> articleList = artEntry.getValue();
                if (articleList != null) {
                    for (Map<String, Object> a : articleList) {
                        sb.append("  - [").append(a.get("id")).append("] ").append(a.get("title"));
                        if (a.get("series") != null) sb.append(" (系列：").append(a.get("series")).append(")");
                        if (a.get("publishDate") != null) sb.append(" 发布于：").append(a.get("publishDate"));
                        sb.append("\n");
                        if (a.get("fansGained") != null) sb.append("    增粉：").append(a.get("fansGained"));
                        if (a.get("notes") != null) sb.append(" 备注：").append(a.get("notes"));
                        sb.append("\n");
                        if (a.get("reads") != null) sb.append("    阅读：").append(a.get("reads")).append(" ");
                        if (a.get("likes") != null) sb.append("点赞：").append(a.get("likes")).append(" ");
                        if (a.get("shares") != null) sb.append("分享：").append(a.get("shares")).append(" ");
                        if (a.get("favorites") != null) sb.append("收藏：").append(a.get("favorites"));
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        // Daily stats summary
        if (dailyStats != null && !dailyStats.isEmpty()) {
            sb.append("## 每日统计\n\n");
            for (var statsEntry : dailyStats.entrySet()) {
                sb.append("账号：").append(statsEntry.getKey()).append("\n");
                List<Map<String, Object>> stats = statsEntry.getValue();
                if (stats != null) {
                    int count = Math.min(stats.size(), 7);
                    for (int i = stats.size() - count; i < stats.size(); i++) {
                        Map<String, Object> d = stats.get(i);
                        if (d != null) {
                            sb.append("  - ").append(d.get("date"));
                            if (d.get("fans") != null) sb.append(" 粉丝：").append(d.get("fans"));
                            if (d.get("reads") != null) sb.append(" 阅读：").append(d.get("reads"));
                            if (d.get("readArticles") != null) sb.append(" 文章：").append(d.get("readArticles"));
                            sb.append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Get cached AI analysis if already generated today, or null if not cached
     */
    public String getCachedAnalysis() {
        String today = com.laoqi.assistant.util.TimeUtil.todayStr();
        if (today.equals(cachedDate) && !cachedAnalysis.isEmpty()) {
            return cachedAnalysis;
        }
        return null;
    }

    /**
     * Use AI to analyze operations data and return insights.
     * Returns cached result if available today, unless force=true.
     */
    public String aiAnalyze(boolean force) {
        if (!force) {
            String cached = getCachedAnalysis();
            if (cached != null) {
                log.debug("[AI分析] 使用缓存的运营分析结果");
                return cached;
            }
        }

        String dataSummary = buildDataSummary();

        String prompt = "你是一个自媒体运营分析专家。以下是我的自媒体运营数据：\n\n"
                + dataSummary
                + "\n请根据以上数据，生成一份运营分析报告，包含：\n"
                + "1. 【数据概览】整体运营状况一句话总结\n"
                + "2. 【各平台表现】每个平台的关键指标和变化趋势\n"
                + "3. 【文章表现】表现最好和最差的文章分析\n"
                + "4. 【发现问题】数据中反映出的问题\n"
                + "5. 【改进建议】具体的改进措施建议\n\n"
                + "请使用简洁的中文，适当使用小标题和列表，便于阅读。";

        try {
            if (!openCodeService.isHealthy()) {
                return "⚠️ opencode serve 未启动，无法进行 AI 分析。请确保 opencode serve --port " + appConfig.getNotesPort() + " 已运行。";
            }

            String sessionId = openCodeService.createSession("运营分析");

            String result = openCodeService.sendMessage(sessionId, prompt);
            // Cache successful result with today's date
            cachedAnalysis = result;
            cachedDate = com.laoqi.assistant.util.TimeUtil.todayStr();
            return result;
        } catch (Exception e) {
            log.error("AI 运营分析失败", e);
            return "❌ AI 分析失败：" + e.getMessage();
        }
    }

}
