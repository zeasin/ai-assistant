package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.OperationsData.*;
import com.laoqi.assistant.util.FileUtil;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class OperationsService {

    private final AppConfig appConfig;
    private final ConfigService configService;

    public OperationsService(AppConfig appConfig, ConfigService configService) {
        this.appConfig = appConfig;
        this.configService = configService;
    }

    private Path getBaseDir() {
        String baseDir = configService.load().getBaseDir();
        if (baseDir != null && !baseDir.isEmpty()) {
            return Paths.get(baseDir);
        }
        return Paths.get("D:\\projects\\richie_learning_notes");
    }

    private Path dataFile() {
        String path = configService.load().getOperationsDataPath();
        if (path == null || path.isEmpty()) path = "自媒体/运营数据.json";
        return getBaseDir().resolve(path);
    }

    public Root loadData() {
        return FileUtil.readJson(dataFile(), Root.class, new Root());
    }

    public AnalysisResult analyze(Map<String, List<ArticleData>> articles) {
        AnalysisResult result = new AnalysisResult();
        if (articles == null || articles.isEmpty()) return result;

        List<TopArticle> allRanked = new ArrayList<>();
        Map<String, PlatformStats> platformStats = new HashMap<>();

        for (Map.Entry<String, List<ArticleData>> entry : articles.entrySet()) {
            String accountName = entry.getKey();
            for (ArticleData article : entry.getValue()) {
                if (article.data == null) continue;
                for (Map.Entry<String, PlatformArticleData> pEntry : article.data.entrySet()) {
                    String pkey = pEntry.getKey();
                    PlatformArticleData pdata = pEntry.getValue();
                    if (pdata != null && pdata.reads != null && pdata.reads > 0) {
                        TopArticle ta = new TopArticle();
                        ta.account = accountName;
                        ta.topic = article.topic;
                        ta.id = article.id;
                        ta.platform = pkey;
                        ta.reads = pdata.reads;
                        ta.fansAtPublish = article.fansAtPublish;
                        ta.fansGained = article.fansGained;
                        allRanked.add(ta);

                        String key = switch (pkey) { case "wechat" -> "微信"; case "csdn" -> "CSDN"; case "zhihu" -> "知乎"; default -> pkey; };
                        platformStats.computeIfAbsent(key, k -> new PlatformStats());
                        platformStats.get(key).reads += pdata.reads;
                        platformStats.get(key).count++;
                    }
                }
            }
        }

        allRanked.sort((a, b) -> b.reads - a.reads);
        result.topArticles = allRanked.size() > 6 ? allRanked.subList(0, 6) : allRanked;

        result.findings = new ArrayList<>();
        result.platformTips = new ArrayList<>();
        result.suggestionPriority = new ArrayList<>();

        List<ArticleReads> wxArticles = new ArrayList<>();
        for (Map.Entry<String, List<ArticleData>> entry : articles.entrySet()) {
            for (ArticleData a : entry.getValue()) {
                if (a.data != null && a.data.containsKey("wechat") && a.data.get("wechat").reads != null) {
                    wxArticles.add(new ArticleReads(a.topic, a.data.get("wechat").reads));
                }
            }
        }
        wxArticles.sort((a, b) -> b.reads - a.reads);
        if (wxArticles.size() >= 2) {
            String bestTopic = wxArticles.get(0).topic;
            String worstTopic = wxArticles.get(wxArticles.size() - 1).topic;
            int bestReads = wxArticles.get(0).reads;
            int worstReads = wxArticles.get(wxArticles.size() - 1).reads;
            int ratio = worstReads > 0 ? bestReads / worstReads : 0;
            Finding f = new Finding();
            f.icon = "🔍"; f.title = "标题含搜索热词 = 微信阅读量x" + ratio + "倍";
            f.detail = "最佳「" + bestTopic + "」" + bestReads + "阅读 vs 最差「" + worstTopic + "」" + worstReads + "阅读";
            f.action = "每篇标题必含1-2个搜一搜可搜关键词";
            result.findings.add(f);
        }

        List<CsdnWxGap> gaps = new ArrayList<>();
        for (Map.Entry<String, List<ArticleData>> entry : articles.entrySet()) {
            for (ArticleData a : entry.getValue()) {
                if (a.data == null) continue;
                PlatformArticleData w = a.data.get("wechat");
                PlatformArticleData c = a.data.get("csdn");
                if (w != null && c != null && w.reads != null && c.reads != null && w.reads > 0 && c.reads > 0) {
                    gaps.add(new CsdnWxGap(a.topic, c.reads, w.reads, c.reads - w.reads));
                }
            }
        }
        gaps.sort((a, b) -> b.gap - a.gap);
        if (!gaps.isEmpty() && gaps.get(0).gap > 100) {
            CsdnWxGap best = gaps.get(0);
            Finding f = new Finding();
            f.icon = "⚡"; f.title = "CSDN吃冲突感标题";
            f.detail = "「" + best.topic + "」CSDN " + best.csdnReads + "阅读，是微信" + best.wxReads + "阅读的" + (best.wxReads > 0 ? best.csdnReads / best.wxReads : 0) + "倍";
            f.action = "CSDN标题用冲突/悬念句式，可更激进";
            result.findings.add(f);
        }

        List<String> hotKeywords = Arrays.asList("OpenMemory", "AI", "OpenCode", "记忆体");
        for (Map.Entry<String, List<ArticleData>> entry : articles.entrySet()) {
            for (ArticleData a : entry.getValue()) {
                boolean isHot = hotKeywords.stream().anyMatch(kw ->
                        (a.topic != null && a.topic.contains(kw)) ||
                        (a.series != null && a.series.contains(kw)));
                if (!isHot) continue;
                if (a.data != null && a.data.containsKey("wechat") && a.data.get("wechat").reads != null) {
                    Finding f = new Finding();
                    f.icon = "🔥"; f.title = "热门AI概念 = 微信推荐起量";
                    f.detail = "「" + a.topic + "」微信" + a.data.get("wechat").reads + "阅读，蹭热点吃搜一搜+推荐双流量";
                    f.action = "紧跟AI热门概念写文";
                    result.findings.add(f);
                    break;
                }
            }
        }

        result.platformTips.add(createTip("微信·码农老齐", "标题搜索词覆盖不稳定", "每篇检查搜一搜关键词热度，标题含≥1个热词"));
        result.platformTips.add(createTip("CSDN", "已验证冲突标题有效但未持续优化", "标题用「冲突/悬念」句式，内容保持技术深度"));
        result.platformTips.add(createTip("知乎", "只发文章不答题，曝光不足", "每周回答2-3个相关知乎问题，文末引导关注"));

        boolean hasHot = false;
        for (Map.Entry<String, List<ArticleData>> entry : articles.entrySet()) {
            for (ArticleData a : entry.getValue()) {
                if ((a.topic != null && a.topic.contains("AI")) ||
                    (a.series != null && a.series.contains("AI"))) {
                    hasHot = true; break;
                }
            }
            if (hasHot) break;
        }
        if (hasHot) {
            Suggestion s = new Suggestion();
            s.priority = "🔴 高"; s.topic = "AI热门概念实操教程";
            s.reason = "已验证：搜一搜热词+实操=最高流量，建议持续输出";
            result.suggestionPriority.add(s);
        }
        Suggestion s2 = new Suggestion();
        s2.priority = "🟡 中"; s2.topic = "开发效率/避坑经验（冲突感标题）";
        s2.reason = "CSDN已验证冲突标题效果6倍于平淡标题";
        result.suggestionPriority.add(s2);

        WritingTemplate wt = new WritingTemplate();
        wt.formula = "[热门AI概念/工具] + [实操/避坑] + [结果暗示]";
        wt.goodExamples = Arrays.asList("OpenCode安装与使用", "OpenMemory记忆体");
        wt.badExamples = Arrays.asList("子代理系统");
        wt.contentPipeline = "热门概念 → 实操教程 → 避坑经验 → 开源互动 → 产品痛点方案";
        result.writingTemplate = wt;

        return result;
    }

    private PlatformTip createTip(String platform, String problem, String action) {
        PlatformTip t = new PlatformTip();
        t.platform = platform; t.problem = problem; t.action = action;
        return t;
    }

    static class ArticleReads { String topic; int reads; ArticleReads(String t, int r) { topic = t; reads = r; } }
    static class CsdnWxGap { String topic; int csdnReads, wxReads, gap; CsdnWxGap(String t, int c, int w, int g) { topic = t; csdnReads = c; wxReads = w; gap = g; } }
    static class PlatformStats { int reads, count; }
}
