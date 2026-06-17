package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.LlmProfileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final LlmConfigResolver configResolver;

    private volatile ChatClient defaultClient;
    private volatile String cachedConfigKey = "";
    private final ConcurrentHashMap<String, ChatClient> modelClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> modelConfigKeys = new ConcurrentHashMap<>();

    public LlmService(LlmConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    public boolean isAvailable() {
        return configResolver.isAvailable();
    }

    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, null);
    }

    public String chat(String systemPrompt, String userMessage, String modelName) {
        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写，或设置环境变量 LLM_API_KEY");
        }
        return client.prompt()
                .system(systemPrompt != null ? systemPrompt : "")
                .user(userMessage)
                .call()
                .content();
    }

    public String chat(List<Map<String, String>> messages) {
        return chat(messages, null);
    }

    public String chat(List<Map<String, String>> messages, String modelName) {
        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        List<Message> springMessages = new ArrayList<>();
        for (Map<String, String> msg : messages) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            switch (role) {
                case "system" -> springMessages.add(new SystemMessage(content));
                case "user" -> springMessages.add(new UserMessage(content));
                case "assistant" -> springMessages.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
                default -> springMessages.add(new UserMessage(content));
            }
        }

        return client.prompt()
                .messages(springMessages)
                .call()
                .content();
    }

    public String chatWithImage(String systemPrompt, String userMessage, String base64Image, String imageType) {
        return chatWithImage(systemPrompt, userMessage, base64Image, imageType, null);
    }

    public String chatWithImage(String systemPrompt, String userMessage, String base64Image, String imageType, String modelName) {
        ChatClient client = getOrCreateClient(modelName);
        if (client == null) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写");
        }

        String mediaType = (imageType != null && !imageType.isEmpty()) ? imageType : "image/jpeg";
        byte[] imageBytes = Base64.getDecoder().decode(base64Image);

        return client.prompt()
                .system(systemPrompt != null ? systemPrompt : "")
                .user(u -> u.text(userMessage)
                        .media(MimeTypeUtils.parseMimeType(mediaType),
                                new ByteArrayResource(imageBytes)))
                .call()
                .content();
    }

    private ChatClient getOrCreateClient(String modelName) {
        if (!isAvailable()) return null;

        if (modelName == null || modelName.isEmpty()) {
            String currentKey = buildConfigKey();
            if (defaultClient == null || !currentKey.equals(cachedConfigKey)) {
                defaultClient = buildDefaultClient();
                cachedConfigKey = currentKey;
                log.info("LlmService ChatClient 已重建 (model={})", configResolver.resolveModel());
            }
            return defaultClient;
        }

        String currentKey = buildConfigKey(modelName);
        String cachedKey = modelConfigKeys.get(modelName);
        if (cachedKey == null || !cachedKey.equals(currentKey)) {
            LlmProfileEntity profile = configResolver.getProfileByName(modelName);
            if (profile != null) {
                ChatClient client = buildClient(profile);
                modelClients.put(modelName, client);
                modelConfigKeys.put(modelName, currentKey);
                log.info("LlmService ChatClient 已重建 (model={})", modelName);
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

    private ChatClient buildDefaultClient() {
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
                .defaultSystem("你是一个有用的AI助手。")
                .build();
    }

    private ChatClient buildClient(LlmProfileEntity profile) {
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
                .defaultSystem("你是一个有用的AI助手。")
                .build();
    }
}
