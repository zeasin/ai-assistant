package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.MessageEntity;
import com.laoqi.assistant.mapper.MessageMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageDbServiceImpl extends ServiceImpl<MessageMapper, MessageEntity> implements MessageDbService {

    private final MessageMapper messageMapper;

    public MessageDbServiceImpl(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public List<MessageEntity> listBySession(String sessionId) {
        return messageMapper.listBySession(sessionId);
    }

    @Override
    public List<MessageEntity> listBySessionAndMode(String sessionId, String mode) {
        return messageMapper.listBySessionAndMode(sessionId, mode);
    }

    @Override
    public List<MessageEntity> listRecentBySession(String sessionId, int limit) {
        return messageMapper.listRecentBySession(sessionId, limit);
    }

    @Override
    public List<MessageEntity> listByKb(Integer kbId, int offset, int limit) {
        return messageMapper.listByKb(kbId, offset, limit);
    }

    @Override
    public long countByKb(Integer kbId) {
        return messageMapper.countByKb(kbId);
    }

    @Override
    public List<MessageEntity> searchByKb(Integer kbId, String q, int limit) {
        return messageMapper.searchByKb(kbId, q, limit);
    }
}
