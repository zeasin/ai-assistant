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

    public MediaDataCollectorService(AppConfig appConfig, ConfigService configService,
                                      OpenCodeService openCodeService, FeishuService feishuService,
                                      LogService logService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.openCodeService = openCodeService;
        this.feishuService = feishuService;
        this.logService = logService;
    }

    private Path getDataDir() {
        Config config = configService.load();
        String baseDir = config.getBaseDir();
        if (baseDir == null || baseDir.isEmpty()) baseDir = "D:\\projects\\richie_learning_notes";
        String opDir = config.getOperationsDataDir();
        if (opDir == null || opDir.isEmpty()) opDir = "自媒体";
        return Paths.get(baseDir).resolve(opDir).resolve("data");
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

            writeJsonFile("自媒体文章.json", articles);
            writeJsonFile("自媒体账号.json", accounts);

            log.info("[数据采集] 完成: 文章更新={}, 账号更新={}", articleUpdates, accountUpdates);
            logService.add("数据采集", "成功",
                    String.format("文章更新%d条, 账号更新%d条", articleUpdates, accountUpdates));

        } catch (Exception e) {
            log.error("[数据采集] 失败: {}", e.getMessage(), e);
            logService.add("数据采集", "失败", e.getMessage());
        }
    }

    private String searchPlatformData() {
        try {
            String prompt = "使用 webfetch 工具执行以下数据采集任务，不要参考任何记忆文件，只按步骤执行。\n\n"
                    + "步骤1：webfetch https://blog.csdn.net/u011314083 ，从页面提取粉丝数、总浏览量、总文章数，以及所有文章标题（最多10篇）\n\n"
                    + "步骤2：对步骤1找到的每篇文章，webfetch 其URL提取阅读量、点赞数、收藏数、转发数、评论数（如果页面没有则设为null）\n\n"
                    + "重要：只输出下面格式的JSON，不要任何其他文字、不要markdown、不要代码块包裹、不要说明：\n"
                    + "{\"csdn\":{\"account\":{\"fans\":269,\"totalViews\":35355,\"totalArticles\":26},\"articles\":[{\"topic\":\"文章标题\",\"publishDate\":\"2026-05-12\",\"reads\":394,\"likes\":9,\"favorites\":6,\"shares\":null,\"comments\":3}]}}";

            String sessionId = openCodeService.findIdleSession();
            if (sessionId == null) {
                sessionId = openCodeService.createSession("数据采集");
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
        List<Map<String, Object>> list = articles.computeIfAbsent("码农老齐", k -> new ArrayList<>());

        String[] platforms = {"csdn", "zhihu"};
        for (String platform : platforms) {
            Object rawPlatform = parsed.get(platform);
            if (!(rawPlatform instanceof Map)) continue;
            Map<String, Object> platformBlock = (Map<String, Object>) rawPlatform;
            Object rawArticles = platformBlock.get("articles");
            if (!(rawArticles instanceof List)) continue;

            List<Map<String, Object>> aiArticles = (List<Map<String, Object>>) rawArticles;
            for (Map<String, Object> aiArt : aiArticles) {
                String aiTopic = (String) aiArt.get("topic");
                if (aiTopic == null || aiTopic.isBlank()) continue;

                Map<String, Object> existing = findExistingArticle(list, aiTopic);

                if (existing == null) {
                    existing = new LinkedHashMap<>();
                    existing.put("id", "A" + ((System.currentTimeMillis() + nextId++) % 100000));
                    existing.put("topic", aiTopic);
                    existing.put("publishDate", aiArt.getOrDefault("publishDate", TimeUtil.todayStr()));
                    existing.put("series", null);
                    existing.put("fansAtPublish", null);
                    existing.put("fansGained", null);
                    existing.put("notes", null);
                    list.add(existing);
                    updates++;
                }

                Map<String, Object> existingData = (Map<String, Object>) existing.get("data");
                if (existingData == null) {
                    existingData = new LinkedHashMap<>();
                    existing.put("data", existingData);
                }

                Map<String, Object> existingPlatform = (Map<String, Object>) existingData.get(platform);
                if (existingPlatform == null) {
                    existingPlatform = new LinkedHashMap<>();
                    existingData.put(platform, existingPlatform);
                }

                boolean changed = false;
                String[] fields = {"reads", "likes", "favorites", "shares", "comments"};
                for (String f : fields) {
                    Object val = aiArt.get(f);
                    if (val != null) {
                        existingPlatform.put(f, val);
                        changed = true;
                    }
                }
                // 更新文章发布时间（如果有）
                Object publishDate = aiArt.get("publishDate");
                if (publishDate != null && existing.get("publishDate") == null) {
                    existing.put("publishDate", publishDate);
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
            String t = (String) art.get("topic");
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
                if (!platform.equals(existing.get("platform"))) continue;
                boolean changed = false;
                for (var field : aiAcc.entrySet()) {
                    if (field.getValue() != null) {
                        existing.put(field.getKey(), field.getValue());
                        changed = true;
                    }
                }
                if (changed) {
                    existing.put("updated", TimeUtil.todayStr());
                    updates++;
                }
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
        paragraphs.add(List.of(Map.of("tag", "text", "text", "📊 老齐，今天的公众号数据来一下？")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "回复格式：")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "码农老齐 粉丝143 阅读128 新增粉丝5")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "启航电商ERP 粉丝3738 阅读90")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "老齐二三事 粉丝0 阅读128")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")));

        for (var entry : existingArticles.entrySet()) {
            List<Map<String, Object>> list = entry.getValue();
            if (list == null || list.isEmpty()) continue;
            Map<String, Object> last = list.get(list.size() - 1);
            String topic = (String) last.getOrDefault("topic", "");
            String date = (String) last.getOrDefault("publishDate", "");
            paragraphs.add(List.of(Map.of("tag", "text", "text",
                    "· " + entry.getKey() + " 最新: " + topic + " (" + date + ")")));
        }

        paragraphs.add(List.of(Map.of("tag", "text", "text", "━━━━━━━━━━━━━━━━━━")));
        paragraphs.add(List.of(Map.of("tag", "text", "text", "直接回复我，我会自动存入运营数据 📝")));

        feishuService.sendPost("📊 公众号数据 · " + today + " · " + wd, paragraphs);
        log.info("[数据采集] 已发送公众号数据请求到飞书");
        logService.add("数据采集", "已请求", "已发送飞书数据请求");
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
}
