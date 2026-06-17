package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.ModuleDefinition;
import com.laoqi.assistant.util.FileUtil;
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

    private final AppConfig appConfig;
    private final ConfigService configService;

    public ModuleService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
    }

    public List<ModuleDefinition> getModules() {
        Map<String, Object> raw = FileUtil.readJson(appConfig.getConfigFile(), MAP_TYPE, Map.of());
        Object modulesRaw = raw.get("modules");
        if (modulesRaw instanceof List) {
            List<ModuleDefinition> result = new ArrayList<>();
            for (Object item : (List<?>) modulesRaw) {
                if (item instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) item;
                    ModuleDefinition mod = new ModuleDefinition();
                    mod.setId(str(m.get("id")));
                    mod.setName(str(m.get("name")));
                    mod.setDir(str(m.get("dir")));
                    mod.setIcon(str(m.get("icon")));
                    mod.setPrompt(str(m.get("prompt")));
                    Object files = m.get("dataFiles");
                    if (files instanceof List) {
                        List<String> fileList = new ArrayList<>();
                        for (Object f : (List<?>) files) fileList.add(f.toString());
                        mod.setDataFiles(fileList);
                    }
                    result.add(mod);
                }
            }
            return result;
        }
        return List.of();
    }

    public ModuleDefinition getModule(String id) {
        return getModules().stream()
                .filter(m -> m.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    public Path getModuleDataDir(ModuleDefinition mod) {
        return Path.of(configService.getBaseDir()).resolve(mod.getDir()).resolve("data");
    }

    public Path getModuleDir(ModuleDefinition mod) {
        return Path.of(configService.getBaseDir()).resolve(mod.getDir());
    }

    public void saveModules(List<ModuleDefinition> modules) {
        Map<String, Object> raw = FileUtil.readJson(appConfig.getConfigFile(), MAP_TYPE, Map.of());
        raw.put("modules", modules);
        FileUtil.writeJson(appConfig.getConfigFile(), raw);
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

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
