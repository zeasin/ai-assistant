package com.laoqi.assistant.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class AjaxResult extends HashMap<String, Object> {

    public static final String CODE_TAG = "code";
    public static final String MSG_TAG = "msg";
    public static final String DATA_TAG = "data";

    private static final long serialVersionUID = 1L;

    public AjaxResult() {
    }

    public AjaxResult(int code, String msg) {
        super.put(CODE_TAG, code);
        super.put(MSG_TAG, msg);
    }

    public AjaxResult(int code, String msg, Object data) {
        super.put(CODE_TAG, code);
        super.put(MSG_TAG, msg);
        if (data != null) {
            super.put(DATA_TAG, data);
        }
    }

    public static AjaxResult success() {
        return new AjaxResult(200, "操作成功");
    }

    public static AjaxResult success(Object data) {
        return new AjaxResult(200, "操作成功", data);
    }

    public static AjaxResult success(String msg, Object data) {
        return new AjaxResult(200, msg, data);
    }

    public static AjaxResult error() {
        return new AjaxResult(500, "操作失败");
    }

    public static AjaxResult error(String msg) {
        return new AjaxResult(500, msg);
    }

    public static AjaxResult error(int code, String msg) {
        return new AjaxResult(code, msg);
    }

    public static AjaxResult warn(String msg) {
        return new AjaxResult(601, msg);
    }

    @Override
    public AjaxResult put(String key, Object value) {
        super.put(key, value);
        return this;
    }
}
