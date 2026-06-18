package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseDbServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBaseEntity> implements KnowledgeBaseDbService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseDbServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }
}
