package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.TurnEmbeddingEntity;

import java.util.List;

public interface TurnEmbeddingDbService extends IService<TurnEmbeddingEntity> {
    List<TurnEmbeddingEntity> listBySession(String sessionId);
    int maxTurnOrder(String sessionId);
    int countBySession(String sessionId);
}
