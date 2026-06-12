package com.laoqi.assistant.collector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.collector.model.CollectorLog;
import com.laoqi.assistant.collector.model.CollectorResult;
import com.laoqi.assistant.collector.model.CollectorTask;
import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.OpenCodeService;
import com.laoqi.assistant.service.PromptService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CollectorService {

    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_LOG_ENTRIES = 100;
    private static final int MAX_RESULT_ENTRIES = 50;

    private final OpenCodeService openCodeService;
    private final PromptService promptService;
    private final LogService logService;
    private final ConfigService configService;
    
    private final Map<String, CollectorTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<CollectorLog>> taskLogs = new ConcurrentHashMap<>();
    private final Map<String, List<CollectorResult>> taskResults = new ConcurrentHashMap<>();

    public CollectorService(OpenCodeService openCodeService, PromptService promptService, 
                           LogService logService, ConfigService configService) {
        this.openCodeService = openCodeService;
        this.promptService = promptService;
        this.logService = logService;
        this.configService = configService;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing CollectorService, collector dir: {}", getCollectorDir());
        log.info("Tasks file path: {}", getTasksFile());
        loadTasks();
        log.info("Loaded {} tasks into memory", tasks.size());
        loadLogs();
        loadResults();
        initDefaultTask();
    }

    private void initDefaultTask() {
        if (!tasks.isEmpty()) {
            log.info("Tasks already exist, skipping default task creation");
            return;
        }
        
        Path tasksFile = getTasksFile();
        if (FileUtil.exists(tasksFile)) {
            String content = FileUtil.readText(tasksFile);
            if (content != null && !content.isBlank()) {
                try {
                    List<CollectorTask> existingTasks = mapper.readValue(content, 
                        new TypeReference<List<CollectorTask>>() {});
                    if (existingTasks != null && !existingTasks.isEmpty()) {
                        log.info("Tasks file has data, skipping default task creation");
                        return;
                    }
                } catch (Exception e) {
                    log.info("Failed to parse tasks file, assuming empty");
                }
            }
        }
        
        CollectorTask defaultTask = new CollectorTask();
        defaultTask.setId("csdn-default");
        defaultTask.setName("CSDN数据采集");
        defaultTask.setPromptKey("csdn-collect");
        defaultTask.setCronExpression("0 0 8 * * ?");
        defaultTask.setEnabled(false);
        defaultTask.setOutputPath("自媒体/data");
        defaultTask.setCreatedAt(TimeUtil.nowStr());
        defaultTask.setUpdatedAt(TimeUtil.nowStr());
        tasks.put(defaultTask.getId(), defaultTask);
        saveTasks();
        log.info("Created default CSDN collector task");
    }

    private Path getCollectorDir() {
        try {
            String baseDir = configService.load().getBaseDir();
            Path dir;
            if (baseDir != null && !baseDir.isEmpty()) {
                dir = Paths.get(baseDir).resolve("ai").resolve("collector");
            } else {
                dir = Paths.get(System.getProperty("user.dir")).resolve("ai").resolve("collector");
            }
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (Exception e) {
            log.warn("Failed to get collector directory, using current directory");
            return Paths.get(System.getProperty("user.dir"));
        }
    }

    private Path getTasksFile() {
        return getCollectorDir().resolve("tasks.json");
    }

    private Path getLogsDir() {
        try {
            Path dir = getCollectorDir().resolve("logs");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (Exception e) {
            return getCollectorDir();
        }
    }

    private Path getResultsDir() {
        try {
            Path dir = getCollectorDir().resolve("results");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (Exception e) {
            return getCollectorDir();
        }
    }

    private void loadTasks() {
        try {
            Path tasksFile = getTasksFile();
            log.info("Loading tasks from: {}", tasksFile.toAbsolutePath());
            log.info("File exists: {}", FileUtil.exists(tasksFile));
            if (FileUtil.exists(tasksFile)) {
                String content = FileUtil.readText(tasksFile);
                log.info("File content length: {} bytes", content != null ? content.length() : 0);
                if (content != null && !content.isBlank()) {
                    List<CollectorTask> loaded = FileUtil.readJson(tasksFile,
                        new TypeReference<List<CollectorTask>>() {}, new ArrayList<>());
                    log.info("Parsed {} tasks from file", loaded.size());
                    for (CollectorTask task : loaded) {
                        tasks.put(task.getId(), task);
                        log.info("Loaded task: {} ({})", task.getName(), task.getId());
                    }
                }
                log.info("Loaded {} collector tasks total", tasks.size());
            }
        } catch (Exception e) {
            log.error("Failed to load collector tasks: {}", e.getMessage(), e);
        }
    }

    private void saveTasks() {
        try {
            FileUtil.writeJson(getTasksFile(), new ArrayList<>(tasks.values()));
        } catch (Exception e) {
            log.error("Failed to save collector tasks: {}", e.getMessage());
        }
    }

    private void loadLogs() {
        try {
            Path logsDir = getLogsDir();
            if (Files.exists(logsDir)) {
                Files.list(logsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            String taskId = path.getFileName().toString().replace(".json", "");
                            List<CollectorLog> logs = FileUtil.readJson(path,
                                new TypeReference<List<CollectorLog>>() {}, new ArrayList<>());
                            taskLogs.put(taskId, logs);
                        } catch (Exception e) {
                            log.warn("Failed to load logs from {}", path);
                        }
                    });
            }
        } catch (Exception e) {
            log.warn("Failed to load collector logs: {}", e.getMessage());
        }
    }

    private void saveLogs(String taskId) {
        try {
            Path logFile = getLogsDir().resolve(taskId + ".json");
            List<CollectorLog> logs = taskLogs.getOrDefault(taskId, new ArrayList<>());
            if (logs.size() > MAX_LOG_ENTRIES) {
                logs = new ArrayList<>(logs.subList(0, MAX_LOG_ENTRIES));
                taskLogs.put(taskId, logs);
            }
            FileUtil.writeJson(logFile, logs);
        } catch (Exception e) {
            log.error("Failed to save collector logs: {}", e.getMessage());
        }
    }

    private void loadResults() {
        try {
            Path resultsDir = getResultsDir();
            if (Files.exists(resultsDir)) {
                Files.list(resultsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            String taskId = path.getFileName().toString().replace(".json", "");
                            List<CollectorResult> results = FileUtil.readJson(path,
                                new TypeReference<List<CollectorResult>>() {}, new ArrayList<>());
                            taskResults.put(taskId, results);
                        } catch (Exception e) {
                            log.warn("Failed to load results from {}", path);
                        }
                    });
            }
        } catch (Exception e) {
            log.warn("Failed to load collector results: {}", e.getMessage());
        }
    }

    private void saveResults(String taskId) {
        try {
            Path resultFile = getResultsDir().resolve(taskId + ".json");
            List<CollectorResult> results = taskResults.getOrDefault(taskId, new ArrayList<>());
            if (results.size() > MAX_RESULT_ENTRIES) {
                results = new ArrayList<>(results.subList(0, MAX_RESULT_ENTRIES));
                taskResults.put(taskId, results);
            }
            FileUtil.writeJson(resultFile, results);
        } catch (Exception e) {
            log.error("Failed to save collector results: {}", e.getMessage());
        }
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
        saveTasks();
        log.info("Created collector task: {}", id);
        return task;
    }

    public CollectorTask updateTask(String id, CollectorTask task) {
        CollectorTask existing = tasks.get(id);
        if (existing == null) return null;
        
        if (task.getName() != null) existing.setName(task.getName());
        if (task.getPromptKey() != null) existing.setPromptKey(task.getPromptKey());
        if (task.getCronExpression() != null) existing.setCronExpression(task.getCronExpression());
        if (task.getEnabled() != null) existing.setEnabled(task.getEnabled());
        if (task.getOutputPath() != null) existing.setOutputPath(task.getOutputPath());
        if (task.getParams() != null) existing.setParams(task.getParams());
        existing.setUpdatedAt(TimeUtil.nowStr());
        
        saveTasks();
        log.info("Updated collector task: {}", id);
        return existing;
    }

    public boolean deleteTask(String id) {
        if (tasks.remove(id) != null) {
            taskLogs.remove(id);
            taskResults.remove(id);
            saveTasks();
            try {
                Files.deleteIfExists(getLogsDir().resolve(id + ".json"));
                Files.deleteIfExists(getResultsDir().resolve(id + ".json"));
            } catch (Exception e) {
                log.warn("Failed to delete log/result files for task {}", id);
            }
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

        CollectorLog logEntry = new CollectorLog();
        logEntry.setId(UUID.randomUUID().toString());
        logEntry.setTaskId(taskId);
        logEntry.setTaskName(task.getName());
        logEntry.setStartTime(TimeUtil.nowStr());
        logEntry.setStatus("RUNNING");

        try {
            if (!openCodeService.isHealthy()) {
                throw new RuntimeException("opencode serve is not running");
            }

            String template = getPromptTemplate(task.getPromptKey());
            if (template == null) {
                throw new RuntimeException("Prompt template not found: " + task.getPromptKey() + " (check AI/collector/prompts/)");
            }

            String prompt = template;
            if (task.getParams() != null) {
                for (Map.Entry<String, String> param : task.getParams().entrySet()) {
                    prompt = prompt.replace("{" + param.getKey() + "}", param.getValue() != null ? param.getValue() : "");
                }
            }

            String sessionId = openCodeService.findIdleSession();
            if (sessionId == null) {
                sessionId = openCodeService.createSession(task.getName());
            }
            String rawResponse = openCodeService.sendMessage(sessionId, prompt);

            String parsedData = parseResponse(rawResponse);

            CollectorResult result = new CollectorResult();
            result.setId(UUID.randomUUID().toString());
            result.setTaskId(taskId);
            result.setTaskName(task.getName());
            result.setCollectTime(TimeUtil.nowStr());
            result.setRawResponse(rawResponse);
            result.setParsedData(parsedData);

            if (task.getOutputPath() != null && !task.getOutputPath().isEmpty()) {
                saveResultToFile(task, result);
            }

            logEntry.setEndTime(TimeUtil.nowStr());
            logEntry.setStatus("SUCCESS");
            logEntry.setResultSize(rawResponse.length());

            taskResults.computeIfAbsent(taskId, k -> new ArrayList<>()).add(0, result);
            taskLogs.computeIfAbsent(taskId, k -> new ArrayList<>()).add(0, logEntry);

            saveLogs(taskId);
            saveResults(taskId);

            logService.add("数据采集", "成功", "任务: " + task.getName());
            log.info("Task {} executed successfully, result size: {}", taskId, rawResponse.length());
            return result;

        } catch (Exception e) {
            logEntry.setEndTime(TimeUtil.nowStr());
            logEntry.setStatus("FAILED");
            logEntry.setErrorMessage(e.getMessage());
            taskLogs.computeIfAbsent(taskId, k -> new ArrayList<>()).add(0, logEntry);
            saveLogs(taskId);
            
            logService.add("数据采集", "失败", "任务: " + task.getName() + ", 错误: " + e.getMessage());
            log.error("Task {} execution failed: {}", taskId, e.getMessage());
            throw new RuntimeException("Task execution failed: " + e.getMessage(), e);
        }
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

    private void saveResultToFile(CollectorTask task, CollectorResult result) {
        try {
            Path dir = Paths.get(task.getOutputPath());
            if (!dir.toFile().exists()) {
                dir.toFile().mkdirs();
            }
            String fileName = TimeUtil.todayStr() + "_" + task.getId() + ".json";
            Path filePath = dir.resolve(fileName);
            
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("taskId", task.getId());
            output.put("taskName", task.getName());
            output.put("collectTime", result.getCollectTime());
            output.put("data", result.getParsedData());
            
            FileUtil.writeJson(filePath, output);
            log.info("Result saved to: {}", filePath);
        } catch (Exception e) {
            log.error("Failed to save result to file: {}", e.getMessage());
        }
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

    private Path getPromptsDir() {
        try {
            Path dir = getCollectorDir().resolve("prompts");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (Exception e) {
            return getCollectorDir();
        }
    }

    private static final java.util.regex.Pattern FRONTMATTER_PATTERN = java.util.regex.Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", java.util.regex.Pattern.DOTALL);

    public Map<String, String> loadPrompts() {
        Map<String, String> result = new LinkedHashMap<>();
        Path dir = getPromptsDir();
        if (!Files.exists(dir)) return result;
        try (var files = Files.list(dir)) {
            List<Path> mdFiles = files.filter(p -> p.toString().endsWith(".md"))
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path file : mdFiles) {
                String key = file.getFileName().toString().replace(".md", "");
                String raw = FileUtil.readText(file);
                if (raw == null || raw.isBlank()) continue;
                String template = raw.trim();
                java.util.regex.Matcher m = FRONTMATTER_PATTERN.matcher(raw);
                if (m.find()) {
                    template = raw.substring(m.end()).trim();
                }
                result.put(key, template);
            }
        } catch (Exception e) {
            log.warn("Failed to load collector prompts: {}", e.getMessage());
        }
        return result;
    }

    public String getPromptTemplate(String key) {
        return loadPrompts().get(key);
    }

    public void savePromptTemplate(String key, String template) {
        Path dir = getPromptsDir();
        String content = "---\ntitle: " + key + "\n---\n\n" + template.trim() + "\n";
        FileUtil.writeText(dir.resolve(key + ".md"), content);
        log.info("Saved collector prompt: {}", key);
    }
}