package com.laoqi.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
    org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class
})
@EnableScheduling
public class AssistantApplication {

    static {
        // 静默 protobuf 兼容性警告（OpenAI SDK 依赖的旧版 protobuf gencode）
        System.setProperty("com.google.protobuf.use_unsafe_pre22_gencode", "true");
    }

    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}