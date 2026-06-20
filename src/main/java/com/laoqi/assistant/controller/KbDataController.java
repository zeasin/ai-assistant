package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.DirectoryDataService;
import com.laoqi.assistant.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;

@Controller
@RequestMapping("/data/kb")
public class KbDataController {

    private static final Logger log = LoggerFactory.getLogger(KbDataController.class);

    private final KnowledgeBaseService kbService;
    private final DirectoryDataService directoryDataService;

    public KbDataController(KnowledgeBaseService kbService, DirectoryDataService directoryDataService) {
        this.kbService = kbService;
        this.directoryDataService = directoryDataService;
    }

    @GetMapping
    public String page(Model model) {
        List<KnowledgeBaseEntity> kbs = kbService.getAll();
        model.addAttribute("kbs", kbs);
        return "kb_data";
    }

    @GetMapping("/api/files")
    @ResponseBody
    public Map<String, Object> listAllDataFiles() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeBaseEntity kb : kbService.getAll()) {
            Path notesDir = Paths.get(kb.getNotesDir());
            if (!Files.isDirectory(notesDir)) continue;
            try {
                Files.walk(notesDir)
                        .filter(p -> p.toString().endsWith(".json") && p.toString().contains("/data/"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                Map<String, Object> file = new LinkedHashMap<>();
                                String fileName = p.getFileName().toString().replace(".json", "");
                                String relPath = notesDir.relativize(p).toString();
                                String dirPart = relPath.replace(fileName + ".json", "").replace("\\", "/");
                                if (dirPart.startsWith("/")) dirPart = dirPart.substring(1);
                                if (dirPart.endsWith("/")) dirPart = dirPart.substring(0, dirPart.length() - 1);

                                file.put("name", p.getFileName().toString());
                                file.put("fileName", fileName);
                                file.put("path", relPath);
                                file.put("dir", dirPart);
                                file.put("size", Files.size(p));
                                file.put("lastModified", Files.getLastModifiedTime(p).toMillis());
                                file.put("kbId", kb.getId());
                                file.put("kbName", kb.getName());
                                result.add(file);
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }
        return Map.of("ok", true, "files", result);
    }

    @GetMapping("/api/read")
    @ResponseBody
    public Map<String, Object> readFile(@RequestParam long kbId, @RequestParam String dir, @RequestParam String fileName) {
        KnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path notesDir = Paths.get(kb.getNotesDir());
        Path dataDir = resolveDataDir(notesDir, dir);
        Map<String, Object> fileData = directoryDataService.getFileData(dataDir, fileName);
        if (fileData == null) return Map.of("ok", false, "error", "文件不存在");
        return Map.of("ok", true, "data", fileData);
    }

    @GetMapping("/api/group")
    @ResponseBody
    public Map<String, Object> readGroup(@RequestParam long kbId, @RequestParam String dir,
                                          @RequestParam String fileName, @RequestParam String group) {
        KnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) return Map.of("ok", false, "error", "知识库不存在");
        Path notesDir = Paths.get(kb.getNotesDir());
        Path dataDir = resolveDataDir(notesDir, dir);
        Map<String, Object> result = directoryDataService.getGroupData(dataDir, fileName, group);
        if (result == null) return Map.of("ok", false, "error", "分组不存在");
        return Map.of("ok", true, "count", result.getOrDefault("count", 0), "data", result.getOrDefault("data", List.of()));
    }

    private Path resolveDataDir(Path notesDir, String dir) {
        Path dataDir = dir.isEmpty() ? notesDir.resolve("data") : notesDir.resolve(dir).resolve("data");
        if (!Files.isDirectory(dataDir)) {
            dataDir = dir.isEmpty() ? notesDir : notesDir.resolve(dir);
        }
        return dataDir;
    }
}
