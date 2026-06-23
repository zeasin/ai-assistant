package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.ReminderService;
import com.laoqi.assistant.service.ReportService;
import com.laoqi.assistant.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Controller
public class KbListPageController {

    private static final Logger log = LoggerFactory.getLogger(KbListPageController.class);
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".obsidian", "__pycache__", ".DS_Store",
            ".claude", ".playwright-mcp", ".sisyphus");

    private final KnowledgeBaseService kbService;
    private final TaskService taskService;
    private final ReminderService reminderService;
    private final ReportService reportService;

    public KbListPageController(KnowledgeBaseService kbService,
                                 TaskService taskService, ReminderService reminderService,
                                 ReportService reportService) {
        this.kbService = kbService;
        this.taskService = taskService;
        this.reminderService = reminderService;
        this.reportService = reportService;
    }

    @GetMapping("/kb")
    public String kbListPage() {
        return "redirect:/";
    }

    // 保留此方法但不再使用，备用
    public String kbListPageOld(Model model) {
        List<KnowledgeBaseEntity> allKbs = kbService.getAll();
        List<Map<String, Object>> kbInfos = new ArrayList<>();

        int totalTasks = 0;
        int totalReminders = 0;
        int totalDirs = 0;
        int totalFiles = 0;

        for (KnowledgeBaseEntity kb : allKbs) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", kb.getId());
            info.put("name", kb.getName());
            info.put("notesDir", kb.getNotesDir());

            try {
                long activeTaskCount = taskService.getAllTasks(kb.getNotesDir()).stream()
                        .filter(t -> !"done".equals(t.status))
                        .count();
                info.put("taskCount", (int) activeTaskCount);
                totalTasks += (int) activeTaskCount;
            } catch (Exception e) {
                info.put("taskCount", 0);
            }

            try {
                int reminderCount = reminderService.getAllReminders(kb.getNotesDir()).size();
                info.put("reminderCount", reminderCount);
                totalReminders += reminderCount;
            } catch (Exception e) {
                info.put("reminderCount", 0);
            }

            try {
                Path notesDir = Paths.get(kb.getNotesDir());
                if (Files.isDirectory(notesDir)) {
                    int[] counts = countDirsAndFiles(notesDir);
                    info.put("dirCount", counts[0]);
                    info.put("fileCount", counts[1]);
                    totalDirs += counts[0];
                    totalFiles += counts[1];
                } else {
                    info.put("dirCount", 0);
                    info.put("fileCount", 0);
                }
            } catch (Exception e) {
                info.put("dirCount", 0);
                info.put("fileCount", 0);
            }

            try {
                String notesDir = kb.getNotesDir();
                if (notesDir != null && !notesDir.isBlank()) {
                    String latestReport = reportService.readLatestReport(notesDir);
                    String reportDate = reportService.getLatestReportDate(notesDir);
                    info.put("hasReport", latestReport != null && !latestReport.isEmpty());
                    info.put("latestReport", latestReport);
                    info.put("reportDate", reportDate);
                } else {
                    info.put("hasReport", false);
                    info.put("latestReport", null);
                    info.put("reportDate", null);
                }
            } catch (Exception e) {
                info.put("hasReport", false);
                info.put("latestReport", null);
                info.put("reportDate", null);
            }

            info.put("labels", parseLabels(kb.getLabels()));
            kbInfos.add(info);
        }

        model.addAttribute("kbInfos", kbInfos);
        model.addAttribute("totalKbs", allKbs.size());
        model.addAttribute("totalTasks", totalTasks);
        model.addAttribute("totalReminders", totalReminders);
        model.addAttribute("totalDirs", totalDirs);
        model.addAttribute("totalFiles", totalFiles);
        return "1.0/kb_list";
    }

    private int[] countDirsAndFiles(Path dir) {
        int dirs = 0;
        int files = 0;
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.equals(dir)) continue;
                String name = p.getFileName().toString();
                if (IGNORED_DIRS.contains(name)) continue;
                if (Files.isDirectory(p)) dirs++;
                else files++;
            }
        } catch (IOException e) {
            log.debug("统计目录失败: {}", dir, e);
        }
        return new int[]{dirs, files};
    }

    private Map<String, String> defaultLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("tasks", "任务");
        labels.put("reminders", "提醒");
        labels.put("notes", "笔记");
        labels.put("config", "配置");
        return labels;
    }

    private Map<String, String> parseLabels(String labelsJson) {
        if (labelsJson == null || labelsJson.isBlank()) return defaultLabels();
        try {
            com.fasterxml.jackson.core.type.TypeReference<Map<String, String>> typeRef =
                    new com.fasterxml.jackson.core.type.TypeReference<>() {};
            Map<String, String> parsed = com.laoqi.assistant.util.FileUtil.readJson(labelsJson, typeRef, null);
            if (parsed == null || parsed.isEmpty()) return defaultLabels();
            Map<String, String> result = defaultLabels();
            result.putAll(parsed);
            return result;
        } catch (Exception e) {
            return defaultLabels();
        }
    }
}
