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
//        log.info("Loading config from {}", appConfig.getConfigFile());
        Config config = FileUtil.readJson(appConfig.getConfigFile(), Config.class,
                Config.defaultConfig("", ""));
        mergeDefaultValues(config);
//        log.info("Config loaded - mediaCollectEnabled={}, mediaCollectTime={}",
//                config.isMediaCollectEnabled(), config.getMediaCollectTime());
        return config;
    }

    private void mergeDefaultValues(Config config) {
        Config defaultConfig = Config.defaultConfig("", "");
        
        if (config.getMediaCollectTime() == null || config.getMediaCollectTime().isEmpty()) {
//            log.info("Setting default mediaCollectTime: {}", defaultConfig.getMediaCollectTime());
            config.setMediaCollectTime(defaultConfig.getMediaCollectTime());
        }
        
        if (config.isMediaCollectEnabled() == null) {
//            log.info("Setting default mediaCollectEnabled: {}", defaultConfig.isMediaCollectEnabled());
            config.setMediaCollectEnabled(defaultConfig.isMediaCollectEnabled());
        }
        
        if (config.isFeishuPollingEnabled() == null) {
            config.setFeishuPollingEnabled(defaultConfig.isFeishuPollingEnabled());
        }
        
        if (config.getWorkDir() == null || config.getWorkDir().isEmpty()) {
            config.setWorkDir(defaultConfig.getWorkDir());
        }
        
        if (config.getDailyDir() == null || config.getDailyDir().isEmpty()) {
            config.setDailyDir(defaultConfig.getDailyDir());
        }
        
        if (config.getWeeklyDir() == null || config.getWeeklyDir().isEmpty()) {
            config.setWeeklyDir(defaultConfig.getWeeklyDir());
        }
        
        if (config.getLogFile() == null || config.getLogFile().isEmpty()) {
            config.setLogFile(defaultConfig.getLogFile());
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
        log.info("Saving config - mediaCollectEnabled={}, mediaCollectTime={}", 
                config.isMediaCollectEnabled(), config.getMediaCollectTime());
        mergeDefaultValues(config);
        
        // 确保 boolean 字段被显式设置
        if (config.getMediaCollectTime() == null || config.getMediaCollectTime().isEmpty()) {
            config.setMediaCollectTime("08:00");
        }
        
        // 强制调用 setter 确保字段被包含
        config.setMediaCollectEnabled(config.isMediaCollectEnabled());
        config.setMediaCollectTime(config.getMediaCollectTime());
        
        log.info("Writing config to {}", appConfig.getConfigFile());
        FileUtil.writeJson(appConfig.getConfigFile(), config);
        log.info("Config saved successfully");
        
        // 重新读取验证
        Config savedConfig = FileUtil.readJson(appConfig.getConfigFile(), Config.class, null);
        if (savedConfig != null) {
            log.info("Verified saved config - mediaCollectEnabled={}, mediaCollectTime={}", 
                    savedConfig.isMediaCollectEnabled(), savedConfig.getMediaCollectTime());
        }
    }
}
