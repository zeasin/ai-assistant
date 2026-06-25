package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
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
    private static final float SIMILARITY_THRESHOLD = 0.3f;  // bge-m3 阈值降低

    private static final Set<String> INDEXED_EXTENSIONS = Set.of(".md", ".json", ".txt");
    
    // 默认排除列表（系统级）- 只排除以.开头的
    private static final Set<String> SYSTEM_IGNORED = Set.of();

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

    // ========== 排除列表管理 ==========

    /**
     * 获取指定知识库的排除文件夹列表
     */
    public Set<String> getIgnoredDirs(Long kbId) {
        Set<String> ignored = new HashSet<>(SYSTEM_IGNORED);
        
        // 从数据库读取用户自定义排除列表
        if (kbId != null) {
            KnowledgeBaseEntity kb = kbService.getById(kbId);
            if (kb != null && kb.getIgnoreDirs() != null && !kb.getIgnoreDirs().isBlank()) {
                try {
                    String[] dirs = kb.getIgnoreDirs().split(",");
                    for (String dir : dirs) {
                        String trimmed = dir.trim();
                        if (!trimmed.isEmpty()) {
                            ignored.add(trimmed);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[NoteIndex] 解析排除列表失败: {}", e.getMessage());
                }
            }
        }
        
        return ignored;
    }

    /**
     * 更新知识库的排除文件夹列表
     */
    public void updateIgnoredDirs(Long kbId, String ignoreDirs) {
        kbService.save(Map.of("id", kbId, "ignoreDirs", ignoreDirs));
        log.info("[NoteIndex] 更新排除列表: kbId={}, ignoreDirs={}", kbId, ignoreDirs);
    }

    /**
     * 获取指定知识库的排除文件列表
     */
    public Set<String> getIgnoredFiles(Long kbId) {
        Set<String> ignored = new HashSet<>();
        
        if (kbId != null) {
            KnowledgeBaseEntity kb = kbService.getById(kbId);
            if (kb != null && kb.getIgnoreFiles() != null && !kb.getIgnoreFiles().isBlank()) {
                try {
                    String[] files = kb.getIgnoreFiles().split(",");
                    for (String file : files) {
                        String trimmed = file.trim();
                        if (!trimmed.isEmpty()) {
                            ignored.add(trimmed);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[NoteIndex] 解析排除文件列表失败: {}", e.getMessage());
                }
            }
        }
        
        return ignored;
    }

    /**
     * 更新知识库的排除文件列表
     */
    public void updateIgnoredFiles(Long kbId, String ignoreFiles) {
        kbService.save(Map.of("id", kbId, "ignoreFiles", ignoreFiles));
        log.info("[NoteIndex] 更新排除文件列表: kbId={}, ignoreFiles={}", kbId, ignoreFiles);
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

        // 获取排除列表
        Set<String> ignoredDirs = getIgnoredDirs(kbId);
        Set<String> ignoredFiles = getIgnoredFiles(kbId);
        log.info("[NoteIndex] 排除文件夹: {}, 排除文件: {}", ignoredDirs, ignoredFiles);

        IndexResult result = new IndexResult();
        try (Stream<Path> walk = Files.walk(baseDir, 10)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> shouldIndex(p, baseDir, ignoredDirs, ignoredFiles))
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

    private boolean shouldIndex(Path file, Path baseDir, Set<String> ignoredDirs, Set<String> ignoredFiles) {
        String fileName = file.getFileName().toString();

        // 排除所有以 . 开头的文件和目录
        if (fileName.startsWith(".")) return false;

        Path relative = baseDir.relativize(file);
        String relativeStr = relative.toString().replace("\\", "/");
        
        // 精确匹配排除文件（必须是完整路径或文件名完全相同）
        if (ignoredFiles.contains(relativeStr) || ignoredFiles.contains(fileName)) {
            log.debug("[NoteIndex] 排除文件: {} (精确匹配)", relativeStr);
            return false;
        }
        
        // 检查文件夹排除列表
        for (String ignored : ignoredDirs) {
            if (relativeStr.startsWith(ignored + "/") || relativeStr.equals(ignored)) {
                log.debug("[NoteIndex] 排除文件: {} (匹配文件夹: {})", relativeStr, ignored);
                return false;
            }
        }
        
        // 检查每个目录段
        for (Path segment : relative) {
            String segStr = segment.toString();
            if (segStr.startsWith(".")) return false;
            if (ignoredDirs.contains(segStr)) return false;
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

        // 提取路径特征信息：上级文件夹名称
        String pathContext = extractPathContext(relativePath);

        // 如果文件已存在且内容未变，检查是否需要重新索引
        if (!existing.isEmpty() && existing.get(0).getContentHash().equals(contentHash)) {
            String firstChunkContent = existing.get(0).getContent();
            if (firstChunkContent != null && firstChunkContent.startsWith(pathContext)) {
                log.debug("[NoteIndex] 跳过未变更文件: {}", relativePath);
                return;
            }
            log.info("[NoteIndex] 文件内容未变但需重新索引（添加路径信息）: {}", relativePath);
        }

        noteEmbeddingDbService.deleteByKbAndPath(kbId, relativePath);
        
        List<String> chunks = chunkContent(content);
        String now = TimeUtil.nowStr();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            // 将路径信息加入内容，提高搜索相关性
            String enhancedContent = pathContext + "\n" + chunk;
            float[] vector = embeddingService.embed(enhancedContent);
            if (vector == null) {
                log.warn("[NoteIndex] 生成向量失败: {} chunk={}", relativePath, i);
                continue;
            }

            NoteEmbeddingEntity entity = new NoteEmbeddingEntity();
            entity.setKbId(kbId);
            entity.setFilePath(relativePath);
            entity.setChunkIndex(i);
            entity.setPathContext(pathContext);
            entity.setContent(chunk);  // 存储纯内容，不含路径
            entity.setEmbedding(Base64.getEncoder().encodeToString(floatToBytes(vector)));
            entity.setContentHash(contentHash);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);

            noteEmbeddingDbService.save(entity);
        }

        log.debug("[NoteIndex] 已索引: {} ({} 个片段, 路径上下文: {})", relativePath, chunks.size(), pathContext);
    }

    /**
     * 提取路径上下文信息
     * 例如：项目/北京本数科技/会议记录.md → "项目 北京本数科技"
     */
    private String extractPathContext(String relativePath) {
        // 移除文件扩展名
        String pathWithoutExt = relativePath.replaceAll("\\.[^.]+$", "");
        // 用空格替换路径分隔符
        String pathContext = pathWithoutExt.replace("/", " ").replace("\\", " ");
        return pathContext;
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
        int checkedCount = 0;
        int matchedCount = 0;
        for (NoteEmbeddingEntity entity : allEntities) {
            float[] vec = bytesToFloat(Base64.getDecoder().decode(entity.getEmbedding()));
            float score = cosineSimilarity(queryVector, vec);
            checkedCount++;
            if (score >= SIMILARITY_THRESHOLD) {
                matchedCount++;
                scored.add(new ScoredChunk(entity, score));
                log.info("[NoteIndex] 匹配: file={}, score={}, path={}", 
                        entity.getFilePath(), String.format("%.3f", score), entity.getPathContext());
            }
        }
        log.info("[NoteIndex] 搜索完成: 总文件={}, 阈值={}, 匹配数={}", checkedCount, SIMILARITY_THRESHOLD, matchedCount);

        // 按相似度降序排序
        scored.sort((a, b) -> Float.compare(b.score, a.score));

        Map<String, Integer> perFileCount = new HashMap<>();
        List<NoteSearchResult> results = new ArrayList<>();

        for (ScoredChunk chunk : scored) {
            int count = perFileCount.getOrDefault(chunk.entity.getFilePath(), 0);
            if (count >= 1) continue;  // 每个文件只返回最高分的一条

            perFileCount.put(chunk.entity.getFilePath(), count + 1);
            results.add(new NoteSearchResult(
                    chunk.entity.getFilePath(),
                    chunk.entity.getPathContext(),
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
        // 查询扩展：生成多个相关查询
        List<String> expandedQueries = expandQuery(query);
        log.info("[NoteIndex] 查询扩展: 原始={}, 扩展={}", query, expandedQueries);

        Map<String, NoteSearchResult> resultMap = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();

        // 1. 只对原始查询做语义搜索（embedding 很慢，只做一次）
        List<NoteSearchResult> semanticResults = search(kbId, query, limit * 2);

        for (NoteSearchResult r : semanticResults) {
            String key = r.filePath();
            double score = r.score();

            // 检查路径是否包含查询关键词
            String pathLower = r.pathContext() != null ? r.pathContext().toLowerCase() : "";
            boolean pathMatch = isPathMatchPath(pathLower, query.toLowerCase());

            if (pathMatch) {
                score = Math.max(score, 0.9);
            }

            scores.put(key, score);
            resultMap.put(key, r);
        }

        // 2. 原始查询走关键词兜底（embedding 可能漏掉的精确匹配）
        List<NoteSearchResult> originalKeywordResults = keywordSearch(kbId, query, limit * 2);
        for (NoteSearchResult r : originalKeywordResults) {
            String key = r.filePath();
            if (!scores.containsKey(key)) {
                String pathLower = r.pathContext() != null ? r.pathContext().toLowerCase() : "";
                boolean pathMatch = isPathMatchPath(pathLower, query.toLowerCase());
                scores.put(key, pathMatch ? 0.85 : 0.5);
                resultMap.put(key, r);
            }
        }

        // 3. 扩展查询只用关键词搜索（瞬间完成），对已有结果加分或补充新结果
        for (String q : expandedQueries) {
            if (q.equals(query)) continue; // 原始查询已处理

            List<NoteSearchResult> keywordResults = keywordSearch(kbId, q, limit * 2);

            for (NoteSearchResult r : keywordResults) {
                String key = r.filePath();

                if (scores.containsKey(key)) {
                    // 已被语义搜索覆盖，关键词加分
                    String pathLower = r.pathContext() != null ? r.pathContext().toLowerCase() : "";
                    boolean pathMatch = isPathMatchPath(pathLower, q.toLowerCase());
                    double bonus = pathMatch ? 0.4 : 0.2;
                    double newScore = Math.min(1.0, scores.get(key) + bonus);
                    scores.put(key, newScore);
                } else {
                    // 语义未命中但关键词命中的结果
                    String pathLower = r.pathContext() != null ? r.pathContext().toLowerCase() : "";
                    boolean pathMatch = isPathMatchPath(pathLower, q.toLowerCase());
                    double score = pathMatch ? 0.85 : 0.5;
                    scores.put(key, score);
                    resultMap.put(key, r);
                }
            }
        }

        // 重排序：按分数降序排列
        return scores.entrySet().stream()
                .filter(e -> e.getValue() >= 0.3)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    NoteSearchResult original = resultMap.get(e.getKey());
                    if (original != null) {
                        return new NoteSearchResult(
                                original.filePath(),
                                original.pathContext(),
                                original.content(),
                                e.getValue().floatValue(),
                                original.chunkIndex(),
                                original.totalChunks()
                        );
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 查询扩展：生成多个相关查询
     */
    private List<String> expandQuery(String query) {
        List<String> queries = new ArrayList<>();
        queries.add(query);  // 原始查询

        // 策略1：提取核心关键词
        String[] words = query.split("[\\s,，、]+");
        if (words.length > 1) {
            for (String word : words) {
                if (word.length() >= 2) {
                    queries.add(word);
                }
            }
        }

        // 策略2：添加常见后缀（仅限有意义的前2个，避免过多无用查询）
        String[] suffixes = {"项目", "记录"};
        for (String suffix : suffixes) {
            if (!query.endsWith(suffix)) {
                queries.add(query + suffix);
            }
        }

        // 策略3：如果有空格，尝试组合
        if (query.contains(" ")) {
            String[] parts = query.split("\\s+");
            if (parts.length == 2) {
                queries.add(parts[0] + parts[1]);
                queries.add(parts[1] + parts[0]);
            }
        }

        return queries.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 智能路径匹配：检查路径是否包含查询的所有关键词
     */
    private boolean isPathMatchPath(String pathLower, String queryLower) {
        if (pathLower == null || queryLower == null) return false;
        
        // 直接包含
        if (pathLower.contains(queryLower)) return true;
        
        // 分词后检查是否所有词都在路径中
        String[] queryWords = queryLower.split("[\\s,，、]+");
        if (queryWords.length <= 1) return false;
        
        for (String word : queryWords) {
            if (word.length() >= 2 && !pathLower.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private List<NoteSearchResult> keywordSearch(Long kbId, String query, int limit) {
        String notesDir = kbService.getNotesDirById(kbId);
        if (notesDir == null || notesDir.isBlank()) return List.of();

        Path baseDir = Paths.get(notesDir);
        List<NoteSearchResult> results = new ArrayList<>();
        String queryLower = query.toLowerCase();
        Set<String> ignoredDirs = getIgnoredDirs(kbId);
        Set<String> ignoredFiles = getIgnoredFiles(kbId);

        // 从数据库读取所有索引记录，用于检查路径上下文
        List<NoteEmbeddingEntity> allEntities = noteEmbeddingDbService.lambdaQuery()
                .eq(NoteEmbeddingEntity::getKbId, kbId)
                .list();
        Set<String> pathContextMatchedFiles = new HashSet<>();
        for (NoteEmbeddingEntity entity : allEntities) {
            if (entity.getPathContext() != null && entity.getPathContext().toLowerCase().contains(queryLower)) {
                pathContextMatchedFiles.add(entity.getFilePath());
            }
        }

        try (Stream<Path> walk = Files.walk(baseDir, 10)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> shouldIndex(p, baseDir, ignoredDirs, ignoredFiles))
                .forEach(file -> {
                    try {
                        String relativePath = baseDir.relativize(file).toString().replace("\\", "/");
                        
                        // 检查路径上下文是否包含关键词
                        boolean pathMatch = pathContextMatchedFiles.contains(relativePath);
                        
                        String content = FileUtil.readText(file);
                        if ((content == null || content.isBlank()) && !pathMatch) return;

                        String contentLower = content != null ? content.toLowerCase() : "";
                        boolean contentMatch = contentLower.contains(queryLower);
                        
                        if (!contentMatch && !pathMatch) return;

                        // 计算分数
                        float score = 0;
                        if (contentMatch) {
                            int matchCount = countOccurrences(contentLower, queryLower);
                            score = Math.min(1.0f, matchCount * 0.2f);
                        }
                        if (pathMatch) {
                            score = Math.max(score, 0.5f);
                        }

                        String pathContext = extractPathContext(relativePath);
                        String displayContent = contentMatch ? 
                            content.substring(0, Math.min(500, content.length())) : 
                            "[路径匹配] " + pathContext;

                        results.add(new NoteSearchResult(
                                relativePath,
                                pathContext,
                                displayContent,
                                score,
                                0,
                                0
                        ));
                    } catch (Exception e) {
                        log.debug("[NoteIndex] 关键词搜索文件失败: {}", file, e);
                    }
                });
        } catch (IOException e) {
            log.warn("[NoteIndex] 关键词搜索失败", e);
        }

        return results.stream()
                .sorted((a, b) -> Float.compare(b.score(), a.score()))
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
            String pathContext,
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
