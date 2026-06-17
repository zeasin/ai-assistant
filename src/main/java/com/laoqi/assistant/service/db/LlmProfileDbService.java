package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.LlmProfileEntity;

import java.util.List;

public interface LlmProfileDbService extends IService<LlmProfileEntity> {
    LlmProfileEntity findDefault();
    LlmProfileEntity findByName(String name);
    List<LlmProfileEntity> listAllOrdered();
}
