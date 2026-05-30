package com.laoqi.assistant.config;

import com.laoqi.assistant.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;

@Component
public class PortHealthChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PortHealthChecker.class);

    private static final String BORDER = "=".repeat(60);

    public static volatile boolean notesRunning = false;
    public static volatile boolean codeRunning = false;

    private final AppConfig appConfig;
    private final LogService logService;

    private volatile boolean started = false;

    public PortHealthChecker(AppConfig appConfig, LogService logService) {
        this.appConfig = appConfig;
        this.logService = logService;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkPort(appConfig.getNotesPort(), "笔记库", true);
        checkPort(appConfig.getCodePort(), "Java项目", true);
        started = true;
        logStartupStatus();
    }

    private void logStartupStatus() {
        logService.add("端口检测(笔记库)", notesRunning ? "成功" : "警告",
                "服务(笔记库) " + (notesRunning ? "已启动" : "未启动，端口: " + appConfig.getNotesPort()));
        logService.add("端口检测(Java项目)", codeRunning ? "成功" : "警告",
                "服务(Java项目) " + (codeRunning ? "已启动" : "未启动，端口: " + appConfig.getCodePort()));
    }

    @Scheduled(fixedRate = 30_000)
    public void scheduledCheck() {
        checkPort(appConfig.getNotesPort(), "笔记库", false);
        checkPort(appConfig.getCodePort(), "Java项目", false);
    }

    private void checkPort(int port, String label, boolean startup) {
        String host = "127.0.0.1";
        boolean previousStatus = getRunningStatus(port);

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            if (!previousStatus) {
                setRunningStatus(port, true);
                log.info("服务({}) 已恢复 ({}:{})", label, host, port);
                if (!startup) {
                    logService.add("端口检测(" + label + ")", "成功",
                            "服务(" + label + ") 已启动");
                }
            }
        } catch (Exception e) {
            if (previousStatus) {
                setRunningStatus(port, false);
                log.warn("服务({}) 连接断开 ({}:{})", label, host, port);
                logService.add("端口检测(" + label + ")", "警告",
                        "服务(" + label + ") 连接断开，端口: " + port);
            }
            if (startup) {
                log.warn("");
                log.warn(BORDER);
                log.warn("  ⚠  服务({}) 未启动！", label);
                log.warn("  端口: {}", port);
                log.warn(BORDER);
                log.warn("");
            }
        }
    }

    private boolean getRunningStatus(int port) {
        if (port == appConfig.getNotesPort()) return notesRunning;
        if (port == appConfig.getCodePort()) return codeRunning;
        return false;
    }

    private void setRunningStatus(int port, boolean status) {
        if (port == appConfig.getNotesPort()) notesRunning = status;
        else if (port == appConfig.getCodePort()) codeRunning = status;
    }
}
