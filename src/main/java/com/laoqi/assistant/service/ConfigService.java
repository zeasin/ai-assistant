package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final AppConfig appConfig;

    public ConfigService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public Config load() {
        // Migration: rename old baseDir field to notesDir
        Map<String, Object> raw = FileUtil.readJson(appConfig.getConfigFile(), MAP_TYPE, null);
        if (raw != null && raw.containsKey("baseDir") && !raw.containsKey("notesDir")) {
            raw.put("notesDir", raw.get("baseDir"));
            raw.remove("baseDir");
            FileUtil.writeJson(appConfig.getConfigFile(), raw);
            log.info("已迁移 config.json: baseDir → notesDir");
        }

        Config config;
        if (raw != null) {
            config = new ObjectMapper().convertValue(raw, Config.class);
        } else {
            config = Config.defaultConfig("", "");
        }
        mergeDefaultValues(config);
        return config;
    }

    private void mergeDefaultValues(Config config) {
        Config defaultConfig = Config.defaultConfig("", "");
        
        if (config.isFeishuPollingEnabled() == null) {
            config.setFeishuPollingEnabled(defaultConfig.isFeishuPollingEnabled());
        }
        // aiProvider 废弃 v0.4.0 — 始终直连 LLM
        if (config.getLlmBaseUrl() == null || config.getLlmBaseUrl().isEmpty()) {
            config.setLlmBaseUrl("https://api.deepseek.com");
        }
        if (config.getLlmModel() == null || config.getLlmModel().isEmpty()) {
            config.setLlmModel("deepseek-chat");
        }
        if (config.getLlmTimeout() <= 0) {
            config.setLlmTimeout(600);
        } else if (config.getLlmTimeout() <= 180) {
            config.setLlmTimeout(600);
        }
        // 编程AI 默认值
        if (config.isCodingPiEnabled() == null) {
            config.setCodingPiEnabled(false);
        }
        if (config.getCodingPiTimeout() == null || config.getCodingPiTimeout() <= 0) {
            config.setCodingPiTimeout(300);
        }
        if (config.getCodingFeishuAppId() == null) {
            config.setCodingFeishuAppId("");
        }
        if (config.getCodingFeishuAppSecret() == null) {
            config.setCodingFeishuAppSecret("");
        }
        if (config.getCodingFeishuChatId() == null) {
            config.setCodingFeishuChatId("");
        }
        if (config.getCodingProjectDir() == null) {
            config.setCodingProjectDir("");
        }
    }

    public String getNotesDir() {
        Config config = load();
        String dir = config.getNotesDir();
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException("未配置笔记库根目录，请先在「设置」页面配置");
        }
        return dir;
    }

    /** @deprecated use getNotesDir() */
    @Deprecated
    public String getBaseDir() { return getNotesDir(); }

    public void save(Config config) {
        mergeDefaultValues(config);
        FileUtil.writeJson(appConfig.getConfigFile(), config);
    }
}
