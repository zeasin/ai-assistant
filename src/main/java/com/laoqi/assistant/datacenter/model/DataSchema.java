package com.laoqi.assistant.datacenter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSchema {
    private List<DataField> fields = new ArrayList<>();

    public DataSchema() {}

    public List<DataField> getFields() { return fields; }
    public void setFields(List<DataField> fields) { this.fields = fields; }

    public void addField(DataField field) {
        this.fields.add(field);
    }

    public String[] getFieldNames() {
        return fields.stream().map(DataField::getName).toArray(String[]::new);
    }
}
