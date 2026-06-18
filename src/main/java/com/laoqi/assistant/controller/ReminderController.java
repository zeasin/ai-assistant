package com.laoqi.assistant.controller;

import com.laoqi.assistant.model.ReminderData.Reminder;
import com.laoqi.assistant.service.LogService;
import com.laoqi.assistant.service.ReminderService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
public class ReminderController {

    private final ReminderService reminderService;
    private final LogService logService;

    public ReminderController(ReminderService reminderService, LogService logService) {
        this.reminderService = reminderService;
        this.logService = logService;
    }

    @GetMapping("/reminders")
    public String remindersPage(Model model) {
        try {
            List<Reminder> reminders = reminderService.getAllReminders();
            List<Map<String, Object>> displayList = reminders.stream()
                    .map(r -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("id", r.id);
                        m.put("name", r.name);
                        m.put("message", r.message);
                        m.put("type", r.type);
                        m.put("time", r.time);
                        m.put("date", r.date);
                        m.put("dayOfWeek", r.dayOfWeek);
                        m.put("dayOfMonth", r.dayOfMonth);
                        m.put("monthDay", r.monthDay);
                        m.put("enabled", r.enabled);
                        m.put("createdAt", r.createdAt);
                        m.put("lastTriggered", r.lastTriggered);
                        m.put("description", reminderService.getReminderDescription(r));
                        m.put("typeLabel", reminderService.getTypeLabel(r.type));
                        m.put("weekdayLabel", reminderService.getWeekdayLabel(r.dayOfWeek));
                        return m;
                    })
                    .toList();
            model.addAttribute("reminders", displayList);
        } catch (Exception e) {
            model.addAttribute("reminders", List.of());
            model.addAttribute("error", e.getMessage());
        }
        return "kb_reminders";
    }

    @GetMapping("/api/reminders")
    @ResponseBody
    public List<Map<String, Object>> getReminders() {
        return reminderService.getAllReminders().stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", r.id);
                    m.put("name", r.name);
                    m.put("message", r.message);
                    m.put("type", r.type);
                    m.put("time", r.time);
                    m.put("date", r.date);
                    m.put("dayOfWeek", r.dayOfWeek);
                    m.put("dayOfMonth", r.dayOfMonth);
                    m.put("monthDay", r.monthDay);
                    m.put("enabled", r.enabled);
                    m.put("createdAt", r.createdAt);
                    m.put("lastTriggered", r.lastTriggered);
                    m.put("description", reminderService.getReminderDescription(r));
                    return m;
                })
                .toList();
    }

    @PostMapping("/api/reminders/add")
    @ResponseBody
    public Map<String, Object> addReminder(
            @RequestParam String name,
            @RequestParam(required = false, defaultValue = "") String message,
            @RequestParam String type,
            @RequestParam(required = false, defaultValue = "09:00") String time,
            @RequestParam(required = false, defaultValue = "") String date,
            @RequestParam(required = false, defaultValue = "") String dayOfWeek,
            @RequestParam(required = false, defaultValue = "") String dayOfMonth,
            @RequestParam(required = false, defaultValue = "") String monthDay) {
        try {
            Reminder r = reminderService.addReminder(name, message, type, time,
                    date, dayOfWeek, dayOfMonth, monthDay);
            logService.add("提醒管理", "成功", "添加提醒: " + name);
            return Map.of("ok", true, "reminder", r);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/reminders/update")
    @ResponseBody
    public Map<String, Object> updateReminder(
            @RequestParam String id,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String message,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String time,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String dayOfWeek,
            @RequestParam(required = false) String dayOfMonth,
            @RequestParam(required = false) String monthDay,
            @RequestParam(required = false) Boolean enabled) {
        try {
            boolean ok = reminderService.updateReminder(id, name, message, type, time,
                    date, dayOfWeek, dayOfMonth, monthDay, enabled);
            if (!ok) {
                return Map.of("ok", false, "error", "提醒不存在");
            }
            logService.add("提醒管理", "成功", "更新提醒: " + name);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/reminders/delete")
    @ResponseBody
    public Map<String, Object> deleteReminder(@RequestParam String id) {
        try {
            boolean ok = reminderService.deleteReminder(id);
            if (ok) {
                logService.add("提醒管理", "成功", "删除提醒");
            }
            return Map.of("ok", ok);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/reminders/toggle")
    @ResponseBody
    public Map<String, Object> toggleReminder(@RequestParam String id) {
        try {
            boolean ok = reminderService.toggleReminder(id);
            if (ok) {
                logService.add("提醒管理", ok ? "启用" : "禁用", "切换提醒状态");
            }
            return Map.of("ok", ok);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/api/reminders/trigger")
    @ResponseBody
    public Map<String, Object> triggerReminder(@RequestParam String id) {
        try {
            List<Reminder> reminders = reminderService.getAllReminders();
            Reminder r = reminders.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
            if (r == null) {
                return Map.of("ok", false, "error", "提醒不存在");
            }
            reminderService.triggerReminder(r);
            logService.add("提醒管理", "成功", "手动触发: " + r.name);
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
