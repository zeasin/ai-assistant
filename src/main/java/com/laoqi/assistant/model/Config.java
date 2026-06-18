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
    // aiProvider 废弃 v0.4.0 — 始终直连 LLM，仅保留 getter 兼容旧配置
    private String llmApiKey = "";
    private String llmBaseUrl = "https://api.deepseek.com";
    private String llmModel = "deepseek-chat";
    private int llmTimeout = 600;
    private Map<String, Map<String, List<String>>> columnSettings = new HashMap<>();

    // 语义向量模型配置（直接存 config.json，不经过 llm_profiles）
    private String embeddingModel = "nomic-embed-text";
    private String embeddingBaseUrl = "http://127.0.0.1:11434";
    private String embeddingApiKey = "";
    private String embeddingProvider = "";

    // 编程AI 配置
    private String codingFeishuAppId = "";
    private String codingFeishuAppSecret = "";
    private String codingFeishuChatId = "";
    private String codingProjectDir = "";
    private Boolean codingPiEnabled = false;
    private Integer codingPiTimeout = 300;

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
    public String getAiProvider() { return "direct"; }
    public void setAiProvider(String aiProvider) { /* 废弃 v0.4.0 */ }
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

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String v) { this.embeddingModel = v; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String v) { this.embeddingBaseUrl = v; }
    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String v) { this.embeddingApiKey = v; }
    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String v) { this.embeddingProvider = v; }

    public String getCodingFeishuAppId() { return codingFeishuAppId; }
    public void setCodingFeishuAppId(String v) { this.codingFeishuAppId = v; }
    public String getCodingFeishuAppSecret() { return codingFeishuAppSecret; }
    public void setCodingFeishuAppSecret(String v) { this.codingFeishuAppSecret = v; }
    public String getCodingFeishuChatId() { return codingFeishuChatId; }
    public void setCodingFeishuChatId(String v) { this.codingFeishuChatId = v; }
    public String getCodingProjectDir() { return codingProjectDir; }
    public void setCodingProjectDir(String v) { this.codingProjectDir = v; }
    public Boolean isCodingPiEnabled() { return codingPiEnabled; }
    public void setCodingPiEnabled(Boolean v) { this.codingPiEnabled = v; }
    public Integer getCodingPiTimeout() { return codingPiTimeout; }
    public void setCodingPiTimeout(Integer v) { this.codingPiTimeout = v; }
}
