package com.laoqi.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    private String baseDir;
    private String feishuWebhookUrl;
    private String feishuAppId;
    private String feishuAppSecret;
    private String feishuChatId;
    private boolean feishuPollingEnabled;
    
    private String customerDataPath;
    private String leadDataPath;
    private String recordDataPath;
    private String operationsDataPath;
    
    private String chatSessionsDir;
    private String chatSessionsFile;
    private String logFile;
    private String dailyDir;
    private String weeklyDir;
    private String comprehensiveReportDir;
    private String remindFile;

    public Config() {}

    public static Config defaultConfig(String webhookUrl, String baseDir) {
        Config c = new Config();
        c.baseDir = baseDir;
        c.feishuWebhookUrl = webhookUrl;
        c.customerDataPath = "企业/客户管理/客户数据.json";
        c.leadDataPath = "企业/客户管理/线索数据.json";
        c.recordDataPath = "企业/客户管理/跟进记录.json";
        c.operationsDataPath = "自媒体/运营数据.json";
        c.chatSessionsDir = "chat";
        c.chatSessionsFile = "chat_sessions.json";
        c.logFile = "assistant_log.json";
        c.dailyDir = "工作/日报";
        c.weeklyDir = "工作/周报";
        c.comprehensiveReportDir = "工作/综合日报";
        c.remindFile = "提醒.md";
        return c;
    }

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
    public boolean isFeishuPollingEnabled() { return feishuPollingEnabled; }
    public void setFeishuPollingEnabled(boolean feishuPollingEnabled) { this.feishuPollingEnabled = feishuPollingEnabled; }
    public String getCustomerDataPath() { return customerDataPath; }
    public void setCustomerDataPath(String customerDataPath) { this.customerDataPath = customerDataPath; }
    public String getLeadDataPath() { return leadDataPath; }
    public void setLeadDataPath(String leadDataPath) { this.leadDataPath = leadDataPath; }
    public String getRecordDataPath() { return recordDataPath; }
    public void setRecordDataPath(String recordDataPath) { this.recordDataPath = recordDataPath; }
    public String getOperationsDataPath() { return operationsDataPath; }
    public void setOperationsDataPath(String operationsDataPath) { this.operationsDataPath = operationsDataPath; }
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
    public String getComprehensiveReportDir() { return comprehensiveReportDir; }
    public void setComprehensiveReportDir(String comprehensiveReportDir) { this.comprehensiveReportDir = comprehensiveReportDir; }
    public String getRemindFile() { return remindFile; }
    public void setRemindFile(String remindFile) { this.remindFile = remindFile; }
}
