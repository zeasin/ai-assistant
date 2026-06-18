package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ModuleDefinition;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.ModuleService;
import com.laoqi.assistant.service.OllamaEmbeddingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Map;

@ControllerAdvice
public class GlobalModelAdvice {

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final ModuleService moduleService;
    private final OllamaEmbeddingService ollamaEmbeddingService;

    public GlobalModelAdvice(AppConfig appConfig, ConfigService configService, ModuleService moduleService,
                             OllamaEmbeddingService ollamaEmbeddingService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.moduleService = moduleService;
        this.ollamaEmbeddingService = ollamaEmbeddingService;
    }

    @ModelAttribute("requestURI")
    public String requestURI(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("keyLabels")
    public Map<String, String> keyLabels() {
        return configService.load().getKeyLabels();
    }

    @ModelAttribute("modules")
    public List<ModuleDefinition> modules() {
        return moduleService.getModules();
    }

    @ModelAttribute("ollamaAvailable")
    public boolean ollamaAvailable() {
        return ollamaEmbeddingService.isAvailable();
    }

    @ModelAttribute("ollamaProvider")
    public String ollamaProvider() {
        return ollamaEmbeddingService.getProviderLabel();
    }

    @ModelAttribute("ollamaModel")
    public String ollamaModel() {
        return appConfig.getOllamaModel();
    }
}
