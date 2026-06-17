package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.TurnEmbeddingEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TurnEmbeddingMapper extends BaseMapper<TurnEmbeddingEntity> {

    @Select("SELECT * FROM turn_embeddings WHERE session_id = #{sessionId} ORDER BY turn_order ASC")
    List<TurnEmbeddingEntity> listBySession(@Param("sessionId") String sessionId);

    @Select("SELECT COALESCE(MAX(turn_order), -1) FROM turn_embeddings WHERE session_id = #{sessionId}")
    int maxTurnOrder(@Param("sessionId") String sessionId);

    @Select("SELECT COUNT(*) FROM turn_embeddings WHERE session_id = #{sessionId}")
    int countBySession(@Param("sessionId") String sessionId);
}
