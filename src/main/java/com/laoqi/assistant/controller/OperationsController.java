package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.model.OperationsData;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.OperationsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class OperationsController {

    private final OperationsService operationsService;
    private final LogService logService;
    private static final ObjectMapper mapper = new ObjectMapper();

    public OperationsController(OperationsService operationsService, LogService logService) {
        this.operationsService = operationsService;
        this.logService = logService;
    }

    @GetMapping("/operations")
    public String operationsPage(Model model) {
        try {
            OperationsData.Root data = operationsService.loadData();
            String lastUpdated = data.meta != null ?
                    (String) data.meta.getOrDefault("lastUpdated", "未知") : "未知";

            // Convert raw Object maps to typed PlatformInfo for the template
            Map<String, Map<String, OperationsData.PlatformInfo>> convertedAccounts = new LinkedHashMap<>();
            if (data.accounts != null) {
                for (var accEntry : data.accounts.entrySet()) {
                    Map<String, OperationsData.PlatformInfo> platforms = new LinkedHashMap<>();
                    for (var platEntry : accEntry.getValue().entrySet()) {
                        if (platEntry.getValue() instanceof Map) {
                            try {
                                OperationsData.PlatformInfo info = mapper.convertValue(
                                        platEntry.getValue(), OperationsData.PlatformInfo.class);
                                platforms.put(platEntry.getKey(), info);
                            } catch (Exception ignored) {}
                        }
                    }
                    convertedAccounts.put(accEntry.getKey(), platforms);
                }
            }

            model.addAttribute("last_updated", lastUpdated);
            model.addAttribute("accounts", convertedAccounts);
            model.addAttribute("articles", data.articles);
            model.addAttribute("daily_stats", data.dailyStats);

            OperationsData.AnalysisResult analysis = operationsService.analyze(data.articles);
            model.addAttribute("analysis", analysis);
        } catch (Exception e) {
            logService.add("运营看板", "失败", e.getMessage());
            model.addAttribute("last_updated", "加载失败");
            model.addAttribute("accounts", Map.of());
            model.addAttribute("articles", Map.of());
            model.addAttribute("daily_stats", Map.of());
        }
        return "operations";
    }
}