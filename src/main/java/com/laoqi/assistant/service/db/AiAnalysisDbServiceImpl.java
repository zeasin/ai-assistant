package com.laoqi.assistant.service.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.laoqi.assistant.entity.AiAnalysisEntity;
import com.laoqi.assistant.mapper.AiAnalysisMapper;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisDbServiceImpl extends ServiceImpl<AiAnalysisMapper, AiAnalysisEntity> implements AiAnalysisDbService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisDbServiceImpl.class);

    private final AiAnalysisMapper mapper;

    public AiAnalysisDbServiceImpl(AiAnalysisMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AiAnalysisEntity getTodayReport(Long kbId) {
        String today = TimeUtil.todayStr();
        LambdaQueryWrapper<AiAnalysisEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAnalysisEntity::getKbId, kbId)
               .eq(AiAnalysisEntity::getType, "daily_report")
               .eq(AiAnalysisEntity::getReportDate, today)
               .orderByDesc(AiAnalysisEntity::getCreatedAt)
               .last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }

    @Override
    public AiAnalysisEntity getLatestReport(Long kbId) {
        LambdaQueryWrapper<AiAnalysisEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAnalysisEntity::getKbId, kbId)
               .eq(AiAnalysisEntity::getType, "daily_report")
               .orderByDesc(AiAnalysisEntity::getReportDate)
               .orderByDesc(AiAnalysisEntity::getCreatedAt)
               .last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }

    @Override
    public String getLatestReportDate(Long kbId) {
        AiAnalysisEntity entity = getLatestReport(kbId);
        return entity != null ? entity.getReportDate() : null;
    }

    @Override
    public AiAnalysisEntity getDailyReportPrompt(Long kbId) {
        LambdaQueryWrapper<AiAnalysisEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAnalysisEntity::getKbId, kbId)
               .eq(AiAnalysisEntity::getType, "daily_report_prompt")
               .last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }

    @Override
    public void saveDailyReportPrompt(Long kbId, String content) {
        AiAnalysisEntity existing = getDailyReportPrompt(kbId);
        String now = TimeUtil.nowStr();
        if (existing != null) {
            existing.setContent(content);
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
        } else {
            AiAnalysisEntity entity = new AiAnalysisEntity();
            entity.setKbId(kbId);
            entity.setType("daily_report_prompt");
            entity.setContent(content);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);
        }
    }

    @Override
    public AiAnalysisEntity getDirAnalysisPrompt(Long kbId) {
        LambdaQueryWrapper<AiAnalysisEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAnalysisEntity::getKbId, kbId)
               .eq(AiAnalysisEntity::getType, "dir_analysis_prompt")
               .last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }

    @Override
    public void saveDirAnalysisPrompt(Long kbId, String content) {
        AiAnalysisEntity existing = getDirAnalysisPrompt(kbId);
        String now = TimeUtil.nowStr();
        if (existing != null) {
            existing.setContent(content);
            existing.setUpdatedAt(now);
            mapper.updateById(existing);
        } else {
            AiAnalysisEntity entity = new AiAnalysisEntity();
            entity.setKbId(kbId);
            entity.setType("dir_analysis_prompt");
            entity.setContent(content);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);
        }
    }

    @Override
    public void cleanOldReports(Long kbId, int keepDays) {
        String cutoffDate = TimeUtil.now().minusDays(keepDays).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LambdaQueryWrapper<AiAnalysisEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAnalysisEntity::getKbId, kbId)
               .eq(AiAnalysisEntity::getType, "daily_report")
               .lt(AiAnalysisEntity::getReportDate, cutoffDate);
        int deleted = mapper.delete(wrapper);
        if (deleted > 0) {
            log.info("已清理 {} 天前的日报, kbId={}, cutoff={}", deleted, kbId, cutoffDate);
        }
    }
}
