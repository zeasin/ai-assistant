package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.CodingRecordEntity;
import com.laoqi.assistant.mapper.CodingRecordMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CodingRecordDbServiceImpl extends ServiceImpl<CodingRecordMapper, CodingRecordEntity> implements CodingRecordDbService {

    private final CodingRecordMapper mapper;

    public CodingRecordDbServiceImpl(CodingRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CodingRecordEntity> findRecent(int limit) {
        return mapper.findRecent(limit);
    }
}