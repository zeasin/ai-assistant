package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.LlmProfileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisService.class);

    private final LlmConfigResolver configResolver;
    private final NoteTools noteTools;

    private volatile ChatClient defaultClient;
    private volatile String cachedConfigKey = "";
    private final ConcurrentHashMap<String, ChatClient> modelClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> modelConfigKeys = new ConcurrentHashMap<>();

    public AgentAnalysisService(LlmConfigResolver configResolver, NoteTools noteTools) {
        this.configResolver = configResolver;
        this.noteTools = noteTools;
    }

    public boolean isAvailable() {
        return configResolver.isAvailable();
    }

    public String analyze(Path scopeDir, String prompt, String systemPrompt) {
        return analyze(scopeDir, prompt, systemPrompt, null);
    }

    public String analyze(Path scopeDir, String prompt, String systemPrompt, String modelName) {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("ChatClient 初始化失败");
        }

        NoteTools.setScope(scopeDir);
        try {
            String userMessage = (prompt != null && !prompt.isBlank())
                    ? prompt
                    : "请分析此目录的内容，给出洞察和建议";

            log.info("[AgentAnalysis] scope={}, prompt长度={}", scopeDir, userMessage.length());

            String result = client.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .content();

            log.info("[AgentAnalysis] 分析完成，结果长度={}", result != null ? result.length() : 0);
            return result != null ? result : "（AI 未返回回复）";
        } finally {
            NoteTools.clearScope();
        }
    }

    private ChatClient getOrCreateClient(String modelName) {
        if (!isAvailable()) return null;

        if (modelName == null || modelName.isEmpty()) {
            String currentKey = buildConfigKey();
            if (defaultClient == null || !currentKey.equals(cachedConfigKey)) {
                defaultClient = createDefaultClient();
                cachedConfigKey = currentKey;
                log.info("AgentAnalysis ChatClient 已重建 (default)");
            }
            return defaultClient;
        }

        String currentKey = buildConfigKey(modelName);
        String cachedKey = modelConfigKeys.get(modelName);
        if (cachedKey == null || !cachedKey.equals(currentKey)) {
            LlmProfileEntity profile = configResolver.getProfileByName(modelName);
            if (profile != null) {
                ChatClient client = createClient(profile);
                modelClients.put(modelName, client);
                modelConfigKeys.put(modelName, currentKey);
                log.info("AgentAnalysis ChatClient 已重建 (model={})", modelName);
            } else {
                return defaultClient;
            }
        }
        return modelClients.get(modelName);
    }

    private String buildConfigKey() {
        return configResolver.resolveBaseUrl() + "|"
                + configResolver.resolveModel() + "|"
                + configResolver.resolveApiKey().hashCode();
    }

    private String buildConfigKey(String modelName) {
        return configResolver.resolveBaseUrl(modelName) + "|"
                + configResolver.resolveModel(modelName) + "|"
                + configResolver.resolveApiKey(modelName).hashCode();
    }

    private ChatClient createDefaultClient() {
        String apiKey = configResolver.resolveApiKey();
        String baseUrl = configResolver.resolveBaseUrl();
        String model = configResolver.resolveModel();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(configResolver.buildRestClientBuilder())
                .build();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .build();

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(options)
                .build();

        return ChatClient.builder(chatModel)
                .defaultTools(noteTools)
                .build();
    }

    private ChatClient createClient(LlmProfileEntity profile) {
        String apiKey = profile.getApiKey();
        String baseUrl = profile.getBaseUrl();
        String model = profile.getModel();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(configResolver.buildRestClientBuilder())
                .build();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .build();

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(options)
                .build();

        return ChatClient.builder(chatModel)
                .defaultTools(noteTools)
                .build();
    }
}
