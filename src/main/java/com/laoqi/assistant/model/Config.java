package com.laoqi.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    private String feishuWebhookUrl;
    private String feishuAppId;
    private String feishuAppSecret;
    private String feishuChatId;
    private boolean feishuPollingEnabled;
    private String erpProjectDir;

    public Config() {}

    public static Config defaultConfig(String webhookUrl) {
        Config c = new Config();
        c.feishuWebhookUrl = webhookUrl;
        return c;
    }

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
    public String getErpProjectDir() { return erpProjectDir; }
    public void setErpProjectDir(String erpProjectDir) { this.erpProjectDir = erpProjectDir; }
}