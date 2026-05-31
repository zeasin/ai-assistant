package com.laoqi.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskData {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskItem {
        public String id;
        public String title;
        public String description;
        public String status;
        public String priority;
        public String createdAt;
        public String updatedAt;
        public String dueDate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public Map<String, Object> meta;
        public List<TaskItem> tasks;
    }
}
