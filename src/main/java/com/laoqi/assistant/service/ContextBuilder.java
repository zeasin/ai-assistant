package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.entity.MessageEntity;
import com.laoqi.assistant.service.NoteIndexService.NoteSearchResult;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final SessionService sessionService;
    private final NoteIndexService noteIndexService;
    private final KnowledgeBaseService kbService;

    public ContextBuilder(SessionService sessionService,
                         NoteIndexService noteIndexService,
                         KnowledgeBaseService kbService) {
        this.sessionService = sessionService;
        this.noteIndexService = noteIndexService;
        this.kbService = kbService;
    }

    /**
     * 构建完整上下文
     * 1. 主动搜索相关笔记
     * 2. 注入历史对话
     * 3. 读取规则文件
     */
    public ChatContext build(String sessionId, String userMessage, Long kbId) {
        // 1. 注入历史对话
        String historyContext = sessionService.buildHistoryContext(sessionId, "knowledge", userMessage);

        // 2. 主动搜索相关笔记
        List<NoteSearchResult> relevantNotes = List.of();
        if (noteIndexService.isAvailable() && kbId != null) {
            try {
                relevantNotes = noteIndexService.search(kbId, userMessage, 5);
                log.info("[ContextBuilder] 主动搜索完成，找到 {} 条相关笔记", relevantNotes.size());
            } catch (Exception e) {
                log.warn("[ContextBuilder] 搜索笔记失败: {}", e.getMessage());
            }
        }

        // 3. 读取规则文件
        String agentsMd = "";
        if (kbId != null) {
            try {
                String notesDir = kbService.getNotesDirById(kbId);
                if (notesDir != null && !notesDir.isBlank()) {
                    Path agentsFile = Paths.get(notesDir, "AGENTS.md");
                    if (agentsFile.toFile().exists()) {
                        agentsMd = FileUtil.readText(agentsFile);
                    }
                }
            } catch (Exception e) {
                log.warn("[ContextBuilder] 读取 AGENTS.md 失败: {}", e.getMessage());
            }
        }

        // 4. 组合上下文
        return new ChatContext(historyContext, relevantNotes, agentsMd);
    }

    /**
     * 将上下文合并为完整的消息
     */
    public String merge(ChatContext context, String userMessage) {
        StringBuilder sb = new StringBuilder();

        // 规则文件
        if (context.agentsMd() != null && !context.agentsMd().isBlank()) {
            sb.append("== 规则文件 (AGENTS.md) ==\n");
            sb.append(context.agentsMd()).append("\n\n");
        }

        // 相关笔记搜索结果
        if (context.relevantNotes() != null && !context.relevantNotes().isEmpty()) {
            sb.append(formatNotesContext(context.relevantNotes())).append("\n\n");
        }

        // 历史对话
        if (context.historyContext() != null && !context.historyContext().isBlank()) {
            sb.append(context.historyContext()).append("\n\n");
        }

        // 用户最新消息
        sb.append("---\n\n用户最新消息:\n").append(userMessage);

        return sb.toString();
    }

    /**
     * 格式化搜索结果为上下文
     */
    private String formatNotesContext(List<NoteSearchResult> notes) {
        if (notes == null || notes.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("== 相关笔记内容 ==\n\n");

        for (NoteSearchResult note : notes) {
            sb.append("【来源: ").append(note.filePath()).append("】\n");
            sb.append(note.content()).append("\n\n");
        }

        sb.append("请基于以上相关笔记内容，结合你的知识，回复用户的问题。\n");
        sb.append("引用笔记时请标注来源路径。");

        return sb.toString();
    }

    /**
     * 上下文数据模型
     */
    public record ChatContext(
            String historyContext,
            List<NoteSearchResult> relevantNotes,
            String agentsMd
    ) {}
}
