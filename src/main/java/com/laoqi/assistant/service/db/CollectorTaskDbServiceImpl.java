package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.CollectorTaskEntity;
import com.laoqi.assistant.mapper.CollectorTaskMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CollectorTaskDbServiceImpl extends ServiceImpl<CollectorTaskMapper, CollectorTaskEntity> implements CollectorTaskDbService {

    private final CollectorTaskMapper mapper;

    public CollectorTaskDbServiceImpl(CollectorTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public CollectorTaskEntity getByTaskId(String taskId) {
        LambdaQueryWrapper<CollectorTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CollectorTaskEntity::getTaskId, taskId).last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }

    @Override
    public List<CollectorTaskEntity> getAllEnabled() {
        LambdaQueryWrapper<CollectorTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CollectorTaskEntity::getEnabled, 1);
        return mapper.selectList(wrapper);
    }
}
