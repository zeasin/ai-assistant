#!/usr/bin/env python3
# -*- coding: utf-8 -*-

with open('README.md', 'r', encoding='utf-8') as f:
    content = f.read()

old_section = '''## 🚀 快速开始

### 环境要求

| 组件 | 要求 |
|------|------|
| JDK | 17+ |
| Maven | 3.8+ |
| AI 大脑 | OpenCode（当前）/ ClaudeCode（即将支持） |

### 一键启动

```bash
# 1. 启动 AI 大脑（当前支持 OpenCode，即将支持 ClaudeCode）
opencode serve --port 14096

# 2. 编译
mvn package -q

# 3. 启动
java -jar target/ai-assistant-2.0.0.jar

# 4. 访问
# Web UI: http://localhost:6790
# Health: http://localhost:6790/health
```

> 💡 **小白用户提示**：如果觉得命令行麻烦，关注后续发布的「一键安装包」版本。

### 首次配置

访问 `http://localhost:6790/config`，设置：

1. **笔记库根目录** — 你的个人数据存放位置
2. **数据目录** — 客户数据、运营数据等子目录
3. **IM 通道**（可选）— 飞书（当前）/ 钉钉、企业微信（即将支持）
4. **AI 规则** — 定义你的 Prompt 模板'''

new_section = '''## 🚀 使用方法

> **4 步开始使用，全程无需改代码。**

### 第一步：启动 AI 大脑

```bash
opencode serve --port 14096
```

> AI 大脑提供所有 AI 能力（对话、分析、生成等），必须先启动。

### 第二步：打包并启动项目

```bash
# 编译打包
mvn package -q

# 启动应用
java -jar target/ai-assistant-2.0.0.jar
```

启动成功后，浏览器访问 **http://localhost:6790**

### 第三步：配置

首次访问会看到空页面，需要先配置。点击顶部导航栏 **「配置」** 或访问 `http://localhost:6790/config`：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| **笔记库根目录** | 你的个人数据存放位置（必填） | `D:\projects\richie_learning_notes` |
| **客户数据目录** | 客户、线索、跟进记录存放的相对路径 | `企业/客户管理` |
| **运营数据目录** | 自媒体运营数据存放的相对路径 | `自媒体` |
| **飞书 Webhook** | 接收推送通知（可选） | `https://open.feishu.cn/open-apis/bot/v2/hook/xxx` |
| **飞书 App ID/Secret** | 飞书机器人身份凭证（可选） | 从飞书开发者后台获取 |
| **字段标签** | 数据表格 key 的中文显示名（可选） | `name → 姓名, stage → 阶段` |

> 💡 **配置说明**：
> - **必填项**：笔记库根目录（不配无法使用任何功能）
> - **可选项**：飞书配置（只用 Web UI 不需要）
> - 配置保存后立即生效，无需重启

### 第四步：开始使用

配置完成后，你可以：

| 方式 | 操作 | 说明 |
|------|------|------|
| **Web UI** | 访问 http://localhost:6790 | 综合日报、AI 对话、客户看板、运营看板、数据编辑器等 |
| **飞书对话** | 在飞书群/私聊中发消息 | "查客户张三"、"记录跟进：今天拜访了 ABC 公司"、"生成日报" |
| **定时任务** | 自动触发 | 工作日 9:00 综合日报、18:00 下班提醒、周二/四发文提醒 |

> 💡 **小白用户提示**：如果觉得命令行麻烦，关注后续发布的「一键安装包」版本（双击即用）。'''

content = content.replace(old_section, new_section)

with open('README.md', 'w', encoding='utf-8') as f:
    f.write(content)

print('使用方法章节更新完成!')
