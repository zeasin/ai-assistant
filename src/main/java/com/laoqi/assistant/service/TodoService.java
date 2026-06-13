package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class TodoService {

    private final AppConfig appConfig;
    private final ConfigService configService;

    public TodoService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
    }

    private Path getBaseDir() {
        return Paths.get(configService.getBaseDir());
    }

    private Path getRemindFile() {
        return getBaseDir().resolve("代办.md");
    }

    public static class Todos {
        public List<String> high = new ArrayList<>();
        public List<String> mid = new ArrayList<>();
        public List<String> low = new ArrayList<>();
        public List<String> daily = new ArrayList<>();
        public List<String> temp = new ArrayList<>();
    }

    public Todos parse() {
        Todos todos = new Todos();

        // Read 代办.md
        Path remindPath = getRemindFile();
        if (FileUtil.exists(remindPath)) {
            String content = FileUtil.readText(remindPath);
            String section = null;
            for (String line : content.split("\n")) {
                String lower = line.toLowerCase();
                if (line.contains("高优先级") || line.contains("🔴") || lower.contains("### 高")) section = "high";
                else if (line.contains("中优先级") || line.contains("🟡") || lower.contains("### 中")) section = "mid";
                else if (line.contains("低优先级") || line.contains("随缘") || line.contains("🟢")) section = "low";
                else if (line.contains("每日循环")) section = "daily";
                else if (line.contains("临时提醒")) section = "temp";

                if (section != null && line.strip().startsWith("- [ ]")) {
                    String text = line.strip().replace("- [ ]", "").strip();
                    if (!text.isEmpty()) {
                        switch (section) {
                            case "high" -> todos.high.add(text);
                            case "mid" -> todos.mid.add(text);
                            case "low" -> todos.low.add(text);
                            case "daily" -> todos.daily.add(text);
                            case "temp" -> todos.temp.add(text);
                        }
                    }
                }
            }
        }

        return todos;
    }

    public void clearTempReminders() {
        Path remindPath = getRemindFile();
        if (!FileUtil.exists(remindPath)) return;
        String content = FileUtil.readText(remindPath);
        StringBuilder out = new StringBuilder();
        boolean inTemp = false;
        for (String line : content.split("\n", -1)) {
            if (line.contains("临时提醒")) {
                inTemp = true;
                out.append(line).append("\n");
                continue;
            }
            if (inTemp) {
                if (line.strip().startsWith("- [")) continue;
                if (line.isBlank() || line.contains("（日报推送后自动清空")) {
                    out.append(line).append("\n");
                    continue;
                }
                inTemp = false;
            }
            out.append(line).append("\n");
        }
        FileUtil.writeText(remindPath, out.toString());
    }
}
