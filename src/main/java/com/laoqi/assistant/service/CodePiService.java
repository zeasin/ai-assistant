package com.laoqi.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class CodePiService {

    private static final Logger log = LoggerFactory.getLogger(CodePiService.class);

    // 流式日志缓冲 — 达到这些条件之一就刷出
    private static final int STREAM_FLUSH_LINES = 1;     // 每 N 行刷一次
    private static final int STREAM_FLUSH_CHARS = 200;    // 或累计 N 字符刷一次

    private static final String BUG_PROMPT_TMPL = """
你是一位资深代码调试专家。请仔细分析以下问题，并给出详细的排查步骤和修复建议。

### 问题描述
{message}

### 项目目录
{project_dir}

### 要求
1. 首先理解代码结构和业务流程，再定位问题
2. 给出具体的排查步骤，包括需要查看哪些文件、搜索什么关键字
3. 如果可能，给出修复方案
4. 注意代码风格和最佳实践
5. 如果信息不足，说明还需要哪些额外信息

请开始你的排查。
""";

    public CodePiResult analyze(String userMessage, String projectDir, int timeoutSec) {
        long start = System.currentTimeMillis();

        File projectFile = new File(projectDir);
        if (!projectFile.isDirectory()) {
            return CodePiResult.failure("项目目录不存在: " + projectDir, System.currentTimeMillis() - start);
        }

        String prompt = BUG_PROMPT_TMPL
                .replace("{message}", userMessage)
                .replace("{project_dir}", projectDir);

        String piPath = findPiExecutable();

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb;
        if (isWin) {
            pb = new ProcessBuilder(
                    "cmd", "/c", "pi", "--mode", "json", "--no-session",
                    "--tools", "read,bash,grep,find,ls"
            );
        } else {
            pb = new ProcessBuilder(
                    piPath, "--mode", "json", "--no-session",
                    "--tools", "read,bash,grep,find,ls"
            );
        }
        pb.directory(projectFile);
        pb.environment().put("PI_OFFLINE", "1");
        pb.environment().put("TERM", "dumb");

        log.info("[CodePi] JSON start | timeout={}s | dir={} | prompt_len={}",
                timeoutSec, projectDir, prompt.length());

        try {
            Process process = pb.start();

            // --mode json：写 prompt 到 stdin，关 stdin → pi 开始处理 → 退出时刷 stdout
            OutputStream stdin = process.getOutputStream();
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close();

            StringBuilder content = new StringBuilder(4096);
            StringBuilder stderrBuf = new StringBuilder();

            // 流式日志：将 pi 的输出实时刷到 logger
            StringBuilder streamBuf = new StringBuilder();

            Thread eventReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        parseJsonEvent(line, content, streamBuf);
                    }
                } catch (IOException ignored) {}
                flushStreamBuf(streamBuf);
            });
            Thread errReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrBuf.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });

            eventReader.start();
            errReader.start();

            // 关 stdin → pi 处理完 prompt 后自动退出，waitFor 等待完成
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            eventReader.join(3000);
            errReader.join(2000);

            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                log.warn("[CodePi] timeout ({}s)", timeoutSec);
                return CodePiResult.failure(
                        String.format("执行超时（%d秒）", timeoutSec), elapsed);
            }

            int exitCode = process.exitValue();
            String outText = content.toString().trim();
            String errText = stderrBuf.toString().trim();

            if (exitCode == 0 || !outText.isEmpty()) {
                if (!outText.isEmpty()) {
                    log.info("[CodePi] ok {} chars {}ms", outText.length(), elapsed);
                    return CodePiResult.success(outText, elapsed);
                }
            }
            String errorMsg = !errText.isEmpty() ? errText : "exit=" + exitCode;
            log.warn("[CodePi] fail exitCode={} err={}", exitCode, truncate(errorMsg, 200));
            return CodePiResult.failure(errorMsg, elapsed);

        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[CodePi] start fail: {}", e.getMessage());
            return CodePiResult.failure("启动 pi CLI 失败: " + e.getMessage(), elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CodePiResult.failure("执行被中断", System.currentTimeMillis() - start);
        }
    }

    private void parseJsonEvent(String line, StringBuilder content, StringBuilder streamBuf) {
        if (line == null || line.isEmpty() || line.charAt(0) != '{') return;
        try {
            String type = extractStr(line, "type");
            if (type == null) return;

            switch (type) {
                case "agent_start":
                    log.info("[Pi] ═══ agent start ═══");
                    break;
                case "agent_end":
                    flushStreamBuf(streamBuf);
                    log.info("[Pi] ═══ agent end ═══");
                    if (content.isEmpty()) {
                        String msgs = extractRaw(line, "messages");
                        if (msgs != null) extractTextFromMessages(msgs, content);
                    }
                    break;
                case "turn_start":
                    flushStreamBuf(streamBuf);
                    log.info("[Pi] ─── turn start ───");
                    break;
                case "turn_end":
                    flushStreamBuf(streamBuf);
                    log.info("[Pi] ─── turn end ───");
                    break;
                case "message_update": {
                    String delta = extractNestedStr(line, "assistantMessageEvent", "delta");
                    if (delta != null && !delta.isEmpty()) {
                        content.append(delta);
                        streamBuf.append(delta);
                        maybeFlushStreamBuf(streamBuf);
                    }
                    break;
                }
                case "tool_execution_start": {
                    flushStreamBuf(streamBuf);
                    String tn = extractStr(line, "toolName");
                    String args = extractStr(line, "args");
                    log.info("[Pi] 🔧 {}", tn != null ? tn + " " + (args != null ? truncate(args, 120) : "") : "");
                    break;
                }
                case "tool_execution_end": {
                    flushStreamBuf(streamBuf);
                    String tn = extractStr(line, "toolName");
                    boolean err = "true".equals(extractStr(line, "isError"));
                    log.info("[Pi] {} {} {}", err ? "❌" : "✅", tn != null ? tn : "", err ? "(error)" : "");
                    break;
                }
                case "response": {
                    String cmd = extractStr(line, "command");
                    String ok = extractStr(line, "success");
                    if ("prompt".equals(cmd) && "true".equals(ok)) {
                        log.info("[Pi] prompt accepted");
                    }
                    break;
                }
                default: break;
            }
        } catch (Exception e) {
            log.warn("[Pi] parse err: {} | {}", e.getMessage(), truncate(line, 120));
        }
    }

    /**
     * 检查缓冲是否需要刷出
     */
    private static void maybeFlushStreamBuf(StringBuilder buf) {
        if (buf == null || buf.isEmpty()) return;
        // 按行刷：遇到换行符就刷
        int idx;
        while ((idx = buf.indexOf("\n")) >= 0) {
            String line = buf.substring(0, idx);
            log.info("[Pi] │ {}", line);
            buf.delete(0, idx + 1);
        }
        // 或超长时刷（防止无换行段落一直不显示）
        if (buf.length() >= STREAM_FLUSH_CHARS) {
            log.info("[Pi] │ {}", buf);
            buf.setLength(0);
        }
    }

    private static void flushStreamBuf(StringBuilder buf) {
        if (buf != null && !buf.isEmpty()) {
            log.info("[Pi] │ {}", buf);
            buf.setLength(0);
        }
    }

    private static String extractStr(String json, String field) {
        String key = "\"" + field + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { sb.append(json.charAt(i + 1)); i += 2; }
            else if (c == '"') break;
            else { sb.append(c); i++; }
        }
        return sb.toString();
    }

    private static String extractRaw(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        char first = json.charAt(start);
        if (first == '{' || first == '[') {
            char close = first == '{' ? '}' : ']';
            int depth = 0, end = start;
            boolean inStr = false;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '"' && (end == 0 || json.charAt(end - 1) != '\\')) inStr = !inStr;
                if (!inStr) {
                    if (c == first) depth++;
                    else if (c == close) depth--;
                }
                if (depth == 0) break;
                end++;
            }
            return json.substring(start, end + 1);
        }
        return null;
    }

    private static String extractNestedStr(String json, String parent, String child) {
        String p = extractRaw(json, parent);
        return p != null ? extractStr(p, child) : null;
    }

    private static void extractTextFromMessages(String msgs, StringBuilder out) {
        try {
            String s = msgs;
            while (true) {
                int ri = s.indexOf("\"role\":\"assistant\"");
                if (ri < 0) break;
                int ci = s.indexOf("\"content\"", ri);
                if (ci < 0) break;
                int ti = s.indexOf("\"text\":\"", ci);
                if (ti < 0) { s = s.substring(ri + 1); continue; }
                int ts = ti + 8, te = ts;
                StringBuilder sb = new StringBuilder();
                while (te < s.length()) {
                    char c = s.charAt(te);
                    if (c == '\\' && te + 1 < s.length()) { sb.append(s.charAt(te + 1)); te += 2; }
                    else if (c == '"') break;
                    else { sb.append(c); te++; }
                }
                if (!sb.isEmpty()) out.append(sb).append('\n');
                s = s.substring(te + 1);
            }
        } catch (Exception e) {
            log.warn("[CodePi] extractText fail: {}", e.getMessage());
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String mdToFeishuCard(String md) {
        if (md == null || md.isEmpty()) return "";
        md = md.replaceAll("(?m)^#{1,6}\\s+(.+?)$", "**$1**");
        StringBuilder result = new StringBuilder();
        String[] lines = md.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("|") && i + 2 < lines.length
                    && lines[i + 1].matches("^[\\s|:\\-]+$")) {
                i++;
                while (i + 1 < lines.length && lines[i + 1].contains("|")) {
                    i++;
                    String[] cells = line.split("\\|");
                    StringBuilder row = new StringBuilder("  - ");
                    for (int j = 0; j < cells.length; j++) {
                        String cell = cells[j].trim();
                        if (!cell.isEmpty()) {
                            if (row.length() > 4) row.append(" | ");
                            row.append(cell);
                        }
                    }
                    result.append(row).append("\n");
                }
            } else {
                result.append(line).append("\n");
            }
        }
        md = result.toString();
        md = md.replaceAll("```(?:\\w+)?\\s*", "");
        md = md.replaceAll("(?m)^\\s*\\*\\s+", "  \u2022 ");
        md = md.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        md = md.replaceAll("`([^`]+)`", "$1");
        md = md.replaceAll("(?m)^\\s*[-\\d]+\\.\\s+", "  ");
        return md.strip();
    }

    private String findPiExecutable() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                File piCmd = new File(appData, "npm/pi.cmd");
                if (piCmd.exists()) return piCmd.getAbsolutePath();
            }
            String userProfile = System.getenv("USERPROFILE");
            if (userProfile != null) {
                File piCmd2 = new File(userProfile, "AppData/Roaming/npm/pi.cmd");
                if (piCmd2.exists()) return piCmd2.getAbsolutePath();
            }
            if (appData != null) {
                File piScript = new File(appData, "npm/pi");
                if (piScript.exists()) return "pi";
            }
            return "pi";
        }
        try {
            Process which = Runtime.getRuntime().exec("which pi");
            if (which.waitFor(3, TimeUnit.SECONDS) && which.exitValue() == 0) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(which.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) return line.trim();
                }
            }
        } catch (Exception ignored) {}
        String home = System.getenv("HOME");
        String[] candidates = {
            "/usr/local/bin/pi", "/usr/bin/pi", "/opt/homebrew/bin/pi",
            home + "/.npm-global/bin/pi", home + "/.local/bin/pi"
        };
        for (String c : candidates) {
            if (new File(c).exists()) return c;
        }
        return "pi";
    }

    public Map<String, Object> checkPiStatus() {
        Map<String, Object> status = new HashMap<>();
        String piPath = findPiExecutable();
        boolean installed = !"pi".equals(piPath) || checkPiExists();
        status.put("installed", installed);
        status.put("path", piPath);
        if (installed) {
            try {
                String version = runPiVersion(piPath);
                status.put("version", version);
                status.put("ok", true);
            } catch (Exception e) {
                status.put("version", null);
                status.put("ok", true);
            }
        } else {
            status.put("version", null);
            status.put("ok", false);
        }
        return status;
    }

    private boolean checkPiExists() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "pi", "--version");
            Process p = pb.start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private String runPiVersion(String piPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "pi", "--version");
            pb.environment().put("TERM", "dumb");
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String v = r.readLine();
                if (p.waitFor(3, TimeUnit.SECONDS) && v != null) return v.trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    public static class CodePiResult {
        private final boolean success;
        private final String output;
        private final String error;
        private final long elapsedMs;

        private CodePiResult(boolean success, String output, String error, long elapsedMs) {
            this.success = success;
            this.output = output;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }

        public static CodePiResult success(String output, long elapsedMs) {
            return new CodePiResult(true, output, null, elapsedMs);
        }

        public static CodePiResult failure(String error, long elapsedMs) {
            return new CodePiResult(false, null, error, elapsedMs);
        }

        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public String getError() { return error; }
        public long getElapsedMs() { return elapsedMs; }
        public String getElapsedStr() {
            if (elapsedMs < 1000) return elapsedMs + "ms";
            long sec = elapsedMs / 1000;
            if (sec < 60) return sec + "s";
            return (sec / 60) + "m " + (sec % 60) + "s";
        }
    }
}
