package com.laoqi.assistant.config;

import com.laoqi.assistant.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 端口健康检测 — v0.4.0 简化版。
 * 只检测 Ollama 服务（语义检索用），不再需要 opencode serve。
 */
@Component
public class PortHealthChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PortHealthChecker.class);

    private static final String BORDER = "=".repeat(60);
    private static final String OLLAMA_HOST = "127.0.0.1";
    private static final int OLLAMA_PORT = 11434;

    public static volatile boolean ollamaRunning = false;

    private final AppConfig appConfig;
    private final LogService logService;

    private volatile boolean started = false;

    public PortHealthChecker(AppConfig appConfig, LogService logService) {
        this.appConfig = appConfig;
        this.logService = logService;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkOllama(true);
        logStatus();
    }

    private void logStatus() {
        logService.add("端口检测", ollamaRunning ? "成功" : "警告",
                "Ollama " + (ollamaRunning ? "已启动" : "未启动，端口: " + getOllamaPort()));
    }

    private int getOllamaPort() {
        String url = appConfig.getOllamaBaseUrl();
        try {
            java.net.URI uri = new java.net.URI(url);
            if (uri.getPort() > 0) return uri.getPort();
        } catch (Exception ignored) {}
        return OLLAMA_PORT;
    }

    /**
     * Only used by templates — indicates semantic search is available
     */
    public static boolean isOllamaAvailable() {
        return ollamaRunning;
    }

    private void checkOllama(boolean startup) {
        int port = getOllamaPort();
        boolean previousStatus = ollamaRunning;

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(OLLAMA_HOST, port), 2000);
            if (!previousStatus) {
                ollamaRunning = true;
                log.info("Ollama 已恢复 ({}:{})", OLLAMA_HOST, port);
            }
        } catch (Exception e) {
            if (previousStatus) {
                ollamaRunning = false;
                log.warn("Ollama 连接断开 ({}:{})", OLLAMA_HOST, port);
                logService.add("端口检测", "警告", "Ollama 连接断开，端口: " + port);
            }
            if (startup) {
                log.warn("");
                log.warn(BORDER);
                log.warn("  ⚠  Ollama 未启动！语义检索不可用");
                log.warn("  端口: {}", port);
                log.warn(BORDER);
                log.warn("");
            }
        }
    }
}