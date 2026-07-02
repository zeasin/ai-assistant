package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.datacenter.DataSetService;
import com.laoqi.assistant.datacenter.model.DataSet;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
    /** 状态回调（静态引用，不用 ThreadLocal——Spring AI 可能在异步线程执行工具） */
    private static Consumer<String> STATUS_CALLBACK;

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConfigService configService;
    private final KnowledgeBaseService kbService;
    private final NoteIndexService noteIndexService;
    private final DataSetService dataSetService;
    private final IndexScannerService indexScannerService;

    public NoteTools(ConfigService configService, KnowledgeBaseService kbService,
                    NoteIndexService noteIndexService, DataSetService dataSetService,
                    IndexScannerService indexScannerService) {
        this.configService = configService;
        this.kbService = kbService;
        this.noteIndexService = noteIndexService;
        this.dataSetService = dataSetService;
        this.indexScannerService = indexScannerService;
    }

    /**
     * 文件写入后主动触发增量索引（异步）
     */
    private void notifyFileChanged(Long kbId, String relativePath) {
        if (kbId == null || !noteIndexService.isAvailable()) return;
        String notesDir = kbService.getNotesDirById(kbId);
        if (notesDir == null || notesDir.isBlank()) return;
        java.nio.file.Path file = java.nio.file.Paths.get(notesDir, relativePath);
        if (!java.nio.file.Files.isRegularFile(file)) return;

        try {
            boolean indexed = noteIndexService.indexFile(file, java.nio.file.Paths.get(notesDir), kbId);
            if (indexed) {
                log.info("[NoteTools] 写后自动索引完成: {}", relativePath);
            }
        } catch (Exception e) {
            log.warn("[NoteTools] 写后自动索引失败: {}", relativePath, e);
        }
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

    /** 设置状态回调，工具方法执行时会通过它上报进度 */
    public static void setStatusCallback(Consumer<String> callback) {
        STATUS_CALLBACK = callback;
    }

    public static void clearStatusCallback() {
        STATUS_CALLBACK = null;
    }

    private void reportStatus(String message) {
        Consumer<String> cb = STATUS_CALLBACK;
        if (cb != null) {
            cb.accept(message);
        } else {
            log.debug("[NoteTools] reportStatus 回调为 null（message={}）", message);
        }
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
        reportStatus("📂 正在浏览目录...");
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
        reportStatus("📖 正在读取文件...");
        if (path == null || path.isEmpty()) return "文件路径不能为空";
        Path file = baseDir().resolve(path).normalize();
        if (!file.startsWith(baseDir())) return "路径越界: " + path;
        if (!Files.isRegularFile(file)) return "文件不存在: " + path;

        // 大文件只读前 12000 字符，避免拖慢响应；小文件全量读取
        String content;
        int MAX_CHARS = 12_000;
        try {
            long fileSize = Files.size(file);
            if (fileSize > 100_000) {
                // 超大文件(>100KB)：用 BufferedReader 只读开头部分
                StringBuilder sb = new StringBuilder();
                try (var reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                    char[] buf = new char[4096];
                    int total = 0;
                    while (total < MAX_CHARS) {
                        int n = reader.read(buf, 0, Math.min(buf.length, MAX_CHARS - total));
                        if (n == -1) break;
                        sb.append(buf, 0, n);
                        total += n;
                    }
                }
                content = sb.toString() + "\n\n...（文件过大，仅显示前 " + MAX_CHARS + " 字符）";
            } else {
                content = FileUtil.readText(file);
                if (content.length() > MAX_CHARS) {
                    content = content.substring(0, MAX_CHARS) + "\n\n...（文件过长，仅显示前 " + MAX_CHARS + " 字符）";
                }
            }
        } catch (IOException e) {
            return "读取文件失败: " + e.getMessage();
        }

        reportStatus("📖 文件已读取，正在分析...");
        if (content.isEmpty()) return "（空文件）";
        return content;
    }

    @Tool(description = "读取指定笔记文件的完整内容，语义与 readFile 相同，用于读取笔记内容")
    public String readNote(@ToolParam(description = "文件路径，相对于笔记库根目录") String path) {
        reportStatus("📖 正在读取文件...");
        return readFile(path);
    }

    @Tool(description = "将Markdown内容写入笔记库文件。注意：这是写入笔记文件，不是操作数据集。如果要操作数据集请使用addRecord工具")
    public String writeFile(
            @ToolParam(description = "文件路径，相对于笔记库根目录") String path,
            @ToolParam(description = "要写入的完整文件内容") String content) {
        reportStatus("💾 正在写入文件...");
        if (path == null || path.isEmpty()) return "文件路径不能为空";
        Path file = baseDir().resolve(path).normalize();
        if (!file.startsWith(baseDir())) return "路径越界: " + path;

        FileUtil.writeText(file, content);
        log.info("[NoteTools] 写入文件: {} ({} 字符)", path, content.length());

        // 写完后主动触发增量索引
        notifyFileChanged(getCurrentKbId(), path);

        reportStatus("💾 文件写入完成");
        return "写入成功: " + path;
    }

    @Tool(description = "删除笔记库中的指定文件，path 是相对于笔记库根目录的路径。注意：这是删除笔记文件，不是操作数据集。如果要删除数据集记录请使用deleteRecord工具")
    public String deleteFile(@ToolParam(description = "文件路径，相对于笔记库根目录，例如 \"客户管理/data/xxx.json\"") String path) {
        reportStatus("🗑️ 正在删除文件...");
        if (path == null || path.isEmpty()) return "文件路径不能为空";
        Path file = baseDir().resolve(path).normalize();
        if (!file.startsWith(baseDir())) return "路径越界: " + path;
        if (!Files.isRegularFile(file)) return "文件不存在: " + path;

        try {
            Files.delete(file);
            log.info("[NoteTools] 删除文件: {}", path);
            reportStatus("🗑️ 文件已删除");
            return "删除成功: " + path;
        } catch (IOException e) {
            return "删除失败: " + e.getMessage();
        }
    }

    @Tool(description = "在笔记库中搜索文件名包含指定关键词的文件和目录。注意：这是搜索笔记文件，不是搜索数据集。如果要搜索数据集请使用searchRecords工具")
    public String searchFiles(@ToolParam(description = "搜索关键词，如 BUG、客户、日报") String keyword) {
        reportStatus("🔎 正在搜索文件...");
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

    @Tool(description = "搜索笔记库内容，基于语义理解查找相关笔记片段。当用户询问某个主题、人物、事件时使用此工具")
    public String searchNotes(
            @ToolParam(description = "搜索关键词或自然语言查询，如'张三的跟进记录'、'本周工作重点'") String query,
            @ToolParam(description = "返回结果数量，默认5，最多20") int limit) {
        reportStatus("🔍 正在搜索笔记...");

        Long kbId = getCurrentKbId();
        if (kbId == null) {
            reportStatus("🔍 搜索失败：未指定知识库");
            return "未指定知识库";
        }

        if (!noteIndexService.isAvailable()) {
            reportStatus("🔍 语义搜索不可用，改用文件名搜索");
            return "语义搜索不可用（Embedding 服务未配置），请使用 searchFiles 按文件名搜索";
        }

        try {
            List<NoteIndexService.NoteSearchResult> results = noteIndexService.hybridSearch(kbId, query, limit);
            reportStatus("🔍 搜索完成，共找到 " + results.size() + " 条结果");

            if (results.isEmpty()) {
                return "未找到与「" + query + "」相关的笔记内容。提示：可以先用 searchFiles 按文件名搜索，或检查索引是否已构建";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 条相关笔记：\n\n");

            for (int i = 0; i < results.size(); i++) {
                NoteIndexService.NoteSearchResult r = results.get(i);
                sb.append("📄 ").append(r.filePath());
                sb.append(" (相似度: ").append(String.format("%.2f", r.score())).append(")\n");

                String contentPreview = r.content();
                if (contentPreview.length() > 300) {
                    contentPreview = contentPreview.substring(0, 300) + "...";
                }
                sb.append("内容摘要：").append(contentPreview).append("\n\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("[NoteTools] 搜索笔记失败", e);
            return "搜索失败: " + e.getMessage();
        }
    }

    @Tool(description = "同时保存笔记文件并添加数据集记录。当用户汇报工作、记录客户沟通、反馈问题时使用此工具"
        + "（而不是分别调用 writeFile 和 addRecord），确保详细内容记录到笔记，关键结构信息记录到数据集")
    public String logRecord(
            @ToolParam(description = "笔记文件路径，相对于笔记库根目录，如 \"客户/张三-2026-06-28.md\"") String notePath,
            @ToolParam(description = "笔记详细内容（Markdown格式）") String noteContent,
            @ToolParam(description = "数据集ID或名称，如 \"客户跟进\"、\"Bug追踪\"") String dataset,
            @ToolParam(description = "JSON格式的结构化数据，如 {\"公司\":\"张三\",\"阶段\":\"报价\"}") String jsonData) {
        reportStatus("📝 正在保存笔记并更新数据集...");

        // 1. 写笔记文件
        if (notePath == null || notePath.isEmpty()) return "笔记文件路径不能为空";
        Path file = baseDir().resolve(notePath).normalize();
        if (!file.startsWith(baseDir())) return "路径越界: " + notePath;

        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            return "创建目录失败: " + e.getMessage();
        }

        FileUtil.writeText(file, noteContent);
        log.info("[NoteTools] logRecord 写入文件: {} ({} 字符)", notePath, noteContent.length());

        // 写完后主动触发增量索引
        notifyFileChanged(getCurrentKbId(), notePath);

        // 2. 写数据集
        String datasetResult;
        try {
            DataSet ds = findDataset(dataset);
            if (ds == null) {
                datasetResult = "⚠️ 未找到数据集「" + dataset + "」，仅保存了笔记文件";
                log.warn("[NoteTools] logRecord 数据集不存在: {}", dataset);
            } else {
                Map<String, Object> record = mapper.readValue(jsonData, new TypeReference<Map<String, Object>>() {});
                record.put("更新时间", java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                int count = dataSetService.addRecords(ds.getId(), List.of(record), "ai_log");
                datasetResult = count > 0
                        ? "✅ 已添加到数据集「" + ds.getName() + "」"
                        : "⚠️ 数据集「" + ds.getName() + "」记录已存在（重复），未添加";
            }
        } catch (Exception e) {
            datasetResult = "⚠️ 数据集写入失败: " + e.getMessage();
            log.warn("[NoteTools] logRecord 写入数据集失败", e);
        }

        reportStatus("✅ 完成");
        return "✅ 笔记已保存: " + notePath + "\n" + datasetResult;
    }

    private DataSet findDataset(String identifier) {
        DataSet ds = dataSetService.getDataset(identifier);
        if (ds != null) return ds;
        List<DataSet> all = dataSetService.getAllDatasets();
        for (DataSet d : all) {
            if (d.getName().equalsIgnoreCase(identifier) || d.getName().contains(identifier)) {
                return d;
            }
        }
        return null;
    }
}
