package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.OpenCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

@Controller
@RequestMapping("/image-recognition")
public class ImageRecognitionController {

    private static final Logger log = LoggerFactory.getLogger(ImageRecognitionController.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".svg", ".ico");

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final OpenCodeService openCodeService;
    private final LogService logService;

    public ImageRecognitionController(AppConfig appConfig, ConfigService configService,
                                       OpenCodeService openCodeService, LogService logService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.logService = logService;
    }

    @GetMapping
    public String page(@RequestParam(required = false, defaultValue = "") String dir,
                       Model model) {
        String baseDir = configService.getBaseDir();
        Path basePath = Paths.get(baseDir);

        if (!Files.isDirectory(basePath)) {
            model.addAttribute("error", "笔记库目录不存在，请先在配置页面设置");
            return "image_recognition";
        }

        List<Map<String, Object>> dirs = new ArrayList<>();
        List<Map<String, Object>> images = new ArrayList<>();

        // Normalize dir - if directory doesn't exist, reset to base
        Path targetDir = dir.isEmpty() ? basePath : basePath.resolve(dir);
        if (!Files.isDirectory(targetDir)) {
            targetDir = basePath;
            dir = "";
        }
        final String finalDir = dir;

        try {
            try (var stream = Files.list(targetDir)) {
                stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            if (name.startsWith(".")) return;

                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", name);

                            if (Files.isDirectory(p)) {
                                entry.put("is_dir", true);
                                entry.put("full_path", finalDir.isEmpty() ? name : finalDir + "/" + name);
                                dirs.add(entry);
                            } else {
                                String lowerName = name.toLowerCase();
                                if (IMAGE_EXTENSIONS.stream().anyMatch(lowerName::endsWith)) {
                                    entry.put("is_dir", false);
                                    entry.put("rel_path", finalDir.isEmpty() ? name : finalDir + "/" + name);
                                    images.add(entry);
                                }
                            }
                        });
            }
        } catch (IOException e) {
            log.error("遍历笔记库目录失败: {}", e.getMessage());
            model.addAttribute("error", "读取目录失败: " + e.getMessage());
        }

        String parent = finalDir.contains("/") ? finalDir.substring(0, finalDir.lastIndexOf('/')) : "";
        String[] parts = finalDir.isEmpty() ? new String[0] : finalDir.split("/");
        List<String> breadcrumbs = Arrays.asList(parts);
        List<String> breadcrumbPaths = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            breadcrumbPaths.add(String.join("/", Arrays.copyOf(parts, i + 1)));
        }

        model.addAttribute("dirs", dirs);
        model.addAttribute("images", images);
        model.addAttribute("current_dir", finalDir);
        model.addAttribute("parent", parent);
        model.addAttribute("breadcrumbs", breadcrumbs);
        model.addAttribute("breadcrumbPaths", breadcrumbPaths);
        return "image_recognition";
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

    @GetMapping("/thumb")
    @org.springframework.web.bind.annotation.ResponseBody
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

    @PostMapping("/recognize")
    public SseEmitter recognize(@RequestParam(name = "image", required = false) MultipartFile image,
                                 @RequestParam(name = "image_path", required = false) String imagePath,
                                 @RequestParam(required = false, defaultValue = "") String prompt) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> log.info("[image-recognition] SSE 完成"));
        emitter.onError(ex -> log.error("[image-recognition] SSE 错误", ex));

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (!openCodeService.isHealthy()) {
                    sendError(emitter, "AI 服务未启动，请确保 opencode serve 已在端口 " + appConfig.getNotesPort() + " 运行");
                    return;
                }

                byte[] imageBytes;
                String imageType;

                if (image != null && !image.isEmpty()) {
                    imageBytes = image.getBytes();
                    imageType = image.getContentType();
                    if (imageType == null || imageType.isEmpty()) {
                        imageType = "image/jpeg";
                    }
                    log.info("[image-recognition] 用户上传图片, size={}, type={}", imageBytes.length, imageType);
                } else if (imagePath != null && !imagePath.isEmpty()) {
                    Path baseDir = Paths.get(configService.getBaseDir());
                    Path file = baseDir.resolve(imagePath).normalize();
                    if (!file.startsWith(baseDir) || !Files.exists(file)) {
                        sendError(emitter, "图片文件不存在: " + imagePath);
                        return;
                    }
                    imageBytes = Files.readAllBytes(file);
                    String name = file.getFileName().toString().toLowerCase();
                    imageType = guessImageType(name);
                    log.info("[image-recognition] 从笔记库读取图片, path={}, size={}", imagePath, imageBytes.length);
                } else {
                    sendError(emitter, "请选择图片文件或输入图片路径");
                    return;
                }

                String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                String userPrompt = (prompt == null || prompt.trim().isEmpty())
                        ? "请详细分析这张图片的内容，包括：1. 图片中有什么 2. 关键信息提取 3. 如果有文字请OCR识别出来 4. 如果有数据/表格请格式化输出"
                        : prompt;

                sendStatus(emitter, "⏳ 正在分析图片...");

                String opencodeSessionId = openCodeService.createSession("image-recognition-" + System.currentTimeMillis());
                String fullMessage = "用户消息:\n" + userPrompt;
                String reply = openCodeService.sendMessageWithImage(opencodeSessionId, fullMessage, base64Image, imageType);

                if (reply != null && !reply.isEmpty()) {
                    sendText(emitter, reply);
                } else {
                    sendText(emitter, "(AI 未返回分析结果)");
                }

                sendDone(emitter);

            } catch (Exception e) {
                log.error("[image-recognition] 处理失败", e);
                try {
                    sendError(emitter, "AI 服务调用失败: " + e.getMessage());
                } catch (Exception ex) {
                    log.error("[image-recognition] 发送错误失败", ex);
                    try { emitter.completeWithError(ex); } catch (Exception e2) { /* ignore */ }
                }
            }
        });

        return emitter;
    }

    @PostMapping("/save")
    @ResponseBody
    public Map<String, Object> saveResult(@RequestParam String path,
                                           @RequestParam String content,
                                           @RequestParam(required = false, defaultValue = "识图分析") String title) {
        try {
            Path baseDir = Paths.get(configService.getBaseDir());
            Path file = baseDir.resolve(path).normalize();

            if (!file.startsWith(baseDir)) {
                return Map.of("ok", false, "error", "路径不合法");
            }

            Files.createDirectories(file.getParent());
            String fullContent = "---\ntitle: " + title + "\ndate: " + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\ntags: [识图分析]\n---\n\n"
                    + content;
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
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }

    private void sendStatus(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().data(Map.of("type", "status", "content", text)));
        } catch (Exception e) {
            log.warn("发送状态失败", e);
        }
    }

    private void sendText(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().data(Map.of("type", "text", "content", text)));
        } catch (Exception e) {
            log.error("发送文本失败", e);
        }
    }

    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().data(Map.of("type", "done")));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送完成失败", e);
            try { emitter.complete(); } catch (Exception ex) { /* ignore */ }
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().data(Map.of("type", "error", "content", message)));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送错误失败", e);
            try { emitter.complete(); } catch (Exception ex) { /* ignore */ }
        }
    }
}
