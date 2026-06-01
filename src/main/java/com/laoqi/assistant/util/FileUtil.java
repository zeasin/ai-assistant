package com.laoqi.assistant.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class FileUtil {

    private static final Logger log = LoggerFactory.getLogger(FileUtil.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static <T> T readJson(Path path, Class<T> clazz, T defaultVal) {
        if (!Files.exists(path)) return defaultVal;
        try {
            return mapper.readValue(path.toFile(), clazz);
        } catch (Exception e) {
            log.warn("Failed to read JSON {}: {}", path, e.getMessage());
            return defaultVal;
        }
    }

    public static <T> T readJson(Path path, TypeReference<T> typeRef, T defaultVal) {
        if (!Files.exists(path)) return defaultVal;
        try {
            return mapper.readValue(path.toFile(), typeRef);
        } catch (Exception e) {
            log.warn("Failed to read JSON {}: {}", path, e.getMessage());
            return defaultVal;
        }
    }

    public static void writeJson(Path path, Object obj) {
        try {
            Files.createDirectories(path.getParent());
            log.info("Writing JSON to {}, class={}", path, obj.getClass().getSimpleName());
            
            // 先序列化到字符串查看
            String json = mapper.writeValueAsString(obj);
            log.info("Serialized JSON length={}, contains mediaCollectEnabled={}", 
                    json.length(), json.contains("mediaCollectEnabled"));
            if (json.length() < 2000) {
                log.info("JSON content: {}", json);
            }
            
            mapper.writeValue(path.toFile(), obj);
            log.info("Successfully wrote JSON to {}", path);
        } catch (IOException e) {
            log.error("Failed to write JSON {}: {}", path, e.getMessage(), e);
        }
    }

    public static String readText(Path path) {
        if (!Files.exists(path)) return "";
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static void writeText(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to write file {}: {}", path, e.getMessage());
        }
    }

    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    public static boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }
}