package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务规划引擎 — 将复杂用户请求拆解为多步执行计划。
 * 规划结果注入到 AI 上下文中，指导工具调用的顺序。
 */
@Service
public class TaskPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlannerService.class);

    private final LlmService llmService;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskPlannerService(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * 判断用户请求是否需要多步规划
     */
    public boolean needsPlanning(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) return false;
        String msg = userMessage.toLowerCase();

        // 触发规划的典型场景
        String[] complexTriggers = {"分析", "总结", "报告", "汇总", "对比", "统计",
                "梳理", "整理", "计划", "规划", "评估", "调研", "研究",
                "生成日报", "生成周报", "生成月报", "工作汇报"};

        for (String t : complexTriggers) {
            if (msg.contains(t)) return true;
        }

        // 超过 40 个字的请求也可能是复杂任务
        return userMessage.length() > 40;
    }

    /**
     * 生成执行计划 — 用 LLM 分析用户请求，输出结构化步骤
     * @return 格式化的计划文本，注入到 prompt 中
     */
    public String generatePlan(String sessionId, String userMessage, Long kbId) {
        try {
            String planPrompt = """
                你是一个任务规划专家。请分析以下用户请求，将其拆解为可执行的多步计划。

                用户请求: %s

                要求：
                1. 拆解为 2-6 个步骤
                2. 每个步骤包含：顺序号 + 动作描述 + 使用的工具
                3. 按依赖关系排序
                4. 关键：依赖前一步结果的步骤要后执行
                5. 如果请求很简单（一句话查询），回复"无需规划"

                可用工具包括：searchNotes(语义搜索), searchFiles(文件名搜索), readFile(读取文件),
                listDir(列出目录), writeFile(写入文件), logRecord(笔记+数据同时记录),
                webSearch(互联网搜索), listDatasets(数据集列表), queryRecords(查询数据),
                runPython(执行代码)

                输出格式（Markdown）：
                ## 执行计划
                Step 1: [工具名] 做什么
                Step 2: [工具名] 做什么
                ...
                """.formatted(userMessage);

            String plan = llmService.chat("你是一个严谨的规划专家，输出简洁的步骤列表。", planPrompt);

            if (plan == null || plan.isBlank() || plan.contains("无需规划")) {
                return null;
            }

            log.info("[Planner] 生成计划: session={}, steps=\n{}", sessionId, plan);
            return plan;

        } catch (Exception e) {
            log.warn("[Planner] 生成计划失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成执行计划并格式化为上下文注入块
     */
    public String buildPlanContext(String sessionId, String userMessage, Long kbId) {
        String plan = generatePlan(sessionId, userMessage, kbId);
        if (plan == null) return "";

        return """
                == 执行计划 ==
                我已为你制定了以下执行计划，请按步骤执行：

                %s

                执行完所有步骤后，给出最终回复。
                注意：如果某步执行结果为空或出错，尝试用其他工具补充。
                ---
                """.formatted(plan);
    }
}
