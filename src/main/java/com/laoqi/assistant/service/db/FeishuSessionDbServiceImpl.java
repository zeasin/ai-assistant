package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.FeishuSessionEntity;
import com.laoqi.assistant.mapper.FeishuSessionMapper;
import org.springframework.stereotype.Service;

@Service
public class FeishuSessionDbServiceImpl extends ServiceImpl<FeishuSessionMapper, FeishuSessionEntity> implements FeishuSessionDbService {
}