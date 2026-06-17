package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private final AppConfig appConfig;

    public ConfigService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public Config load() {
        Config config = FileUtil.readJson(appConfig.getConfigFile(), Config.class,
                Config.defaultConfig("", ""));
        mergeDefaultValues(config);
        return config;
    }

    private void mergeDefaultValues(Config config) {
        Config defaultConfig = Config.defaultConfig("", "");
        
        if (config.isFeishuPollingEnabled() == null) {
            config.setFeishuPollingEnabled(defaultConfig.isFeishuPollingEnabled());
        }
        // aiProvider 废弃 v0.3.0 — 始终直连 LLM
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

    public String getBaseDir() {
        Config config = load();
        String dir = config.getBaseDir();
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException("未配置笔记库根目录，请先在「设置」页面配置");
        }
        return dir;
    }

    public void save(Config config) {
        mergeDefaultValues(config);
        FileUtil.writeJson(appConfig.getConfigFile(), config);
    }
}
