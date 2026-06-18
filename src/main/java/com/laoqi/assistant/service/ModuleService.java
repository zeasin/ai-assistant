package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.entity.ModuleEntity;
import com.laoqi.assistant.model.ModuleDefinition;
import com.laoqi.assistant.service.db.ModuleDbService;
import com.laoqi.assistant.util.FileUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ModuleService {

    private static final Logger log = LoggerFactory.getLogger(ModuleService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final ModuleDbService moduleDbService;

    public ModuleService(AppConfig appConfig, ConfigService configService, ModuleDbService moduleDbService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.moduleDbService = moduleDbService;
    }

    @PostConstruct
    public void migrateFromConfig() {
        try {
            long count = moduleDbService.count();
            if (count > 0) return;

            Map<String, Object> raw = FileUtil.readJson(appConfig.getConfigFile(), MAP_TYPE, Map.of());
            Object modulesRaw = raw.get("modules");
            if (!(modulesRaw instanceof List<?> list)) return;

            int order = 0;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                ModuleEntity e = new ModuleEntity();
                e.setModuleId(str(m.get("id")));
                e.setName(str(m.get("name")));
                e.setDir(str(m.get("dir")));
                e.setIcon(str(m.get("icon")));
                e.setPrompt(str(m.get("prompt")));
                Object files = m.get("dataFiles");
                if (files instanceof List<?> fileList) {
                    e.setDataFiles(FileUtil.toJson(fileList));
                } else {
                    e.setDataFiles("[]");
                }
                e.setSortOrder(order++);
                moduleDbService.save(e);
            }
            log.info("已从 config.json 迁移 {} 个模块到 SQLite", list.size());

            raw.remove("modules");
            FileUtil.writeJson(appConfig.getConfigFile(), raw);
            log.info("已从 config.json 中移除 modules 配置");
        } catch (Exception e) {
            log.warn("模块迁移失败（可能是首次启动，表尚未创建）: {}", e.getMessage());
        }
    }

    public List<ModuleDefinition> getModules() {
        List<ModuleEntity> entities = moduleDbService.lambdaQuery()
                .orderByAsc(ModuleEntity::getSortOrder)
                .list();
        List<ModuleDefinition> result = new ArrayList<>();
        for (ModuleEntity e : entities) {
            result.add(toDefinition(e));
        }
        return result;
    }

    public ModuleDefinition getModule(String id) {
        ModuleEntity e = moduleDbService.lambdaQuery()
                .eq(ModuleEntity::getModuleId, id)
                .one();
        return e != null ? toDefinition(e) : null;
    }

    public Path getModuleDataDir(ModuleDefinition mod) {
        return Path.of(configService.getBaseDir()).resolve(mod.getDir()).resolve("data");
    }

    public Path getModuleDir(ModuleDefinition mod) {
        return Path.of(configService.getBaseDir()).resolve(mod.getDir());
    }

    public void saveModules(List<ModuleDefinition> modules) {
        moduleDbService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        int order = 0;
        for (ModuleDefinition mod : modules) {
            moduleDbService.save(toEntity(mod, order++));
        }
    }

    @SuppressWarnings("unchecked")
    public void saveModule(Map<String, Object> body) {
        String moduleId = (String) body.get("moduleId");
        ModuleEntity e = moduleDbService.lambdaQuery()
                .eq(ModuleEntity::getModuleId, moduleId)
                .one();
        boolean isNew = e == null;

        if (isNew) {
            e = new ModuleEntity();
            e.setModuleId(moduleId);
            int maxOrder = moduleDbService.lambdaQuery()
                    .orderByDesc(ModuleEntity::getSortOrder)
                    .list().stream()
                    .findFirst()
                    .map(m -> m.getSortOrder() + 1)
                    .orElse(0);
            e.setSortOrder(maxOrder);
        }

        e.setName(str(body.get("name")));
        e.setDir(str(body.get("dir")));
        e.setIcon(str(body.get("icon")));
        e.setPrompt(str(body.get("prompt")));
        Object files = body.get("dataFiles");
        if (files instanceof List<?> fileList) {
            e.setDataFiles(FileUtil.toJson(fileList));
        } else if (isNew) {
            e.setDataFiles("[]");
        }

        if (isNew) {
            moduleDbService.save(e);
        } else {
            moduleDbService.updateById(e);
        }
    }

    public void removeModuleByModuleId(String moduleId) {
        moduleDbService.lambdaUpdate()
                .eq(ModuleEntity::getModuleId, moduleId)
                .remove();
    }

    public void removeModule(Long id) {
        moduleDbService.removeById(id);
    }

    public void reorderModules(List<String> moduleIds) {
        int order = 0;
        for (String moduleId : moduleIds) {
            moduleDbService.lambdaUpdate()
                    .eq(ModuleEntity::getModuleId, moduleId)
                    .set(ModuleEntity::getSortOrder, order++)
                    .update();
        }
    }

    private static final String PROMPT_FILENAME = "分析提示词.md";

    public Path getPromptPath(ModuleDefinition mod) {
        return getModuleDir(mod).resolve(PROMPT_FILENAME);
    }

    public String readPrompt(ModuleDefinition mod) {
        Path file = getPromptPath(mod);
        if (FileUtil.exists(file)) {
            return FileUtil.readText(file);
        }
        String initialPrompt = mod.getPrompt();
        if (initialPrompt == null || initialPrompt.isBlank()) {
            initialPrompt = "你是一个" + mod.getName() + "分析师。分析以下数据，从以下几个维度给出洞察：\n\n"
                    + "1. 整体概况\n2. 趋势分析\n3. 问题与风险\n4. 优化建议\n5. 下一步行动\n";
        }
        FileUtil.writeText(file, initialPrompt);
        log.info("已为模块 {} 创建提示词文件: {}", mod.getId(), file);
        return initialPrompt;
    }

    public void writePrompt(ModuleDefinition mod, String content) {
        Path file = getPromptPath(mod);
        FileUtil.writeText(file, content);
    }

    private ModuleDefinition toDefinition(ModuleEntity e) {
        ModuleDefinition mod = new ModuleDefinition();
        mod.setId(e.getModuleId());
        mod.setName(e.getName());
        mod.setDir(e.getDir());
        mod.setIcon(e.getIcon());
        mod.setPrompt(e.getPrompt());
        List<String> files = FileUtil.readJson(e.getDataFiles(), LIST_TYPE, List.of());
        mod.setDataFiles(new ArrayList<>(files));
        return mod;
    }

    private ModuleEntity toEntity(ModuleDefinition mod, int order) {
        ModuleEntity e = new ModuleEntity();
        e.setModuleId(mod.getId());
        e.setName(mod.getName());
        e.setDir(mod.getDir());
        e.setIcon(mod.getIcon());
        e.setPrompt(mod.getPrompt());
        e.setDataFiles(FileUtil.toJson(mod.getDataFiles()));
        e.setSortOrder(order);
        return e;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
