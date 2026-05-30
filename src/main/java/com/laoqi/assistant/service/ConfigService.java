package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {

    private final AppConfig appConfig;

    public ConfigService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public Config load() {
        return FileUtil.readJson(appConfig.getConfigFile(), Config.class,
                Config.defaultConfig(appConfig.getFeishuWebhookUrl()));
    }

    public void save(Config config) {
        FileUtil.writeJson(appConfig.getConfigFile(), config);
    }
}