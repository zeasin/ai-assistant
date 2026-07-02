package com.laoqi.assistant.service;
import com.laoqi.assistant.entity.FileIndexMetaEntity;
import com.laoqi.assistant.service.db.FileIndexMetaDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class IndexScannerService {
    private static final Logger log = LoggerFactory.getLogger(IndexScannerService.class);
    private final NoteIndexService noteIndexService;
    private final FileIndexMetaDbService metaDbService;
    private final KnowledgeBaseService kbService;
    public IndexScannerService(NoteIndexService ns, FileIndexMetaDbService md, KnowledgeBaseService kb) {
        this.noteIndexService = ns; this.metaDbService = md; this.kbService = kb;
    }

    /**
     * 检查 Embedding 服务是否可用
     */
    public boolean isEmbeddingAvailable() {
        return noteIndexService.isAvailable();
    }
    public ScanResult scanKb(Long kbId) {
        log.info("[IndexScanner] 开始增量扫描, kbId={}", kbId);
        if (!noteIndexService.isAvailable()) return new ScanResult("Embedding 不可用");
        String notesDir = kbService.getNotesDirById(kbId);
        if (notesDir == null || notesDir.isBlank()) return new ScanResult("路径未配置");
        Path baseDir = Paths.get(notesDir);
        if (!Files.isDirectory(baseDir)) return new ScanResult("路径不存在");
        Map<String, FileIndexMetaEntity> metaMap = metaDbService.getMapByKb(kbId);
        Set<String> ignoredDirs = noteIndexService.getIgnoredDirs(kbId);
        Set<String> ignoredFiles = noteIndexService.getIgnoredFiles(kbId);
        ScanResult result = new ScanResult();
        result.kbId = kbId;
        result.scanTime = java.time.LocalDateTime.now().toString();
        try (Stream<Path> walk = Files.walk(baseDir, 10)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> noteIndexService.shouldIndex(p, baseDir, ignoredDirs, ignoredFiles))
                    .sorted().collect(Collectors.toList());
            for (Path file : files) {
                String relPath = baseDir.relativize(file).toString().replace("\\", "/");
                FileIndexMetaEntity existingMeta = metaMap.remove(relPath);
                try {
                    long lastModified = Files.getLastModifiedTime(file).toMillis();
                    long fileSize = Files.size(file);
                    if (existingMeta == null) {
                        result.newFiles++; result.actualChanged++;
                        indexAndUpdateMeta(file, baseDir, kbId, null, lastModified, fileSize);
                    } else if (existingMeta.getLastModified() == null
                            || existingMeta.getLastModified() != lastModified
                            || existingMeta.getFileSize() == null
                            || existingMeta.getFileSize() != fileSize) {
                        result.modifiedFiles++; result.actualChanged++;
                        indexAndUpdateMeta(file, baseDir, kbId, existingMeta, lastModified, fileSize);
                    } else {
                        result.skippedFiles++;
                    }
                } catch (Exception e) {
                    log.warn("[IndexScanner] 处理失败: {}", relPath, e);
                    result.errors++; result.errorDetails.add(relPath + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            result.errorDetails.add("扫描失败: " + e.getMessage());
            return result;
        }
        for (String deletedPath : metaMap.keySet()) {
            noteIndexService.removeFileFromIndex(kbId, deletedPath);
            metaDbService.deleteByKbAndPath(kbId, deletedPath);
            result.deletedFiles++; result.actualChanged++;
        }
        log.info("[IndexScanner] 完成: kbId={}, 新={}, 改={}, 删={}, 跳={}, 错={}",
                kbId, result.newFiles, result.modifiedFiles, result.deletedFiles,
                result.skippedFiles, result.errors);
        return result;
    }
    private void indexAndUpdateMeta(Path file, Path baseDir, Long kbId, FileIndexMetaEntity existingMeta, long lastModified, long fileSize) throws Exception {
        boolean indexed = noteIndexService.indexSingleFile(file, baseDir, kbId);
        if (indexed) {
            String contentHash = null;
            try {
                String c = com.laoqi.assistant.util.FileUtil.readText(file);
                if (c != null && !c.isBlank()) contentHash = md5(c);
            } catch (Exception e) { log.warn("[IndexScanner] hash失败"); }
            String now = java.time.LocalDateTime.now().toString();
            String relPath = baseDir.relativize(file).toString().replace("\\", "/");
            if (existingMeta != null) {
                existingMeta.setLastModified(lastModified);
                existingMeta.setFileSize(fileSize);
                existingMeta.setContentHash(contentHash);
                existingMeta.setLastIndexedAt(now);
                metaDbService.updateById(existingMeta);
            } else {
                FileIndexMetaEntity meta = new FileIndexMetaEntity();
                meta.setKbId(kbId); meta.setFilePath(relPath);
                meta.setLastModified(lastModified); meta.setFileSize(fileSize);
                meta.setContentHash(contentHash); meta.setLastIndexedAt(now);
                meta.setCreatedAt(now);
                metaDbService.save(meta);
            }
        }
    }
    public Map<Long, ScanResult> scanAllKbs() {
        var allKbs = kbService.getAll();
        Map<Long, ScanResult> results = new LinkedHashMap<>();
        for (var kb : allKbs) {
            try { results.put(kb.getId(), scanKb(kb.getId()));
            } catch (Exception e) {
                ScanResult r = new ScanResult("失败: " + e.getMessage());
                r.kbId = kb.getId(); results.put(kb.getId(), r);
            }
        }
        return results;
    }
    private String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(content.hashCode()); }
    }
    public static class ScanResult {
        public Long kbId; public String scanTime;
        public int newFiles, modifiedFiles, deletedFiles, skippedFiles, actualChanged, errors;
        public List<String> errorDetails; public String errorMessage; public boolean success;
        public ScanResult() {
            this.newFiles=0; this.modifiedFiles=0; this.deletedFiles=0;
            this.skippedFiles=0; this.actualChanged=0; this.errors=0;
            this.errorDetails=new ArrayList<>(); this.success=true;
        }
        public ScanResult(String msg) { this(); this.success=false; this.errorMessage=msg; }
        public int getTotalFiles() { return newFiles+modifiedFiles+deletedFiles+skippedFiles; }
    }
}