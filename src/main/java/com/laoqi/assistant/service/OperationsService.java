package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.laoqi.assistant.util.TimeUtil;

@Service
public class OperationsService {

    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final OpenCodeService openCodeService;
    private final PromptService promptService;

    private String cachedAnalysis = "";
    private String cachedDate = "";

    public OperationsService(AppConfig appConfig, ConfigService configService, OpenCodeService openCodeService, PromptService promptService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.promptService = promptService;
    }

    private Path getBaseDir() {
        return Paths.get(configService.getBaseDir());
    }

    private Path getAnalysisDir() {
        var config = configService.load();
        String dir = config.getOperationsDataDir();
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException("运营数据目录未配置");
        }
        return getBaseDir().resolve(dir).resolve("AI分析");
    }

    /**
     * Read today's saved AI analysis report from file
     */
    public String readTodayAnalysis() {
        Path dir = getAnalysisDir();
        String date = TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        if (FileUtil.exists(file)) {
            return FileUtil.readText(file);
        }
        return null;
    }

    /**
     * Save AI analysis result to file
     */
    public void saveAnalysis(String result) {
        Path dir = getAnalysisDir();
        String date = TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        FileUtil.writeText(file, result);
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

        String prompt = promptService.getTemplate("operations-analysis");

        try {
            if (!openCodeService.isHealthy()) {
                return "⚠️ opencode serve 未启动，无法进行 AI 分析。请确保 opencode serve --port " + appConfig.getNotesPort() + " 已运行。";
            }

            String sessionId = openCodeService.createSession(promptService.getSessionTitle("operations-analysis"));

            String result = openCodeService.sendMessage(sessionId, prompt);
            // Cache successful result with today's date and save to file
            cachedAnalysis = result;
            cachedDate = com.laoqi.assistant.util.TimeUtil.todayStr();
            saveAnalysis(result);
            return result;
        } catch (Exception e) {
            log.error("AI 运营分析失败", e);
            return "❌ AI 分析失败：" + e.getMessage();
        }
    }

}
