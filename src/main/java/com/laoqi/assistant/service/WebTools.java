package com.laoqi.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 互联网搜索与网页获取工具集 — 让 AI 可以获取最新的网络信息。
 * 使用内置 HttpClient，无需外部依赖。
 */
@Component
public class WebTools {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);

    private static final String DEFAULT_SEARCH_URL = "https://www.baidu.com/s?wd=";
    private static final int TIMEOUT_SECONDS = 15;
    private static final int MAX_RESPONSE_LENGTH = 8000;

    private final HttpClient httpClient;

    public WebTools() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(description = "搜索互联网获取最新信息。当用户问实时新闻、当前事件、最新消息、你不知道的信息时必须使用此工具。注意：搜索结果可能包含广告，请甄别使用")
    public String webSearch(
            @ToolParam(description = "搜索关键词，尽量精确") String query,
            @ToolParam(description = "返回结果数量，默认 5，最多 10") int limit) {
        if (query == null || query.isEmpty()) return "搜索关键词不能为空";
        int resultLimit = Math.max(1, Math.min(10, limit <= 0 ? 5 : limit));

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = DEFAULT_SEARCH_URL + encoded;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body == null || body.isEmpty()) {
                return "搜索无结果";
            }

            // 提取可读文本（去除HTML标签）
            String text = extractText(body);

            // 截取前 MAX_RESPONSE_LENGTH 字符
            if (text.length() > MAX_RESPONSE_LENGTH) {
                text = text.substring(0, MAX_RESPONSE_LENGTH) + "\n\n...（结果过长，已截断）";
            }

            log.info("[WebTools] 搜索: query={}, 响应长度={}", query, text.length());
            return "🌐 搜索「" + query + "」结果：\n\n" + text;

        } catch (Exception e) {
            log.warn("[WebTools] 搜索失败: {}", e.getMessage());
            return "❌ 搜索失败: " + e.getMessage() + "\n提示：可以尝试用更精确的关键词搜索，或检查网络连接。";
        }
    }

    @Tool(description = "获取指定 URL 的网页内容。当用户要求查看某个链接、或 webSearch 结果不够详细时使用此工具")
    public String fetchUrl(
            @ToolParam(description = "完整的网页 URL，必须以 http:// 或 https:// 开头") String url) {
        if (url == null || url.isEmpty()) return "URL 不能为空";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "❌ URL 格式不正确，必须以 http:// 或 https:// 开头";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body == null || body.isEmpty()) {
                return "该页面无内容";
            }

            String text = extractText(body);
            if (text.length() > MAX_RESPONSE_LENGTH) {
                text = text.substring(0, MAX_RESPONSE_LENGTH) + "\n\n...（内容过长，已截断）";
            }

            log.info("[WebTools] 获取URL: {}, 响应长度={}", url, text.length());
            return text;

        } catch (Exception e) {
            log.warn("[WebTools] 获取URL失败: {}", e.getMessage());
            return "❌ 获取失败: " + e.getMessage();
        }
    }

    /**
     * 简易 HTML 转纯文本
     */
    private String extractText(String html) {
        if (html == null || html.isEmpty()) return "";

        // 移除 script 和 style 块
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", "")
                // 替换换行标签
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("(?i)</tr>", "\n")
                .replaceAll("(?i)</li>", "\n")
                .replaceAll("(?i)</h[1-6]>", "\n\n")
                // 移除所有 HTML 标签
                .replaceAll("<[^>]+>", "")
                // 解码 HTML 实体
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#\\d+;", " ")
                // 合并空白
                .replaceAll("\\s+", " ")
                .replaceAll("\\n\\s*\\n", "\n\n")
                .trim();

        // 移除过长的无意义行
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() > 200) {
                // 长行可能是无格式文本，保留但截断
                sb.append(trimmed, 0, 200).append("...\n");
            } else {
                sb.append(trimmed).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
