package com.laoqi.assistant.dto;

import java.util.List;

/**
 * 数据目录列表响应（保持前端兼容：ok + files 在顶层）
 */
public class DataListResult {

    private boolean ok;
    private String directory;
    private String fullPath;
    private boolean exists;
    private String error;
    private List<DataFileInfo> files;
    private int fileCount;

    public DataListResult() {}

    // ========== 工厂方法 ==========

    public static DataListResult success(String directory, String fullPath, List<DataFileInfo> files) {
        DataListResult r = new DataListResult();
        r.ok = true;
        r.exists = true;
        r.directory = directory;
        r.fullPath = fullPath;
        r.files = files;
        r.fileCount = files.size();
        return r;
    }

    public static DataListResult notExists(String directory, String fullPath) {
        DataListResult r = new DataListResult();
        r.ok = false;
        r.exists = false;
        r.directory = directory;
        r.fullPath = fullPath;
        r.error = "目录不存在: " + fullPath;
        return r;
    }

    public static DataListResult fail(String error) {
        DataListResult r = new DataListResult();
        r.ok = false;
        r.error = error;
        return r;
    }

    // ========== Getter / Setter ==========

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }

    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }

    public String getFullPath() { return fullPath; }
    public void setFullPath(String fullPath) { this.fullPath = fullPath; }

    public boolean isExists() { return exists; }
    public void setExists(boolean exists) { this.exists = exists; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public List<DataFileInfo> getFiles() { return files; }
    public void setFiles(List<DataFileInfo> files) { this.files = files; }

    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }
}
