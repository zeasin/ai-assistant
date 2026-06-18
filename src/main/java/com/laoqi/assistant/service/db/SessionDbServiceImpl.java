package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.SessionEntity;
import com.laoqi.assistant.mapper.SessionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SessionDbServiceImpl extends ServiceImpl<SessionMapper, SessionEntity> implements SessionDbService {

    private final SessionMapper sessionMapper;

    public SessionDbServiceImpl(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    @Override
    public List<SessionEntity> listAllOrderByUpdate() {
        return sessionMapper.listAllOrderByUpdate();
    }

    @Override
    public List<SessionEntity> listBySourceOrderByUpdate(String source) {
        return sessionMapper.listBySourceOrderByUpdate(source);
    }

    @Override
    public SessionEntity findLatestByKb(Integer kbId) {
        return sessionMapper.findLatestByKb(kbId);
    }

    @Override
    public List<SessionEntity> listByKb(Integer kbId) {
        return sessionMapper.listByKb(kbId);
    }
}
