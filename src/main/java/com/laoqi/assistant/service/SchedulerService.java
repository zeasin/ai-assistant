package com.laoqi.assistant.service;

import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final ReportService reportService;
    private final FeishuService feishuService;
    private final LogService logService;

    public SchedulerService(ReportService reportService, FeishuService feishuService,
                            LogService logService) {
        this.reportService = reportService;
        this.feishuService = feishuService;
        this.logService = logService;
    }

    @Scheduled(cron = "0 0 9 * * ?", zone = "Asia/Shanghai")
    public void morningReport() {
        log.info("[{}] ⏰ 定时任务：生成综合日报", TimeUtil.nowStr());
        reportService.generateAndPush();
    }

    @Scheduled(cron = "0 0 18 * * ?", zone = "Asia/Shanghai")
    public void dailyReportReminder() {
        log.info("[{}] ⏰ 定时任务：下班日报提醒", TimeUtil.nowStr());
        feishuService.dailyReportReminder();
        logService.add("下班提醒", "成功");
    }

    @Scheduled(cron = "0 0 9 ? * TUE", zone = "Asia/Shanghai")
    public void articleTuesday() {
        log.info("[{}] ⏰ 定时任务：周二发文提醒", TimeUtil.nowStr());
        feishuService.articleReminder("码农老齐", "周二");
        logService.add("周二发文提醒", "成功");
    }

    @Scheduled(cron = "0 0 9 ? * THU", zone = "Asia/Shanghai")
    public void articleThursday() {
        log.info("[{}] ⏰ 定时任务：周四发文提醒", TimeUtil.nowStr());
        feishuService.articleReminder("启航电商ERP", "周四");
        logService.add("周四发文提醒", "成功");
    }
}
