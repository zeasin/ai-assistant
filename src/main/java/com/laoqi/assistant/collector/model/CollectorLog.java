package com.laoqi.assistant.collector.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CollectorLog {
    private String id;
    private String taskId;
    private String taskName;
    private String startTime;
    private String endTime;
    private String status;
    private String errorMessage;
    private Integer resultSize;

    public CollectorLog() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getResultSize() { return resultSize; }
    public void setResultSize(Integer resultSize) { this.resultSize = resultSize; }
}