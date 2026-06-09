package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.ChatSessionEntity;
import com.laoqi.assistant.mapper.ChatSessionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatSessionDbServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSessionEntity> implements ChatSessionDbService {

    private final ChatSessionMapper chatSessionMapper;

    public ChatSessionDbServiceImpl(ChatSessionMapper chatSessionMapper) {
        this.chatSessionMapper = chatSessionMapper;
    }

    @Override
    public List<ChatSessionEntity> listAllOrderByUpdate() {
        return chatSessionMapper.listAllOrderByUpdate();
    }
}