package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(StartupReportGenerator.class);

    private final ReportService reportService;
    private final OpenCodeService openCodeService;
    private final LogService logService;

    public StartupReportGenerator(ReportService reportService, OpenCodeService openCodeService,
                                   LogService logService) {
        this.reportService = reportService;
        this.openCodeService = openCodeService;
        this.logService = logService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(8000);

                if (!openCodeService.isHealthy()) {
                    log.warn("opencode serve 未就绪，跳过启动日报生成");
                    logService.add("启动日报", "跳过", "opencode serve 未就绪");
                    return;
                }

                log.info("服务已就绪，开始生成今日综合日报");
                reportService.generateAndPush();
            } catch (Exception e) {
                log.error("启动日报生成失败", e);
                logService.add("启动日报", "失败", e.getMessage());
            }
        }, "startup-report").start();
    }
}
