package com.laoqi.assistant.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.laoqi.assistant.model.ReminderData.Reminder;
import com.laoqi.assistant.util.TimeUtil;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final ReportService reportService;
    private final FeishuService feishuService;
    private final LogService logService;
    private final ReminderService reminderService;

    public SchedulerService(ReportService reportService, FeishuService feishuService,
                            LogService logService,
                            ReminderService reminderService) {
        this.reportService = reportService;
        this.feishuService = feishuService;
        this.logService = logService;
        this.reminderService = reminderService;
    }

    @Scheduled(cron = "0 30 10 * * ?", zone = "Asia/Shanghai")
    public void morningReport() {
        log.info("[{}] ⏰ 定时任务：生成综合日报", TimeUtil.nowStr());
        reportService.generateAndPush();
    }

    @Scheduled(cron = "0 0 18 * * ?", zone = "Asia/Shanghai")
    public void dailyReportReminder() {
        log.info("[{}] ⏰ 定时任务：下班日报提醒", TimeUtil.nowStr());
        boolean ok = feishuService.dailyReportReminder();
        logService.add("下班提醒", ok ? "成功" : "失败");
    }

    @Scheduled(cron = "0 0 9 ? * TUE", zone = "Asia/Shanghai")
    public void articleTuesday() {
        log.info("[{}] ⏰ 定时任务：周二发文提醒", TimeUtil.nowStr());
        boolean ok = feishuService.articleReminder("码农老齐", "周二");
        logService.add("周二发文提醒", ok ? "成功" : "失败");
    }

    @Scheduled(cron = "0 0 9 ? * THU", zone = "Asia/Shanghai")
    public void articleThursday() {
        log.info("[{}] ⏰ 定时任务：周四发文提醒", TimeUtil.nowStr());
        boolean ok = feishuService.articleReminder("启航电商ERP", "周四");
        logService.add("周四发文提醒", ok ? "成功" : "失败");
    }

    @Scheduled(cron = "0 * * * * ?", zone = "Asia/Shanghai")
    public void checkDynamicReminders() {
        try {
            log.info("[{}] ⏰ 检查动态提醒...", TimeUtil.nowStr());
            List<Reminder> dueReminders = reminderService.getDueReminders();
            if (dueReminders.isEmpty()) {
                log.info("[{}] ⏰ 没有到期的提醒", TimeUtil.nowStr());
            } else {
                log.info("[{}] ⏰ 找到 {} 个到期的提醒", TimeUtil.nowStr(), dueReminders.size());
                for (Reminder r : dueReminders) {
                    log.info("[{}] ⏰ 触发动态提醒：{}", TimeUtil.nowStr(), r.name);
                    reminderService.triggerReminder(r);
                    logService.add("定时提醒", "成功", r.name);
                }
            }
        } catch (Exception e) {
            log.error("[提醒] 检查动态提醒失败: {}", e.getMessage(), e);
        }
    }
}
