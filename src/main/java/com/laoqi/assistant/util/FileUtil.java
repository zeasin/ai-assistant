package com.laoqi.assistant.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
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
        try (var in = Files.newInputStream(path)) {
            return mapper.readValue(in, clazz);
        } catch (Exception e) {
            log.warn("Failed to read JSON {}: {}", path, e.getMessage());
            return defaultVal;
        }
    }

    public static <T> T readJson(Path path, TypeReference<T> typeRef, T defaultVal) {
        if (!Files.exists(path)) return defaultVal;
        try (var in = Files.newInputStream(path)) {
            return mapper.readValue(in, typeRef);
        } catch (Exception e) {
            log.warn("Failed to read JSON {}: {}", path, e.getMessage());
            return defaultVal;
        }
    }

    public static void writeJson(Path path, Object obj) {
        try {
            Files.createDirectories(path.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
            try (var writer = new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
                writer.write(json);
            }
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

    public static String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return "[]";
        }
    }

    public static <T> T readJson(String json, TypeReference<T> typeRef, T defaultVal) {
        if (json == null || json.isBlank()) return defaultVal;
        try {
            return mapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.warn("Failed to read JSON string: {}", e.getMessage());
            return defaultVal;
        }
    }
}