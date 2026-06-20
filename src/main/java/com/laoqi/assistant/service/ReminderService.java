package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ReminderData;
import com.laoqi.assistant.model.ReminderData.Reminder;
import com.laoqi.assistant.model.ReminderData.Root;
import com.laoqi.assistant.util.FileUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
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
    private final ConfigService configService;
    private final DataSource dataSource;

    public ReminderService(AppConfig appConfig, FeishuService feishuService,
                           ConfigService configService, DataSource dataSource) {
        this.appConfig = appConfig;
        this.feishuService = feishuService;
        this.configService = configService;
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        migrateFromJson();
        ensureDefaultReminder();
    }

    private void migrateFromJson() {
        try {
            Path globalFile = getGlobalRemindersFile();
            if (!Files.exists(globalFile)) {
                // 尝试从笔记库迁移
                migrateFromNoteLibrary();
            }

            if (!Files.exists(globalFile)) return;

            List<Reminder> existing = getAllRemindersFromDb();
            if (!existing.isEmpty()) {
                log.info("[提醒迁移] SQLite 中已有提醒，跳过迁移");
                return;
            }

            Root root = FileUtil.readJson(globalFile, Root.class, new Root());
            if (root.reminders == null || root.reminders.isEmpty()) return;

            int count = 0;
            for (Reminder r : root.reminders) {
                insertReminderToDb(r, null);
                count++;
            }
            log.info("[提醒迁移] 从 JSON 迁移 {} 条提醒到 SQLite", count);
        } catch (Exception e) {
            log.warn("[提醒迁移] 迁移失败: {}", e.getMessage());
        }
    }

    private void migrateFromNoteLibrary() {
        try {
            String notesDir = configService.getNotesDirIfExists();
            if (notesDir == null || notesDir.isEmpty()) return;

            Path oldFile = Paths.get(notesDir, "AI", "提醒", "reminders.json");
            if (Files.exists(oldFile)) {
                Root oldRoot = FileUtil.readJson(oldFile, Root.class, null);
                if (oldRoot != null && oldRoot.reminders != null && !oldRoot.reminders.isEmpty()) {
                    FileUtil.writeJson(getGlobalRemindersFile(), oldRoot);
                    log.info("[提醒迁移] 从笔记库迁移 {} 条提醒", oldRoot.reminders.size());
                }
            }
        } catch (Exception e) {
            log.warn("[提醒迁移] 从笔记库迁移失败: {}", e.getMessage());
        }
    }

    private void ensureDefaultReminder() {
        try {
            List<Reminder> existing = getAllRemindersFromDb();
            boolean hasDailyReport = existing.stream()
                    .anyMatch(r -> "daily-report-reminder".equals(r.id));
            if (!hasDailyReport) {
                Reminder r = new Reminder();
                r.id = "daily-report-reminder";
                r.name = "下班日报提醒";
                r.message = "到6点了老齐，写一下今天的工作记录！\n写完后我来帮你更新记忆文件，明天综合日报就会包含这些内容。\n内容包括：\n- 今天做了什么事\n- 客户沟通情况\n- 开发/文章进展\n- 明天计划";
                r.type = "daily";
                r.time = "18:00";
                r.enabled = true;
                r.createdAt = LocalDateTime.now(TZ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                insertReminderToDb(r, null);
                log.info("[提醒] 已创建默认下班日报提醒");
            }
        } catch (Exception e) {
            log.warn("[提醒] 初始化默认提醒失败: {}", e.getMessage());
        }
    }

    private Path getGlobalRemindersFile() {
        Path dir = appConfig.getConfigDirPath().resolve("AI").resolve("reminders");
        if (!Files.exists(dir)) {
            try { Files.createDirectories(dir); } catch (Exception e) {
                log.warn("[提醒] 创建全局提醒目录失败: {}", dir);
            }
        }
        return dir.resolve("reminders_data.json");
    }

    // ========== SQLite CRUD ==========

    private List<Reminder> getAllRemindersFromDb() {
        List<Reminder> reminders = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM reminders ORDER BY created_at DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                reminders.add(mapRowToReminder(rs));
            }
        } catch (SQLException e) {
            log.error("[提醒] 查询失败", e);
        }
        return reminders;
    }

    private void insertReminderToDb(Reminder r, Long kbId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO reminders (id, name, message, type, time, date, day_of_week, day_of_month, month_day, enabled, created_at, last_triggered, kb_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, r.id);
            ps.setString(2, r.name);
            ps.setString(3, r.message);
            ps.setString(4, r.type);
            ps.setString(5, r.time);
            ps.setString(6, r.date);
            ps.setString(7, r.dayOfWeek);
            ps.setString(8, r.dayOfMonth);
            ps.setString(9, r.monthDay);
            ps.setInt(10, r.enabled ? 1 : 0);
            ps.setString(11, r.createdAt);
            ps.setString(12, r.lastTriggered);
            if (kbId != null) {
                ps.setLong(13, kbId);
            } else {
                ps.setNull(13, Types.INTEGER);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[提醒] 插入失败", e);
        }
    }

    private Reminder mapRowToReminder(ResultSet rs) throws SQLException {
        Reminder r = new Reminder();
        r.id = rs.getString("id");
        r.name = rs.getString("name");
        r.message = rs.getString("message");
        r.type = rs.getString("type");
        r.time = rs.getString("time");
        r.date = rs.getString("date");
        r.dayOfWeek = rs.getString("day_of_week");
        r.dayOfMonth = rs.getString("day_of_month");
        r.monthDay = rs.getString("month_day");
        r.enabled = rs.getInt("enabled") == 1;
        r.createdAt = rs.getString("created_at");
        r.lastTriggered = rs.getString("last_triggered");
        return r;
    }

    private Reminder getReminderFromDb(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM reminders WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapRowToReminder(rs);
            }
        } catch (SQLException e) {
            log.error("[提醒] 查询失败", e);
        }
        return null;
    }

    private void updateReminderInDb(Reminder r) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE reminders SET name=?, message=?, type=?, time=?, date=?, day_of_week=?, day_of_month=?, month_day=?, enabled=?, last_triggered=? WHERE id=?")) {
            ps.setString(1, r.name);
            ps.setString(2, r.message);
            ps.setString(3, r.type);
            ps.setString(4, r.time);
            ps.setString(5, r.date);
            ps.setString(6, r.dayOfWeek);
            ps.setString(7, r.dayOfMonth);
            ps.setString(8, r.monthDay);
            ps.setInt(9, r.enabled ? 1 : 0);
            ps.setString(10, r.lastTriggered);
            ps.setString(11, r.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[提醒] 更新失败", e);
        }
    }

    private void deleteReminderFromDb(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM reminders WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[提醒] 删除失败", e);
        }
    }

    // ========== Public API ==========

    public List<Reminder> getAllReminders() {
        return getAllRemindersFromDb();
    }

    public List<Reminder> getAllReminders(String notesDir) {
        return getAllRemindersFromDb();
    }

    public List<Reminder> getEnabledReminders() {
        return getAllRemindersFromDb().stream()
                .filter(r -> r.enabled)
                .collect(Collectors.toList());
    }

    public Reminder addReminder(String name, String message, String type, String time,
                                 String date, String dayOfWeek, String dayOfMonth, String monthDay) {
        return addReminderInternal(name, message, type, time, date, dayOfWeek, dayOfMonth, monthDay, null);
    }

    public Reminder addReminder(String notesDir, String name, String message, String type, String time,
                                 String date, String dayOfWeek, String dayOfMonth, String monthDay) {
        return addReminderInternal(name, message, type, time, date, dayOfWeek, dayOfMonth, monthDay, null);
    }

    public Reminder addReminder(String name, String message, String type, String time,
                                 String date, String dayOfWeek, String dayOfMonth, String monthDay, Long kbId) {
        return addReminderInternal(name, message, type, time, date, dayOfWeek, dayOfMonth, monthDay, kbId);
    }

    private Reminder addReminderInternal(String name, String message, String type, String time,
                                 String date, String dayOfWeek, String dayOfMonth, String monthDay, Long kbId) {
        Reminder r = new Reminder();
        r.id = "R" + System.currentTimeMillis();
        r.name = name;
        r.message = message;
        r.type = type;
        r.time = normalizeTime(time);

        if ("once".equals(type)) {
            r.date = date;
        } else if ("weekly".equals(type)) {
            r.dayOfWeek = dayOfWeek;
        } else if ("monthly".equals(type)) {
            r.dayOfMonth = dayOfMonth;
        } else if ("yearly".equals(type)) {
            r.monthDay = monthDay;
        }
        r.enabled = true;
        r.createdAt = LocalDateTime.now(TZ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        insertReminderToDb(r, kbId);

        if (kbId != null) {
            syncToNoteLibrary(kbId);
        }

        log.info("[提醒] 新增提醒: {} ({})", name, type);
        return r;
    }

    public boolean updateReminder(String id, String name, String message, String type,
                                    String time, String date, String dayOfWeek, String dayOfMonth,
                                    String monthDay, Boolean enabled) {
        return updateReminderInternal(id, name, message, type, time, date, dayOfWeek, dayOfMonth, monthDay, enabled, null);
    }

    public boolean updateReminder(String notesDir, String id, String name, String message, String type,
                                    String time, String date, String dayOfWeek, String dayOfMonth,
                                    String monthDay, Boolean enabled) {
        return updateReminderInternal(id, name, message, type, time, date, dayOfWeek, dayOfMonth, monthDay, enabled, null);
    }

    public boolean updateReminder(String id, String name, String message, String type,
                                    String time, String date, String dayOfWeek, String dayOfMonth,
                                    String monthDay, Boolean enabled, Long kbId) {
        return updateReminderInternal(id, name, message, type, time, date, dayOfWeek, dayOfMonth, monthDay, enabled, kbId);
    }

    private boolean updateReminderInternal(String id, String name, String message, String type,
                                    String time, String date, String dayOfWeek, String dayOfMonth,
                                    String monthDay, Boolean enabled, Long kbId) {
        Reminder r = getReminderFromDb(id);
        if (r == null) return false;

        if (name != null) r.name = name;
        if (message != null) r.message = message;
        if (type != null) r.type = type;
        if (time != null) {
            r.time = normalizeTime(time);
            r.lastTriggered = null;
        }

        String reminderType = (type != null) ? type : r.type;
        if ("once".equals(reminderType)) {
            if (date != null) {
                r.date = date;
                r.lastTriggered = null;
            }
            r.dayOfWeek = null;
            r.dayOfMonth = null;
            r.monthDay = null;
        } else if ("weekly".equals(reminderType)) {
            r.date = null;
            if (dayOfWeek != null) r.dayOfWeek = dayOfWeek;
            r.dayOfMonth = null;
            r.monthDay = null;
        } else if ("monthly".equals(reminderType)) {
            r.date = null;
            r.dayOfWeek = null;
            if (dayOfMonth != null) r.dayOfMonth = dayOfMonth;
            r.monthDay = null;
        } else if ("yearly".equals(reminderType)) {
            r.date = null;
            r.dayOfWeek = null;
            r.dayOfMonth = null;
            if (monthDay != null) r.monthDay = monthDay;
        } else {
            r.date = null;
            r.dayOfWeek = null;
            r.dayOfMonth = null;
            r.monthDay = null;
        }

        if (enabled != null) r.enabled = enabled;

        updateReminderInDb(r);

        if (kbId != null) {
            syncToNoteLibrary(kbId);
        }

        log.info("[提醒] 更新提醒: {} ({})", r.name, type);
        return true;
    }

    private String normalizeTime(String time) {
        if (time == null || time.isBlank()) return "09:00";
        String[] parts = time.split(":");
        if (parts.length != 2) return "09:00";
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return String.format("%02d:%02d", hour, minute);
        } catch (NumberFormatException e) {
            return "09:00";
        }
    }

    public boolean deleteReminder(String id) {
        return deleteReminderInternal(id, null);
    }

    public boolean deleteReminder(String notesDir, String id) {
        return deleteReminderInternal(id, null);
    }

    public boolean deleteReminder(String id, Long kbId) {
        return deleteReminderInternal(id, kbId);
    }

    private boolean deleteReminderInternal(String id, Long kbId) {
        deleteReminderFromDb(id);

        if (kbId != null) {
            syncToNoteLibrary(kbId);
        }

        log.info("[提醒] 删除提醒: {}", id);
        return true;
    }

    public boolean toggleReminder(String id) {
        return toggleReminderInternal(id, null);
    }

    public boolean toggleReminder(String notesDir, String id) {
        return toggleReminderInternal(id, null);
    }

    public boolean toggleReminder(String id, Long kbId) {
        return toggleReminderInternal(id, kbId);
    }

    private boolean toggleReminderInternal(String id, Long kbId) {
        Reminder r = getReminderFromDb(id);
        if (r == null) return false;

        r.enabled = !r.enabled;
        updateReminderInDb(r);

        if (kbId != null) {
            syncToNoteLibrary(kbId);
        }

        log.info("[提醒] {}提醒: {}", r.enabled ? "启用" : "禁用", r.name);
        return true;
    }

    public void triggerReminder(Reminder r) {
        triggerReminder(r, null);
    }

    public void triggerReminder(String notesDir, Reminder r) {
        triggerReminder(r, null);
    }

    public void triggerReminder(Reminder r, Long kbId) {
        if (r == null || !r.enabled) return;

        try {
            List<List<Map<String, String>>> content = List.of(
                    List.of(Map.of("tag", "text", "text", "🔔 " + r.name)),
                    List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")),
                    List.of(Map.of("tag", "text", "text", r.message != null ? r.message : "该提醒了！"))
            );
            boolean success = feishuService.sendPost("🔔 " + r.name, content);
            if (!success) {
                log.warn("[提醒] 触发失败(飞书发送未成功): {}", r.name);
                return;
            }

            r.lastTriggered = LocalDateTime.now(TZ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            updateReminderInDb(r);

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

        for (Reminder r : getEnabledReminders()) {
            if (!shouldTriggerNow(r, now, currentTime, currentWeekday, currentMonth, currentDayOfMonth)) {
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

        if (triggerHour != currentHour || triggerMinute != currentMinute) {
            return false;
        }

        return switch (r.type) {
            case "daily" -> true;
            case "once" -> r.date != null && r.date.equals(now.toLocalDate().toString());
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
            case "once" -> "一次";
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
        if ("once".equals(r.type) && r.date != null) {
            sb.append(" (").append(r.date).append(")");
        } else if ("weekly".equals(r.type) && r.dayOfWeek != null) {
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

    private void syncToNoteLibrary(Long kbId) {
        try {
            String notesDir = configService.getNotesDir(kbId);
            if (notesDir == null || notesDir.isEmpty()) return;

            List<Reminder> allReminders = getRemindersByKbId(kbId);
            Root root = new Root();
            root.reminders = allReminders;
            root.meta = new LinkedHashMap<>();
            root.meta.put("lastUpdated", LocalDate.now(TZ).toString());

            Path reminderDir = Paths.get(notesDir, "AI", "提醒");
            if (!Files.exists(reminderDir)) {
                Files.createDirectories(reminderDir);
            }
            FileUtil.writeJson(reminderDir.resolve("reminders.json"), root);
            log.debug("[提醒] 同步到笔记库: kbId={}, count={}", kbId, allReminders.size());
        } catch (Exception e) {
            log.warn("[提醒] 同步到笔记库失败: {}", e.getMessage());
        }
    }

    private List<Reminder> getRemindersByKbId(Long kbId) {
        List<Reminder> reminders = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM reminders WHERE kb_id = ? ORDER BY created_at DESC")) {
            if (kbId != null) {
                ps.setLong(1, kbId);
            } else {
                ps.setObject(1, null);
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                reminders.add(mapRowToReminder(rs));
            }
        } catch (SQLException e) {
            log.error("[提醒] 按KB查询失败", e);
        }
        return reminders;
    }
}
