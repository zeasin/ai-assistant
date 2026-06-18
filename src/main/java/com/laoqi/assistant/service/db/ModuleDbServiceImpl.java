package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.ModuleEntity;
import com.laoqi.assistant.mapper.ModuleMapper;
import org.springframework.stereotype.Service;

@Service
public class ModuleDbServiceImpl extends ServiceImpl<ModuleMapper, ModuleEntity> implements ModuleDbService {
}
