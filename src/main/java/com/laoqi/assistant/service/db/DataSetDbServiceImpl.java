package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.DataSetEntity;
import com.laoqi.assistant.mapper.DataSetMapper;
import org.springframework.stereotype.Service;

@Service
public class DataSetDbServiceImpl extends ServiceImpl<DataSetMapper, DataSetEntity> implements DataSetDbService {
}
