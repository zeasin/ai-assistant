package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final AppConfig appConfig;
    private final FeishuService feishuService;
    private final TodoService todoService;
    private final LogService logService;

    public ReportService(AppConfig appConfig,
                          FeishuService feishuService, TodoService todoService,
                          LogService logService) {
        this.appConfig = appConfig;
        this.feishuService = feishuService;
        this.todoService = todoService;
        this.logService = logService;
    }

    public static class ReportResult {
        public String report;
        public String error;
    }

    public ReportResult generate() {
        ReportResult result = new ReportResult();
        result.error = "AI 引擎未配置，请在 application.yml 中配置 AI 服务";
        return result;
    }

    public void generateAndPush() {
        ReportResult r = generate();
        if (r.report != null) {
            String today = TimeUtil.todayStr();
            String wd = TimeUtil.weekdayCn(TimeUtil.now());
            String title = "🌅 老齐早安 · " + today + " · " + wd;
            var paras = feishuService.reportToParagraphs(r.report);
            feishuService.sendPost(title, paras);
            saveComprehensiveReport(r.report);
            todoService.clearTempReminders();
            logService.add("日报生成", "成功", "AI 日报已生成并推送");
        } else {
            logService.add("日报生成", "失败", r.error);
        }
    }

    public void saveComprehensiveReport(String report) {
        Path dir = appConfig.getComprehensiveReportDir();
        String date = TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        FileUtil.writeText(file, report);
    }
}
