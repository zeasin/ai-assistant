package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.model.LogEntry;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);
    private static final int MAX_LOG = 100;

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final TypeReference<List<LogEntry>> logType = new TypeReference<List<LogEntry>>() {};

    public LogService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
    }

    private Path getLogFile() {
        Config config = configService.load();
        String baseDir = config.getBaseDir();
        if (baseDir == null || baseDir.isEmpty()) baseDir = "D:\\projects\\richie_learning_notes";
        String logFile = config.getLogFile();
        if (logFile == null || logFile.isEmpty()) logFile = "assistant_log.json";
        return Paths.get(baseDir).resolve(logFile);
    }

    public List<LogEntry> load() {
        return FileUtil.readJson(getLogFile(), logType, new ArrayList<>());
    }

    public synchronized void add(String action, String status, String detail) {
        List<LogEntry> logs = load();
        logs.add(0, new LogEntry(TimeUtil.nowStr(), action, status, detail));
        if (logs.size() > MAX_LOG) logs = logs.subList(0, MAX_LOG);
        FileUtil.writeJson(getLogFile(), logs);
    }

    public void add(String action, String status) {
        add(action, status, "");
    }
}
