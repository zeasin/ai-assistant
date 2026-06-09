package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.ChatMessageEntity;

import java.util.List;

public interface ChatMessageDbService extends IService<ChatMessageEntity> {
    List<ChatMessageEntity> listBySession(String sessionId);
    List<ChatMessageEntity> listRecentBySession(String sessionId, int limit);
}