package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequestMapping("/config")
public class ConfigController {

    private final ConfigService configService;
    private final LogService logService;
    private final FeishuService feishuService;
    private final OllamaEmbeddingService ollamaEmbeddingService;
    private final LlmConfigResolver llmConfigResolver;

    public ConfigController(ConfigService configService, LogService logService,
                             FeishuService feishuService,
                             OllamaEmbeddingService ollamaEmbeddingService,
                             LlmConfigResolver llmConfigResolver) {
        this.configService = configService;
        this.logService = logService;
        this.feishuService = feishuService;
        this.ollamaEmbeddingService = ollamaEmbeddingService;
        this.llmConfigResolver = llmConfigResolver;
    }

    @GetMapping
    public String configPage(Model model) {
        model.addAttribute("scheduler_jobs", List.of(
                Map.of("id", "morning_report", "time", "每天 09:00", "desc", "生成综合日报")
        ));
        model.addAttribute("ollama_available", ollamaEmbeddingService.isAvailable());
        model.addAttribute("ollama_provider", ollamaEmbeddingService.getProviderLabel());
        model.addAttribute("config", configService.load());
        List<LlmProfileEntity> allProfiles = llmConfigResolver.getAllProfiles();
        model.addAttribute("llm_models", allProfiles);

        return "1.0/config";
    }
}
