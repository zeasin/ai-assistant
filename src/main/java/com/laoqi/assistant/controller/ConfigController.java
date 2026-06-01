package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequestMapping("/config")
public class ConfigController {

    private final ConfigService configService;
    private final LogService logService;
    private final FeishuService feishuService;
    private final ReportService reportService;

    public ConfigController(ConfigService configService, LogService logService,
                             FeishuService feishuService, ReportService reportService) {
        this.configService = configService;
        this.logService = logService;
        this.feishuService = feishuService;
        this.reportService = reportService;
    }

    @GetMapping
    public String configPage(Model model) {
        model.addAttribute("scheduler_jobs", List.of(
                Map.of("id", "daily_report", "time", "每天 18:00", "desc", "下班日报提醒"),
                Map.of("id", "article_tue", "time", "每周二 09:00", "desc", "码农老齐发文提醒"),
                Map.of("id", "article_thu", "time", "每周四 09:00", "desc", "启航电商ERP发文提醒")
        ));
        model.addAttribute("crontab", "（Java 版使用 Spring @Scheduled，无 crontab）");
        model.addAttribute("launchagent", "（Java 版使用 Spring @Scheduled，无 LaunchAgent）");
        return "config";
    }

    @PostMapping("/trigger")
    @ResponseBody
    public Map<String, Object> triggerJob(@RequestParam String job) {
        try {
            switch (job) {
                case "daily_report" -> feishuService.dailyReportReminder();
                case "article_tue" -> feishuService.articleReminder("码农老齐", "周二");
                case "article_thu" -> feishuService.articleReminder("启航电商ERP", "周四");
                case "generate_report" -> reportService.generateAndPush();
                default -> throw new IllegalArgumentException("未知任务: " + job);
            }
            logService.add("手动触发", "成功", job);
            return Map.of("ok", true);
        } catch (Exception e) {
            logService.add("手动触发", "失败", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}