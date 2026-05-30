package com.laoqi.assistant.model;

import java.util.ArrayList;
import java.util.List;

public class ChatSession {
    private String id;
    private String title;
    private String created;
    private String updated;
    private List<ChatMessage> messages = new ArrayList<>();

    public static class ChatMessage {
        private String role;
        private String content;
        private String time;

        public ChatMessage() {}

        public ChatMessage(String role, String content, String time) {
            this.role = role;
            this.content = content;
            this.time = time;
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
    }

    public static String deriveTitle(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            if ("user".equals(m.getRole())) {
                String t = m.getContent();
                if (t.length() > 30) t = t.substring(0, 30) + "...";
                return t;
            }
        }
        return "新对话";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }
    public String getUpdated() { return updated; }
    public void setUpdated(String updated) { this.updated = updated; }
    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
}