package com.laoqi.assistant.datacenter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSchema {
    private List<DataField> fields = new ArrayList<>();
    private List<String> statuses = new ArrayList<>();
    private List<String> typeOptions = new ArrayList<>();
    private List<String> statusOptions = new ArrayList<>();

    public DataSchema() {}

    public List<DataField> getFields() { return fields; }
    public void setFields(List<DataField> fields) { this.fields = fields; }

    public List<String> getStatuses() { return statuses; }
    public void setStatuses(List<String> statuses) { this.statuses = statuses; }

    public List<String> getTypeOptions() { return typeOptions; }
    public void setTypeOptions(List<String> typeOptions) { this.typeOptions = typeOptions; }

    public List<String> getStatusOptions() { return statusOptions; }
    public void setStatusOptions(List<String> statusOptions) { this.statusOptions = statusOptions; }

    public void addField(DataField field) {
        this.fields.add(field);
    }

    public String[] getFieldNames() {
        return fields.stream().map(DataField::getName).toArray(String[]::new);
    }
}
