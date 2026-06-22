package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
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
}
