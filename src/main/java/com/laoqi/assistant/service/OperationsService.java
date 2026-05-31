package com.laoqi.assistant.service;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.OperationsData.*;
import com.laoqi.assistant.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OperationsService {

    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final OpenCodeService openCodeService;

    // AI analysis cache
    private String cachedAnalysis = "";
    private String cachedDate = "";

    public OperationsService(AppConfig appConfig, ConfigService configService, OpenCodeService openCodeService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.openCodeService = openCodeService;
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

    /**
     * Build a text summary of the operations data for use as AI prompt context
     */
    public String buildDataSummary(Root data) {
        StringBuilder sb = new StringBuilder();

        // Account summary
        if (data.accounts != null && !data.accounts.isEmpty()) {
            sb.append("## 账号概况\n\n");
            for (var accEntry : data.accounts.entrySet()) {
                sb.append("账号：").append(accEntry.getKey()).append("\n");
                for (var platEntry : accEntry.getValue().entrySet()) {
                    sb.append("  - 平台：").append(platEntry.getKey());
                    if (platEntry.getValue() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> info = (Map<String, Object>) platEntry.getValue();
                        if (info.get("name") != null) sb.append("，名称：").append(info.get("name"));
                        if (info.get("fans") != null) sb.append("，粉丝：").append(info.get("fans"));
                        if (info.get("totalViews") != null) sb.append("，总访问：").append(info.get("totalViews"));
                        if (info.get("totalArticles") != null) sb.append("，总文章：").append(info.get("totalArticles"));
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }

        // Recent articles
        if (data.articles != null && !data.articles.isEmpty()) {
            sb.append("## 近期文章\n\n");
            for (var artEntry : data.articles.entrySet()) {
                sb.append("账号：").append(artEntry.getKey()).append("\n");
                List<ArticleData> articles = artEntry.getValue();
                if (articles != null) {
                    for (ArticleData a : articles) {
                        sb.append("  - [").append(a.id).append("] ").append(a.topic);
                        if (a.series != null) sb.append(" (系列：").append(a.series).append(")");
                        if (a.publishDate != null) sb.append(" 发布于：").append(a.publishDate);
                        sb.append("\n");
                        if (a.fansGained != null) sb.append("    增粉：").append(a.fansGained);
                        if (a.notes != null) sb.append(" 备注：").append(a.notes);
                        sb.append("\n");
                        if (a.data != null) {
                            for (var pEntry : a.data.entrySet()) {
                                PlatformArticleData pd = pEntry.getValue();
                                if (pd != null) {
                                    sb.append("    ").append(pEntry.getKey()).append("：");
                                    if (pd.reads != null) sb.append("阅读").append(pd.reads).append(" ");
                                    if (pd.likes != null) sb.append("点赞").append(pd.likes).append(" ");
                                    if (pd.shares != null) sb.append("分享").append(pd.shares).append(" ");
                                    if (pd.favorites != null) sb.append("收藏").append(pd.favorites);
                                    sb.append("\n");
                                }
                            }
                        }
                    }
                }
                sb.append("\n");
            }
        }

        // Daily stats summary
        if (data.dailyStats != null && !data.dailyStats.isEmpty()) {
            sb.append("## 每日统计\n\n");
            for (var statsEntry : data.dailyStats.entrySet()) {
                sb.append("账号：").append(statsEntry.getKey()).append("\n");
                List<DailyStats> stats = statsEntry.getValue();
                if (stats != null) {
                    // Only include recent 7 days
                    int count = Math.min(stats.size(), 7);
                    for (int i = stats.size() - count; i < stats.size(); i++) {
                        DailyStats d = stats.get(i);
                        if (d != null) {
                            sb.append("  - ").append(d.date);
                            if (d.fans != null) sb.append(" 粉丝：").append(d.fans);
                            if (d.reads != null) sb.append(" 阅读：").append(d.reads);
                            if (d.readArticles != null) sb.append(" 文章：").append(d.readArticles);
                            sb.append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Get cached AI analysis if already generated today, or null if not cached
     */
    public String getCachedAnalysis() {
        String today = com.laoqi.assistant.util.TimeUtil.todayStr();
        if (today.equals(cachedDate) && !cachedAnalysis.isEmpty()) {
            return cachedAnalysis;
        }
        return null;
    }

    /**
     * Use AI to analyze operations data and return insights.
     * Returns cached result if available today, unless force=true.
     */
    public String aiAnalyze(boolean force) {
        if (!force) {
            String cached = getCachedAnalysis();
            if (cached != null) {
                log.debug("[AI分析] 使用缓存的运营分析结果");
                return cached;
            }
        }

        Root data = loadData();
        String dataSummary = buildDataSummary(data);

        String prompt = "你是一个自媒体运营分析专家。以下是我的自媒体运营数据：\n\n"
                + dataSummary
                + "\n请根据以上数据，生成一份运营分析报告，包含：\n"
                + "1. 【数据概览】整体运营状况一句话总结\n"
                + "2. 【各平台表现】每个平台的关键指标和变化趋势\n"
                + "3. 【文章表现】表现最好和最差的文章分析\n"
                + "4. 【发现问题】数据中反映出的问题\n"
                + "5. 【改进建议】具体的改进措施建议\n\n"
                + "请使用简洁的中文，适当使用小标题和列表，便于阅读。";

        try {
            if (!openCodeService.isHealthy()) {
                return "⚠️ opencode serve 未启动，无法进行 AI 分析。请确保 opencode serve --port " + appConfig.getNotesPort() + " 已运行。";
            }

            String sessionId = openCodeService.createSession("运营分析");

            String result = openCodeService.sendMessage(sessionId, prompt);
            // Cache successful result with today's date
            cachedAnalysis = result;
            cachedDate = com.laoqi.assistant.util.TimeUtil.todayStr();
            return result;
        } catch (Exception e) {
            log.error("AI 运营分析失败", e);
            return "❌ AI 分析失败：" + e.getMessage();
        }
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

    public void saveData(Root data) {
        FileUtil.writeJson(dataFile(), data);
    }

    public void addArticle(String accountName, String id, String topic, String series, String publishDate,
                            String fansAtPublish, String fansGained, String notes,
                            Map<String, PlatformArticleData> platformData) {
        Root data = loadData();
        if (data.articles == null) data.articles = new LinkedHashMap<>();
        data.articles.computeIfAbsent(accountName, k -> new ArrayList<>());

        ArticleData article = new ArticleData();
        article.id = id;
        article.topic = topic;
        article.series = series;
        article.publishDate = publishDate;
        article.fansAtPublish = fansAtPublish;
        article.fansGained = fansGained;
        article.notes = notes;
        article.data = platformData;
        data.articles.get(accountName).add(article);
        saveData(data);
    }

    public void addDailyStats(String accountName, DailyStats stats) {
        Root data = loadData();
        if (data.dailyStats == null) data.dailyStats = new LinkedHashMap<>();
        data.dailyStats.computeIfAbsent(accountName, k -> new ArrayList<>());
        data.dailyStats.get(accountName).add(stats);
        saveData(data);
    }

    public void saveAccount(String accountName, String platformKey, String name, Integer fans,
                             Integer totalViews, Integer totalArticles) {
        Root data = loadData();
        if (data.accounts == null) data.accounts = new LinkedHashMap<>();
        data.accounts.computeIfAbsent(accountName, k -> new LinkedHashMap<>());

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        if (fans != null) info.put("fans", fans);
        if (totalViews != null) info.put("totalViews", totalViews);
        if (totalArticles != null) info.put("totalArticles", totalArticles);
        data.accounts.get(accountName).put(platformKey, info);
        saveData(data);
    }
}
