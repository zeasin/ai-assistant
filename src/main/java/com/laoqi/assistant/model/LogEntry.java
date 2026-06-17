package com.laoqi.assistant.model;

public class LogEntry {
    private String time;
    private String action;
    private String status;
    private String detail;

    public LogEntry() {}

    public LogEntry(String time, String action, String status, String detail) {
        this.time = time;
        this.action = action;
        this.status = status;
        this.detail = detail;
    }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}