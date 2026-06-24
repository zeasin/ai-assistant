package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.datacenter.DataSetService;
import com.laoqi.assistant.datacenter.model.DataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 数据中心工具集 — 让 AI 可以通过对话操作数据集（SQLite数据库）。
 * 支持查询、新增、修改、删除数据记录。
 * 注意：数据保存在SQLite数据库中，不是笔记库JSON文件。
 */
@Component
public class DataTools {

    private static final Logger log = LoggerFactory.getLogger(DataTools.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSetService dataSetService;

    public DataTools(DataSetService dataSetService) {
        this.dataSetService = dataSetService;
    }

    private String now() {
        return LocalDateTime.now().format(DATE_FMT);
    }

    @Tool(description = "列出所有数据集，返回数据集ID、名称、记录数等信息。当需要操作数据集时，应先调用此工具查看有哪些数据集可用")
    public String listDatasets() {
        List<DataSet> datasets = dataSetService.getAllDatasets();
        if (datasets.isEmpty()) {
            return "暂无数据集";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共有 ").append(datasets.size()).append(" 个数据集：\n\n");
        for (DataSet ds : datasets) {
            sb.append("📦 ").append(ds.getName()).append("\n");
            sb.append("   ID: ").append(ds.getId()).append("\n");
            sb.append("   记录数: ").append(ds.getRecordCount()).append("\n");
            if (ds.getDescription() != null && !ds.getDescription().isEmpty()) {
                sb.append("   描述: ").append(ds.getDescription()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "搜索数据集中的记录。当用户说'查'、'找'、'搜'某条数据时必须使用此工具，不要使用searchFiles。如果不确定数据集名称，请先调用listDatasets查看所有数据集")
    public String searchRecords(
            @ToolParam(description = "数据集ID或名称") String dataset,
            @ToolParam(description = "搜索关键词（可选，为空则返回所有记录）") String keyword) {
        DataSet ds = findDataset(dataset);
        if (ds == null) {
            return "未找到数据集: " + dataset;
        }

        List<Map<String, Object>> records = dataSetService.searchRecords(ds.getId(), keyword);
        if (records.isEmpty()) {
            return "在数据集「" + ds.getName() + "」中未找到匹配的记录";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("在「").append(ds.getName()).append("」中找到 ").append(records.size()).append(" 条记录：\n\n");
        for (int i = 0; i < Math.min(records.size(), 20); i++) {
            Map<String, Object> record = records.get(i);
            sb.append("[").append(i + 1).append("] ");
            String id = String.valueOf(record.get("_id"));
            sb.append("ID: ").append(id).append(" | ");
            record.forEach((k, v) -> {
                if (!k.startsWith("_") && v != null && !k.equals("id")) {
                    sb.append(k).append(": ").append(v).append(" | ");
                }
            });
            sb.append("\n");
        }
        if (records.size() > 20) {
            sb.append("... 还有 ").append(records.size() - 20).append(" 条记录未显示");
        }
        return sb.toString();
    }

    @Tool(description = "向数据集添加一条新记录。当用户说'记录'、'新增'、'添加'某条数据时必须使用此工具，不要使用writeFile。如果不确定数据集名称，请先调用listDatasets查看所有数据集")
    public String addRecord(
            @ToolParam(description = "数据集ID或名称") String dataset,
            @ToolParam(description = "JSON格式的数据，如 {\"编号\":\"P001\",\"标题\":\"测试BUG\",\"状态\":\"待修复\"}") String jsonData) {
        DataSet ds = findDataset(dataset);
        if (ds == null) {
            return "未找到数据集: " + dataset;
        }

        try {
            Map<String, Object> record = mapper.readValue(jsonData, new TypeReference<Map<String, Object>>() {});
            record.put("更新时间", now());
            int count = dataSetService.addRecords(ds.getId(), List.of(record), "ai_chat");
            if (count > 0) {
                return "✅ 已成功添加 1 条记录到「" + ds.getName() + "」";
            } else {
                return "⚠️ 记录已存在（重复），未添加";
            }
        } catch (Exception e) {
            return "❌ 添加失败: " + e.getMessage();
        }
    }

    @Tool(description = "修改数据集中的一条记录。当用户说'修改'、'更新'、'改为'某条数据时必须使用此工具，不要使用writeFile。如果不确定数据集名称，请先调用listDatasets查看所有数据集")
    public String updateRecord(
            @ToolParam(description = "数据集ID或名称") String dataset,
            @ToolParam(description = "记录ID（_id字段的值）") String recordId,
            @ToolParam(description = "JSON格式的更新字段，如 {\"状态\":\"已修复\"}") String jsonData) {
        DataSet ds = findDataset(dataset);
        if (ds == null) {
            return "未找到数据集: " + dataset;
        }

        try {
            // 先查找原记录
            List<Map<String, Object>> records = dataSetService.searchRecords(ds.getId(), recordId);
            Map<String, Object> original = null;
            for (Map<String, Object> r : records) {
                if (recordId.equals(String.valueOf(r.get("_id")))) {
                    original = r;
                    break;
                }
            }

            if (original == null) {
                return "❌ 未找到记录: " + recordId;
            }

            // 合并更新字段
            Map<String, Object> updates = mapper.readValue(jsonData, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> updatedRecord = new LinkedHashMap<>(original);
            updatedRecord.putAll(updates);
            updatedRecord.put("更新时间", now());

            // 删除原记录，添加新记录
            dataSetService.deleteRecord(ds.getId(), recordId);
            dataSetService.addRecords(ds.getId(), List.of(updatedRecord), "ai_chat_update");

            return "✅ 已更新记录 " + recordId + " 在「" + ds.getName() + "」中";
        } catch (Exception e) {
            return "❌ 更新失败: " + e.getMessage();
        }
    }

    @Tool(description = "删除数据集中的一条记录。如果不确定数据集名称，请先调用listDatasets查看所有数据集")
    public String deleteRecord(
            @ToolParam(description = "数据集ID或名称") String dataset,
            @ToolParam(description = "记录ID（_id字段的值）") String recordId) {
        DataSet ds = findDataset(dataset);
        if (ds == null) {
            return "未找到数据集: " + dataset;
        }

        boolean deleted = dataSetService.deleteRecord(ds.getId(), recordId);
        if (deleted) {
            return "✅ 已删除记录 " + recordId + " 在「" + ds.getName() + "」中";
        } else {
            return "❌ 未找到记录: " + recordId;
        }
    }

    @Tool(description = "获取数据集中某条记录的详细信息。如果不确定数据集名称，请先调用listDatasets查看所有数据集")
    public String getRecord(
            @ToolParam(description = "数据集ID或名称") String dataset,
            @ToolParam(description = "记录ID（_id字段的值）") String recordId) {
        DataSet ds = findDataset(dataset);
        if (ds == null) {
            return "未找到数据集: " + dataset;
        }

        List<Map<String, Object>> records = dataSetService.searchRecords(ds.getId(), recordId);
        for (Map<String, Object> record : records) {
            if (recordId.equals(String.valueOf(record.get("_id")))) {
                StringBuilder sb = new StringBuilder();
                sb.append("📋 记录详情（数据集: ").append(ds.getName()).append("）\n\n");
                record.forEach((k, v) -> {
                    if (v != null) {
                        sb.append(k).append(": ").append(v).append("\n");
                    }
                });
                return sb.toString();
            }
        }
        return "❌ 未找到记录: " + recordId;
    }

    /**
     * 根据ID或名称查找数据集
     */
    private DataSet findDataset(String identifier) {
        // 先尝试按ID查找
        DataSet ds = dataSetService.getDataset(identifier);
        if (ds != null) return ds;

        // 再尝试按名称查找
        List<DataSet> all = dataSetService.getAllDatasets();
        for (DataSet d : all) {
            if (d.getName().equalsIgnoreCase(identifier) || d.getName().contains(identifier)) {
                return d;
            }
        }
        return null;
    }
}
