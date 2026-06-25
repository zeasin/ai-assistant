package com.laoqi.assistant.dto;

/**
 * 数据目录中的文件信息
 */
public class DataFileInfo {

    private String name;
    private String path;
    private long size;
    private long lastModified;

    public DataFileInfo() {}

    public DataFileInfo(String name, String path, long size, long lastModified) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
}
