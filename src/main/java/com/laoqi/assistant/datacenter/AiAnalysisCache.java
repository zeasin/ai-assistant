package com.laoqi.assistant.datacenter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiAnalysisCache {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisCache.class);
    private static final long CACHE_TTL_MS = 30 * 60 * 1000; // 30分钟

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public String get(String moduleId) {
        CacheEntry entry = cache.get(moduleId);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
            cache.remove(moduleId);
            log.debug("AI分析缓存过期: {}", moduleId);
            return null;
        }
        log.debug("AI分析缓存命中: {}", moduleId);
        return entry.content;
    }

    public void put(String moduleId, String content) {
        cache.put(moduleId, new CacheEntry(content, System.currentTimeMillis()));
        log.debug("AI分析缓存更新: {}", moduleId);
    }

    public void invalidate(String moduleId) {
        cache.remove(moduleId);
        log.debug("AI分析缓存清除: {}", moduleId);
    }

    public void invalidateAll() {
        cache.clear();
        log.debug("AI分析缓存全部清除");
    }

    private static class CacheEntry {
        String content;
        long timestamp;

        CacheEntry(String content, long timestamp) {
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}
