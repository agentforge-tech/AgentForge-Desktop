# 桌面端内置启动 `npm run desktop:dev:embedded` 详解

本文把 `npm run desktop:dev:embedded` 从命令入口、脚本编排、Vite/Electron 启动、Java 后端拉起、健康检查、Token/IPC、数据目录初始化到常见故障，按执行顺序逐段说明。

这里的“内置”指：**Java 后端由 Electron 主进程启动并托管**，不是 IDE 或另一个终端提前启动。开发模式使用本地 `runtime/server/*.jar` 与 `runtime/jre`，打包后使用安装包内 `resources/runtime`。

> 只想快速跑起来：先看第 0 节，再照第 5 节执行；排查问题直接看第 15 节。

---

## 0. 先看结论

### 0.1 一条命令做了什么

```bash
cd /path/to/AgentForge-Desktop/agentforge-desktop-ui
npm run desktop:dev:embedded
```

等价于依次执行：

```text
npm run build:server     # Maven 构建后端 JAR
npm run runtime:server   # 复制 JAR 到 runtime/server
node scripts/dev/desktop-dev.mjs --embedded
  ├── 启动 Vite（127.0.0.1 上的空闲端口）
  └── 启动 Electron
        └── 启动 java -jar runtime/server/agentforge-desktop-server.jar
              └── 健康检查通过后创建窗口
```

### 0.2 三个必须记住的点

1. **每次都会重新构建后端**：`build:server` 总是执行 `mvn clean package -DskipTests`，即使 `runtime/server` 和 `target` 里已有旧 JAR。
2. **内置模式不需要手工设置后端地址/Token**：Electron 会为本次启动动态分配端口、生成一次性 Token，并通过 IPC 交给渲染进程。
3. **必须从 `agentforge-desktop-ui` 目录执行**：脚本、`node_modules`、`runtime`、`.agentforge-dev` 都按 UI 工程为基准定位。

### 0.3 三种模式对照

| 模式 | 命令 | 谁启动 Java | 后端地址 | Token | 数据目录 |
| --- | --- | --- | --- | --- | --- |
| 内置开发模式（本文） | `npm run desktop:dev:embedded` | Electron | 动态空闲端口 | Electron 生成 | `agentforge-desktop-ui/.agentforge-dev` |
| 外部后端模式 | `npm run desktop:dev` | IDE / 终端 | `http://127.0.0.1:43179` | 可选，外部后端设置 | 由外部后端决定；UI 侧仍传 `.agentforge-dev` |
| 打包后内置模式 | 双击安装后的 App | Electron | 动态空闲端口 | Electron 生成 | `~/.agentforge-desktop`（除非设置环境变量） |

---

## 1. 命令入口与目录约定

### 1.1 必须在 UI 工程目录执行

```bash
cd /path/to/AgentForge-Desktop/agentforge-desktop-ui
npm run desktop:dev:embedded
```

原因：

- `npm run` 会读取 `agentforge-desktop-ui/package.json` 中的 scripts；
- `desktop-dev.mjs` 启动 Vite 时使用 `node_modules/vite`，Electron 从 `node_modules` 解析；
- Electron 开发模式以 `process.cwd()` 定位 `runtime/server`、`runtime/jre`，而 `desktop-dev.mjs` 会把 Electron 的 `cwd` 设为 UI 目录；
- 默认调试数据目录是 `agentforge-desktop-ui/.agentforge-dev`。

从仓库根目录执行会找不到 `package.json` 或解析到错误路径。

### 1.2 npm 命令展开

`agentforge-desktop-ui/package.json` 中：

```json
{
  "scripts": {
    "desktop:dev:embedded": "npm run build:server && npm run runtime:server && node scripts/dev/desktop-dev.mjs --embedded",
    "build:server": "node scripts/build/build-server.mjs",
    "runtime:server": "node scripts/build/prepare-server.mjs",
    "desktop:dev": "node scripts/dev/desktop-dev.mjs",
    "doctor": "node scripts/doctor.mjs"
  }
}
```

`&&` 表示任一阶段失败就停止，后续阶段不会执行。

### 1.3 完整进程树

```text
终端 / npm
├── node scripts/build/build-server.mjs         # 构建 JAR 后退出
├── node scripts/build/prepare-server.mjs       # 复制 JAR 后退出
└── node scripts/dev/desktop-dev.mjs --embedded # 常驻，直到退出
    ├── node node_modules/vite/bin/vite.js --host 127.0.0.1 --port <Vite端口> --strictPort
    └── Electron（node_modules/electron 可执行文件，加载 .）
        └── java -Xms128m -Xmx1536m -Dfile.encoding=UTF-8 \
                 -jar runtime/server/agentforge-desktop-server.jar \
                 --server.address=127.0.0.1 \
                 --server.port=<后端端口> \
                 --agentforge.desktop.token=<一次性Token> \
                 --agentforge.desktop.root-dir=<绝对数据目录>
```

进程归属关系：

- Vite 和 Electron 是 `desktop-dev.mjs` 的子进程；
- Java 后端是 **Electron 主进程**的子进程；
- 关闭 Electron 时，`desktop-dev.mjs` 会回收 Vite；Electron 退出前会终止 Java；
- `Ctrl+C` 会通过 `SIGINT`/`SIGTERM` 回收整棵进程树。

### 1.4 scripts 包结构

```text
agentforge-desktop-ui/scripts/
├── lib/                      # 共享模块，不直接执行
│   ├── paths.mjs             # uiDir / desktopDir / serverDir / runtimeDir
│   ├── exec.mjs              # 命令查找、spawn、带日志执行
│   ├── java.mjs              # Java 8 查找、版本判断
│   ├── maven.mjs             # Maven 查找、本地仓库、核心构件定位
│   ├── ports.mjs             # 空闲端口、HTTP 等待
│   └── proc.mjs              # 子进程退出回收
├── dev/desktop-dev.mjs       # Vite + Electron 编排
├── build/
│   ├── build-server.mjs      # 核心构件检查 + Maven 构建后端
│   ├── prepare-server.mjs    # 复制 JAR 到 runtime/server
│   ├── prepare-jre.mjs       # 复制 JDK/JRE 8 到 runtime/jre
│   └── prepare-windows-jre.mjs
├── package/                  # electron-builder 打包与前置检查
├── verify/                   # 打包产物校验
└── doctor.mjs                # 一键环境体检
```

---

## 2. 环境要求与查找规则

### 2.1 必需软件

| 环境 | 要求 | 用途 | 检查命令 |
| --- | --- | --- | --- |
| Node.js | 22+ | 执行 Node 脚本、Vite、Electron | `node --version` |
| npm | 10+ | 执行 npm scripts | `npm --version` |
| JDK | JDK 8（Zulu 8 / Temurin 8） | 编译 Spring Boot 后端，并可作为开发期 Java | `java -version` |
| Maven | 3.9+ | 安装 AgentForge 核心构件、构建后端 | `mvn -version` |
| 磁盘 | 首次构建建议至少 1 GiB 可用 | npm/Maven 依赖与构建缓存 | `df -h .` |

### 2.2 JDK 8 查找顺序

`build-server.mjs` 通过 `scripts/lib/java.mjs` 的 `findJavaHome()` 选择构建 JDK，顺序为：

```text
AGENTFORGE_DESKTOP_JAVA_HOME
-> JAVA_HOME
-> /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
-> /Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home
```

找到候选目录后，会执行 `<home>/bin/java -version`，只有输出匹配 `version "1.8` 才算通过。若都不匹配，报错：

```text
JDK 8 not found. Set AGENTFORGE_DESKTOP_JAVA_HOME to a JDK 8 home.
```

推荐显式设置：

```bash
export AGENTFORGE_DESKTOP_JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export JAVA_HOME="$AGENTFORGE_DESKTOP_JAVA_HOME"
"$AGENTFORGE_DESKTOP_JAVA_HOME/bin/java" -version   # 应显示 1.8.0_xxx
```

注意：该变量指向 JDK Home 目录，不是 `bin/java` 文件。

### 2.3 开发期 Java（Electron 运行后端时）

`electron/main.mjs` 的 `javaExecutable()` 规则不同，未打包时按以下顺序：

```text
<当前工作目录>/runtime/jre/bin/java
-> AGENTFORGE_DESKTOP_JAVA_HOME/bin/java
-> JAVA_HOME/bin/java
-> PATH 中的 java
```

`desktop-dev.mjs` 会把 Electron 的工作目录设为 `agentforge-desktop-ui`，所以第一项实际是：

```text
agentforge-desktop-ui/runtime/jre/bin/java
```

如果希望使用工程内 JRE 8：

```bash
npm run runtime:jre:mac-arm64   # Apple Silicon
npm run runtime:jre:mac-x64     # Intel Mac
npm run runtime:jre:win-x64     # Windows x64
```

准备完成后应存在 `agentforge-desktop-ui/runtime/jre/bin/java`。该目录优先级最高；如果里面的 Java 版本或架构不对，即使系统 JDK 8 正确也可能启动失败。

### 2.4 Maven 查找顺序

`build-server.mjs` 通过 `findMaven()` 查找：

```text
AGENTFORGE_MAVEN
-> AgentForge-Desktop/mvnw 或 mvnw.cmd
-> PATH 中的 mvn
-> macOS 常见路径：/opt/homebrew/bin/mvn、/usr/local/bin/mvn
-> IntelliJ IDEA / IDEA CE 内置 Maven
```

找不到时报错：

```text
Maven not found. Set AGENTFORGE_MAVEN or install Maven 3.9+.
```

### 2.5 AgentForge 核心构件

桌面后端依赖以下模块：

```text
agentforge-ai-bom
agentforge-model-api
agentforge-model-core
agentforge-model-openai
agentforge-model-anthropic
agentforge-model-registry
agentforge-agent-core
```

`build-server.mjs` 会先查询 Maven 本地仓库，并检查：

```text
<localRepository>/cloud/changlu/agentforge/agentforge-agent-core/1.0.0-SNAPSHOT/agentforge-agent-core-1.0.0-SNAPSHOT.jar
```

判断逻辑：

- 构件存在且未设置 `AGENTFORGE_REBUILD_CORE=1`：直接复用；
- 构件缺失或 `AGENTFORGE_REBUILD_CORE=1`：要求存在 AgentForge 源码，并执行核心模块安装；
- 源码目录默认是桌面仓库同级目录：

```text
<AgentForge-Desktop 父目录>/AgentForge
```

也可显式指定：

```bash
export AGENTFORGE_HOME=/path/to/AgentForge
```

源码目录不存在时报错：

```text
AgentForge source not found: <path>. Set AGENTFORGE_HOME or run AgentForge core install manually.
```

### 2.6 npm 依赖

首次使用或 `node_modules` 不存在时：

```bash
cd /path/to/AgentForge-Desktop/agentforge-desktop-ui
npm install
```

`desktop:dev:embedded` 依赖本地 `node_modules/vite` 与 `node_modules/electron`，不会自动执行 `npm install`。

### 2.7 一键体检

```bash
npm run doctor
```

`doctor` 只读检查以下内容，不会触发 Maven 构建：

| 检查项 | 通过标准 | 缺失时级别 |
| --- | --- | --- |
| Node.js | 主版本 >= 22 | warn |
| npm | 主版本 >= 10 | warn / fail |
| JDK 8 | 能定位到 Java 8 Home | fail |
| Maven | 能定位到 mvn | fail |
| AgentForge 核心构件 | 本地仓库已有 JAR，或存在源码目录 | warn / fail |
| 前端依赖 | `node_modules/vite` 与 `node_modules/electron` 存在 | fail |
| 开发期 JRE | `runtime/jre/bin/java` 存在 | warn |
| 内置后端 JAR | `runtime/server/agentforge-desktop-server.jar` 存在 | warn |
| 磁盘空间 | >= 1 GiB | warn；< 256 MiB 为 fail |
| 工作目录/调试数据目录 | 可写 | fail |

存在 `[fail]` 时脚本退出码为 1。

---

## 3. 首次启动步骤

### 第 0 步：环境体检

```bash
cd /path/to/AgentForge-Desktop/agentforge-desktop-ui
npm run doctor
```

先处理所有 `[fail]`，再决定是否处理 `[warn]`。

### 第 1 步：检查目录

```bash
cd /path/to/AgentForge-Desktop
test -f agentforge-desktop-ui/package.json && echo 'UI package ok'
test -f agentforge-desktop-server/pom.xml && echo 'server pom ok'
```

### 第 2 步：设置构建环境

macOS 示例：

```bash
export AGENTFORGE_DESKTOP_JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export JAVA_HOME="$AGENTFORGE_DESKTOP_JAVA_HOME"
export AGENTFORGE_HOME=/path/to/AgentForge
```

如果核心构件已经在本地 Maven 仓库，可以不设置 `AGENTFORGE_HOME`。

### 第 3 步：安装前端依赖

```bash
cd /path/to/AgentForge-Desktop/agentforge-desktop-ui
npm install
```

### 第 4 步：必要时准备开发期 JRE

如果工程内已有可用的 `runtime/jre` 可跳过；否则执行对应平台的 `runtime:jre:*`，或确保系统 `AGENTFORGE_DESKTOP_JAVA_HOME` / `JAVA_HOME` 指向 JDK 8。

### 第 5 步：启动内置模式

```bash
npm run desktop:dev:embedded
```

终端不要关闭：Vite、Electron、Java 后端都由该终端管理。

---

## 4. 阶段一：`build:server`

对应脚本：`agentforge-desktop-ui/scripts/build/build-server.mjs`

### 4.1 路径计算

脚本根据自身位置计算，不依赖调用者当前目录：

```text
scriptsDir  = <ui>/scripts
uiDir       = <ui>
desktopDir  = <repo>
serverDir   = <repo>/agentforge-desktop-server
```

### 4.2 执行顺序

```text
1. 查找 Maven
2. 查找 JDK 8
3. 构造环境：JAVA_HOME=<JDK8>，PATH=<JDK8>/bin:<原 PATH>
4. 查询 Maven 本地仓库
5. 检查 agentforge-agent-core:1.0.0-SNAPSHOT
6. 必要时安装核心构件
7. mvn -f agentforge-desktop-server/pom.xml clean package -DskipTests
8. 产出 agentforge-desktop-server/target/agentforge-desktop-server.jar
```

关键日志：

```text
[AgentForge] JAVA_HOME=<JDK8 Home>
[AgentForge] Maven=<mvn 路径>
[AgentForge] core artifacts found: <jar>            # 或 installing core artifacts from <src>
[AgentForge] server jar build completed
```

排查时以日志中的 `JAVA_HOME` / `Maven` 为准，终端里直接执行 `java -version` 不一定等于脚本实际使用的 Java。

### 4.3 核心构件安装命令

构件缺失时执行：

```bash
mvn -f /path/to/AgentForge/pom.xml \
  -pl :agentforge-ai-bom,:agentforge-model-api,:agentforge-model-core,\
:agentforge-model-openai,:agentforge-model-anthropic,\
:agentforge-model-registry,:agentforge-agent-core \
  -am install \
  -DskipTests \
  -Dspotless.check.skip=true \
  -Dspotless.apply.skip=true
```

首次执行可能下载 Maven 插件与外部依赖；网络、磁盘空间、`~/.m2` 权限都可能导致失败。

### 4.4 后端构建命令

```bash
mvn -f /path/to/AgentForge-Desktop/agentforge-desktop-server/pom.xml \
  clean package -DskipTests
```

`-DskipTests` 只跳过测试，不跳过编译、资源处理或 Spring Boot repackage。后端 POM 设置：

```xml
<finalName>agentforge-desktop-server</finalName>
```

最终产物是可直接 `java -jar` 启动的 Fat JAR：

```text
agentforge-desktop-server/target/agentforge-desktop-server.jar
```

### 4.5 关于 `clean`

当前 `build:server` 与 `build:server:clean` 都会执行 `clean package`，`--clean` 暂未产生行为差异。因此**不能**通过现有命令跳过重建；要复用现有 JAR，请单独执行第 6 节与第 7 节。

### 4.6 SQL 资源如何进入 JAR

后端 POM 把源码目录 `agentforge-desktop-server/sql/sqllite`（注意目录名拼写为 `sqllite`）作为资源复制到 JAR 内的：

```text
db/sqlite/increment
```

因此新增 SQL 时，源文件应放在：

```text
agentforge-desktop-server/sql/sqllite/<版本目录>/<文件名>.sql
```

例如：

```text
agentforge-desktop-server/sql/sqllite/1.0.x/20260101000000_desktop_baseline.sql
```

构建后进入 JAR 的路径为：

```text
db/sqlite/increment/1.0.x/20260101000000_desktop_baseline.sql
```

---

## 5. 阶段二：`runtime:server`

对应脚本：`agentforge-desktop-ui/scripts/build/prepare-server.mjs`

源文件优先级：

```text
AGENTFORGE_DESKTOP_SERVER_JAR_SOURCE
-> <repo>/agentforge-desktop-server/target/agentforge-desktop-server.jar
```

目标路径固定为：

```text
agentforge-desktop-ui/runtime/server/agentforge-desktop-server.jar
```

源文件不存在时报错：

```text
Java backend jar not found: <source>
Build it first with: npm run build:server
```

用自定义 JAR 调试：

```bash
export AGENTFORGE_DESKTOP_SERVER_JAR_SOURCE=/path/to/agentforge-desktop-server.jar
npm run runtime:server
```

### 5.1 不重新构建时如何启动

```bash
cd agentforge-desktop-ui
npm run runtime:server
node scripts/dev/desktop-dev.mjs --embedded
```

`desktop:dev:embedded` 本身没有“跳过构建”开关，必须绕开前两步。

---

## 6. 阶段三：`scripts/dev/desktop-dev.mjs`

### 6.1 模式判定

```text
embedded = 参数包含 --embedded 或 AGENTFORGE_DESKTOP_EMBEDDED=1
localServerPort = AGENTFORGE_DESKTOP_SERVER_PORT 或 43179
serverUrl = AGENTFORGE_DESKTOP_SERVER_URL 或 (embedded ? '' : http://127.0.0.1:43179)
```

关键点：

- `AGENTFORGE_DESKTOP_SERVER_URL` **优先级最高**；即使带了 `--embedded`，只要设置了它，Electron 会连接外部后端，不会启动内置 Java。
- `AGENTFORGE_DESKTOP_SERVER_PORT` 只影响外部模式默认地址，不影响 Electron 内置端口。
- 内置模式会向 Electron 传入空字符串 `AGENTFORGE_DESKTOP_SERVER_URL=''`，确保主进程走内置分支。

启动内置模式前建议清理外部变量：

```bash
unset AGENTFORGE_DESKTOP_SERVER_URL
unset AGENTFORGE_DESKTOP_SERVER_TOKEN
```

### 6.2 数据目录

```text
rootDir = AGENTFORGE_DESKTOP_ROOT_DIR 或 <agentforge-desktop-ui>/.agentforge-dev
```

该值会作为 `AGENTFORGE_DESKTOP_ROOT_DIR` 传给 Electron。需要隔离多套数据时：

```bash
AGENTFORGE_DESKTOP_ROOT_DIR="$PWD/.agentforge-dev-test" \
npm run desktop:dev:embedded
```

### 6.3 Vite 端口与启动

```text
起始端口 = AGENTFORGE_VITE_PORT 或 1420
实际端口 = 从起始端口开始，在 127.0.0.1 上寻找第一个可用端口（最多尝试 100 个）
```

启动命令：

```bash
node node_modules/vite/bin/vite.js \
  --host 127.0.0.1 \
  --port <实际端口> \
  --strictPort
```

- `--strictPort`：端口被占用时直接失败，不会静默换端口；
- 脚本最多等待 30 秒，每 180ms 访问一次 `http://127.0.0.1:<端口>`；
- 只要返回 `response.ok` 或状态码 < 500，就认为 Vite 可用。

日志：

```text
[AgentForge] renderer dev port: <端口>
[AgentForge] desktop root dir: <绝对路径>
```

### 6.4 Electron 启动环境

Vite 可用后启动 Electron，工作目录为 `agentforge-desktop-ui`，注入：

```text
ELECTRON_RENDERER_URL=http://127.0.0.1:<Vite端口>
AGENTFORGE_DESKTOP_SERVER_URL=<空字符串或外部地址>
AGENTFORGE_DESKTOP_ROOT_DIR=<数据目录>
```

`electron/main.mjs` 看到 `ELECTRON_RENDERER_URL` 后进入开发模式，窗口通过 `win.loadURL(ELECTRON_RENDERER_URL)` 加载页面；否则加载 `dist/index.html`。

### 6.5 退出回收

| 事件 | 行为 |
| --- | --- |
| Electron 退出 | 终止 Vite，`process.exit(Electron 退出码)` |
| Vite 非 0 退出 | 终止 Electron，`process.exit(Vite 退出码)` |
| `SIGINT` / `SIGTERM` | 终止所有子进程后以 0 退出 |

---

## 7. Electron 主进程：启动后端

对应文件：`agentforge-desktop-ui/electron/main.mjs`

### 7.1 启动决策

```text
EXTERNAL_SERVER_URL = process.env.AGENTFORGE_DESKTOP_SERVER_URL || ''
若 EXTERNAL_SERVER_URL 非空 -> 外部模式
否则 -> 内置模式
```

### 7.2 外部模式

1. 去掉末尾 `/`；
2. 用 800ms 超时探测 `GET /api/local/health`；
3. 如果外部地址恰好是 `http://127.0.0.1:43179` 或 `http://localhost:43179` 且探测失败，会再尝试兼容地址 `http://127.0.0.1:43178`；
4. 即使探测失败，也会返回外部模式配置并继续创建窗口（不会阻止启动）；
5. `workspace` 取健康检查返回的值，失败时为空字符串；
6. `runtime` 返回 `external-java`。

### 7.3 内置模式：JAR 与 Java

JAR：

```text
未打包：AGENTFORGE_DESKTOP_SERVER_JAR
       -> <process.cwd()>/runtime/server/agentforge-desktop-server.jar
已打包：<process.resourcesPath>/runtime/server/agentforge-desktop-server.jar
```

JAR 不存在时报错：

```text
agentforge-desktop-server.jar not found: <path>
Run: npm run runtime:server
```

Java：

```text
未打包：
  <process.cwd()>/runtime/jre/bin/java
  -> AGENTFORGE_DESKTOP_JAVA_HOME/bin/java
  -> JAVA_HOME/bin/java
  -> PATH 中的 java
已打包：
  <process.resourcesPath>/runtime/jre/bin/java
```

若 Java 是路径形式且文件不存在，报错：

```text
Embedded Java runtime not found: <path>
```

### 7.4 内置模式：端口、Token、根目录

| 项目 | 规则 |
| --- | --- |
| 后端端口 | `AGENTFORGE_DESKTOP_PORT`；否则让操作系统分配空闲端口（`listen(0, '127.0.0.1')`） |
| Token | `randomBytes(32).toString('hex')`，64 个十六进制字符，仅本次启动有效 |
| 根目录 | `AGENTFORGE_DESKTOP_ROOT_DIR` 转绝对路径；否则 `~/.agentforge-desktop` |

根目录会递归创建。注意：在 `desktop:dev:embedded` 流程中，`desktop-dev.mjs` 一定会传入 `AGENTFORGE_DESKTOP_ROOT_DIR`（默认 `.agentforge-dev`），所以开发模式实际使用的是 `.agentforge-dev`，而不是 `~/.agentforge-desktop`。

### 7.5 Java 启动参数

```text
java -Xms128m -Xmx<AGENTFORGE_DESKTOP_JAVA_XMX 或 1536m> -Dfile.encoding=UTF-8 \
  -jar <jar> \
  --server.address=127.0.0.1 \
  --server.port=<端口> \
  --agentforge.desktop.token=<Token> \
  --agentforge.desktop.root-dir=<绝对根目录>
```

补充：

- `AGENTFORGE_DESKTOP_JAVA_XMX` 默认 `1536m`，可改大；
- 子进程工作目录是根目录 `<root>`，不是 UI 目录；
- 子进程继承 Electron 的环境变量；
- `windowsHide: true`；
- stdout/stderr 统一转发到当前终端，并加前缀 `[desktop-server] `。

日志：

```text
[AgentForge] starting Java runtime: <java> -jar <jar>
[desktop-server] ...后端输出...
```

### 7.6 健康检查

Electron 最多等待 **60 秒**，每 **180ms** 轮询一次：

```http
GET http://127.0.0.1:<端口>/api/local/health
X-AgentForge-Desktop-Token: <Token>
```

同时满足以下条件才算 ready：

1. HTTP 响应成功（`response.ok`，即 2xx）；
2. 响应体 `code === 0`；
3. 响应体 `data.ready` 为真。

后端返回示例：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "ready": true,
    "workspace": "/abs/path/.agentforge-dev",
    "config": "/abs/path/.agentforge-dev/conf/config.json",
    "models": "/abs/path/.agentforge-dev/conf/models.json",
    "db": "/abs/path/.agentforge-dev/data/agentforge-desktop.db",
    "version": "0.1.0"
  }
}
```

失败处理：

- Java 进程提前退出：立即抛出 `agentforge-desktop-server exited early with code <n>`；
- 60 秒超时：抛出 `Timed out waiting for agentforge-desktop-server[: <原因>]`；
- 启动失败：终止已拉起的 Java（`SIGTERM`），弹窗并退出应用。

### 7.7 后端退出与回收

- Java 进程退出时，如果应用未在退出流程中且存在窗口，会向渲染进程发送：

```text
local-agent:backend-exit
payload: { code, signal }
```

- `before-quit`：如果 Java 子进程仍存活，发送 `SIGTERM`；
- `SIGINT`：调用 `stopBackend()`，先 `SIGTERM`，5 秒后仍未退出则 `SIGKILL`，然后退出；
- 非 macOS 关闭所有窗口会退出应用；macOS 保留应用，`activate` 时重新创建窗口。

---

## 8. Spring Boot 后端初始化

### 8.1 默认配置

`agentforge-desktop-server/src/main/resources/application.yml`：

```yaml
server:
  address: 127.0.0.1
  port: ${AGENTFORGE_DESKTOP_PORT:43179}
  shutdown: graceful

agentforge:
  desktop:
    root-dir: ${AGENTFORGE_DESKTOP_ROOT_DIR:${user.home}/.agentforge-desktop}
    token: ${AGENTFORGE_DESKTOP_TOKEN:}
    config-file: ${AGENTFORGE_DESKTOP_CONFIG:}

spring:
  mvc:
    async:
      request-timeout: -1
```

内置模式通过命令行参数覆盖端口、Token、根目录，所以实际端口是 Electron 动态分配的，`43179` 只是后端单独启动时的默认值。命令行参数优先级高于环境变量和 `application.yml`。

### 8.2 Token 校验

`DesktopTokenFilter` 对除 `OPTIONS` 外的所有请求生效：

- 当配置的 Token 非空时，必须携带：

```text
X-AgentForge-Desktop-Token: <Token>
```

- 缺失或错误返回：

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json;charset=UTF-8

{"code":401,"msg":"invalid desktop token"}
```

- Token 为空时直接放行（外部/单机调试模式常见）。

### 8.3 SQLite 数据源

`DesktopDataSourceConfig` 的关键参数：

| 参数 | 值 |
| --- | --- |
| JDBC URL | `jdbc:sqlite:<root>/data/agentforge-desktop.db` |
| busy_timeout | 5000ms |
| journal_mode | WAL |
| synchronous | NORMAL |
| foreign_keys | on |
| 连接池 | Hikari，最大 1、最小空闲 1 |

注意：WAL 模式会在数据库旁生成 `agentforge-desktop.db-wal`、`agentforge-desktop.db-shm`，属于正常现象。

### 8.4 数据目录结构

默认根目录（开发模式 `.agentforge-dev`，打包模式 `~/.agentforge-desktop`）：

```text
<root>/
├── conf/
│   ├── config.json                 # Agent 配置
│   ├── config.json.bak             # 保存时的上一版本备份（存在时）
│   ├── models.json                 # 模型配置
│   └── models.json.bak             # 保存时的上一版本备份（存在时）
├── data/
│   ├── agentforge-desktop.db       # SQLite 主库
│   ├── agentforge-desktop.db-wal   # WAL
│   └── agentforge-desktop.db-shm   # WAL 共享内存
├── logs/                           # 后端启动时创建
├── sql-applied/                    # 已执行 SQL 的归档
│   └── <版本目录>/<文件名>.sql
└── config.json                     # 旧版本路径，启动时会迁移到 conf/
```

如果设置了 `AGENTFORGE_DESKTOP_CONFIG`：

- 主配置路径变成该文件的绝对路径；
- 模型文件固定为配置文件的同级 `models.json`；
- 此时 `conf/` 不再是固定位置。

### 8.5 Agent/模型配置初始化

`DesktopConfigStore.ensureLoaded()` 在启动时执行：

1. 创建配置文件父目录；
2. 如果 `conf/config.json` 不存在，但根目录存在旧的 `config.json`，先迁移：
   - `config.json` -> `conf/config.json`；
   - 如果存在旧 `.bak` 且新 `.bak` 不存在，同步迁移；
3. 如果 `conf/config.json` 仍不存在，从 JAR 内 `defaults/config.json` 复制；资源不存在时写空配置；
4. 如果 `conf/models.json` 存在，直接加载；
5. 如果 `models.json` 不存在，但旧 `config.json` 内嵌了 models，则写出一份 `models.json` 并回写 `config.json`；
6. 否则从 JAR 内 `defaults/models.json` 复制；
7. 记录配置文件与模型文件的修改时间和大小，供后续变更检测。

当前仓库的默认值：

```json
// defaults/models.json
{
  "version": 1,
  "models": []
}
```

```json
// defaults/config.json（节选）
{
  "version": 1,
  "defaultAid": "agt_default",
  "agents": [
    {
      "aid": "agt_default",
      "name": "AgentForge Desktop",
      "agentConfig": {
        "systemPrompt": "你是一名专业的 AI 助手，请准确、清晰地回答用户问题。",
        "chatModel": { "default_model_id": null, "model_ids": [] }
      }
    }
  ]
}
```

因此**启动成功不等于可以立即对话**：默认模型列表为空，需要先在模型配置页新增模型，并为 Agent 选择默认模型。

### 8.6 配置保存与热加载

- 保存时先写 `*.tmp`，`force(true)` 落盘；
- 如果原文件存在，先复制为 `*.bak`；
- 再用原子移动覆盖正式文件（不支持原子移动时退化为普通移动）；
- POSIX 系统设置权限 `rw-------`；
- `reloadIfChanged()` 通过比较修改时间和文件大小判断是否需要重新加载；
- 模型文件加载失败时保留上一份内存快照，不会让配置整体失效；
- 热加载由 Agent 运行期和聊天请求触发，不需要重启后端。

### 8.7 增量 SQL 迁移

`DesktopSchemaMigrator` 在 Bean 初始化时执行：

1. 创建迁移记录表：

```sql
CREATE TABLE IF NOT EXISTS desktop_schema_migration (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  script_name TEXT    NOT NULL,
  version     TEXT    NOT NULL,
  checksum    TEXT    NOT NULL,
  applied_at  INTEGER NOT NULL
);
```

2. 读取已执行的 `script_name -> checksum`；
3. 扫描 JAR 内 `classpath*:db/sqlite/increment/**/*.sql`；
4. 按相对路径名称升序排序；
5. 对每个脚本计算 SHA-256：
   - 已执行且校验和一致：跳过；
   - 已执行但校验和不一致：报错 `incremental sql changed after execution: <name>`；
   - 未执行：在事务中执行 SQL，并插入迁移记录；
6. 执行成功后，把脚本原文归档到 `<root>/sql-applied/<相对路径>`；
7. 没有找到任何 SQL 时只记录警告，不视为失败。

迁移记录示例：

| 字段 | 示例 |
| --- | --- |
| `script_name` | `1.0.x/20260101000000_desktop_baseline.sql` |
| `version` | `1.0.x` |
| `checksum` | 64 位 SHA-256 十六进制字符串 |
| `applied_at` | 毫秒时间戳 |

---

## 9. 渲染进程、preload 与 IPC

### 9.1 preload 暴露的 API

`electron/preload.cjs` 通过 `contextBridge` 暴露 `window.electronAPI`：

| 方法 | 底层 IPC | 说明 |
| --- | --- | --- |
| `getLocalServerInfo()` | `local-agent:server-info` | 获取后端地址、Token、工作目录等 |
| `openPath(target)` | `local-agent:open-path` | 用系统方式打开路径 |
| `chooseDirectory(currentPath)` | `local-agent:choose-directory` | 打开目录选择对话框 |
| `onBackendExit(callback)` | 监听 `local-agent:backend-exit` | 后端退出通知，返回取消监听函数 |

### 9.2 serverInfo 返回结构

```json
{
  "baseUrl": "http://127.0.0.1:<端口>",
  "token": "<64 位十六进制 Token>",
  "workspace": "<后端根目录>",
  "platform": "darwin",
  "arch": "arm64",
  "runtime": "embedded-java"
}
```

`runtime` 取值：

- 内置模式：`embedded-java`
- 外部模式：`external-java`

### 9.3 前端 API 客户端

`src/api/client.js`：

1. `resolveServerInfo()` 首次调用会缓存 IPC Promise；
2. 没有 `window.electronAPI` 时退化为浏览器模式：

```json
{ "baseUrl": "", "token": "", "runtime": "browser" }
```

3. 请求地址为 `(baseUrl || '') + path`；
4. 自动添加：

```text
Content-Type: application/json
X-AgentForge-Desktop-Token: <token>   # token 非空时
```

5. 非 2xx 抛错；`code !== 0` 也抛错；成功时返回 `body.data`。

因此 Electron 模式不需要设置 `VITE_PROXY_TARGET`。Vite 代理只用于单独执行 `npm run dev` 的浏览器调试模式，目标默认 `http://127.0.0.1:43179`，可用 `VITE_PROXY_TARGET` 覆盖。

### 9.4 窗口与安全配置

`BrowserWindow` 关键参数：

| 参数 | 值 |
| --- | --- |
| 默认尺寸 | 1440 x 920 |
| 最小尺寸 | 1080 x 700 |
| preload | `electron/preload.cjs` |
| `contextIsolation` | `true` |
| `nodeIntegration` | `false` |
| `sandbox` | `true` |
| `webSecurity` | `true` |
| `spellcheck` | `false` |

外部链接处理：

- `window.open` / `target=_blank` 一律拒绝，并交给系统浏览器打开；
- 页面导航到其他 `http(s)` 源时阻止并交给系统浏览器；
- 开发模式下才允许加载 `ELECTRON_RENDERER_URL` 同源页面。

### 9.5 开发工具与冒烟检查

开发模式（存在 `ELECTRON_RENDERER_URL`）：

| 操作 | 快捷键/变量 |
| --- | --- |
| macOS 打开/关闭 DevTools | `Cmd + Option + I` |
| Windows/Linux 打开/关闭 DevTools | `Ctrl + Shift + I` |
| 右键菜单 | “检查元素” |
| 启动时自动打开 | `ELECTRON_OPEN_DEVTOOLS=1` |

冒烟检查：

```bash
ELECTRON_OPEN_DEVTOOLS=1 \
AGENTFORGE_DESKTOP_SMOKE=1 \
npm run desktop:dev:embedded
```

页面加载后约 2.5 秒，主进程会输出：

```text
[AgentForge] renderer text: "<页面文本前 800 字符>"
[AgentForge] electronAPI: object
[AgentForge] serverInfo: {"baseUrl":"...","token":"...","workspace":"...","runtime":"embedded-java"}
```

可快速判断 Renderer、preload、IPC、后端是否全部连通。

---

## 10. 端口与环境变量总表

### 10.1 端口

| 用途 | 默认 | 决定者 | 覆盖变量 | 说明 |
| --- | --- | --- | --- | --- |
| Vite 开发服务器 | 1420 起找空闲端口 | `desktop-dev.mjs` | `AGENTFORGE_VITE_PORT`（起始端口） | `--strictPort` |
| Electron 内置后端 | 操作系统分配 | `electron/main.mjs` | `AGENTFORGE_DESKTOP_PORT` | 随机空闲端口 |
| 外部后端默认地址 | 43179 | `desktop-dev.mjs` | `AGENTFORGE_DESKTOP_SERVER_PORT` | 仅外部模式 |
| 后端单独启动 | 43179 | `application.yml` | `AGENTFORGE_DESKTOP_PORT` | 内置模式会被命令行参数覆盖 |
| 旧版外部兼容探测 | 43178 | `electron/main.mjs` | 无 | 仅当外部地址是 43179/localhost 且健康检查失败 |

### 10.2 环境变量

| 变量 | 默认/行为 | 读取方 | 作用 |
| --- | --- | --- | --- |
| `AGENTFORGE_DESKTOP_JAVA_HOME` | 无 | 构建脚本、Electron | 构建/开发运行时 JDK/JRE Home |
| `JAVA_HOME` | 无 | 构建脚本、Electron | JDK 8 备用路径 |
| `AGENTFORGE_MAVEN` | 自动查找 | 构建脚本 | 指定 Maven 可执行文件 |
| `AGENTFORGE_HOME` | 桌面仓库同级 `AgentForge` | 构建脚本 | AgentForge 核心源码目录 |
| `AGENTFORGE_REBUILD_CORE` | 未设置 | 构建脚本 | 为 `1` 时强制重新安装核心构件 |
| `AGENTFORGE_VITE_PORT` | `1420` | `desktop-dev.mjs` | Vite 起始端口 |
| `AGENTFORGE_DESKTOP_EMBEDDED` | 未设置 | `desktop-dev.mjs` | 为 `1` 时等价于 `--embedded` |
| `AGENTFORGE_DESKTOP_ROOT_DIR` | 开发 `.agentforge-dev`；打包 `~/.agentforge-desktop` | `desktop-dev.mjs`、Electron、Spring | 配置、数据库、日志、SQL 归档根目录 |
| `AGENTFORGE_DESKTOP_CONFIG` | `<root>/conf/config.json` | Spring | 覆盖 Agent 主配置文件 |
| `AGENTFORGE_DESKTOP_SERVER_JAR` | `runtime/server/agentforge-desktop-server.jar` | Electron | Electron 直接运行的 JAR |
| `AGENTFORGE_DESKTOP_SERVER_JAR_SOURCE` | `../agentforge-desktop-server/target/...jar` | `prepare-server.mjs` | `runtime:server` 的复制源 |
| `AGENTFORGE_DESKTOP_PORT` | 随机空闲端口 | Electron、Spring | 固定内置后端端口 |
| `AGENTFORGE_DESKTOP_SERVER_PORT` | `43179` | `desktop-dev.mjs` | 仅外部模式默认后端端口 |
| `AGENTFORGE_DESKTOP_JAVA_XMX` | `1536m` | Electron | 内置后端 JVM 最大堆 |
| `AGENTFORGE_DESKTOP_MODEL_API_KEY` | 无 | Spring | 模型 API Key 为空时的备用 Key |
| `AGENTFORGE_DESKTOP_SERVER_URL` | 内置模式为空 | `desktop-dev.mjs`、Electron | 设置后优先连接外部后端 |
| `AGENTFORGE_DESKTOP_SERVER_TOKEN` | 无 | `desktop-dev.mjs`、Electron | 外部后端 Token |
| `ELECTRON_RENDERER_URL` | 无 | Electron | 存在时进入开发模式并加载该 URL |
| `ELECTRON_OPEN_DEVTOOLS` | 无 | Electron | 为 `1` 时自动打开 DevTools |
| `AGENTFORGE_DESKTOP_SMOKE` | 无 | Electron | 为 `1` 时执行冒烟输出 |
| `VITE_PROXY_TARGET` | `http://127.0.0.1:43179` | Vite | 浏览器调试代理目标 |

### 10.3 优先级

```text
后端端口：命令行 --server.port
        > AGENTFORGE_DESKTOP_PORT
        > application.yml 默认 43179

后端地址模式：AGENTFORGE_DESKTOP_SERVER_URL（非空）
            > 内置模式

数据根目录：命令行 --agentforge.desktop.root-dir
          > AGENTFORGE_DESKTOP_ROOT_DIR
          > ~/.agentforge-desktop

主配置路径：AGENTFORGE_DESKTOP_CONFIG（非空）
          > <root>/conf/config.json
```

---

## 11. 启动成功日志逐行解读

```text
[AgentForge] JAVA_HOME=/Library/.../zulu-8.jdk/Contents/Home
  -> build-server 选中的构建 JDK

[AgentForge] Maven=/opt/homebrew/bin/mvn
  -> build-server 选中的 Maven

[AgentForge] core artifacts found: /Users/.../.m2/.../agentforge-agent-core-1.0.0-SNAPSHOT.jar
  -> 复用本地核心构件；若显示 installing 则正在从源码安装

[AgentForge] server jar build completed
  -> 后端 JAR 构建完成

[AgentForge] server runtime prepared: .../runtime/server/agentforge-desktop-server.jar
  -> JAR 已复制到 runtime/server

[AgentForge] renderer dev port: 1420
  -> Vite 实际端口

[AgentForge] desktop root dir: /Users/.../agentforge-desktop-ui/.agentforge-dev
  -> 本次调试数据根目录

[AgentForge] Electron will start runtime/server/agentforge-desktop-server.jar automatically
  -> 内置模式；若显示 attach Java runtime 则是外部模式

[AgentForge] starting Java runtime: ... -jar ...
  -> Electron 正在拉起 Java

[desktop-server] ... Spring Boot 日志 ...
  -> Java 子进程输出，前缀固定

[AgentForge] backend ready: http://127.0.0.1:<动态端口> (embedded)
  -> 健康检查通过

[AgentForge] renderer loaded: AgentForge Desktop
  -> 页面加载完成
```

---

## 12. 启动成功验收

### 12.1 文件检查

在 `agentforge-desktop-ui` 下：

```bash
test -s runtime/server/agentforge-desktop-server.jar && echo 'server jar ok'
test -x runtime/jre/bin/java && echo 'embedded java ok'
test -f .agentforge-dev/conf/config.json && echo 'agent config ok'
test -f .agentforge-dev/conf/models.json && echo 'model config ok'
test -f .agentforge-dev/data/agentforge-desktop.db && echo 'sqlite db ok'
```

> 如果设置了 `AGENTFORGE_DESKTOP_ROOT_DIR` 或 `AGENTFORGE_DESKTOP_CONFIG`，请替换为实际路径。

### 12.2 工作台功能检查

1. Electron 窗口正常打开，页面没有白屏；
2. 模型配置页可新增模型并通过连通性测试；
3. Agent 配置页可为默认 Agent 选择模型并保存；
4. 工作台模型选择器能加载模型；
5. 发送消息后 SSE 流式响应、会话落库、历史消息重放正常。

### 12.3 为什么不能直接 curl 内置后端

内置模式的端口和 Token 都是 Electron 运行时动态生成的，终端里通常拿不到 Token。直接请求：

```bash
curl http://127.0.0.1:<动态端口>/api/local/health
```

会返回 401：

```json
{"code":401,"msg":"invalid desktop token"}
```

应通过 Electron 日志、`AGENTFORGE_DESKTOP_SMOKE=1` 或界面调用验证。如果你已经把动态端口和 Token 从 `serverInfo` 日志中取出，则可以：

```bash
curl -H 'X-AgentForge-Desktop-Token: <token>' \
  http://127.0.0.1:<动态端口>/api/local/health
```

---

## 13. 常用调试命令

### 13.1 标准内置启动

```bash
cd /path/to/AgentForge-Desktop/agentforge-desktop-ui

export AGENTFORGE_DESKTOP_JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export JAVA_HOME="$AGENTFORGE_DESKTOP_JAVA_HOME"
export AGENTFORGE_HOME=/path/to/AgentForge

unset AGENTFORGE_DESKTOP_SERVER_URL
unset AGENTFORGE_DESKTOP_SERVER_TOKEN

npm run doctor
npm run desktop:dev:embedded
```

### 13.2 隔离数据目录

```bash
AGENTFORGE_DESKTOP_ROOT_DIR="$PWD/.agentforge-dev-test" \
npm run desktop:dev:embedded
```

适合测试配置初始化、数据库迁移或不同模型配置。

### 13.3 自动打开 DevTools 并冒烟

```bash
ELECTRON_OPEN_DEVTOOLS=1 \
AGENTFORGE_DESKTOP_SMOKE=1 \
npm run desktop:dev:embedded
```

### 13.4 固定内置后端端口

```bash
AGENTFORGE_DESKTOP_PORT=43179 npm run desktop:dev:embedded
```

端口必须未被占用。通常更推荐随机端口，方便并行启动多个实例。

### 13.5 复用现有 JAR，不重新构建

```bash
cd /path/to/AgentForge-Desktop/agentforge-desktop-ui
npm run runtime:server
node scripts/dev/desktop-dev.mjs --embedded
```

### 13.6 指定自定义 JAR

```bash
export AGENTFORGE_DESKTOP_SERVER_JAR=/path/to/agentforge-desktop-server.jar
node scripts/dev/desktop-dev.mjs --embedded
```

注意：使用该变量时，Electron 会直接运行指定 JAR；`runtime:server` 不再必需。

### 13.7 指定配置文件

```bash
export AGENTFORGE_DESKTOP_CONFIG=/path/to/config.json
node scripts/dev/desktop-dev.mjs --embedded
```

模型文件会使用同级的 `models.json`。

---

## 14. 故障排查

| 现象 | 可能原因 | 处理方式 |
| --- | --- | --- |
| `npm: command not found` | Node.js/npm 未安装或不在 PATH | 安装 Node.js 22+，重开终端 |
| `Maven not found` | 未找到 Maven | 安装 Maven 3.9+，或设置 `AGENTFORGE_MAVEN` |
| `JDK 8 not found` | 未找到 Java 8 | 设置 `AGENTFORGE_DESKTOP_JAVA_HOME`，确认输出 1.8 |
| Maven 使用 Java 21/17 | 候选路径指向高版本 JDK | 显式设置 `AGENTFORGE_DESKTOP_JAVA_HOME` 与 `JAVA_HOME` |
| `AgentForge source not found` | 核心构件缺失且源码目录不存在 | 设置 `AGENTFORGE_HOME`，或先安装核心构件到本地仓库 |
| Maven 下载失败 | 网络、仓库权限或磁盘不足 | 检查网络、`~/.m2` 权限、`df -h .` |
| `Java backend jar not found` | `runtime/server` 未复制成功 | 确认 `build:server` 成功，再执行 `npm run runtime:server` |
| `Embedded Java runtime not found` | `runtime/jre` 缺少 Java 或路径错误 | 执行对应 `runtime:jre:*`，或设置有效 JDK 8 |
| `agentforge-desktop-server.jar not found` | Electron 找不到 JAR | 执行 `npm run runtime:server`，或设置 `AGENTFORGE_DESKTOP_SERVER_JAR` |
| Electron 窗口不出现 | Vite 未启动、Electron 启动失败或健康检查超时 | 查看 Vite、Electron、`[desktop-server]` 日志 |
| `Timed out waiting for agentforge-desktop-server` | Java 启动失败、JAR 不兼容、端口冲突、初始化失败 | 看 Java 首个异常；检查端口、目录权限、SQLite 迁移日志 |
| `agentforge-desktop-server exited early with code N` | Java 进程提前退出 | 查看 `[desktop-server]` 中的异常栈 |
| 页面 API 返回 401 | Token 未传入、外部 Token 错误、混用外部变量 | 确认 `window.electronAPI` 正常；内置模式 `unset AGENTFORGE_DESKTOP_SERVER_URL` |
| 页面能打开但没有模型 | 默认 `models.json` 为空 | 进入模型配置页新增模型 |
| 修改配置未生效 | 改错数据目录或 JSON 非法 | 看日志 `desktop root dir`，检查 `<root>/conf/*.json` |
| 配置保存后出现 `.bak` / `.tmp` | 保存流程正常产物 | `.bak` 是上一版本，`.tmp` 残留说明移动前异常，可删除 |
| 调试时连到旧后端 | 设置了 `AGENTFORGE_DESKTOP_SERVER_URL` | `unset` 后重启 |
| 提示 `incremental sql changed after execution` | 已执行 SQL 内容被修改 | 不要修改历史 SQL；新增增量脚本 |
| 磁盘不足/构建中断 | Maven/npm 缓存与构建产物过大 | 清理 `~/.m2`、`node_modules`、`target`，预留 >= 1 GiB |

---

## 15. 当前工作区环境核验

环境状态会随机器、依赖和磁盘变化，以 `npm run doctor` 实时输出为准。最近一次核验：

```text
[ok] Node.js        v22.16.0
[ok] npm            10.9.2
[ok] JDK 8          /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
[ok] Maven          3.9.6
[ok] 核心构件       本地 Maven 仓库已存在 agentforge-agent-core
[ok] 前端依赖       node_modules/vite、node_modules/electron 已存在
[ok] 开发期 JRE     runtime/jre/bin/java 已存在
[ok] 内置后端 JAR   runtime/server/agentforge-desktop-server.jar 已存在
[ok] 磁盘空间       约 14.7 GiB 可用
```

注意：`runtime/server` 中已有 JAR 不代表 `desktop:dev:embedded` 会跳过构建，脚本仍会先执行 `build:server`。

---

## 16. 代码索引

| 关注点 | 文件 |
| --- | --- |
| npm 命令链 | `agentforge-desktop-ui/package.json` |
| 共享路径/命令/Java/Maven/端口/进程 | `scripts/lib/*.mjs` |
| 核心构件检查、Maven 构建后端 | `agentforge-desktop-ui/scripts/build/build-server.mjs` |
| JAR 复制 | `agentforge-desktop-ui/scripts/build/prepare-server.mjs` |
| JRE 准备 | `agentforge-desktop-ui/scripts/build/prepare-jre.mjs` |
| Vite + Electron 编排 | `agentforge-desktop-ui/scripts/dev/desktop-dev.mjs` |
| 环境体检 | `agentforge-desktop-ui/scripts/doctor.mjs` |
| 内置 Java、健康检查、Token、窗口、IPC | `agentforge-desktop-ui/electron/main.mjs` |
| preload API | `agentforge-desktop-ui/electron/preload.cjs` |
| 前端 API 地址、Token、错误处理 | `agentforge-desktop-ui/src/api/client.js` |
| Vite 端口与浏览器代理 | `agentforge-desktop-ui/vite.config.js` |
| 后端默认配置 | `agentforge-desktop-server/src/main/resources/application.yml` |
| 默认 Agent/模型配置 | `agentforge-desktop-server/src/main/resources/defaults/` |
| 健康检查/应用配置接口 | `modules/system/LocalDesktopController.java` |
| Token 校验 | `common/DesktopTokenFilter.java` |
| SQLite 数据源与 WAL | `config/DesktopDataSourceConfig.java` |
| 数据根目录/配置路径 | `config/DesktopProperties.java` |
| Agent/模型配置初始化与热加载 | `store/DesktopConfigStore.java` |
| 增量 SQL 扫描、校验、归档 | `store/DesktopSchemaMigrator.java` |
| SQL 源文件 | `agentforge-desktop-server/sql/sqllite/` |
| 后端构建资源映射 | `agentforge-desktop-server/pom.xml` |