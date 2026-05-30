package com.laoqi.assistant.util;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class TimeUtil {

    private static final ZoneId TZ = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DF_COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DF_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Random RNG = new Random();

    public static ZonedDateTime now() {
        return ZonedDateTime.now(TZ);
    }

    public static String nowStr() {
        return now().format(DF);
    }

    public static String todayStr() {
        return now().format(DF_DATE);
    }

    public static String sessionId() {
        return now().format(DF_COMPACT) + Math.abs(RNG.nextLong() % 1000);
    }

    public static String weekdayCn(ZonedDateTime dt) {
        return switch (dt.getDayOfWeek().getValue()) {
            case 1 -> "周一"; case 2 -> "周二"; case 3 -> "周三"; case 4 -> "周四";
            case 5 -> "周五"; case 6 -> "周六"; case 7 -> "周日"; default -> "";
        };
    }

    public static String weekdayRoutine(ZonedDateTime dt) {
        return switch (dt.getDayOfWeek().getValue()) {
            case 1 -> "常规工作日，推进开发任务";
            case 2 -> "📝 周二！发「码农老齐」公众号，记得多平台分发";
            case 3 -> "常规工作日";
            case 4 -> "📝 周四！发「启航电商ERP」公众号";
            case 5 -> "常规工作日，本周收尾";
            case 6 -> "📝 周六！发「老齐二三事」";
            case 7 -> "休息日，适当放松";
            default -> "";
        };
    }
}