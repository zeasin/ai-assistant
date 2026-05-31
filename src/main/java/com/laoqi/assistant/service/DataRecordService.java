package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DataRecordService {

    private static final Logger log = LoggerFactory.getLogger(DataRecordService.class);

    private final OpenCodeService openCodeService;
    private final AppConfig appConfig;
    private final LogService logService;

    private String recordSessionId;

    public DataRecordService(OpenCodeService openCodeService,
                             AppConfig appConfig,
                             LogService logService) {
        this.openCodeService = openCodeService;
        this.appConfig = appConfig;
        this.logService = logService;
    }

    /**
     * Forward the user's message to the AI (opencode) and return the reply.
     * The AI reads AGENTS.md rules from the notes library and operates on data files directly.
     */
    public String record(String text) {
        try {
            if (!openCodeService.isHealthy()) {
                return "⚠️ opencode serve 未启动（端口 " + appConfig.getNotesPort() + "），无法处理请求。";
            }

            if (recordSessionId == null) {
                recordSessionId = openCodeService.createSession("数据录入");
            }

            log.info("[数据录入] 发送给 AI: {}", text);
            String reply = openCodeService.sendMessage(recordSessionId, text);
            log.info("[数据录入] AI 回复: {}", reply);
            logService.add("数据录入", "完成", text);
            return reply;
        } catch (Exception e) {
            log.error("[数据录入] 失败", e);
            return "❌ 处理失败：" + e.getMessage();
        }
    }
}