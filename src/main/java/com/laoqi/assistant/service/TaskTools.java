package com.laoqi.assistant.service;

import com.laoqi.assistant.model.TaskData.TaskItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务管理工具集 - 让 AI 可以通过对话管理待办任务。
 */
@Component
public class TaskTools {

    private static final Logger log = LoggerFactory.getLogger(TaskTools.class);

    private final TaskService taskService;

    public TaskTools(TaskService taskService) {
        this.taskService = taskService;
    }

    @Tool(description = "创建新任务。当用户说记个任务或帮我创建一个待办时使用此工具")
    public String createTask(
            @ToolParam(description = "任务标题") String title,
            @ToolParam(description = "任务详细描述，可选") String description,
            @ToolParam(description = "优先级: high/mid/low，默认 mid") String priority,
            @ToolParam(description = "截止日期: yyyy-MM-dd 格式，可选") String dueDate) {
        Long kbId = NoteTools.getCurrentKbId();
        String pri = (priority != null && !priority.isEmpty()) ? priority : "mid";
        TaskItem task = taskService.addTask(title, description, pri, dueDate, kbId);
        log.info("[TaskTools] 创建任务: {} (priority={})", task.id, pri);
        return "已创建任务: " + title + " (ID: " + task.id + ", 优先级: " + pri
                + (dueDate != null && !dueDate.isEmpty() ? ", 截止日期: " + dueDate : "") + ")";
    }

    @Tool(description = "查询待办任务列表，支持按状态筛选。当用户说我的任务或有哪些任务时使用此工具")
    public String listTasks(
            @ToolParam(description = "筛选状态: pending-进行中, done-已完成, all-所有，默认 pending") String status) {
        List<TaskItem> allTasks = taskService.getAllTasks();
        if (allTasks.isEmpty()) {
            return "暂无任务";
        }

        List<TaskItem> filtered;
        if (status == null || status.isEmpty() || "all".equals(status)) {
            filtered = allTasks;
        } else {
            filtered = allTasks.stream()
                    .filter(t -> status.equals(t.status))
                    .collect(Collectors.toList());
        }

        if (filtered.isEmpty()) {
            String label = "done".equals(status) ? "已完成" : "进行中";
            return "没有" + label + "的任务";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(filtered.size()).append(" 个任务：\n");
        for (int i = 0; i < filtered.size(); i++) {
            TaskItem t = filtered.get(i);
            String icon = "done".equals(t.status) ? "[x]" : "[ ]";
            sb.append(icon).append(" ").append(t.title).append("\n");
            sb.append("  ID: ").append(t.id).append(" | 状态: ").append(statusLabel(t.status));
            sb.append(" | 优先级: ").append(priorityLabel(t.priority));
            if (t.dueDate != null && !t.dueDate.isEmpty()) {
                sb.append(" | 截止: ").append(t.dueDate);
            }
            sb.append("\n");
            if (t.description != null && !t.description.isEmpty()) {
                sb.append("  描述: ").append(t.description).append("\n");
            }
        }
        return sb.toString();
    }

    @Tool(description = "更新任务状态或字段。当用户说完成任务或更新任务时使用此工具")
    public String updateTask(
            @ToolParam(description = "任务ID") String taskId,
            @ToolParam(description = "新标题，可选") String title,
            @ToolParam(description = "新描述，可选") String description,
            @ToolParam(description = "新状态: pending/done，可选") String status,
            @ToolParam(description = "新优先级: high/mid/low，可选") String priority,
            @ToolParam(description = "新截止日期: yyyy-MM-dd，可选") String dueDate) {
        Long kbId = NoteTools.getCurrentKbId();
        TaskItem updated = taskService.updateTask(taskId, title, description, status, priority, dueDate, kbId);
        if (updated == null) {
            return "未找到任务: " + taskId;
        }
        log.info("[TaskTools] 更新任务: {} -> status={}", taskId, status);
        return "已更新任务: " + updated.title + " (状态: " + statusLabel(updated.status)
                + ", 优先级: " + priorityLabel(updated.priority) + ")";
    }

    @Tool(description = "删除任务。当用户说删除任务或移除任务时使用此工具")
    public String deleteTask(
            @ToolParam(description = "任务ID") String taskId) {
        Long kbId = NoteTools.getCurrentKbId();
        taskService.deleteTask(taskId, kbId);
        log.info("[TaskTools] 删除任务: {}", taskId);
        return "已删除任务: " + taskId;
    }

    @Tool(description = "标记任务为已完成。当用户说完成任务或做完了时使用此工具")
    public String completeTask(
            @ToolParam(description = "任务ID") String taskId) {
        return updateTask(taskId, null, null, "done", null, null);
    }

    private String statusLabel(String s) {
        return "done".equals(s) ? "已完成" : "进行中";
    }

    private String priorityLabel(String p) {
        if (p == null) return "中";
        return switch (p) {
            case "high" -> "高";
            case "low" -> "低";
            default -> "中";
        };
    }
}
