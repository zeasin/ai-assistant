package com.laoqi.assistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * LLM HTTP 客户端配置。
 * <p>
 * 使用 JDK HttpClient（而非 Netty），避免 io.netty.handler.timeout.ReadTimeoutException。
 * 超时时间从 app.llm-timeout 读取（默认 600 秒），支持大上下文请求。
 */
@Configuration
public class LlmRestClientConfig {

    @Value("${app.llm-timeout:600}")
    private int readTimeoutSeconds;

    @Bean
    public RestClient.Builder llmRestClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        return RestClient.builder()
                .requestFactory(requestFactory);
    }
}
