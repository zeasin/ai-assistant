package com.laoqi.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.service.CustomerService;
import com.laoqi.assistant.service.LogService;
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
    private static final ObjectMapper mapper = new ObjectMapper();

    public CustomerController(CustomerService customerService, LogService logService) {
        this.customerService = customerService;
        this.logService = logService;
    }

    @GetMapping("/customers")
    public String customersPage(Model model) {
        Map<String, Object> data = customerService.loadCustomerData();
        List<Map<String, Object>> customers = customerService.getCustomers();
        List<Map<String, Object>> leads = customerService.getLeads();
        List<Map<String, Object>> records = customerService.getRecords();

        String lastUpdated = "未知";
        if (data.get("meta") instanceof Map) {
            lastUpdated = (String) ((Map) data.get("meta")).getOrDefault("lastUpdated", "未知");
        }

        // Stats
        int total = customers.size();
        List<Map<String, Object>> activeCustomers = customers.stream()
                .filter(c -> !"已流失".equals(c.get("stage"))).collect(Collectors.toList());
        List<Map<String, Object>> churned = customers.stream()
                .filter(c -> "已流失".equals(c.get("stage"))).collect(Collectors.toList());

        double totalContract = customers.stream()
                .mapToDouble(c -> toDouble(c.get("contractAmount"))).sum();
        double totalPaid = customers.stream()
                .mapToDouble(c -> toDouble(c.get("paidAmount"))).sum();
        double target = 500000;

        // Pipeline stages
        List<Map<String, Object>> pipelines = (List<Map<String, Object>>) data.getOrDefault("pipelines", List.of());
        List<Map<String, Object>> leadStatuses = (List<Map<String, Object>>) data.getOrDefault("leadStatuses", List.of());

        Map<String, Map<String, Object>> pipelineStages = new LinkedHashMap<>();
        for (Map<String, Object> p : pipelines) {
            String stage = (String) p.get("stage");
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("count", 0);
            ps.put("customers", new ArrayList<>());
            ps.put("color", p.getOrDefault("color", "gray"));
            pipelineStages.put(stage, ps);
        }
        for (Map<String, Object> c : customers) {
            String stage = (String) c.get("stage");
            if (stage != null && pipelineStages.containsKey(stage)) {
                pipelineStages.get(stage).put("count", (int) pipelineStages.get(stage).get("count") + 1);
                ((List) pipelineStages.get(stage).get("customers")).add(c);
            }
        }
        int maxFunnel = pipelineStages.values().stream()
                .mapToInt(v -> (int) v.get("count")).max().orElse(0);

        // Lead funnel
        Map<String, Map<String, Object>> leadFunnel = new LinkedHashMap<>();
        for (Map<String, Object> ls : leadStatuses) {
            String status = (String) ls.get("status");
            Map<String, Object> lsMap = new LinkedHashMap<>();
            lsMap.put("count", 0);
            lsMap.put("leads", new ArrayList<>());
            lsMap.put("color", ls.getOrDefault("color", "gray"));
            leadFunnel.put(status, lsMap);
        }
        for (Map<String, Object> l : leads) {
            String status = (String) l.getOrDefault("status", "待跟进");
            if (leadFunnel.containsKey(status)) {
                leadFunnel.get(status).put("count", (int) leadFunnel.get(status).get("count") + 1);
                ((List) leadFunnel.get(status).get("leads")).add(l);
            }
        }
        int maxLeadFunnel = leadFunnel.values().stream()
                .mapToInt(v -> (int) v.get("count")).max().orElse(0);

        // Revenue customers
        List<Map<String, Object>> revenueCustomers = customers.stream()
                .filter(c -> toDouble(c.get("contractAmount")) > 0).collect(Collectors.toList());

        // Records by customer
        Map<String, List<Map<String, Object>>> recordsByCustomer = new HashMap<>();
        for (Map<String, Object> r : records) {
            String cid = (String) r.get("customerId");
            recordsByCustomer.computeIfAbsent(cid, k -> new ArrayList<>()).add(r);
        }
        customers.forEach(c -> c.put("activities", recordsByCustomer.getOrDefault(c.get("id"), List.of())));
        leads.forEach(l -> l.put("activities", recordsByCustomer.getOrDefault(l.get("id"), List.of())));

        // Recent activities (top 10)
        List<Map<String, Object>> recentActivities = records.stream()
                .sorted((a, b) -> {
                    String da = (String) a.getOrDefault("date", "");
                    String db = (String) b.getOrDefault("date", "");
                    return db.compareTo(da);
                })
                .limit(10)
                .map(r -> {
                    Map<String, Object> act = new LinkedHashMap<>();
                    act.put("date", r.getOrDefault("date", ""));
                    act.put("customer", r.getOrDefault("customerName", ""));
                    act.put("type", r.getOrDefault("actType", ""));
                    act.put("content", r.getOrDefault("content", ""));
                    return act;
                })
                .collect(Collectors.toList());

        // Todos
        List<Map<String, Object>> todos = new ArrayList<>();
        for (Map<String, Object> c : customers) {
            if (c.get("nextFollowUp") != null || (c.get("notes") != null && ((String)c.get("notes")).contains("催")))
                todos.add(c);
        }
        for (Map<String, Object> l : leads) {
            if (l.get("nextFollowUp") != null || (l.get("notes") != null && ((String)l.get("notes")).contains("催")))
                todos.add(l);
        }
        todos.sort((a, b) -> {
            String na = (String) a.getOrDefault("nextFollowUp", "");
            String nb = (String) b.getOrDefault("nextFollowUp", "");
            return na.compareTo(nb);
        });

        model.addAttribute("last_updated", lastUpdated);
        model.addAttribute("total", total);
        model.addAttribute("active_count", activeCustomers.size());
        model.addAttribute("churned_count", churned.size());
        model.addAttribute("lead_count", leads.size());
        model.addAttribute("delivered_count",
                customers.stream().filter(c -> Set.of("已交付", "进行中", "已签约-待首款").contains(c.get("stage"))).count());
        model.addAttribute("total_contract", totalContract);
        model.addAttribute("total_paid", totalPaid);
        model.addAttribute("target", target);
        model.addAttribute("pipeline_stages", pipelineStages);
        model.addAttribute("max_funnel_count", maxFunnel);
        model.addAttribute("lead_funnel", leadFunnel);
        model.addAttribute("max_lead_funnel", maxLeadFunnel);
        model.addAttribute("revenue_customers", revenueCustomers);
        model.addAttribute("recent_activities", recentActivities);
        model.addAttribute("todos", todos.size() > 8 ? todos.subList(0, 8) : todos);
        model.addAttribute("leads", leads);
        model.addAttribute("customers", customers);

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

        if (!force) {
            String cached = customerService.getCachedAnalysis();
            if (cached != null) {
                try {
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(
                            Map.of("type", "text", "content", cached))));
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("type", "done"))));
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }
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