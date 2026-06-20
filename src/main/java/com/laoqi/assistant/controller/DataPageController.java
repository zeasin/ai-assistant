package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.LlmService;
import com.laoqi.assistant.service.LogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class DataPageController {

    private static final Logger log = LoggerFactory.getLogger(DataPageController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final KnowledgeBaseService kbService;
    private final LlmService llmService;
    private final LogService logService;
    private final DataSource dataSource;

    private volatile String cachedOverview = null;
    private volatile long cachedOverviewTime = 0;
    private volatile String cachedDataHash = null;

    public DataPageController(KnowledgeBaseService kbService, LlmService llmService,
                              LogService logService, DataSource dataSource) {
        this.kbService = kbService;
        this.llmService = llmService;
        this.logService = logService;
        this.dataSource = dataSource;
    }

    @GetMapping("/data")
    public String dataPage(Model model) {
        List<KnowledgeBaseEntity> kbs = kbService.getAll();
        model.addAttribute("kbs", kbs);
        return "data";
    }

    @GetMapping("/data/api/overview")
    @ResponseBody
    public Map<String, Object> getOverview(@RequestParam(value = "force", defaultValue = "false") boolean force) {
        try {
            String currentHash = computeDataHash();

            if (!force && cachedOverview != null && currentHash.equals(cachedDataHash)) {
                log.info("[数据概览] 返回缓存结果");
                return Map.of("ok", true, "cached", true, "overview", objectMapper.readValue(cachedOverview, Map.class));
            }

            log.info("[数据概览] 数据已变化，重新分析...");
            List<Map<String, Object>> allFiles = scanAllKbData();
            if (allFiles.isEmpty()) {
                return Map.of("ok", true, "cached", false, "overview", Map.of("metrics", List.of(), "charts", List.of(), "insights", List.of("暂无数据文件")));
            }

            String overviewJson = generateAiOverview(allFiles);
            cachedOverview = overviewJson;
            cachedDataHash = currentHash;
            cachedOverviewTime = System.currentTimeMillis();

            log.info("[数据概览] AI分析完成，已缓存");
            logService.add("数据概览", "成功", "AI分析 " + allFiles.size() + " 个文件");

            return Map.of("ok", true, "cached", false, "overview", objectMapper.readValue(overviewJson, Map.class));
        } catch (Exception e) {
            log.error("[数据概览] 分析失败", e);
            return Map.of("ok", false, "error", "分析失败: " + e.getMessage());
        }
    }

    private String computeDataHash() {
        StringBuilder sb = new StringBuilder();
        try {
            for (KnowledgeBaseEntity kb : kbService.getAll()) {
                Path notesDir = Paths.get(kb.getNotesDir());
                if (!Files.isDirectory(notesDir)) continue;
                sb.append(kb.getId()).append(":");
                Files.walk(notesDir)
                        .filter(p -> p.toString().endsWith(".json") && (p.toString().contains("/data/") || p.toString().contains("\\data\\")))
                        .sorted()
                        .forEach(p -> {
                            try {
                                sb.append(p.getFileName()).append(":").append(Files.getLastModifiedTime(p).toMillis()).append(",");
                            } catch (Exception ignored) {}
                        });
            }
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
        return sb.toString();
    }

    private List<Map<String, Object>> scanAllKbData() {
        List<Map<String, Object>> allFiles = new ArrayList<>();
        for (KnowledgeBaseEntity kb : kbService.getAll()) {
            Path notesDir = Paths.get(kb.getNotesDir());
            if (!Files.isDirectory(notesDir)) continue;
            try {
                Files.walk(notesDir)
                        .filter(p -> p.toString().endsWith(".json") && (p.toString().contains("/data/") || p.toString().contains("\\data\\")))
                        .forEach(p -> {
                            try {
                                Map<String, Object> file = new LinkedHashMap<>();
                                file.put("name", p.getFileName().toString());
                                file.put("path", notesDir.relativize(p).toString());
                                file.put("size", Files.size(p));
                                file.put("kbId", kb.getId());
                                file.put("kbName", kb.getName());
                                file.put("lastModified", Files.getLastModifiedTime(p).toMillis());

                                String content = Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
                                if (content.length() > 2000) content = content.substring(0, 2000);
                                file.put("sample", content);

                                allFiles.add(file);
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }
        return allFiles;
    }

    private String generateAiOverview(List<Map<String, Object>> files) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是数据分析师。以下是用户笔记库中的数据文件信息：\n\n");

        for (Map<String, Object> f : files) {
            prompt.append("文件: ").append(f.get("name")).append(" (").append(f.get("kbName")).append(")\n");
            prompt.append("大小: ").append(f.get("size")).append(" bytes\n");
            prompt.append("内容样本:\n").append(f.get("sample")).append("\n\n");
        }

        prompt.append("请生成数据仪表盘概要，严格返回以下 JSON 格式（不要加 markdown 代码块标记）：\n");
        prompt.append("{\"metrics\":[{\"label\":\"指标名\",\"value\":\"值\",\"icon\":\"emoji\"}],");
        prompt.append("\"charts\":[{\"type\":\"bar|line|pie|doughnut\",\"title\":\"标题\",\"labels\":[],\"data\":[]}],");
        prompt.append("\"insights\":[\"洞察1\",\"洞察2\"]}\n\n");
        prompt.append("要求：\n");
        prompt.append("- metrics 最多4个，展示关键数据（如文件数、总大小、知识库数等）\n");
        prompt.append("- charts 最多3个，基于数据内容生成有价值的图表\n");
        prompt.append("- insights 最多3条，基于数据给出分析建议\n");
        prompt.append("- 图表 data 是数字数组，labels 是字符串数组\n");

        String reply = llmService.chat("你是一个数据分析师，只返回JSON，不要其他内容。", prompt.toString());
        if (reply == null || reply.isEmpty()) {
            return "{\"metrics\":[],\"charts\":[],\"insights\":[\"AI 未返回结果\"]}";
        }

        String cleaned = reply.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "");
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        return cleaned;
    }
}
