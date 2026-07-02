package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 文件索引元数据 — 追踪笔记库文件的变更状态，用于增量索引
 * 核心作用：无需遍历全文和计算 hash，仅通过 lastModified + fileSize 快速判断文件是否变化
 */
@TableName("file_index_meta")
public class FileIndexMetaEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库 ID */
    private Long kbId;

    /** 相对路径（相对于笔记库根目录），使用 / 分隔 */
    private String filePath;

    /** 文件最后修改时间戳（毫秒） */
    private Long lastModified;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 内容 MD5（精确检测，仅当 lastModified/fileSize 变化时才计算） */
    private String contentHash;

    /** 最后索引时间 */
    private String lastIndexedAt;

    /** 创建时间 */
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Long getLastModified() { return lastModified; }
    public void setLastModified(Long lastModified) { this.lastModified = lastModified; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getLastIndexedAt() { return lastIndexedAt; }
    public void setLastIndexedAt(String lastIndexedAt) { this.lastIndexedAt = lastIndexedAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
