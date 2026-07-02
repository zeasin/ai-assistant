package com.laoqi.assistant.service;

import com.laoqi.assistant.service.db.FileIndexMetaDbService;
import com.laoqi.assistant.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Service
public class IndexWatcherService {
    private static final Logger log = LoggerFactory.getLogger(IndexWatcherService.class);
    private static final int DEBOUNCE_MS = 2000;

    private final NoteIndexService noteIndexService;
    private final IndexScannerService scannerService;
    private final KnowledgeBaseService kbService;
    private final FileIndexMetaDbService metaDbService;

    private final Map<Long, WatcherEntry> watchers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "watcher-debouncer"); t.setDaemon(true); return t;
    });
    private volatile boolean running = true;

    public IndexWatcherService(NoteIndexService ns, IndexScannerService ss, KnowledgeBaseService kb, FileIndexMetaDbService md) {
        this.noteIndexService = ns; this.scannerService = ss; this.kbService = kb; this.metaDbService = md;
    }

    @PostConstruct
    public void init() {
        log.info("[IndexWatcher] 初始化文件监听服务");
        for (var kb : kbService.getAll()) {
            if (kb.getNotesDir() != null && !kb.getNotesDir().isBlank()) {
                try { registerKb(kb.getId(), kb.getNotesDir()); }
                catch (Exception e) { log.warn("[IndexWatcher] 注册失败: kbId={}", kb.getId(), e); }
            }
        }
        new Thread(this::processEvents, "index-watcher").start();
        log.info("[IndexWatcher] 已启动，共 {} 个知识库", watchers.size());
    }

    @PreDestroy
    public void shutdown() {
        this.running = false;
        for (WatcherEntry e : watchers.values()) {
            try { e.watchService.close(); } catch (IOException ignored) {}
        }
        debouncer.shutdown();
    }

    public void registerKb(Long kbId, String notesDir) throws IOException {
        if (notesDir == null || notesDir.isBlank()) return;
        Path dir = Paths.get(notesDir);
        if (!Files.isDirectory(dir)) { log.warn("[IndexWatcher] 目录不存在: {}", notesDir); return; }
        WatcherEntry old = watchers.remove(kbId);
        if (old != null) { try { old.watchService.close(); } catch (IOException ignored) {} }
        WatchService ws = FileSystems.getDefault().newWatchService();
        WatcherEntry entry = new WatcherEntry(kbId, ws, dir);
        registerTree(ws, dir);
        watchers.put(kbId, entry);
        log.info("[IndexWatcher] 已注册: kbId={}, dir={}", kbId, notesDir);
    }

    public void unregisterKb(Long kbId) {
        WatcherEntry old = watchers.remove(kbId);
        if (old != null) { try { old.watchService.close(); } catch (IOException ignored) {} }
    }

    public void reRegisterAll() {
        watchers.clear();
        for (var kb : kbService.getAll()) {
            if (kb.getNotesDir() != null && !kb.getNotesDir().isBlank()) {
                try { registerKb(kb.getId(), kb.getNotesDir()); } catch (Exception e) {
                    log.warn("[IndexWatcher] 重新注册失败: kbId={}", kb.getId(), e);
                }
            }
        }
    }

    private void registerTree(WatchService ws, Path dir) {
        try (Stream<Path> stream = Files.walk(dir, 10)) {
            stream.filter(Files::isDirectory).filter(p -> { return !p.getFileName().toString().startsWith("."); }).forEach(subDir -> {
                try {
                    WatchKey key = subDir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                    watchers.values().stream().filter(e -> e.watchService == ws).findFirst().ifPresent(e -> e.dirKeys.put(key, subDir));
                } catch (IOException e) {
                    log.warn("[IndexWatcher] 注册子目录失败: {}", subDir, e);
                }
            });
        } catch (IOException e) {
            log.warn("[IndexWatcher] 遍历目录失败: {}", dir, e);
        }
    }

    private void processEvents() {
        while (running) {
            for (WatcherEntry entry : watchers.values()) {
                WatchKey key;
                try { key = entry.watchService.poll(1, TimeUnit.SECONDS); }
                catch (InterruptedException | ClosedWatchServiceException e) { continue; }
                if (key == null) continue;
                Path dir = entry.dirKeys.get(key);
                if (dir == null) { key.reset(); continue; }
                Set<String> changedFiles = new HashSet<>();
                for (WatchEvent event : key.pollEvents()) {
                    WatchEvent.Kind kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                    Path filename = (Path) event.context();
                    String name = filename.toString();
                    if (name.startsWith(".")) continue;
                    if (name.endsWith("~") || name.endsWith(".swp") || name.endsWith(".tmp")) continue;
                    Path fullPath = dir.resolve(filename);
                    String relPath = entry.baseDir.relativize(fullPath).toString().replace("\\", "/");
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath) && !name.startsWith(".")) {
                        registerTree(entry.watchService, fullPath);
                    }
                    if (Files.isRegularFile(fullPath) || kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        changedFiles.add(relPath);
                    }
                }
                if (!key.reset()) { continue; }
                if (!changedFiles.isEmpty()) {
                    Long kbId = entry.kbId;
                    debouncer.schedule(() -> processDebouncedEvents(kbId, changedFiles), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
                }
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }

    private void processDebouncedEvents(Long kbId, Set<String> changedFiles) {
        if (!running) return;
        String notesDir = kbService.getNotesDirById(kbId);
        if (notesDir == null) return;
        Path baseDir = Paths.get(notesDir);
        log.info("[IndexWatcher] 处理变更: kbId={}, 文件数={}", kbId, changedFiles.size());
        for (String relPath : changedFiles) {
            Path fullPath = baseDir.resolve(relPath);
            try {
                if (Files.isRegularFile(fullPath)) {
                    noteIndexService.indexSingleFile(fullPath, baseDir, kbId);
                    var meta = metaDbService.findByKbAndPath(kbId, relPath);
                    String now = TimeUtil.nowStr();
                    if (meta != null) {
                        meta.setLastModified(Files.getLastModifiedTime(fullPath).toMillis());
                        meta.setFileSize(Files.size(fullPath));
                        meta.setLastIndexedAt(now);
                        metaDbService.updateById(meta);
                    }
                } else {
                    noteIndexService.removeFileFromIndex(kbId, relPath);
                    metaDbService.deleteByKbAndPath(kbId, relPath);
                }
            } catch (Exception e) {
                log.warn("[IndexWatcher] 处理失败: {}", relPath, e);
            }
        }
    }

    public boolean isWatching(Long kbId) { return watchers.containsKey(kbId); }
    public int getWatchedKbCount() { return watchers.size(); }

    private static class WatcherEntry {
        final Long kbId;
        final WatchService watchService;
        final Path baseDir;
        final Map<WatchKey, Path> dirKeys = new ConcurrentHashMap<>();
        WatcherEntry(Long kbId, WatchService ws, Path baseDir) {
            this.kbId = kbId;
            this.watchService = ws;
            this.baseDir = baseDir;
        }
    }
}
