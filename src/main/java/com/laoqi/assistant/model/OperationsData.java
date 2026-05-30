package com.laoqi.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OperationsData {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyStats {
        public String date;
        public Integer fans;
        public Integer reads;
        public Integer readArticles;
        public Integer sourceRecommend;
        public Integer sourceSearch;
        public Integer sourceMessage;
        public Integer sourceMoments;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlatformInfo {
        public String name;
        public Integer fans;
        public String fansUpdated;
        public Integer totalViews;
        public Integer totalArticles;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArticleData {
        public String id;
        public String topic;
        public String series;
        public String publishDate;
        public String fansAtPublish;
        public String fansGained;
        public String notes;
        public Map<String, PlatformArticleData> data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlatformArticleData {
        public Integer reads;
        public Integer likes;
        public Integer shares;
        public Integer favorites;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnalysisResult {
        public List<TopArticle> topArticles;
        public List<Finding> findings;
        public List<PlatformTip> platformTips;
        public List<Suggestion> suggestionPriority;
        public WritingTemplate writingTemplate;
    }

    public static class TopArticle {
        public String account;
        public String topic;
        public String id;
        public String platform;
        public int reads;
        public String fansAtPublish;
        public String fansGained;
    }

    public static class Finding {
        public String icon;
        public String title;
        public String detail;
        public String action;
    }

    public static class PlatformTip {
        public String platform;
        public String problem;
        public String action;
    }

    public static class Suggestion {
        public String priority;
        public String topic;
        public String reason;
    }

    public static class WritingTemplate {
        public String formula;
        public List<String> goodExamples;
        public List<String> badExamples;
        public String contentPipeline;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public Map<String, Object> meta;
        public Map<String, Map<String, Object>> accounts;
        public Map<String, List<ArticleData>> articles;
        public Map<String, List<DailyStats>> dailyStats;
    }
}