package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("note_embeddings")
public class NoteEmbeddingEntity {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long kbId;
    
    private String filePath;
    
    private Integer chunkIndex;
    
    private String pathContext;
    
    private String content;
    
    private String embedding;
    
    private String contentHash;
    
    private String createdAt;
    
    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    
    public String getPathContext() { return pathContext; }
    public void setPathContext(String pathContext) { this.pathContext = pathContext; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
