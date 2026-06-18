package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.MessageEntity;

import java.util.List;

public interface MessageDbService extends IService<MessageEntity> {
    List<MessageEntity> listBySession(String sessionId);
    List<MessageEntity> listBySessionAndMode(String sessionId, String mode);
    List<MessageEntity> listRecentBySession(String sessionId, int limit);
    List<MessageEntity> listByKb(Integer kbId, int offset, int limit);
    long countByKb(Integer kbId);
    List<MessageEntity> searchByKb(Integer kbId, String q, int limit);
}
