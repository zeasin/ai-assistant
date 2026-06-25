package com.laoqi.assistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应包装。
 *
 * <pre>
 * 成功: { "ok": true,  "data": ... }
 * 失败: { "ok": false, "error": "..." }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResult<T> {

    private boolean ok;
    private T data;
    private String error;

    private ApiResult() {}

    // ========== 成功 ==========

    public static <T> ApiResult<T> success(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.ok = true;
        r.data = data;
        return r;
    }

    public static ApiResult<Void> success() {
        ApiResult<Void> r = new ApiResult<>();
        r.ok = true;
        return r;
    }

    // ========== 失败 ==========

    public static <T> ApiResult<T> fail(String error) {
        ApiResult<T> r = new ApiResult<>();
        r.ok = false;
        r.error = error;
        return r;
    }

    // ========== Getter / Setter ==========

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
