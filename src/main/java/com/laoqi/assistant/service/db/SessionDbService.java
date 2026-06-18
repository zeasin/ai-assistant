package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.SessionEntity;

import java.util.List;

public interface SessionDbService extends IService<SessionEntity> {
    List<SessionEntity> listAllOrderByUpdate();
    List<SessionEntity> listBySourceOrderByUpdate(String source);
    SessionEntity findLatestByKb(Integer kbId);
    List<SessionEntity> listByKb(Integer kbId);
}
