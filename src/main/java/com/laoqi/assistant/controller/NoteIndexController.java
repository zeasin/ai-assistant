package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.NoteIndexService;
import com.laoqi.assistant.service.NoteIndexService.IndexResult;
import com.laoqi.assistant.service.NoteIndexService.IndexStats;
import com.laoqi.assistant.service.NoteIndexService.NoteSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/kb/{id}/api/index")
public class NoteIndexController {

    private static final Logger log = LoggerFactory.getLogger(NoteIndexController.class);
    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor();

    private final NoteIndexService noteIndexService;
    private final LogService logService;
    
    private volatile boolean isBuilding = false;
    private volatile String lastBuildResult = "";
    private volatile int buildFileCount = 0;
    private volatile int buildChunkCount = 0;
    private volatile int totalFiles = 0;
    private volatile int processedFiles = 0;
    private volatile String currentFile = "";

    public NoteIndexController(NoteIndexService noteIndexService, LogService logService) {
        this.noteIndexService = noteIndexService;
        this.logService = logService;
    }

    @PostMapping("/build")
    public Map<String, Object> buildIndex(@PathVariable Long id) {
        log.info("[NoteIndex] 收到构建索引请求，kbId={}", id);
        
        if (!noteIndexService.isAvailable()) {
            log.warn("[NoteIndex] Embedding 服务不可用");
            return Map.of("ok", false, "error", "Embedding 服务不可用，请配置 Ollama 或 API");
        }
        
        if (isBuilding) {
            log.warn("[NoteIndex] 索引正在构建中");
            return Map.of("ok", false, "error", "索引正在构建中，请稍候");
        }

        indexExecutor.execute(() -> {
            isBuilding = true;
            lastBuildResult = "";
            processedFiles = 0;
            currentFile = "准备中...";
            log.info("[NoteIndex] 开始构建索引，kbId={}", id);
            try {
                IndexResult result = noteIndexService.indexKB(id, 
                    (total, processed, file) -> {
                        this.totalFiles = total;
                        this.processedFiles = processed;
                        this.currentFile = file;
                    });
                buildFileCount = result.fileCount;
                buildChunkCount = result.totalChunks;
                lastBuildResult = "成功";
                logService.add("笔记索引", "构建完成", "文件: " + result.fileCount + ", 片段: " + result.totalChunks);
                log.info("[NoteIndex] 构建完成，文件: {}, 片段: {}", result.fileCount, result.totalChunks);
            } catch (Exception e) {
                log.error("[NoteIndex] 构建索引失败", e);
                lastBuildResult = "失败: " + e.getMessage();
            } finally {
                isBuilding = false;
                currentFile = "";
            }
        });

        return Map.of("ok", true, "message", "索引构建已启动");
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus(@PathVariable Long id) {
        boolean embeddingAvailable = noteIndexService.isAvailable();
        IndexStats stats = noteIndexService.getIndexStats(id);
        
        String status;
        if (!embeddingAvailable) {
            status = "unavailable";
        } else if (stats.fileCount() == 0) {
            status = "empty";
        } else {
            status = "ready";
        }

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("ok", true);
        result.put("embeddingAvailable", embeddingAvailable);
        result.put("status", status);
        result.put("fileCount", stats.fileCount());
        result.put("chunkCount", stats.chunkCount());
        result.put("building", isBuilding);
        result.put("lastBuildResult", lastBuildResult);
        
        if (isBuilding) {
            result.put("totalFiles", totalFiles);
            result.put("processedFiles", processedFiles);
            result.put("currentFile", currentFile);
            result.put("progress", totalFiles > 0 ? (processedFiles * 100 / totalFiles) : 0);
        }
        
        return result;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        int limit = body.containsKey("limit") ? (int) body.get("limit") : 5;

        if (query == null || query.isBlank()) {
            return Map.of("ok", false, "error", "搜索内容不能为空");
        }

        if (!noteIndexService.isAvailable()) {
            return Map.of("ok", false, "error", "Embedding 服务不可用");
        }

        try {
            List<NoteSearchResult> results = noteIndexService.search(id, query, limit);
            return Map.of("ok", true, "results", results, "query", query);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/hybrid-search")
    public Map<String, Object> hybridSearch(@PathVariable Long id,
                                             @RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        int limit = body.containsKey("limit") ? (int) body.get("limit") : 5;

        if (query == null || query.isBlank()) {
            return Map.of("ok", false, "error", "搜索内容不能为空");
        }

        if (!noteIndexService.isAvailable()) {
            return Map.of("ok", false, "error", "Embedding 服务不可用");
        }

        try {
            List<NoteSearchResult> results = noteIndexService.hybridSearch(id, query, limit);
            return Map.of("ok", true, "results", results, "query", query);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @DeleteMapping
    public Map<String, Object> clearIndex(@PathVariable Long id) {
        noteIndexService.clearIndex(id);
        logService.add("笔记索引", "清空", "知识库: " + id);
        return Map.of("ok", true, "message", "索引已清空");
    }
}
