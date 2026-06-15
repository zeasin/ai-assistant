package com.laoqi.assistant.controller;

import com.laoqi.assistant.model.AjaxResult;
import com.laoqi.assistant.model.TableDataInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public abstract class BaseController {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected AjaxResult success() {
        return AjaxResult.success();
    }

    protected AjaxResult success(Object data) {
        return AjaxResult.success(data);
    }

    protected AjaxResult success(String msg, Object data) {
        return AjaxResult.success(msg, data);
    }

    protected AjaxResult error() {
        return AjaxResult.error();
    }

    protected AjaxResult error(String msg) {
        return AjaxResult.error(msg);
    }

    protected AjaxResult error(int code, String msg) {
        return AjaxResult.error(code, msg);
    }

    protected AjaxResult toAjax(int rows) {
        return rows > 0 ? AjaxResult.success() : AjaxResult.error();
    }

    protected AjaxResult toAjax(boolean result) {
        return result ? AjaxResult.success() : AjaxResult.error();
    }

    protected TableDataInfo getDataTable(List<?> list) {
        return new TableDataInfo(list.size(), list);
    }
}
