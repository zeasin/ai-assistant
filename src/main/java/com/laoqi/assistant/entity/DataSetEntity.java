package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("data_center_datasets")
public class DataSetEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    private String datasetId;
    private String name;
    private String description;
    private String schemaJson;
    private String importConfigsJson;
    private String createdAt;
    private String updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSchemaJson() { return schemaJson; }
    public void setSchemaJson(String schemaJson) { this.schemaJson = schemaJson; }
    public String getImportConfigsJson() { return importConfigsJson; }
    public void setImportConfigsJson(String importConfigsJson) { this.importConfigsJson = importConfigsJson; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
