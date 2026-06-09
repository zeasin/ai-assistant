package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("feishu_sessions")
public class FeishuSessionEntity {

    @TableId
    private String userKey;
    private String chatId;
    private String chatType;
    private String openCodeSessionId;
    private String openCodeCodeSessionId;
    private String created;
    private String updated;

    public String getUserKey() { return userKey; }
    public void setUserKey(String userKey) { this.userKey = userKey; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getOpenCodeSessionId() { return openCodeSessionId; }
    public void setOpenCodeSessionId(String openCodeSessionId) { this.openCodeSessionId = openCodeSessionId; }
    public String getOpenCodeCodeSessionId() { return openCodeCodeSessionId; }
    public void setOpenCodeCodeSessionId(String openCodeCodeSessionId) { this.openCodeCodeSessionId = openCodeCodeSessionId; }
    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }
    public String getUpdated() { return updated; }
    public void setUpdated(String updated) { this.updated = updated; }
}
