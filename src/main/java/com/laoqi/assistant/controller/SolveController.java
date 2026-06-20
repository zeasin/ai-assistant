package com.laoqi.assistant.controller;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/image/solve")
public class SolveController {

    private static final Logger log = LoggerFactory.getLogger(SolveController.class);

    private final LlmService llmService;
    private final LlmConfigResolver llmConfigResolver;
    private final KnowledgeBaseService kbService;
    private final LogService logService;

    public SolveController(LlmService llmService, LlmConfigResolver llmConfigResolver,
                           KnowledgeBaseService kbService, LogService logService) {
        this.llmService = llmService;
        this.llmConfigResolver = llmConfigResolver;
        this.kbService = kbService;
        this.logService = logService;
    }

    @GetMapping
    public String page(Model model) {
        if (!llmService.isAvailable()) {
            return "redirect:/config#ai-model-section";
        }
        List<LlmProfileEntity> allProfiles = llmConfigResolver.getAllProfiles();
        List<LlmProfileEntity> visionModels = allProfiles.stream()
                .filter(LlmProfileEntity::isMultimodal)
                .collect(Collectors.toList());
        model.addAttribute("visionModels", visionModels);
        LlmProfileEntity defaultProfile = llmConfigResolver.getDefaultProfile();
        model.addAttribute("defaultModel", defaultProfile != null ? defaultProfile.getName() : "");
        return "solve";
    }

    @PostMapping("/api/recognize")
    @ResponseBody
    public Map<String, Object> recognize(@RequestParam("image") MultipartFile image,
                                          @RequestParam(value = "modelName", defaultValue = "") String modelName) {
        long startMs = System.currentTimeMillis();
        try {
            byte[] imageBytes = image.getBytes();
            String imageType = image.getContentType();
            if (imageType == null || imageType.isEmpty()) imageType = "image/jpeg";
            String fileName = image.getOriginalFilename();

            log.info("[识题] 识别请求: model={}, file={}, type={}, size={}KB",
                    modelName, fileName, imageType, imageBytes.length / 1024);

            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String systemPrompt = "你是一个专业的解题助手。请识别图片中的题目，给出详细的解答过程和最终答案。" +
                    "如果图片中有多道题，请逐一解答。用中文回答，Markdown 格式。" +
                    "解答格式要求：先给出题目内容，再给出解题思路，最后给出答案。";
            String userPrompt = "请识别并解答这道题。";

            String reply = llmService.chatWithImage(systemPrompt, userPrompt, base64Image, imageType, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            log.info("[识题] 识别成功: model={}, file={}, 耗时={}ms, 响应长度={}chars",
                    modelName, fileName, elapsed, result.length());

            logService.add("识题", "成功", "上传图片识别: " + fileName);

            List<Map<String, String>> conversation = new ArrayList<>();
            Map<String, String> sysMsg = new LinkedHashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            conversation.add(sysMsg);

            Map<String, String> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            userMsg.put("hasImage", "true");
            conversation.add(userMsg);

            Map<String, String> aiMsg = new LinkedHashMap<>();
            aiMsg.put("role", "assistant");
            aiMsg.put("content", reply != null ? reply : "");
            conversation.add(aiMsg);

            return Map.of("ok", true, "answer", reply, "conversation", conversation);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[识题] 识别失败: model={}, 耗时={}ms, 错误={}", modelName, elapsed, e.getMessage());
            logService.add("识题", "失败", e.getMessage());
            return Map.of("ok", false, "error", "识别失败: " + e.getMessage());
        }
    }

    @PostMapping("/api/recognize-file")
    @ResponseBody
    public Map<String, Object> recognizeFile(@RequestBody Map<String, Object> body) {
        long startMs = System.currentTimeMillis();
        try {
            String filePath = (String) body.getOrDefault("path", "");
            long kbId = body.containsKey("kbId") ? ((Number) body.get("kbId")).longValue() : 0;
            String modelName = (String) body.getOrDefault("modelName", "");

            log.info("[识题] KB图片识别请求: model={}, path={}, kbId={}", modelName, filePath, kbId);

            KnowledgeBaseEntity kb = kbService.getById(kbId);
            if (kb == null) {
                log.warn("[识题] 知识库不存在: kbId={}", kbId);
                return Map.of("ok", false, "error", "知识库不存在");
            }

            Path basePath = Paths.get(kb.getNotesDir());
            Path file = basePath.resolve(filePath).normalize();
            if (!file.startsWith(basePath) || !Files.exists(file)) {
                log.warn("[识题] 图片文件不存在: path={}", filePath);
                return Map.of("ok", false, "error", "图片文件不存在");
            }

            byte[] imageBytes = Files.readAllBytes(file);
            String imageType = guessImageType(file.getFileName().toString().toLowerCase());
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            log.info("[识题] KB图片识别: file={}, type={}, size={}KB", filePath, imageType, imageBytes.length / 1024);

            String systemPrompt = "你是一个专业的解题助手。请识别图片中的题目，给出详细的解答过程和最终答案。" +
                    "如果图片中有多道题，请逐一解答。用中文回答，Markdown 格式。" +
                    "解答格式要求：先给出题目内容，再给出解题思路，最后给出答案。";
            String userPrompt = "请识别并解答这道题。";

            String reply = llmService.chatWithImage(systemPrompt, userPrompt, base64Image, imageType, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            log.info("[识题] KB图片识别成功: file={}, 耗时={}ms, 响应长度={}chars",
                    filePath, elapsed, result.length());

            logService.add("识题", "成功", "KB图片识别: " + filePath);

            List<Map<String, String>> conversation = new ArrayList<>();
            Map<String, String> sysMsg = new LinkedHashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            conversation.add(sysMsg);

            Map<String, String> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            userMsg.put("hasImage", "true");
            conversation.add(userMsg);

            Map<String, String> aiMsg = new LinkedHashMap<>();
            aiMsg.put("role", "assistant");
            aiMsg.put("content", reply != null ? reply : "");
            conversation.add(aiMsg);

            return Map.of("ok", true, "answer", reply, "conversation", conversation);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[识题] KB图片识别失败: 耗时={}ms, 错误={}", elapsed, e.getMessage());
            logService.add("识题", "失败", e.getMessage());
            return Map.of("ok", false, "error", "识别失败: " + e.getMessage());
        }
    }

    @PostMapping("/api/ask")
    @ResponseBody
    public Map<String, Object> ask(@RequestBody Map<String, Object> body) {
        long startMs = System.currentTimeMillis();
        try {
            String userMessage = (String) body.getOrDefault("message", "");
            String modelName = (String) body.getOrDefault("modelName", "");
            List<?> convList = (List<?>) body.getOrDefault("conversation", List.of());

            log.info("[识题] 追问请求: model={}, message=\"{}\", 上下文消息数={}",
                    modelName,
                    userMessage.length() > 60 ? userMessage.substring(0, 60) + "..." : userMessage,
                    convList.size());

            List<Map<String, String>> messages = new ArrayList<>();
            for (Object item : convList) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> msg = new LinkedHashMap<>();
                    Object role = m.get("role");
                    Object content = m.get("content");
                    msg.put("role", role != null ? String.valueOf(role) : "user");
                    msg.put("content", content != null ? String.valueOf(content) : "");
                    messages.add(msg);
                }
            }

            Map<String, String> newUserMsg = new LinkedHashMap<>();
            newUserMsg.put("role", "user");
            newUserMsg.put("content", userMessage);
            messages.add(newUserMsg);

            String reply = llmService.chat(messages, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            log.info("[识题] 追问成功: 耗时={}ms, 响应长度={}chars", elapsed, result.length());

            Map<String, String> aiMsg = new LinkedHashMap<>();
            aiMsg.put("role", "assistant");
            aiMsg.put("content", reply != null ? reply : "");
            messages.add(aiMsg);

            return Map.of("ok", true, "answer", reply, "conversation", messages);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[识题] 追问失败: 耗时={}ms, 错误={}", elapsed, e.getMessage());
            return Map.of("ok", false, "error", "回复失败: " + e.getMessage());
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
