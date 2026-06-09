package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.ChatMessageEntity;
import com.laoqi.assistant.mapper.ChatMessageMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatMessageDbServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessageEntity> implements ChatMessageDbService {

    private final ChatMessageMapper chatMessageMapper;

    public ChatMessageDbServiceImpl(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public List<ChatMessageEntity> listBySession(String sessionId) {
        return chatMessageMapper.listBySession(sessionId);
    }

    @Override
    public List<ChatMessageEntity> listRecentBySession(String sessionId, int limit) {
        return chatMessageMapper.listRecentBySession(sessionId, limit);
    }
}