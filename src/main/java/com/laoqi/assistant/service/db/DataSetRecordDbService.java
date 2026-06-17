package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.DataSetRecordEntity;

import java.util.List;

public interface DataSetRecordDbService extends IService<DataSetRecordEntity> {
    List<DataSetRecordEntity> listByDataset(String datasetId);
    int countByDataset(String datasetId);
    boolean existsByHash(String datasetId, String hash);
}
