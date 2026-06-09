package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.FeishuMessageEntity;

import java.util.List;

public interface FeishuMessageDbService extends IService<FeishuMessageEntity> {
    List<FeishuMessageEntity> listByUserKey(String userKey);
}