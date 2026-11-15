# 安装后启动初始化与 SQLite 增量 SQL 迁移详解

本文从用户安装 AgentForge Desktop 后首次启动开始，梳理 Electron 主进程、内置 Java 后端、本地配置、SQLite 数据库和增量 SQL 的完整初始化链路。

> 核心结论：**安装程序只把应用文件放到目标机器，不直接创建用户数据库，也不执行增量 SQL。真正的用户级初始化发生在每次启动应用时，由内置 Java 后端在 Spring Boot 启动期间自动完成。**

---

## 0. 四种场景先分清

| 场景 | 是否创建数据目录 | 是否扫描增量 SQL | 实际执行内容 |
| --- | --- | --- | --- |
| 只安装，未启动 | 否 | 否 | 仅由 DMG/NSIS 放置 App、Electron、JRE 和 JAR |
| 首次启动 | 是 | 是 | 创建配置、SQLite DB、迁移台账和业务表 |
| 普通重启 | 已存在则复用 | 是 | 校验已执行 SQL，无新脚本则跳过 |
| 覆盖安装新版后启动 | 复用旧数据 | 是 | 依次执行新版 JAR 中未执行的 SQL |

因此，“安装后初始化”更准确的说法是“**安装后首次启动初始化**”。

---

## 1. 安装包中已经包含什么

electron-builder 将应用组装为两类资源：

```text
应用代码（ASAR）
|-- dist/                    # Vue/Vite 渲染页面
|-- electron/                # Electron main/preload
`-- package.json

外部运行资源（resources）
`-- runtime/
    |-- jre/                 # 当前 OS/CPU 对应的 Java 8
    `-- server/
        `-- agentforge-desktop-server.jar
```

增量 SQL 不是单独放在 Electron 的 `resources/sql` 中，而是由 Maven 打入 `agentforge-desktop-server.jar`。这样 Java 后端无论在开发环境还是安装包中运行，都使用同一套 classpath 扫描逻辑。

---

## 2. 安装后第一次启动的完整时序

```text
用户双击 AgentForge Desktop
  |
  |-- 1. Electron 主进程启动
  |      |-- 找到 resources/runtime/jre/bin/java(.exe)
  |      |-- 找到 resources/runtime/server/*.jar
  |      |-- 分配 127.0.0.1 随机端口
  |      |-- 生成随机访问 Token
  |      `-- 创建 ~/.agentforge-desktop
  |
  |-- 2. Electron spawn 内置 Java 后端
  |      `-- java -jar ... --root-dir=~/.agentforge-desktop ...
  |
  |-- 3. Spring Boot 创建应用上下文
  |      |-- 绑定 DesktopProperties
  |      |-- 创建 SQLite DataSource/JdbcTemplate
  |      |-- 加载/初始化 config.json 和 models.json
  |      `-- DesktopSchemaMigrator.afterPropertiesSet()
  |            |-- 创建迁移台账表
  |            |-- 扫描 JAR 中的增量 SQL
  |            |-- 按路径排序
  |            `-- 事务执行未运行脚本并写入台账
  |
  |-- 4. Spring Web 服务完成启动
  |
  |-- 5. Electron 轮询 GET /api/local/health
  |      `-- code=0 && data.ready=true
  |
  |-- 6. Electron 注册 IPC
  |
  `-- 7. 创建并展示主窗口
```

这个顺序的保障是：数据库迁移未完成时，Electron 不会把后端判定为 ready。

---

## 3. 数据根目录如何决定

### 3.1 打包后默认目录

Electron `main.mjs` 默认使用：

```text
<user.home>/.agentforge-desktop
```

并把它作为启动参数传给 Java：

```text
--agentforge.desktop.root-dir=<absolute path>
```

也可通过 `AGENTFORGE_DESKTOP_ROOT_DIR` 覆盖。命令行参数优先级高于 `application.yml` 默认值。

### 3.2 开发模式目录

`desktop-dev.mjs` 默认使用：

```text
agentforge-desktop-ui/.agentforge-dev
```

所以开发数据与安装版用户数据默认互不影响。

### 3.3 初始化后的典型目录

```text
~/.agentforge-desktop/
|-- conf/
|   |-- config.json
|   `-- models.json
|-- data/
|   |-- agentforge-desktop.db
|   |-- agentforge-desktop.db-wal       # SQLite WAL 运行期可能存在
|   `-- agentforge-desktop.db-shm       # SQLite WAL 运行期可能存在
`-- logs/
```

优化后的 Java 自动迁移器不会在用户目录创建 `sql-applied`，也不会把增量 SQL 明文导出到安装目录外。

---

## 4. 配置文件初始化

`DesktopConfigStore` 是 Spring `@Component`，在 `@PostConstruct init()` 中调用 `ensureLoaded()`。

初始化步骤：

1. 创建 `conf/` 目录。
2. 如果新路径 `conf/config.json` 不存在，但旧路径 `<root>/config.json` 存在，先迁移旧配置。
3. `config.json` 不存在时，从 JAR 中的 `defaults/config.json` 复制。
4. 读取、反序列化并规范化配置。
5. 处理旧版本中内嵌在 `config.json` 的 models，必要时拆分到 `models.json`。
6. `models.json` 不存在时，从 `defaults/models.json` 复制。
7. 记录文件修改时间和大小，供后续热加载变更检测。

配置初始化失败会抛出 `IllegalStateException`，导致 Spring Bean 创建失败。

> `DesktopConfigStore` 和 `DesktopSchemaMigrator` 都在 Spring Bean 初始化阶段执行，当前代码没有用 `@DependsOn` 明确声明两者的互相顺序。它们不互相依赖，但都必须成功，Spring 应用上下文才能正常完成启动。

---

## 5. SQLite DataSource 初始化

`DesktopDataSourceConfig.dataSource()` 在创建 Hikari DataSource 前先创建：

```text
<root>/data/
<root>/logs/
```

数据库路径是：

```text
<root>/data/agentforge-desktop.db
```

JDBC URL：

```text
jdbc:sqlite:<absolute-db-path>?busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL&foreign_keys=on
```

参数含义：

| 参数 | 作用 |
| --- | --- |
| `busy_timeout=5000` | 数据库被短暂占用时最多等待 5 秒 |
| `journal_mode=WAL` | 使用 Write-Ahead Logging，改善读写并发与崩溃恢复 |
| `synchronous=NORMAL` | 在耐久性与性能之间取平衡 |
| `foreign_keys=on` | 开启 SQLite 外键约束检查 |

Hikari 连接池的 `maximumPoolSize` 和 `minimumIdle` 都是 1，符合本地单应用 SQLite 的写入模型，也降低迁移阶段的并发冲突。

---

## 6. 增量 SQL 如何进入 JAR

仓库 SQL 源目录是：

```text
agentforge-desktop-server/sql/sqllite/
`-- 1.0.x/
    `-- 20260101000000_desktop_baseline.sql
```

> 注意：当前仓库目录名是 `sqllite`（两个 `l`），本文保留现状，不要在新增 SQL 时误放到自行创建的 `sqlite` 目录。

`pom.xml` 将该目录映射到 JAR classpath：

```text
sql/sqllite/**
    -> db/sqlite/increment/**
```

运行时 `DesktopSchemaMigrator` 扫描：

```text
classpath*:db/sqlite/increment/**/*.sql
```

因此开发者添加 SQL 后必须重新构建后端 JAR，仅修改源目录不会让已安装应用自动获取新脚本。

---

## 7. `DesktopSchemaMigrator` 的触发时机

`DesktopSchemaMigrator` 同时具备：

```java
@Component
public class DesktopSchemaMigrator implements InitializingBean
```

Spring 完成构造器注入后，会回调：

```java
@Override
public void afterPropertiesSet() {
    migrate();
}
```

这表示迁移不需要 Electron 或前端主动调用 API，也不需要用户点击“升级”按钮。每次 Java 后端创建 Spring 上下文时都会自动进入 `migrate()`。

如果迁移失败：

1. `afterPropertiesSet()` 抛出异常；
2. `DesktopSchemaMigrator` Bean 创建失败；
3. Spring Boot 应用上下文启动失败；
4. Java 进程退出，或无法对健康检查返回 ready；
5. Electron `waitForBackend()` 检测到子进程退出或最终超时；
6. 桌面端显示启动失败对话框，不进入正常业务界面。

---

## 8. 迁移台账表

迁移器首先保证以下表存在：

```sql
CREATE TABLE IF NOT EXISTS desktop_schema_migration (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    script_name TEXT NOT NULL,
    version     TEXT NOT NULL,
    checksum    TEXT NOT NULL,
    applied_at  INTEGER NOT NULL
);
```

| 字段 | 含义 | 示例 |
| --- | --- | --- |
| `id` | 自增主键 | `1` |
| `script_name` | 相对 `db/sqlite/increment/` 的脚本路径 | `1.0.x/20260101000000_desktop_baseline.sql` |
| `version` | 脚本路径最后一个 `/` 前的部分 | `1.0.x` |
| `checksum` | SQL 原始字节的 SHA-256 | 64 位小写十六进制字符串 |
| `applied_at` | 执行完成时的 Unix 毫秒时间戳 | `1760000000000` |

当前表结构并没有对 `script_name` 建立唯一约束。正常桌面启动只有一个迁移器且连接池大小为 1，所以常规场景不会并发插入；但不应同时启动多个指向同一 `root-dir` 的后端进程。

---

## 9. 每次启动时的详细迁移算法

### 9.1 读取已执行集合

迁移器执行：

```sql
SELECT script_name, checksum FROM desktop_schema_migration;
```

并构建：

```text
Map<script_name, checksum>
```

### 9.2 扫描与排序

扫描所有 `*.sql` 后，以 classpath 相对路径字典序排序：

```text
1.0.x/20260101000000_desktop_baseline.sql
1.0.x/20260102090000_add_xxx.sql
1.1.x/20260201090000_add_yyy.sql
```

代码不会解析时间戳的数值含义，顺序完全取决于字符串排序。因此时间戳必须固定长度并补齐。

### 9.3 三种判定结果

| 状态 | 判定 | 行为 |
| --- | --- | --- |
| 新脚本 | 台账无 `script_name` | 执行 SQL 并写入台账 |
| 已执行且未变 | 台账有同名且 checksum 相同 | 跳过 |
| 已执行但被修改 | 台账有同名但 checksum 不同 | 立即失败，终止启动 |

这个规则意味着：**一旦 SQL 已经发布并在任何用户机器上执行，就不能编辑原脚本。修正数据库必须新增一个后续 SQL。**

---

## 10. 单个脚本的事务边界

每个新脚本单独使用一个 JDBC 事务：

```text
获取 Connection
  -> 记住原 autoCommit
  -> setAutoCommit(false)
  -> ScriptUtils 执行当前 SQL
  -> INSERT desktop_schema_migration
  -> commit
  -> 恢复 autoCommit
  -> 释放 Connection
```

任一步执行异常：

```text
rollback
  -> 不会保留该脚本本次的变更
  -> 不会写入该脚本的台账记录
  -> 抛异常终止后续脚本与应用启动
```

注意：这是“单脚本事务”，不是“全部迁移一个总事务”。例如当前有 A、B、C 三个待执行脚本：

- A 成功后会立即提交；
- B 失败时只回滚 B；
- A 仍保持已执行状态；
- C 不会执行；
- 修复 B 并重启后，A 被跳过，从 B 继续。

---

## 11. 优化方案：只保留数据库迁移台账

优化前，Java 迁移器在每个 SQL 成功后还会将脚本复制到：

```text
~/.agentforge-desktop/sql-applied/<script_name>
```

该文件副本并不参与后续升级判定，迁移器始终只查询 `desktop_schema_migration`。为避免在用户数据目录暴露增量 SQL，并减少一个非必要的文件写入点，现已将 Java 自动迁移优化为：

```text
JAR 内 SQL
  -> 计算 SHA-256
  -> 对比 desktop_schema_migration
  -> 事务执行 SQL
  -> 同事务写入 desktop_schema_migration
  -> 结束，不导出 SQL 文件
```

### 11.1 为什么台账表已经足够

`desktop_schema_migration` 已保存恢复迁移状态所需的全部信息：

- `script_name` 判断某个 SQL 是否已执行；
- `checksum` 保证已执行 SQL 不能被静默修改；
- `version` 标识脚本所属版本；
- `applied_at` 记录执行时间。

覆盖安装时用户数据库保留，新 JAR 只需将内置 SQL 与该表对比，即可正确找出待执行增量。

### 11.2 该优化同时解决的问题

- 用户不会在 `~/.agentforge-desktop` 看到明文 SQL；
- 数据目录只保留应用运行必需的配置、数据库和日志；
- 避免“SQLite 事务已提交，但 SQL 副本写入失败”导致的假性启动失败；
- 迁移状态不再分散在数据库与文件系统两处；
- `DesktopSchemaMigrator` 不再依赖 `DesktopProperties`，职责更聚焦。

### 11.3 旧版本已创建目录的处理

新代码不再创建或写入 `sql-applied`，但也不会自动删除用户机器上已存在的旧目录。这是为了避免升级过程中执行非必要的破坏性文件操作。旧目录不影响任何迁移判定，可由用户或维护者在确认无需后手工清理。

---

## 12. 首个 baseline SQL 初始化什么

当前 baseline：

```text
1.0.x/20260101000000_desktop_baseline.sql
```

创建以下表：

| 表 | 用途 |
| --- | --- |
| `desktop_schema_migration` | 增量 SQL 执行台账 |
| `ai_agent_session` | Agent 会话主表 |
| `ai_agent_session_message` | 会话消息、状态、耗时和事件 |
| `ai_agent_memory` | Agent 记忆内容 |

迁移器自身也会在 baseline 之前执行 `CREATE TABLE IF NOT EXISTS desktop_schema_migration`，所以 baseline 中的同名建表语句是幂等的。

---

## 13. 后续如何新增增量 SQL

### 13.1 命名规则

建议继续使用：

```text
sql/sqllite/<version>/<yyyyMMddHHmmss>_<description>.sql
```

例如：

```text
sql/sqllite/1.1.x/20261110143000_add_session_summary.sql
```

要求：

- 时间戳使用 14 位固定长度；
- 在全部版本目录中保持脚本路径唯一；
- 名称使用小写英文和下划线；
- 不在已发布脚本上直接修改；
- 修复也要新建更晚时间戳的 SQL。

### 13.2 SQL 编写建议

- 优先使用 SQLite 支持的语法，不要复制 MySQL 专属 DDL。
- 脚本由 Java 代码统一开启事务，通常不要自行写 `BEGIN/COMMIT`。
- 考虑用户可能从很旧版本直接跨版本升级，所以每个脚本只能依赖它之前的脚本，不能假设中间版本被单独启动过。
- DDL 和数据回填尽量拆成小而清晰的脚本，方便定位失败。
- 在实际的旧版数据库副本上做升级测试，不要只测空库。

### 13.3 发布前验证

```bash
cd agentforge-desktop-ui
npm run build:server
npm run runtime:server
npm run desktop:dev:embedded
```

启动后检查：

```bash
sqlite3 .agentforge-dev/data/agentforge-desktop.db \
  "SELECT id,script_name,version,checksum,applied_at FROM desktop_schema_migration ORDER BY id;"
```

然后重启一次，确认不会重复执行，台账也不会新增同名记录；同时确认用户数据目录下没有新建 `sql-applied`。

---

## 14. 覆盖升级时数据为什么不会丢

应用安装目录与用户数据目录是分离的：

```text
安装目录：App / Program Files / Applications
用户数据：~/.agentforge-desktop
```

安装新版会替换 Electron/JRE/JAR，但正常不会覆盖 `~/.agentforge-desktop`。新版首次启动时：

1. 新 JAR 读取旧 SQLite DB。
2. 读取旧 `desktop_schema_migration` 台账。
3. 与新 JAR 中的全部 SQL 对比。
4. 已执行 SQL 通过 checksum 验证后跳过。
5. 仅执行新增 SQL。
6. 迁移完成后才进入正常界面。

这就是增量升级的核心机制。

---

## 15. 手工 `upgrade-sqlite.sh` 与自动迁移的关系

仓库还提供：

```text
agentforge-desktop-server/bin/upgrade-sqlite.sh
```

它使用系统 `sqlite3` 执行源码目录中的 SQL，与 Java 迁移器共用 `desktop_schema_migration` 台账，适合开发排查或手工预演：

```bash
cd agentforge-desktop-server
./bin/upgrade-sqlite.sh --dry-run
./bin/upgrade-sqlite.sh
```

可配置：

| 变量 | 用途 |
| --- | --- |
| `AGENTFORGE_DESKTOP_ROOT_DIR` | 数据根目录 |
| `AGENTFORGE_DESKTOP_SQLITE_PATH` | 直接指定 DB |
| `AGENTFORGE_DESKTOP_SQL_DIR` | 指定 SQL 源目录 |
| `AGENTFORGE_DESKTOP_SQL_APPLIED_DIR` | 仅指定手工 Shell 脚本的副本目录 |

需要注意当前两者的差异：

- Java 迁移器会对已执行脚本校验 checksum，且不会向用户目录导出 SQL；
- Shell 脚本判断已存在 `script_name` 后直接跳过，不会校验已执行脚本是否变更；
- Shell 是独立开发工具，仍默认复制到源码工程 `sql/local/sqlite`，与安装版 Java 自动迁移无关；
- 安装版正常启动不依赖系统 `sqlite3`，使用 Java/JDBC 自动迁移即可。

除非正在开发或故障恢复，用户不需要手工运行该 Shell 脚本。不要在桌面应用正在运行时手工对同一 DB 执行迁移。

---

## 16. 失败排查

### 16.1 查看后端输出

Electron 会为 Java 子进程日志加前缀：

```text
[desktop-server] ...
```

常见关键日志：

```text
SQLite incremental migration started, classpath root: db/sqlite/increment/
SQLite migration ledger loaded: <n> applied script(s)
SQLite incremental SQL scan completed: <discovered> discovered, <pending> pending, <applied> already applied
Applying incremental SQL [1/1]: script=<script_name>, version=<version>, checksum=<12-char-prefix>
Incremental SQL statements executed successfully: script=<script_name>
Migration ledger updated and transaction committed: table=desktop_schema_migration, script=<script_name>, ...
Incremental SQL applied [1/1]: script=<script_name>
SQLite incremental migration completed: <n> applied this run, <n> total discovered, elapsed=<ms> ms
incremental sql changed after execution: <script_name>
Incremental SQL migration failed: script=<script_name>
Incremental SQL transaction rolled back: script=<script_name>
```

上述日志同时输出到 Electron 控制台和 `<root-dir>/logs/agentforge-desktop.log`，并按日期/大小滚动压缩归档到 `<root-dir>/logs/archive/`。完整日志策略见同目录的《AgentForge Desktop 日志统一管控详解》。

### 16.2 查看台账

```bash
sqlite3 ~/.agentforge-desktop/data/agentforge-desktop.db \
  "SELECT id,script_name,version,checksum,datetime(applied_at/1000,'unixepoch','localtime') FROM desktop_schema_migration ORDER BY id;"
```

### 16.3 检查 JAR 中是否包含 SQL

```bash
jar tf agentforge-desktop-ui/runtime/server/agentforge-desktop-server.jar \
  | grep 'db/sqlite/increment/.*\.sql'
```

如果新 SQL 不在 JAR 里，重新执行：

```bash
cd agentforge-desktop-ui
npm run build:server
npm run runtime:server
```

### 16.4 checksum 不一致

正确处理不是直接修改台账 checksum，而是：

1. 恢复已发布 SQL 的原始内容；
2. 新建一个更晚时间戳的修复 SQL；
3. 重新构建 JAR 并验证升级。

### 16.5 执行中途失败

优先保留数据库现场并备份：

```bash
cp ~/.agentforge-desktop/data/agentforge-desktop.db /safe/path/agentforge-desktop.db.bak
```

之后检查失败 SQL、当前表结构和台账。由于单脚本事务会回滚，下次启动将重新尝试该脚本。不要在没有备份时手工删除迁移台账。

---

## 17. 当前实现边界与改进建议

### 17.1 当前已有保障

- Spring Bean 初始化期自动迁移；
- classpath/JAR 资源扫描；
- 字典序稳定排序；
- 单脚本事务；
- SQL 与台账原子提交；
- SHA-256 防止已执行脚本被静默修改；
- 只在 SQLite 台账保存迁移状态，不向用户目录导出 SQL；
- 迁移失败阻止应用带病启动。

### 17.2 可后续增强

- 为 `desktop_schema_migration.script_name` 增加唯一约束，强化并发防护；
- 增加针对空库、老版库、SQL 失败回滚和 checksum 冲突的自动化测试；
- 对发布版本和数据库 schema 版本增加可视化诊断信息；
- 考虑在重大升级前自动备份 SQLite DB；
- 评估是否同步简化独立 Shell 脚本的文件复制行为，并与 Java 迁移器统一 checksum 检查规则；
- 评估是否将仓库中的 `sql/sqllite` 拼写统一为 `sql/sqlite`，如修改需同步 Maven 和 Shell 路径。

---

## 18. 关键代码索引

| 职责 | 文件 |
| --- | --- |
| Electron 启动 Java、健康检查与窗口创建 | `agentforge-desktop-ui/electron/main.mjs` |
| Java 应用入口 | `agentforge-desktop-server/.../AgentForgeDesktopServerApplication.java` |
| 根目录、配置与 DB 路径 | `config/DesktopProperties.java` |
| SQLite DataSource 与连接参数 | `config/DesktopDataSourceConfig.java` |
| 配置初始化与旧配置迁移 | `store/DesktopConfigStore.java` |
| SQLite 自动增量迁移 | `store/DesktopSchemaMigrator.java` |
| 增量 SQL 源文件 | `agentforge-desktop-server/sql/sqllite/` |
| SQL 打入 JAR 的 Maven 资源配置 | `agentforge-desktop-server/pom.xml` |
| 手工 SQLite 升级脚本 | `agentforge-desktop-server/bin/upgrade-sqlite.sh` |
| ready 健康检查 | `modules/system/LocalDesktopController.java` |

---

## 19. 最终总结

安装后初始化的真正主线是：

1. 安装包落盘 Electron + JRE + Java JAR，不直接碰用户 DB。
2. 首次启动时 Electron 创建数据根目录并拉起内置 Java。
3. Spring Boot 初始化默认配置、models 和 SQLite DataSource。
4. `DesktopSchemaMigrator` 在 Bean 初始化阶段扫描 JAR 中的增量 SQL。
5. 它以台账表 + SHA-256 判断哪些脚本需要执行。
6. 每个新 SQL 与它的台账记录在同一事务中提交，不再导出 SQL 文件。
7. 迁移完成后 Spring 服务才完成启动。
8. Electron 等到 `/api/local/health` 返回 ready 后才创建正常主窗口。

所以，对新安装、普通重启和覆盖升级而言，都是同一个启动流程；差别只在于台账中是否已经存在脚本记录。
