package com.laoqi.assistant.model;

import java.util.ArrayList;
import java.util.List;

public class ModuleDefinition {
    private String id;
    private String name;
    private String dir;
    private String icon;
    private String prompt;
    private List<String> dataFiles = new ArrayList<>();

    public ModuleDefinition() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public List<String> getDataFiles() { return dataFiles; }
    public void setDataFiles(List<String> dataFiles) { this.dataFiles = dataFiles; }
}
