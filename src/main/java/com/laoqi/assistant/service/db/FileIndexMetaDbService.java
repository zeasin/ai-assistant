package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.FileIndexMetaEntity;
import com.laoqi.assistant.mapper.FileIndexMetaMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FileIndexMetaDbService extends ServiceImpl<FileIndexMetaMapper, FileIndexMetaEntity> {

    private static final Logger log = LoggerFactory.getLogger(FileIndexMetaDbService.class);

    private final DataSource dataSource;

    public FileIndexMetaDbService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 应用启动时自动建表 */
    @PostConstruct
    public void init() {
        createTable();
    }

    private void createTable() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_index_meta (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    kb_id           INTEGER NOT NULL,
                    file_path       TEXT NOT NULL,
                    last_modified   INTEGER NOT NULL DEFAULT 0,
                    file_size       INTEGER NOT NULL DEFAULT 0,
                    content_hash    TEXT,
                    last_indexed_at TEXT,
                    created_at      TEXT
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_meta_kb ON file_index_meta(kb_id)");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_file_meta_kb_path ON file_index_meta(kb_id, file_path)");
            log.info("[FileIndexMeta] 数据库表已就绪: file_index_meta");
        } catch (Exception e) {
            log.warn("[FileIndexMeta] 建表失败（可能已存在）: {}", e.getMessage());
        }
    }

    /**
     * 获取指定知识库的所有文件元数据，以 filePath 为 key 的 Map
     */
    public Map<String, FileIndexMetaEntity> getMapByKb(Long kbId) {
        List<FileIndexMetaEntity> list = lambdaQuery()
                .eq(FileIndexMetaEntity::getKbId, kbId)
                .list();
        return list.stream()
                .collect(Collectors.toMap(FileIndexMetaEntity::getFilePath, e -> e, (a, b) -> b));
    }

    /**
     * 获取指定知识库的文件路径集合
     */
    public java.util.Set<String> getFilePathSetByKb(Long kbId) {
        List<FileIndexMetaEntity> list = lambdaQuery()
                .eq(FileIndexMetaEntity::getKbId, kbId)
                .select(FileIndexMetaEntity::getFilePath)
                .list();
        return list.stream()
                .map(FileIndexMetaEntity::getFilePath)
                .collect(Collectors.toSet());
    }

    /**
     * 根据 kbId 和 filePath 删除
     */
    public void deleteByKbAndPath(Long kbId, String filePath) {
        lambdaUpdate()
                .eq(FileIndexMetaEntity::getKbId, kbId)
                .eq(FileIndexMetaEntity::getFilePath, filePath)
                .remove();
    }

    /**
     * 删除知识库的所有元数据
     */
    public void deleteByKb(Long kbId) {
        lambdaUpdate()
                .eq(FileIndexMetaEntity::getKbId, kbId)
                .remove();
    }

    /**
     * 根据 kbId 和 filePath 查找
     */
    public FileIndexMetaEntity findByKbAndPath(Long kbId, String filePath) {
        return lambdaQuery()
                .eq(FileIndexMetaEntity::getKbId, kbId)
                .eq(FileIndexMetaEntity::getFilePath, filePath)
                .one();
    }
}
