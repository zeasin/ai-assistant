package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("chat_messages")
public class ChatMessageEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;
    private String sessionId;
    private String role;
    private String content;
    private String mode;
    private String createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}