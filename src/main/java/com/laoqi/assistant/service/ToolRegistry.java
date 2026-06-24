package com.laoqi.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final List<Object> tools = new ArrayList<>();
    private final Map<String, ToolMeta> toolIndex = new ConcurrentHashMap<>();

    public ToolRegistry(NoteTools noteTools, DataTools dataTools) {
        register(noteTools);
        register(dataTools);
        log.info("[ToolRegistry] 初始化完成，已注册 {} 个工具集: {}", tools.size(), toolIndex.keySet());
    }

    public void register(Object toolBean) {
        List<Method> toolMethods = findToolMethods(toolBean.getClass());
        if (toolMethods.isEmpty()) {
            log.warn("[ToolRegistry] {} 没有 @Tool 方法，跳过", toolBean.getClass().getSimpleName());
            return;
        }
        tools.add(toolBean);
        for (Method method : toolMethods) {
            Tool annotation = method.getAnnotation(Tool.class);
            String name = method.getName();
            String desc = annotation.description();
            toolIndex.put(name, new ToolMeta(name, desc, toolBean.getClass().getSimpleName()));
        }
        log.info("[ToolRegistry] 注册工具集: {} ({} 个方法)", toolBean.getClass().getSimpleName(), toolMethods.size());
    }

    public void unregister(Object toolBean) {
        tools.remove(toolBean);
        toolIndex.values().removeIf(meta -> meta.sourceClass.equals(toolBean.getClass().getSimpleName()));
        log.info("[ToolRegistry] 移除工具集: {}", toolBean.getClass().getSimpleName());
    }

    public List<Object> getAllTools() {
        return Collections.unmodifiableList(tools);
    }

    public Object[] getToolArray() {
        return tools.toArray();
    }

    public Map<String, ToolMeta> getToolIndex() {
        return Collections.unmodifiableMap(toolIndex);
    }

    public List<ToolMeta> listTools() {
        return new ArrayList<>(toolIndex.values());
    }

    private List<Method> findToolMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    result.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    public record ToolMeta(String name, String description, String sourceClass) {}
}
