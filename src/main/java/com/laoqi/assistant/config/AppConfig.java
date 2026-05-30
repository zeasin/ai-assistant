package com.laoqi.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private String baseDir;
    private String configDir;
    private int notesPort = 14096;
    private int codePort = 14099;
    private String feishuWebhookUrl;
    private String timezone = "Asia/Shanghai";
    private int maxHistoryChars = 6000;

    public Path getBaseDirPath() {
        return Paths.get(baseDir);
    }

    public Path getConfigDirPath() {
        return Paths.get(configDir);
    }

    public Path getConfigFile() {
        return getConfigDirPath().resolve("config.json");
    }

    public Path getLogFile() {
        return getBaseDirPath().resolve("assistant_log.json");
    }

    public Path getChatSessionsFile() {
        return getBaseDirPath().resolve("chat_sessions.json");
    }

    public Path getDailyDir() {
        return getBaseDirPath().resolve("工作").resolve("日报");
    }

    public Path getWeeklyDir() {
        return getBaseDirPath().resolve("工作").resolve("周报");
    }

    public Path getComprehensiveReportDir() {
        return getBaseDirPath().resolve("工作").resolve("综合日报");
    }

    public Path getRemindFile() {
        return getBaseDirPath().resolve("提醒.md");
    }

    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }
    public int getNotesPort() { return notesPort; }
    public void setNotesPort(int notesPort) { this.notesPort = notesPort; }
    public int getCodePort() { return codePort; }
    public void setCodePort(int codePort) { this.codePort = codePort; }
    public String getFeishuWebhookUrl() { return feishuWebhookUrl; }
    public void setFeishuWebhookUrl(String feishuWebhookUrl) { this.feishuWebhookUrl = feishuWebhookUrl; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public int getMaxHistoryChars() { return maxHistoryChars; }
    public void setMaxHistoryChars(int maxHistoryChars) { this.maxHistoryChars = maxHistoryChars; }
}