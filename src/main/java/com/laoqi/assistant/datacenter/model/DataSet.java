package com.laoqi.assistant.datacenter.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSet {
    private String id;
    private String name;
    private String description;
    private DataSchema schema;
    private String outputDir;
    private Map<String, ImportConfig> importConfigs = new HashMap<>();
    private String createdAt;
    private String updatedAt;

    public DataSet() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public DataSchema getSchema() { return schema; }
    public void setSchema(DataSchema schema) { this.schema = schema; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public Map<String, ImportConfig> getImportConfigs() { return importConfigs; }
    public void setImportConfigs(Map<String, ImportConfig> importConfigs) { this.importConfigs = importConfigs; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
