package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sessions")
public class SessionEntity {

    @TableId
    private String id;
    private String source;
    private String title;
    private String chatId;
    private String chatType;
    private String openCodeSessionId;
    private String openCodeCodeSessionId;
    private String mode;
    private String createdAt;
    private String updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getOpenCodeSessionId() { return openCodeSessionId; }
    public void setOpenCodeSessionId(String openCodeSessionId) { this.openCodeSessionId = openCodeSessionId; }
    public String getOpenCodeCodeSessionId() { return openCodeCodeSessionId; }
    public void setOpenCodeCodeSessionId(String openCodeCodeSessionId) { this.openCodeCodeSessionId = openCodeCodeSessionId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
