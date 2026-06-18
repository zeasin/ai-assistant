package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.service.ConfigService;
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

import java.io.IOException;
import java.nio.file.*;
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
    private final ConfigService configService;
    private final KnowledgeBaseService kbService;
    private final LlmService llmService;
    private final LogService logService;
    private final LlmConfigResolver llmConfigResolver;

    public ImageRecognitionController(AppConfig appConfig, ConfigService configService,
                                       KnowledgeBaseService kbService,
                                       LlmService llmService, LogService logService,
                                       LlmConfigResolver llmConfigResolver) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.kbService = kbService;
        this.llmService = llmService;
        this.logService = logService;
        this.llmConfigResolver = llmConfigResolver;
    }

    @GetMapping
    public String page(@RequestParam(required = false) Long kbId, Model model) {
        // 无 kbId 时重定向到带 kbId 的 URL
        if (kbId == null) {
            KnowledgeBaseEntity first = kbService.getFirst();
            if (first == null) return "redirect:/config";
            return "redirect:/image?kbId=" + first.getId();
        }

        KnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) return "redirect:/config";

        model.addAttribute("currentKb", kb);
        model.addAttribute("currentKbId", kb.getId());
        model.addAttribute("currentKbName", kb.getName());

        List<LlmProfileEntity> allProfiles = llmConfigResolver.getAllProfiles();
        List<LlmProfileEntity> visionModels = allProfiles.stream()
                .filter(p -> p.isMultimodal())
                .collect(Collectors.toList());
        model.addAttribute("visionModels", visionModels);
        return "image";
    }

    /** 列出指定 KB 当前目录下的图片文件（仅当前层，不递归） */
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
                                                                      @RequestParam(required = false, defaultValue = "0") Long kbId) {
        try {
            Path baseDir = getKbBasePath(kbId);
            if (baseDir == null) baseDir = Paths.get(configService.getNotesDir());
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

    /** 获取指定 KB 的完整目录树（嵌套结构，含根节点） */
    @GetMapping("/api/dir-tree")
    @ResponseBody
    public Map<String, Object> getDirTree(@RequestParam Long kbId) {
        Path basePath = getKbBasePath(kbId);
        if (basePath == null) {
            return Map.of("ok", false, "error", "知识库不存在");
        }
        // 根节点
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
            // skip inaccessible directories
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

            if (!llmService.isAvailable()) {
                log.warn("识图失败: LLM API Key 未配置");
                return Map.of("ok", false, "error", "LLM API Key 未配置");
            }
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String systemPrompt = "你是一个专业的图片分析助手，请根据用户指定的场景详细分析图片内容，用中文回答。";
            String reply = llmService.chatWithImage(systemPrompt, userPrompt, base64Image, imageType, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String imageUrl = "data:" + imageType + ";base64," + base64Image;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            log.info("识图成功: model={}, file={}, 耗时={}ms, 响应长度={}chars",
                    modelName, fileName, elapsed, result.length());
            log.debug("识图响应内容 (前200字): {}",
                    result.length() > 200 ? result.substring(0, 200) + "..." : result);

            logService.add("识图分析", "成功", "上传图片识别");
            return Map.of("ok", true, "image_url", imageUrl, "result", result);
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
            Long kbId = body.containsKey("kbId") ? Long.parseLong(body.get("kbId")) : 0L;
            String prompt = body.getOrDefault("prompt", "请详细分析这张图片的内容");

            Path baseDir = getKbBasePath(kbId);
            if (baseDir == null) baseDir = Paths.get(configService.getNotesDir());
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

            if (!llmService.isAvailable()) {
                log.warn("识图失败: LLM API Key 未配置");
                return Map.of("ok", false, "error", "LLM API Key 未配置");
            }
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String systemPrompt = "你是一个专业的图片分析助手，请根据用户指定的场景详细分析图片内容，用中文回答。";
            String reply = llmService.chatWithImage(systemPrompt, prompt, base64Image, imageType, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String imageUrl = "/image/thumb?path=" + java.net.URLEncoder.encode(filePath, "UTF-8")
                    + "&kbId=" + kbId;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            log.info("识图成功: model={}, file={}, 耗时={}ms, 响应长度={}chars",
                    modelName, filePath, elapsed, result.length());
            log.debug("识图响应内容 (前200字): {}",
                    result.length() > 200 ? result.substring(0, 200) + "..." : result);

            logService.add("识图分析", "成功", filePath);
            return Map.of("ok", true, "image_url", imageUrl, "result", result);
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
            Long kbId = body.containsKey("kbId") ? Long.parseLong(body.get("kbId")) : 0L;

            Path baseDir = getKbBasePath(kbId);
            if (baseDir == null) baseDir = Paths.get(configService.getNotesDir());
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
        if (kbId != null && kbId > 0) {
            KnowledgeBaseEntity kb = kbService.getById(kbId);
            if (kb != null) {
                Path p = Paths.get(kb.getNotesDir());
                if (Files.isDirectory(p)) return p;
            }
        }
        try {
            return Paths.get(configService.getNotesDir());
        } catch (Exception e) {
            return null;
        }
    }

    private String guessImageType(String name) {
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}