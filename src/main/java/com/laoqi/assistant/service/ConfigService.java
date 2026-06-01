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
                Config.defaultConfig("", ""));
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
        FileUtil.writeJson(appConfig.getConfigFile(), config);
    }
}
