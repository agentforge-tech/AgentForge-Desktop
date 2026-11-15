# AgentForge Desktop 日志统一管控详解

本文说明 AgentForge Desktop 的日志输出、目录定位、滚动归档、容量约束、开发/安装环境差异、SQLite 迁移日志和故障排查方式。

> 当前统一日志文件主要承载 **Java 后端运行日志**。Electron 主进程仍输出到终端/宿主进程控制台；Electron 同时会将 Java 标准输出加上 `[desktop-server]` 前缀转发。

---

## 0. 快速结论

### 0.1 默认日志位置

| 运行方式 | 数据根目录 | 当前日志 |
| --- | --- | --- |
| 安装版 | `~/.agentforge-desktop` | `~/.agentforge-desktop/logs/agentforge-desktop.log` |
| 本地嵌入开发 | `agentforge-desktop-ui/.agentforge-dev` | `agentforge-desktop-ui/.agentforge-dev/logs/agentforge-desktop.log` |
| 自定义目录 | `<custom-root>` | `<custom-root>/logs/agentforge-desktop.log` |

### 0.2 归档位置

```text
<root-dir>/logs/archive/
`-- agentforge-desktop.2026-11-10.0.log.gz
```

### 0.3 默认策略

| 项目 | 策略 |
| --- | --- |
| 日志级别 | Root `INFO` |
| 当前文件 | `agentforge-desktop.log` |
| 时间滚动 | 按天 |
| 大小滚动 | 单个归档最大 `20MB` |
| 保留时间 | `30` 天 |
| 归档总容量 | 最大 `1GB` |
| 归档压缩 | Gzip (`.gz`) |
| 启动时清理 | 是，按保留期和总容量清理 |
| 字符集 | UTF-8 |
| 输出目标 | Console + Rolling File |

---

## 1. 整体日志链路

```text
Java/SLF4J Logger
  `-- Logback
      |-- CONSOLE
      |    `-- stdout/stderr
      |         `-- Electron 转发为 [desktop-server] ...
      `-- ROLLING_FILE
           |-- <root-dir>/logs/agentforge-desktop.log
           `-- <root-dir>/logs/archive/*.log.gz
```

应用业务代码统一使用 SLF4J：

```java
private static final Logger log = LoggerFactory.getLogger(CurrentClass.class);

log.info("message: {}", value);
log.warn("message: {}", value);
log.error("message: {}", value, exception);
```

不要在 Java 业务代码中使用 `System.out.println`，否则无法统一控制日志级别、格式和滚动归档。

---

## 2. Logback 配置文件

统一配置位于：

```text
agentforge-desktop-server/src/main/resources/logback-spring.xml
```

使用 `logback-spring.xml` 而不是普通 `logback.xml` 的原因：

- Spring Boot 可以在 Logback 初始化时注入 Spring Environment 属性；
- 可通过 `<springProperty>` 读取 `agentforge.desktop.root-dir`；
- 日志目录因此可与配置、SQLite 和其他桌面数据共用同一根目录；
- 无需向用户暴露或要求用户维护外部 `conf/logback.xml`。

Spring Boot Web Starter 已默认引入 SLF4J + Logback，当前不需要在 `pom.xml` 重复添加 Logback 版本依赖。

---

## 3. 日志根目录如何受控

日志目录不是独立硬编码，它始终由桌面端数据根目录派生：

```text
LOG_HOME = <effective agentforge.desktop.root-dir>/logs
```

### 3.1 安装版默认路径

Electron `main.mjs` 启动时计算：

```javascript
const rootDir = process.env.AGENTFORGE_DESKTOP_ROOT_DIR
  ? path.resolve(process.env.AGENTFORGE_DESKTOP_ROOT_DIR)
  : path.join(app.getPath('home'), '.agentforge-desktop')
```

未设置环境变量时：

```text
rootDir = <当前用户主目录>/.agentforge-desktop
logs   = <当前用户主目录>/.agentforge-desktop/logs
```

macOS 典型实际路径：

```text
/Users/edy/.agentforge-desktop/logs/agentforge-desktop.log
```

Windows 根据 Electron `app.getPath('home')` 得到用户 Home，典型结果类似：

```text
C:\Users\<user>\.agentforge-desktop\logs\agentforge-desktop.log
```

Electron 会先创建 `rootDir`，然后向 Java 传入：

```text
--agentforge.desktop.root-dir=<absolute-root-dir>
```

### 3.2 本地开发默认路径

`scripts/dev/desktop-dev.mjs` 默认计算：

```javascript
const rootDir = process.env.AGENTFORGE_DESKTOP_ROOT_DIR
  || path.join(uiDir, '.agentforge-dev')
```

它将 `AGENTFORGE_DESKTOP_ROOT_DIR` 传入 Electron 环境。Electron 再将同一绝对路径转换为 Java 命令行属性，所以默认日志为：

```text
agentforge-desktop-ui/.agentforge-dev/logs/agentforge-desktop.log
```

这样开发数据和安装版用户数据不会混在一起。

### 3.3 用环境变量自定义

```bash
export AGENTFORGE_DESKTOP_ROOT_DIR=/path/to/custom-agentforge-data
npm run desktop:dev:embedded
```

实际日志位置：

```text
/path/to/custom-agentforge-data/logs/agentforge-desktop.log
```

环境变量影响的不只是日志，还包括：

```text
<custom-root>/conf
<custom-root>/data
<custom-root>/logs
```

### 3.4 直接启动 Java 时自定义

不经过 Electron，直接调试 JAR 时：

```bash
java -jar agentforge-desktop-server.jar \
  --agentforge.desktop.root-dir=/tmp/agentforge-local
```

日志位置：

```text
/tmp/agentforge-local/logs/agentforge-desktop.log
```

也可使用环境变量：

```bash
export AGENTFORGE_DESKTOP_ROOT_DIR=/tmp/agentforge-local
java -jar agentforge-desktop-server.jar
```

`application.yml` 将它映射为：

```yaml
agentforge:
  desktop:
    root-dir: ${AGENTFORGE_DESKTOP_ROOT_DIR:${user.home}/.agentforge-desktop}
```

### 3.5 最终优先级

从 Java/Spring 视角，有效根目录的优先级为：

```text
Java 命令行 --agentforge.desktop.root-dir
  > Spring 配置属性/环境映射
  > ${user.home}/.agentforge-desktop 默认值
```

但在正常 Electron 链路中，Electron 会先读取 `AGENTFORGE_DESKTOP_ROOT_DIR`，再把已解析的绝对路径作为 Java 命令行参数传入。因此不会出现 Electron 数据目录和 Java 日志目录不一致的情况。

### 3.6 Logback 如何取到最终路径

`logback-spring.xml` 使用：

```xml
<springProperty scope="context"
                name="DESKTOP_ROOT"
                source="agentforge.desktop.root-dir"
                defaultValue="${user.home}/.agentforge-desktop"/>
<property name="LOG_HOME" value="${DESKTOP_ROOT}/logs"/>
```

`springProperty` 读取的是 Spring Environment 中最终生效的 `agentforge.desktop.root-dir`，所以命令行参数、环境变量映射和默认值会自动作用到日志路径。

---

## 4. 日志文件与滚动归档

### 4.1 当前日志

```text
<root-dir>/logs/agentforge-desktop.log
```

Logback 以 append 方式写入，应用重启不会直接清空当前日志。

### 4.2 按日期归档

进入新日期后，旧日志进入：

```text
<root-dir>/logs/archive/agentforge-desktop.<yyyy-MM-dd>.<index>.log.gz
```

### 4.3 按大小切分

即使在同一天，当归档分片达到 `20MB` 时也会使用新索引：

```text
agentforge-desktop.2026-11-10.0.log.gz
agentforge-desktop.2026-11-10.1.log.gz
agentforge-desktop.2026-11-10.2.log.gz
```

### 4.4 清理策略

```xml
<maxHistory>30</maxHistory>
<totalSizeCap>1GB</totalSizeCap>
<cleanHistoryOnStart>true</cleanHistoryOnStart>
```

清理条件：

- 超过 30 天的归档会被删除；
- 即使未到 30 天，归档总量超过 1GB 时也会从旧到新清理；
- 应用启动时会检查历史归档是否超出策略。

---

## 5. 日志格式

默认格式：

```text
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{48} [%file:%line] - %msg%n
```

示例：

```text
2026-11-10 08:09:26.513 [main] INFO  c.c.a.desktop.store.DesktopSchemaMigrator [DesktopSchemaMigrator.java:232] - Migration ledger updated and transaction committed: ...
```

| 字段 | 用途 |
| --- | --- |
| 毫秒时间 | 对齐 Electron、Spring 和 SQL 迁移时序 |
| 线程名 | 判断主启动线程、Web 请求线程或异步任务 |
| 级别 | INFO/WARN/ERROR 等 |
| Logger | 定位类/模块 |
| 源文件:行号 | 快速定位打印位置 |
| 消息 | 业务事件和上下文 |

---

## 6. SQLite 增量迁移日志

`DesktopSchemaMigrator` 会输出一条可连续跟踪的迁移流水线。

### 6.1 有新脚本

```text
SQLite incremental migration started, classpath root: db/sqlite/increment/
SQLite migration ledger loaded: 0 applied script(s)
SQLite incremental SQL scan completed: 1 discovered, 1 pending, 0 already applied
Applying incremental SQL [1/1]: script=1.0.x/20260101000000_desktop_baseline.sql, version=1.0.x, checksum=1d71171973a0
Incremental SQL statements executed successfully: script=1.0.x/20260101000000_desktop_baseline.sql
Migration ledger updated and transaction committed: table=desktop_schema_migration, script=..., version=1.0.x, checksum=1d71171973a0
Incremental SQL applied [1/1]: script=1.0.x/20260101000000_desktop_baseline.sql
SQLite incremental migration completed: 1 applied this run, 1 total discovered, elapsed=29 ms
```

这些日志能确认：

1. JAR 内是否找到 SQL；
2. 台账中已有多少记录；
3. 本次有多少待执行脚本；
4. 当前执行的脚本、版本和 checksum 前缀；
5. SQL 语句是否执行成功；
6. `desktop_schema_migration` 是否更新并与 SQL 一起提交；
7. 本次完成数量和总耗时。

### 6.2 没有新脚本

```text
SQLite migration ledger loaded: 1 applied script(s)
SQLite incremental SQL scan completed: 1 discovered, 0 pending, 1 already applied
No pending incremental SQL; verifying checksums of applied scripts
SQLite incremental migration completed: 0 applied this run, 1 total discovered, elapsed=<ms> ms
```

即使没有新 SQL，迁移器仍会对已执行脚本做 checksum 验证。

### 6.3 执行失败

```text
Incremental SQL transaction rolled back: script=<script_name>
Incremental SQL migration failed: script=<script_name>
```

失败时会带完整异常栈，并阻止 Spring Boot 完成启动。

### 6.4 日志安全边界

- 不打印 SQL 正文；
- 不把 SQL 导出到用户目录；
- checksum 日志只打印前 12 位；
- 不打印数据库中的业务数据；
- 台账更新日志在 commit 成功后打印，不会误报未提交状态。

---

## 7. 常用查看命令

### 7.1 实时跟踪安装版日志

```bash
tail -f ~/.agentforge-desktop/logs/agentforge-desktop.log
```

### 7.2 只看数据库迁移

```bash
rg 'SQLite incremental|incremental SQL|Migration ledger' \
  ~/.agentforge-desktop/logs/agentforge-desktop.log
```

### 7.3 查看警告和错误

```bash
rg ' WARN | ERROR ' ~/.agentforge-desktop/logs/agentforge-desktop.log
```

### 7.4 查看压缩归档

macOS/Linux：

```bash
gzip -cd ~/.agentforge-desktop/logs/archive/agentforge-desktop.2026-11-10.0.log.gz | less
```

### 7.5 本地开发

```bash
tail -f agentforge-desktop-ui/.agentforge-dev/logs/agentforge-desktop.log
```

---

## 8. 故障排查

### 8.1 `Logging system failed to initialize`

如果出现：

```text
Could not initialize Logback logging from classpath:logback-spring.xml
Problem parsing XML document
```

先校验 XML：

```bash
xmllint --noout agentforge-desktop-server/src/main/resources/logback-spring.xml
```

XML 注释内不允许出现连续的两个减号。例如不应在 `<!-- ... -->` 里直接写带双减号前缀的命令行参数。本项目配置已避免该写法。

### 8.2 日志没有出现在预期目录

检查启动参数与环境变量：

```bash
echo "$AGENTFORGE_DESKTOP_ROOT_DIR"
ps -ef | rg 'agentforge-desktop-server.jar'
```

正常 Electron 启动日志中也会输出 Java 启动命令及工作目录。

### 8.3 日志目录不可写

Logback 在初始化 RollingFileAppender 时需要对 `<root-dir>/logs` 有写权限。检查：

```bash
ls -ld ~/.agentforge-desktop ~/.agentforge-desktop/logs
```

不要把 `AGENTFORGE_DESKTOP_ROOT_DIR` 指向只读系统目录或应用安装包内部。

### 8.4 归档没有立即出现

归档只在滚动条件触发时生成：

- 日期跨天；
- 当日文件达到切分条件；
- 日志事件触发 Logback 进行滚动检查。

刚启动且日志量很小时，只有 `agentforge-desktop.log` 是正常现象。

---

## 9. 日志开发规范

### 9.1 级别使用

| 级别 | 使用场景 |
| --- | --- |
| `DEBUG` | 详细内部状态，默认 INFO 下不输出 |
| `INFO` | 启动阶段、状态转换、迁移、用户可感知的正常操作 |
| `WARN` | 可恢复、使用降级或需要注意的异常 |
| `ERROR` | 操作失败、数据不一致风险、启动无法继续 |

### 9.2 结构化文案

推荐：

```java
log.info("Agent initialized: aid={}, modelId={}, elapsed={} ms", aid, modelId, elapsed);
```

不推荐：

```java
log.info("success");
```

日志应包含事件、主要标识和结果，但不应输出敏感信息。

### 9.3 禁止打印

- API Key、Token、Authorization Header；
- 完整用户提示词和模型私密响应，除非有明确的脱敏调试机制；
- `config.json` / `models.json` 完整内容；
- SQL 正文和业务表数据；
- 未脱敏的用户隐私信息。

### 9.4 异常打印

需要根因栈时：

```java
log.error("Operation failed: id={}", id, exception);
```

不要只打印 `exception.getMessage()`，否则会丢失调用栈和根因。

---

## 10. 配置调整指南

配置位置：

```text
agentforge-desktop-server/src/main/resources/logback-spring.xml
```

常见调整：

```xml
<!-- 单分片大小 -->
<maxFileSize>20MB</maxFileSize>

<!-- 保留天数 -->
<maxHistory>30</maxHistory>

<!-- 历史归档总容量 -->
<totalSizeCap>1GB</totalSizeCap>
```

修改后必须重新构建 Java JAR：

```bash
cd agentforge-desktop-ui
npm run build:server
npm run runtime:server
```

仅修改源码中的 XML，已安装应用不会自动获取新配置。

---

## 11. 发布前验证

### 11.1 XML 语法

```bash
xmllint --noout agentforge-desktop-server/src/main/resources/logback-spring.xml
```

### 11.2 Maven 打包

```bash
mvn -o -f agentforge-desktop-server/pom.xml -DskipTests package
```

### 11.3 临时目录启动

```bash
java -jar agentforge-desktop-server/target/agentforge-desktop-server.jar \
  --server.port=43199 \
  --agentforge.desktop.root-dir=/tmp/agentforge-desktop-log-check
```

检查：

```bash
test -f /tmp/agentforge-desktop-log-check/logs/agentforge-desktop.log
rg 'SQLite incremental migration' \
  /tmp/agentforge-desktop-log-check/logs/agentforge-desktop.log
```

### 11.4 安装版验证

1. 启动应用；
2. 确认 `~/.agentforge-desktop/logs/agentforge-desktop.log` 存在；
3. 确认日志包含 Spring Boot 启动和 SQLite 迁移摘要；
4. 重启应用，确认日志追加且打印 `0 pending`；
5. 确认用户目录不会创建 `sql-applied`。

---

## 12. 关键代码索引

| 职责 | 文件 |
| --- | --- |
| Logback 统一配置 | `agentforge-desktop-server/src/main/resources/logback-spring.xml` |
| 默认 root-dir 映射 | `agentforge-desktop-server/src/main/resources/application.yml` |
| 服务端路径属性 | `config/DesktopProperties.java` |
| Electron 数据根目录及 Java 启动参数 | `agentforge-desktop-ui/electron/main.mjs` |
| 开发模式 `.agentforge-dev` 路径 | `agentforge-desktop-ui/scripts/dev/desktop-dev.mjs` |
| SQLite 迁移过程日志 | `store/DesktopSchemaMigrator.java` |

---

## 13. 当前边界与后续演进

当前已完成：

- Java 后端统一 SLF4J + Logback；
- 控制台与文件双输出；
- 日志目录跟随桌面数据根目录；
- 按日期 + 大小滚动；
- 压缩归档、保留天数和总容量约束；
- SQLite 迁移的开始、扫描、脚本执行、台账提交和最终汇总日志。

可后续演进：

- 为 Electron 主进程增加独立的日志文件与滚动策略；
- 在 UI 中提供“打开日志目录”入口；
- 提供脱敏后的一键诊断包；
- 针对特定模块支持临时 DEBUG 级别，避免全局 DEBUG 产生过量日志。

总体原则是：**日志跟随用户数据根目录，当前日志便于实时排查，历史日志自动压缩与有界保留，业务日志不暴露敏感数据。**
