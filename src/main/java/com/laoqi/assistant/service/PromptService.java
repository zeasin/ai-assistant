package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.util.FileUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    private static final TypeReference<Map<String, Map<String, String>>> PROMPT_MAP_TYPE = new TypeReference<>() {};
    private static final String DEFAULT_RESOURCE = "prompts-defaults.json";

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);

    private record PromptDef(String title, String sessionTitle, String template) {}

    private final AppConfig appConfig;
    private final ConfigService configService;
    private Map<String, PromptDef> prompts = new LinkedHashMap<>();

    public PromptService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        // 1. 尝试从笔记库 AI/prompts/ 加载 MD 文件
        Map<String, PromptDef> mdPrompts = loadFromNotesDir();
        if (!mdPrompts.isEmpty()) {
            prompts = mdPrompts;
            log.info("PromptService loaded {} prompts from AI/prompts/ MD files", mdPrompts.size());
            return;
        }

        // 2. 笔记库中没有提示词，从 classpath JSON 默认值生成 MD 文件到笔记库
        log.info("No MD prompts found in notes library, generating from JSON defaults...");
        Map<String, Map<String, String>> jsonPrompts = loadDefaults();
        Map<String, Map<String, String>> external = loadExternalJson();
        if (external != null) {
            jsonPrompts.putAll(external);
        }

        saveToMdFiles(jsonPrompts);

        // 3. 重新从 MD 文件加载（确保内容一致）
        prompts = loadFromNotesDir();
        if (prompts.isEmpty()) {
            // 极端情况：MD 文件生成失败，直接使用 JSON 数据
            prompts = convertFromJson(jsonPrompts);
            log.warn("Failed to generate MD prompt files, using JSON in-memory");
        } else {
            log.info("Generated and loaded {} prompts from AI/prompts/ MD files", prompts.size());
        }
    }

    /**
     * 从笔记库 AI/prompts/ 目录加载所有 .md 文件作为提示词
     */
    private Map<String, PromptDef> loadFromNotesDir() {
        Path promptsDir = getPromptsDir();
        if (promptsDir == null || !Files.exists(promptsDir)) {
            log.debug("Prompts directory not found: {}", promptsDir);
            return Map.of();
        }

        Map<String, PromptDef> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(promptsDir)) {
            List<Path> mdFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .collect(Collectors.toList());

            for (Path file : mdFiles) {
                String key = file.getFileName().toString().replace(".md", "");
                String raw = FileUtil.readText(file);
                if (raw == null || raw.isBlank()) {
                    log.warn("Empty prompt file: {}", file);
                    continue;
                }
                PromptDef def = parseMdPrompt(raw);
                result.put(key, def);
                log.debug("Loaded prompt: {} (title={})", key, def.title);
            }
        } catch (Exception e) {
            log.warn("Failed to load prompts from notes dir: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 解析 MD 文件：提取 YAML frontmatter + 正文
     * frontmatter 损坏时整篇作为 template，key 作为 session_title
     */
    private PromptDef parseMdPrompt(String raw) {
        String title = "";
        String sessionTitle = "";
        String template = raw.trim();

        Matcher m = FRONTMATTER_PATTERN.matcher(raw);
        if (m.find()) {
            String yaml = m.group(1);
            title = extractField(yaml, "title", "");
            sessionTitle = extractField(yaml, "session_title", title);
            template = raw.substring(m.end()).trim();
        }

        return new PromptDef(title, sessionTitle.isEmpty() ? title : sessionTitle, template);
    }

    private String extractField(String yaml, String fieldName, String fallback) {
        Pattern p = Pattern.compile(
                "^\\s*" + Pattern.quote(fieldName) + "\\s*:\\s*(.+)\\s*$", Pattern.MULTILINE);
        Matcher m = p.matcher(yaml);
        if (m.find()) {
            return m.group(1).trim().replaceAll("^[\"']|[\"']$", "");
        }
        return fallback;
    }

    private Path getPromptsDir() {
        String baseDir = configService.getBaseDir();
        if (baseDir == null || baseDir.isEmpty()) return null;
        return Paths.get(baseDir).resolve("AI").resolve("prompts");
    }

    // ---- JSON fallback methods (unchanged) ----

    private Map<String, Map<String, String>> loadDefaults() {
        try {
            ClassPathResource resource = new ClassPathResource(DEFAULT_RESOURCE);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return new ObjectMapper().readValue(is, PROMPT_MAP_TYPE);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load default prompts from classpath: {}", e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    private Path getExternalJsonPath() {
        return appConfig.getConfigFile().resolveSibling("prompts.json");
    }

    private Map<String, Map<String, String>> loadExternalJson() {
        Path external = getExternalJsonPath();
        if (FileUtil.exists(external)) {
            try {
                return FileUtil.readJson(external, PROMPT_MAP_TYPE, null);
            } catch (Exception e) {
                log.warn("Failed to load external prompts from {}: {}", external, e.getMessage());
            }
        }
        return null;
    }

    private Map<String, PromptDef> convertFromJson(Map<String, Map<String, String>> json) {
        Map<String, PromptDef> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : json.entrySet()) {
            String title = e.getValue().getOrDefault("session_title", e.getKey());
            result.put(e.getKey(), new PromptDef(title, title, e.getValue().get("template")));
        }
        return result;
    }

    // ---- Public API (unchanged interface) ----

    public String getTemplate(String key) {
        PromptDef def = prompts.get(key);
        return def != null ? def.template() : null;
    }

    public String getSessionTitle(String key) {
        PromptDef def = prompts.get(key);
        return def != null ? def.sessionTitle() : null;
    }

    public String format(String key, Map<String, String> variables) {
        String template = getTemplate(key);
        if (template == null) return null;
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, String> e : variables.entrySet()) {
                result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
            }
        }
        return result;
    }

    /**
     * 获取所有提示词（兼容前端 /prompts 页面）
     * 返回格式: {key: {template: ..., session_title: ...}}
     */
    public Map<String, Map<String, String>> getAllPrompts() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, PromptDef> e : prompts.entrySet()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("template", e.getValue().template());
            entry.put("session_title", e.getValue().sessionTitle());
            result.put(e.getKey(), entry);
        }
        return result;
    }

    /**
     * 保存提示词。MD 模式下写入到 AI/prompts/ 目录，JSON 模式写入 prompts.json
     */
    public void savePrompts(Map<String, Map<String, String>> promptsData) {
        // 如果当前是 MD 模式，写入到笔记库 AI/prompts/ 目录
        if (usesMdMode()) {
            saveToMdFiles(promptsData);
            reload();
            log.info("Saved {} prompts to AI/prompts/ MD files", promptsData.size());
            return;
        }

        // JSON 模式：写入 prompts.json
        Path external = getExternalJsonPath();
        Map<String, Map<String, String>> writeable = new LinkedHashMap<>(promptsData);
        FileUtil.writeJson(external, writeable);
        reload();
        log.info("Saved {} prompts to {}", promptsData.size(), external);
    }

    /**
     * 判断当前是否使用 MD 模式
     */
    private boolean usesMdMode() {
        Path dir = getPromptsDir();
        if (dir == null || !Files.exists(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".md"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 将提示词写入到 AI/prompts/ 目录的 MD 文件
     */
    private void saveToMdFiles(Map<String, Map<String, String>> promptsData) {
        Path promptsDir = getPromptsDir();
        if (promptsDir == null) return;
        try {
            Files.createDirectories(promptsDir);
        } catch (Exception e) {
            log.warn("Failed to create prompts dir: {}", e.getMessage());
        }

        for (Map.Entry<String, Map<String, String>> e : promptsData.entrySet()) {
            String key = e.getKey();
            String template = e.getValue().getOrDefault("template", "");
            String sessionTitle = e.getValue().getOrDefault("session_title", key);

            StringBuilder md = new StringBuilder();
            md.append("---\n");
            md.append("title: ").append(sessionTitle).append("\n");
            md.append("session_title: ").append(sessionTitle).append("\n");
            md.append("---\n\n");
            md.append("# ").append(sessionTitle).append("\n\n");
            md.append(template.trim()).append("\n");

            Path file = promptsDir.resolve(key + ".md");
            FileUtil.writeText(file, md.toString());
            log.debug("Saved prompt to MD: {}", file);
        }
    }

    /**
     * 重置提示词：MD 模式下删除所有 MD 文件；JSON 模式下删除 prompts.json
     */
    public void resetPrompts() {
        if (usesMdMode()) {
            Path promptsDir = getPromptsDir();
            if (promptsDir != null && Files.exists(promptsDir)) {
                try (Stream<Path> files = Files.list(promptsDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".md"))
                         .forEach(p -> {
                             try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                         });
                } catch (Exception e) {
                    log.warn("Failed to reset MD prompts: {}", e.getMessage());
                }
            }
        } else {
            Path external = getExternalJsonPath();
            try {
                Files.deleteIfExists(external);
            } catch (Exception e) {
                log.warn("Failed to delete external prompts file: {}", e.getMessage());
            }
        }
        reload();
        log.info("Reset prompts to defaults");
    }
}