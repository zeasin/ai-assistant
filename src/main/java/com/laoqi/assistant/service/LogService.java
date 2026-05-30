package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.LogEntry;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);
    private static final int MAX_LOG = 100;

    private final AppConfig appConfig;
    private final TypeReference<List<LogEntry>> logType = new TypeReference<List<LogEntry>>() {};

    public LogService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public List<LogEntry> load() {
        return FileUtil.readJson(appConfig.getLogFile(), logType, new ArrayList<>());
    }

    public synchronized void add(String action, String status, String detail) {
        List<LogEntry> logs = load();
        logs.add(0, new LogEntry(TimeUtil.nowStr(), action, status, detail));
        if (logs.size() > MAX_LOG) logs = logs.subList(0, MAX_LOG);
        FileUtil.writeJson(appConfig.getLogFile(), logs);
    }

    public void add(String action, String status) {
        add(action, status, "");
    }
}