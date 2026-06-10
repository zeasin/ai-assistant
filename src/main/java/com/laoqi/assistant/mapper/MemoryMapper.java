package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.Memory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MemoryMapper extends BaseMapper<Memory> {

    @Select("""
        SELECT m.id, m.content, m.source, m.tags, m.created_at,
               bm25(memories_fts, 1.0) AS score
        FROM memories_fts f
        JOIN memories m ON f.rowid = m.id
        WHERE memories_fts MATCH #{ftsQuery}
        ORDER BY score
        LIMIT #{topK}
    """)
    List<Memory> searchFts(@Param("ftsQuery") String ftsQuery, @Param("topK") int topK);

    @Select("""
        SELECT id, content, source, tags, created_at
        FROM memories
        WHERE content LIKE '%' || #{keyword} || '%'
        ORDER BY created_at DESC
        LIMIT #{topK}
    """)
    List<Memory> searchLike(@Param("keyword") String keyword, @Param("topK") int topK);
}