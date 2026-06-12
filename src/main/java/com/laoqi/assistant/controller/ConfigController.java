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
                Map.of("id", "morning_report", "time", "每天 09:00", "desc", "生成综合日报")
        ));
        return "config";
    }

    @PostMapping("/trigger")
    @ResponseBody
    public Map<String, Object> triggerJob(@RequestParam String job) {
        try {
            switch (job) {
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
