package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MediaDataCollectorService {

    private static final Logger log = LoggerFactory.getLogger(MediaDataCollectorService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<Map<String, List<Map<String, Object>>>> MAP_LIST_TYPE = new TypeReference<>() {};

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final OpenCodeService openCodeService;
    private final FeishuService feishuService;
    private final LogService logService;
    private final PromptService promptService;

    public MediaDataCollectorService(AppConfig appConfig, ConfigService configService,
                                      OpenCodeService openCodeService, FeishuService feishuService,
                                      LogService logService, PromptService promptService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.feishuService = feishuService;
        this.logService = logService;
        this.promptService = promptService;
    }

    private Path getDataDir() {
        Config config = configService.load();
        String opDir = config.getOperationsDataDir();
        if (opDir == null || opDir.isEmpty()) opDir = "自媒体";
        return Paths.get(configService.getBaseDir()).resolve(opDir).resolve("data");
    }

    private Map<String, List<Map<String, Object>>> readJsonFile(String fileName) {
        Path file = getDataDir().resolve(fileName);
        return FileUtil.readJson(file, MAP_LIST_TYPE, new LinkedHashMap<>());
    }

    private void writeJsonFile(String fileName, Map<String, List<Map<String, Object>>> data) {
        Path file = getDataDir().resolve(fileName);
        FileUtil.writeJson(file, data);
    }

    public void collect() {
        log.info("[数据采集] 开始抓取 CSDN/知乎公开数据...");
        try {
            if (!openCodeService.isHealthy()) {
                log.warn("[数据采集] opencode serve 未启动，跳过");
                logService.add("数据采集", "跳过", "opencode serve 未启动");
                return;
            }

            var articles = readJsonFile("自媒体文章.json");
            var accounts = readJsonFile("自媒体账号.json");

            String aiResult = searchPlatformData();
            Map<String, Object> parsed = parseAiResponse(aiResult);
            if (parsed == null || parsed.isEmpty()) {
                log.warn("[数据采集] AI 未返回数据");
                logService.add("数据采集", "完成", "AI未返回数据");
                return;
            }

            int articleUpdates = mergeArticleData(articles, parsed);
            int accountUpdates = mergeAccountsDirect(accounts, parsed);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("_lastCollectTime", TimeUtil.nowStr());
            meta.put("_lastCollectResult", String.format("文章更新%d条, 账号更新%d条", articleUpdates, accountUpdates));
            accounts.put("_meta", List.of(meta));

            writeJsonFile("自媒体文章.json", articles);
            writeJsonFile("自媒体账号.json", accounts);

            log.info("[数据采集] 完成: 文章更新={}, 账号更新={}", articleUpdates, accountUpdates);
            logService.add("数据采集", "成功",
                    String.format("文章更新%d条, 账号更新%d条", articleUpdates, accountUpdates));

            // 发送数据采集完成的飞书通知
            String today = TimeUtil.todayStr();
            String wd = TimeUtil.weekdayCn(TimeUtil.now());
            List<List<Map<String, String>>> paragraphs = new ArrayList<>();
            paragraphs.add(List.of(Map.of("tag", "text", "text", "✅ CSDN/知乎数据采集完成！")));
            paragraphs.add(List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")));
            paragraphs.add(List.of(Map.of("tag", "text", "text", "文章更新：" + articleUpdates + " 条")));
            paragraphs.add(List.of(Map.of("tag", "text", "text", "账号更新：" + accountUpdates + " 条")));
            paragraphs.add(List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")));
            paragraphs.add(List.of(Map.of("tag", "text", "text", "采集时间：" + TimeUtil.nowStr())));
            feishuService.sendPost("📊 数据采集完成 · " + today + " · " + wd, paragraphs);
            log.info("[数据采集] 已发送采集完成通知到飞书");

        } catch (Exception e) {
            log.error("[数据采集] 失败: {}", e.getMessage(), e);
            logService.add("数据采集", "失败", e.getMessage());
        }
    }

    private String searchPlatformData() {
        try {
            String prompt = promptService.getTemplate("csdn-collect");

            String sessionId = openCodeService.findIdleSession();
            if (sessionId == null) {
                sessionId = openCodeService.createSession(promptService.getSessionTitle("csdn-collect"));
            }
            return openCodeService.sendMessage(sessionId, prompt);

        } catch (Exception e) {
            log.error("[数据采集] AI搜索失败: {}", e.getMessage(), e);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAiResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) return Map.of();
        try {
            Matcher m = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```").matcher(aiResponse);
            String json;
            if (m.find()) {
                json = m.group(1).trim();
            } else {
                int start = aiResponse.indexOf('{');
                int end = aiResponse.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    json = aiResponse.substring(start, end + 1);
                } else {
                    return Map.of();
                }
            }
            return mapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            log.warn("[数据采集] 解析AI响应失败: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private int mergeArticleData(Map<String, List<Map<String, Object>>> articles, Map<String, Object> parsed) {
        int updates = 0;
        int nextId = 1;

        String[] platforms = {"csdn", "zhihu"};
        for (String platform : platforms) {
            Object rawPlatform = parsed.get(platform);
            if (!(rawPlatform instanceof Map)) continue;
            Map<String, Object> platformBlock = (Map<String, Object>) rawPlatform;
            Object rawArticles = platformBlock.get("articles");
            if (!(rawArticles instanceof List)) continue;

            String groupKey = "码农老齐-" + platform;
            List<Map<String, Object>> list = articles.computeIfAbsent(groupKey, k -> new ArrayList<>());

            List<Map<String, Object>> aiArticles = (List<Map<String, Object>>) rawArticles;
            for (Map<String, Object> aiArt : aiArticles) {
                String aiTopic = (String) aiArt.get("topic");
                if (aiTopic == null || aiTopic.isBlank()) continue;

                Map<String, Object> existing = findExistingArticle(list, aiTopic);

                if (existing == null) {
                    existing = new LinkedHashMap<>();
                    existing.put("文章id", "A" + ((System.currentTimeMillis() + nextId++) % 100000));
                    existing.put("文章名", aiTopic);
                    existing.put("日期", aiArt.getOrDefault("publishDate", TimeUtil.todayStr()));
                    list.add(existing);
                    updates++;
                }

                boolean changed = false;
                Object reads = aiArt.get("reads");
                Object likes = aiArt.get("likes");
                Object favorites = aiArt.get("favorites");
                Object shares = aiArt.get("shares");
                Object comments = aiArt.get("comments");

                if (reads != null) { existing.put("阅读", reads); changed = true; }
                if (likes != null) { existing.put("点赞", likes); changed = true; }
                if (comments != null) { existing.put("评论", comments); changed = true; }
                // Combine shares + favorites into 转发收藏
                int shareFav = 0;
                if (shares != null) shareFav += ((Number) shares).intValue();
                if (favorites != null) shareFav += ((Number) favorites).intValue();
                if (shareFav > 0) { existing.put("转发收藏", shareFav); changed = true; }

                Object publishDate = aiArt.get("publishDate");
                if (publishDate != null && existing.get("日期") == null) {
                    existing.put("日期", publishDate);
                }
                if (changed) updates++;
            }
        }
        return updates;
    }

    private Map<String, Object> findExistingArticle(List<Map<String, Object>> list, String topic) {
        String norm = topic.replaceAll("[\\s　,，、：:。.；;！!？?（）()\\[\\]【】「」『》》]|^【.*?】", "").toLowerCase();
        Map<String, Object> bestMatch = null;
        int bestScore = 0;
        for (Map<String, Object> art : list) {
            String t = (String) art.get("文章名");
            if (t == null) continue;
            String tNorm = t.replaceAll("[\\s　,，、：:。.；;！!？?（）()\\[\\]【】「」『》》]|^【.*?】", "").toLowerCase();
            if (tNorm.isEmpty()) continue;
            if (norm.contains(tNorm) || tNorm.contains(norm)) {
                int score = Math.min(norm.length(), tNorm.length());
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = art;
                }
            }
        }
        return bestMatch;
    }

    @SuppressWarnings("unchecked")
    private int mergeAccountsDirect(Map<String, List<Map<String, Object>>> accounts, Map<String, Object> parsed) {
        List<Map<String, Object>> existingList = accounts.get("码农老齐");
        if (existingList == null) return 0;

        int updates = 0;
        String[] platforms = {"csdn", "zhihu"};
        for (String platform : platforms) {
            Object rawPlatform = parsed.get(platform);
            if (!(rawPlatform instanceof Map)) continue;
            Map<String, Object> platformBlock = (Map<String, Object>) rawPlatform;
            Object rawAccount = platformBlock.get("account");
            if (!(rawAccount instanceof Map)) continue;
            Map<String, Object> aiAcc = (Map<String, Object>) rawAccount;

            for (Map<String, Object> existing : existingList) {
                if (!platform.equals(existing.get("平台"))) continue;
                // Map AI response fields to Chinese keys
                if (aiAcc.get("fans") != null) existing.put("粉丝", aiAcc.get("fans"));
                if (aiAcc.get("totalViews") != null) existing.put("总访问", aiAcc.get("totalViews"));
                if (aiAcc.get("totalArticles") != null) existing.put("文章数", aiAcc.get("totalArticles"));
                existing.put("更新日期", TimeUtil.todayStr());
                updates++;
                break;
            }
        }
        return updates;
    }

    public void sendWechatDataRequest() {
        String today = TimeUtil.todayStr();
        String wd = TimeUtil.weekdayCn(TimeUtil.now());

        var existingArticles = readJsonFile("自媒体文章.json");

        List<List<Map<String, String>>> paragraphs = new ArrayList<>();
        paragraphs.add(List.of(Map.of("tag", "text", "text", "码农老齐 粉丝- 阅读- 分享收藏- 主页- 消息- 搜一搜- 聊天会话- 其他- 朋友圈- 推荐-")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "启航电商ERP 粉丝- 阅读- 分享收藏- 主页- 消息- 搜一搜- 聊天会话- 其他- 朋友圈- 推荐-")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "老齐二三事 粉丝- 阅读- 分享收藏- 主页- 消息- 搜一搜- 聊天会话- 其他- 朋友圈- 推荐-")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")));

        List<String> targetAccounts = List.of("码农老齐", "启航电商ERP", "老齐二三事");
        for (String account : targetAccounts) {
            paragraphs.add(List.of(Map.of("tag", "text", "text", "📝 " + account + " 文章数据：")));
            boolean found = false;
            String wechatKey = account + "-微信";
            if (existingArticles.containsKey(account)) {
                found = addArticles(paragraphs, existingArticles.get(account));
            } else if (existingArticles.containsKey(wechatKey)) {
                found = addArticles(paragraphs, existingArticles.get(wechatKey));
            }
            if (!found) {
                paragraphs.add(List.of(Map.of("tag", "text", "text", "  暂无文章数据")));
            }
        }

        feishuService.sendPost("📊 公众号数据 · " + today + " · " + wd, paragraphs);
        log.info("[数据采集] 已发送公众号数据请求到飞书");
        logService.add("数据采集", "已请求", "已发送飞书数据请求");
    }

    private boolean addArticles(List<List<Map<String, String>>> paragraphs, List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) return false;
        int count = 0;
        for (int i = list.size() - 1; i >= 0 && count < 5; i--) {
            Map<String, Object> article = list.get(i);
            String id = (String) article.getOrDefault("文章id", "");
            String title = (String) article.getOrDefault("文章名", "");
            if (title != null && !title.isBlank()) {
                String reads = article.get("阅读") != null ? article.get("阅读").toString() : "-";
                String likes = article.get("点赞") != null ? article.get("点赞").toString() : "-";
                String shareFav = article.get("转发收藏") != null ? article.get("转发收藏").toString() : "0";
                String recommend = article.get("推荐") != null ? article.get("推荐").toString() : "-";
                String comments = article.get("评论") != null ? article.get("评论").toString() : "-";
                paragraphs.add(List.of(Map.of("tag", "text", "text", "  [" + id + "] " + title + " 阅读:" + reads + " 点赞:" + likes + " 转发收藏:" + shareFav + " 推荐:" + recommend + " 评论:" + comments)));
                count++;
            }
        }
        return count > 0;
    }

    public Map<String, Object> requestSync() {
        sendWechatDataRequest();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("time", TimeUtil.nowStr());
        return result;
    }

    public Map<String, Object> dryRun() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (!openCodeService.isHealthy()) {
                result.put("error", "opencode serve 未启动");
                return result;
            }
            var articles = readJsonFile("自媒体文章.json");
            if (articles.isEmpty()) {
                result.put("error", "没有文章数据");
                return result;
            }
            String aiResult = searchPlatformData();
            result.put("rawResponse", aiResult);

            Map<String, Object> parsed = parseAiResponse(aiResult);
            result.put("parsed", parsed);
            result.put("ok", true);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> collectSync() {
        collect();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("time", TimeUtil.nowStr());
        return result;
    }

    public String getLastCollectTime() {
        try {
            var accounts = readJsonFile("自媒体账号.json");
            var meta = accounts.get("_meta");
            if (meta != null && !meta.isEmpty()) {
                var m = meta.get(0);
                if (m instanceof Map) {
                    Object time = ((Map<?, ?>) m).get("_lastCollectTime");
                    if (time != null) return time.toString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
