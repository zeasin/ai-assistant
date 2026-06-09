package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {

    @Select("SELECT * FROM chat_sessions ORDER BY updated_at DESC")
    List<ChatSessionEntity> listAllOrderByUpdate();
}