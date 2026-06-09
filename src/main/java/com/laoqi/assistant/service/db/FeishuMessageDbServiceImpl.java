package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.FeishuMessageEntity;
import com.laoqi.assistant.mapper.FeishuMessageMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FeishuMessageDbServiceImpl extends ServiceImpl<FeishuMessageMapper, FeishuMessageEntity> implements FeishuMessageDbService {

    private final FeishuMessageMapper feishuMessageMapper;

    public FeishuMessageDbServiceImpl(FeishuMessageMapper feishuMessageMapper) {
        this.feishuMessageMapper = feishuMessageMapper;
    }

    @Override
    public List<FeishuMessageEntity> listByUserKey(String userKey) {
        return feishuMessageMapper.listByUserKey(userKey);
    }
}