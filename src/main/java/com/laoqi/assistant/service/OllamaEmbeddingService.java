package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@Service
public class OllamaEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingService.class);

    private final AppConfig appConfig;
    private final LogService logService;
    private OllamaEmbeddingModel model;
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
                log.warn("⚠️ Ollama 未运行 ({}), 语义检索不可用，将回退最近3轮", baseUrl);
                logService.add("Ollama", "不可用", "连接失败: HTTP " + code);
                this.available = false;
                return;
            }

            String body = new String(conn.getInputStream().readAllBytes());
            if (!body.contains("\"name\":\"" + modelName + "\"")) {
                log.warn("⚠️ Embedding 模型 '{}' 未安装, 请执行: ollama pull {}", modelName, modelName);
                logService.add("Ollama", "模型未安装", "请执行: ollama pull " + modelName);
                this.available = false;
                return;
            }

            this.model = OllamaEmbeddingModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(appConfig.getOllamaTimeoutSeconds()))
                    .build();

            this.available = true;
            log.info("✅ Ollama 语义检索已就绪 (model={})", modelName);
            logService.add("Ollama", "就绪", "model=" + modelName);

        } catch (Exception e) {
            log.warn("⚠️ Ollama 连接失败 ({}): {}, 语义检索不可用，将回退最近3轮", baseUrl, e.getMessage());
            logService.add("Ollama", "不可用", e.getMessage());
            this.available = false;
        }
    }

    public Optional<Embedding> embed(String text) {
        if (!available || model == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(model.embed(text).content());
        } catch (Exception e) {
            log.warn("Embedding 生成失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isAvailable() {
        return available;
    }
}
