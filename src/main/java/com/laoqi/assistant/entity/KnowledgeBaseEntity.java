package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("knowledge_bases")
public class KnowledgeBaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String notesDir;
    private String labels;
    private Integer isActive;
    private Integer sortOrder;
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNotesDir() { return notesDir; }
    public void setNotesDir(String notesDir) { this.notesDir = notesDir; }
    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }
    public Integer getIsActive() { return isActive; }
    public void setIsActive(Integer isActive) { this.isActive = isActive; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
