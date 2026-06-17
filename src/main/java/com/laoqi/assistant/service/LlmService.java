package com.laoqi.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 直连 LLM API — v3.0 基于 Spring AI 2.0 ChatClient。
 *
 * 底层使用 Spring AI 的 OpenAiChatModel / OpenAiApi，
 * 可对接任意 OpenAI 兼容 API：
 *   - DeepSeek     → https://api.deepseek.com
 *   - 商汤 SenseNova → https://token.sensenova.cn/v1
 *   - 智谱 GLM     → https://open.bigmodel.cn/api/paas/v4
 *
 * 在 /config 页面配置 baseUrl + apiKey + model 即可切换。
 *
 * 方法签名与之前一致（chat/chatWithImage/isAvailable），调用方无需改动。
 * config 来源统一委托给 LlmConfigResolver。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final LlmConfigResolver configResolver;
    private final Object configLock = new Object();

    // 懒初始化，config 变更时重建
    private volatile ChatClient chatClient;
    private volatile String cachedConfigKey = "";

    public LlmService(LlmConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    // ========== 公共 API（保持签名不变） ==========

    /**
     * 检查 LLM 直连是否可用（API Key 已配置）
     */
    public boolean isAvailable() {
        return configResolver.isAvailable();
    }

    /**
     * 同步调用 LLM（system + user），返回完整回复文本
     */
    public String chat(String systemPrompt, String userMessage) {
        ChatClient client = getOrCreateClient();
        if (client == null) {
            throw new IllegalStateException("LLM API Key 未配置，请在配置页填写，或设置环境变量 LLM_API_KEY");
        }
        return client.prompt()
                .system(systemPrompt != null ? systemPrompt : "")
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * 同步调用 LLM（多轮消息列表），返回完整回复文本
     */
    public String chat(List<Map<String, String>> messages) {
        ChatClient client = getOrCreateClient();
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

    /**
     * 同步调用 LLM（图片识别），支持多模态 vision API
     */
    public String chatWithImage(String systemPrompt, String userMessage, String base64Image, String imageType) {
        ChatClient client = getOrCreateClient();
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

    // ========== 内部方法（ChatClient 懒初始化 + 自动重建） ==========

    private ChatClient getOrCreateClient() {
        if (!isAvailable()) return null;

        String currentKey = buildConfigKey();
        if (chatClient == null || !currentKey.equals(cachedConfigKey)) {
            synchronized (configLock) {
                String keyAfterLock = buildConfigKey();
                if (chatClient == null || !keyAfterLock.equals(cachedConfigKey)) {
                    chatClient = createChatClient();
                    cachedConfigKey = keyAfterLock;
                    log.info("ChatClient 已重建 (model={})", configResolver.resolveModel());
                }
            }
        }
        return chatClient;
    }

    private String buildConfigKey() {
        return configResolver.resolveBaseUrl() + "|"
                + configResolver.resolveModel() + "|"
                + configResolver.resolveApiKey().hashCode();
    }

    private ChatClient createChatClient() {
        String apiKey = configResolver.resolveApiKey();
        String baseUrl = configResolver.resolveBaseUrl();
        String model = configResolver.resolveModel();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // Spring AI OpenAiApi — 通用 OpenAI 兼容客户端
        // 任何 OpenAI 兼容 API（DeepSeek/商汤/智谱等）只需改 baseUrl + model 即可
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(configResolver.buildRestClientBuilder())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个有用的AI助手。")
                .build();
    }
}
