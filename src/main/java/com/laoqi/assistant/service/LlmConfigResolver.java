package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.db.LlmProfileDbService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;

@Component
@DependsOn("sessionService")
public class LlmConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigResolver.class);

    private final ConfigService configService;
    private final RestClient.Builder llmRestClientBuilder;
    private final LlmProfileDbService llmProfileDbService;

    public LlmConfigResolver(ConfigService configService, RestClient.Builder llmRestClientBuilder,
                             LlmProfileDbService llmProfileDbService) {
        this.configService = configService;
        this.llmRestClientBuilder = llmRestClientBuilder;
        this.llmProfileDbService = llmProfileDbService;
    }

    @PostConstruct
    public void init() {
        migrateLegacyConfig();
    }

    private void migrateLegacyConfig() {
        List<LlmProfileEntity> existing = llmProfileDbService.listAllOrdered();
        if (!existing.isEmpty()) return;

        Config cfg = loadConfig();
        String apiKey = cfg != null ? cfg.getLlmApiKey() : "";
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("LLM_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("DEEPSEEK_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) return;

        String baseUrl = (cfg != null && cfg.getLlmBaseUrl() != null && !cfg.getLlmBaseUrl().isEmpty())
                ? cfg.getLlmBaseUrl() : "https://api.deepseek.com";
        String model = (cfg != null && cfg.getLlmModel() != null && !cfg.getLlmModel().isEmpty())
                ? cfg.getLlmModel() : "deepseek-chat";
        int timeout = (cfg != null && cfg.getLlmTimeout() > 0) ? cfg.getLlmTimeout() : 600;

        LlmProfileEntity profile = new LlmProfileEntity();
        profile.setName("default");
        profile.setApiKey(apiKey);
        profile.setBaseUrl(baseUrl);
        profile.setModel(model);
        profile.setTimeout(timeout);
        profile.setIsDefault(true);
        llmProfileDbService.save(profile);
        log.info("Migrated legacy LLM config to llm_profiles table (model={})", model);
    }

    private Config loadConfig() {
        try {
            return configService.load();
        } catch (Exception e) {
            return null;
        }
    }

    private LlmProfileEntity resolveDefaultProfile() {
        LlmProfileEntity profile = llmProfileDbService.findDefault();
        if (profile == null) {
            List<LlmProfileEntity> all = llmProfileDbService.listAllOrdered();
            if (!all.isEmpty()) {
                profile = all.get(0);
            }
        }
        return profile;
    }

    private LlmProfileEntity resolveProfile(String name) {
        if (name == null || name.isEmpty()) return resolveDefaultProfile();
        LlmProfileEntity profile = llmProfileDbService.findByName(name);
        if (profile == null) return resolveDefaultProfile();
        return profile;
    }

    public String resolveApiKey() {
        LlmProfileEntity p = resolveDefaultProfile();
        if (p != null) return p.getApiKey();
        Config cfg = loadConfig();
        if (cfg != null) {
            String key = cfg.getLlmApiKey();
            if (key != null && !key.isEmpty()) return key;
        }
        String key = System.getenv("LLM_API_KEY");
        if (key != null && !key.isEmpty()) return key;
        key = System.getenv("DEEPSEEK_API_KEY");
        if (key != null && !key.isEmpty()) return key;
        return "";
    }

    public String resolveBaseUrl() {
        LlmProfileEntity p = resolveDefaultProfile();
        if (p != null) return p.getBaseUrl();
        Config cfg = loadConfig();
        if (cfg != null) {
            String url = cfg.getLlmBaseUrl();
            if (url != null && !url.isEmpty()) return url;
        }
        String envUrl = System.getenv("LLM_BASE_URL");
        if (envUrl != null && !envUrl.isEmpty()) return envUrl;
        return "https://api.deepseek.com";
    }

    public String resolveModel() {
        LlmProfileEntity p = resolveDefaultProfile();
        if (p != null) return p.getModel();
        Config cfg = loadConfig();
        if (cfg != null) {
            String model = cfg.getLlmModel();
            if (model != null && !model.isEmpty()) return model;
        }
        return "deepseek-chat";
    }

    public int resolveTimeout() {
        LlmProfileEntity p = resolveDefaultProfile();
        if (p != null && p.getTimeout() != null && p.getTimeout() > 0) return p.getTimeout();
        Config cfg = loadConfig();
        if (cfg != null && cfg.getLlmTimeout() > 0) return cfg.getLlmTimeout();
        return 60;
    }

    public boolean isAvailable() {
        String apiKey = resolveApiKey();
        return apiKey != null && !apiKey.isEmpty();
    }

    public RestClient.Builder buildRestClientBuilder() {
        return llmRestClientBuilder;
    }

    // ========== Profile-based resolution (for multi-model) ==========

    public String resolveApiKey(String profileName) {
        LlmProfileEntity p = resolveProfile(profileName);
        if (p != null) {
            String key = p.getApiKey();
            if (key != null && !key.isEmpty()) return key;
        }
        return resolveApiKey();
    }

    public String resolveBaseUrl(String profileName) {
        LlmProfileEntity p = resolveProfile(profileName);
        if (p != null) {
            String url = p.getBaseUrl();
            if (url != null && !url.isEmpty()) return url;
        }
        return resolveBaseUrl();
    }

    public String resolveModel(String profileName) {
        LlmProfileEntity p = resolveProfile(profileName);
        if (p != null) {
            String m = p.getModel();
            if (m != null && !m.isEmpty()) return m;
        }
        return resolveModel();
    }

    public int resolveTimeout(String profileName) {
        LlmProfileEntity p = resolveProfile(profileName);
        if (p != null && p.getTimeout() != null && p.getTimeout() > 0) return p.getTimeout();
        return resolveTimeout();
    }

    public List<LlmProfileEntity> getAllProfiles() {
        return llmProfileDbService.listAllOrdered();
    }

    public LlmProfileEntity getDefaultProfile() {
        return resolveDefaultProfile();
    }

    public LlmProfileEntity getProfileByName(String name) {
        return llmProfileDbService.findByName(name);
    }
}
