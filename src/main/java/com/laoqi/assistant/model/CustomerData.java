package com.laoqi.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomerData {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Customer {
        public String id;
        public String name;
        public String company;
        public String region;
        public String stage;
        public String product;
        public Double contractAmount;
        public Double paidAmount;
        public String notes;
        public String lastContactedAt;
        public String nextFollowUp;
        public List<Map<String, String>> contacts;
        public List<Map<String, Object>> activities;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Lead {
        public String id;
        public String name;
        public String company;
        public String region;
        public String source;
        public String status;
        public String notes;
        public String createdAt;
        public String lastContactedAt;
        public String nextFollowUp;
        public List<Map<String, String>> contacts;
        public List<Map<String, Object>> activities;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Record {
        public String id;
        public String customerId;
        public String customerName;
        public String type;
        public String actType;
        public String date;
        public String content;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PipelineStage {
        public String stage;
        public String color;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PipelineData {
        public List<PipelineStage> pipelines;
        public List<Customer> customers;
        public Map<String, Object> meta;
        public List<LeadStatus> leadStatuses;
        public List<Lead> leads;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LeadStatus {
        public String status;
        public String color;
    }
}