package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.SessionEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SessionMapper extends BaseMapper<SessionEntity> {

    @Select("SELECT * FROM sessions ORDER BY updated_at DESC")
    List<SessionEntity> listAllOrderByUpdate();

    @Select("SELECT * FROM sessions WHERE source = #{source} ORDER BY updated_at DESC")
    List<SessionEntity> listBySourceOrderByUpdate(String source);
}
