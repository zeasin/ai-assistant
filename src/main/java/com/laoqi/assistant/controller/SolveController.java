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

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
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
    private final DataSource dataSource;

    public SolveController(LlmService llmService, LlmConfigResolver llmConfigResolver,
                           KnowledgeBaseService kbService, LogService logService, DataSource dataSource) {
        this.llmService = llmService;
        this.llmConfigResolver = llmConfigResolver;
        this.kbService = kbService;
        this.logService = logService;
        this.dataSource = dataSource;
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
        model.addAttribute("sessions", getRecentSessions());
        return "solve";
    }

    @PostMapping("/api/recognize")
    @ResponseBody
    public Map<String, Object> recognize(@RequestParam("image") MultipartFile image,
                                          @RequestParam(value = "modelName", defaultValue = "") String modelName,
                                          @RequestParam(value = "prompt", defaultValue = "") String prompt) {
        long startMs = System.currentTimeMillis();
        String fileName = image.getOriginalFilename();
        long sessionId = -1;
        try {
            byte[] imageBytes = image.getBytes();
            String imageType = image.getContentType();
            if (imageType == null || imageType.isEmpty()) imageType = "image/jpeg";

            log.info("[识题] 识别请求: model={}, file={}, type={}, size={}KB, prompt={}",
                    modelName, fileName, imageType, imageBytes.length / 1024,
                    prompt != null && !prompt.isEmpty() ? prompt : "(默认)");

            sessionId = insertSession(fileName, "", imageType, imageBytes, modelName, "pending", prompt);
            log.info("[识题] 创建会话: sessionId={}, 图片已保存({}KB)", sessionId, imageBytes.length / 1024);

            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String systemPrompt = "你是一个专业的解题助手。请识别图片中的题目，给出详细的解答过程和最终答案。" +
                    "如果图片中有多道题，请逐一解答。用中文回答，Markdown 格式。" +
                    "解答格式要求：先给出题目内容，再给出解题思路，最后给出答案。";
            String userPrompt = prompt != null && !prompt.isEmpty() ? prompt : "请识别并解答这道题。";

            log.info("[识题] 用户提示词: {}", prompt != null && !prompt.isEmpty() ? prompt : "(使用默认提示词)");
            log.info("[识题] 组装提示词 - systemPrompt: {}, userPrompt: {}", systemPrompt, userPrompt);

            String reply = llmService.chatWithImage(systemPrompt, userPrompt, base64Image, imageType, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            if (sessionId > 0) updateSessionResult(sessionId, result, "completed");

            log.info("[识题] 识别成功: model={}, file={}, 耗时={}ms, 响应长度={}chars, sessionId={}",
                    modelName, fileName, elapsed, result.length(), sessionId);
            logService.add("识题", "成功", "上传图片识别: " + fileName);

            List<Map<String, String>> conversation = buildConversation(systemPrompt, userPrompt, reply, true);

            return Map.of("ok", true, "answer", reply, "conversation", conversation, "sessionId", sessionId);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[识题] 识别失败: model={}, file={}, 耗时={}ms, 错误={}", modelName, fileName, elapsed, e.getMessage());
            if (sessionId > 0) updateSessionResult(sessionId, "识别失败: " + e.getMessage(), "failed");
            logService.add("识题", "失败", e.getMessage());
            return Map.of("ok", false, "error", "识别失败: " + e.getMessage());
        }
    }

    @PostMapping("/api/recognize-file")
    @ResponseBody
    public Map<String, Object> recognizeFile(@RequestBody Map<String, Object> body) {
        long startMs = System.currentTimeMillis();
        long sessionId = -1;
        try {
            String filePath = (String) body.getOrDefault("path", "");
            long kbId = body.containsKey("kbId") ? ((Number) body.get("kbId")).longValue() : 0;
            String modelName = (String) body.getOrDefault("modelName", "");

            log.info("[识题] KB图片识别请求: model={}, path={}, kbId={}", modelName, filePath, kbId);

            KnowledgeBaseEntity kb = kbService.getById(kbId);
            if (kb == null) return Map.of("ok", false, "error", "知识库不存在");

            Path basePath = Paths.get(kb.getNotesDir());
            Path file = basePath.resolve(filePath).normalize();
            if (!file.startsWith(basePath) || !Files.exists(file)) {
                return Map.of("ok", false, "error", "图片文件不存在");
            }

            byte[] imageBytes = Files.readAllBytes(file);
            String imageType = guessImageType(file.getFileName().toString().toLowerCase());
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            sessionId = insertSession(file.getFileName().toString(), filePath, imageType, imageBytes, modelName, "pending", "");
            log.info("[识题] 创建会话: sessionId={}, 图片已保存({}KB)", sessionId, imageBytes.length / 1024);

            String systemPrompt = "你是一个专业的解题助手。请识别图片中的题目，给出详细的解答过程和最终答案。" +
                    "如果图片中有多道题，请逐一解答。用中文回答，Markdown 格式。" +
                    "解答格式要求：先给出题目内容，再给出解题思路，最后给出答案。";
            String userPrompt = "请识别并解答这道题。";

            log.info("[识题] 组装提示词 - systemPrompt: {}, userPrompt: {}", systemPrompt, userPrompt);

            String reply = llmService.chatWithImage(systemPrompt, userPrompt, base64Image, imageType, modelName);

            long elapsed = System.currentTimeMillis() - startMs;
            String result = (reply != null && !reply.isEmpty()) ? reply : "(AI 未返回结果)";

            if (sessionId > 0) updateSessionResult(sessionId, result, "completed");

            log.info("[识题] KB图片识别成功: file={}, 耗时={}ms, 响应长度={}chars, sessionId={}",
                    filePath, elapsed, result.length(), sessionId);
            logService.add("识题", "成功", "KB图片识别: " + filePath);

            List<Map<String, String>> conversation = buildConversation(systemPrompt, userPrompt, reply, true);

            return Map.of("ok", true, "answer", reply, "conversation", conversation, "sessionId", sessionId);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[识题] KB图片识别失败: 耗时={}ms, 错误={}", elapsed, e.getMessage());
            if (sessionId > 0) updateSessionResult(sessionId, "识别失败: " + e.getMessage(), "failed");
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
            long sessionId = body.containsKey("sessionId") ? ((Number) body.get("sessionId")).longValue() : 0;
            List<?> convList = (List<?>) body.getOrDefault("conversation", List.of());

            log.info("[识题] 追问请求: model={}, sessionId={}, message=\"{}\", 上下文消息数={}",
                    modelName, sessionId,
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

            if (sessionId > 0) {
                insertFollowUp(sessionId, userMessage, result);
            }

            log.info("[识题] 追问成功: 耗时={}ms, 响应长度={}chars, sessionId={}", elapsed, result.length(), sessionId);

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

    @GetMapping("/api/sessions")
    @ResponseBody
    public Map<String, Object> getSessions() {
        return Map.of("ok", true, "sessions", getRecentSessions());
    }

    @GetMapping("/api/sessions/{id}")
    @ResponseBody
    public Map<String, Object> getSession(@PathVariable long id) {
        Map<String, Object> session = getSessionById(id);
        if (session == null) return Map.of("ok", false, "error", "会话不存在");
        List<Map<String, Object>> followUps = getFollowUps(id);
        return Map.of("ok", true, "session", session, "followUps", followUps);
    }

    @GetMapping("/api/sessions/{id}/image")
    @ResponseBody
    public org.springframework.http.ResponseEntity<byte[]> getSessionImage(@PathVariable long id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT image_data, image_type FROM solve_sessions WHERE id = ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                byte[] imageData = rs.getBytes("image_data");
                String imageType = rs.getString("image_type");
                if (imageData != null && imageData.length > 0) {
                    return org.springframework.http.ResponseEntity.ok()
                            .header("Content-Type", imageType != null ? imageType : "image/jpeg")
                            .header("Cache-Control", "max-age=3600")
                            .body(imageData);
                }
            }
        } catch (SQLException e) {
            log.error("[识题] 获取会话图片失败", e);
        }
        return org.springframework.http.ResponseEntity.notFound().build();
    }

    @DeleteMapping("/api/sessions/{id}")
    @ResponseBody
    public Map<String, Object> deleteSession(@PathVariable long id) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM solve_follow_ups WHERE session_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM solve_sessions WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            conn.commit();
            return Map.of("ok", true);
        } catch (SQLException e) {
            log.error("[识题] 删除会话失败", e);
            return Map.of("ok", false, "error", "删除失败");
        }
    }

    // ========== DB helpers ==========

    private long insertSession(String imageName, String imagePath, String imageType, byte[] imageData, String model, String status, String prompt) {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO solve_sessions (title, image_name, image_path, image_type, image_data, model, status, created_at, prompt) VALUES (?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, imageName);
            ps.setString(2, imageName);
            ps.setString(3, imagePath);
            ps.setString(4, imageType);
            if (imageData != null && imageData.length > 0) {
                ps.setBytes(5, imageData);
            } else {
                ps.setNull(5, Types.BLOB);
            }
            ps.setString(6, model);
            ps.setString(7, status);
            ps.setString(8, now);
            ps.setString(9, prompt != null ? prompt : "");
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            log.error("[识题] 插入会话失败", e);
        }
        return -1;
    }

    private void updateSessionResult(long sessionId, String answer, String status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE solve_sessions SET answer=?, status=? WHERE id=?")) {
            ps.setString(1, answer);
            ps.setString(2, status);
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[识题] 更新会话失败", e);
        }
    }

    private void insertFollowUp(long sessionId, String question, String answer) {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO solve_follow_ups (session_id, question, answer, sort_order, created_at) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, sessionId);
            ps.setString(2, question);
            ps.setString(3, answer);
            int maxOrder = getMaxFollowUpOrder(sessionId);
            ps.setInt(4, maxOrder + 1);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[识题] 插入追问失败", e);
        }
    }

    private int getMaxFollowUpOrder(long sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(MAX(sort_order), 0) FROM solve_follow_ups WHERE session_id = ?")) {
            ps.setLong(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("[识题] 查询追问序号失败", e);
        }
        return 0;
    }

    private List<Map<String, Object>> getRecentSessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM solve_sessions ORDER BY id DESC LIMIT 20")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("title", rs.getString("title"));
                m.put("imageName", rs.getString("image_name"));
                m.put("prompt", rs.getString("prompt"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getString("created_at"));
                list.add(m);
            }
        } catch (SQLException e) {
            log.error("[识题] 查询会话列表失败", e);
        }
        return list;
    }

    private Map<String, Object> getSessionById(long id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM solve_sessions WHERE id = ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("title", rs.getString("title"));
                m.put("imageName", rs.getString("image_name"));
                m.put("imagePath", rs.getString("image_path"));
                m.put("model", rs.getString("model"));
                m.put("prompt", rs.getString("prompt"));
                m.put("answer", rs.getString("answer"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }
        } catch (SQLException e) {
            log.error("[识题] 查询会话失败", e);
        }
        return null;
    }

    private List<Map<String, Object>> getFollowUps(long sessionId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM solve_follow_ups WHERE session_id = ? ORDER BY sort_order")) {
            ps.setLong(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("question", rs.getString("question"));
                m.put("answer", rs.getString("answer"));
                m.put("createdAt", rs.getString("created_at"));
                list.add(m);
            }
        } catch (SQLException e) {
            log.error("[识题] 查询追问列表失败", e);
        }
        return list;
    }

    private List<Map<String, String>> buildConversation(String systemPrompt, String userPrompt, String reply, boolean hasImage) {
        List<Map<String, String>> conversation = new ArrayList<>();
        Map<String, String> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        conversation.add(sysMsg);

        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        if (hasImage) userMsg.put("hasImage", "true");
        conversation.add(userMsg);

        Map<String, String> aiMsg = new LinkedHashMap<>();
        aiMsg.put("role", "assistant");
        aiMsg.put("content", reply != null ? reply : "");
        conversation.add(aiMsg);

        return conversation;
    }

    private String guessImageType(String name) {
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
