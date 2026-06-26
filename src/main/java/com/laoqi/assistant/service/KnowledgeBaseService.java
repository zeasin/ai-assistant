package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.service.db.KnowledgeBaseDbService;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KnowledgeBaseDbService kbDbService;
    private final AppConfig appConfig;

    public KnowledgeBaseService(KnowledgeBaseDbService kbDbService, AppConfig appConfig) {
        this.kbDbService = kbDbService;
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void migrateFromConfig() {
        try {
            long count = kbDbService.count();
            if (count > 0) return;

            Map<String, Object> raw = FileUtil.readJson(appConfig.getConfigFile(), MAP_TYPE, Map.of());
            String notesDir = str(raw.get("notesDir"));
            if (notesDir == null || notesDir.isBlank()) {
                notesDir = str(raw.get("baseDir"));
            }
            if (notesDir == null || notesDir.isBlank()) return;

            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            kb.setName("工作");
            kb.setNotesDir(notesDir);
            kb.setLabels("{\"tasks\":\"任务\",\"reminders\":\"提醒\",\"notes\":\"笔记\",\"config\":\"配置\"}");
            kb.setSortOrder(0);
            kb.setCreatedAt(TimeUtil.nowStr());
            kbDbService.save(kb);
            log.info("已从 config.json 迁移知识库: name={}, notesDir={}", kb.getName(), kb.getNotesDir());
        } catch (Exception e) {
            log.warn("知识库迁移失败（可能是首次启动，表尚未创建）: {}", e.getMessage());
        }
    }

    public List<KnowledgeBaseEntity> getAll() {
        return kbDbService.lambdaQuery()
                .orderByAsc(KnowledgeBaseEntity::getSortOrder)
                .list();
    }

    public KnowledgeBaseEntity getById(Long id) {
        return kbDbService.getById(id);
    }

    public KnowledgeBaseEntity getFirst() {
        return kbDbService.lambdaQuery()
                .orderByAsc(KnowledgeBaseEntity::getSortOrder)
                .last("LIMIT 1")
                .one();
    }

    public void save(Map<String, Object> body) {
        Object idRaw = body.get("id");
        Long id = idRaw != null ? Long.valueOf(idRaw.toString()) : null;

        KnowledgeBaseEntity e;
        boolean isNew = false;
        if (id != null && id > 0) {
            e = kbDbService.getById(id);
            if (e == null) {
                e = new KnowledgeBaseEntity();
                e.setId(id);
            }
        } else {
            e = new KnowledgeBaseEntity();
            isNew = true;
        }

        if (body.containsKey("name")) e.setName(str(body.get("name")));
        if (body.containsKey("notesDir")) e.setNotesDir(str(body.get("notesDir")));
        if (body.containsKey("labels")) e.setLabels(str(body.get("labels")));
        if (body.containsKey("dirSettings")) e.setDirSettings(str(body.get("dirSettings")));
        if (body.containsKey("ignoreDirs")) e.setIgnoreDirs(str(body.get("ignoreDirs")));
        if (body.containsKey("ignoreFiles")) e.setIgnoreFiles(str(body.get("ignoreFiles")));
        if (body.containsKey("sortOrder")) {
            e.setSortOrder(Integer.valueOf(body.get("sortOrder").toString()));
        }
        if (body.containsKey("autoReport")) {
            Object v = body.get("autoReport");
            e.setAutoReport(Boolean.TRUE.equals(v) ? 1 : 0);
        }
        if (body.containsKey("feishuPush")) {
            Object v = body.get("feishuPush");
            e.setFeishuPush(Boolean.TRUE.equals(v) ? 1 : 0);
        }

        if (isNew) {
            int maxOrder = kbDbService.lambdaQuery()
                    .orderByDesc(KnowledgeBaseEntity::getSortOrder)
                    .list().stream()
                    .findFirst()
                    .map(k -> k.getSortOrder() + 1)
                    .orElse(0);
            e.setSortOrder(maxOrder);
            e.setLabels("{}");
            e.setCreatedAt(TimeUtil.nowStr());
            kbDbService.save(e);
        } else {
            kbDbService.updateById(e);
        }
    }

    public void delete(Long id) {
        KnowledgeBaseEntity e = kbDbService.getById(id);
        if (e == null) return;

        kbDbService.removeById(id);
    }

    public void setActive(Long id) {
    }

    public void reorder(List<Long> ids) {
        int order = 0;
        for (Long id : ids) {
            kbDbService.lambdaUpdate()
                    .eq(KnowledgeBaseEntity::getId, id)
                    .set(KnowledgeBaseEntity::getSortOrder, order++)
                    .update();
        }
    }

    public String getNotesDir() {
        KnowledgeBaseEntity first = getFirst();
        if (first != null) {
            return first.getNotesDir();
        }
        return "";
    }

    public String getNotesDirById(Long kbId) {
        if (kbId == null) return getNotesDir();
        KnowledgeBaseEntity kb = getById(kbId);
        if (kb != null) return kb.getNotesDir();
        return getNotesDir();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
