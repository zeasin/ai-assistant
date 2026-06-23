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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                relevantNotes = noteIndexService.hybridSearch(kbId, userMessage, 10);
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
     * 格式化搜索结果为上下文（去重+筛选）
     */
    private String formatNotesContext(List<NoteSearchResult> notes) {
        if (notes == null || notes.isEmpty()) return "";

        // 按文件去重，只保留每个文件最高分的一条
        Map<String, NoteSearchResult> bestResults = new LinkedHashMap<>();
        for (NoteSearchResult note : notes) {
            String key = note.filePath();
            if (!bestResults.containsKey(key) || note.score() > bestResults.get(key).score()) {
                bestResults.put(key, note);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("== 相关笔记内容 ==\n\n");

        int count = 0;
        List<String> addedContents = new ArrayList<>();
        
        for (NoteSearchResult note : bestResults.values()) {
            if (count >= 5) break;  // 最多注入5条
            
            String content = note.content();
            if (content.length() > 800) {
                content = content.substring(0, 800);
            }
            
            // 内容去重：检查是否与已添加的内容高度相似
            boolean isDuplicate = false;
            for (String existing : addedContents) {
                if (isContentSimilar(content, existing)) {
                    log.info("[ContextBuilder] 跳过重复内容: {}", note.filePath());
                    isDuplicate = true;
                    break;
                }
            }
            
            if (isDuplicate) continue;
            
            addedContents.add(content);
            sb.append("【来源: ").append(note.filePath()).append("】\n");
            sb.append(content).append("\n\n");
            count++;
        }

        sb.append("请基于以上相关笔记内容回答，引用时标注来源。");

        return sb.toString();
    }

    /**
     * 检查两段内容是否高度相似（简单实现：基于关键词重叠率）
     */
    private boolean isContentSimilar(String content1, String content2) {
        if (content1 == null || content2 == null) return false;
        
        // 取前200字进行比较
        String c1 = content1.length() > 200 ? content1.substring(0, 200) : content1;
        String c2 = content2.length() > 200 ? content2.substring(0, 200) : content2;
        
        // 计算字符重叠率
        Set<Character> chars1 = new HashSet<>();
        for (char c : c1.toCharArray()) {
            if (Character.isLetterOrDigit(c)) chars1.add(c);
        }
        
        int overlap = 0;
        for (char c : c2.toCharArray()) {
            if (Character.isLetterOrDigit(c) && chars1.contains(c)) {
                overlap++;
            }
        }
        
        int total = Math.min(c1.length(), c2.length());
        double similarity = (double) overlap / total;
        
        return similarity > 0.7;  // 超过70%相似度认为重复
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
