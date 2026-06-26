package com.laoqi.assistant.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.model.ReminderData.Reminder;
import com.laoqi.assistant.util.TimeUtil;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final ReportService reportService;
    private final LogService logService;
    private final ReminderService reminderService;
    private final KnowledgeBaseService kbService;

    public SchedulerService(ReportService reportService,
                            LogService logService,
                            ReminderService reminderService,
                            KnowledgeBaseService kbService) {
        this.reportService = reportService;
        this.logService = logService;
        this.reminderService = reminderService;
        this.kbService = kbService;
    }

    @Scheduled(cron = "0 0 9 * * ?", zone = "Asia/Shanghai")
    public void morningReport() {
        log.info("[{}] ⏰ 定时任务：生成综合日报", TimeUtil.nowStr());
        List<KnowledgeBaseEntity> kbs = kbService.getAll();
        for (KnowledgeBaseEntity kb : kbs) {
            try {
                // 检查是否启用了自动日报（默认开启）
                boolean autoReport = kb.getAutoReport() == null || kb.getAutoReport() == 1;
                if (!autoReport) {
                    log.info("[{}] ⏰ 知识库「{}」已关闭自动日报，跳过", TimeUtil.nowStr(), kb.getName());
                    continue;
                }
                log.info("[{}] ⏰ 为知识库「{}」生成日报", TimeUtil.nowStr(), kb.getName());
                reportService.generateAndPush(kb.getId());
            } catch (Exception e) {
                log.error("[{}] ⏰ 知识库「{}」日报生成失败: {}", TimeUtil.nowStr(), kb.getName(), e.getMessage());
            }
        }
    }

    @Scheduled(cron = "0 * * * * ?", zone = "Asia/Shanghai")
    public void checkDynamicReminders() {
        try {
            log.info("[{}] ⏰ 检查动态提醒...", TimeUtil.nowStr());
            List<Reminder> dueReminders = reminderService.getDueReminders();
            if (dueReminders.isEmpty()) {
//                log.info("[{}] ⏰ 没有到期的提醒", TimeUtil.nowStr());
            } else {
                log.info("[{}] ⏰ 找到 {} 个到期的提醒", TimeUtil.nowStr(), dueReminders.size());
                for (Reminder r : dueReminders) {
                    String reminderName = r.name != null ? r.name : "(未命名提醒)";
                    log.info("[{}] ⏰ 触发动态提醒：{}", TimeUtil.nowStr(), reminderName);
                    reminderService.triggerReminder(r);
                    logService.add("定时提醒", "成功", reminderName);
                }
            }
        } catch (Exception e) {
            log.error("[提醒] 检查动态提醒失败: {}", e.getMessage(), e);
        }
    }
}
