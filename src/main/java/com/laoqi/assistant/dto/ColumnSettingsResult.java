package com.laoqi.assistant.dto;

import java.util.List;
import java.util.Map;

/**
 * 列设置响应（保持前端兼容：ok + settings 在顶层）
 */
public class ColumnSettingsResult {

    private boolean ok;
    private String type;
    private Map<String, List<String>> settings;

    public ColumnSettingsResult() {}

    public static ColumnSettingsResult success(String type, Map<String, List<String>> settings) {
        ColumnSettingsResult r = new ColumnSettingsResult();
        r.ok = true;
        r.type = type;
        r.settings = settings;
        return r;
    }

    public static ColumnSettingsResult fail(String error) {
        ColumnSettingsResult r = new ColumnSettingsResult();
        r.ok = false;
        return r;
    }

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Map<String, List<String>> getSettings() { return settings; }
    public void setSettings(Map<String, List<String>> settings) { this.settings = settings; }
}
