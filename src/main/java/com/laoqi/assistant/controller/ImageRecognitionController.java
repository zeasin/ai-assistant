package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.LlmConfigResolver;
import com.laoqi.assistant.service.LlmService;
import com.laoqi.assistant.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
@RequestMapping("/image")
public class ImageRecognitionController {

    private static final Logger log = LoggerFactory.getLogger(ImageRecognitionController.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp");

    private final AppConfig appConfig;
    private final KnowledgeBaseService kbService;
    private final LlmService llmService;
    private final LogService logService;
    private final LlmConfigResolver llmConfigResolver;
    private final DataSource dataSource;

    public ImageRecognitionController(AppConfig appConfig,
                                       KnowledgeBaseService kbService,
                                       LlmService llmService, LogService logService,
                                       LlmConfigResolver llmConfigResolver,
                                       DataSource dataSource) {
        this.appConfig = appConfig;
        this.kbService = kbService;
        this.llmService = llmService;
        this.logService = logService;
        this.llmConfigResolver = llmConfigResolver;
        this.dataSource = dataSource;
    }

    @GetMapping
    public String page(@RequestParam(required = false) Long kbId, Model model) {
        if (!llmService.isAvailable()) {
            return "redirect:/config#ai-model-section";
        }

        KnowledgeBaseEntity kb = null;
        if (kbId != null) {
            kb = kbService.getById(kbId);
        }
        if (kb == null) {
            kb = kbService.getFirst();
        }

        if (kb != null) {
            model.addAttribute("currentKb", kb);
            model.addAttribute("currentKbId", kb.getId());
            model.addAttribute("currentKbName", kb.getName());
            model.addAttribute("kbReady", true);
        } else {
            model.addAttribute("currentKb", null);
            model.addAttribute("currentKbId", null);
            model.addAttribute("currentKbName", null);
            model.addAttribute("kbReady", false);
        }

        List<LlmProfileEntity> allProfiles = llmConfigResolver.getAllProfiles();
        List<LlmProfileEntity> visionModels = allProfiles.stream()
                .filter(p -> p.isMultimodal())
                .collect(Collectors.toList());
        model.addAttribute("visionModels", visionModels);
        return "image";
    }

    // ========== SQLite CRUD ==========

    private long insertAnalysis(String imageName, String imagePath, String imageType,
                                 String prompt, String model, String source, Long kbId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO image_analyses (image_name, image_path, image_type, prompt, model, source, kb_id, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, imageName);
            ps.setString(2, imagePath);
            ps.setString(3, imageType);
            ps.setString(4, prompt);
            ps.setString(5, model);
            ps.setString(6, source);
            if (kbId != null) {
                ps.setLong(7, kbId);
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.setString(8, java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("[识图] 插入记录失败", e);
        }
        return -1;
    }

    private void updateAnalysisResult(long id, String result, String status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE image_analyses SET result=?, status=?, completed_at=? WHERE id=?")) {
            ps.setString(1, result);
            ps.setString(2, status);
            ps.setString(3, java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[识图] 更新记录失败", e);
        }
    }

    private List<Map<String, Object>> getAnalysesFromDb(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM image_analyses ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("imageName", rs.getString("image_name"));
                m.put("imagePath", rs.getString("image_path"));
                m.put("imageType", rs.getString("image_type"));
                m.put("prompt", rs.getString("prompt"));
                m.put("result", rs.getString("result"));
                m.put("model", rs.getString("model"));
                m.put("source", rs.getString("source"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getString("created_at"));
                m.put("completedAt", rs.getString("completed_at"));
                list.add(m);
            }
        } catch (SQLException e) {
            log.error("[识图] 查询历史失败", e);
        }
        return list;
    }

    // ========== API ==========

    @GetMapping("/api/history")
    @ResponseBody
    public Map<String, Object> getHistory(
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return Map.of("ok", true, "analyses", getAnalysesFromDb(limit));
    }

    /** 列出指定 KB 当前目录下的图片文件 */
    @GetMapping("/api/images")
    @ResponseBody
    public Map<String, Object> listImages(@RequestParam(required = false, defaultValue = "0") Long kbId,
                                           @RequestParam(required = false, defaultValue = "") String dir) {
        try {
            Path basePath = getKbBasePath(kbId);
            if (basePath == null) {
                return Map.of("ok", false, "error", "知识库不存在");
            }
            Path searchDir = dir.isEmpty() ? basePath : basePath.resolve(dir).normalize();
            if (!searchDir.startsWith(basePath) || !Files.isDirectory(searchDir)) {
                return Map.of("ok", false, "error", "目录不存在");
            }

            List<Map<String, String>> images = new ArrayList<>();
            try (Stream<Path> list = Files.list(searchDir)) {
                list.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith);
                        })
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> {
                            Map<String, String> entry = new LinkedHashMap<>();
                            entry.put("name", p.getFileName().toString());
                            entry.put("path", basePath.relativize(p).toString().replace("\\", "/"));
                            images.add(entry);
                        });
            }
            return Map.of("ok", true, "images", images);
        } catch (IOException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /** 图片缩略图 */
    @GetMapping("/thumb")
    @ResponseBody
    public org.springframework.http.ResponseEntity<byte[]> thumbnail(@RequestParam String path,
                                                                      @RequestParam Long kbId) {
        try {
            Path baseDir = getKbBasePath(kbId);
            Path file = baseDir.resolve(path).normalize();
            if (!file.startsWith(baseDir) || !Files.exists(file)) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            byte[] bytes = Files.readAllBytes(file);
            String name = file.getFileName().toString().toLowerCase();
            String contentType = guessImageType(name);
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .header("Cache-Control", "max-age=3600")
                    .body(bytes);
        } catch (IOException e) {
            return org.springframework.http.ResponseEntity.status(500).build();
        }
    }

    /** 获取指定 KB 的完整目录树 */
    @GetMapping("/api/dir-tree")
    @ResponseBody
    public Map<String, Object> getDirTree(@RequestParam Long kbId) {
        Path basePath = getKbBasePath(kbId);
        if (basePath == null) {
            return Map.of("ok", false, "error", "知识库不存在");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "根目录");
        root.put("path", "");
        root.put("children", buildDirTree(basePath, basePath, "", 0));
        return Map.of("ok", true, "tree", List.of(root));
    }

    private List<Map<String, Object>> buildDirTree(Path basePath, Path currentDir, String relPath, int depth) {
        if (depth > 20) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.list(currentDir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String rel = relPath.isEmpty() ? p.getFileName().toString() : relPath + "/" + p.getFileName().toString();
                        Map<String, Object> node = new LinkedHashMap<>();
                        node.put("name", p.getFileName().toString());
                        node.put("path", rel);
                        node.put("children", buildDirTree(basePath, p, rel, depth + 1));
                        result.add(node);
                    });
        } catch (IOException e) {
            // skip
        }
        return result;
    }

    /** 列出指定 KB 下的子目录 */
    @GetMapping("/subdirs")
    @ResponseBody
    public Map<String, Object> getSubdirs(@RequestParam(required = false, defaultValue = "0") Long kbId,
                                           @RequestParam(required = false, defaultValue = "") String dir) {
        Path basePath = getKbBasePath(kbId);
        if (basePath == null) {
            return Map.of("ok", false, "error", "知识库不存在");
        }
        Path targetDir = dir.isEmpty() ? basePath : basePath.resolve(dir);
        if (!Files.isDirectory(targetDir)) {
            return Map.of("ok", false, "error", "目录不存在");
        }

        List<Map<String, String>> subdirs = new ArrayList<>();
        try (var stream = Files.list(targetDir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("name", p.getFileName().toString());
                        entry.put("path", dir.isEmpty() ? p.getFileName().toString() : dir + "/" + p.getFileName().toString());
                        subdirs.add(entry);
                    });
        } catch (IOException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
        return Map.of("ok", true, "subdirs", subdirs);
    }

    /** 上传图片并识别 */
    @PostMapping("/recognize-sync")
    @ResponseBody
    public Map<String, Object> recognizeSync(@RequestParam("image") MultipartFile image,
                                              @RequestParam(value = "prompt", defaultValue = "") String prompt,
                                              @RequestParam(value = "modelName", defaultValue = "") String modelName) {
        long startMs = System.currentTimeMillis();
        try {
            byte[] imageBytes = image.getBytes();
            String imageType = image.getContentType();
            if (imageType == null || imageType.isEmpty()) imageType = "image/jpeg";
            String fileName = image.getOriginalFilename();

            String userPrompt = (prompt == null || prompt.trim().isEmpty())
                    ? "请详细分析这张图片的内容，用中文回答。" : prompt;

            log.info("识图请求: model={}, file={}, type={}, size={}KB, prompt=\"{}\"",
                    modelName, fileName, imageType, imageBytes.length / 1024,
                    userPrompt.length() > 60 ? userPrompt.substring(0, 60) + "..." : userPrompt);

            // 创建 pending 记录
            long recordId = insertAnalysis(fileName, "", imageType, userPrompt, modelName, "upload", null);

            if (!llmService.isAvailable()) {
                if (recordId > 0) updateAnalysisResult(recordId, "LLM API Key 未配置", "failed");
                return Map.of("ok", false, "error", "LLM API Key 未配置");
            }
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String systemPrompt = "你是一个专业的图片分析助手，请根据用户指定的场景详细分析图片内容，用中文回答。";
            String reply = llmService.chatWithImage(systemPrompt, userPrompt, base64Image, imageType, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String imageUrl = "data:" + imageType + ";base64," + base64Image;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            // 更新记录
            if (recordId > 0) updateAnalysisResult(recordId, result, "completed");

            log.info("识图成功: model={}, file={}, 耗时={}ms, 响应长度={}chars",
                    modelName, fileName, elapsed, result.length());

            logService.add("识图分析", "成功", "上传图片识别");
            return Map.of("ok", true, "image_url", imageUrl, "result", result, "recordId", recordId);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("识图失败: model={}, 耗时={}ms, 错误={}", modelName, elapsed, e.getMessage());
            return Map.of("ok", false, "error", "AI 服务调用失败: " + e.getMessage());
        }
    }

    /** 识别知识库中的图片 */
    @PostMapping("/recognize-file")
    @ResponseBody
    public Map<String, Object> recognizeFile(@RequestBody Map<String, String> body) {
        long startMs = System.currentTimeMillis();
        String filePath = body.getOrDefault("path", "");
        String modelName = body.getOrDefault("modelName", "");
        try {
            Long kbId = body.containsKey("kbId") ? Long.parseLong(body.get("kbId")) : null;
            String prompt = body.getOrDefault("prompt", "请详细分析这张图片的内容");

            Path baseDir = getKbBasePath(kbId);
            Path file = baseDir.resolve(filePath).normalize();
            if (!file.startsWith(baseDir) || !Files.exists(file)) {
                log.warn("识图失败: 文件不存在 path={}", filePath);
                return Map.of("ok", false, "error", "图片文件不存在");
            }

            byte[] imageBytes = Files.readAllBytes(file);
            String imageType = guessImageType(file.getFileName().toString().toLowerCase());

            log.info("识图请求: model={}, file={}, type={}, size={}KB, prompt=\"{}\"",
                    modelName, filePath, imageType, imageBytes.length / 1024,
                    prompt.length() > 60 ? prompt.substring(0, 60) + "..." : prompt);

            // 创建 pending 记录
            long recordId = insertAnalysis(file.getFileName().toString(), filePath, imageType, prompt, modelName, "kb", kbId);

            if (!llmService.isAvailable()) {
                if (recordId > 0) updateAnalysisResult(recordId, "LLM API Key 未配置", "failed");
                return Map.of("ok", false, "error", "LLM API Key 未配置");
            }
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String systemPrompt = "你是一个专业的图片分析助手，请根据用户指定的场景详细分析图片内容，用中文回答。";
            String reply = llmService.chatWithImage(systemPrompt, prompt, base64Image, imageType, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String imageUrl = "/image/thumb?path=" + java.net.URLEncoder.encode(filePath, "UTF-8")
                    + "&kbId=" + kbId;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            // 更新记录
            if (recordId > 0) updateAnalysisResult(recordId, result, "completed");

            log.info("识图成功: model={}, file={}, 耗时={}ms, 响应长度={}chars",
                    modelName, filePath, elapsed, result.length());

            logService.add("识图分析", "成功", filePath);
            return Map.of("ok", true, "image_url", imageUrl, "result", result, "recordId", recordId);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("识图失败: model={}, file={}, 耗时={}ms, 错误={}", modelName, filePath, elapsed, e.getMessage());
            return Map.of("ok", false, "error", "AI 服务调用失败: " + e.getMessage());
        }
    }

    /** 保存分析结果到指定 KB */
    @PostMapping("/save")
    @ResponseBody
    public Map<String, Object> saveResult(@RequestBody Map<String, String> body) {
        try {
            String path = body.get("path");
            String content = body.get("content");
            Long kbId = body.containsKey("kbId") ? Long.parseLong(body.get("kbId")) : null;

            Path baseDir = getKbBasePath(kbId);
            Path file = baseDir.resolve(path).normalize();
            if (!file.startsWith(baseDir)) {
                return Map.of("ok", false, "error", "路径不合法");
            }

            Files.createDirectories(file.getParent());
            String now = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String fullContent = "---\ntitle: 识图分析\ndate: " + now
                    + "\ntags: [识图分析]\n---\n\n" + content;
            Files.writeString(file, fullContent, java.nio.charset.StandardCharsets.UTF_8);

            logService.add("识图分析保存", "成功", path);
            return Map.of("ok", true, "path", path);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "保存失败: " + e.getMessage());
        }
    }

    /** 获取所有知识库 */
    @GetMapping("/api/kb-list")
    @ResponseBody
    public Map<String, Object> kbList() {
        List<KnowledgeBaseEntity> all = kbService.getAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeBaseEntity kb : all) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", kb.getId());
            item.put("name", kb.getName());
            item.put("notesDir", kb.getNotesDir());
            result.add(item);
        }
        return Map.of("ok", true, "kbs", result);
    }

    // ========== 私有方法 ==========

    private Path getKbBasePath(Long kbId) {
        if (kbId == null || kbId <= 0) {
            throw new IllegalStateException("缺少 kbId 参数");
        }
        KnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new IllegalStateException("知识库不存在: kbId=" + kbId);
        }
        Path p = Paths.get(kb.getNotesDir());
        if (!Files.isDirectory(p)) {
            throw new IllegalStateException("笔记库目录不存在: " + kb.getNotesDir());
        }
        return p;
    }

    private String guessImageType(String name) {
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
