package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.util.FileUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    private static final TypeReference<Map<String, Map<String, String>>> PROMPT_MAP_TYPE = new TypeReference<>() {};
    private static final String DEFAULT_RESOURCE = "prompts-defaults.json";

    private final AppConfig appConfig;
    private Map<String, Map<String, String>> prompts = new HashMap<>();

    public PromptService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        prompts = loadDefaults();
        Map<String, Map<String, String>> external = loadExternal();
        if (external != null) {
            prompts.putAll(external);
            log.info("Loaded {} prompt overrides from external file", external.size());
        }
        log.info("PromptService initialized with {} prompt templates", prompts.size());
    }

    private Path getExternalPath() {
        return appConfig.getConfigFile().resolveSibling("prompts.json");
    }

    public Map<String, Map<String, String>> getAllPrompts() {
        return new LinkedHashMap<>(prompts);
    }

    public void savePrompts(Map<String, Map<String, String>> prompts) {
        Path external = getExternalPath();
        FileUtil.writeJson(external, prompts);
        reload();
        log.info("Saved {} prompts to {}", prompts.size(), external);
    }

    public void resetPrompts() {
        Path external = getExternalPath();
        try {
            java.nio.file.Files.deleteIfExists(external);
        } catch (Exception e) {
            log.warn("Failed to delete external prompts file: {}", e.getMessage());
        }
        reload();
        log.info("Reset prompts to defaults");
    }

    private Map<String, Map<String, String>> loadDefaults() {
        try {
            ClassPathResource resource = new ClassPathResource(DEFAULT_RESOURCE);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return new ObjectMapper().readValue(is, PROMPT_MAP_TYPE);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load default prompts from classpath: {}", e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Map<String, String>> loadExternal() {
        Path external = appConfig.getConfigFile().resolveSibling("prompts.json");
        if (FileUtil.exists(external)) {
            try {
                return FileUtil.readJson(external, PROMPT_MAP_TYPE, null);
            } catch (Exception e) {
                log.warn("Failed to load external prompts from {}: {}", external, e.getMessage());
            }
        }
        return null;
    }

    public String getTemplate(String key) {
        Map<String, String> def = prompts.get(key);
        if (def == null) return null;
        return def.get("template");
    }

    public String getSessionTitle(String key) {
        Map<String, String> def = prompts.get(key);
        if (def == null) return null;
        return def.get("session_title");
    }

    public String format(String key, Map<String, String> variables) {
        String template = getTemplate(key);
        if (template == null) return null;
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, String> e : variables.entrySet()) {
                result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
            }
        }
        return result;
    }
}
