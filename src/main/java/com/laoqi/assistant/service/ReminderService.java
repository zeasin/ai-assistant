package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ReminderData;
import com.laoqi.assistant.model.ReminderData.Reminder;
import com.laoqi.assistant.model.ReminderData.Root;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final ZoneId TZ = ZoneId.of("Asia/Shanghai");

    private final AppConfig appConfig;
    private final FeishuService feishuService;

    public ReminderService(AppConfig appConfig, FeishuService feishuService) {
        this.appConfig = appConfig;
        this.feishuService = feishuService;
    }

    private Path getRemindersFile() {
        return appConfig.getConfigFile().getParent().resolve("reminders.json");
    }

    public Root load() {
        return FileUtil.readJson(getRemindersFile(), Root.class, new Root());
    }

    private void save(Root root) {
        if (root.reminders == null) root.reminders = new ArrayList<>();
        if (root.meta == null) root.meta = new LinkedHashMap<>();
        FileUtil.writeJson(getRemindersFile(), root);
    }

    public List<Reminder> getAllReminders() {
        Root root = load();
        if (root.reminders == null) root.reminders = new ArrayList<>();
        return root.reminders;
    }

    public List<Reminder> getEnabledReminders() {
        return getAllReminders().stream()
                .filter(r -> r.enabled)
                .collect(Collectors.toList());
    }

    public Reminder addReminder(String name, String message, String type, String time,
                                 String dayOfWeek, String dayOfMonth, String monthDay) {
        Root root = load();
        if (root.reminders == null) root.reminders = new ArrayList<>();
        if (root.meta == null) root.meta = new LinkedHashMap<>();

        Reminder r = new Reminder();
        r.id = "R" + System.currentTimeMillis();
        r.name = name;
        r.message = message;
        r.type = type;
        r.time = time;
        r.dayOfWeek = dayOfWeek;
        r.dayOfMonth = dayOfMonth;
        r.monthDay = monthDay;
        r.enabled = true;
        r.createdAt = LocalDateTime.now(TZ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        root.reminders.add(r);
        root.meta.put("lastUpdated", LocalDate.now(TZ).toString());
        save(root);

        log.info("[提醒] 新增提醒: {} ({})", name, type);
        return r;
    }

    public Reminder updateReminder(String id, String name, String message, String type,
                                    String time, String dayOfWeek, String dayOfMonth,
                                    String monthDay, Boolean enabled) {
        Root root = load();
        if (root.reminders == null) return null;

        for (Reminder r : root.reminders) {
            if (r.id.equals(id)) {
                if (name != null) r.name = name;
                if (message != null) r.message = message;
                if (type != null) r.type = type;
                if (time != null) r.time = time;
                if (dayOfWeek != null) r.dayOfWeek = dayOfWeek;
                if (dayOfMonth != null) r.dayOfMonth = dayOfMonth;
                if (monthDay != null) r.monthDay = monthDay;
                if (enabled != null) r.enabled = enabled;
                root.meta.put("lastUpdated", LocalDate.now(TZ).toString());
                save(root);
                log.info("[提醒] 更新提醒: {} ({})", r.name, type);
                return r;
            }
        }
        return null;
    }

    public boolean deleteReminder(String id) {
        Root root = load();
        if (root.reminders == null) return false;

        boolean removed = root.reminders.removeIf(r -> r.id.equals(id));
        if (removed) {
            root.meta.put("lastUpdated", LocalDate.now(TZ).toString());
            save(root);
            log.info("[提醒] 删除提醒: {}", id);
        }
        return removed;
    }

    public boolean toggleReminder(String id) {
        Root root = load();
        if (root.reminders == null) return false;

        for (Reminder r : root.reminders) {
            if (r.id.equals(id)) {
                r.enabled = !r.enabled;
                root.meta.put("lastUpdated", LocalDate.now(TZ).toString());
                save(root);
                log.info("[提醒] {}提醒: {}", r.enabled ? "启用" : "禁用", r.name);
                return true;
            }
        }
        return false;
    }

    public void triggerReminder(Reminder r) {
        if (r == null || !r.enabled) return;

        try {
            List<List<Map<String, String>>> content = List.of(
                    List.of(Map.of("tag", "text", "text", "🔔 " + r.name)),
                    List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")),
                    List.of(Map.of("tag", "text", "text", r.message != null ? r.message : "该提醒了！"))
            );
            feishuService.sendPost("🔔 " + r.name, content);

            r.lastTriggered = LocalDateTime.now(TZ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            Root root = load();
            save(root);

            log.info("[提醒] 触发提醒: {}", r.name);
        } catch (Exception e) {
            log.error("[提醒] 触发失败: {} - {}", r.name, e.getMessage());
        }
    }

    public List<Reminder> getDueReminders() {
        List<Reminder> due = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(TZ);
        LocalTime currentTime = now.toLocalTime();
        String currentWeekday = String.valueOf(now.getDayOfWeek().getValue());
        String currentMonth = String.valueOf(now.getMonthValue());
        String currentDayOfMonth = String.valueOf(now.getDayOfMonth());

        log.info("[提醒] 检查提醒: 当前时间={}, 周几={}, 日期={}", now.toLocalTime(), currentWeekday, now.toLocalDate());

        for (Reminder r : getEnabledReminders()) {
            log.info("[提醒] 检查提醒: {}, 时间={}, 类型={}, 启用={}", r.name, r.time, r.type, r.enabled);
            
            if (!shouldTriggerNow(r, now, currentTime, currentWeekday, currentMonth, currentDayOfMonth)) {
                log.info("[提醒] 时间不匹配，跳过: {}", r.name);
                continue;
            }
            if (wasTriggeredToday(r, now)) {
                log.info("[提醒] 今日已触发，跳过: {}", r.name);
                continue;
            }
            log.info("[提醒] 准备触发: {}", r.name);
            due.add(r);
        }
        return due;
    }

    private boolean shouldTriggerNow(Reminder r, ZonedDateTime now, LocalTime currentTime,
                                      String currentWeekday, String currentMonth, String currentDayOfMonth) {
        String[] parts = r.time.split(":");
        if (parts.length != 2) return false;

        int triggerHour = Integer.parseInt(parts[0]);
        int triggerMinute = Integer.parseInt(parts[1]);
        int currentHour = currentTime.getHour();
        int currentMinute = currentTime.getMinute();

        // 只比较小时和分钟，忽略秒，确保整分钟内都能触发
        if (triggerHour != currentHour || triggerMinute != currentMinute) {
            log.info("[提醒] 时间不匹配: 期望{}:{} vs 当前{}:{}", triggerHour, triggerMinute, currentHour, currentMinute);
            return false;
        }

        return switch (r.type) {
            case "daily" -> true;
            case "weekly" -> r.dayOfWeek != null && r.dayOfWeek.equals(currentWeekday);
            case "monthly" -> r.dayOfMonth != null && r.dayOfMonth.equals(currentDayOfMonth);
            case "yearly" -> {
                if (r.monthDay == null) yield false;
                String[] md = r.monthDay.split("-");
                yield md.length == 2 &&
                        md[0].equals(currentMonth) &&
                        md[1].equals(currentDayOfMonth);
            }
            default -> false;
        };
    }

    private boolean wasTriggeredToday(Reminder r, ZonedDateTime now) {
        if (r.lastTriggered == null) return false;
        return r.lastTriggered.startsWith(now.toLocalDate().toString());
    }

    public String getTypeLabel(String type) {
        return switch (type) {
            case "daily" -> "每天";
            case "weekly" -> "每周";
            case "monthly" -> "每月";
            case "yearly" -> "每年";
            default -> type;
        };
    }

    public String getWeekdayLabel(String dayOfWeek) {
        if (dayOfWeek == null) return "";
        return switch (dayOfWeek) {
            case "1" -> "周一";
            case "2" -> "周二";
            case "3" -> "周三";
            case "4" -> "周四";
            case "5" -> "周五";
            case "6" -> "周六";
            case "7" -> "周日";
            default -> dayOfWeek;
        };
    }

    public String getReminderDescription(Reminder r) {
        StringBuilder sb = new StringBuilder();
        sb.append(getTypeLabel(r.type));
        if (r.time != null) {
            sb.append(" ").append(r.time);
        }
        if ("weekly".equals(r.type) && r.dayOfWeek != null) {
            sb.append(" (").append(getWeekdayLabel(r.dayOfWeek)).append(")");
        }
        if ("monthly".equals(r.type) && r.dayOfMonth != null) {
            sb.append(" (").append(r.dayOfMonth).append("号)");
        }
        if ("yearly".equals(r.type) && r.monthDay != null) {
            sb.append(" (").append(r.monthDay.replace("-", "月")).append("日)");
        }
        return sb.toString();
    }
}
