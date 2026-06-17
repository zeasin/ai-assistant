package com.laoqi.assistant.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
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
        try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            return mapper.readValue(reader, clazz);
        } catch (Exception e) {
            log.warn("Failed to read JSON {}: {}", path, e.getMessage());
            return defaultVal;
        }
    }

    public static <T> T readJson(Path path, TypeReference<T> typeRef, T defaultVal) {
        if (!Files.exists(path)) return defaultVal;
        try (var reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            return mapper.readValue(reader, typeRef);
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
}