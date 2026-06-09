package com.laoqi.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoqi.assistant.entity.FeishuMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface FeishuMessageMapper extends BaseMapper<FeishuMessageEntity> {

    @Select("SELECT * FROM feishu_messages WHERE user_key = #{userKey} ORDER BY created_at ASC")
    List<FeishuMessageEntity> listByUserKey(@Param("userKey") String userKey);
}