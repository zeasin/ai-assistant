package com.laoqi.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReminderData {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Reminder {
        public String id;
        public String name;
        public String message;
        public String type;
        public String time;
        public String date;
        public String dayOfWeek;
        public String dayOfMonth;
        public String monthDay;
        public boolean enabled;
        public String createdAt;
        public String lastTriggered;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public List<Reminder> reminders;
        public Map<String, Object> meta;
    }
}
