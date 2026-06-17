package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.CodingRecordEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CodingRecordMapper extends BaseMapper<CodingRecordEntity> {

    @Select("SELECT * FROM coding_records ORDER BY id DESC LIMIT #{limit}")
    List<CodingRecordEntity> findRecent(int limit);
}