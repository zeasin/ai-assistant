package com.laoqi.assistant.service;

import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.model.ReminderData.Reminder;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final ReportService reportService;
    private final FeishuService feishuService;
    private final LogService logService;
    private final MediaDataCollectorService mediaDataCollectorService;
    private final ConfigService configService;
    private final ReminderService reminderService;
    private final OperationsService operationsService;
    private final CustomerService customerService;
    private final com.laoqi.assistant.controller.WorkReportController workReportController;

    public SchedulerService(ReportService reportService, FeishuService feishuService,
                            LogService logService,
                            MediaDataCollectorService mediaDataCollectorService,
                            ConfigService configService,
                            ReminderService reminderService,
                            OperationsService operationsService,
                            CustomerService customerService,
                            com.laoqi.assistant.controller.WorkReportController workReportController) {
        this.reportService = reportService;
        this.feishuService = feishuService;
        this.logService = logService;
        this.mediaDataCollectorService = mediaDataCollectorService;
        this.configService = configService;
        this.reminderService = reminderService;
        this.operationsService = operationsService;
        this.customerService = customerService;
        this.workReportController = workReportController;
    }

    @Scheduled(cron = "0 30 9 * * ?", zone = "Asia/Shanghai")
    public void morningReport() {
        log.info("[{}] ⏰ 定时任务：生成综合日报", TimeUtil.nowStr());
        reportService.generateAndPush();
    }

    @Scheduled(cron = "0 30 11 * * ?", zone = "Asia/Shanghai")
    public void morningOperationsAnalysis() {
        log.info("[{}] ⏰ 定时任务：生成运营AI分析", TimeUtil.nowStr());
        try {
            String result = operationsService.aiAnalyze(true);
            if (result != null && !result.isEmpty()) {
                logService.add("运营AI分析", "成功", "定时任务自动生成");
            } else {
                logService.add("运营AI分析", "失败", "AI返回为空");
            }
        } catch (Exception e) {
            log.error("运营AI分析定时任务失败", e);
            logService.add("运营AI分析", "失败", e.getMessage());
        }
    }

    @Scheduled(cron = "0 5 8 * * ?", zone = "Asia/Shanghai")
    public void morningCustomerAnalysis() {
        log.info("[{}] ⏰ 定时任务：生成客户AI分析", TimeUtil.nowStr());
        try {
            String result = customerService.aiAnalyze(true);
            if (result != null && !result.isEmpty()) {
                logService.add("客户AI分析", "成功", "定时任务自动生成");
            } else {
                logService.add("客户AI分析", "失败", "AI返回为空");
            }
        } catch (Exception e) {
            log.error("客户AI分析定时任务失败", e);
            logService.add("客户AI分析", "失败", e.getMessage());
        }
    }

    @Scheduled(cron = "0 10 8 * * ?", zone = "Asia/Shanghai")
    public void morningWorkReportAnalysis() {
        log.info("[{}] ⏰ 定时任务：生成工作AI分析", TimeUtil.nowStr());
        try {
            String result = workReportController.generateAndSaveAnalysis();
            if (result != null && !result.isEmpty()) {
                logService.add("工作AI分析", "成功", "定时任务自动生成");
            } else {
                logService.add("工作AI分析", "失败", "AI返回为空");
            }
        } catch (Exception e) {
            log.error("工作AI分析定时任务失败", e);
            logService.add("工作AI分析", "失败", e.getMessage());
        }
    }

    @Scheduled(cron = "0 * * * * ?", zone = "Asia/Shanghai")
    public void collectPlatformData() {
        String now = LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
//        log.debug("[{}] ⏰ 每分钟检查：是否需要执行CSDN数据采集", TimeUtil.nowStr());
        
        Config config = configService.load();
        Boolean enabled = config.isMediaCollectEnabled();
        String expected = config.getMediaCollectTime();
        
//        log.debug("[{}]  配置检查：enabled={}, 期望时间={}, 当前时间={}", TimeUtil.nowStr(), enabled, expected, now);
        
        if (!Boolean.TRUE.equals(enabled)) {
            log.debug("[{}]  跳过：采集开关未开启", TimeUtil.nowStr());
            return;
        }
        if (expected == null || expected.isBlank()) {
            log.debug("[{}]  跳过：采集时间未配置", TimeUtil.nowStr());
            return;
        }
        if (!expected.equals(now)) {
//            log.debug("[{}]  跳过：当前时间 {} != 配置时间 {}", TimeUtil.nowStr(), now, expected);
            return;
        }
        
        log.info("[{}] ⏰ 定时任务：CSDN数据采集 开始执行！", TimeUtil.nowStr());
        logService.add("定时任务", "开始", "CSDN数据采集（定时触发）");
        mediaDataCollectorService.collect();
    }

//    @Scheduled(cron = "0 57 8 * * ?", zone = "Asia/Shanghai")
//    public void wechatDataRequest() {
//        log.info("[{}] ⏰ 定时任务：公众号数据采集请求", TimeUtil.nowStr());
//        mediaDataCollectorService.sendWechatDataRequest();
//    }

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
