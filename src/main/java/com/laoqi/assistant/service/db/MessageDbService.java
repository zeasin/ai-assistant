package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.MessageEntity;

import java.util.List;

public interface MessageDbService extends IService<MessageEntity> {
    List<MessageEntity> listBySession(String sessionId);
    List<MessageEntity> listBySessionAndMode(String sessionId, String mode);
    List<MessageEntity> listRecentBySession(String sessionId, int limit);
}
