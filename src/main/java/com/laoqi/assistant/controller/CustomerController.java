package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.CustomerService;
import com.laoqi.assistant.service.LogService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Controller
public class CustomerController {

    private final CustomerService customerService;
    private final LogService logService;
    private final ConfigService configService;
    private static final ObjectMapper mapper = new ObjectMapper();

    public CustomerController(CustomerService customerService, LogService logService,
                               ConfigService configService) {
        this.customerService = customerService;
        this.logService = logService;
        this.configService = configService;
    }

    @GetMapping(value = "/api/customers/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getCustomerData() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean isDataDirConfigured = customerService.isCustomerDataDirConfigured();
        result.put("isDataDirConfigured", isDataDirConfigured);
        
        if (isDataDirConfigured) {
            Map<String, Map<String, List<Map<String, Object>>>> allData = customerService.loadAllDataGroups();
            Map<String, List<String>> tableColumns = new LinkedHashMap<>();
            String lastUpdated = "未知";
            
            for (Map.Entry<String, Map<String, List<Map<String, Object>>>> fileEntry : allData.entrySet()) {
                String fileKey = fileEntry.getKey();
                for (Map.Entry<String, List<Map<String, Object>>> groupEntry : fileEntry.getValue().entrySet()) {
                    String groupKey = groupEntry.getKey();
                    String tableId = fileKey + "_" + groupKey;
                    LinkedHashSet<String> colSet = new LinkedHashSet<>();
                    for (Map<String, Object> row : groupEntry.getValue()) {
                        colSet.addAll(row.keySet());
                    }
                    tableColumns.put(tableId, new ArrayList<>(colSet));
                }
            }
            
            Map<String, Object> metaData = customerService.loadCustomerData();
            if (metaData.get("meta") instanceof Map) {
                lastUpdated = (String) ((Map) metaData.get("meta")).getOrDefault("lastUpdated", "未知");
            }
            
            result.put("rawDataGroups", allData);
            result.put("tableColumns", tableColumns);
            result.put("lastUpdated", lastUpdated);
            result.put("dataDirEmpty", allData.isEmpty());
            String cfgDir = configService.load().getCustomerDataDir();
            result.put("customerDataDir", cfgDir != null && !cfgDir.isEmpty() ? cfgDir : "未配置");
        }
        
        return result;
    }

    @GetMapping("/customers")
    public String customersPage(Model model) {
        return "customers";
    }

    @GetMapping("/customers/all")
    public String customersAll(Model model) {
        model.addAttribute("customers", customerService.getCustomers());
        return "customers_all";
    }

    @GetMapping("/leads/all")
    public String leadsAll(Model model) {
        model.addAttribute("leads", customerService.getLeads());
        return "leads_all";
    }

    @GetMapping("/api/customers/ai-analysis")
    public SseEmitter aiAnalysis(@RequestParam(required = false, defaultValue = "false") boolean force) {
        SseEmitter emitter = new SseEmitter(300_000L);

        // Check if customer data directory is configured first
        if (!customerService.isCustomerDataDirConfigured()) {
            try {
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                        Map.of("type", "text", "content", "⚠️ 请先在配置页面设置「客户数据目录」，然后再进行 AI 分析。"))));
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("type", "done"))));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Map<String, Object> statusEvent = Map.of("type", "status", "content", "⏳ AI 正在分析客户数据...");
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(statusEvent)));

                String result = customerService.aiAnalyze(force);
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("type", "text", "content", result))));
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("type", "done"))));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                            Map.of("type", "error", "content", "AI 分析失败: " + e.getMessage()))));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });
        return emitter;
    }

    @PostMapping("/api/leads/add")
    @ResponseBody
    public Map<String, Object> addLead(
            @RequestParam String name,
            @RequestParam(required = false, defaultValue = "") String source,
            @RequestParam(required = false, defaultValue = "待跟进") String status,
            @RequestParam(required = false, defaultValue = "") String company,
            @RequestParam(required = false, defaultValue = "") String region,
            @RequestParam(required = false, defaultValue = "") String contactName,
            @RequestParam(required = false, defaultValue = "") String contactPhone,
            @RequestParam(required = false, defaultValue = "") String contactWechat,
            @RequestParam(required = false, defaultValue = "") String contactEmail,
            @RequestParam(required = false, defaultValue = "") String notes) {
        try {
            Map<String, Object> result = customerService.addLead(name, source, status, company,
                    region, contactName, contactPhone, contactWechat, contactEmail, notes);
            result.put("ok", true);
            return result;
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/records/add")
    @ResponseBody
    public Map<String, Object> addRecord(
            @RequestParam String customerId,
            @RequestParam(required = false, defaultValue = "") String customerName,
            @RequestParam(required = false, defaultValue = "跟进") String actType,
            @RequestParam String content,
            @RequestParam(required = false, defaultValue = "") String date,
            @RequestParam(required = false, defaultValue = "") String nextFollowUp) {
        try {
            customerService.addRecord(customerId, customerName, actType, content, date, nextFollowUp);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/customers/add")
    @ResponseBody
    public Map<String, Object> addCustomer(
            @RequestParam String name,
            @RequestParam(required = false, defaultValue = "") String company,
            @RequestParam(required = false, defaultValue = "") String region,
            @RequestParam(required = false, defaultValue = "潜在") String stage,
            @RequestParam(required = false, defaultValue = "") String product,
            @RequestParam(required = false, defaultValue = "0") Double contractAmount,
            @RequestParam(required = false, defaultValue = "0") Double paidAmount,
            @RequestParam(required = false, defaultValue = "") String notes,
            @RequestParam(required = false, defaultValue = "") String contactName,
            @RequestParam(required = false, defaultValue = "") String contactPhone,
            @RequestParam(required = false, defaultValue = "") String contactWechat) {
        try {
            Map<String, Object> result = customerService.addCustomer(name, company, region, stage, product,
                    contractAmount > 0 ? contractAmount : null,
                    paidAmount > 0 ? paidAmount : null,
                    notes, contactName, contactPhone, contactWechat);
            result.put("ok", true);
            return result;
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        return 0;
    }
}
