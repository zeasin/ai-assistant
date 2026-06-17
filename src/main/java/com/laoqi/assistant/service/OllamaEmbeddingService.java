package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Ollama Embedding 向量化服务。
 * v0.4.0 基于 Spring AI 2.0 EmbeddingModel API，替代 LangChain4j。
 */
@Service
public class OllamaEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingService.class);

    private final AppConfig appConfig;
    private final LogService logService;
    private EmbeddingModel embeddingModel;
    private boolean available;

    public OllamaEmbeddingService(AppConfig appConfig, LogService logService) {
        this.appConfig = appConfig;
        this.logService = logService;
    }

    @PostConstruct
    public void init() {
        checkHealth();
    }

    public void checkHealth() {
        String baseUrl = appConfig.getOllamaBaseUrl();
        String modelName = appConfig.getOllamaModel();

        try {
            URI uri = URI.create(baseUrl + "/api/tags");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("⚠️ Ollama 未运行 ({}), 语义检索不可用", baseUrl);
                logService.add("Ollama", "不可用", "连接失败: HTTP " + code);
                this.available = false;
                return;
            }

            String body = new String(conn.getInputStream().readAllBytes());
            if (!body.contains("\"name\":\"" + modelName + "\"") && !body.contains("\"name\":\"" + modelName + ":")) {
                log.warn("⚠️ Embedding 模型 '{}' 未安装, 请执行: ollama pull {}", modelName, modelName);
                logService.add("Ollama", "模型未安装", "请执行: ollama pull " + modelName);
                this.available = false;
                return;
            }

            // 使用 Spring AI OllamaEmbeddingModel
            this.embeddingModel = OllamaEmbeddingModel.builder()
                    .ollamaApi(OllamaApi.builder()
                            .baseUrl(baseUrl)
                            .build())
                    .options(OllamaEmbeddingOptions.builder()
                            .model(modelName)
                            .build())
                    .build();

            this.available = true;
            log.info("✅ Ollama 语义检索已就绪 (model={})", modelName);
            logService.add("Ollama", "就绪", "model=" + modelName);

        } catch (Exception e) {
            log.warn("⚠️ Ollama 连接失败 ({}): {}, 语义检索不可用", baseUrl, e.getMessage());
            logService.add("Ollama", "不可用", e.getMessage());
            this.available = false;
        }
    }

    /**
     * 将文本转换为 float[] 向量。
     * 在 Spring AI 中，embed(text) 直接返回 float[]，无需包装类。
     */
    public float[] embed(String text) {
        if (!available || embeddingModel == null) {
            return null;
        }
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.warn("Embedding 生成失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean isAvailable() {
        return available;
    }
}