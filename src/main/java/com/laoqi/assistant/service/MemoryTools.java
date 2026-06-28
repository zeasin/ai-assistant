package com.laoqi.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Agent 记忆工具集 — 让 AI 可以读写关于用户的关键信息。
 * 用于记住用户偏好、项目事实、重要约定等。
 */
@Component
public class MemoryTools {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);

    private final MemoryManagerService memoryManager;

    public MemoryTools(MemoryManagerService memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Tool(description = "记住一条关于用户的关键信息。当用户透露了个人偏好、重要事实、约定时主动使用此工具存储。例如：记住用户的名字、喜欢的称呼、项目约定等")
    public String remember(
            @ToolParam(description = "分类: user_profile(用户画像)/preference(偏好)/project(项目)/fact(关键事实)/goal(目标)") String category,
            @ToolParam(description = "信息键名，如\"用户称呼\"、\"项目名称\"、\"常用语言\"") String key,
            @ToolParam(description = "信息值，如\"老齐\"、\"BiLing AI\"、\"Java\"") String value,
            @ToolParam(description = "重要程度: 1-5，越高越重要，默认 2") int importance) {
        Long kbId = NoteTools.getCurrentKbId();
        int imp = Math.max(1, Math.min(5, importance <= 0 ? 2 : importance));
        memoryManager.put(kbId, category, key, value, imp);
        log.info("[MemoryTools] 记住: [{}] {}={} (重要性={})", category, key, value, imp);
        return "✅ 已记住: " + key + " = " + value
                + "\n   分类: " + categoryLabel(category) + " | 重要性: " + imp;
    }

    @Tool(description = "回忆关于用户的已存储信息。当需要了解用户偏好、项目背景、之前约定时使用此工具")
    public String recall(
            @ToolParam(description = "搜索关键词，如\"称呼\"、\"项目\"、\"语言\"") String keyword) {
        Long kbId = NoteTools.getCurrentKbId();
        if (keyword == null || keyword.isEmpty()) {
            // 返回所有记忆
            String formatted = memoryManager.formatMemories(kbId);
            if (formatted.isEmpty()) {
                return "目前还没有关于你的记忆信息。你可以告诉我一些你的信息，我会记住。";
            }
            return formatted;
        }

        var results = memoryManager.search(kbId, keyword);
        if (results.isEmpty()) {
            return "未找到关于「" + keyword + "」的记忆。你可以告诉我相关信息，我会记住。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("关于「").append(keyword).append("」的记忆：\n\n");
        for (var entry : results) {
            sb.append("- ").append(entry.key()).append(": ").append(entry.value());
            if (entry.importance() >= 3) sb.append(" ⭐");
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "删除一条已记住的信息。当用户说\"忘记这个\"、\"删掉这个记忆\"时必须使用此工具")
    public String forget(
            @ToolParam(description = "要删除的信息键名") String key) {
        Long kbId = NoteTools.getCurrentKbId();
        memoryManager.delete(kbId, key);
        log.info("[MemoryTools] 忘记: {}", key);
        return "✅ 已忘记: " + key;
    }

    @Tool(description = "列出所有已记住的信息分类。当用户问\"你记得我什么\"时使用此工具")
    public String listMemories() {
        Long kbId = NoteTools.getCurrentKbId();
        String formatted = memoryManager.formatMemories(kbId);
        if (formatted.isEmpty()) {
            return "目前还没有关于你的记忆信息。你可以告诉我一些你的信息，我会记住。";
        }
        return formatted;
    }

    private String categoryLabel(String c) {
        return switch (c) {
            case "user_profile" -> "用户画像";
            case "preference" -> "偏好设置";
            case "project" -> "项目信息";
            case "fact" -> "关键事实";
            case "goal" -> "目标跟踪";
            default -> c;
        };
    }
}
