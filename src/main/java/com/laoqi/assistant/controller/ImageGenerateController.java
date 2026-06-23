package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.ImageGenerateService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class ImageGenerateController {

    private final ImageGenerateService imageGenerateService;

    public ImageGenerateController(ImageGenerateService imageGenerateService) {
        this.imageGenerateService = imageGenerateService;
    }

    @GetMapping("/image/generate")
    public String page(Model model) {
        return "2.0/image_generate";
    }

    @PostMapping("/api/image/generate")
    @ResponseBody
    public Map<String, Object> generate(@RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        String size = body.getOrDefault("size", "2752x1536");
        String model = body.getOrDefault("model", "sd-turbo");

        if (prompt == null || prompt.isBlank()) {
            return Map.of("ok", false, "error", "请输入描述");
        }

        try {
            String imageUrl = imageGenerateService.generate(prompt, size, model);
            return Map.of("ok", true, "url", imageUrl);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
