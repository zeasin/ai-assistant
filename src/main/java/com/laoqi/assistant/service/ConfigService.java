package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final AppConfig appConfig;
    private final KnowledgeBaseService kbService;

    public ConfigService(AppConfig appConfig, KnowledgeBaseService kbService) {
        this.appConfig = appConfig;
        this.kbService = kbService;
    }

    private void ensureConfigFile() {
        Path configFile = appConfig.getConfigFile();
        if (Files.exists(configFile)) return;
        Path template = appConfig.getConfigDirPath().resolve("config.template.json");
        if (Files.exists(template)) {
            try {
                Files.copy(template, configFile);
                log.info("config.json 不存在，已从 config.template.json 创建");
            } catch (Exception e) {
                log.warn("无法从模板创建 config.json: {}", e.getMessage());
            }
        }
    }

    public Config load() {
        ensureConfigFile();

        Map<String, Object> raw = FileUtil.readJson(appConfig.getConfigFile(), MAP_TYPE, null);

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
        String dir = kbService.getNotesDir();
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException("未配置笔记库，请先在「设置」页面添加知识库并配置笔记库路径");
        }
        return dir;
    }

    /** 获取笔记库目录，不存在时返回 null（不抛异常） */
    public String getNotesDirIfExists() {
        try {
            return kbService.getNotesDir();
        } catch (Exception e) {
            return null;
        }
    }

    public String getNotesDir(Long kbId) {
        String dir = kbService.getNotesDirById(kbId);
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException("未配置笔记库，请先在「设置」页面添加知识库并配置笔记库路径");
        }
        return dir;
    }

    public void save(Config config) {
        mergeDefaultValues(config);
        FileUtil.writeJson(appConfig.getConfigFile(), config);
    }
}
