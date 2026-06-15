package com.laoqi.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private String configDir;
    private int notesPort = 14096;
    private String timezone = "Asia/Shanghai";
    private int maxHistoryChars = 6000;

    public Path getConfigDirPath() {
        String dir = configDir != null ? configDir : ".";
        return Paths.get(dir);
    }

    public Path getConfigFile() {
        return getConfigDirPath().resolve("config.json");
    }

    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }
    public int getNotesPort() { return notesPort; }
    public void setNotesPort(int notesPort) { this.notesPort = notesPort; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public int getMaxHistoryChars() { return maxHistoryChars; }
    public void setMaxHistoryChars(int maxHistoryChars) { this.maxHistoryChars = maxHistoryChars; }
}
