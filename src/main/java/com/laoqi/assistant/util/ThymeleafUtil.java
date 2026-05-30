package com.laoqi.assistant.util;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component("utils")
public class ThymeleafUtil {

    public String mdInline(String text) {
        return MarkdownUtil.markdownInline(text);
    }

    public String pct(int part, int total) {
        if (total <= 0) return "—";
        return String.format("%.1f%%", part * 100.0 / total);
    }

    public String platformName(String key) {
        return switch (key) {
            case "wechat" -> "微信";
            case "csdn" -> "CSDN";
            case "zhihu" -> "知乎";
            default -> key;
        };
    }

    public String limit(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    public String safeString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    public String defaultString(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v != null ? v.toString() : def;
    }

    public <T> T orDefault(Object val, T def) {
        return val != null ? (T) val : def;
    }

    public String wan(double amount) {
        return String.format("%.0f万", amount / 10000);
    }

    public String fmtOne(double v) {
        return String.format("%.1f", v);
    }

    public String fmtPct(double v) {
        return String.format("%.1f%%", v);
    }

    public double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        return 0;
    }

    public int toInt(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        return 0;
    }

    public List toList(Object val) {
        if (val instanceof List) return (List) val;
        return List.of();
    }
}