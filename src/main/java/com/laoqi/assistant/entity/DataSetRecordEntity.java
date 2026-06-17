package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("data_center_records")
public class DataSetRecordEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    private String recordId;
    private String datasetId;
    private String dataJson;
    private String source;
    private String contentHash;
    private String createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
