package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.NoteEmbeddingEntity;
import com.laoqi.assistant.service.db.NoteEmbeddingDbService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class NoteIndexService {

    private static final Logger log = LoggerFactory.getLogger(NoteIndexService.class);

    private static final int CHUNK_SIZE = 300;
    private static final int CHUNK_OVERLAP = 50;
    private static final float SIMILARITY_THRESHOLD = 0.5f;

    private static final Set<String> INDEXED_EXTENSIONS = Set.of(".md", ".json", ".txt");
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".obsidian", "__pycache__", ".DS_Store",
            ".claude", ".playwright-mcp", ".sisyphus", "AI");

    private final NoteEmbeddingDbService noteEmbeddingDbService;
    private final OllamaEmbeddingService embeddingService;
    private final KnowledgeBaseService kbService;

    public NoteIndexService(NoteEmbeddingDbService noteEmbeddingDbService,
                           OllamaEmbeddingService embeddingService,
                           KnowledgeBaseService kbService) {
        this.noteEmbeddingDbService = noteEmbeddingDbService;
        this.embeddingService = embeddingService;
        this.kbService = kbService;
    }

    public boolean isAvailable() {
        return embeddingService.isAvailable();
    }

    // ========== 进度回调接口 ==========

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int totalFiles, int processedFiles, String currentFile);
    }

    // ========== 索引构建 ==========

    public IndexResult indexKB(Long kbId) {
        return indexKB(kbId, null);
    }

    public IndexResult indexKB(Long kbId, ProgressCallback callback) {
        log.info("[NoteIndex] 开始构建索引，kbId={}", kbId);
        
        if (!isAvailable()) {
            log.error("[NoteIndex] Embedding 服务不可用");
            throw new IllegalStateException("Embedding 服务不可用，请配置 Ollama 或 API");
        }

        String notesDir = kbService.getNotesDirById(kbId);
        log.info("[NoteIndex] 笔记库路径: {}", notesDir);
        
        if (notesDir == null || notesDir.isBlank()) {
            log.error("[NoteIndex] 笔记库路径未配置");
            throw new IllegalStateException("笔记库路径未配置");
        }

        Path baseDir = Paths.get(notesDir);
        if (!Files.isDirectory(baseDir)) {
            log.error("[NoteIndex] 笔记库路径不存在: {}", notesDir);
            throw new IllegalStateException("笔记库路径不存在: " + notesDir);
        }

        IndexResult result = new IndexResult();
        try (Stream<Path> walk = Files.walk(baseDir, 10)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> shouldIndex(p, baseDir))
                    .sorted()
                    .collect(Collectors.toList());

            log.info("[NoteIndex] 开始构建索引，KB={}，共 {} 个文件", kbId, files.size());

            int total = files.size();
            int processed = 0;
            
            for (Path file : files) {
                String fileName = baseDir.relativize(file).toString().replace("\\", "/");
                if (callback != null) {
                    callback.onProgress(total, processed, fileName);
                }
                
                try {
                    indexFile(file, baseDir, kbId);
                    result.fileCount++;
                } catch (Exception e) {
                    log.warn("[NoteIndex] 索引文件失败: {}", file, e);
                    result.errors.add(file.getFileName().toString() + ": " + e.getMessage());
                }
                processed++;
            }
            
            if (callback != null) {
                callback.onProgress(total, total, "完成");
            }

            result.totalChunks = noteEmbeddingDbService.countByKb(kbId);
            log.info("[NoteIndex] 索引完成，KB={}，文件={}，片段={}", kbId, result.fileCount, result.totalChunks);
        } catch (IOException e) {
            throw new RuntimeException("扫描笔记库失败: " + e.getMessage(), e);
        }

        return result;
    }

    private boolean shouldIndex(Path file, Path baseDir) {
        String fileName = file.getFileName().toString();

        if (fileName.startsWith(".")) return false;

        Path relative = baseDir.relativize(file);
        for (Path segment : relative) {
            if (IGNORED_DIRS.contains(segment.toString())) return false;
        }

        String ext = getExtension(fileName);
        return INDEXED_EXTENSIONS.contains(ext);
    }

    private void indexFile(Path file, Path baseDir, Long kbId) throws Exception {
        String relativePath = baseDir.relativize(file).toString().replace("\\", "/");
        String content = FileUtil.readText(file);

        if (content == null || content.isBlank()) return;

        String contentHash = md5(content);

        List<NoteEmbeddingEntity> existing = noteEmbeddingDbService.lambdaQuery()
                .eq(NoteEmbeddingEntity::getKbId, kbId)
                .eq(NoteEmbeddingEntity::getFilePath, relativePath)
                .list();

        if (!existing.isEmpty() && existing.get(0).getContentHash().equals(contentHash)) {
            log.debug("[NoteIndex] 跳过未变更文件: {}", relativePath);
            return;
        }

        noteEmbeddingDbService.deleteByKbAndPath(kbId, relativePath);

        List<String> chunks = chunkContent(content);
        String now = TimeUtil.nowStr();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] vector = embeddingService.embed(chunk);
            if (vector == null) {
                log.warn("[NoteIndex] 生成向量失败: {} chunk={}", relativePath, i);
                continue;
            }

            NoteEmbeddingEntity entity = new NoteEmbeddingEntity();
            entity.setKbId(kbId);
            entity.setFilePath(relativePath);
            entity.setChunkIndex(i);
            entity.setContent(chunk);
            entity.setEmbedding(Base64.getEncoder().encodeToString(floatToBytes(vector)));
            entity.setContentHash(contentHash);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);

            noteEmbeddingDbService.save(entity);
        }

        log.debug("[NoteIndex] 已索引: {} ({} 个片段)", relativePath, chunks.size());
    }

    // ========== 搜索 ==========

    public List<NoteSearchResult> search(Long kbId, String query, int limit) {
        if (!isAvailable()) {
            throw new IllegalStateException("Embedding 服务不可用");
        }

        float[] queryVector = embeddingService.embed(query);
        if (queryVector == null) {
            throw new RuntimeException("生成查询向量失败");
        }

        List<NoteEmbeddingEntity> allEntities = noteEmbeddingDbService.lambdaQuery()
                .eq(NoteEmbeddingEntity::getKbId, kbId)
                .list();

        if (allEntities.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (NoteEmbeddingEntity entity : allEntities) {
            float[] vec = bytesToFloat(Base64.getDecoder().decode(entity.getEmbedding()));
            float score = cosineSimilarity(queryVector, vec);
            if (score >= SIMILARITY_THRESHOLD) {
                scored.add(new ScoredChunk(entity, score));
            }
        }

        scored.sort((a, b) -> Float.compare(b.score, a.score));

        Map<String, Integer> perFileCount = new HashMap<>();
        List<NoteSearchResult> results = new ArrayList<>();

        for (ScoredChunk chunk : scored) {
            int count = perFileCount.getOrDefault(chunk.entity.getFilePath(), 0);
            if (count >= 2) continue;

            perFileCount.put(chunk.entity.getFilePath(), count + 1);
            results.add(new NoteSearchResult(
                    chunk.entity.getFilePath(),
                    chunk.entity.getContent(),
                    chunk.score,
                    chunk.entity.getChunkIndex(),
                    0
            ));

            if (results.size() >= limit) break;
        }

        return results;
    }

    public List<NoteSearchResult> hybridSearch(Long kbId, String query, int limit) {
        List<NoteSearchResult> semanticResults = search(kbId, query, limit * 2);

        List<NoteSearchResult> keywordResults = keywordSearch(kbId, query, limit * 2);

        Map<String, Double> scores = new HashMap<>();
        Map<String, NoteSearchResult> resultMap = new HashMap<>();

        for (NoteSearchResult r : semanticResults) {
            String key = r.filePath() + ":" + r.chunkIndex();
            scores.merge(key, r.score() * 0.7, Double::sum);
            resultMap.put(key, r);
        }

        for (NoteSearchResult r : keywordResults) {
            String key = r.filePath() + ":" + r.chunkIndex();
            scores.merge(key, r.score() * 0.3, Double::sum);
            resultMap.putIfAbsent(key, r);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> resultMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<NoteSearchResult> keywordSearch(Long kbId, String query, int limit) {
        String notesDir = kbService.getNotesDirById(kbId);
        if (notesDir == null || notesDir.isBlank()) return List.of();

        Path baseDir = Paths.get(notesDir);
        List<NoteSearchResult> results = new ArrayList<>();
        String queryLower = query.toLowerCase();

        try (Stream<Path> walk = Files.walk(baseDir, 10)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> shouldIndex(p, baseDir))
                .forEach(file -> {
                    try {
                        String content = FileUtil.readText(file);
                        if (content == null || content.isBlank()) return;

                        String contentLower = content.toLowerCase();
                        if (!contentLower.contains(queryLower)) return;

                        String relativePath = baseDir.relativize(file).toString().replace("\\", "/");
                        List<String> chunks = chunkContent(content);

                        for (int i = 0; i < chunks.size(); i++) {
                            if (chunks.get(i).toLowerCase().contains(queryLower)) {
                                int matchCount = countOccurrences(chunks.get(i).toLowerCase(), queryLower);
                                float score = Math.min(1.0f, matchCount * 0.2f);

                                results.add(new NoteSearchResult(
                                        relativePath,
                                        chunks.get(i),
                                        score,
                                        i,
                                        chunks.size()
                                ));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[NoteIndex] 关键词搜索文件失败: {}", file, e);
                    }
                });
        } catch (IOException e) {
            log.warn("[NoteIndex] 关键词搜索失败", e);
        }

        return results.stream()
                .sorted((a, b) -> Float.compare(b.score, a.score))
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ========== 索引管理 ==========

    public void clearIndex(Long kbId) {
        noteEmbeddingDbService.deleteByKb(kbId);
        log.info("[NoteIndex] 已清空索引，KB={}", kbId);
    }

    public IndexStats getIndexStats(Long kbId) {
        int fileCount = noteEmbeddingDbService.countFilesByKb(kbId);
        int chunkCount = noteEmbeddingDbService.countByKb(kbId);
        return new IndexStats(fileCount, chunkCount);
    }

    // ========== 工具方法 ==========

    private List<String> chunkContent(String content) {
        List<String> chunks = new ArrayList<>();

        String[] paragraphs = content.split("\n\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (currentChunk.length() + paragraph.length() > CHUNK_SIZE && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString().trim());
                String overlap = currentChunk.toString();
                if (overlap.length() > CHUNK_OVERLAP) {
                    overlap = overlap.substring(overlap.length() - CHUNK_OVERLAP);
                }
                currentChunk = new StringBuilder(overlap).append("\n\n").append(paragraph);
            } else {
                if (!currentChunk.isEmpty()) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }

            while (currentChunk.length() > CHUNK_SIZE) {
                chunks.add(currentChunk.substring(0, CHUNK_SIZE).trim());
                currentChunk = new StringBuilder(currentChunk.substring(CHUNK_SIZE - CHUNK_OVERLAP));
            }
        }

        if (!currentChunk.toString().isBlank()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : (float) (dot / denom);
    }

    private byte[] floatToBytes(float[] values) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(values.length * 4);
        buf.order(java.nio.ByteOrder.nativeOrder());
        for (float v : values) buf.putFloat(v);
        return buf.array();
    }

    private float[] bytesToFloat(byte[] bytes) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
        buf.order(java.nio.ByteOrder.nativeOrder());
        float[] result = new float[bytes.length / 4];
        for (int i = 0; i < result.length; i++) {
            result[i] = buf.getFloat();
        }
        return result;
    }

    private String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase() : "";
    }

    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // ========== 数据模型 ==========

    public record NoteSearchResult(
            String filePath,
            String content,
            float score,
            int chunkIndex,
            int totalChunks
    ) {}

    private record ScoredChunk(NoteEmbeddingEntity entity, float score) {}

    public static class IndexResult {
        public int fileCount;
        public int totalChunks;
        public List<String> errors;

        public IndexResult() {
            this.fileCount = 0;
            this.totalChunks = 0;
            this.errors = new ArrayList<>();
        }
    }

    public record IndexStats(int fileCount, int chunkCount) {}
}
