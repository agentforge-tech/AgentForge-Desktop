# AgentForge Desktop

[English](README.md) | **简体中文**

面向 macOS 与 Windows 的本地优先 AI Agent 工作台。AgentForge Desktop 由 Vue 2 + Vite + Electron 桌面壳、内置 Spring Boot 后端和随包 JRE 8 运行时组成：用户只需安装一个应用，就能直接与 Agent 对话，无需打开终端或配置 JDK。

![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Windows-4f46e5)
![Electron](https://img.shields.io/badge/Electron-31-47848f)
![Vue](https://img.shields.io/badge/Vue-2.7-42b883)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7-6db33f)
![Java](https://img.shields.io/badge/Java-8-e76f00)

## 为什么选择 AgentForge Desktop

多数 Agent 框架默认你需要一台服务器、一个数据库和随时待命的开发者。AgentForge Desktop 反过来做：整套运行时都跑在用户自己的机器上。

- **零配置启动**——Electron 在空闲本地端口拉起后端 JAR 并生成一次性 Token，运行期不需要 JDK、Maven 或数据库。
- **本地优先**——会话、记忆和配置全部留在 `~/.agentforge-desktop`；SQLite 取代 MySQL，服务只监听 `127.0.0.1`。
- **真正的 Agent 循环**——流式推理、工具调用、工作区感知执行、可取消运行，而不是聊天接口封装。
- **一键交付**——构建脚本自动组装 JAR、JRE 与 Electron 应用，覆盖 macOS arm64/x64 和 Windows x64。

## 功能特性

- **工作台**——会话历史、置顶/重命名/删除，右侧对话详情支持 Markdown、代码高亮和一键复制。
- **流式对话**——通过 SSE 推送 `session / think / resp / tool_call / resp_end / error` 事件，历史消息可基于持久化事件重放。
- **工作区选择**——每个会话可选择本地项目目录，Agent 在该工作区下运行，并自动获得环境感知的内置指令。
- **人工确认（HITL）**——内置 `ask_user` 提问，并对 `rm` 等高风险 bash 命令发起审批；中断后的工具批次可在同一条消息上继续执行，冻结快照持久化到 SQLite（`ai_agent_interrupt_snapshot`）以支持审计与 Runtime 重建。
- **运行中追加指令**——Agent 执行期间可继续补充要求，未消费的指令在下一个模型调用边界注入，错过的内容会回到输入框。
- **模型管理**——增删改查、连通性测试、Function Call 测试，以及按 Agent 配置可用模型与默认模型。
- **Agent 配置**——模型集合、默认模型、记忆窗口，并只读预览系统自动组装的内置提示词；保存后立即生效。
- **双模式渲染**——调试模式与优雅模式，分别用于查看原始推理和阅读最终回答。
- **热加载**——手动修改 `conf/config.json` 或 `conf/models.json` 后自动重新加载。
- **版本化 SQLite 迁移**——启动时执行带校验和的增量 SQL，并提供手动命令行工具用于排查。
- **脚本化工程链路**——`doctor` 环境体检，按 build/dev/package/verify 分层，并提供双平台产物校验。

## 架构

```text
┌──────────────────────────────────────────────────────────────┐
│ Electron 主进程                                              │
│  · 选择空闲端口 / 生成一次性 Token                            │
│  · 拉起 runtime/jre/bin/java -jar runtime/server/*.jar        │
│  · 等待 /api/local/health 就绪，通过 IPC 暴露给渲染进程       │
└───────────────────────────────┬──────────────────────────────┘
                                │ IPC
┌───────────────────────────────▼──────────────────────────────┐
│ 渲染进程（Vue 2 + Vite + Element UI）                         │
│  · 工作台、会话历史、模型与 Agent 配置页                      │
│  · API Client 统一附加 baseUrl 与桌面 Token                   │
└───────────────────────────────┬──────────────────────────────┘
                                │ HTTP / SSE（127.0.0.1）
┌───────────────────────────────▼──────────────────────────────┐
│ agentforge-desktop-server（Spring Boot 2.7 / Java 8）         │
│  · Chat / Session / Message / Model / Agent 接口              │
│  · HarnessAgent + LocalRuntime、中间件、SSE 协议              │
│  · DesktopConfigStore 与 SQLite 迁移器                        │
└──────────────────────────────────────────────────────────────┘
```

## 技术栈

| 层 | 选型 |
| --- | --- |
| 桌面壳 | Electron 31 |
| 前端 | Vue 2.7、Vue Router 3、Vite 5、Element UI 2.15 |
| Markdown | markdown-it、highlight.js、KaTeX、DOMPurify |
| 后端 | Spring Boot 2.7.18、Java 8、MyBatis 2.3 |
| 持久化 | SQLite（sqlite-jdbc 3.46.x） |
| Agent 运行时 | AgentForge `agentforge-agent-core`、`agentforge-harness-agent`、`agentforge-model-registry` |
| 打包 | spring-boot-maven-plugin、内置 JRE 8、electron-builder |

## 仓库结构

```text
agentforge-desktop-server/   本地 Spring Boot 后端（SSE、SQLite、Agent 运行时）
agentforge-desktop-ui/       Vue 2 渲染层、Electron 主进程、构建与打包脚本
docs/                        设计说明、本地调试指南与打包文档
```

## 环境要求

- Node.js 22+ 与 npm 10+
- JDK 8（推荐 Zulu 8 / Temurin 8）
- Maven 3.9+
- macOS（arm64/x64）或 Windows x64

构建脚本会自动识别 `/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home`，也可通过 `AGENTFORGE_DESKTOP_JAVA_HOME` 指定。

首次构建会把 AgentForge 核心构件安装到本地 Maven 仓库。默认源码位于 `../../AgentForge`，可用 `AGENTFORGE_HOME` 覆盖。

## 快速开始

```bash
# 0. 只读环境体检：Node/npm/JDK 8/Maven/核心构件/依赖/runtime/磁盘
cd agentforge-desktop-ui
npm install
npm run doctor

# 1. 启动后端（默认端口 43179）
cd ../agentforge-desktop-server
mvn spring-boot:run

# 2. 启动桌面端并连接本机后端
cd ../agentforge-desktop-ui
npm run desktop:dev

# 3. 或一键构建 JAR + 内置运行时并启动全部服务
npm run desktop:dev:embedded

# 4. 为当前机器打包
npm run package:current
```

Electron 下载缓慢时可使用镜像：

```bash
ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/ npm install
```

Vite 开发服务器运行在 `127.0.0.1:1420`，并将 `/api` 代理到 `127.0.0.1:43179`；可通过 `VITE_PROXY_TARGET` 修改目标地址。

## 配置与数据

| 项目 | 默认位置 |
| --- | --- |
| Agent 配置 | `~/.agentforge-desktop/conf/config.json` |
| 模型配置 | `~/.agentforge-desktop/conf/models.json` |
| SQLite 数据库 | `~/.agentforge-desktop/data/agentforge-desktop.db` |
| 日志 | `~/.agentforge-desktop/logs/` |
| 工作区 | `~/.agentforge-desktop/workspace/` |

数据根目录可通过 `AGENTFORGE_DESKTOP_ROOT_DIR` 覆盖，配置文件可通过 `AGENTFORGE_DESKTOP_CONFIG` 指定。API Key 可通过 `AGENTFORGE_DESKTOP_MODEL_API_KEY` 预置。

已有安装会自动迁移：旧的 `config.json` 会被拆分为 `conf/config.json` 与 `conf/models.json`。

## 打包

```bash
# 当前机器
npm run package:current

# macOS 双架构
npm run package:mac -- --arch arm64
npm run package:mac -- --arch intel

# Windows x64（在 Windows 构建机上）
npm run package:win-x64
```

macOS 本地打包会复用 `node_modules/electron/dist`，避免重新下载 Electron。JRE 路径可通过 `packaging.config.local.json` 覆盖：

```json
{
  "jreHomes": {
    "mac-arm64": "/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home",
    "mac-x64": "/path/to/macos-x64-jre-8/Contents/Home",
    "win-x64": "C:\\Tools\\jre8-win-x64"
  }
}
```

在 macOS 上构建 Windows 产物可使用 `npm run package:win-x64:cross`，产物校验使用 `scripts/verify/*.mjs`。

## 数据库迁移

```bash
cd agentforge-desktop-server
./bin/upgrade-sqlite.sh            # 执行未执行脚本
./bin/upgrade-sqlite.sh --dry-run  # 只查看待执行脚本
```

内置迁移器会在启动时自动执行版本化 SQL，记录每个脚本的 SHA-256 校验和；若已执行的脚本内容发生变化，启动会直接失败。

## 文档

- [设计与实现方案](docs/桌面版agent工作台设计实现/README.md)
- [快捷命令与联调验证](docs/桌面版agent工作台设计实现/快捷命令与联调验证.md)
- [桌面端内置启动](docs/桌面版agent工作台设计实现/整体快捷流程梳理/本地调试/桌面端内置启动-desktop-dev-embedded.md)
- [快捷一键打包-三端适配](docs/桌面版agent工作台设计实现/整体快捷流程梳理/本地调试/快捷一键打包-三端适配实现详解.md)
- [日志统一管控](docs/桌面版agent工作台设计实现/整体快捷流程梳理/本地调试/AgentForge-Desktop日志统一管控详解.md)
- [安装后启动初始化与 SQLite 增量迁移](docs/桌面版agent工作台设计实现/整体快捷流程梳理/本地调试/安装后启动初始化与SQLite增量SQL迁移详解.md)