package com.laoqi.assistant.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.collector.model.CollectorLog;
import com.laoqi.assistant.collector.model.CollectorResult;
import com.laoqi.assistant.collector.model.CollectorTask;
import com.laoqi.assistant.service.LlmService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.datacenter.DataSetService;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CollectorService {

    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Map<String, String> DEFAULT_PROMPTS = Map.of(
        "csdn-collect", "请从CSDN博客页面中提取文章数据，包括：标题、作者、发布时间、阅读数、点赞数、评论数。输出JSON数组格式。"
    );

    private final LlmService llmService;
    private final LogService logService;
    private final DataSetService dataSetService;

    private final Map<String, CollectorTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<CollectorLog>> taskLogs = new ConcurrentHashMap<>();
    private final Map<String, List<CollectorResult>> taskResults = new ConcurrentHashMap<>();

    public CollectorService(LlmService llmService,
                           LogService logService,
                           DataSetService dataSetService) {
        this.llmService = llmService;
        this.logService = logService;
        this.dataSetService = dataSetService;
    }

    public List<CollectorTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public CollectorTask getTask(String id) {
        return tasks.get(id);
    }

    public CollectorTask createTask(CollectorTask task) {
        String id = UUID.randomUUID().toString();
        task.setId(id);
        task.setCreatedAt(TimeUtil.nowStr());
        task.setUpdatedAt(TimeUtil.nowStr());
        if (task.getEnabled() == null) {
            task.setEnabled(true);
        }

        tasks.put(id, task);
        log.info("Created collector task: {}", id);
        return task;
    }

    public CollectorTask updateTask(String id, CollectorTask task) {
        CollectorTask existing = tasks.get(id);
        if (existing == null) return null;

        if (task.getName() != null) existing.setName(task.getName());
        if (task.getTaskType() != null) existing.setTaskType(task.getTaskType());
        if (task.getPromptKey() != null) existing.setPromptKey(task.getPromptKey());
        if (task.getUrl() != null) existing.setUrl(task.getUrl());
        if (task.getCronExpression() != null) existing.setCronExpression(task.getCronExpression());
        if (task.getEnabled() != null) existing.setEnabled(task.getEnabled());
        if (task.getDatasetId() != null) existing.setDatasetId(task.getDatasetId());
        if (task.getParams() != null) existing.setParams(task.getParams());
        existing.setUpdatedAt(TimeUtil.nowStr());

        log.info("Updated collector task: {}", id);
        return existing;
    }

    public boolean deleteTask(String id) {
        if (tasks.remove(id) != null) {
            taskLogs.remove(id);
            taskResults.remove(id);
            log.info("Deleted collector task: {}", id);
            return true;
        }
        return false;
    }

    public CollectorResult executeTask(String taskId) {
        CollectorTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        String taskType = task.getTaskType();
        if (taskType == null || taskType.isEmpty() || "ai_prompt".equals(taskType)) {
            return executeAiPromptTask(task);
        } else if ("url_fetch".equals(taskType)) {
            return executeUrlFetchTask(task);
        } else {
            throw new IllegalArgumentException("Unknown task type: " + taskType);
        }
    }

    private CollectorResult executeAiPromptTask(CollectorTask task) {
        String taskId = task.getId();
        long startMs = System.currentTimeMillis();
        log.info("🚀 [采集器] AI任务启动: {} ({}) — {}", task.getName(), taskId, TimeUtil.nowStr());

        CollectorLog logEntry = createLogEntry(task);

        try {
            String template = DEFAULT_PROMPTS.get(task.getPromptKey());
            if (template == null) {
                template = "请根据要求提取数据，输出JSON数组格式。";
            }

            String prompt = template;
            if (task.getParams() != null) {
                for (Map.Entry<String, String> param : task.getParams().entrySet()) {
                    prompt = prompt.replace("{" + param.getKey() + "}", param.getValue() != null ? param.getValue() : "");
                }
            }

            String rawResponse;
            if (!llmService.isAvailable()) {
                throw new RuntimeException("LLM API Key 未配置");
            }
            rawResponse = llmService.chat("你是一个数据采集助手。请严格按要求的格式输出JSON数据。", prompt);

            String parsedData = parseResponse(rawResponse);

            CollectorResult result = buildResult(task, rawResponse, parsedData);
            postExecute(task, result, logEntry, rawResponse.length(), startMs);
            return result;

        } catch (Exception e) {
            failExecute(task, logEntry, e, startMs);
            throw new RuntimeException("Task execution failed: " + e.getMessage(), e);
        }
    }

    private CollectorResult executeUrlFetchTask(CollectorTask task) {
        String taskId = task.getId();
        long startMs = System.currentTimeMillis();
        log.info("🚀 [采集器] URL任务启动: {} ({}) url={} — {}", task.getName(), taskId, task.getUrl(), TimeUtil.nowStr());

        CollectorLog logEntry = createLogEntry(task);

        try {
            if (task.getUrl() == null || task.getUrl().isBlank()) {
                throw new RuntimeException("URL未配置");
            }

            String prompt = "请访问以下URL，提取页面中的结构化数据。\n" +
                    "URL: " + task.getUrl() + "\n\n" +
                    "**重要：不要保存到文件，不要写入任何文件。只在回复中直接输出JSON数据。**\n\n" +
                    "输出格式要求：\n" +
                    "1. 直接输出JSON数组，不要包含其他文字说明\n" +
                    "2. 用 ```json 包裹\n" +
                    "3. 每个元素是一个对象，包含页面中的数据字段";

            if (task.getDatasetId() != null && !task.getDatasetId().isEmpty()) {
                try {
                    var ds = dataSetService.getDataset(task.getDatasetId());
                    if (ds != null && ds.getSchema() != null && ds.getSchema().getFields() != null) {
                        prompt += "\n\n必须使用以下字段名（保持英文）：\n";
                        for (var field : ds.getSchema().getFields()) {
                            prompt += "- " + field.getName();
                            if (field.getDisplayName() != null) {
                                prompt += "（" + field.getDisplayName() + "）";
                            }
                            prompt += "\n";
                        }
                        prompt += "\n示例格式：[{\"title\":\"标题\",\"views\":123}, ...]";
                    }
                } catch (Exception e) {
                    log.warn("Failed to load dataset schema: {}", e.getMessage());
                }
            }

            String rawResponse;
            if (!llmService.isAvailable()) {
                throw new RuntimeException("LLM API Key 未配置");
            }
            rawResponse = llmService.chat("你是一个数据采集助手。请严格按要求的格式输出JSON数据。", prompt);

            String parsedData = parseResponse(rawResponse);

            CollectorResult result = buildResult(task, rawResponse, parsedData);
            postExecute(task, result, logEntry, rawResponse.length(), startMs);
            return result;

        } catch (Exception e) {
            failExecute(task, logEntry, e, startMs);
            throw new RuntimeException("Task execution failed: " + e.getMessage(), e);
        }
    }

    private CollectorLog createLogEntry(CollectorTask task) {
        CollectorLog logEntry = new CollectorLog();
        logEntry.setId(UUID.randomUUID().toString());
        logEntry.setTaskId(task.getId());
        logEntry.setTaskName(task.getName());
        logEntry.setStartTime(TimeUtil.nowStr());
        logEntry.setStatus("RUNNING");
        return logEntry;
    }

    private CollectorResult buildResult(CollectorTask task, String rawResponse, String parsedData) {
        CollectorResult result = new CollectorResult();
        result.setId(UUID.randomUUID().toString());
        result.setTaskId(task.getId());
        result.setTaskName(task.getName());
        result.setCollectTime(TimeUtil.nowStr());
        result.setRawResponse(rawResponse);
        result.setParsedData(parsedData);
        return result;
    }

    private void postExecute(CollectorTask task, CollectorResult result, CollectorLog logEntry, int responseSize, long startMs) {
        String taskId = task.getId();

        if (task.getDatasetId() != null && !task.getDatasetId().isEmpty()) {
            try {
                List<Map<String, Object>> records = parseToRecords(result.getParsedData());
                if (!records.isEmpty()) {
                    int count = dataSetService.addRecords(task.getDatasetId(), records, "collector");
                    log.info("Auto-imported {} records to dataset {} from task {}", count, task.getDatasetId(), taskId);
                }
            } catch (Exception ie) {
                log.warn("Failed to auto-import to dataset {}: {}", task.getDatasetId(), ie.getMessage());
            }
        }

        logEntry.setEndTime(TimeUtil.nowStr());
        logEntry.setStatus("SUCCESS");
        logEntry.setResultSize(responseSize);

        taskResults.computeIfAbsent(taskId, k -> new ArrayList<>()).add(0, result);
        taskLogs.computeIfAbsent(taskId, k -> new ArrayList<>()).add(0, logEntry);

        logService.add("数据采集", "成功", "任务: " + task.getName());
        log.info("✅ [采集器] 任务完成: {} ({}) — {} (耗时: {}ms)",
                task.getName(), taskId, TimeUtil.nowStr(), System.currentTimeMillis() - startMs);
    }

    private void failExecute(CollectorTask task, CollectorLog logEntry, Exception e, long startMs) {
        String taskId = task.getId();
        logEntry.setEndTime(TimeUtil.nowStr());
        logEntry.setStatus("FAILED");
        logEntry.setErrorMessage(e.getMessage());
        taskLogs.computeIfAbsent(taskId, k -> new ArrayList<>()).add(0, logEntry);

        logService.add("数据采集", "失败", "任务: " + task.getName() + ", 错误: " + e.getMessage());
        log.error("❌ [采集器] 任务失败: {} ({}) — {} (耗时: {}ms): {}",
                task.getName(), taskId, TimeUtil.nowStr(), System.currentTimeMillis() - startMs, e.getMessage());
    }

    private String parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) return "{}";

        Matcher m = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```").matcher(rawResponse);
        if (m.find()) {
            return m.group(1).trim();
        }

        int start = rawResponse.indexOf('{');
        int end = rawResponse.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return rawResponse.substring(start, end + 1);
        }

        try {
            return mapper.writeValueAsString(Map.of("text", rawResponse));
        } catch (Exception e) {
            return "{\"text\": \"\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseToRecords(String parsedData) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (parsedData == null || parsedData.isBlank()) return records;
        try {
            Object obj = mapper.readValue(parsedData, Object.class);
            if (obj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> record = new HashMap<>();
                        map.forEach((k, v) -> record.put(String.valueOf(k), v));
                        records.add(record);
                    }
                }
            } else if (obj instanceof Map<?, ?> map) {
                Map<String, Object> record = new HashMap<>();
                map.forEach((k, v) -> record.put(String.valueOf(k), v));
                records.add(record);
            }
        } catch (Exception e) {
            log.warn("Failed to parse response as records: {}", e.getMessage());
        }
        return records;
    }

    public List<CollectorLog> getTaskLogs(String taskId) {
        return taskLogs.getOrDefault(taskId, new ArrayList<>());
    }

    public List<CollectorResult> getTaskResults(String taskId) {
        return taskResults.getOrDefault(taskId, new ArrayList<>());
    }

    public List<CollectorLog> getAllLogs() {
        List<CollectorLog> allLogs = new ArrayList<>();
        taskLogs.values().forEach(allLogs::addAll);
        allLogs.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return allLogs;
    }

    public Map<String, String> loadPrompts() {
        return new LinkedHashMap<>(DEFAULT_PROMPTS);
    }

    public String getPromptTemplate(String key) {
        return DEFAULT_PROMPTS.get(key);
    }

    public void savePromptTemplate(String key, String template) {
        log.info("Prompt template saved in-memory: {}", key);
    }
}