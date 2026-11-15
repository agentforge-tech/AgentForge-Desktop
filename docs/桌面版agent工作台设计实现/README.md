# AgentForge Desktop 桌面版 Agent 工作台 · 初版设计与实现方案

> 版本：v0.1（方案评审稿）
> 目标仓库：`AgentForge-Desktop`
> 参考实现：
> - 桌面壳、内置 JRE、一键打包：Electron + 内置运行时通用方案
> - 表结构、会话交互、模型配置、Agent 配置：`AgentForge/agentforge-service`

---

## 1. 背景与目标

把 AgentForge 的服务端能力装进一个本地桌面应用，形成"开箱即用"的 Agent 工作台：

- 用户安装桌面端后，无需手动安装 JDK、无需启动命令行服务。
- 桌面端内置 Spring Boot 后端（JAR）与精简 JRE，Electron 负责拉起、健康探测、退出回收。
- 工作台左侧为**会话历史**，右侧为**对话详情**，支持流式思考、工具调用卡片、历史事件重放。
- 提供**模型配置**与**Agent 基础配置**，保存后直接在本地工作台对话。
- 开发期支持"一键调试本机后端"，交付期支持"一键打包"。

### 1.1 初版范围（v1）

| 模块 | v1 是否包含 | 说明 |
| --- | --- | --- |
| 会话历史 + 对话详情 | ✅ | 左侧列表、右侧会话，含置顶/重命名/删除 |
| 流式对话 | ✅ | `session / think / resp / tool_call / resp_end / error` |
| 历史消息重放 | ✅ | 基于持久化的 SSE 事件数组还原 |
| 双模式渲染 | ✅ | 工作台右上角切换调试模式 / 优雅模式 |
| 模型配置 | ✅ | 增删改查、连通性测试、Function Call 测试 |
| 工作台模型切换 | ✅ | 右上角选择 `model_ids` 中的模型，按请求 `modelId` 初始化 |
| 工作台设置抽屉 | ✅ | 右上角设置按钮，直接编辑模型 / Agent 配置 |
| Agent 基础配置 | ✅ | 名称、头像、描述、模型、系统提示词、记忆配置（保存即生效） |
| Agent 调试 | ✅ | 使用当前配置直接对话，无需发布 |
| 一键调试本机后端 | ✅ | `npm run desktop:dev`（连接 IDE 启动的服务） |
| 一键打包 | ✅ | 内置 JAR + JRE 8 + electron-builder |
| HTTP / MCP 插件 | ❌ | 保留 `pluginConfig` 结构，v1 不装配 |
| Agent 历史版本 | ❌ | v1 不实现版本切换，配置文件只保留当前配置 |
| Agent Trace 可视化 | ❌ | 表可预留，v1 不做页面 |
| 应用内自动更新 | ❌ | v2 再做 |

### 1.2 非目标

- 不做多用户/登录体系，桌面端固定单用户 `create_user_id = 1`。
- 不追求与 `agentforge-service` 的数据库 100% 兼容，只保持字段语义一致。
- v1 不引入 MySQL，桌面端固定 SQLite。

---

## 2. 技术选型

### 2.1 总体

| 层 | 选型 | 依据 |
| --- | --- | --- |
| 桌面壳 | Electron 43.x | 桌面端通用方案 |
| 前端 | Vue 2.7.16 + Vue Router 3.6.5 + Vite 5 | 对齐 agentforge-service-ui |
| UI 组件 | Element UI 2.15.14 | 对齐 agentforge-service-ui 的表单/弹窗 |
| Markdown | markdown-it + highlight.js + katex + dompurify | 对齐 agentforge-service-ui |
| 后端 | Spring Boot 2.7.18 + Java 8 | 对齐 agentforge-service-web |
| 持久化 | SQLite（sqlite-jdbc 3.46.x）+ MyBatis 2.3.2 | 对齐 agentforge-service 的 Mapper 写法 |
| 打包 | spring-boot-maven-plugin + 内置 JRE 8 + electron-builder | 内置运行时的一键打包链路 |
| Agent 运行时 | `agentforge-agent-core` + `agentforge-model-registry` | 直接复用 AgentForge 核心 |

### 2.2 依赖策略（关键）

**直接依赖 AgentForge 核心构件**，不依赖 `agentforge-service-web` 这个可执行工程：

```xml
<dependency>
  <groupId>cloud.changlu.agentforge</groupId>
  <artifactId>agentforge-agent-core</artifactId>
  <version>${agentforge.version}</version>
</dependency>
<dependency>
  <groupId>cloud.changlu.agentforge</groupId>
  <artifactId>agentforge-model-registry</artifactId>
  <version>${agentforge.version}</version>
</dependency>
```

服务层（会话、SSE 协议、生命周期中间件）按 `agentforge-service-web` 的设计**移植**到桌面端；模型配置 / Agent 配置参考其字段结构，改为本地 `config.json` 持久化。原因：

1. `agentforge-service-web` 是 `spring-boot-maven-plugin` 可执行包，作为库依赖会引入固定配置约定（`conf/application.yml`、MySQL 开关、静态站点等）。
2. 桌面端需要 SQLite、单用户、Token 鉴权、动态端口、内置 JRE 等定制。
3. 移植量可控：核心是 4 张表（迁移记录 + 会话 + 消息 + 记忆）、5 个 Controller、2 个中间件、1 个 Agent 工厂。

**前置条件**：AgentForge 核心构件以 `1.0.0-SNAPSHOT` 从本地源码安装，构建脚本会自动检测并安装，见第 8 节。

---

## 3. 总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│ Electron 主进程 (electron/main.mjs)                          │
│  · 选空闲端口 / 生成一次性 Token                              │
│  · 拉起 runtime/jre/bin/java -jar runtime/server/xxx.jar     │
│  · 等待 /api/local/health 就绪                                │
│  · 通过 IPC 暴露 baseUrl + token + workspace                  │
└───────────────────────────────┬──────────────────────────────┘
                                │ IPC
┌───────────────────────────────▼──────────────────────────────┐
│ 渲染进程 (Vue2 + Vite)                                        │
│  · 工作台：左侧会话历史 / 右侧对话详情                        │
│  · 模型配置 / Agent 配置                                      │
│  · API Client 统一拼接 baseUrl + X-AgentForge-Desktop-Token   │
└───────────────────────────────┬──────────────────────────────┘
                                │ HTTP / SSE (127.0.0.1)
┌───────────────────────────────▼──────────────────────────────┐
│ agentforge-desktop-server (Spring Boot 2.7.18 / Java 8)      │
│  ├─ ChatController        /api/chat/stream | /stop           │
│  ├─ SessionController     /api/agent/sessions/*              │
│  ├─ MessageController     /api/agent/session-messages/detail │
│  ├─ ModelConfigController /api/model-config/*                │
│  ├─ AgentConfigController /api/agent-config/*                │
│  └─ LocalDesktopController/api/local/health                  │
│         │                                                     │
│         ├─ ReActAgentFactory → agentforge-agent-core         │
│         ├─ LlmFactory        → agentforge-model-registry     │
│         ├─ DesktopConfigStore→ config.json（模型 + Agent）    │
│         └─ MyBatis + SQLite  → ~/.agentforge-desktop/data/*.db│
└──────────────────────────────────────────────────────────────┘
```

### 3.1 进程与端口

| 项 | 值 |
| --- | --- |
| 后端默认端口 | `43178`（开发期固定；打包后动态空闲端口） |
| 前端开发端口 | `1420`（Vite，strictPort 自动顺延） |
| Token Header | `X-AgentForge-Desktop-Token` |
| 数据根目录 | `~/.agentforge-desktop`（可用 `AGENTFORGE_DESKTOP_ROOT_DIR` 覆盖） |
| Agent 配置 | `~/.agentforge-desktop/conf/config.json` |
| 模型配置 | `~/.agentforge-desktop/conf/models.json` |
| SQLite | `~/.agentforge-desktop/data/agentforge-desktop.db` |
| 日志 | `~/.agentforge-desktop/logs/` |
| 工作区（预留） | `~/.agentforge-desktop/workspace/` |

### 3.2 环境变量约定

统一使用 `AGENTFORGE_DESKTOP_*` 前缀，避免与 AgentForge 服务端变量冲突：

```text
AGENTFORGE_DESKTOP_SERVER_URL     连接外部后端（开发调试）
AGENTFORGE_DESKTOP_SERVER_TOKEN   外部后端 Token（可空）
AGENTFORGE_DESKTOP_ROOT_DIR       数据根目录
AGENTFORGE_DESKTOP_CONFIG         主配置文件路径（默认 <root>/conf/config.json）
AGENTFORGE_DESKTOP_MODEL_API_KEY  模型密钥默认值（config.json 中 apiKey 为空时使用）
AGENTFORGE_DESKTOP_PORT           固定端口
AGENTFORGE_DESKTOP_JAVA_HOME      开发期指定 JDK
AGENTFORGE_DESKTOP_SERVER_JAR     开发期指定 JAR
AGENTFORGE_DESKTOP_JAVA_XMX       堆上限，默认 1536m
AGENTFORGE_DESKTOP_DB_TYPE        数据库类型，默认 sqlite（v1 仅支持 sqlite）
AGENTFORGE_DESKTOP_SQL_DIR        增量 SQL 目录（升级脚本使用）
AGENTFORGE_DESKTOP_SQLITE_PATH    SQLite 文件路径（升级脚本使用）
```

---

## 4. 仓库目录结构

```text
AgentForge-Desktop/
├── .gitignore
├── README.md                        # 快速开始（实现后补充）
├── docs/
│   └── 桌面版agent工作台设计实现/
│       └── README.md                # 本文档
├── agentforge-desktop-server/
│   ├── pom.xml
│   ├── bin/
│   │   └── upgrade-sqlite.sh        # 增量 SQL 升级脚本
│   ├── sql/
│   │   ├── sqllite/<version>/*.sql  # 增量 SQL（按版本维护）
│   │   └── local/sqlite/            # 已执行归档（gitignore）
│   └── src/
│       ├── main/java/cloud/changlu/agentforge/desktop/
│       │   ├── AgentForgeDesktopServerApplication.java
│       │   ├── common/              # ApiResponse / 异常处理 / Token 过滤
│       │   ├── config/              # DataSource / MyBatis / Web / Agent / 属性
│       │   ├── store/               # DesktopConfigStore / SchemaMigrator / ChatMemoryStore
│       │   └── modules/
│       │       ├── system/          # health / app-config
│       │       ├── model/           # 模型配置（config.json）
│       │       ├── agent/           # Agent 配置（config.json）+ 运行时装配
│       │       ├── session/         # 会话 / 消息
│       │       └── chat/            # SSE 对话 + 生命周期中间件
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── defaults/config.json # 默认模型 + Agent 模板
│       │   └── mapper/**.xml        # 会话 / 消息 / 记忆
│       └── test/java/...
├── agentforge-desktop-ui/
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   ├── electron/
│   │   ├── main.mjs
│   │   └── preload.cjs
│   ├── scripts/
│   │   ├── dev-utils.mjs
│   │   ├── desktop-dev.mjs
│   │   ├── build-server.mjs
│   │   ├── prepare-server.mjs
│   │   ├── prepare-jre.mjs
│   │   ├── prepare-windows-jre.mjs
│   │   ├── check-windows-cross-env.mjs
│   │   ├── package-current.mjs
│   │   ├── package-mac.mjs
│   │   ├── package-desktop.mjs
│   │   ├── verify-mac-package.mjs
│   │   ├── verify-win-package.mjs
│   │   ├── after-pack-mac.mjs
│   │   └── stage-update-artifacts.mjs
│   ├── runtime/                    # 构建产物（gitignore）
│   │   ├── server/agentforge-desktop-server.jar
│   │   └── jre/
│   └── src/
│       ├── main.js
│       ├── App.vue                 # 左侧导航栏 + 内容区
│       ├── router/index.js
│       ├── api/                    # client / model / agent / session / chat
│       ├── utils/                  # sse / sessionReplay / chatAutoScroll
│       ├── modes/                  # debug / elegant 两种消息渲染
│       ├── components/             # AgentMessageContent / MarkdownView / ModelSelect / ConfigDrawer
│       └── pages/
│           ├── workbench/ChatWorkbench.vue
│           ├── models/ModelsPage.vue
│           └── agents/AgentsPage.vue + AgentConfigPage.vue
└── docs/...
```

### 4.1 `.gitignore` 初版内容

```gitignore
# OS / IDE
.DS_Store
.idea/
*.iml
.vscode/

# Java
target/
*.class
*.log

# Node / 前端
node_modules/
dist/
.vite/
release/

# 桌面运行产物
runtime/server/*.jar
runtime/jre/
runtime/template/
.cache/
packaging.config.local.json

# 桌面本地调试数据
.agentforge-dev/

# 本地数据
data/
logs/
sql/local/sqlite/
sql-applied/
*.db
*.db-shm
*.db-wal

# 环境变量
.env
.env.local
```

---

## 5. 后端设计（agentforge-desktop-server）

### 5.1 Maven 坐标与依赖

```xml
<groupId>cloud.changlu.agentforge</groupId>
<artifactId>agentforge-desktop-server</artifactId>
<version>0.1.0-SNAPSHOT</version>
```

不设 parent，通过 BOM 引入版本管理；编译目标与 AgentForge 核心保持一致（Java 8）：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>2.7.18</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>cloud.changlu.agentforge</groupId>
      <artifactId>agentforge-ai-bom</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

核心依赖：

```text
spring-boot-starter-web
mybatis-spring-boot-starter 2.3.2
sqlite-jdbc 3.46.1.3
agentforge-agent-core
agentforge-model-registry（含 openai / anthropic / core）
spring-boot-starter-test（test）
```

编译与打包：

```xml
<maven.compiler.source>8</maven.compiler.source>
<maven.compiler.target>8</maven.compiler.target>
<spring-boot-maven-plugin>
  <version>2.7.18</version>
  <configuration>
    <mainClass>cloud.changlu.agentforge.desktop.AgentForgeDesktopServerApplication</mainClass>
  </configuration>
</spring-boot-maven-plugin>
<finalName>agentforge-desktop-server</finalName>
```

### 5.2 Spring 配置（application.yml）

```yaml
server:
  address: 127.0.0.1
  port: ${AGENTFORGE_DESKTOP_PORT:43178}
  shutdown: graceful

spring:
  application:
    name: agentforge-desktop-server
  main:
    banner-mode: "off"
  jackson:
    default-property-inclusion: non_null
  mvc:
    async:
      # SSE 长连接，不能超时
      request-timeout: -1

agentforge:
  desktop:
    token: ""
    root-dir: ${user.home}/.agentforge-desktop

mybatis:
  mapper-locations: classpath*:mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true
```

`DesktopDataSourceConfig` 使用代码方式创建 Hikari 数据源：

```text
jdbc:sqlite:${root-dir}/data/agentforge-desktop.db
  ?busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL&foreign_keys=on
maximum-pool-size = 1
```

> SQLite 单写者，连接池固定 1，避免 `SQLITE_BUSY`。

### 5.3 表设计（SQLite v1）

数据库只保留**会话、消息、记忆**三类数据；**模型配置与 Agent 配置统一存放在本地 `config.json`**（见 5.3.1），不建对应表。字段语义沿用 `agentforge-service`，类型从 MySQL 映射到 SQLite（`DATETIME → TEXT`、`LONGTEXT → TEXT`、`AUTO_INCREMENT → AUTOINCREMENT`）。另有一张迁移记录表。

```sql
-- 0. 迁移记录（增量 SQL 执行台账，脚本与内置迁移器共用）
CREATE TABLE IF NOT EXISTS desktop_schema_migration (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    script_name TEXT    NOT NULL,   -- 相对路径，如 1.0.x/20260101000000_desktop_baseline.sql
    version     TEXT    NOT NULL,   -- 版本目录，如 1.0.x
    checksum    TEXT    NOT NULL,   -- 脚本 SHA-256，防止执行后篡改
    applied_at  INTEGER NOT NULL    -- 执行时间（epoch 毫秒）
);

-- 1. 会话
CREATE TABLE IF NOT EXISTS ai_agent_session (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     TEXT    NOT NULL,              -- 对外会话标识，同时作为 memoryId
    aid            TEXT    NOT NULL,
    title          TEXT    NOT NULL DEFAULT '新会话',
    status         INTEGER NOT NULL DEFAULT 0,    -- 0空闲 1运行中 2完成 3失败
    model_id       INTEGER          DEFAULT NULL, -- 最近一次选择的模型 ID
    pin_order      INTEGER NOT NULL DEFAULT 0,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. 会话消息
CREATE TABLE IF NOT EXISTS ai_agent_session_message (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     INTEGER NOT NULL,              -- 关联 ai_agent_session.id
    seq_num        INTEGER NOT NULL,
    role_type      INTEGER NOT NULL,              -- 1 用户 2 AI
    status         INTEGER NOT NULL DEFAULT 1,    -- 1创建 2处理中 3失败 4取消 5成功
    started_at     TEXT             DEFAULT NULL,
    finished_at    TEXT             DEFAULT NULL,
    elapsed_ms     INTEGER          DEFAULT NULL,
    model_id       INTEGER          DEFAULT NULL, -- AI 消息实际使用的模型 ID
    content        TEXT             DEFAULT NULL,
    events         TEXT             DEFAULT NULL, -- 推送到页面的 SSE 事件 JSON 数组
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. 对话记忆（对应 agentforge 的 ai_agent_memrory，桌面端修正拼写）
CREATE TABLE IF NOT EXISTS ai_agent_memory (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    memory_id   TEXT NOT NULL,
    content     TEXT NOT NULL,
    create_time TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

说明：

- **索引约定**：业务表只保留 `id` 主键，不创建二级索引或唯一索引。
  `memory_id`、`aid` 的唯一性由服务层与配置文件保证。
  桌面端为单用户、本地小数据量，避免额外索引维护成本；v1 接受顺序扫描。
- **配置不落库**：Agent 配置在 `<root>/conf/config.json`，模型配置在 `<root>/conf/models.json`，不建 `ai_model_config` / `ai_agent_config` 表。
- `ai_agent_trace_snapshot` v1 不建表，预留后续增量脚本。
- 时间字段使用 `TEXT`，沿用 `agentforge-service` 的 `SqliteDateTypeHandler` 思路，保证 `Date` 映射。
- 会话消息的 `session_id` 指向主键 `ai_agent_session.id`，与 `agentforge-service` 保持一致。

### 5.3.1 本地配置文件（模型 + Agent）

#### 文件位置

| 项 | 值 |
| --- | --- |
| Agent 配置 | `<root>/conf/config.json`，默认 `~/.agentforge-desktop/conf/config.json` |
| 模型配置 | `<root>/conf/models.json`，默认 `~/.agentforge-desktop/conf/models.json` |
| 默认模板 | JAR 内 `defaults/config.json` 与 `defaults/models.json`，首次启动复制到对应路径 |
| 备份 | `*.json.bak`（每次成功写入前保留上一版） |
| 覆盖路径 | `AGENTFORGE_DESKTOP_CONFIG` 环境变量 |

#### 完整 JSON 结构

```json
{
  "version": 1,
  "defaultAid": "agt_default",
  "models": [
    {
      "id": 1,
      "name": "DeepSeek Flash",
      "provider": 1,
      "modelType": 0,
      "modelName": "deepseek-flash",
      "url": "https://tokenrhythm.studio/v1",
      "apiKey": "",
      "createTime": "2026-01-01T10:00:00+08:00",
      "modifyTime": "2026-01-01T10:00:00+08:00"
    },
    {
      "id": 2,
      "name": "Claude Sonnet",
      "provider": 2,
      "modelType": 0,
      "modelName": "claude-sonnet-4",
      "url": "https://api.anthropic.com",
      "apiKey": "",
      "createTime": "2026-01-01T10:00:00+08:00",
      "modifyTime": "2026-01-01T10:00:00+08:00"
    }
  ],
  "agents": [
    {
      "aid": "agt_default",
      "name": "AgentForge 助手",
      "type": 1,
      "avatar": "",
      "description": "桌面端默认 Agent",
      "agentConfig": {
        "systemPrompt": "你是一名专业的 AI 助手，请准确、清晰地回答用户问题。",
        "chatModel": {
          "default_model_id": 1,
          "model_ids": [1, 2]
        },
        "chatMemoryConfig": {
          "type": 1,
          "memoryConfig": "{\"maxMessage\":1000}"
        },
        "pluginConfig": null
      },
      "experConfig": {
        "welcomeText": "今天想解决什么问题？",
        "themeColor": "#4f46e5"
      },
      "createTime": "2026-01-01T10:00:00+08:00",
      "modifyTime": "2026-01-01T10:00:00+08:00"
    }
  ]
}
```

#### 字段说明

| 字段 | 说明 |
| --- | --- |
| `version` | 配置文件版本，v1 固定为 `1` |
| `defaultAid` | 工作台默认选中的 Agent |
| `models[].id` | 数字主键，被 `chatModel.default_model_id` / `chatModel.model_ids` 引用 |
| `models[].provider` | `1` OpenAI / `2` Anthropic |
| `models[].modelType` | `0` chat（v1 仅 chat） |
| `models[].modelName` | 模型名称，如 `deepseek-flash` |
| `models[].url` | OpenAI-compatible Base URL |
| `models[].apiKey` | 密钥；留空时回退 `AGENTFORGE_DESKTOP_MODEL_API_KEY` |
| `agents[].aid` | Agent 稳定标识（文件内唯一） |
| `agents[].type` | `1` ReActAgent |
| `agents[].agentConfig.systemPrompt` | 系统提示词 |
| `agents[].agentConfig.chatModel.default_model_id` | 默认模型 ID，工作台首次进入时选中 |
| `agents[].agentConfig.chatModel.model_ids` | 工作台可选择的模型集合；为空表示允许全部模型 |
| `agents[].agentConfig.chatMemoryConfig` | 记忆类型与窗口配置 |
| `agents[].agentConfig.pluginConfig` | v1 固定为 `null` |
| `agents[].experConfig` | 展示配置（欢迎语、主题色等） |
| `createTime` / `modifyTime` | ISO-8601 时间字符串 |

#### 保存与初始化流程

```text
配置页 / 工作台设置抽屉保存（模型 / Agent）
  → POST /api/model-config/upsert 或 /api/agent-config/upsert
  → 校验（必填、id/aid 不重复、default_model_id 存在且属于 model_ids）
  → DesktopConfigStore.save()
       1. 写 config.json.tmp
       2. fsync + 原子 rename
       3. 旧文件备份为 *.json.bak
       4. 更新内存快照
  → 清理运行时缓存
       · 模型变更：清空全部 Agent 实例
       · Agent 变更：只清理对应 aid
  → 初始化 Agent
       · Agent 保存：立即 getAgentAndInitAgent(...) 预热并校验
       · 模型保存：只清理引用该模型的缓存，等待下次对话初始化
  → 返回 { config, initOk, initError }
  → 前端提示保存成功；初始化失败时显示 initError，配置仍保留，修正后可再次保存
```

#### 手动编辑与热加载

- 用户可直接编辑 `config.json`，适合批量维护或脚本生成。
- `DesktopConfigStore` 每次读取时比较文件 `mtime + size`，变化后重新加载。
- 文件变化后清空 Agent 缓存，下一次对话自动使用新配置初始化。
- 解析失败时：保留内存中的上一版，接口返回明确错误；启动时解析失败则把原文件改名为
  `config.json.corrupt-<timestamp>`，再复制默认模板。

#### 约束

- 模型 `id` 在文件内唯一，Agent `aid` 在文件内唯一。
- `default_model_id` 必须指向存在的模型；`model_ids` 非空时默认模型必须包含在内。
- 删除模型前检查被哪些 Agent 的 `model_ids` / `default_model_id` 引用。
- `defaultAid` 不存在时回退到 `agents[0].aid`；没有任何 Agent 时工作台引导创建。
- v1 不支持 Agent 历史版本与发布快照。

### 5.4 增量 SQL 与自动升级

#### 5.4.1 目录与版本

增量 SQL 按版本目录维护，默认数据库为 **SQLite**（v1 唯一支持的数据库）：

```text
agentforge-desktop-server/
├── sql/
│   ├── sqllite/                       # 增量 SQL（源文件，按版本维护）
│   │   ├── 1.0.x/
│   │   │   └── 20260101000000_desktop_baseline.sql
│   │   └── 1.1.x/                     # 后续版本新增目录
│   └── local/
│       └── sqlite/                    # 已执行归档（开发期，gitignore）
├── bin/
│   └── upgrade-sqlite.sh              # 手动 / 运维升级脚本
└── src/main/resources/
    ├── application.yml
    └── mapper/**.xml
```

Maven 构建时把 `sql/sqllite/**` 复制进 JAR（`db/sqlite/increment/**`），保证安装包自带全部增量脚本：

```xml
<resources>
  <resource>
    <directory>src/main/resources</directory>
  </resource>
  <resource>
    <directory>sql/sqllite</directory>
    <targetPath>db/sqlite/increment</targetPath>
  </resource>
</resources>
```

#### 5.4.2 命名规范

```text
<版本目录>/<yyyyMMddHHmmss>_<变更描述>.sql
示例：1.1.x/20260301093000_add_agent_trace_table.sql
```

- 一个文件只做一类变更，便于回滚与排查。
- 文件名时间戳保证执行顺序稳定；目录按版本排序。
- 文件内不写 `BEGIN/COMMIT`，由执行器统一包事务。
- 建表使用 `CREATE TABLE IF NOT EXISTS`，作为二次保护；不额外创建二级索引。

#### 5.4.3 启动自动升级（用户正常安装场景）

用户双击应用即完成升级，不需要手动执行脚本：

```text
Electron 启动
  └─ spawn java -jar agentforge-desktop-server.jar
        └─ DesktopSchemaMigrator（数据源初始化后、Web 服务就绪前）
              1. 扫描 classpath:db/sqlite/increment/**/*.sql
              2. 按「版本目录 + 文件名」排序
              3. 查询 desktop_schema_migration 跳过已执行脚本
              4. 校验已执行脚本 checksum，不一致则阻断启动并告警
              5. 每个脚本单独事务执行（ScriptUtils + JDBC）
              6. 成功后写入 script_name / version / checksum / applied_at
              7. 复制脚本到 <root>/sql-applied/<version>/ 作为审计归档
        └─ /api/local/health 就绪
  └─ Electron 健康探测通过，打开窗口
```

升级安装（覆盖安装新版本）时：

1. 新安装包内含新 JAR（携带新增量 SQL）。
2. 启动时迁移器只执行**尚未记录**的脚本。
3. 用户已有数据（会话、记忆）与 `config.json` 原样保留。

#### 5.4.4 升级脚本（开发 / 运维）

`bin/upgrade-sqlite.sh` 与内置迁移器共用同一套「增量目录 + 迁移台账」语义：

```bash
cd agentforge-desktop-server
./bin/upgrade-sqlite.sh            # 执行 sql/sqllite 下未执行脚本
./bin/upgrade-sqlite.sh --dry-run  # 只列出待执行脚本
```

脚本行为：

```text
1. AGENTFORGE_DESKTOP_DB_TYPE 默认 sqlite；非 sqlite 直接跳过并提示
2. DB 默认 <root>/data/agentforge-desktop.db
3. 增量目录默认 sql/sqllite，可用 AGENTFORGE_DESKTOP_SQL_DIR 覆盖
4. 遍历 *.sql 按相对路径排序
5. 读取 desktop_schema_migration 跳过已执行项
6. 单文件事务执行；成功后写入台账并归档到 sql/local/sqlite/<version>/
7. 失败回滚并以非 0 退出，打印日志
```

> 桌面安装包内不依赖 `sqlite3` 命令行：正常用户走 Java 内置迁移器；脚本仅用于开发、排障和服务器式部署。
> 两条路径通过 `desktop_schema_migration` 互通，谁先执行都不会重复执行。

#### 5.4.5 幂等、校验与回滚

| 能力 | 规则 |
| --- | --- |
| 幂等 | 以 `script_name` 记录判定，已执行即跳过 |
| 顺序 | 版本目录升序 → 文件名升序 |
| 校验 | 记录 SHA-256；已执行脚本被修改时启动失败并提示 |
| 事务 | 每个脚本一个事务，失败整体回滚 |
| 阻断 | 迁移失败不启动 Web 服务，Electron 健康探测超时并弹窗提示 |
| 审计 | `<root>/sql-applied/<version>/` 保留已执行脚本副本 |

#### 5.4.6 新增 SQL 的流程

```text
1. 在 sql/sqllite/<下一个版本>/ 新建带时间戳的 .sql
2. 本地启动服务或执行 ./bin/upgrade-sqlite.sh 验证
3. 提交 SQL 文件与对应 Mapper / 领域对象变更
4. 打包时自动带入 JAR；用户升级安装后启动即自动执行
```

> v1 固定 SQLite；后续若接入 MySQL，按同样规则增加 `sql/mysql/<version>/` 与 `mapper/mysql/**`，默认仍保持 SQLite。

### 5.5 后端包结构

```text
cloud.changlu.agentforge.desktop
├── AgentForgeDesktopServerApplication
├── common
│   ├── ApiResponse<T>                 # {code,msg,data}
│   ├── GlobalExceptionHandler
│   └── DesktopTokenFilter             # 校验 X-AgentForge-Desktop-Token
├── config
│   ├── DesktopProperties              # root-dir / token
│   ├── DesktopDataSourceConfig        # SQLite + Hikari
│   ├── DesktopWebConfig               # CORS / 异步 / 过滤器
│   └── DesktopAgentConfiguration      # 中间件 / 记忆 / 会话 bean
├── store
│   ├── DesktopConfigStore             # config.json 读写 / 热加载 / 原子替换
│   ├── DesktopSchemaMigrator
│   └── SqliteChatMemoryStore          # 实现 ChatMemoryStore
└── modules
    ├── system
    │   ├── LocalDesktopController     # /api/local/health、/app-config
    │   └── LocalDesktopService
    ├── model                          # 模型配置（config.json）
    ├── agent                          # Agent 配置（config.json）+ 运行时
    │   ├── controller/AgentConfigController
    │   ├── service/AgentConfigServiceImpl
    │   ├── runtime/ReActAgentFactory
    │   ├── runtime/StandardReActAgentManager
    │   ├── runtime/AgentManagerFactory
    │   └── domain/config/*            # StandardAgentConfig 等
    ├── session                        # 移植 session 模块
    └── chat
        ├── ChatController             # SSE
        ├── ServiceStreamingMiddleware
        ├── SessionLifecycleMiddleware
        └── protocol/*                 # StreamEventResponse / 处理器
```

### 5.6 API 清单（v1）

统一返回 `{ "code": 0, "msg": "ok", "data": ... }`，错误 `code != 0`。
模型与 Agent 配置由 `config.json` 承载，下列接口本质是文件 CRUD 与运行时刷新。

#### 系统

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/local/health` | `{ready, workspace, version, db}`，Electron 启动探测 |
| GET | `/api/local/app-config` | 应用信息（版本、数据目录、平台） |

#### 模型配置

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/model-config/options` | 厂商 / 模型类别枚举 |
| GET | `/api/model-config/list` | 模型列表 |
| GET | `/api/model-config/{id}` | 详情 |
| POST | `/api/model-config/upsert` | 新增/更新（写入 config.json） |
| DELETE | `/api/model-config/{id}` | 删除（校验 Agent 引用） |
| POST | `/api/model-config/testConn` | 连通性测试 |
| POST | `/api/model-config/testFunctionCall` | Function Call 测试 |

#### Agent 配置

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/agent-config/list` | Agent 列表（工作台选择器） |
| GET | `/api/agent-config/{aid}` | 按 aid 获取当前配置 |
| POST | `/api/agent-config/upsert` | 新增/更新（写入 config.json 并刷新运行时） |
| DELETE | `/api/agent-config/{aid}` | 删除（同时清理运行时缓存） |

#### 会话

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/sessions/sessionList` | 历史会话 `{aid, limit}`，返回含 `modelId` |
| POST | `/api/agent/sessions/topList` | 置顶会话 |
| POST | `/api/agent/sessions/setTop` | 置顶/取消 |
| POST | `/api/agent/sessions/changeTitle` | 重命名 |
| POST | `/api/agent/sessions/delete` | 删除 |
| POST | `/api/agent/session-messages/detail` | 历史消息 + 事件重放 |

#### 对话

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/stream` | SSE 流式对话 `{aid, sessionId, message, modelId?}` |
| POST | `/api/chat/stop?sessionId=` | 停止本轮 |

> `modelId` 可选；缺省时使用 Agent 的 `chatModel.default_model_id`。
> 服务端会校验 `modelId` 是否属于 `chatModel.model_ids`（非空时），并记录到会话与 AI 消息。

### 5.7 Agent 运行时装配

直接复用 AgentForge 核心，桌面端只负责"配置 → 运行时"的装配：

```text
DesktopConfigStore
  config.json → agents[] → StandardAgentConfig
  config.json → models[] → LlmBasicConfig
  每次请求按 modelId 解析实际模型（请求 modelId → default_model_id）
        │
        ▼
ReActAgentFactory
  · DesktopConfigStore.findModel(effectiveModelId) → LlmFactory.buildChatModel / buildStreamChatModel
  · SqliteChatMemoryStore → ChatMemoryProvider
  · middleware: Logging → SessionLifecycle → ServiceStreaming
  · toolService: v1 为空（pluginConfig 预留）
        │
        ▼
StandardReActAgentManager（缓存键 = aid + modelId）
```

配置 JSON 结构（对应 `config.json` 中 `agents[].agentConfig`，与 `agentforge-service` 的 `StandardAgentConfig` 一致）：

```json
{
  "systemPrompt": "你是一名专业的 AI 助手……",
  "chatModel": {
    "default_model_id": 1,
    "model_ids": [1, 2]
  },
  "chatMemoryConfig": {
    "type": 1,
    "memoryConfig": "{\"maxMessage\":1000}"
  },
  "pluginConfig": null
}
```

> 桌面端在装配运行时会把 `default_model_id` / 请求 `modelId` 映射为
> `AgentModelConfig.llmModelId`，核心 `StandardAgentConfig` 无需改动。

`experConfig` v1 仅存展示信息（对应 `config.json` 中 `agents[].experConfig`），例如：

```json
{
  "welcomeText": "今天想解决什么问题？",
  "themeColor": "#4f46e5"
}
```

### 5.8 对话链路

```text
POST /api/chat/stream
  1. 校验 aid / message
  2. 解析模型
       modelId = request.modelId ?? agentConfig.chatModel.default_model_id
       model_ids 非空时校验 modelId ∈ model_ids
       → DesktopConfigStore.findAgent(aid) + findModel(modelId)
  3. SessionService.initChat(..., modelId)
       会话不存在则创建；写入 USER 消息 + AI 占位消息（记录 model_id）
  4. AgentManager.getAgentAndInitAgent（缓存键 aid + modelId）
  5. 绑定 SseEmitter，注册 stop 动作
  6. 异步 agent.stream(req).ignoreErrors().start()
  7. 中间件回调：
       onInitComplete     → 推 session，消息置 RUNNING
       onPartialThinking  → 推 think
       onPartialResponse  → 推 resp，累计 content
       beforeToolExecution→ 推 tool_call（执行中）
       afterToolExecution → 推 tool_call（完成 + 耗时）
       onStop             → 推 resp_end，落库 content/events/elapsed/model_id/终态
       onStopWithError    → 推 error 并落库失败态
```

#### SSE 事件格式

```json
{ "event": "session",   "type": "created",   "content": "sess-uuid", "isFinish": false }
{ "event": "think",     "type": "text",      "content": "思考片段",  "isFinish": false }
{ "event": "resp",      "type": "text",      "content": "回答片段",  "isFinish": false }
{ "event": "tool_call", "type": "tool_card", "content": { "toolCallId": "call_1", "toolName": "...", "inputParams": "...", "outputResult": "...", "duration": 120 }, "isFinish": true }
{ "event": "resp_end",  "isFinish": true }
{ "event": "error",     "type": "text",      "content": "错误信息",  "isFinish": true }
```

> 与 `agentforge-service` 完全一致，前端 `sessionReplay.js` 可直接复用。

#### 停止

`POST /api/chat/stop?sessionId=外部sessionId` → `agent.cancel(sessionId)`，同时前端 `AbortController.abort()`。

### 5.9 桌面适配要点

1. **Token 鉴权**：`DesktopTokenFilter` 读取 `agentforge.desktop.token`，非空时校验请求头；`/api/local/health` 同样校验（Electron 会带 Token）。
2. **动态端口**：Electron 传 `--server.port=<free>`，`application.yml` 只做默认值。
3. **数据目录**：Electron 传 `--agentforge.desktop.root-dir=<root>`，启动时自动 `mkdir -p data/logs/workspace`。
4. **优雅退出**：`server.shutdown=graceful`；Electron `before-quit` 先 `SIGTERM`，超时 `SIGKILL`。
5. **健康探测**：`/api/local/health` 返回 `ready=true` 即认为就绪，Electron 轮询 60s。
6. **CORS**：仅放宽 `http://127.0.0.1:*` 与 `http://localhost:*`，并允许 Token 头。

---

## 6. 前端设计（agentforge-desktop-ui）

### 6.1 页面与路由

```text
/                     → 重定向 /workbench
/workbench            → ChatWorkbench（工作台）
/models               → ModelsPage（模型配置）
/agents               → AgentsPage（Agent 列表）
/agents/config?id=    → AgentConfigPage（Agent 配置）
```

App 外壳：

```text
┌────┬───────────────────────────────────────────────┐
│ 导航│                                               │
│ 栏 │              页面内容（router-view）           │
│56px│                                               │
└────┴───────────────────────────────────────────────┘
```

导航栏：工作台 / 模型配置 / Agent 配置；底部显示后端连接状态（embedded / external + 端口）。

### 6.2 工作台布局（核心）

```text
┌──────────────────────┬────────────────────────────────────────────┐
│ Agent 选择器          │ 会话标题    [模型 ▾] [调试模式 ▾] [⚙]      │
│ [ AgentForge 助手 ▾ ] │                                            │
│ ──────────────────── │  ┌──────────────────────────────────────┐  │
│ ＋ 新建对话           │  │ 用户消息气泡                          │  │
│ ──────────────────── │  └──────────────────────────────────────┘  │
│ 置顶会话              │  ┌──────────────────────────────────────┐  │
│  · 会话 A  10-31     │  │ AI 回答：think / tool_call / markdown │  │
│ ──────────────────── │  └──────────────────────────────────────┘  │
│ 历史会话              │                                            │
│  · 会话 B  10-30     │                                            │
│  · 会话 C  10-29     │  ┌──────────────────────────────────────┐  │
│                      │  │ 输入框                          发送 │  │
│                      │  └──────────────────────────────────────┘  │
└──────────────────────┴────────────────────────────────────────────┘
```

关键交互：

- 顶部 Agent 选择器：切换后重新加载该 `aid` 的会话列表；默认取第一个 Agent，选择结果存 `localStorage`。
- **右上角工具栏**：模型选择 + 展示模式 + 设置按钮。
  - **模型选择**：数据来自 `/api/model-config/list`，按当前 Agent 的 `chatModel.model_ids` 过滤
    （`model_ids` 为空则展示全部模型）；默认选中 `default_model_id`；发送时把 `modelId` 一并传给后端。
    当前会话记住所选模型，重新打开历史会话时优先恢复会话的 `model_id`；
    若该模型已被删除，回退到 `default_model_id`。
  - **设置按钮（齿轮）**：点击打开右侧配置抽屉，可直接编辑模型与 Agent 配置，无需离开工作台。
  - **展示模式切换**：与 `agentforge-service` 保持一致：
    - `debug`：调试模式 · 事件时间线，完整展示 `think` / `tool_call` 过程（默认）。
    - `elegant`：优雅模式 · 简洁展示，面向使用者，弱化思考与工具细节。
    - 切换只重新渲染当前消息，不重新请求后端；选择结果存 `localStorage`（`agentforge.render.mode`）。
- 新建对话：清空 `sessionId` 与消息，输入框聚焦。
- 发送：`POST /api/chat/stream`，携带当前选中的 `modelId`；Agent 按该模型初始化。
- 首个 `session` 事件回填 `sessionId`，并刷新左侧会话列表。
- 历史会话点击：`/api/agent/session-messages/detail` → `replaySessionMessages` 还原。
- 停止：按钮在运行中切换为"停止"，调用后端 stop + abort。
- 重命名 / 置顶 / 删除：直接复用 `agentforge-service` 的会话接口。

### 6.3 消息渲染

复用 `agentforge-service-ui` 的两套模式，消息数据结构一致，区别只在渲染层：

| 模式 | 名称 | 展示方式 | 默认 |
| --- | --- | --- | --- |
| `debug` | 调试模式 · 事件时间线 | 完整展示 think / tool_call 卡片与耗时 | ✅ |
| `elegant` | 优雅模式 · 简洁展示 | 以最终回答为主，思考与工具折叠/弱化 | |

- 两套模式共用同一份 `message.parts` 与 `events`，切换不重新请求。
- 历史消息重放后同样支持两种模式切换。
- 模式选择通过 `modes/index.js` 统一维护，新增模式只需扩展 `RENDER_MODES`。

消息结构：

```js
{
  role: 'assistant',
  status: 'running' | 'success' | 'error',
  content: '最终回答',
  parts: [
    { type: 'think', content, status, duration },
    { type: 'tool_card', toolCallId, toolName, inputParams, outputResult, duration, isFinish }
  ],
  error: '',
  startedAt, finishedAt, elapsedMs
}
```

`utils/sessionReplay.js` 与 `utils/chatAutoScroll.js` 从 `agentforge-service-ui` 移植，保证实时与历史渲染一致。

### 6.4 API 客户端（Electron 适配）

`agentforge-service-ui` 直接请求同源 `/api`；桌面端需要动态 `baseUrl + token`。统一封装：

```js
// src/api/client.js
let infoPromise = null
async function resolveInfo() {
  if (window.electronAPI?.getLocalServerInfo) {
    if (!infoPromise) infoPromise = window.electronAPI.getLocalServerInfo()
    return infoPromise
  }
  // 纯浏览器开发：走 Vite 代理
  return { baseUrl: '', token: '' }
}

export async function request(path, options = {}) {
  const { baseUrl, token } = await resolveInfo()
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (token) headers['X-AgentForge-Desktop-Token'] = token
  const res = await fetch(baseUrl + path, { ...options, headers })
  // ...统一错误处理与 JSON 解析
}
```

`postSse` 同理：拼接 `baseUrl`、带 Token 头、保持事件解析逻辑。

### 6.5 模型配置页

参考 `agentforge-service-ui/src/pages/models/ModelsPage.vue`：

- 卡片/表格列表：名称、厂商、模型名、Base URL、创建时间。
- 新增/编辑弹窗：名称、厂商（OpenAI / Anthropic）、模型名称、Base URL、API Key、类型（chat）。
- 操作：连通性测试、Function Call 测试、删除。
- 测试请求支持"未保存表单值直接测"，API Key 留空则沿用已保存值（沿用 agentforge 逻辑）。
- 保存后直接写入 `config.json` 的 `models[]`，并清理引用该模型的 Agent 运行时缓存。

### 6.6 Agent 配置页

参考 `AgentConfigPage.vue`，v1 精简为：

| 字段 | 说明 |
| --- | --- |
| 名称 / 头像 URL / 描述 | 基础信息 |
| 可用模型（model_ids） | 多选，决定工作台模型下拉的候选集合 |
| 默认模型（default_model_id） | 单选，必须是可用模型之一 |
| 系统提示词 | textarea |
| 记忆类型 / 最大消息数 | 默认 1 / 1000 |
| 操作 | 保存、开始调试、删除 |

"开始调试"跳转 `/workbench?aid=<aid>`。

保存行为：

1. 校验表单（名称、模型、提示词、`default_model_id` 与 `model_ids` 是否合法）。
2. 写入 `config.json` 的 `agents[]`（原子替换 + `.bak` 备份）。
3. 清理该 `aid` 的运行时缓存。
4. 初始化 Agent（预热）；初始化失败时返回 `initError`，配置仍保留。
5. 工作台下一次对话直接使用新配置，无需重启。
6. 手动编辑 `config.json` 也会被热加载，并自动清空运行时缓存。

### 6.7 工作台设置抽屉

右上角设置按钮打开右侧抽屉，复用配置页表单，避免离开对话上下文：

```text
┌──────────────────────────── 设置抽屉 ────────────────────────────┐
│ [模型配置] [Agent 配置]                                    ×     │
│ ─────────────────────────────────────────────────────────────── │
│ 名称        [ AgentForge 助手                              ]     │
│ 可用模型    [ DeepSeek Flash × ] [ Claude Sonnet × ]  ＋        │
│ 默认模型    [ DeepSeek Flash ▾ ]                                 │
│ 系统提示词  [ ............................................ ]     │
│ 记忆配置    [ 类型 ▾ ] [ 最大消息数 1000 ]                        │
│                                                                  │
│ [打开完整配置页]                              [取消] [保存]      │
└──────────────────────────────────────────────────────────────────┘
```

- 保存调用与配置页相同的 `upsert` 接口：写入 `config.json` → 清理缓存 → 初始化 Agent。
- 保存成功后：
  - 模型下拉立即刷新；
  - 当前 Agent 名称/头像立即更新；
  - 下一次对话使用新配置与当前选中模型。
- "打开完整配置页"跳转 `/agents/config?aid=<aid>`。

### 6.8 后端连接状态

导航栏底部小组件：

```text
● 已连接 · embedded-java
  127.0.0.1:43178
  [检测] [打开数据目录]
```

数据来自 `window.electronAPI.getLocalServerInfo()` + `/api/local/health`。

---

## 7. Electron 桌面壳

### 7.1 启动流程（Electron 主进程）

```text
app.whenReady()
  ├─ 若存在 AGENTFORGE_DESKTOP_SERVER_URL：
  │    直接连接外部后端（IDE 调试模式），只做健康探测
  └─ 否则：
       1. 解析 JAR：打包态 process.resourcesPath/runtime/server/*.jar
                    开发态 runtime/server/*.jar
       2. 解析 Java：打包态 resources/runtime/jre/bin/java
                    开发态 JAVA_HOME / 系统 java
       3. 选空闲端口，生成 32 字节随机 Token
       4. spawn java -Xms128m -Xmx1536m -jar <jar>
            --server.address=127.0.0.1
            --server.port=<port>
            --agentforge.desktop.token=<token>
            --agentforge.desktop.root-dir=<root>
       5. 轮询 /api/local/health（带 Token）直到 ready
       6. 注册 IPC → 创建 BrowserWindow → 加载 dist 或 Vite URL
```

### 7.2 IPC 暴露

```text
local-agent:server-info        { baseUrl, token, workspace, platform, arch, runtime }
local-agent:open-path          打开目录
local-agent:choose-directory   选择目录（预留工作区）
local-agent:backend-exit       后端异常退出通知
```

`preload.cjs` 通过 `contextBridge` 暴露 `window.electronAPI`，保持 `contextIsolation: true`、`nodeIntegration: false`。

### 7.3 开发模式

- `npm run dev`：仅 Vite（浏览器调试），代理 `/api` 到 `http://127.0.0.1:43178`。
- `npm run desktop:dev`：Vite + Electron，默认连接 IDE 启动的后端（`AGENTFORGE_DESKTOP_SERVER_URL`），不拉起 JAR。
- `npm run desktop:dev -- --embedded`：Electron 自己拉起 `runtime/server/*.jar`。

---

## 8. 一键调试与一键打包

### 8.1 命令矩阵（agentforge-desktop-ui/package.json）

```json
{
  "scripts": {
    "dev": "vite --host 127.0.0.1 --port 1420",
    "doctor": "node scripts/doctor.mjs",
    "desktop:dev": "node scripts/dev/desktop-dev.mjs",
    "desktop:dev:embedded": "npm run build:server && npm run runtime:server && node scripts/dev/desktop-dev.mjs --embedded",

    "build:server": "node scripts/build/build-server.mjs",
    "build:server:clean": "node scripts/build/build-server.mjs --clean",
    "runtime:server": "node scripts/build/prepare-server.mjs",
    "runtime:jre:mac-arm64": "node scripts/build/prepare-jre.mjs mac-arm64",
    "runtime:jre:mac-x64": "node scripts/build/prepare-jre.mjs mac-x64",
    "runtime:jre:win-x64": "node scripts/build/prepare-jre.mjs win-x64",

    "build": "vite build",
    "package:current": "node scripts/package/package-current.mjs",
    "package:mac": "node scripts/package/package-mac.mjs",
    "package:mac-arm64": "node scripts/package/package-desktop.mjs mac-arm64",
    "package:mac-x64": "node scripts/package/package-desktop.mjs mac-x64",
    "package:win-x64": "node scripts/package/package-desktop.mjs win-x64",
    "package:win-x64:cross": "node scripts/package/package-desktop.mjs win-x64 --cross"
  }
}
```

### 8.2 `scripts/build/build-server.mjs`

```text
1. 定位 mvn（AGENTFORGE_MAVEN / PATH / Homebrew / IDEA 内置）
2. 解析 AGENTFORGE_HOME（默认 ../AgentForge，可用环境变量覆盖）
3. 检查本地仓库是否已有 agentforge-agent-core:1.0.0-SNAPSHOT
   不存在或 AGENTFORGE_REBUILD_CORE=1 时从 AgentForge 源码安装
4. mvn -f ../agentforge-desktop-server/pom.xml clean package -DskipTests
5. 产物：agentforge-desktop-server/target/agentforge-desktop-server.jar
```

> 若后续核心构件发布到 Maven Central，第 3 步自动跳过。

### 8.3 `scripts/build/prepare-server.mjs`

复制 JAR 到 `runtime/server/agentforge-desktop-server.jar`。

### 8.4 `scripts/build/prepare-jre.mjs`（内置 JRE 8）

- 支持 `mac-arm64 / mac-x64 / win-x64` 三个目标。
- 通过 `packaging.config.local.json` 的 `jreHomes.<target>` 或 `AGENTFORGE_DESKTOP_JRE_HOME_*` 指定 JRE 8 目录（推荐 Temurin / Zulu）。
- JDK 8 不提供 `jlink`，直接复制完整 JRE 8 到 `runtime/jre`。
- 可选裁剪：删除 `src.zip`、`demo`、`sample`、`man` 等非运行时文件，保留 `bin/java` 与 `lib`。
- 校验目标架构：
  - macOS：`file runtime/jre/bin/java` 必须匹配 `arm64` / `x86_64`。
  - Windows：`bin/java.exe` 必须以 `MZ` 开头，`release` 中 `OS_ARCH` 必须为 `amd64`。
- 校验版本：`release` / `java -version` 必须为 `1.8`。
- macOS 打包前把 JRE 文件改为可写，避免更新时 EPERM。

配置示例 `packaging.config.local.json`：

```json
{
  "jreHomes": {
    "mac-arm64": "/Library/Java/JavaVirtualMachines/temurin-8-aarch64/Contents/Home",
    "mac-x64": "/Library/Java/JavaVirtualMachines/temurin-8-x64/Contents/Home",
    "win-x64": "C:\\Tools\\jre8-win-x64"
  },
  "jreArchives": {
    "win-x64": "/Users/me/.cache/jre8-win-x64.zip"
  }
}
```

### 8.5 三端本地打包能力（macOS arm64 / macOS x64 / Windows x64）

| 目标 | 建议构建机 | 内置 JRE 8 | electron-builder 参数 | 产物 |
| --- | --- | --- | --- | --- |
| `mac-arm64` | macOS arm64 | Temurin/Zulu JRE 8 aarch64 | `--mac dmg zip --arm64` | `...-mac-apple-silicon-arm64.dmg/.zip` |
| `mac-x64` | macOS x64（或 arm64 + x64 JRE） | JRE 8 x64 | `--mac dmg zip --x64` | `...-mac-intel-x64.dmg/.zip` |
| `win-x64` | Windows x64（推荐） | JRE 8 x64 | `--win nsis --x64` | `...-win-x64.exe` |

本地命令：

```bash
# 当前机器自动识别（mac arm64 / mac x64 / win x64）
npm run package:current

# macOS 双架构
npm run package:mac -- --arch arm64
npm run package:mac -- --arch intel

# Windows x64（在 Windows 构建机上）
npm run package:win-x64

# 可选：macOS 上通过 Docker/Wine 交叉构建 Windows
npm run package:win-x64:cross
```

约束与说明：

- macOS 两种架构都可以在同一台 Mac 本地打包，但必须为每个架构准备对应的 JRE 8。
- Windows NSIS 建议在 Windows x64 构建机上执行；跨机产物必须回到 Windows 做启动冒烟测试。
- 三端产物都内置 `runtime/jre` 与 `runtime/server/*.jar`，终端用户无需安装 Java。
- macOS 本地未配置 Developer ID 时自动做 ad-hoc 签名；公开分发仍需 Developer ID + notarization。
- Windows 公开分发建议配置代码签名证书，避免 SmartScreen 拦截。

### 8.6 `scripts/build/prepare-windows-jre.mjs`（Windows 交叉构建）

在 macOS 上准备 Windows x64 JRE 8：

```text
1. 优先使用配置的 jreHomes["win-x64"] 或 jreArchives["win-x64"]
2. 未配置时下载 Adoptium JRE 8：
   https://api.adoptium.net/v3/binary/latest/8/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk
3. 缓存到 .cache/windows-jre/jre8-win-x64.zip
4. 解压并定位包含 bin/java.exe 与 release 的根目录
5. 校验 JAVA_VERSION=1.8、OS_ARCH=amd64、java.exe 为 PE 文件
6. 复制到 runtime/jre
```

`scripts/package/check-windows-cross-env.mjs` 在执行 `package:win-x64:cross` 前检查：

- 当前系统必须是 macOS；
- 存在 `wine64` / `wine`，或 Docker Desktop 可用（推荐 `electronuserland/builder:wine`）。

### 8.7 `scripts/package/package-desktop.mjs`

```text
1. scripts/build/build-server.mjs
2. scripts/build/prepare-server.mjs
3. scripts/build/prepare-jre.mjs <target>（win-x64 跨机时走 prepare-windows-jre）
4. vite build
5. electron-builder 按目标平台打包
6. scripts/verify/verify-mac-package.mjs / verify-win-package.mjs
7. scripts/package/stage-update-artifacts.mjs（可选；v1 未启用自动更新可跳过）
```

electron-builder 关键配置：

```json
{
  "appId": "cloud.changlu.agentforge.desktop",
  "productName": "AgentForge Desktop",
  "asar": true,
  "directories": { "output": "release" },
  "files": ["dist/**/*", "electron/**/*", "package.json"],
  "extraResources": [
    { "from": "runtime/server", "to": "runtime/server" },
    { "from": "runtime/jre", "to": "runtime/jre" }
  ],
  "artifactName": "${productName}-${version}-${os}-${arch}.${ext}",
  "afterPack": "scripts/package/after-pack-mac.mjs",
  "mac": { "target": ["dmg", "zip"], "category": "public.app-category.developer-tools" },
  "win": { "target": [{ "target": "nsis", "arch": ["x64"] }] },
  "nsis": { "oneClick": false, "allowToChangeInstallationDirectory": true }
}
```

各目标会在命令行覆盖 `artifactName`：

```text
mac-arm64 → ${productName}-${version}-mac-apple-silicon-arm64.${ext}
mac-x64   → ${productName}-${version}-mac-intel-x64.${ext}
win-x64   → ${productName}-${version}-win-x64.${ext}
```

### 8.8 打包后校验

| 脚本 | 校验内容 |
| --- | --- |
| `verify-mac-package.mjs` | `.app`、可执行文件、内置 `java`、DMG 存在；`codesign --verify --deep --strict`；`file` 校验架构；`hdiutil verify` 校验 DMG |
| `verify-win-package.mjs` | `win-unpacked`、`java.exe`、server JAR、安装包存在；`java.exe` 为 PE 文件 |
| `after-pack-mac.mjs` | electron-builder 打包后清理扩展属性并做 ad-hoc 签名，避免 macOS 提示“已损坏” |

### 8.9 开发/打包验收命令

```bash
# 1) 后端 IDE 启动（IDEA 或命令行）
mvn -f agentforge-desktop-server/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=43178 --agentforge.desktop.root-dir=$HOME/.agentforge-desktop"

# 2) 连接本机后端调试
cd agentforge-desktop-ui
npm install
npm run desktop:dev

# 3) 一键内置后端调试
npm run desktop:dev:embedded

# 4) 当前机器一键打包
npm run package:current

# 5) 三端本地打包
npm run package:mac -- --arch arm64
npm run package:mac -- --arch intel
npm run package:win-x64
```

---

## 9. 与参考工程的复用关系

| 能力 | 来源 | 复用方式 |
| --- | --- | --- |
| Agent 运行时 | `agentforge-agent-core` | Maven 直接依赖 |
| 模型工厂 | `agentforge-model-registry` | Maven 直接依赖 |
| 会话 / SSE / 记忆 | `agentforge-service-web` | 按模块移植，改 SQLite 与包名 |
| 模型配置 / Agent 配置 | 本地 `config.json` | 参考字段结构，文件持久化与热加载 |
| 表结构 | `agentforge-service/sql/local/sqlite` | 会话/消息/记忆表迁移为桌面基线 DDL |
| 配置结构 | agentforge-service 的模型 / Agent 字段 | 改为本地 `config.json`（models + agents） |
| 工作台对话 UI | `agentforge-service-ui` | 移植消息渲染、SSE、重放、自动滚动 |
| Electron 壳 / JRE / 打包脚本 | 桌面端通用方案 | 结构复刻，替换品牌与变量前缀 |
| SQLite 迁移器 / Token / 健康探测 | 本地服务通用设计 | 设计复刻 |
| 会话左栏布局 | 通用工作台布局 | 精简为 v1 所需形态 |

### 9.1 方案取舍

| 点 | 通用参考方案 | 本项目 |
| --- | --- | --- |
| 对话协议 | submit + 轮询 stream（持久化事件队列） | v1 直接 SSE（更简单，对齐 agentforge） |
| 会话存储 | 单张会话快照表（payload_json） | 结构化 `session` + `message` 表 |
| 配置存储 | 单行配置 JSON | 本地 `config.json`（models + agents） |
| 数据目录 | `~/.agent-desktop` | `~/.agentforge-desktop` |

> v1 选择直接 SSE 是为了降低实现复杂度；`desktop_stream_event` 的 submit/poll 方案作为 v2 的断线续传增强。

---

## 10. 安全与风险

### 10.1 安全

- 后端仅监听 `127.0.0.1`，不对外暴露。
- 每次启动生成一次性 Token，Electron 通过 IPC 下发给渲染进程。
- `contextIsolation: true`、`nodeIntegration: false`、`sandbox: true`。
- 外链统一 `shell.openExternal`，禁止窗口内导航到外部页面。
- API Key 明文存于本地 `config.json`（建议权限 `0600`）；v2 使用系统钥匙串或本地加密。
- 不使用 `webSecurity: false`，不关闭 CORS 校验。

### 10.2 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| AgentForge 核心未发布到 Central | 首次构建失败 | `build-server` 自动本地 install；README 说明前置条件 |
| Spring Boot 2.7 与移植代码不兼容 | 编译错误 | 移植文件均基于 Spring Boot 2.7 / `javax.*`，无需改造 |
| SQLite 时间类型 | 映射异常 | 使用 `SqliteDateTypeHandler`（移植自 agentforge） |
| SSE 长连接被代理/超时 | 对话中断 | `spring.mvc.async.request-timeout=-1`；本地直连 |
| JDK 8 无 jlink 裁剪能力 | 安装包体积较大 | 直接内置 JRE 8，可删除示例/文档文件；v1 接受体积 |
| 应用退出残留 Java 进程 | 端口/资源泄漏 | `before-quit` + 超时 `SIGKILL`；启动时检查端口 |
| 单写 SQLite 并发 | `SQLITE_BUSY` | 连接池 1 + WAL + `busy_timeout` |
| `config.json` 被手动改坏 | 配置加载失败 | 保留上一版内存快照 + `.bak` + 启动时隔离为 `.corrupt-*` |
| 多入口并发写 `config.json` | 文件损坏/覆盖 | 单进程内串行写 + 临时文件 + 原子 rename |
| 增量 SQL 升级失败 | 应用无法启动 | 单脚本事务回滚 + 启动前校验 + 日志与升级脚本可重试 |

---

## 11. 里程碑与验收标准

### M0 仓库与构建骨架（0.5 天）

- 新增 `.gitignore`。
- 建立 `agentforge-desktop-server` Maven 骨架与 `agentforge-desktop-ui` Vite/Electron 骨架。
- `build:server`、`runtime:server` 可跑通。

### M1 后端基础（1 天）

- SQLite 数据源 + 按版本维护的增量 SQL + 启动自动迁移。
- `bin/upgrade-sqlite.sh` 升级脚本（与内置迁移器共用迁移台账）。
- `DesktopConfigStore`：`config.json` 读写、原子替换、热加载。
- `/api/local/health`。
- Token 过滤 + 统一响应/异常。

### M2 模型配置 + Agent 配置（1.5 天）

- 模型 CRUD + 连通性/Function Call 测试，保存写入 `config.json`。
- Agent CRUD，保存写入 `config.json` 并初始化 Agent。
- 文件热加载与运行时缓存清理。
- `ReActAgentFactory` 装配核心运行时。

### M3 会话与对话（1.5 天）

- 会话/消息表与接口。
- `/api/chat/stream` SSE + 中间件落库。
- 历史事件重放。

### M4 桌面壳与一键调试（1 天）

- Electron 拉起 JAR、健康探测、IPC。
- `desktop:dev` / `desktop:dev:embedded`。
- 工作台左会话右对话。
- 工作台右上角：模型选择、设置抽屉、调试/优雅双模式。

### M5 一键打包（1 天）

- JRE 8 运行时 + electron-builder。
- macOS arm64 / macOS x64 / Windows x64 本地打包与校验。
- 三端产物冒烟测试。

### v1 验收标准

1. `npm run desktop:dev` 能连接 IDE 启动的后端，工作台正常流式对话。
2. `npm run desktop:dev:embedded` 能一键构建 JAR 并由 Electron 拉起，无需手动装 Java。
3. 左侧会话历史可切换、置顶、重命名、删除；右侧消息可完整重放。
4. 工作台右上角可在调试模式 / 优雅模式之间切换，实时与历史消息同步切换且不重新请求。
5. 工作台右上角可选择 Agent 允许的模型，下一次对话按所选 `modelId` 初始化并记录到会话/消息。
6. 右上角设置按钮可打开配置抽屉，保存后 `config.json`、模型下拉与 Agent 信息立即更新。
7. 模型配置可新增并测试通过；Agent 可选择模型、写提示词、保存并调试。
8. 配置页保存后 `config.json` 立即更新；手动编辑 `config.json` 无需重启即可生效。
9. 旧版本数据库在覆盖安装后启动，能自动执行新增量 SQL 且会话数据不丢失。
10. `npm run package:current` 产出安装包；macOS arm64/x64 与 Windows x64 三端可本地打包。
11. 安装包离线启动后功能一致，内置 JRE 与 JAR 均可用。

---

## 12. 待确认问题（评审时请拍板）

已确定：

- 后端采用 **Spring Boot 2.7.18 + Java 8**（与 agentforge-service-web 一致）。
- 模型配置与 Agent 配置使用本地 `config.json`，不建数据库表。
- 工作台左栏顶部常驻 Agent 下拉。
- 工作台右上角提供模型选择、设置抽屉、调试/优雅双模式。

待确认：

1. **MCP / HTTP 插件**：确认 v1 不接入，仅保留 `pluginConfig` 字段。
2. **品牌与图标**：产品名暂定 `AgentForge Desktop`，图标与主题色后续替换。
3. **依赖构建**：是否接受首次构建时自动在 `../AgentForge` 执行核心模块 `mvn install`？若核心构件已发布到 Central，则不需要。

---

## 附录 A：初版文件清单

### agentforge-desktop-server

```text
pom.xml
bin/upgrade-sqlite.sh
sql/sqllite/1.0.x/20260101000000_desktop_baseline.sql
src/main/resources/application.yml
src/main/resources/defaults/config.json
src/main/resources/mapper/session/AgentSessionMapper.xml
src/main/resources/mapper/session/AgentSessionMessageMapper.xml
src/main/resources/mapper/agent/memory/AgentMemoryMapper.xml
src/main/java/.../AgentForgeDesktopServerApplication.java
src/main/java/.../common/ApiResponse.java
src/main/java/.../common/GlobalExceptionHandler.java
src/main/java/.../common/DesktopTokenFilter.java
src/main/java/.../config/DesktopProperties.java
src/main/java/.../config/DesktopDataSourceConfig.java
src/main/java/.../config/DesktopWebConfig.java
src/main/java/.../config/DesktopAgentConfiguration.java
src/main/java/.../store/DesktopConfigStore.java
src/main/java/.../store/DesktopSchemaMigrator.java
src/main/java/.../store/SqliteChatMemoryStore.java
src/main/java/.../store/SqliteDateTypeHandler.java
src/main/java/.../modules/system/LocalDesktopController.java
src/main/java/.../modules/system/LocalDesktopService.java
src/main/java/.../modules/model/**            # Controller / Service / Domain（config.json）
src/main/java/.../modules/agent/**            # Controller / Service / Runtime / Domain（config.json）
src/main/java/.../modules/session/**          # Controller / Service / Mapper / Domain
src/main/java/.../modules/chat/**             # Controller / Middleware / Protocol
```

### agentforge-desktop-ui

```text
package.json
vite.config.js
index.html
electron/main.mjs
electron/preload.cjs
scripts/doctor.mjs
scripts/lib/paths.mjs
scripts/lib/exec.mjs
scripts/lib/java.mjs
scripts/lib/maven.mjs
scripts/lib/ports.mjs
scripts/lib/proc.mjs
scripts/dev/desktop-dev.mjs
scripts/build/build-server.mjs
scripts/build/prepare-server.mjs
scripts/build/prepare-jre.mjs
scripts/build/prepare-windows-jre.mjs
scripts/package/check-windows-cross-env.mjs
scripts/package/package-current.mjs
scripts/package/package-mac.mjs
scripts/package/package-desktop.mjs
scripts/package/after-pack-mac.mjs
scripts/package/stage-update-artifacts.mjs
scripts/verify/verify-mac-package.mjs
scripts/verify/verify-win-package.mjs
src/main.js
src/App.vue
src/router/index.js
src/styles/chat.css
src/api/client.js
src/api/model.js
src/api/agent.js
src/api/session.js
src/api/chat.js
src/utils/sse.js
src/utils/sessionReplay.js
src/utils/chatAutoScroll.js
src/modes/index.js
src/modes/debug/*.vue
src/modes/elegant/*.vue
src/components/MarkdownView.vue
src/components/AgentMessageContent.vue
src/components/AppNavRail.vue
src/components/ModelSelect.vue
src/components/ConfigDrawer.vue
src/pages/workbench/ChatWorkbench.vue
src/pages/models/ModelsPage.vue
src/pages/agents/AgentsPage.vue
src/pages/agents/AgentConfigPage.vue
```

### 根目录

```text
.gitignore
README.md（实现后补充）
```

---

## 13. 实现落地记录（v0.1）

本节记录已经落地的实现与方案之间的差异，便于后续维护。

### 13.1 已落地内容

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 后端骨架 | ✅ | Spring Boot 2.7.18 / JDK 8 / SQLite |
| config.json | ✅ | 模型 + Agent 配置，原子写入、`.bak`、热加载 |
| 增量 SQL | ✅ | 版本目录 + 启动自动迁移 + `upgrade-sqlite.sh` |
| 模型配置 | ✅ | 增删改查、连通性测试、Function Call 测试 |
| Agent 配置 | ✅ | 模型集合、默认模型、提示词、记忆窗口、保存初始化 |
| 会话/消息 | ✅ | 会话列表、置顶、重命名、删除、消息重放 |
| SSE 对话 | ✅ | session / think / resp / tool_call / resp_end / error |
| 工作台 UI | ✅ | 左会话右对话、模型切换、双模式、设置抽屉 |
| Electron 壳 | ✅ | 内置 JRE 8 + JAR、健康探测、Token、IPC |
| macOS 打包 | ✅ | 已验证 arm64 DMG/ZIP |
| Windows 打包 | ✅ | 脚本与 JRE 准备逻辑已实现，需在 Windows 构建机验证 |

### 13.2 与方案的实现差异

- **MyBatis 采用注解 Mapper**：会话/消息使用 `@Select/@Insert/@Update` 注解，
  未创建 XML Mapper；模型与 Agent 配置为文件存储，不经过 MyBatis。
- **Electron 版本**：当前固定 `31.7.7`；打包时通过
  `--config.electronDist=node_modules/electron/dist` 复用本地发行目录，
  避免 electron-builder 重新下载。
- **JRE 选择**：`prepare-jre.mjs` 会优先寻找可用的 JRE 8；
  macOS 下自动回退到 Zulu 8 / Temurin 8。JDK 8 无 `jlink`，直接复制 JRE。
- **配置保存初始化**：Agent 保存后立即清理运行时缓存并预热；
  模型保存后清空全部 Agent 缓存，下一次对话使用新模型配置。

### 13.3 已验证场景

1. 后端 `mvn clean package` 通过，启动后 `/api/local/health` 正常。
2. 增量 SQL 首次执行成功，重复执行自动跳过。
3. 模型/Agent 保存写入 `config.json`，Agent 返回 `initOk=true`。
4. SSE 对话可返回 `session` 与 `error` 事件，会话与消息正确落库。
5. Playwright 验证工作台、模型页、Agent 页、设置抽屉，控制台无错误。
6. Electron 内置 JRE/JAR 启动成功，渲染进程通过 IPC 获取 baseUrl/token。
7. macOS arm64 打包产出 DMG/ZIP，安装包内含 `runtime/jre` 与 `runtime/server`。

### 13.4 已知限制

- 真实对话需要在模型配置中填入有效 API Key。
- Windows x64 安装包需在 Windows 构建机执行并做启动冒烟测试。
- 未配置 Apple Developer ID / Windows 代码签名，公开分发前需补充签名与公证。
- HTTP / MCP 插件、Agent 版本、Trace 可视化仍按方案留到后续版本。
