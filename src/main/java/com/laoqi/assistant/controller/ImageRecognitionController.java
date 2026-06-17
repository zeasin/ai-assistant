package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LlmService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.OpenCodeService;
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
@RequestMapping("/image-recognition")
public class ImageRecognitionController {

    private static final Logger log = LoggerFactory.getLogger(ImageRecognitionController.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp");

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final OpenCodeService openCodeService;
    private final LlmService llmService;
    private final LogService logService;

    public ImageRecognitionController(AppConfig appConfig, ConfigService configService,
                                       OpenCodeService openCodeService, LlmService llmService,
                                       LogService logService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.llmService = llmService;
        this.logService = logService;
    }

    private boolean isDirectMode() {
        return "direct".equals(configService.load().getAiProvider());
    }

    @GetMapping
    public String page() {
        return "image_recognition";
    }

    @GetMapping("/api/images")
    @ResponseBody
    public Map<String, Object> listImages() {
        try {
            Path basePath = Paths.get(configService.getBaseDir());
            if (!Files.isDirectory(basePath)) {
                return Map.of("ok", false, "error", "笔记库目录不存在");
            }
            List<Map<String, String>> images = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(basePath)) {
                walk.filter(Files::isRegularFile)
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

    @GetMapping("/thumb")
    @ResponseBody
    public org.springframework.http.ResponseEntity<byte[]> thumbnail(@RequestParam String path) {
        try {
            Path baseDir = Paths.get(configService.getBaseDir());
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

    @GetMapping("/subdirs")
    @ResponseBody
    public Map<String, Object> getSubdirs(@RequestParam(required = false, defaultValue = "") String dir) {
        String baseDir = configService.getBaseDir();
        Path basePath = Paths.get(baseDir);
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

    @PostMapping("/recognize-sync")
    @ResponseBody
    public Map<String, Object> recognizeSync(@RequestParam("image") MultipartFile image,
                                              @RequestParam(value = "prompt", defaultValue = "") String prompt) {
        try {
            byte[] imageBytes = image.getBytes();
            String imageType = image.getContentType();
            if (imageType == null || imageType.isEmpty()) imageType = "image/jpeg";

            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String userPrompt = (prompt == null || prompt.trim().isEmpty())
                    ? "请详细分析这张图片的内容" : prompt;

            String reply;
            if (isDirectMode()) {
                if (!llmService.isAvailable()) {
                    return Map.of("ok", false, "error", "LLM API Key 未配置");
                }
                reply = llmService.chatWithImage("你是一个图片分析助手，请用中文回答。", userPrompt, base64Image, imageType);
            } else {
                if (!openCodeService.isHealthy()) {
                    return Map.of("ok", false, "error", "AI 服务未启动");
                }
                String sessionId = openCodeService.createSession("img-" + System.currentTimeMillis());
                reply = openCodeService.sendMessageWithImage(sessionId, userPrompt, base64Image, imageType);
            }

            String imageUrl = "data:" + imageType + ";base64," + base64Image;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            logService.add("识图分析", "成功", "上传图片");
            return Map.of("ok", true, "image_url", imageUrl, "result", result);
        } catch (Exception e) {
            log.error("识图分析失败", e);
            return Map.of("ok", false, "error", "AI 服务调用失败: " + e.getMessage());
        }
    }

    @PostMapping("/recognize-file")
    @ResponseBody
    public Map<String, Object> recognizeFile(@RequestBody Map<String, String> body) {
        try {
            String filePath = body.get("path");
            String prompt = body.getOrDefault("prompt", "请详细分析这张图片的内容");

            Path baseDir = Paths.get(configService.getBaseDir());
            Path file = baseDir.resolve(filePath).normalize();
            if (!file.startsWith(baseDir) || !Files.exists(file)) {
                return Map.of("ok", false, "error", "图片文件不存在");
            }

            byte[] imageBytes = Files.readAllBytes(file);
            String imageType = guessImageType(file.getFileName().toString().toLowerCase());
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            String reply;
            if (isDirectMode()) {
                if (!llmService.isAvailable()) {
                    return Map.of("ok", false, "error", "LLM API Key 未配置");
                }
                reply = llmService.chatWithImage("你是一个图片分析助手，请用中文回答。", prompt, base64Image, imageType);
            } else {
                if (!openCodeService.isHealthy()) {
                    return Map.of("ok", false, "error", "AI 服务未启动");
                }
                String sessionId = openCodeService.createSession("img-" + System.currentTimeMillis());
                reply = openCodeService.sendMessageWithImage(sessionId, prompt, base64Image, imageType);
            }

            String imageUrl = "/image-recognition/thumb?path=" + java.net.URLEncoder.encode(filePath, "UTF-8");
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            logService.add("识图分析", "成功", filePath);
            return Map.of("ok", true, "image_url", imageUrl, "result", result);
        } catch (Exception e) {
            log.error("识图分析失败", e);
            return Map.of("ok", false, "error", "AI 服务调用失败: " + e.getMessage());
        }
    }

    @PostMapping("/save")
    @ResponseBody
    public Map<String, Object> saveResult(@RequestBody Map<String, String> body) {
        try {
            String path = body.get("path");
            String content = body.get("content");

            Path baseDir = Paths.get(configService.getBaseDir());
            Path file = baseDir.resolve(path).normalize();
            if (!file.startsWith(baseDir)) {
                return Map.of("ok", false, "error", "路径不合法");
            }

            Files.createDirectories(file.getParent());
            String fullContent = "---\ntitle: 识图分析\ndate: " + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    + "\ntags: [识图分析]\n---\n\n" + content;
            Files.writeString(file, fullContent, java.nio.charset.StandardCharsets.UTF_8);

            logService.add("识图分析保存", "成功", path);
            return Map.of("ok", true, "path", path);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "保存失败: " + e.getMessage());
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
