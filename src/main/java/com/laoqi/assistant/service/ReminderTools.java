package com.laoqi.assistant.service;

import com.laoqi.assistant.model.ReminderData.Reminder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 提醒管理工具集 - 让 AI 可以通过对话管理定时提醒。
 */
@Component
public class ReminderTools {

    private static final Logger log = LoggerFactory.getLogger(ReminderTools.class);

    private final ReminderService reminderService;

    public ReminderTools(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @Tool(description = "创建定时提醒。当用户说提醒我或设置提醒时使用此工具。type 可选 daily(每天)/once(一次)/weekly(每周)/monthly(每月)")
    public String createReminder(
            @ToolParam(description = "提醒名称") String name,
            @ToolParam(description = "提醒消息内容") String message,
            @ToolParam(description = "提醒类型: daily/once/weekly/monthly，默认 daily") String type,
            @ToolParam(description = "触发时间: HH:mm 格式（24小时），如 18:00") String time,
            @ToolParam(description = "一次性提醒的日期: yyyy-MM-dd（仅 type=once 时需要）") String date,
            @ToolParam(description = "每周提醒的星期几: 1-7（1=周一，仅 type=weekly 时需要）") String dayOfWeek,
            @ToolParam(description = "每月提醒的日期: 1-31（仅 type=monthly 时需要）") String dayOfMonth) {
        Long kbId = NoteTools.getCurrentKbId();
        String safeType = (type != null && !type.isEmpty()) ? type : "daily";
        String safeTime = (time != null && !time.isEmpty()) ? time : "09:00";
        reminderService.addReminder(name, message, safeType, safeTime, date, dayOfWeek, dayOfMonth, null, kbId);
        log.info("[ReminderTools] 创建提醒: {} (type={}, time={})", name, safeType, safeTime);
        return "已创建提醒: " + name + " (类型: " + reminderService.getTypeLabel(safeType)
                + ", 时间: " + safeTime + ")";
    }

    @Tool(description = "列出所有提醒。当用户说我的提醒或有哪些提醒时使用此工具")
    public String listReminders(
            @ToolParam(description = "筛选: all-所有, enabled-启用的, disabled-禁用的，默认 all") String filter) {
        List<Reminder> all = reminderService.getAllReminders();
        if (all.isEmpty()) {
            return "暂无提醒";
        }

        List<Reminder> filtered;
        if (filter == null || filter.isEmpty() || "all".equals(filter)) {
            filtered = all;
        } else if ("enabled".equals(filter)) {
            filtered = all.stream().filter(r -> r.enabled).collect(Collectors.toList());
        } else {
            filtered = all.stream().filter(r -> !r.enabled).collect(Collectors.toList());
        }

        if (filtered.isEmpty()) {
            return "没有匹配的提醒";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(filtered.size()).append(" 个提醒：\n");
        for (int i = 0; i < filtered.size(); i++) {
            Reminder r = filtered.get(i);
            String icon = r.enabled ? "[ON]" : "[OFF]";
            sb.append(icon).append(" ").append(r.name).append("\n");
            sb.append("  ID: ").append(r.id);
            sb.append(" | ").append(reminderService.getReminderDescription(r));
            if (r.message != null && !r.message.isEmpty()) {
                String msgPreview = r.message.length() > 60 ? r.message.substring(0, 60) + "..." : r.message;
                sb.append("\n  消息: ").append(msgPreview);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "启用或禁用提醒。当用户说打开提醒或关闭提醒时使用此工具")
    public String toggleReminder(
            @ToolParam(description = "提醒ID") String reminderId) {
        Long kbId = NoteTools.getCurrentKbId();
        boolean success = reminderService.toggleReminder(reminderId, kbId);
        if (!success) {
            return "未找到提醒: " + reminderId;
        }
        return "已切换提醒状态: " + reminderId;
    }

    @Tool(description = "删除提醒。当用户说删除提醒或移除提醒时使用此工具")
    public String deleteReminder(
            @ToolParam(description = "提醒ID") String reminderId) {
        Long kbId = NoteTools.getCurrentKbId();
        reminderService.deleteReminder(reminderId, kbId);
        return "已删除提醒: " + reminderId;
    }

    @Tool(description = "更新提醒的设置。当用户说修改提醒时使用此工具。只传需要修改的字段")
    public String updateReminder(
            @ToolParam(description = "提醒ID") String reminderId,
            @ToolParam(description = "新名称，可选") String name,
            @ToolParam(description = "新消息内容，可选") String message,
            @ToolParam(description = "新类型: daily/once/weekly/monthly，可选") String type,
            @ToolParam(description = "新触发时间: HH:mm，可选") String time,
            @ToolParam(description = "新日期: yyyy-MM-dd，可选") String date,
            @ToolParam(description = "新星期: 1-7，可选") String dayOfWeek,
            @ToolParam(description = "新每月日期: 1-31，可选") String dayOfMonth,
            @ToolParam(description = "是否启用: true/false，可选") Boolean enabled) {
        Long kbId = NoteTools.getCurrentKbId();
        boolean success = reminderService.updateReminder(reminderId, name, message, type, time, date, dayOfWeek, dayOfMonth, null, enabled, kbId);
        if (!success) {
            return "未找到提醒: " + reminderId;
        }
        return "已更新提醒: " + reminderId;
    }
}
