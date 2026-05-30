package com.laoqi.assistant.util;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownUtil {

    private static final Parser parser;
    private static final HtmlRenderer renderer;

    static {
        List<Extension> extensions = Arrays.asList(TablesExtension.create());
        parser = Parser.builder().extensions(extensions).build();
        renderer = HtmlRenderer.builder().extensions(extensions).build();
    }

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String processed = preprocessReport(markdown);
        Node doc = parser.parse(processed);
        return renderer.render(doc);
    }

    private static String preprocessReport(String text) {
        text = text.replaceAll("[━═]{8,}", "<hr class=\"section-divider\">");
        Pattern p = Pattern.compile("【([^】]+)】\n?");
        Matcher m = p.matcher(text);
        text = m.replaceAll("<div class=\"inline-section\">【$1】</div>\n\n");
        text = text.replaceAll("(?<!\n)\n(<hr class=\"section-divider\">)", "\n\n$1");
        text = text.replaceAll("(<hr class=\"section-divider\">)(?!\n)", "$1\n\n");
        return text;
    }

    public static String markdownInline(String text) {
        if (text == null) return "";
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        text = text.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<em>$1</em>");
        return text;
    }

    public static String stripFrontmatter(String content) {
        if (content != null && content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end != -1) {
                return content.substring(end + 3).strip();
            }
        }
        return content;
    }
}