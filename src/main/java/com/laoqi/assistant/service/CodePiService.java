package com.laoqi.assistant.service;

import com.laoqi.assistant.model.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * pi CLI 调用服务
 * 对应 Python 版 run_pi() + build_bug_prompt() + BUG_PROMPT + md_to_feishu()
 */
@Service
public class CodePiService {

    private static final Logger log = LoggerFactory.getLogger(CodePiService.class);

    // BUG_PROMPT — 完全移植自 Python 版
    private static final String BUG_PROMPT_TMPL = """
你是一个资深开发工程师，负责排查线上 bug。

## 你的工作方式

像经验丰富的技术负责人一样工作：
1. **先理解问题** — 仔细分析用户描述的现象、错误信息、截图内容
2. **了解项目全貌** — 看项目结构、模块划分、关键配置
3. **追溯调用链** — 从入口到数据层，理解代码执行路径
4. **精确定位** — 找到具体的文件、方法、行号
5. **诊断根因** — 分析是逻辑错误、边界条件、并发问题还是配置问题
6. **给出修复方案** — 提供可复制的代码或配置修改

## 分析维度

从以下多个角度检查代码：
- **空指针/类型安全** — 未判空、拆箱 NPE、类型转换
- **数据一致性** — 并发写入无锁、事务范围不够、缺少幂等
- **边界条件** — 空列表、null 值、分页边界、超长输入
- **资源泄漏** — 流未关闭、连接未释放
- **安全风险** — SQL 注入、越权、敏感信息泄露
- **性能问题** — N+1 查询、全表扫描、无分页
- **语义错误** — 条件写反、字段绑定错误、常量含义理解偏差

## 输出格式

严格按以下格式输出（用中文）：

### 1. 问题定位
- 涉及文件、行号、方法名
- 相关代码片段

### 2. 根因分析
- 为什么这是 Bug
- 什么条件下会触发
- 影响范围

### 3. 修复方案
- 具体代码修改（可复制）
- 如果是配置问题，给出正确的配置

### 4. 严重程度
- 🔴 严重（数据丢失/系统崩溃）
- 🟡 中等（功能异常/数据不一致）
- 🟢 轻微（代码质量问题）

## 原则

- 如果确定问题，直接给出修复代码
- 如果不确定，说明需要哪些进一步信息（日志、复现步骤、截图）
- 不猜测，只基于读到的代码下结论
- 发现多个 Bug 时按严重程度排序列出

用户的问题：
{message}

项目目录：{project_dir}
""";

    /**
     * 调用 pi CLI 分析代码
     *
     * @param userMessage 用户的问题描述
     * @param projectDir  项目目录
     * @param timeoutSec  超时秒数
     * @return 分析结果
     */
    public CodePiResult analyze(String userMessage, String projectDir, int timeoutSec) {
        long start = System.currentTimeMillis();

        // 1. 验证项目目录
        File projectFile = new File(projectDir);
        if (!projectFile.isDirectory()) {
            return CodePiResult.failure("项目目录不存在: " + projectDir, System.currentTimeMillis() - start);
        }

        // 2. 构建 prompt
        String prompt = BUG_PROMPT_TMPL
                .replace("{message}", userMessage)
                .replace("{project_dir}", projectDir);

        // 3. 查找 pi 可执行文件
        String piPath = findPiExecutable();
        if (piPath == null) {
            return CodePiResult.failure("pi CLI 未安装，请运行: npm install -g @earendil-works/pi-coding-agent",
                    System.currentTimeMillis() - start);
        }

        // 4. 构建命令
        // Windows 上 pi 是 Node.js 脚本，需要用 cmd /c 执行
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb;
        if (isWin) {
            pb = new ProcessBuilder(
                    "cmd", "/c", "pi", "-p", prompt, "--no-session",
                    "--tools", "read,bash,grep,find,ls"
            );
        } else {
            pb = new ProcessBuilder(
                    piPath, "-p", prompt, "--no-session",
                    "--tools", "read,bash,grep,find,ls"
            );
        }
        pb.directory(projectFile);
        pb.environment().put("PI_OFFLINE", "1");
        pb.environment().put("TERM", "dumb");

        log.info("[CodePi] 开始执行 pi CLI | timeout={}s | dir={} | prompt={}...", timeoutSec, projectDir,
                prompt.substring(0, Math.min(80, prompt.length())));

        // 5. 执行
        try {
            Process process = pb.start();

            // 读取 stdout / stderr
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (IOException ignored) {
                }
            });
            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (IOException ignored) {
                }
            });

            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);

            stdoutReader.join(2000);
            stderrReader.join(2000);

            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                log.warn("[CodePi] 超时 ({}s)", timeoutSec);
                return CodePiResult.failure(
                        String.format("执行超时（%d秒），请简化问题或增大超时时间", timeoutSec), elapsed);
            }

            int exitCode = process.exitValue();
            String outText = stdout.toString().trim();
            String errText = stderr.toString().trim();

            if (exitCode == 0 && !outText.isEmpty()) {
                log.info("[CodePi] 成功 | 输出 {} 字符 | 耗时 {}ms", outText.length(), elapsed);
                return CodePiResult.success(outText, elapsed);
            } else {
                String errorMsg = !errText.isEmpty() ? errText :
                        (!outText.isEmpty() ? outText : "退出码 " + exitCode);
                log.warn("[CodePi] 失败 | exitCode={} | err={}", exitCode, truncate(errorMsg, 200));
                return CodePiResult.failure(errorMsg, elapsed);
            }

        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[CodePi] 启动失败: {}", e.getMessage());
            return CodePiResult.failure("启动 pi CLI 失败: " + e.getMessage(), elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long elapsed = System.currentTimeMillis() - start;
            return CodePiResult.failure("执行被中断", elapsed);
        }
    }

    /**
     * Markdown 转为飞书卡片兼容文本
     * 对应 Python 版 md_to_feishu()
     */
    public static String mdToFeishuCard(String md) {
        if (md == null || md.isEmpty()) return "";
        // 标题 → 加粗
        md = md.replaceAll("(?m)^#{1,6}\\s+(.+?)$", "**$1**");
        // 表格 → 列表
        StringBuilder result = new StringBuilder();
        String[] lines = md.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("|") && i + 2 < lines.length
                    && lines[i + 1].matches("^[\\s|:\\-]+$")) {
                // Skip header separator
                i += 1;
                while (i + 1 < lines.length && lines[i + 1].contains("|")) {
                    i++;
                    String[] cells = lines[i].trim().replaceAll("^\\|", "").split("\\|");
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
        return result.toString().trim();
    }

    /**
     * 查找 pi 可执行文件路径
     * 在 Windows 上优先返回 .cmd 包装器，因为 pi 是 Node.js 脚本
     */
    private String findPiExecutable() {
        // 1. Windows 上直接查找 .cmd 文件
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

            // 如果没找到 .cmd，就用 npm 路径下的 pi 并包裹 cmd /c
            if (appData != null) {
                File piScript = new File(appData, "npm/pi");
                if (piScript.exists()) return "pi"; // 由调用方处理 cmd /c
            }

            return "pi";
        }

        // macOS/Linux: 正常查找
        try {
            Process which = Runtime.getRuntime().exec("which pi");
            if (which.waitFor(3, TimeUnit.SECONDS) && which.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(which.getInputStream(), StandardCharsets.UTF_8))) {
                    String firstLine = reader.readLine();
                    if (firstLine != null && !firstLine.trim().isEmpty()) {
                        return firstLine.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 检查常见位置
        String[] candidates = {
                    "/usr/local/bin/pi",
                    "/usr/bin/pi",
                    "/opt/homebrew/bin/pi",
                    System.getenv("HOME") + "/.npm-global/bin/pi",
                    System.getenv("HOME") + "/.local/bin/pi",
            };
            for (String c : candidates) {
                File f = new File(c);
                if (f.exists() && f.canExecute()) return c;
            }
        return "pi";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * pi CLI 调用结果
     */
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
            long sec = elapsedMs / 1000;
            if (sec < 60) return sec + "s";
            return (sec / 60) + "m" + (sec % 60) + "s";
        }
    }
}
