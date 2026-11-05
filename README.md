# AgentForge Desktop

AgentForge 桌面版 Agent 工作台：Vue2 + Vite + Electron 桌面壳，内置 Spring Boot 后端与 JRE 8。

## 目录

- `agentforge-desktop-server`：本地 Java 后端（Spring Boot 2.7.18 / Java 8 / SQLite）
- `agentforge-desktop-ui`：Vue2 + Vite + Electron 桌面端
- `docs/桌面版agent工作台设计实现`：设计与实现方案

## 环境要求

- Node.js 22+、npm 10+
- JDK 8（推荐 Zulu 8 / Temurin 8）
- Maven 3.9+
- macOS（arm64/x64）或 Windows x64

本机若已安装 `/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home`，
构建脚本会自动识别；也可通过 `AGENTFORGE_DESKTOP_JAVA_HOME` 指定。

## 快速开始

```bash
# 0. 首次构建会自动把 AgentForge 核心构件安装到本地 Maven 仓库
#    AgentForge 源码默认位于 ../../AgentForge，可用 AGENTFORGE_HOME 覆盖

# 1. 启动后端（IDE 或命令行，默认端口 43179）
cd agentforge-desktop-server
mvn spring-boot:run

# 2. 启动桌面端（连接本机后端）
cd ../agentforge-desktop-ui
npm install
# 如果 Electron 下载慢或失败，可使用镜像：
# ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/ npm install
npm run desktop:dev

# 3. 一键构建后端 + 内置 JRE 调试
npm run desktop:dev:embedded

# 4. 当前机器一键打包
npm run package:current
```

配置文件默认位于 `~/.agentforge-desktop/config.json`，包含模型与 Agent 配置。
SQLite 数据库位于 `~/.agentforge-desktop/data/agentforge-desktop.db`。

## 已实现能力

- 工作台：左侧会话历史、右侧对话详情、置顶/重命名/删除
- 流式对话：`session / think / resp / tool_call / resp_end / error`
- 历史消息事件重放
- 右上角模型选择、调试/优雅双模式、设置抽屉
- 模型配置：增删改查、连通性测试、Function Call 测试
- Agent 配置：模型集合、默认模型、系统提示词、记忆窗口、保存并初始化
- 配置保存直接写入 `config.json`，手动编辑后自动热加载
- 版本化增量 SQL + 启动自动升级 + `bin/upgrade-sqlite.sh`
- Electron 内置 JRE 8 + JAR，一键打包 macOS arm64/x64、Windows x64

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

macOS 本地打包会使用 `node_modules/electron/dist` 作为 Electron 发行目录，
避免重新从网络下载。JRE 8 路径可通过 `packaging.config.local.json` 覆盖：

```json
{
  "jreHomes": {
    "mac-arm64": "/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home",
    "mac-x64": "/path/to/macos-x64-jre-8/Contents/Home",
    "win-x64": "C:\\Tools\\jre8-win-x64"
  }
}
```

## 升级 SQL

```bash
cd agentforge-desktop-server
./bin/upgrade-sqlite.sh            # 执行未执行脚本
./bin/upgrade-sqlite.sh --dry-run  # 只查看待执行脚本
```

正常用户启动桌面端时，Java 内置迁移器会自动执行增量 SQL，无需手动操作。
