package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ai_analysis")
public class AiAnalysisEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    /** 类型: daily_report | dir_analysis | daily_report_prompt | dir_analysis_prompt */
    private String type;

    /** 内容 (日报/分析报告/提示词) */
    private String content;

    /** 生成时使用的提示词（分析结果专用） */
    private String prompt;

    /** 目录分析的子目录路径 (日报和提示词为null) */
    private String dirPath;

    /** 报告日期 yyyy-MM-dd (提示词为null) */
    private String reportDate;

    private String createdAt;

    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getDirPath() { return dirPath; }
    public void setDirPath(String dirPath) { this.dirPath = dirPath; }

    public String getReportDate() { return reportDate; }
    public void setReportDate(String reportDate) { this.reportDate = reportDate; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
