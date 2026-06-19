package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 笔记库工具集 — Spring AI 2.0 @Tool 注解版。
 * 每个方法用 @Tool 声明，AI 自动通过 ToolCallingAdvisor 调用。
 */
@Component
public class NoteTools {

    private static final Logger log = LoggerFactory.getLogger(NoteTools.class);
    private static final ThreadLocal<Long> CURRENT_KB_ID = new ThreadLocal<>();
    private static final ThreadLocal<Path> CURRENT_SCOPE = new ThreadLocal<>();

    private final ConfigService configService;
    private final KnowledgeBaseService kbService;

    public NoteTools(ConfigService configService, KnowledgeBaseService kbService) {
        this.configService = configService;
        this.kbService = kbService;
    }

    public static void setCurrentKbId(Long kbId) {
        if (kbId != null) {
            CURRENT_KB_ID.set(kbId);
        } else {
            CURRENT_KB_ID.remove();
        }
    }

    public static Long getCurrentKbId() {
        return CURRENT_KB_ID.get();
    }

    public static void clearCurrentKbId() {
        CURRENT_KB_ID.remove();
    }

    public static void setScope(Path scopeDir) {
        CURRENT_SCOPE.set(scopeDir);
    }

    public static void clearScope() {
        CURRENT_SCOPE.remove();
    }

    private Path baseDir() {
        Path scope = CURRENT_SCOPE.get();
        if (scope != null) return scope;
        Long kbId = CURRENT_KB_ID.get();
        String dir = kbService.getNotesDirById(kbId);
        return Path.of(dir);
    }

    @Tool(description = "列出笔记库指定目录下的所有文件和子目录，path 是相对于笔记库根目录的路径")
    public String listDir(@ToolParam(description = "目录路径，相对于笔记库根目录，例如 \"工作/日报\"") String path) {
        Path dir = baseDir().resolve(path != null ? path : "").normalize();
        if (!dir.startsWith(baseDir())) return "路径越界: " + path;
        if (!Files.isDirectory(dir)) {
            return "目录不存在: " + (path != null ? path : "/");
        }
        try (Stream<Path> stream = Files.list(dir)) {
            String result = stream
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(p -> (Files.isDirectory(p) ? "📁 " : "📄 ") + p.getFileName())
                    .sorted()
                    .collect(Collectors.joining("\n"));
            if (result.isEmpty()) return "（空目录）";
            return result;
        } catch (IOException e) {
            return "读取目录失败: " + e.getMessage();
        }
    }

    @Tool(description = "读取笔记库中指定文件的内容，path 是相对于笔记库根目录的路径")
    public String readFile(@ToolParam(description = "文件路径，相对于笔记库根目录，例如 \"工作/日报/2024-01-01.md\"") String path) {
        if (path == null || path.isEmpty()) return "文件路径不能为空";
        Path file = baseDir().resolve(path).normalize();
        if (!file.startsWith(baseDir())) return "路径越界: " + path;
        if (!Files.isRegularFile(file)) return "文件不存在: " + path;

        String content = FileUtil.readText(file);
        if (content.isEmpty()) return "（空文件）";

        if (content.length() > 8000) {
            return content.substring(0, 8000) + "\n\n...（文件过长，仅显示前 8000 字符）";
        }
        return content;
    }

    @Tool(description = "将数据写入笔记库指定文件。如果文件已存在，需要先读取原有内容，合并后再写入")
    public String writeFile(
            @ToolParam(description = "文件路径，相对于笔记库根目录") String path,
            @ToolParam(description = "要写入的完整文件内容") String content) {
        if (path == null || path.isEmpty()) return "文件路径不能为空";
        Path file = baseDir().resolve(path).normalize();
        if (!file.startsWith(baseDir())) return "路径越界: " + path;

        FileUtil.writeText(file, content);
        log.info("[NoteTools] 写入文件: {} ({} 字符)", path, content.length());
        return "写入成功: " + path;
    }

    @Tool(description = "在笔记库中搜索文件名包含指定关键词的文件和目录（仅搜索文件名，不搜索文件内容），搜索结果限制 20 条")
    public String searchFiles(@ToolParam(description = "搜索关键词，如 BUG、客户、日报") String keyword) {
        if (keyword == null || keyword.isEmpty()) return "搜索关键词不能为空";
        Path root = baseDir();
        StringBuilder result = new StringBuilder();
        try (Stream<Path> stream = Files.walk(root, 5)) {
            stream
                .filter(p -> p.getFileName().toString().toLowerCase().contains(keyword.toLowerCase()))
                .filter(p -> !p.toString().contains(".git") && !p.toString().contains("__pycache__"))
                .limit(20)
                .forEach(p -> {
                    String relative = root.relativize(p).toString().replace("\\", "/");
                    result.append(Files.isDirectory(p) ? "📁 " : "📄 ").append(relative).append("\n");
                });
        } catch (IOException e) {
            return "搜索失败: " + e.getMessage();
        }
        if (result.isEmpty()) return "未找到包含「" + keyword + "」的文件或目录";
        return result.toString().trim();
    }
}
