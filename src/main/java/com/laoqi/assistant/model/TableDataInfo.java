package com.laoqi.assistant.model;

import java.io.Serializable;
import java.util.List;

public class TableDataInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;
    private List<?> rows;
    private int code;
    private String msg;

    public TableDataInfo() {
    }

    public TableDataInfo(long total, List<?> rows) {
        this.total = total;
        this.rows = rows;
        this.code = 200;
        this.msg = "查询成功";
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public List<?> getRows() {
        return rows;
    }

    public void setRows(List<?> rows) {
        this.rows = rows;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }
}
