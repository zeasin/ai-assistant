package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.laoqi.assistant.entity.AiAnalysisEntity;

public interface AiAnalysisDbService extends IService<AiAnalysisEntity> {

    /** 获取指定知识库当天的日报 */
    AiAnalysisEntity getTodayReport(Long kbId);

    /** 获取指定知识库最新的日报 */
    AiAnalysisEntity getLatestReport(Long kbId);

    /** 获取指定知识库最新的日报日期 */
    String getLatestReportDate(Long kbId);

    /** 获取指定知识库的日报提示词 */
    AiAnalysisEntity getDailyReportPrompt(Long kbId);

    /** 保存日报提示词 (存在则更新) */
    void saveDailyReportPrompt(Long kbId, String content);

    /** 获取指定知识库的目录分析提示词 */
    AiAnalysisEntity getDirAnalysisPrompt(Long kbId);

    /** 保存目录分析提示词 (存在则更新) */
    void saveDirAnalysisPrompt(Long kbId, String content);

    /** 清理指定知识库N天前的日报 */
    void cleanOldReports(Long kbId, int keepDays);
}
