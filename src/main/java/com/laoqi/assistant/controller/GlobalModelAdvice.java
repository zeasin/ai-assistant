package com.laoqi.assistant.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.KnowledgeBaseService;
import com.laoqi.assistant.service.OllamaEmbeddingService;
import com.laoqi.assistant.util.FileUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ControllerAdvice
public class GlobalModelAdvice {

    private static final TypeReference<Map<String, String>> LABELS_TYPE = new TypeReference<>() {};

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final KnowledgeBaseService kbService;
    private final OllamaEmbeddingService ollamaEmbeddingService;

    public GlobalModelAdvice(AppConfig appConfig, ConfigService configService,
                             KnowledgeBaseService kbService,
                             OllamaEmbeddingService ollamaEmbeddingService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.kbService = kbService;
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

    @ModelAttribute("kbList")
    public List<KbNavItem> kbList() {
        List<KnowledgeBaseEntity> all = kbService.getAll();
        List<KbNavItem> result = new ArrayList<>();
        for (KnowledgeBaseEntity kb : all) {
            KbNavItem item = new KbNavItem();
            item.id = kb.getId();
            item.name = kb.getName();
            item.labels = parseLabels(kb.getLabels());
            result.add(item);
        }
        return result;
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

    @ModelAttribute("codingEnabled")
    public boolean codingEnabled() {
        var config = configService.load();
        return Boolean.TRUE.equals(config.isCodingPiEnabled());
    }

    private Map<String, String> parseLabels(String labelsJson) {
        if (labelsJson == null || labelsJson.isBlank()) {
            return defaultLabels();
        }
        try {
            Map<String, String> parsed = FileUtil.readJson(labelsJson, LABELS_TYPE, null);
            if (parsed == null || parsed.isEmpty()) {
                return defaultLabels();
            }
            Map<String, String> result = defaultLabels();
            result.putAll(parsed);
            return result;
        } catch (Exception e) {
            return defaultLabels();
        }
    }

    private Map<String, String> defaultLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("tasks", "任务");
        labels.put("reminders", "提醒");
        labels.put("notes", "笔记");
        labels.put("config", "配置");
        return labels;
    }

    public static class KbNavItem {
        public Long id;
        public String name;
        public Map<String, String> labels;
    }
}
