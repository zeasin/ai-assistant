package com.laoqi.assistant.datacenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.laoqi.assistant.entity.DataModuleEntity;
import com.laoqi.assistant.entity.DataSetEntity;
import com.laoqi.assistant.mapper.DataModuleMapper;
import com.laoqi.assistant.service.db.DataSetDbService;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

@Service
public class DataModuleService {

    private static final Logger log = LoggerFactory.getLogger(DataModuleService.class);

    private final DataModuleMapper moduleMapper;
    private final DataSetDbService dataSetDbService;
    private final DataSource dataSource;

    public DataModuleService(DataModuleMapper moduleMapper, DataSetDbService dataSetDbService, DataSource dataSource) {
        this.moduleMapper = moduleMapper;
        this.dataSetDbService = dataSetDbService;
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        createTable();
    }

    private void createTable() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS data_center_modules (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    module_id       TEXT NOT NULL UNIQUE,
                    name            TEXT NOT NULL,
                    description     TEXT,
                    icon            TEXT,
                    sort_order      INTEGER DEFAULT 0,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
                """);
            log.info("Data center modules table initialized");
        } catch (Exception e) {
            log.error("Failed to create modules table", e);
        }
    }

    public List<Map<String, Object>> getAllModules() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<DataModuleEntity> modules = moduleMapper.selectList(
            new LambdaQueryWrapper<DataModuleEntity>().orderByAsc(DataModuleEntity::getSortOrder)
        );
        for (DataModuleEntity module : modules) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", module.getModuleId());
            map.put("name", module.getName());
            map.put("description", module.getDescription());
            map.put("icon", module.getIcon());
            map.put("sortOrder", module.getSortOrder());
            map.put("createdAt", module.getCreatedAt());
            map.put("updatedAt", module.getUpdatedAt());

            long datasetCount = dataSetDbService.countByModuleId(module.getModuleId());
            map.put("datasetCount", datasetCount);

            result.add(map);
        }
        return result;
    }

    public Map<String, Object> getModule(String moduleId) {
        LambdaQueryWrapper<DataModuleEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataModuleEntity::getModuleId, moduleId);
        DataModuleEntity entity = moduleMapper.selectOne(wrapper);
        if (entity == null) return null;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getModuleId());
        map.put("name", entity.getName());
        map.put("description", entity.getDescription());
        map.put("icon", entity.getIcon());
        map.put("sortOrder", entity.getSortOrder());
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());

        long datasetCount = dataSetDbService.countByModuleId(moduleId);
        map.put("datasetCount", datasetCount);

        return map;
    }

    public Map<String, Object> createModule(String name, String description, String icon) {
        String moduleId = "MOD" + System.currentTimeMillis();
        String now = TimeUtil.nowStr();

        DataModuleEntity entity = new DataModuleEntity();
        entity.setModuleId(moduleId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setIcon(icon != null ? icon : "📦");
        entity.setSortOrder(getNextSortOrder());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        moduleMapper.insert(entity);
        log.info("Created data module: {} ({})", name, moduleId);

        return getModule(moduleId);
    }

    public Map<String, Object> updateModule(String moduleId, String name, String description, String icon, Integer sortOrder) {
        LambdaQueryWrapper<DataModuleEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataModuleEntity::getModuleId, moduleId);
        DataModuleEntity entity = moduleMapper.selectOne(wrapper);
        if (entity == null) return null;

        if (name != null) entity.setName(name);
        if (description != null) entity.setDescription(description);
        if (icon != null) entity.setIcon(icon);
        if (sortOrder != null) entity.setSortOrder(sortOrder);
        entity.setUpdatedAt(TimeUtil.nowStr());

        moduleMapper.updateById(entity);
        log.info("Updated data module: {}", moduleId);

        return getModule(moduleId);
    }

    public boolean deleteModule(String moduleId) {
        LambdaQueryWrapper<DataModuleEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataModuleEntity::getModuleId, moduleId);
        int deleted = moduleMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("Deleted data module: {}", moduleId);
            return true;
        }
        return false;
    }

    private int getNextSortOrder() {
        Long count = moduleMapper.selectCount(null);
        return count != null ? count.intValue() : 0;
    }
}
