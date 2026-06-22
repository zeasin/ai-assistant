package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.NoteEmbeddingEntity;
import com.laoqi.assistant.mapper.NoteEmbeddingMapper;
import org.springframework.stereotype.Service;

@Service
public class NoteEmbeddingDbService extends ServiceImpl<NoteEmbeddingMapper, NoteEmbeddingEntity> {
    
    public int countByKb(Long kbId) {
        long count = lambdaQuery()
                .eq(NoteEmbeddingEntity::getKbId, kbId)
                .count();
        return (int) count;
    }
    
    public int countFilesByKb(Long kbId) {
        long count = lambdaQuery()
                .eq(NoteEmbeddingEntity::getKbId, kbId)
                .select(NoteEmbeddingEntity::getFilePath)
                .list()
                .stream()
                .map(NoteEmbeddingEntity::getFilePath)
                .distinct()
                .count();
        return (int) count;
    }
    
    public void deleteByKbAndPath(Long kbId, String filePath) {
        lambdaUpdate()
                .eq(NoteEmbeddingEntity::getKbId, kbId)
                .eq(NoteEmbeddingEntity::getFilePath, filePath)
                .remove();
    }
    
    public void deleteByKb(Long kbId) {
        lambdaUpdate()
                .eq(NoteEmbeddingEntity::getKbId, kbId)
                .remove();
    }
    
    public NoteEmbeddingEntity findByKbAndPathAndChunk(Long kbId, String filePath, Integer chunkIndex) {
        return lambdaQuery()
                .eq(NoteEmbeddingEntity::getKbId, kbId)
                .eq(NoteEmbeddingEntity::getFilePath, filePath)
                .eq(NoteEmbeddingEntity::getChunkIndex, chunkIndex)
                .one();
    }
}
