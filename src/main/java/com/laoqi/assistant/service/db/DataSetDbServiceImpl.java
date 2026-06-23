package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.DataSetEntity;
import com.laoqi.assistant.mapper.DataSetMapper;
import org.springframework.stereotype.Service;

@Service
public class DataSetDbServiceImpl extends ServiceImpl<DataSetMapper, DataSetEntity> implements DataSetDbService {

    @Override
    public long countByModuleId(String moduleId) {
        if (moduleId == null) {
            return count(new LambdaQueryWrapper<DataSetEntity>().isNull(DataSetEntity::getModuleId));
        }
        return count(new LambdaQueryWrapper<DataSetEntity>().eq(DataSetEntity::getModuleId, moduleId));
    }
}
