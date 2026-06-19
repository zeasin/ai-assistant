package com.laoqi.assistant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/data")
public class DataController {

    private static final Logger log = LoggerFactory.getLogger(DataController.class);
    private static final TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};

    private final ConfigService configService;
    private final AppConfig appConfig;

    public DataController(ConfigService configService, AppConfig appConfig) {
        this.configService = configService;
        this.appConfig = appConfig;
    }

    private Path getNotesDir(Long kbId) {
        String notesDir = configService.getNotesDir(kbId);
        return Paths.get(notesDir);
    }

    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listData(
            @RequestParam(required = false) Long kbId,
            @RequestParam String dir) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        Path baseDir;
        try {
            baseDir = getNotesDir(kbId);
        } catch (IllegalStateException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
        
        Path targetDir = baseDir.resolve(dir).resolve("data").normalize();
        Path base = baseDir.normalize();
        if (!targetDir.startsWith(base)) {
            result.put("ok", false);
            result.put("error", "路径越界");
            return ResponseEntity.ok(result);
        }
        
        result.put("directory", dir);
        result.put("fullPath", targetDir.toString());
        
        if (!Files.exists(targetDir)) {
            result.put("ok", false);
            result.put("error", "目录不存在: " + targetDir);
            result.put("exists", false);
            return ResponseEntity.ok(result);
        }
        
        result.put("exists", true);
        
        try {
            List<Map<String, Object>> fileList = new ArrayList<>();
            
            try (java.util.stream.Stream<Path> stream = Files.list(targetDir)) {
                List<Path> jsonFiles = stream
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
                
                for (Path f : jsonFiles) {
                    String fileName = f.getFileName().toString().replace(".json", "");
                    Map<String, Object> fileInfo = new LinkedHashMap<>();
                    fileInfo.put("name", fileName);
                    fileInfo.put("path", f.toString());
                    fileInfo.put("size", Files.size(f));
                    fileInfo.put("lastModified", Files.getLastModifiedTime(f).toMillis());
                    fileList.add(fileInfo);
                }
            }
            
            result.put("ok", true);
            result.put("files", fileList);
            result.put("fileCount", fileList.size());
            
        } catch (Exception e) {
            log.error("读取目录失败: {}", targetDir, e);
            result.put("ok", false);
            result.put("error", "读取目录失败: " + e.getMessage());
        }
        
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/column-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getColumnSettings(@RequestParam(required = false, defaultValue = "customer") String type) {
        Config config = configService.load();
        Map<String, Map<String, List<String>>> allSettings = config.getColumnSettings();
        if (allSettings == null) {
            allSettings = new HashMap<>();
        }
        Map<String, List<String>> typeSettings = allSettings.getOrDefault(type, new HashMap<>());
        return Map.of("ok", true, "settings", typeSettings);
    }

    @PostMapping(value = "/column-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveColumnSettings(
            @RequestParam(required = false, defaultValue = "customer") String type,
            @RequestBody Map<String, List<String>> settings) {
        try {
            Config config = configService.load();
            Map<String, Map<String, List<String>>> allSettings = config.getColumnSettings();
            if (allSettings == null) {
                allSettings = new HashMap<>();
            }
            allSettings.put(type, settings);
            config.setColumnSettings(allSettings);
            configService.save(config);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
