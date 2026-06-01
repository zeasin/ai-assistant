package com.laoqi.assistant.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Config {
    private Map<String, String> keyLabels = new HashMap<>();
    private String baseDir;
    private String feishuWebhookUrl;
    private String feishuAppId;
    private String feishuAppSecret;
    private String feishuChatId;
    private Boolean feishuPollingEnabled;
    
    private String customerDataPath;
    private String leadDataPath;
    private String recordDataPath;
    private String customerDataDir;
    private String operationsDataPath;
    private String operationsDataDir;
    
    private String workDir;
    private String chatSessionsDir;
    private String chatSessionsFile;
    private String logFile;
    private String dailyDir;
    private String weeklyDir;
    private String remindFile;
    private Boolean mediaCollectEnabled;
    private String mediaCollectTime;
    private Map<String, Map<String, List<String>>> columnSettings = new HashMap<>();

    public Config() {}

    public static Config defaultConfig(String webhookUrl, String baseDir) {
        Config c = new Config();
        c.baseDir = baseDir;
        c.feishuWebhookUrl = webhookUrl;
        c.customerDataDir = "";
        c.operationsDataPath = "自媒体/运营数据.json";
        c.chatSessionsDir = "chat";
        c.chatSessionsFile = "chat_sessions.json";
        c.logFile = "assistant_log.json";
        c.dailyDir = "日报";
        c.weeklyDir = "周报";
        c.workDir = "工作";
        c.remindFile = "提醒.md";
        c.mediaCollectEnabled = false;
        c.mediaCollectTime = "08:00";
        return c;
    }

    public Map<String, String> getKeyLabels() { return keyLabels; }
    public void setKeyLabels(Map<String, String> keyLabels) { this.keyLabels = keyLabels; }
    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    public String getFeishuWebhookUrl() { return feishuWebhookUrl; }
    public void setFeishuWebhookUrl(String feishuWebhookUrl) { this.feishuWebhookUrl = feishuWebhookUrl; }
    public String getFeishuAppId() { return feishuAppId; }
    public void setFeishuAppId(String feishuAppId) { this.feishuAppId = feishuAppId; }
    public String getFeishuAppSecret() { return feishuAppSecret; }
    public void setFeishuAppSecret(String feishuAppSecret) { this.feishuAppSecret = feishuAppSecret; }
    public String getFeishuChatId() { return feishuChatId; }
    public void setFeishuChatId(String feishuChatId) { this.feishuChatId = feishuChatId; }
    public Boolean isFeishuPollingEnabled() { return feishuPollingEnabled; }
    public void setFeishuPollingEnabled(Boolean feishuPollingEnabled) { this.feishuPollingEnabled = feishuPollingEnabled; }
    public String getCustomerDataPath() { return customerDataPath; }
    public void setCustomerDataPath(String customerDataPath) { this.customerDataPath = customerDataPath; }
    public String getLeadDataPath() { return leadDataPath; }
    public void setLeadDataPath(String leadDataPath) { this.leadDataPath = leadDataPath; }
    public String getRecordDataPath() { return recordDataPath; }
    public void setRecordDataPath(String recordDataPath) { this.recordDataPath = recordDataPath; }
    public String getCustomerDataDir() { return customerDataDir; }
    public void setCustomerDataDir(String customerDataDir) { this.customerDataDir = customerDataDir; }
    public String getOperationsDataPath() { return operationsDataPath; }
    public void setOperationsDataPath(String operationsDataPath) { this.operationsDataPath = operationsDataPath; }
    public String getOperationsDataDir() { return operationsDataDir; }
    public void setOperationsDataDir(String operationsDataDir) { this.operationsDataDir = operationsDataDir; }
    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public String getChatSessionsDir() { return chatSessionsDir; }
    public void setChatSessionsDir(String chatSessionsDir) { this.chatSessionsDir = chatSessionsDir; }
    public String getChatSessionsFile() { return chatSessionsFile; }
    public void setChatSessionsFile(String chatSessionsFile) { this.chatSessionsFile = chatSessionsFile; }
    public String getLogFile() { return logFile; }
    public void setLogFile(String logFile) { this.logFile = logFile; }
    public String getDailyDir() { return dailyDir; }
    public void setDailyDir(String dailyDir) { this.dailyDir = dailyDir; }
    public String getWeeklyDir() { return weeklyDir; }
    public void setWeeklyDir(String weeklyDir) { this.weeklyDir = weeklyDir; }
    public String getRemindFile() { return remindFile; }
    public void setRemindFile(String remindFile) { this.remindFile = remindFile; }
    public Boolean isMediaCollectEnabled() { return mediaCollectEnabled; }
    public void setMediaCollectEnabled(Boolean mediaCollectEnabled) { this.mediaCollectEnabled = mediaCollectEnabled; }
    public String getMediaCollectTime() { return mediaCollectTime; }
    public void setMediaCollectTime(String mediaCollectTime) { this.mediaCollectTime = mediaCollectTime; }
    public Map<String, Map<String, List<String>>> getColumnSettings() { return columnSettings; }
    public void setColumnSettings(Map<String, Map<String, List<String>>> columnSettings) { this.columnSettings = columnSettings; }
}
