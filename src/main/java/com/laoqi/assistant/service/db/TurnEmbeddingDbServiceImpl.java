package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.TurnEmbeddingEntity;
import com.laoqi.assistant.mapper.TurnEmbeddingMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TurnEmbeddingDbServiceImpl extends ServiceImpl<TurnEmbeddingMapper, TurnEmbeddingEntity> implements TurnEmbeddingDbService {

    private final TurnEmbeddingMapper turnEmbeddingMapper;

    public TurnEmbeddingDbServiceImpl(TurnEmbeddingMapper turnEmbeddingMapper) {
        this.turnEmbeddingMapper = turnEmbeddingMapper;
    }

    @Override
    public List<TurnEmbeddingEntity> listBySession(String sessionId) {
        return turnEmbeddingMapper.listBySession(sessionId);
    }

    @Override
    public int maxTurnOrder(String sessionId) {
        return turnEmbeddingMapper.maxTurnOrder(sessionId);
    }

    @Override
    public int countBySession(String sessionId) {
        return turnEmbeddingMapper.countBySession(sessionId);
    }
}
