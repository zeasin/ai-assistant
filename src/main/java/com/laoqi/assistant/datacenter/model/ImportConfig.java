package com.laoqi.assistant.datacenter.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImportConfig {
    private Boolean enabled = true;
    private Map<String, String> columnMapping = new HashMap<>();
    private String aiPromptKey;
    private String urlTemplate;

    public ImportConfig() {}

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Map<String, String> getColumnMapping() { return columnMapping; }
    public void setColumnMapping(Map<String, String> columnMapping) { this.columnMapping = columnMapping; }

    public String getAiPromptKey() { return aiPromptKey; }
    public void setAiPromptKey(String aiPromptKey) { this.aiPromptKey = aiPromptKey; }

    public String getUrlTemplate() { return urlTemplate; }
    public void setUrlTemplate(String urlTemplate) { this.urlTemplate = urlTemplate; }
}
