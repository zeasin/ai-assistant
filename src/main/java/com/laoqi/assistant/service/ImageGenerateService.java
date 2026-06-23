package com.laoqi.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class ImageGenerateService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerateService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // SenseNova 测试配置
    private static final String API_KEY = "sk-mcMWLtBbj4KmnLh3am9O1XpVQozSDxKL";
    private static final String BASE_URL = "https://token.sensenova.cn/v1";

    public String generate(String prompt, String size, String model) {
        String url = BASE_URL + "/images/generations";
        String useModel = (model != null && !model.isBlank()) ? model : "sensenova-u1-fast";
        String useSize = (size != null && !size.isBlank()) ? size : "2048x2048";

        try {
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "model", useModel,
                    "prompt", prompt,
                    "n", 1,
                    "size", useSize
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[ImageGen] API 错误: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("图片生成失败: HTTP " + response.statusCode());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && data.size() > 0) {
                JsonNode first = data.get(0);
                if (first.has("url")) {
                    return first.get("url").asText();
                }
                if (first.has("b64_json")) {
                    return "data:image/png;base64," + first.get("b64_json").asText();
                }
            }

            throw new RuntimeException("图片生成失败: 响应格式异常");
        } catch (Exception e) {
            log.error("[ImageGen] 生成失败", e);
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }
    }
}
