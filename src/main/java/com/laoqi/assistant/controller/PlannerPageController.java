package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.ConfigService;
import com.laoqi.assistant.service.ReminderService;
import com.laoqi.assistant.service.TaskService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller
public class PlannerPageController {

    private final ConfigService configService;
    private final TaskService taskService;
    private final ReminderService reminderService;

    public PlannerPageController(ConfigService configService, TaskService taskService,
                                  ReminderService reminderService) {
        this.configService = configService;
        this.taskService = taskService;
        this.reminderService = reminderService;
    }

    @GetMapping("/planner")
    public String plannerPage(Model model) {
        try {
            var tasks = taskService.getAllTasks();
            var reminders = reminderService.getAllReminders();

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

            model.addAttribute("tasks", tasks);
            model.addAttribute("reminders", displayList);
        } catch (Exception e) {
            model.addAttribute("tasks", List.of());
            model.addAttribute("reminders", List.of());
            model.addAttribute("error", e.getMessage());
        }
        return "planner";
    }
}
