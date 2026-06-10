package com.laoqi.assistant.service;

import com.laoqi.assistant.entity.Memory;

import java.util.List;

public interface IMemoryService {

    /**
     * 写入一条记忆，自动同步 FTS5 全文索引
     *
     * @param content 记忆文本
     * @param source  来源（user / inferred / system）
     * @param tags    标签列表
     */
    void remember(String content, String source, List<String> tags);

    /**
     * BM25 全文检索 Top-K
     *
     * @param query 搜索关键词
     * @param topK  返回条数
     */
    List<Memory> search(String query, int topK);

    /** 按 ID 删除（遗忘） */
    void forget(Integer id);

    /** 获取所有记忆（按时间倒序） */
    List<Memory> listAll();

    /** 记忆总数 */
    long count();
}