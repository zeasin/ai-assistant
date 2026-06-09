package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.ChatSessionEntity;

import java.util.List;

public interface ChatSessionDbService extends IService<ChatSessionEntity> {
    List<ChatSessionEntity> listAllOrderByUpdate();
}