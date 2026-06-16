package com.laoqi.assistant.service;

import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 笔记库工具集，供 NoteAssistantService 编排使用。
 * 三个工具的方法签名与 OpenAI function calling 协议对应。
 */
@Component
public class NoteTools {

    private static final Logger log = LoggerFactory.getLogger(NoteTools.class);

    private final ConfigService configService;

    public NoteTools(ConfigService configService) {
        this.configService = configService;
    }

    private Path baseDir() {
        return Path.of(configService.getBaseDir());
    }

    public String listDir(String path) {
        Path dir = baseDir().resolve(path != null ? path : "");
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

    public String readFile(String path) {
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

    public String writeFile(String path, String content) {
        if (path == null || path.isEmpty()) return "文件路径不能为空";
        Path file = baseDir().resolve(path).normalize();
        if (!file.startsWith(baseDir())) return "路径越界: " + path;

        FileUtil.writeText(file, content);
        log.info("[NoteTools] 写入文件: {} ({} 字符)", path, content.length());
        return "写入成功: " + path;
    }

    /**
     * 在笔记库中搜索文件名包含指定关键词的文件和目录
     */
    public String searchFiles(String keyword) {
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
