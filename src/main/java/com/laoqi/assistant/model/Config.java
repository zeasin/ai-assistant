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
    
    private String logFile;
    private List<Map<String, Object>> modules;
    // aiProvider 废弃 v3.0 — 始终直连 LLM，仅保留 getter 兼容旧配置
    private String llmApiKey = "";
    private String llmBaseUrl = "https://api.deepseek.com";
    private String llmModel = "deepseek-chat";
    private int llmTimeout = 600;
    private Map<String, Map<String, List<String>>> columnSettings = new HashMap<>();

    public Config() {}

    public static Config defaultConfig(String webhookUrl, String baseDir) {
        Config c = new Config();
        c.baseDir = baseDir;
        c.feishuWebhookUrl = webhookUrl;
        c.logFile = "assistant_log.json";
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
    public String getLogFile() { return logFile; }
    public void setLogFile(String logFile) { this.logFile = logFile; }
    public List<Map<String, Object>> getModules() { return modules; }
    public void setModules(List<Map<String, Object>> modules) { this.modules = modules; }
    public String getAiProvider() { return "direct"; }
    public void setAiProvider(String aiProvider) { /* 废弃 v3.0 */ }
    public String getLlmApiKey() { return llmApiKey; }
    public void setLlmApiKey(String llmApiKey) { this.llmApiKey = llmApiKey; }
    public String getLlmBaseUrl() { return llmBaseUrl; }
    public void setLlmBaseUrl(String llmBaseUrl) { this.llmBaseUrl = llmBaseUrl; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public int getLlmTimeout() { return llmTimeout; }
    public void setLlmTimeout(int llmTimeout) { this.llmTimeout = llmTimeout; }
    public Map<String, Map<String, List<String>>> getColumnSettings() { return columnSettings; }
    public void setColumnSettings(Map<String, Map<String, List<String>>> columnSettings) { this.columnSettings = columnSettings; }
}
