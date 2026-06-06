package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import com.laoqi.assistant.util.TimeUtil;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);
    private static final TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final OpenCodeService openCodeService;
    private final PromptService promptService;

    // AI analysis cache
    private String cachedAnalysis = "";
    private String cachedDate = "";

    public CustomerService(AppConfig appConfig, ConfigService configService, OpenCodeService openCodeService, PromptService promptService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.promptService = promptService;
    }

    private Path getBaseDir() {
        return Paths.get(configService.getBaseDir());
    }

    private Path customerFile() {
        Path dir = customerDataDir();
        if (dir != null && java.nio.file.Files.exists(dir)) {
            return dir.resolve("客户数据.json");
        }
        String path = configService.load().getCustomerDataPath();
        if (path == null || path.isEmpty()) path = "企业/客户管理/客户数据.json";
        return getBaseDir().resolve(path);
    }
    private Path leadFile() {
        Path dir = customerDataDir();
        if (dir != null && java.nio.file.Files.exists(dir)) {
            return dir.resolve("线索数据.json");
        }
        String path = configService.load().getLeadDataPath();
        if (path == null || path.isEmpty()) path = "企业/客户管理/线索数据.json";
        return getBaseDir().resolve(path);
    }
    private Path recordFile() {
        Path dir = customerDataDir();
        if (dir != null && java.nio.file.Files.exists(dir)) {
            return dir.resolve("跟进记录.json");
        }
        String path = configService.load().getRecordDataPath();
        if (path == null || path.isEmpty()) path = "企业/客户管理/跟进记录.json";
        return getBaseDir().resolve(path);
    }
    
    private Path customerDataDir() {
        String dir = configService.load().getCustomerDataDir();
        if (dir == null || dir.isEmpty()) return null;
        return getBaseDir().resolve(dir).resolve("data");
    }

    /**
     * Get the analysis report directory. If customer data dir is configured, use that parent.
     * Otherwise fall back to {baseDir}/客户/AI分析
     */
    private Path getAnalysisDir() {
        String dir = configService.load().getCustomerDataDir();
        if (dir != null && !dir.isEmpty()) {
            return getBaseDir().resolve(dir).resolve("AI分析");
        }
        return getBaseDir().resolve("客户").resolve("AI分析");
    }

    /**
     * Read today's saved AI analysis report from file
     */
    public String readTodayAnalysis() {
        Path dir = getAnalysisDir();
        String date = TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        if (FileUtil.exists(file)) {
            return FileUtil.readText(file);
        }
        return null;
    }

    /**
     * Save AI analysis result to file
     */
    public void saveAnalysis(String result) {
        Path dir = getAnalysisDir();
        String date = TimeUtil.todayStr();
        Path file = dir.resolve(date + ".md");
        FileUtil.writeText(file, result);
    }

    public boolean isCustomerDataDirConfigured() {
        String dir = configService.load().getCustomerDataDir();
        return dir != null && !dir.isEmpty();
    }

    public Map<String, Map<String, List<Map<String, Object>>>> loadAllDataGroups() {
        Map<String, Map<String, List<Map<String, Object>>>> groups = new LinkedHashMap<>();
        
        Path dir = customerDataDir();
        log.info("loadAllDataGroups() - dir: {}", dir);
        if (dir == null || !java.nio.file.Files.exists(dir)) {
            log.warn("dir not found: {}", dir);
            return groups;
        }

        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
            // 先收集所有文件到列表
            List<java.nio.file.Path> jsonFiles = stream
                .filter(f -> f.getFileName().toString().endsWith(".json"))
                .sorted()
                .collect(java.util.stream.Collectors.toList());
                
            log.info("Found {} JSON files", jsonFiles.size());
            
            for (java.nio.file.Path f : jsonFiles) {
                String name = f.getFileName().toString().replace(".json", "");
                log.info("Processing file: {}", name);
                Map<String, Object> data = FileUtil.readJson(f, mapType, new HashMap<>());
                log.info("  File {} has keys: {}", name, data.keySet());
                Map<String, List<Map<String, Object>>> fileGroups = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : data.entrySet()) {
                    if (e.getValue() instanceof List) {
                        List<Map<String, Object>> entries = new ArrayList<>();
                        for (Object item : (List) e.getValue()) {
                            if (item instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> entry = (Map<String, Object>) item;
                                entries.add(entry);
                            }
                        }
                        if (!entries.isEmpty()) {
                            fileGroups.put(e.getKey(), entries);
                        }
                    }
                }
                log.info("  File {} groups: {}", name, fileGroups.keySet());
                groups.put(name, fileGroups);
            }
            log.info("Final groups: {}", groups.keySet());
        } catch (java.io.IOException e) {
            log.error("Failed to load JSON files", e);
        }

        return groups;
    }

    public Map<String, Object> loadCustomerData() {
        return FileUtil.readJson(customerFile(), mapType, new HashMap<>());
    }

    public List<Map<String, Object>> getCustomers() {
        Map<String, Object> data = loadCustomerData();
        Object c = data.get("customers");
        if (c instanceof List) return (List<Map<String, Object>>) c;
        return new ArrayList<>();
    }

    public List<Map<String, Object>> getLeads() {
        Map<String, Object> data = FileUtil.readJson(leadFile(), mapType, new HashMap<>());
        Object l = data.get("leads");
        if (l instanceof List) return (List<Map<String, Object>>) l;
        return new ArrayList<>();
    }

    public List<Map<String, Object>> getRecords() {
        Map<String, Object> data = FileUtil.readJson(recordFile(), mapType, new HashMap<>());
        Object r = data.get("records");
        if (r instanceof List) return (List<Map<String, Object>>) r;
        return new ArrayList<>();
    }



    /**
     * Get cached AI analysis if already generated today, or null if not cached
     */
    public String getCachedAnalysis() {
        String today = com.laoqi.assistant.util.TimeUtil.todayStr();
        if (today.equals(cachedDate) && !cachedAnalysis.isEmpty()) {
            return cachedAnalysis;
        }
        return null;
    }

    /**
     * Use AI to analyze customer data. Returns cached result if available today, unless force=true.
     */
    public String aiAnalyze(boolean force) {
        if (!force) {
            String cached = getCachedAnalysis();
            if (cached != null) {
                log.debug("[AI分析] 使用缓存的客户分析结果");
                return cached;
            }
        }

        String prompt = promptService.getTemplate("customer-analysis");

        try {
            if (!openCodeService.isHealthy()) {
                return "⚠️ opencode serve 未启动（端口 " + appConfig.getNotesPort() + "），无法进行 AI 分析。";
            }
            String sessionId = openCodeService.createSession(promptService.getSessionTitle("customer-analysis"));
            String result = openCodeService.sendMessage(sessionId, prompt);
            cachedAnalysis = result;
            cachedDate = com.laoqi.assistant.util.TimeUtil.todayStr();
            saveAnalysis(result);
            return result;
        } catch (Exception e) {
            log.error("AI 客户分析失败", e);
            return "❌ AI 分析失败：" + e.getMessage();
        }
    }

    public Map<String, Object> addLead(String name, String source, String status, String company,
                                        String region, String contactName, String contactPhone,
                                        String contactWechat, String contactEmail, String notes) {
        Map<String, Object> leadData = FileUtil.readJson(leadFile(), mapType, new HashMap<>());
        List<Map<String, Object>> leads = (List<Map<String, Object>>) leadData.computeIfAbsent("leads", k -> new ArrayList<>());

        int maxNum = leads.stream()
                .filter(l -> l.get("id") instanceof String s && s.startsWith("M"))
                .mapToInt(l -> {
                    try { return Integer.parseInt(((String) l.get("id")).substring(1)); }
                    catch (Exception e) { return 0; }
                })
                .max().orElse(0);
        String newId = String.format("M%02d", maxNum + 1);
        String now = TimeUtil.todayStr();

        Map<String, Object> newLead = new LinkedHashMap<>();
        newLead.put("id", newId);
        newLead.put("name", name);
        newLead.put("company", company.isEmpty() ? null : company);
        newLead.put("region", region.isEmpty() ? null : region);
        newLead.put("status", status);
        newLead.put("source", source);
        newLead.put("contacts", new ArrayList<>());
        newLead.put("createdAt", now);
        newLead.put("lastContactedAt", now);
        newLead.put("nextFollowUp", null);
        newLead.put("notes", notes.isEmpty() ? null : notes);

        if (!contactName.isEmpty()) {
            Map<String, Object> contact = new LinkedHashMap<>();
            contact.put("name", contactName);
            contact.put("phone", contactPhone.isEmpty() ? null : contactPhone);
            contact.put("wechat", contactWechat.isEmpty() ? null : contactWechat);
            contact.put("email", contactEmail.isEmpty() ? null : contactEmail);
            contact.put("role", "负责人");
            contact.put("isPrimary", true);
            ((List<Map<String, Object>>) newLead.get("contacts")).add(contact);
        }

        leads.add(newLead);
        Map<String, Object> meta = (Map<String, Object>) leadData.computeIfAbsent("meta", k -> new HashMap<>());
        meta.put("lastUpdated", now);
        FileUtil.writeJson(leadFile(), leadData);

        Map<String, Object> recordData = FileUtil.readJson(recordFile(), mapType, new HashMap<>());
        List<Map<String, Object>> records = (List<Map<String, Object>>) recordData.computeIfAbsent("records", k -> new ArrayList<>());
        int maxRNum = records.stream()
                .filter(r -> r.get("id") instanceof String s && s.startsWith("R"))
                .mapToInt(r -> {
                    try { return Integer.parseInt(((String) r.get("id")).substring(1)); }
                    catch (Exception e) { return 0; }
                })
                .max().orElse(0);
        Map<String, Object> newRecord = new LinkedHashMap<>();
        newRecord.put("id", String.format("R%03d", maxRNum + 1));
        newRecord.put("customerId", newId);
        newRecord.put("customerName", name);
        newRecord.put("type", "lead");
        newRecord.put("actType", "新增");
        newRecord.put("date", now);
        newRecord.put("content", "新增线索，来源：" + source + (notes.isEmpty() ? "" : "，备注：" + notes));
        records.add(newRecord);
        meta = (Map<String, Object>) recordData.computeIfAbsent("meta", k -> new HashMap<>());
        meta.put("lastUpdated", now);
        FileUtil.writeJson(recordFile(), recordData);

        Map<String, Object> result = new HashMap<>();
        result.put("id", newId);
        result.put("name", name);
        return result;
    }

    public void addRecord(String customerId, String customerName, String actType, String content, String date, String nextFollowUp) {
        if (date == null || date.isEmpty()) date = TimeUtil.todayStr();
        Map<String, Object> recordData = FileUtil.readJson(recordFile(), mapType, new HashMap<>());
        List<Map<String, Object>> records = (List<Map<String, Object>>) recordData.computeIfAbsent("records", k -> new ArrayList<>());

        int maxNum = records.stream()
                .filter(r -> r.get("id") instanceof String s && s.startsWith("R"))
                .mapToInt(r -> {
                    try { return Integer.parseInt(((String) r.get("id")).substring(1)); }
                    catch (Exception e) { return 0; }
                })
                .max().orElse(0);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", String.format("R%03d", maxNum + 1));
        record.put("customerId", customerId);
        record.put("customerName", customerName);
        record.put("type", customerId.matches("M\\d+|P\\d+") ? "lead" : "customer");
        record.put("actType", actType);
        record.put("date", date);
        record.put("content", content);
        records.add(record);

        Map<String, Object> meta = (Map<String, Object>) recordData.computeIfAbsent("meta", k -> new HashMap<>());
        meta.put("lastUpdated", date);
        FileUtil.writeJson(recordFile(), recordData);

        String leadDir = "lead";
        if (customerId.matches("M\\d+|P\\d+")) {
            Map<String, Object> leadData = FileUtil.readJson(leadFile(), mapType, new HashMap<>());
            List<Map<String, Object>> leads = (List<Map<String, Object>>) leadData.getOrDefault("leads", new ArrayList<>());
            for (Map<String, Object> l : leads) {
                if (customerId.equals(l.get("id"))) {
                    l.put("lastContactedAt", date);
                    if (nextFollowUp != null && !nextFollowUp.isEmpty()) l.put("nextFollowUp", nextFollowUp);
                    break;
                }
            }
            meta = (Map<String, Object>) leadData.computeIfAbsent("meta", k -> new HashMap<>());
            meta.put("lastUpdated", date);
            FileUtil.writeJson(leadFile(), leadData);
        } else {
            Map<String, Object> custData = loadCustomerData();
            List<Map<String, Object>> customers = (List<Map<String, Object>>) custData.getOrDefault("customers", new ArrayList<>());
            for (Map<String, Object> c : customers) {
                if (customerId.equals(c.get("id"))) {
                    c.put("lastContactedAt", date);
                    if (nextFollowUp != null && !nextFollowUp.isEmpty()) c.put("nextFollowUp", nextFollowUp);
                    break;
                }
            }
            meta = (Map<String, Object>) custData.computeIfAbsent("meta", k -> new HashMap<>());
            meta.put("lastUpdated", date);
            FileUtil.writeJson(customerFile(), custData);
        }
    }

    public Map<String, Object> addCustomer(String name, String company, String region, String stage,
                                             String product, Double contractAmount, Double paidAmount,
                                             String notes, String contactName, String contactPhone, String contactWechat) {
        Map<String, Object> custData = loadCustomerData();
        List<Map<String, Object>> customers = (List<Map<String, Object>>) custData.computeIfAbsent("customers", k -> new ArrayList<>());

        int maxNum = customers.stream()
                .filter(c -> c.get("id") instanceof String s && s.startsWith("C"))
                .mapToInt(c -> {
                    try { return Integer.parseInt(((String) c.get("id")).substring(1)); }
                    catch (Exception e) { return 0; }
                })
                .max().orElse(0);
        String newId = String.format("C%03d", maxNum + 1);
        String now = TimeUtil.todayStr();

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", newId);
        customer.put("name", name);
        customer.put("company", company.isEmpty() ? null : company);
        customer.put("region", region.isEmpty() ? null : region);
        customer.put("stage", stage.isEmpty() ? "潜在" : stage);
        customer.put("product", product.isEmpty() ? null : product);
        if (contractAmount != null) customer.put("contractAmount", contractAmount);
        if (paidAmount != null) customer.put("paidAmount", paidAmount);
        customer.put("notes", notes.isEmpty() ? null : notes);
        customer.put("createdAt", now);
        customer.put("lastContactedAt", now);

        if (!contactName.isEmpty()) {
            List<Map<String, String>> contacts = new ArrayList<>();
            Map<String, String> contact = new LinkedHashMap<>();
            contact.put("name", contactName);
            contact.put("phone", contactPhone.isEmpty() ? null : contactPhone);
            contact.put("wechat", contactWechat.isEmpty() ? null : contactWechat);
            contact.put("role", "负责人");
            contact.put("isPrimary", "true");
            contacts.add(contact);
            customer.put("contacts", contacts);
        }

        customers.add(customer);
        Map<String, Object> meta = (Map<String, Object>) custData.computeIfAbsent("meta", k -> new HashMap<>());
        meta.put("lastUpdated", now);
        FileUtil.writeJson(customerFile(), custData);

        Map<String, Object> result = new HashMap<>();
        result.put("id", newId);
        result.put("name", name);
        return result;
    }
}
