package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.KnowledgeBaseEntity;
import com.laoqi.assistant.entity.LlmProfileEntity;
import com.laoqi.assistant.service.*;
import com.laoqi.assistant.service.NoteIndexService.IndexStats;
import com.laoqi.assistant.service.db.MessageDbService;
import com.laoqi.assistant.util.MarkdownUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.stream.Collectors;

@Controller
public class IndexV2Controller {

    private final KnowledgeBaseService kbService;
    private final ReportService reportService;
    private final LlmService llmService;
    private final LlmConfigResolver llmConfigResolver;
    private final NoteIndexService noteIndexService;
    private final MessageDbService messageDbService;

    public IndexV2Controller(KnowledgeBaseService kbService,
                             ReportService reportService,
                             LlmService llmService,
                             LlmConfigResolver llmConfigResolver,
                             NoteIndexService noteIndexService,
                             MessageDbService messageDbService) {
        this.kbService = kbService;
        this.reportService = reportService;
        this.llmService = llmService;
        this.llmConfigResolver = llmConfigResolver;
        this.noteIndexService = noteIndexService;
        this.messageDbService = messageDbService;
    }

    @GetMapping("/v2")
    public String index(@RequestParam(required = false) Long kbId, Model model) {
        // 必须带 kbId，否则重定向
        if (kbId == null) {
            var first = kbService.getFirst();
            if (first != null) {
                return "redirect:/v2?kbId=" + first.getId();
            }
            return "redirect:/v1";
        }

        KnowledgeBaseEntity currentKb = kbService.getById(kbId);
        if (currentKb == null) {
            var first = kbService.getFirst();
            if (first != null) {
                return "redirect:/v2?kbId=" + first.getId();
            }
            return "redirect:/v1";
        }
        model.addAttribute("currentKb", currentKb);
        model.addAttribute("kbId", currentKb.getId());

        // 对话 Tab - 模型列表
        List<LlmProfileEntity> chatModels = llmConfigResolver.getAllProfiles()
                .stream()
                .filter(p -> !LlmProfileEntity.TYPE_EMBEDDING.equals(p.getModelType()))
                .collect(Collectors.toList());
        model.addAttribute("chatModels", chatModels);
        LlmProfileEntity defaultProfile = llmConfigResolver.getDefaultProfile();
        model.addAttribute("defaultModel", defaultProfile != null ? defaultProfile.getName() : "");

        // 分析 Tab - 日报
        if (currentKb != null) {
            String todayReport = reportService.readTodayReport(currentKb.getId());
            if (todayReport != null && !todayReport.isEmpty()) {
                model.addAttribute("report", MarkdownUtil.toHtml(todayReport));
                model.addAttribute("report_time", "今日");
                model.addAttribute("report_error", "");
            } else {
                String latestReport = reportService.readLatestReport(currentKb.getId());
                String latestDate = reportService.getLatestReportDate(currentKb.getId());
                if (latestReport != null && !latestReport.isEmpty()) {
                    model.addAttribute("report", MarkdownUtil.toHtml(latestReport));
                    model.addAttribute("report_time", latestDate != null ? latestDate : "");
                    model.addAttribute("report_error", "");
                } else {
                    model.addAttribute("report", "");
                    model.addAttribute("report_time", "尚未生成");
                    model.addAttribute("report_error", "");
                }
            }

            // 分析 Tab - KB 统计
            try {
                IndexStats stats = noteIndexService.getIndexStats(currentKb.getId());
                model.addAttribute("kbFileCount", stats.fileCount());
                model.addAttribute("kbIndexCount", stats.chunkCount());
            } catch (Exception e) {
                model.addAttribute("kbFileCount", 0);
                model.addAttribute("kbIndexCount", 0);
            }
            try {
                model.addAttribute("kbTotalMessages",
                        messageDbService.countByKb(currentKb.getId().intValue()));
            } catch (Exception e) {
                model.addAttribute("kbTotalMessages", 0);
            }
        }

        return "2.0/index";
    }
}
