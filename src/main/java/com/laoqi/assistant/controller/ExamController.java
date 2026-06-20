package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.service.ExamService;
import com.laoqi.assistant.service.LlmConfigResolver;
import com.laoqi.assistant.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/image/exam")
public class ExamController {

    private static final Logger log = LoggerFactory.getLogger(ExamController.class);

    private final LlmService llmService;
    private final LlmConfigResolver llmConfigResolver;
    private final ExamService examService;

    public ExamController(LlmService llmService, LlmConfigResolver llmConfigResolver, ExamService examService) {
        this.llmService = llmService;
        this.llmConfigResolver = llmConfigResolver;
        this.examService = examService;
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
        return "exam";
    }

    @PostMapping("/api/recognize")
    @ResponseBody
    public Map<String, Object> recognize(@RequestParam("image") MultipartFile image,
                                          @RequestParam(value = "modelName", defaultValue = "") String modelName,
                                          @RequestParam(value = "paperName", defaultValue = "") String paperName) {
        long startMs = System.currentTimeMillis();
        try {
            byte[] imageBytes = image.getBytes();
            String imageType = image.getContentType();
            if (imageType == null || imageType.isEmpty()) imageType = "image/jpeg";
            String name = (paperName == null || paperName.isEmpty()) ? "未命名试卷" : paperName;

            log.info("[试卷] 识别请求: model={}, file={}, size={}KB", modelName, image.getOriginalFilename(), imageBytes.length / 1024);

            long paperId = examService.recognizeAndSave(imageBytes, imageType, modelName, name);
            List<Map<String, Object>> questions = examService.getQuestions(paperId, null, null);

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("[试卷] 识别完成: paperId={}, 题目数={}, 耗时={}ms", paperId, questions.size(), elapsed);

            return Map.of("ok", true, "paperId", paperId, "questions", questions);
        } catch (Exception e) {
            log.error("[试卷] 识别失败", e);
            return Map.of("ok", false, "error", "识别失败: " + e.getMessage());
        }
    }

    @GetMapping("/api/papers")
    @ResponseBody
    public Map<String, Object> getPapers() {
        return Map.of("ok", true, "papers", examService.getPapers());
    }

    @GetMapping("/api/papers/{id}/questions")
    @ResponseBody
    public Map<String, Object> getPaperQuestions(@PathVariable long id) {
        return Map.of("ok", true, "questions", examService.getQuestions(id, null, null));
    }

    @DeleteMapping("/api/papers/{id}")
    @ResponseBody
    public Map<String, Object> deletePaper(@PathVariable long id) {
        boolean ok = examService.deletePaper(id);
        return ok ? Map.of("ok", true) : Map.of("ok", false, "error", "删除失败");
    }

    @GetMapping("/api/questions")
    @ResponseBody
    public Map<String, Object> getQuestions(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String tag) {
        return Map.of("ok", true, "questions", examService.getQuestions(null, type, tag));
    }

    @DeleteMapping("/api/questions/{id}")
    @ResponseBody
    public Map<String, Object> deleteQuestion(@PathVariable long id) {
        boolean ok = examService.deleteQuestion(id);
        return ok ? Map.of("ok", true) : Map.of("ok", false, "error", "删除失败");
    }

    @PostMapping("/api/practice/start")
    @ResponseBody
    public Map<String, Object> startPractice(@RequestBody Map<String, Object> body) {
        int count = body.containsKey("count") ? ((Number) body.get("count")).intValue() : 10;
        String type = body.getOrDefault("type", "").toString();
        List<Map<String, Object>> questions = examService.startPractice(count, type);
        return Map.of("ok", true, "questions", questions);
    }

    @PostMapping("/api/practice/submit")
    @ResponseBody
    public Map<String, Object> submitAnswer(@RequestBody Map<String, String> body) {
        long questionId = Long.parseLong(body.getOrDefault("questionId", "0"));
        String userAnswer = body.getOrDefault("userAnswer", "");

        boolean ok = examService.submitAnswer(questionId, userAnswer);
        if (!ok) return Map.of("ok", false, "error", "提交失败");

        String correctAnswer = examService.getQuestions(questionId, null, null).stream()
                .findFirst().map(q -> (String) q.get("answer")).orElse("");
        boolean isCorrect = normalizeAnswer(userAnswer).equals(normalizeAnswer(correctAnswer));
        return Map.of("ok", true, "isCorrect", isCorrect);
    }

    @GetMapping("/api/stats")
    @ResponseBody
    public Map<String, Object> getStats() {
        return Map.of("ok", true, "stats", examService.getStats());
    }

    private String normalizeAnswer(String answer) {
        if (answer == null) return "";
        String s = answer.trim().toLowerCase();
        s = s.replaceAll("[\\s　]+", "");
        s = s.replaceAll("^[A-Aa-a][.、．。]\\s*", "");
        return s;
    }
}
