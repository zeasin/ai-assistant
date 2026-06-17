package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.CodingRecordEntity;

import java.util.List;

public interface CodingRecordDbService extends IService<CodingRecordEntity> {
    List<CodingRecordEntity> findRecent(int limit);
}