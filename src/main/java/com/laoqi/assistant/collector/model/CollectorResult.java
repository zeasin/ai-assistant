package com.laoqi.assistant.collector.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CollectorResult {
    private String id;
    private String taskId;
    private String taskName;
    private String collectTime;
    private String rawResponse;
    private String parsedData;

    public CollectorResult() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getCollectTime() { return collectTime; }
    public void setCollectTime(String collectTime) { this.collectTime = collectTime; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public String getParsedData() { return parsedData; }
    public void setParsedData(String parsedData) { this.parsedData = parsedData; }
}