package com.laoqi.assistant.datacenter.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSetInfo {
    private String id;
    private String name;
    private String description;
    private int recordCount;
    private String lastImportTime;
    private String createdAt;

    public DataSetInfo() {}

    public DataSetInfo(DataSet ds, int recordCount, String lastImportTime) {
        this.id = ds.getId();
        this.name = ds.getName();
        this.description = ds.getDescription();
        this.recordCount = recordCount;
        this.lastImportTime = lastImportTime;
        this.createdAt = ds.getCreatedAt();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getRecordCount() { return recordCount; }
    public void setRecordCount(int recordCount) { this.recordCount = recordCount; }

    public String getLastImportTime() { return lastImportTime; }
    public void setLastImportTime(String lastImportTime) { this.lastImportTime = lastImportTime; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
