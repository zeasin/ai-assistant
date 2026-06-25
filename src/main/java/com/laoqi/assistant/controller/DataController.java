package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.dto.ApiResult;
import com.laoqi.assistant.dto.ColumnSettingsResult;
import com.laoqi.assistant.dto.DataFileInfo;
import com.laoqi.assistant.dto.DataListResult;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.service.ConfigService;
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
    public ResponseEntity<DataListResult> listData(
            @RequestParam(required = false) Long kbId,
            @RequestParam String dir) {

        Path baseDir;
        try {
            baseDir = getNotesDir(kbId);
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(DataListResult.fail(e.getMessage()));
        }

        Path targetDir = baseDir.resolve(dir).resolve("data").normalize();
        Path base = baseDir.normalize();
        if (!targetDir.startsWith(base)) {
            return ResponseEntity.ok(DataListResult.fail("路径越界"));
        }

        if (!Files.exists(targetDir)) {
            return ResponseEntity.ok(DataListResult.notExists(dir, targetDir.toString()));
        }

        try {
            List<DataFileInfo> fileList = new ArrayList<>();

            try (java.util.stream.Stream<Path> stream = Files.list(targetDir)) {
                List<Path> jsonFiles = stream
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());

                for (Path f : jsonFiles) {
                    String fileName = f.getFileName().toString().replace(".json", "");
                    fileList.add(new DataFileInfo(
                        fileName,
                        f.toString(),
                        Files.size(f),
                        Files.getLastModifiedTime(f).toMillis()
                    ));
                }
            }

            return ResponseEntity.ok(DataListResult.success(dir, targetDir.toString(), fileList));

        } catch (Exception e) {
            log.error("读取目录失败: {}", targetDir, e);
            return ResponseEntity.ok(DataListResult.fail("读取目录失败: " + e.getMessage()));
        }
    }

    @GetMapping(value = "/column-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ColumnSettingsResult getColumnSettings(
            @RequestParam(required = false, defaultValue = "customer") String type) {
        Config config = configService.load();
        Map<String, Map<String, List<String>>> allSettings = config.getColumnSettings();
        if (allSettings == null) {
            allSettings = new HashMap<>();
        }
        Map<String, List<String>> typeSettings = allSettings.getOrDefault(type, new HashMap<>());
        return ColumnSettingsResult.success(type, typeSettings);
    }

    @PostMapping(value = "/column-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<Void> saveColumnSettings(
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
            return ApiResult.success();
        } catch (Exception e) {
            return ApiResult.fail(e.getMessage());
        }
    }
}
