package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.LlmProfileEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface LlmProfileMapper extends BaseMapper<LlmProfileEntity> {

    @Select("SELECT * FROM llm_profiles WHERE is_default = 1 LIMIT 1")
    LlmProfileEntity findDefault();

    @Select("SELECT * FROM llm_profiles WHERE name = #{name} LIMIT 1")
    LlmProfileEntity findByName(String name);

    @Select("SELECT * FROM llm_profiles ORDER BY is_default DESC, id ASC")
    List<LlmProfileEntity> listAllOrdered();
}
