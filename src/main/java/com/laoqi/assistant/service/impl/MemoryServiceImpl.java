package com.laoqi.assistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.laoqi.assistant.entity.Memory;
import com.laoqi.assistant.mapper.MemoryMapper;
import com.laoqi.assistant.service.IMemoryService;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class MemoryServiceImpl implements IMemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceImpl.class);

    private final MemoryMapper memoryMapper;

    public MemoryServiceImpl(MemoryMapper memoryMapper) {
        this.memoryMapper = memoryMapper;
    }

    @Override
    public void remember(String content, String source, List<String> tags) {
        Memory m = new Memory();
        m.setContent(content);
        m.setSource(source);
        m.setTags(tags == null ? "[]" :
            "[" + tags.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(",")) + "]");
        m.setCreatedAt(TimeUtil.nowStr());
        memoryMapper.insert(m);
        log.info("记忆已保存: id={}, source={}", m.getId(), source);
    }

    @Override
    public List<Memory> search(String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        try {
            String ftsQuery = String.join(" AND ", query.trim().split("\\s+"));
            return memoryMapper.searchFts(ftsQuery, topK);
        } catch (DataAccessException e) {
            log.warn("FTS5 search failed, falling back to LIKE: {}", e.getMessage());
            return memoryMapper.searchLike(query.trim(), topK);
        }
    }

    @Override
    public void forget(Integer id) {
        memoryMapper.deleteById(id);
        log.info("记忆已遗忘: id={}", id);
    }

    @Override
    public List<Memory> listAll() {
        return memoryMapper.selectList(new QueryWrapper<Memory>().orderByDesc("created_at"));
    }

    @Override
    public long count() {
        return memoryMapper.selectCount(null);
    }
}