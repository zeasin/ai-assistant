package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    @Select("SELECT * FROM chat_messages WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<ChatMessageEntity> listBySession(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM chat_messages WHERE session_id = #{sessionId} ORDER BY created_at DESC LIMIT #{limit}")
    List<ChatMessageEntity> listRecentBySession(@Param("sessionId") String sessionId, @Param("limit") int limit);
}