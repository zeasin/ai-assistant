package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.model.Config;
import com.laoqi.assistant.util.FileUtil;
import com.laoqi.assistant.util.TimeUtil;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class MediaDataCollectorService {

    private static final Logger log = LoggerFactory.getLogger(MediaDataCollectorService.class);
    private static final TypeReference<Map<String, List<Map<String, Object>>>> MAP_LIST_TYPE = new TypeReference<>() {};

    private final AppConfig appConfig;
    private final ConfigService configService;
    private final FeishuService feishuService;
    private final LogService logService;

    public MediaDataCollectorService(AppConfig appConfig, ConfigService configService,
                                      FeishuService feishuService, LogService logService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.feishuService = feishuService;
        this.logService = logService;
    }

    private Path getDataDir() {
        Config config = configService.load();
        String baseDir = config.getBaseDir();
        if (baseDir == null || baseDir.isEmpty()) {
            throw new IllegalStateException("笔记库根目录未配置");
        }
        return Paths.get(baseDir).resolve("自媒体").resolve("data");
    }

    private Map<String, List<Map<String, Object>>> readJsonFile(String fileName) {
        Path file = getDataDir().resolve(fileName);
        return FileUtil.readJson(file, MAP_LIST_TYPE, new LinkedHashMap<>());
    }

    private void writeJsonFile(String fileName, Map<String, List<Map<String, Object>>> data) {
        Path file = getDataDir().resolve(fileName);
        FileUtil.writeJson(file, data);
    }

    public Map<String, Object> importWechatExcel(InputStream inputStream, String account) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (account == null || account.isBlank()) {
            result.put("ok", false);
            result.put("error", "请选择账号");
            return result;
        }
        try (HSSFWorkbook wb = new HSSFWorkbook(inputStream)) {
            Sheet sheet = wb.getSheetAt(0);
            // date → { channel → value }
            Map<String, Map<String, Double>> dailyChannelData = new LinkedHashMap<>();
            Map<String, Double> dailyShares = new LinkedHashMap<>();
            Map<String, Double> dailyFavorites = new LinkedHashMap<>();
            Map<String, Map<String, Object>> articleMap = new LinkedHashMap<>();

            for (int r = 3; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String date = cellStr(row, 1);
                String channel = cellStr(row, 2);
                String readersStr = cellStr(row, 3);

                if (channel != null && !channel.isEmpty() && !readersStr.isEmpty()) {
                    dailyChannelData
                        .computeIfAbsent(date, k -> new LinkedHashMap<>())
                        .put(channel, parseDouble(readersStr));
                }

                String shareDate = cellStr(row, 5);
                if (!shareDate.isEmpty()) {
                    String shares = cellStr(row, 6);
                    String favs = cellStr(row, 8);
                    if (!shares.isEmpty()) dailyShares.put(shareDate, parseDouble(shares));
                    if (!favs.isEmpty()) dailyFavorites.put(shareDate, parseDouble(favs));
                }

                String articleTitle = cellStr(row, 13);
                String artReaders = cellStr(row, 14);
                if (!articleTitle.isEmpty() && !artReaders.isEmpty()) {
                    Map<String, Object> existing = articleMap.get(articleTitle);
                    if (existing == null) {
                        existing = new LinkedHashMap<>();
                        existing.put("文章名", articleTitle);
                        existing.put("日期", fmtDate(cellStr(row, 12)));
                        existing.put("阅读", 0.0);
                        articleMap.put(articleTitle, existing);
                    }
                    if (existing.get("阅读") instanceof Double) {
                        existing.put("阅读", (Double) existing.get("阅读") + parseDouble(artReaders));
                    }
                    if (existing.get("日期") == null || ((String) existing.get("日期")).isEmpty()) {
                        existing.put("日期", fmtDate(cellStr(row, 12)));
                    }
                }
            }

            int nextId = 1;
            var articles = readJsonFile("自媒体文章.json");
            String groupKey = account + "-微信";
            List<Map<String, Object>> list = articles.computeIfAbsent(groupKey, k -> new ArrayList<>());
            int articleUpdates = 0;
            for (Map<String, Object> art : articleMap.values()) {
                String title = (String) art.get("文章名");
                Map<String, Object> existing = findExistingArticle(list, title);
                if (existing == null) {
                    existing = new LinkedHashMap<>();
                    existing.put("文章id", "A" + ((System.currentTimeMillis() + nextId++) % 100000));
                    existing.put("文章名", title);
                    existing.put("日期", art.get("日期"));
                    list.add(existing);
                    articleUpdates++;
                }
                existing.put("阅读", ((Double) art.get("阅读")).intValue());
                if (art.get("日期") != null && !((String) art.get("日期")).isEmpty()) {
                    existing.put("日期", art.get("日期"));
                }
                articleUpdates++;
            }

            // Excel channel names → JSON field names
            Map<String, String> channelFieldMap = Map.of(
                "全部", "阅读",
                "推荐", "推荐",
                "搜一搜", "搜一搜",
                "主页", "主页",
                "消息", "消息",
                "聊天会话", "聊天会话",
                "朋友圈", "朋友圈",
                "其他", "其他"
            );

            var dailyStats = readJsonFile("自媒体日数据.json");
            List<Map<String, Object>> statsList = dailyStats.computeIfAbsent(account, k -> new ArrayList<>());
            int statsUpdates = 0;
            for (Map.Entry<String, Map<String, Double>> e : dailyChannelData.entrySet()) {
                String date = e.getKey();
                Map<String, Double> channelValues = e.getValue();
                Map<String, Object> existing = findDailyStat(statsList, date);
                if (existing == null) {
                    existing = new LinkedHashMap<>();
                    existing.put("日期", date);
                    statsList.add(existing);
                }
                for (Map.Entry<String, String> mapping : channelFieldMap.entrySet()) {
                    Double val = channelValues.get(mapping.getKey());
                    if (val != null) {
                        existing.put(mapping.getValue(), val);
                    }
                }
                Double shares = dailyShares.get(date);
                Double favs = dailyFavorites.get(date);
                if (shares != null || favs != null) {
                    existing.put("分享收藏", (int) ((shares != null ? shares : 0) + (favs != null ? favs : 0)));
                }
                statsUpdates++;
            }

            var accounts = readJsonFile("自媒体账号.json");
            List<Map<String, Object>> accountList = accounts.computeIfAbsent(account, k -> new ArrayList<>());
            boolean wechatAccountFound = false;
            for (Map<String, Object> acc : accountList) {
                if ("wechat".equals(acc.get("平台"))) {
                    acc.put("名称", account);
                    acc.put("更新日期", TimeUtil.todayStr());
                    wechatAccountFound = true;
                    break;
                }
            }
            if (!wechatAccountFound) {
                Map<String, Object> wechatAcc = new LinkedHashMap<>();
                wechatAcc.put("平台", "wechat");
                wechatAcc.put("名称", account);
                wechatAcc.put("更新日期", TimeUtil.todayStr());
                accountList.add(wechatAcc);
            }

            writeJsonFile("自媒体文章.json", articles);
            writeJsonFile("自媒体日数据.json", dailyStats);
            writeJsonFile("自媒体账号.json", accounts);

            log.info("[Excel导入] 完成: 文章更新{}条, 日统计更新{}条", articleUpdates, statsUpdates);
            logService.add("数据采集", "Excel导入",
                    String.format("文章更新%d条, 日统计更新%d条", articleUpdates, statsUpdates));

            result.put("ok", true);
            result.put("articleUpdates", articleUpdates);
            result.put("statsUpdates", statsUpdates);
            return result;
        } catch (Exception e) {
            log.error("[Excel导入] 失败: {}", e.getMessage(), e);
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private static String cellStr(Row row, int col) {
        var cell = row.getCell(col);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    yield String.valueOf((long) v);
                }
                yield String.valueOf(v);
            }
            default -> "";
        };
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private static String fmtDate(String s) {
        if (s == null || s.length() != 8) return s;
        return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
    }

    private Map<String, Object> findDailyStat(List<Map<String, Object>> list, String date) {
        for (Map<String, Object> s : list) {
            if (date.equals(s.get("日期"))) return s;
        }
        return null;
    }

    private Map<String, Object> findExistingArticle(List<Map<String, Object>> list, String topic) {
        String norm = topic.replaceAll("[\\s\u3000,，、：:。.；;！!？?（）()\\[\\]【】「」『》》]|^【.*?】", "").toLowerCase();
        Map<String, Object> bestMatch = null;
        int bestScore = 0;
        for (Map<String, Object> art : list) {
            String t = (String) art.get("文章名");
            if (t == null) continue;
            String tNorm = t.replaceAll("[\\s\u3000,，、：:。.；;！!？?（）()\\[\\]【】「」『》》]|^【.*?】", "").toLowerCase();
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

    }
