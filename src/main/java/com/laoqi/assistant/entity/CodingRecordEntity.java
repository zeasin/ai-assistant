package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("coding_records")
public class CodingRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String time;
    private String startTime;
    private String endTime;
    private Integer duration; // 秒
    private String aiEngine;
    private String message;
    private String response;
    private String elapsed;
    private Boolean success;
    private String source; // "debug" or "feishu"
    private String projectDir;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }
    public String getAiEngine() { return aiEngine; }
    public void setAiEngine(String aiEngine) { this.aiEngine = aiEngine; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    public String getElapsed() { return elapsed; }
    public void setElapsed(String elapsed) { this.elapsed = elapsed; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getProjectDir() { return projectDir; }
    public void setProjectDir(String projectDir) { this.projectDir = projectDir; }
}
