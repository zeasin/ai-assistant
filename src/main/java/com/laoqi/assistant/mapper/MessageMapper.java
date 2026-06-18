package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.MessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MessageMapper extends BaseMapper<MessageEntity> {

    @Select("SELECT * FROM messages WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<MessageEntity> listBySession(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM messages WHERE session_id = #{sessionId} AND mode = #{mode} ORDER BY created_at ASC")
    List<MessageEntity> listBySessionAndMode(@Param("sessionId") String sessionId, @Param("mode") String mode);

    @Select("SELECT * FROM messages WHERE session_id = #{sessionId} ORDER BY created_at DESC LIMIT #{limit}")
    List<MessageEntity> listRecentBySession(@Param("sessionId") String sessionId, @Param("limit") int limit);

    @Select("SELECT m.* FROM messages m JOIN sessions s ON m.session_id = s.id WHERE s.source = 'web' AND s.kb_id = #{kbId} ORDER BY m.created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<MessageEntity> listByKb(@Param("kbId") Integer kbId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM messages m JOIN sessions s ON m.session_id = s.id WHERE s.source = 'web' AND s.kb_id = #{kbId}")
    long countByKb(@Param("kbId") Integer kbId);

    @Select("SELECT m.* FROM messages m JOIN sessions s ON m.session_id = s.id WHERE s.source = 'web' AND s.kb_id = #{kbId} AND m.content LIKE '%' || #{q} || '%' ORDER BY m.created_at DESC LIMIT #{limit}")
    List<MessageEntity> searchByKb(@Param("kbId") Integer kbId, @Param("q") String q, @Param("limit") int limit);
}
