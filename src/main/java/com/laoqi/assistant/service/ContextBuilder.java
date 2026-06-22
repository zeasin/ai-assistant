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
        return build(sessionId, userMessage, kbId, "knowledge");
    }

    public ChatContext build(String sessionId, String userMessage, Long kbId, String mode) {
        // 1. 注入历史对话
        String historyContext = sessionService.buildHistoryContext(sessionId, mode, userMessage);

        // 2. 主动搜索相关笔记
        List<NoteSearchResult> relevantNotes = List.of();
        if (noteIndexService.isAvailable() && kbId != null) {
            try {
                relevantNotes = noteIndexService.hybridSearch(kbId, userMessage, 5);
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
                        if (agentsMd.length() > 3000) {
                            log.info("[ContextBuilder] AGENTS.md 超过 3000 字，截断注入");
                            agentsMd = agentsMd.substring(0, 3000) + "\n\n（内容过长，已截断）";
                        }
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
     * 将上下文合并为 system 消息和 user 消息两部分。
     * system 消息：先注入相关笔记，再以 AGENTS.md 规则收尾（LLM 对最近读到的内容记忆更强）。
     * user 消息：历史对话 + 用户最新消息。
     */
    public MergedContext merge(ChatContext context, String userMessage) {
        StringBuilder systemPart = new StringBuilder();
        StringBuilder userPart = new StringBuilder();

        // 相关笔记搜索结果 → system 消息（先注入，作为参考资料）
        if (context.relevantNotes() != null && !context.relevantNotes().isEmpty()) {
            systemPart.append(formatNotesContext(context.relevantNotes())).append("\n\n");
        }

        // 规则文件 → system 消息（放在 system 消息最后，LLM 对最近内容记忆更强，规则更受重视）
        if (context.agentsMd() != null && !context.agentsMd().isBlank()) {
            systemPart.append("== 规则文件 (AGENTS.md) ==\n");
            systemPart.append(context.agentsMd()).append("\n\n");
        }

        // 历史对话 → user 消息
        if (context.historyContext() != null && !context.historyContext().isBlank()) {
            userPart.append(context.historyContext()).append("\n\n");
        }

        // 用户最新消息 → user 消息
        userPart.append("---\n\n用户最新消息:\n").append(userMessage);

        return new MergedContext(systemPart.toString(), userPart.toString());
    }

    /**
     * 合并后的上下文
     */
    public record MergedContext(String systemContext, String userMessage) {}

    /**
     * 格式化搜索结果为上下文（去重+筛选）
     */
    private String formatNotesContext(List<NoteSearchResult> notes) {
        if (notes == null || notes.isEmpty()) return "";

        // 按文件去重，只保留每个文件最高分的一条；排除 AGENTS.md（已作为规则文件主动注入）
        Map<String, NoteSearchResult> bestResults = new LinkedHashMap<>();
        for (NoteSearchResult note : notes) {
            String key = note.filePath();
            if (key != null && key.toLowerCase().endsWith("agents.md")) continue;
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
                content = truncateToLastSentence(content, 800);
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
            sb.append("【来源: ").append(note.filePath()).append("】");
            if (note.title() != null && !note.title().isBlank()) {
                sb.append(" 标题: ").append(note.title());
            }
            sb.append("\n");
            sb.append(content).append("\n\n");
            count++;
        }

        sb.append("请基于以上相关笔记内容回答，引用时标注来源。");

        return sb.toString();
    }

    /**
     * 检查两段内容是否高度相似（基于前200字符的编辑距离）
     */
    private boolean isContentSimilar(String content1, String content2) {
        if (content1 == null || content2 == null) return false;
        
        // 取前200字进行比较
        String c1 = content1.length() > 200 ? content1.substring(0, 200) : content1;
        String c2 = content2.length() > 200 ? content2.substring(0, 200) : content2;

        if (c1.equals(c2)) return true;

        // 计算公共子串比例
        int commonLen = longestCommonSubstring(c1, c2);
        double ratio = (double) commonLen / Math.min(c1.length(), c2.length());
        return ratio > 0.6;
    }

    private int longestCommonSubstring(String a, String b) {
        int m = a.length(), n = b.length();
        int maxLen = 0;
        int[] dp = new int[n + 1];
        for (int i = 1; i <= m; i++) {
            int prev = 0;
            for (int j = 1; j <= n; j++) {
                int temp = dp[j];
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[j] = prev + 1;
                    maxLen = Math.max(maxLen, dp[j]);
                } else {
                    dp[j] = 0;
                }
                prev = temp;
            }
        }
        return maxLen;
    }

    /**
     * 截断到最后一个完整句子边界，避免在中间切断
     */
    private String truncateToLastSentence(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        String truncated = text.substring(0, maxLen);
        // 找最后一个句子分隔符：。！？! ? . \n
        int lastPeriod = Math.max(
                Math.max(truncated.lastIndexOf('。'), truncated.lastIndexOf('！')),
                Math.max(truncated.lastIndexOf('？'), truncated.lastIndexOf('.'))
        );
        int lastNewline = truncated.lastIndexOf('\n');
        int cutPoint = Math.max(lastPeriod, lastNewline);
        if (cutPoint > maxLen / 2) {
            return truncated.substring(0, cutPoint + 1);
        }
        return truncated;
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
