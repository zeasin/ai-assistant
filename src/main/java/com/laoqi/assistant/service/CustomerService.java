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

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);
    private static final TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};

    private final AppConfig appConfig;
    private final ConfigService configService;

    public CustomerService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
    }

    private Path getBaseDir() {
        String baseDir = configService.load().getBaseDir();
        if (baseDir != null && !baseDir.isEmpty()) {
            return Paths.get(baseDir);
        }
        return Paths.get("D:\\projects\\richie_learning_notes");
    }

    private Path customerFile() { 
        String path = configService.load().getCustomerDataPath();
        if (path == null || path.isEmpty()) path = "企业/客户管理/客户数据.json";
        return getBaseDir().resolve(path);
    }
    private Path leadFile() { 
        String path = configService.load().getLeadDataPath();
        if (path == null || path.isEmpty()) path = "企业/客户管理/线索数据.json";
        return getBaseDir().resolve(path);
    }
    private Path recordFile() { 
        String path = configService.load().getRecordDataPath();
        if (path == null || path.isEmpty()) path = "企业/客户管理/跟进记录.json";
        return getBaseDir().resolve(path);
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
}
