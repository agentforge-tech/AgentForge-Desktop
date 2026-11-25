# 	AgentForge-Desktop 能力增强（ripgrep / rg）检测与一键安装方案

> 对应仓库：`AgentForge-Desktop`
> 相关模块：`agentforge-desktop-ui/electron/main.mjs`、`agentforge-desktop-ui/electron/preload.cjs`、`agentforge-desktop-ui/src/components/ConfigDrawer.vue`、`agentforge-desktop-server` 的 `LocalDesktopController`
> 上游依赖：`AgentForge/agentforge-framework/agentforge-harness-agent` 的 `LocalRuntimeTools#rg`
> 适用范围：桌面端本地单实例运行模式（macOS / Windows）
> 状态：方案评审稿（未实施）

---

## 0. 结论先行

本次要回答两个问题：**「能不能快速检测」**、**「要不要默认自动安装」**。先给结论，再给依据。

| 问题 | 结论 |
| --- | --- |
| 能否支持快速检测命令 | **可以，且必须"绕过 shell"做检测**。用绝对路径 + 直接 spawn 探测，单次 < 100ms，不需要 `which`、不需要起 shell、不需要联网 |
| 是否默认自动安装 | **不建议"点击检测后才安装"**。默认走「**随包内置 + 首次启动静默初始化**」，用户零操作、离线可用 |
| 「一键安装」按钮是否还要留 | **要留**，但定位从"首次获取"降级为「**修复 / 升级 / 兜底**」：内置二进制缺失、损坏、或用户想升级版本时使用 |
| 是否要改造核心框架 | **不需要**。只要把工具目录注入后端进程 `PATH`，`rg` 工具即可生效 |

### 0.1 决定性实测证据（本机 macOS 14.6 / arm64）

这四条直接决定了方案形态，不是推测：

| # | 实测内容 | 结果 | 含义 |
| --- | --- | --- | --- |
| 1 | `ls -l /opt/homebrew/bin/rg` | `-> ../Cellar/ripgrep/15.2.0/bin/rg` | 用户**已经装了** rg |
| 2 | 桌面端当前 `PATH`（GUI 继承环境） | 含 `/usr/local/bin`、`/usr/bin`，**不含** `/opt/homebrew/bin` | GUI 启动的进程拿不到 Homebrew 路径 |
| 3 | `/bin/sh -lc 'which rg'` | `exit=1`（找不到） | **「用户装了 rg 但检测为缺失」的假阴性真实存在** |
| 4 | 前置目录后 `PATH="<rgdir>:$PATH" /bin/sh -lc 'which rg'` | `/<dir>/rg`，`ripgrep 16.0.0` | **注入 PATH 即可让工具链路立刻可用** |

也就是说：**问题不是"用户没装 rg"，而是"进程找不到 rg"**。若按"检测缺失 → 让用户 `brew install`"的朴素方案做，用户装完仍然检测失败，体验是坏的。

### 0.2 体积与可执行性实测

官方 release 逐项验证过，可直接作为钉版本依据：

| 平台包 | 压缩包 | 解压后 `rg` | 校验值（SHA-256） |
| --- | --- | --- | --- |
| `aarch64-apple-darwin` | 1.7 MB | **3.83 MB** | `5a145656d5067144001bfce6648767fd3254599aa736f1ffdb7fb907760b8ca1` |
| `x86_64-apple-darwin` | 1.8 MB | ~3.9 MB | `aa9183b0e0bfac03c5818895ad18b75001c402d230063898abfe5a58cd413caa` |
| `x86_64-pc-windows-msvc` | 1.8 MB | ~3.9 MB | `f418ae83e63046a94a7b5f3a44bf3a169f66e689164856ecee02547131c73931` |

实测结论：

- 二进制为 `Mach-O thin (arm64)`，`Signature=adhoc (linker-signed)`，**无需签名流程即可执行**；
- 解压携带属性仅为 `com.apple.provenance`，**没有 `com.apple.quarantine`**，不会触发 Gatekeeper 拦截弹窗；
- 直接 `--version` 输出 `ripgrep 16.0.0 (rev 669cb4c755)`，可跑。

> 因此"随包内置"的成本是**每个平台约 3.8 MB**，换来的是零网络、零权限、零弹窗。这个代价值得。

---

## 1. 现状核查（代码事实，可回溯）

### 1.1 Agent 侧远期没有 rg 时的实际表现

`rg` 不是"可选优化"，它是核心框架暴露给模型的正式工具：

```java
// AgentForge/agentforge-framework/agentforge-harness-agent/.../local/tool/LocalRuntimeTools.java:226
@Tool(name = "rg", value = "Run ripgrep inside the workspace and return matching lines.")
public String rg(
        @P(name = "pattern", value = "ripgrep search pattern") String pattern,
        @P(name = "path", value = "Workspace-relative search path") String path) {
    Path target = workspace.resolveExisting(path == null || path.trim().isEmpty() ? "." : path);
    String relative = workspace.root().relativize(target).toString();
    if (relative.isEmpty()) relative = ".";
    return commands.execute(
                    "rg --line-number --no-heading -- "
                            + quote(pattern)
                            + " "
                            + quote(relative))
            .toString();
}
```

要点：

1. `rg` 工具内部**只是一句 shell 命令**，可用性完全取决于 `commands.execute()` 所在的 shell 能否解析到 `rg`；
2. 失败时返回的是 `CommandResult.toString()`，形如 `exitCode=127, timedOut=false\n/bin/sh: rg: command not found`（`CommandResult.java:27-29`），**模型会当成"搜索无结果/命令失败"继续推理**，而不是报"环境缺失"——这让缺失问题非常隐蔽；
3. 框架已内置纯 Java 的 `grep`（`LocalRuntimeTools.java:185`）作为语义近亲，但**能力不对等**：`grep` 需要 `Files.walk` 全量遍历 + 逐行读 + `Pattern.compile`，且受 `maxFileBytes` / `maxSearchResults` 限制；`rg` 忽略 `.gitignore`、支持多线程、跳过二进制。**两者不能互相替代**，这也说明了为什么值得为 rg 做专门的环境治理。

### 1.2 执行链路：shell 与 PATH 从哪来

```java
// .../local/command/CommandExecutor.java:35-39
process =
        new ProcessBuilder(options.shell(), "-lc", command)
                .directory(workspace.workingDirectory().toFile())
                .redirectErrorStream(true)
                .start();
```

```java
// .../local/config/LocalRuntimeOptions.java:14
this.shell = isBlank(builder.shell) ? "/bin/sh" : builder.shell;
```

而桌面端构造运行时**没有传 shell**：

```java
// agentforge-desktop-server/.../modules/agent/runtime/AgentRuntimeService.java:245-250
LocalRuntime runtime = LocalRuntime.builder()
        .options(LocalRuntimeOptions.builder()
                // 5分钟超时，避免长时间阻塞 Agent 执行。
                .commandTimeoutMillis(5 * 60 * 1000)
                .build())
        .workspace(workspace).build();
```

后端进程由主进程拉起，环境变量整体继承：

```javascript
// agentforge-desktop-ui/electron/main.mjs:164-170
const child = spawn(java, args, {
  cwd: rootDir,
  stdio: ['ignore', 'pipe', 'pipe'],
  windowsHide: true,
  env: { ...process.env }
})
```

**结论：环境可见性 = 主进程 `process.env.PATH` 的可见性。** 这就是唯一的注入点，改动面最小，且无需动核心框架。

> **重要前置风险（跨平台）**：`LocalRuntimeOptions` 默认 shell 是 `/bin/sh`，而 `AgentRuntimeService.java:245-250` 并未覆盖。Windows 上并不存在 `/bin/sh`，除非用户装了 Git Bash。所以 **Windows 下 `rg` / `bash` 工具在当前实现下本就是失效的**。本方案必须一并处理（见 §7.2）。

### 1.3 现有设置页结构（改造参照）

`ConfigDrawer.vue` 左侧栏目是硬编码按钮（`:13-27`），三个 tab：`models` / `agent` / `system`；右侧是 `v-show` 的 `<section>` 块（`:29-100`）。「系统配置」块在 `:81-100`，行结构是"左侧文案 + 右侧控件"的 `system-setting-row`：

```html
<!-- ConfigDrawer.vue:83-88 -->
<div class="system-setting-row">
  <div class="system-setting-copy">
    <b>交互模式支持切换</b>
    <span>允许在工作台中切换交互模式</span>
  </div>
  <el-checkbox v-model="systemForm.interactionModeSupport" aria-label="交互模式支持切换" />
</div>
```

系统配置的读写走主进程白名单，**未知字段会被丢弃**：

```javascript
// main.mjs:355-368
const DEFAULT_SETTINGS = {
  interactionModeSupport: false,
  taskCompletionReminder: true
}

// 白名单规范化：未知字段丢弃，类型错误回退默认值。
function normalizeSettings(value) {
  const input = value && typeof value === 'object' ? value : {}
  return {
    interactionModeSupport: input.interactionModeSupport === true,
    taskCompletionReminder: input.taskCompletionReminder !== false
  }
}
```

> 这条约束意味着：**新增任何持久化字段都必须同步改 `normalizeSettings`，否则写进去就没了。** 这是最容易踩的坑。

### 1.4 打包侧既有约定

`extraResources` 已把运行时按目录整体拷贝，新增工具目录属于同一套机制：

```json
// package.json:55-65
"extraResources": [
  { "from": "runtime/server", "to": "runtime/server" },
  { "from": "runtime/jre",    "to": "runtime/jre" }
]
```

`.gitignore` 已忽略二进制目录（`**/runtime/jre/`），工具目录按同样规则处理即可（见 `../.gitignore:19-21`）。

---

## 2. 方案选型：为什么是「内置优先 + 检测/安装兜底」

三条候选路径，逐条打掉：

| 方案 | 做法 | 致命问题 | 判定 |
| --- | --- | --- | --- |
| A. 纯检测 + 引导用户自行安装 | 检测 → 提示 `brew install ripgrep` | 本机实测：用户**已装**仍检测失败（PATH 不含 `/opt/homebrew/bin`）；Windows 无 brew；需联网；安装完还要重启 | ❌ 不可行 |
| B. 纯在线下载安装 | 点按钮 → 下载 zip → 解压到数据目录 | 首次使用必须联网；GitHub 在企业网/国内常不可达；需要校验与失败重试；用户开箱即"不可用" | ⚠️ 只适合做兜底 |
| C. 随包内置 + 启动初始化 | 二进制随安装包分发 → 首次启动复制到数据目录 → 注入 PATH | 包体积 +3.8MB/平台 | ✅ **采用** |

**采用 C 作为主路径**，理由：

1. **离线可用**：能力不依赖网络，符合"本地工作台"定位；
2. **确定性**：版本钉死，不受用户 `brew` 版本/升级影响，`rg` 输出格式（`--line-number --no-heading`）行为稳定；
3. **绕开 PATH 陷阱**：二进制落在自己可控目录，主动注入 `PATH`，从根本上消除 §0.1 的假阴性；
4. **用户零操作**：不需要"先检测再决定"，避免把环境问题变成用户负担。

**保留 B 作为兜底**，触发条件：内置二进制缺失（源码态调试/裁剪安装）、文件损坏、或用户主动"升级到最新版"。这样"一键安装"按钮有真实价值，不是装饰。

### 2.1 安装方式：为什么「下载二进制包」而不是「执行安装命令」

这是本方案最容易被问到的取舍点，单独说明。

**结论：三种安装方式里，只有"解压官方二进制包"是可行的，`brew install` / 系统安装器等命令方式必须排除。**

| 安装方式 | 具体做法 | 本机实测结果 | 判定 |
| --- | --- | --- | --- |
| ① 包管理器命令 | `brew install ripgrep` / `winget install` / `apt install` | **已装 15.2.0，Agent 仍报 `rg: command not found`**（`/bin/sh -lc 'which rg'` 返回 1） | ❌ 排除 |
| ② 下载安装器/执行器 | 下载 `.pkg` / `.msi` / `install.sh` 并执行 | 需管理员权限、触发系统弹窗、macOS 需公证、无法静默、无法回滚 | ❌ 排除 |
| ③ **下载官方二进制包** | 下载 `tar.gz`/`zip` → 校验 sha256 → **只解压出 `rg` 一个文件** → 落盘 `chmod 755` | 实测包 **3.83 MB**，`adhoc` 签名、**无 `com.apple.quarantine`**、直接可执行 | ✅ **采用** |

**排除 ① 的三个硬理由**（不只是"不方便"）：

1. **装完仍然不可用**：包管理器把二进制装到 `/opt/homebrew/bin`，而 GUI 启动的 Electron 进程 `PATH` 里没有这个目录（§0.1 第 2、3 条实测）。**用户会经历"装成功了但功能还是坏的"，这是最坏的产品体验**；
2. **平台不通用**：Windows 没有 brew；winget 需要交互与管理员权限；Linux 发行版之间包管理器各不相同。三端统一成本极高；
3. **版本不可控**：用户机器上的版本由包管理器决定（本机 15.2.0 ≠ 钉死 16.0.0），`rg` 的输出格式与行为会随版本漂移，破坏可复现性。

**采用 ③ 的四个收益**：

1. **零权限**：只写用户目录 `~/.agentforge-desktop/tools/`，不碰系统路径，不需要 sudo / UAC；
2. **零弹窗**：实测官方 release 二进制仅带 `com.apple.provenance`，**没有 quarantine 属性**，不会触发 Gatekeeper「无法验证开发者」；
3. **绕开 PATH 陷阱**：装到自己可控目录并主动注入 `PATH`（§7.1 已实测验证），从根源消除假阴性；
4. **只取一个文件**：解压后仅保留 `rg` 二进制（3.83 MB），丢弃 `doc/`、`complete/`、`README` 等，不污染用户磁盘。

> 补充：`rg` 是**单文件静态二进制**，不依赖 libpcre、不需要 `cargo`/Rust 工具链、不需要编译。这也是它适合"直接拷文件"这种最轻量分发方式的原因——换作依赖动态库的工具，就必须走包管理器了。

### 2.2 分发方式：内置还是在线

| 方式 | 用户是否需要联网 | 冷启动耗时 | 适用场景 |
| --- | --- | --- | --- |
| **随包内置**（主路径） | ❌ 不需要 | 毫秒级文件复制 | 默认；覆盖 99% 用户 |
| 在线下载（兜底） | ✅ 需要 | 取决于网速（3.8MB） | 内置缺失/损坏、或用户主动升级 |

内置让"一键安装"退化为纯本地复制，**即使 GitHub 完全不可达也能用**——这一点对内网/受限网络环境是决定性的。

> 在线路径的实现细节：主进程使用 Electron 的 `net.fetch`（而非全局 `fetch`）以遵循系统代理 / PAC；请求带 60s 超时，避免受限网络下界面卡在"安装中"；失败按 `NETWORK_UNREACHABLE` / `CHECKSUM_MISMATCH` 分类返回，便于给出可行动提示。

---

## 3. 总体设计

### 3.1 三层漏斗

```text
┌─ L0 打包期：二进制随包分发 ─────────────────────────────┐
│  runtime/tools/rg/<platform>-<arch>/rg                 │
│  由 scripts/build/prepare-rg.mjs 准备（等价 prepare-jre）│
│  打包后：<resources>/runtime/tools/rg/...               │
└───────────────────────┬────────────────────────────────┘
                        │ 首次启动静默复制（幂等，按 sha256 比对）
                        ▼
┌─ L1 运行期：用户级工具目录 ─────────────────────────────┐
│  ~/.agentforge-desktop/tools/rg/<version>/rg           │
│  主进程启动后端时把它前置进 PATH → 工具链路立即可用      │
└───────────────────────┬────────────────────────────────┘
                        │ 设置页「检测」只做校验，不下载
                        ▼
┌─ L2 兜底：一键安装 / 升级 ──────────────────────────────┐
│  内置可用 → 直接复制（秒级，无网络）                     │
│  内置缺失 → 在线下载（钉版本 + SHA-256 校验）            │
└────────────────────────────────────────────────────────┘
```

### 3.2 关键设计原则

| 原则 | 具体要求 |
| --- | --- |
| 检测不依赖 shell | 只用 `fs.existsSync` + `spawnSync(absPath, ['--version'])`，绝不 `which` / `bash -c` |
| 安装可幂等 | 以 `sha256(二进制) == 期望值` 判定"已就绪"，重复执行无副作用 |
| 生效不改核心 | 只注入 `PATH`，不改 `LocalRuntimeOptions` 语义（Windows shell 单独处理，见 §7.2） |
| 失败可解释 | 三态 + 原因文案，不给"未知错误" |
| 不做隐式联网 | 启动初始化只做本地复制；联网必须由用户点击触发 |

---

## 4. 数据与契约

### 4.1 持久化：`settings.json` 扩展

复用既有 `<rootDir>/conf/settings.json`，新增**只读快照**字段（记录上次检测结果，用于页面秒开时避免闪烁）：

```json
{
  "interactionModeSupport": false,
  "taskCompletionReminder": true,
  "enhancements": {
    "rg": {
      "status": "ready",
      "version": "16.0.0",
      "source": "bundled",
      "path": "~/.agentforge-desktop/tools/rg/16.0.0/rg",
      "checkedAt": "2026-11-26T10:12:31.000Z"
    }
  }
}
```

约定（与既有系统配置风格保持一致，见《AgentForge-Desktop系统配置读取与扩展说明.md》）：

- 字段名 camelCase，语义稳定，不随 UI 文案变化；
- **缺失 / 损坏 / 类型错误一律回退默认**：`enhancements.rg.status` 默认 `"unknown"`；
- `enhancements` 是**展示快照**，不是事实源；页面打开时后台必须重新检测一次以校正；
- **必须同步修改 `main.mjs:355-368` 的 `DEFAULT_SETTINGS` 与 `normalizeSettings`**，否则被白名单丢弃（见 §1.3）。

`source` 取值：`bundled` / `system` / `user-online` / `none`。

### 4.2 主进程 IPC（渲染进程 → 主进程）

沿用现有 `local-agent:` 前缀与 `ipcMain.handle` + `contextBridge` 暴露方式（`preload.cjs` 现有 22 个方法可参照）：

| 通道 | 入参 | 返回 | 说明 |
| --- | --- | --- | --- |
| `local-agent:enhancements:detect` | `{ id: 'rg' }` | `EnhancementStatus` | 只读探测，**无网络、无写盘** |
| `local-agent:enhancements:install` | `{ id: 'rg', mode: 'auto' \| 'online' \| 'upgrade' }` | `EnhancementStatus` | 幂等；`auto` = 有内置走内置，无内置走在线 |
| `local-agent:enhancements:list` | `{}` | `EnhancementStatus[]` | 页面初始化一次性拉全量，避免逐个探测 |

`EnhancementStatus` 结构（前端可直接渲染）：

```jsonc
{
  "id": "rg",
  "name": "ripgrep",
  "kind": "cli",                       // cli | binary
  "capability": "高速文件内容检索（rg 工具）",
  "status": "ready",                   // unknown | ready | missing | outdated | broken
  "version": "16.0.0",
  "minVersion": "13.0.0",              // 硬门槛：低于此版本判 outdated，阻断使用
  "recommendedVersion": "16.0.0",      // 推荐/内置版本：仅用于提示“可升级”，不阻断
  "source": "bundled",
  "path": "/Users/edy/.agentforge-desktop/tools/rg/16.0.0/rg",
  "canAutoInstall": true,              // 内置可用或在线可用
  "installMode": "bundled",            // bundled | online | none
  "needsRestart": false,               // 是否需要重启后端才生效
  "message": "已就绪，rg 工具可直接使用",
  "checkedAt": 1770000000000
}
```

> `needsRestart` 是关键字段：**首次安装/路径变更后必须置 `true`**，因为后端已按旧 `PATH` 启动（见 §7.1）。

### 4.3 后端 HTTP（可选，仅供 Agent 自检）

如需让 Agent 自己判断环境（例如提示"当前环境建议安装 rg"），在既有 `LocalDesktopController` 上加一条只读接口，语义与 IPC 检测保持一致：

```java
// agentforge-desktop-server/.../modules/system/LocalDesktopController.java:22-30 之后追加
@GetMapping("/enhancements")
public ApiResponse<Map<String, Object>> enhancements() { /* 读取 settings.json 的 enhancements 快照 */ }
```

> 该接口**只读快照，不做探测**，避免把"检测"职责散落到两个进程。真正的探测权威在主进程。

---

## 5. 检测设计（对应需求点 1）

### 5.1 候选链：按优先级找"最合适的那个 rg"

检测不是"有没有"，而是"用哪个"。按序短路：

| 序 | 来源 | 探测路径 | 采纳条件 |
| --- | --- | --- | --- |
| 1 | 用户级（本方案安装） | `<rootDir>/tools/rg/<ver>/rg(.exe)` | sha256 匹配 + `--version` 可执行 |
| 2 | 内置（安装包内） | `<resources>/runtime/tools/rg/<p>-<a>/rg(.exe)` | sha256 匹配 + 可执行 |
| 3 | 系统已装 | 绝对路径候选列表（见下） | 可执行 + 版本 ≥ `minVersion`(13.0.0) |
| 4 | 均无 | — | `missing` |

系统候选必须是**绝对路径枚举**，不能 `which`：

```javascript
// macOS
['/opt/homebrew/bin/rg', '/usr/local/bin/rg', '/usr/bin/rg', `${home}/.cargo/bin/rg`]
// Windows
[`${process.env.LOCALAPPDATA}\\Programs\\ripgrep\\rg.exe`, 'C:\\Program Files\\ripgrep\\rg.exe']
```

优先级设计理由：**用户级 > 内置 > 系统**。因为用户级由本应用管理，可保证版本与行为一致；系统已装版本不可控（本机就是 15.2.0，而钉版本是 16.0.0）。

### 5.2 版本探测与解析

```javascript
function probeRg(bin) {
  if (!bin || !fs.existsSync(bin)) return null
  const r = spawnSync(bin, ['--version'], { encoding: 'utf8', timeout: 3000 })
  if (r.status !== 0) return { ok: false, raw: (r.stderr || r.error?.message || '').trim() }
  const text = `${r.stdout || ''}${r.stderr || ''}`.trim()
  const m = text.match(/ripgrep\s+(\d+)\.(\d+)\.(\d+)/)   // 实测输出: "ripgrep 16.0.0 (rev 669cb4c755)"
  return { ok: true, version: m ? `${m[1]}.${m[2]}.${m[3]}` : text.split('\n')[0], raw: text }
}
```

注意实测输出含 `features:+pcre2` 第二行（Homebrew 构建），解析**只取第一行**并忽略 features，避免误判。

### 5.3 状态机

| status | 判定 | 右侧展示 | 主按钮 |
| --- | --- | --- | --- |
| `ready` | 探测成功且版本 ≥ `minVersion`（13.0.0） | ✅ 已支持 + 版本 + 路径；版本 < `recommendedVersion` 时附「可升级至 16.0.0」 | 「重新检测」 |
| `outdated` | 可执行但版本 < `minVersion`（13.0.0） | ⚠️ 版本过低（当前 x.y.z / 要求 ≥ 13.0.0） | 「一键升级」 |
| `missing` | 全部候选不存在 | ❌ 未检测到 | 「一键安装」 |
| `broken` | 文件存在但 `--version` 失败（架构不符 / 权限 / 损坏） | ❌ 文件损坏或不可执行 + 原因 | 「重新安装」 |
| `unknown` | 尚未检测过 | 灰色「未检测」 | 「检测」 |

### 5.3.1 版本门槛：硬门槛与推荐版本分离（已决定）

这是本节最关键的取舍，直接决定"系统已装老版本"是被阻断还是被放行：

| 字段 | 值 | 语义 | 行为 |
| --- | --- | --- | --- |
| `minVersion` | **13.0.0** | 硬门槛，低于即 `outdated` | 判为不可用，引导升级 |
| `recommendedVersion` | **16.0.0** | 推荐版本（内置版本） | **仅提示**"可升级"，状态仍为 `ready`，不阻断 |

**为什么门槛定 13.0.0 而不是 16.0.0**：

1. `rg` 在本项目的用法只有 `rg --line-number --no-heading -- <pattern> <path>`（`LocalRuntimeTools.java:233-238`），这三个参数在 13.x 起就稳定存在，**功能上不依赖 16.0.0 的新特性**；
2. 若硬门槛设为 16.0.0，**本机这种"已装 15.2.0"的用户会被直接判为 `outdated` 并阻断**，而这台机器上的 rg 实际完全能跑（实测 `--version` 正常）。用版本号阻断一个能用工具，是错误取舍；
3. 版本过新的收益（新特性/性能微调）远小于"阻断用户已可用环境"的代价。

**结论**：状态判定只看 `minVersion`；`recommendedVersion` 只用于产生一条**非阻断**提示。用户已装 15.2.0 时 → `status: ready`、`message: "已就绪（15.2.0，可升级至 16.0.0）"`。§8.2 中 `outdated` 的徽标文案也据此调整为「⚠️ 版本过低（< 13.0.0）」。

`broken` 的典型成因值得在文案里体现，因为用户会真实遇到：

- **架构不符**：在 arm64 机器上放了 x86_64 二进制（`bad CPU type in executable`）；
- **权限缺失**：复制后丢了 `+x`（`Permission denied`）；
- **macOS 隔离属性**：从网络下载后带 `com.apple.quarantine`（`cannot be opened because the developer cannot be verified`）。**内置路径不会有此问题**（实测 release 包仅带 `com.apple.provenance`），只有在 §6.2 在线下载路径才需主动清理。

---

## 6. 安装设计（对应需求点 2）

### 6.1 内置安装（默认路径，无网络）

```text
1) 定位内置二进制：<resources>/runtime/tools/rg/<platform>-<arch>/rg
2) 计算 sha256，与 EXPECTED_SHA256[target] 比对，不一致 → 中止并报错（不静默降级）
3) 目标：<rootDir>/tools/rg/<version>/rg
4) mkdir -p，copyFile，chmod 0o755（Windows 跳过）
5) 原子替换：先写 .tmp 再 rename，避免半写坏文件
6) 回写 settings.json（enhancements.rg），返回 needsRestart
```

平台映射（与既有 `prepare-jre.mjs:10-14` 的命名习惯保持一致，便于统一维护）：

| `process.platform` | `process.arch` | 目录名 | 上游资源 |
| --- | --- | --- | --- |
| `darwin` | `arm64` | `mac-arm64` | `aarch64-apple-darwin` |
| `darwin` | `x64` | `mac-x64` | `x86_64-apple-darwin` |
| `win32` | `x64` | `win-x64` | `x86_64-pc-windows-msvc` |

> 不支持的组合（如 `linux`、`win32/arm64`）必须显式返回 `installMode: 'none'`、`canAutoInstall: false`，并在 UI 上把按钮置灰 + 给出说明，而不是让用户点了才报错。

### 6.2 在线安装（兜底，仅在无内置时）

钉死版本，禁止"取 latest"——否则产物不可复现、校验值必然失效：

```text
源: https://github.com/BurntSushi/ripgrep/releases/download/16.0.0/ripgrep-16.0.0-<target>.tar.gz|zip
```

```text
1) 下载到 <rootDir>/tools/.download/ripgrep-16.0.0-<target>.tar.gz
2) 校验 SHA-256（与 §0.2 常量表一致），不符立即删除并报错
3) 解压（tar.gz 用系统 tar；zip 用 electron 自带能力或纯 Node 实现），只取其中的 rg 二进制
4) macOS：若存在 com.apple.quarantine，执行 xattr -d 清理
   Windows：无隔离属性概念，跳过
5) 后续步骤同 6.1 的第 3~6 步
```

失败必须**可区分**，因为用户会问"为什么装不上"：`NETWORK_UNREACHABLE`（GitHub 不可达）/ `CHECKSUM_MISMATCH`（下载损坏或中间人）/ `UNSUPPORTED_PLATFORM` / `DISK_ERROR`。

> 需要明确：这条路径**不属于"开箱即用"承诺**。它是 §2 表格里 B 方案的降级使用，UI 上应标注「需联网」字样。

### 6.3 幂等与"已就绪就不动"

安装入口第一步永远是"先检测"：

- 已 `ready` 且 `source` 为 `bundled`/`user-online` → 直接返回当前状态，**不做任何写操作**；
- 已 `ready` 但 `source: 'system'` 且用户点「一键安装」→ 视为"接管"，装到用户级目录并优先使用（版本一致性收益）；
- `mode: 'upgrade'` 才允许覆盖已有用户级二进制。

---

## 7. 生效机制

### 7.0 关键缺陷修复：后端由外部进程启动时 PATH 不生效（实测踩坑）

这是落地后由真实使用暴露出的**最重要问题**，必须在读 §7.1 前先理解。

**现象**：设置页显示 `ripgrep 16.0.0 ✅ 已支持`，但 Agent 里执行 `rg` 却返回 `/bin/sh: rg: command not found`。

**根因链路**（逐层实测确认）：

```java
// 1) 工具命令最终由这里执行，注意：没有设置 environment
//    .../local/command/CommandExecutor.java:36
new ProcessBuilder(options.shell(), "-lc", command)
        .directory(workspace.workingDirectory().toFile())
```

```javascript
// 2) 于是 PATH 完全继承"谁启动了 JVM"
```

实测证据（同一台机器）：

| 后端由谁启动 | 该进程 PATH 是否含 rg | Agent 能否用 rg |
| --- | --- | --- |
| Electron（`startBackend`，注入过 PATH） | ✅ 含 | ✅ 可用 |
| **IDEA / 外部进程**（`target/classes` 直接跑） | ❌ 不含 | ❌ `command not found` |

**这解释了一个极具迷惑性的组合**：检测逻辑是对的（按绝对路径枚举确实找到了文件），但执行环境是错的（那个 JVM 的 PATH 里没有）。两个都是真话，但对用户呈现为"显示正常却不能用"。

**为什么不能只靠 §7.1 的 `spawn` 注入**：§7.1 只在"Electron 自己拉起后端"时有效。开发态下后端常由 IDEA 启动（便于断点调试），此时 `spawn` 那段代码根本不执行。

**解决方案：包装 shell（`tools/bin/agentforge-sh`）**

安装/升级成功后自动生成一个包装脚本，它先注入 PATH 再转调真正的 shell：

```sh
#!/bin/sh
AGENTFORGE_EXTRA_PATH=""
[ -d '<用户级 rg 目录>' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'<用户级 rg 目录>:'
[ -d '/opt/homebrew/bin' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'/opt/homebrew/bin:'
[ -n "$AGENTFORGE_EXTRA_PATH" ] && PATH="$AGENTFORGE_EXTRA_PATH$PATH"
export PATH
exec /bin/sh "$@"
```

然后把这个脚本作为后端配置项传入：

```javascript
// main.mjs:startBackend()
...(shellWrapper ? [`--agentforge.desktop.shell=${shellWrapper}`] : [])
```

```java
// AgentRuntimeService.java
.shell(resolveShell())     // 读配置，校验存在且可执行，异常回退 /bin/sh
```

**实测效果**（模拟 IDEA 的干净 PATH `/usr/bin:/bin`）：

```
用 /bin/sh             → exit=127, "rg: command not found"
用 agentforge-sh       → exit=0, 正确返回匹配行
```

**为什么用包装脚本而不是改核心 `CommandExecutor`**：

| 方案 | 评价 |
| --- | --- |
| 改 `ProcessBuilder` 加 `.environment()` | 要动 `AgentForge` 核心仓库，跨仓评审成本高 |
| **传一个自带 PATH 的 shell** | ✅ 只改桌面端 + 一个配置项，核心零改动 |
| 要求用户改 IDEA 启动配置 | ❌ 把问题推给用户，且新装用户仍会踩 |

**配套改进：检测口径必须与执行口径一致**

修复过程中还发现一个连带 bug：检测用的是 `/bin/sh`，而实际生效的是包装脚本，导致"修复后仍显示未修复"。现在 `resolveInShell({ rootDir })` 会自动选用该 rootDir 下实际生效的 shell（有包装脚本就用它）。

同时检测结果新增 `effective` 字段，把"文件存在"与"Agent 真能用"分开表达：

| 字段 | 含义 |
| --- | --- |
| `status: ready` | 找到了可执行的 rg 文件 |
| `effective: false` | **但 Agent 的 shell 里解析不到** → UI 显示「待修复」+「修复 PATH」按钮 |
| `effective: true` | Agent 实际可调用 |

这条设计保证了：**UI 上显示的每个 ✅，都代表 Agent 真的能用**，不再有误导。

### 7.1 PATH 注入（核心改动，一行搞定）

在 `startBackend()` 里构造 `spawn` 环境时前置工具目录：

```javascript
// main.mjs:164-170 附近改造
const toolPath = path.join(desktopRootDir(), 'tools', 'rg')   // 父目录即可，内部按版本分子目录
const rgBin = <检测得到的 rg 绝对路径的 dirname>
const child = spawn(java, args, {
  cwd: rootDir,
  stdio: ['ignore', 'pipe', 'pipe'],
  windowsHide: true,
  env: {
    ...process.env,
    PATH: [rgBin, process.env.PATH || ''].join(path.delimiter)
  }
})
```

**已验证该做法有效**（§0.1 第 4 条实测：注入后 `/bin/sh -lc 'which rg'` 成功）。而且因为它作用在"进程环境"层，核心框架的 `CommandExecutor` 完全不需要改动。

同时建议**顺带修复 GUI PATH 缺失的通用问题**（独立价值，不依赖 rg）：

| 平台 | 建议追加的 PATH 前缀 | 解决的问题 |
| --- | --- | --- |
| macOS | `/opt/homebrew/bin`、`/usr/local/bin`、`${home}/.cargo/bin` | GUI 启动拿不到 Homebrew / cargo，用户装的 `node`、`ffmpeg`、`rg` 全部不可见 |
| Windows | `${LOCALAPPDATA}\\Programs\\...` 等常见目录 | 同上 |

> 这条能直接消除"我在终端里能用，在 Agent 里不能用"这一类最高频的用户困惑。**建议与 rg 一起做**，但要在文档/日志里可区分（避免把"用户装了但我们路径错了"误报成"用户没装"）。

### 7.2 Windows 的 `/bin/sh` 问题（前置必做）

`LocalRuntimeOptions.java:14` 默认 shell 为 `/bin/sh`，而 `AgentRuntimeService.java:245-250` 未覆盖。**Windows 下 `rg`/`bash` 工具必然失败**，因为不存在 `/bin/sh`。这与 rg 是否安装无关，属于既有缺陷。

两种落地方式，二选一（建议 A，改动最小且不触碰核心语义）：

- **A. 桌面端显式传 shell（推荐）**：在 `AgentRuntimeService` 构造 options 时按平台选择，并新增可配置项：

```java
LocalRuntimeOptions.builder()
        .shell(resolveShell())            // win32 → powershell.exe / cmd.exe；其余保持 /bin/sh
        .commandTimeoutMillis(5 * 60 * 1000)
        .build()
```

- **B. 核心框架内部处理**：`LocalRuntimeOptions` 的默认值按 `os.name` 判定。改动落在 `AgentForge` 仓库，属于跨仓改动，需单独评审。

> 若走 A，**必须为 `CommandExecutor` 的 `-lc` 参数做适配**：`cmd.exe` 用 `/c`，PowerShell 用 `-Command`。当前代码把 `-lc` 写死在 `CommandExecutor.java:36`，所以"只传 shell 路径"并不够——**这需要核心框架暴露 `argsPrefix` 或内部按 shell 类型适配**。这是本方案中唯一必须跨仓协同的点，建议单独立项，不要塞进 rg 需求里。

### 7.3 何时需要重启后端

后端进程的 `PATH` 在启动瞬间固化，后续改环境对已运行进程无效。因此：

| 场景 | 是否需重启 |
| --- | --- |
| 首次启动初始化（在 `startBackend` 之前完成） | 否 |
| 应用内「一键安装」（后端已在跑） | **是** |
| 检测到 `ready`（无需安装） | 否 |

既有 `stopBackend()`（`main.mjs:191`）已具备优雅停止能力，可复用于"重启后端"。UI 侧给出明确反馈，不要静默重启：

```text
安装成功 → 提示「已安装 ripgrep 16.0.0，需重启本地服务后生效」→ [立即重启]
```

**设计取舍**：也可以选择"不重启，让 `install` 返回 `needsRestart: true`，下次启动自然生效"。更轻，但用户体验是"装了却还用不了"，容易被认为是 bug。**建议提供「立即重启」按钮**，默认不自动重启（避免打断正在进行的对话）。

---

## 8. UI 设计：设置 → 能力增强

### 8.1 栏目位置与结构

在 `ConfigDrawer.vue:23-26` 的「系统配置」之后新增第四个 tab（用户要求"系统环境为默认第一行"）：

```html
<!-- ConfigDrawer.vue:23-26 之后追加 -->
<button type="button" :class="{ active: tab === 'enhance' }" @click="tab = 'enhance'">
  <svg class="settings-nav-icon" viewBox="0 0 24 24" aria-hidden="true">
    <path d="M12 3l2.4 5.2 5.6.8-4 3.9 1 5.6-5-2.7-5 2.7 1-5.6-4-3.9 5.6-.8z"/>
  </svg>
  <span>能力增强</span>
</button>
```

右侧内容区（`v-show="tab === 'enhance'"`），结构为**第一行环境概览 + 下方能力列表**：

```text
┌ 工作台系统环境 ──────────────────────────────────────────────┐
│ macOS 14.6 · arm64          [重新检测]                        │  ← 第一行：只读概览
│ 内置运行时 embedded-java · 工具目录 ~/.agentforge-desktop/tools│
└──────────────────────────────────────────────────────────────┘

┌ 能力列表 ────────────────────────────────────────────────────┐
│ ripgrep (rg)                    高速文件内容检索    [未检测]   │
│ 未检测到，安装后 Agent 可用 rg 工具检索代码                     │
│                                                              │
│ ripgrep (rg)                    高速文件内容检索    ✅ 已支持 │
│ 16.0.0 · 内置 · tools/rg/16.0.0/rg                           │
│                                                              │
│ ripgrep (rg)                    高速文件内容检索    ❌ 未安装 │
│ 未检测到可用的 rg                               [一键安装]     │
└──────────────────────────────────────────────────────────────┘
```

### 8.2 行内状态机（与 §5.3 一一对应）

| 状态 | 右侧徽标 | 右侧动作 |
| --- | --- | --- |
| `unknown` | 灰点「未检测」 | `[检测]` |
| `ready` | ✅「已支持」+ 版本/来源/路径；低于推荐版本时附「可升级」 | `[重新检测]` / 附 `[升级至 16.0.0]` |
| `outdated` | ⚠️「版本过低（< 13.0.0）」 | `[一键升级]` |
| `missing` | ❌「未检测到」 | `[一键安装]`（无内置时标注"需联网"） |
| `broken` | ❌「不可用」+ 原因 | `[重新安装]` |
| 安装中 | 转圈 + 进度文案 | 按钮禁用 |
| 安装完成待重启 | ✅ + 「需重启生效」 | `[立即重启]` |

用户描述的交互被完整保留：**默认不检测（灰色/未检测）→ 点检测 → 右侧出结果 → 支持则 ✅，不支持则出一键安装按钮**。差别只在于：因为内置了二进制，绝大多数用户第一次点「检测」就直接是 ✅。

### 8.3 前端实现要点

- 数据：`data()` 新增 `enhancements: []`、`detecting: false`、`installingId: ''`（对齐 `ConfigDrawer.vue:176-194` 现有风格）；
- 打开弹窗时（`watch.visible`，`:205-208`）先渲染 `settings.json` 快照，**再异步 `detect` 校正**，避免空白闪烁；
- 所有调用都要防降级：浏览器态 `window.electronAPI` 不存在时，`$message.warning('当前环境不支持能力检测')`，与 `saveSystem`（`:260-264`）的处理方式一致；
- 不引入新的 UI 组件库，复用 `el-button` / `el-tag` 与既有 `system-setting-row` 样式类，减少样式分叉。

---

## 9. 落点清单（可直接照做）

| # | 文件 | 位置 | 改动 |
| --- | --- | --- | --- |
| 1 | `agentforge-desktop-ui/package.json` | `extraResources`（:55-65） | 追加 `{ "from": "runtime/tools", "to": "runtime/tools" }` |
| 2 | `.gitignore` | :19-21 附近 | 追加 `**/runtime/tools/`（二进制不入库） |
| 3 | `agentforge-desktop-ui/scripts/build/prepare-rg.mjs` | 新文件 | 按 `prepare-jre.mjs` 风格实现"按平台准备二进制 + 校验 sha256" |
| 4 | `agentforge-desktop-ui/package.json` | `scripts` | 增加 `runtime:rg:mac-arm64` / `mac-x64` / `win-x64` |
| 5 | `agentforge-desktop-ui/electron/enhancements.mjs` | 新文件 | 检测/安装/写快照的纯逻辑（便于单测，不依赖 Electron API） |
| 6 | `agentforge-desktop-ui/electron/main.mjs` | `DEFAULT_SETTINGS` / `normalizeSettings`（:355-368） | 新增 `enhancements` 字段，**否则被白名单丢弃** |
| 7 | `agentforge-desktop-ui/electron/main.mjs` | `startBackend()` 的 `spawn`（:164-170） | `env.PATH` 前置 rg 目录（+ macOS Homebrew 路径） |
| 8 | `agentforge-desktop-ui/electron/main.mjs` | `registerIpc()`（:472-488） | 新增 3 个 `local-agent:enhancements:*` 通道 |
| 9 | `agentforge-desktop-ui/electron/main.mjs` | `app.whenReady()`（:588-593） | `startBackend()` **之前**执行一次静默初始化 |
| 10 | `agentforge-desktop-ui/electron/preload.cjs` | 全文件（:1-52） | 暴露 `detectEnhancements` / `installEnhancement` / `listEnhancements` |
| 11 | `agentforge-desktop-ui/src/components/ConfigDrawer.vue` | nav（:13-27）、section（:81-100 之后）、data（:176-194）、methods（:260-279 之后）、styles | 新增「能力增强」tab + 列表 UI + 检测/安装方法 |
| 12 | `agentforge-desktop-server/.../modules/system/LocalDesktopController.java` | :22-30 之后 | （可选）`GET /api/local/enhancements` 只读快照 |
| 13 | `agentforge-desktop-server/.../agent/runtime/AgentRuntimeService.java` | :245-250 | （Windows 前置项）按平台传 `.shell(...)`，需配合核心 §7.2 |

**改动量评估**：主路径（不含 Windows shell 修复）约 1 个新脚本 + 1 个新模块 + 3 处主进程改动 + 1 处 UI 扩展，属于可控范围。

> **实施状态：§9 的 1~11 项已落地并通过验证**，实际实现与该清单的差异记录在 §9.1，验证结论见 §9.2。第 12、13 项按计划推迟（12 为可选，13 需跨仓评审）。

---

## 9.1 落地实现说明（已完成）

本节记录**实际写下的代码**与清单的差异，避免后续维护者按清单去找不存在的代码。

### 9.1.1 相对清单的 4 处调整

| 项 | 清单原计划 | 实际实现 | 原因 |
| --- | --- | --- | --- |
| A | 安装模式只有 `auto` / `online` / `upgrade` | 增加 **`bundled`** 模式 | 启动初始化必须**绝对禁止联网**。仅靠 `auto` 无法表达"这次只许用内置，失败就放弃"，会在冷启动时悄悄发起网络请求 |
| B | 探测直接 `spawnSync` 一次 | 增加**失败重试（默认 2 次 / 120ms）** | 实测踩到：macOS 首次执行新落盘的 ad-hoc 签名二进制时系统在做安全扫描，首次 `spawn` 偶发失败，会误判成 `broken`（"刚装好却说文件损坏"） |
| C | 下载用全局 `fetch` | 改为**可注入 `fetchImpl`**，主进程传 Electron `net.fetch` | 实测：Electron 主进程的全局 `fetch` **不遵循系统代理 / PAC**（本机实测 `UND_ERR_CONNECT_TIMEOUT`，而 Node 直连同 URL 成功）。不换实现的话，有代理的企业环境在线安装必挂 |
| D | 未提及下载超时 | 增加**60s 超时 + 错误码分类** | 受限网络下请求会长时间挂起，界面卡在"安装中"；必须主动中断并给出 `NETWORK_UNREACHABLE` / `CHECKSUM_MISMATCH` 可行动提示 |

### 9.1.2 实际文件清单

**新增文件（2 个）**

```text
agentforge-desktop-ui/electron/enhancements.mjs      # 检测 / 安装 / PATH 计算（纯 Node，不依赖 Electron API）
agentforge-desktop-ui/scripts/build/prepare-rg.mjs   # 打包期：下载 + 校验 + 解压 + 架构自检 + manifest
```

**修改文件（6 个）**

| 文件 | 实际改动 |
| --- | --- |
| `electron/main.mjs` | 引入增强模块；`DEFAULT_SETTINGS` / `normalizeSettings` 增加 `enhancements`；新增 `bundledToolsDir()` / `enhancementContext()` / `backendPath()` / `saveEnhancementSnapshot()` / `initEnhancements()` / `restartBackend()`；`spawn` 注入 `env.PATH`；注册 4 个 IPC（list / detect / install / restart-backend）；`whenReady` 中 **`initEnhancements()` 先于 `startBackend()`** |
| `electron/preload.cjs` | 暴露 `listEnhancements` / `detectEnhancement(id)` / `installEnhancement(id, mode)` / `restartBackend()` |
| `src/components/ConfigDrawer.vue` | 新增「能力增强」nav 项 + 面板（环境概览行 + 能力列表 + 状态徽标 + 安装/升级/重启按钮）；`data` 增加 8 个字段；新增 `loadEnhancements` / `detectEnhancements` / `installEnhancement` / `restartBackend` / `statusLabel` / `shortPath` 方法；`load()` 尾部调用 `loadEnhancements()`；新增约 60 行 scoped 样式 |
| `src/api/client.js` | 新增 `invalidateServerInfo()`——后端重启后端口与 token 都会变，不失效缓存会导致后续请求 401 |
| `package.json` | `extraResources` 追加 `runtime/tools`；`scripts` 追加 4 条 `runtime:rg*` |
| `.gitignore` | 追加 `**/runtime/tools/` |

### 9.1.3 关键实现细节（值得记住的三点）

**① 启动时序：必须先装工具，后起后端**

```javascript
app.whenReady().then(async () => {
  await initEnhancements()          // 1) 先把 rg 落到用户级目录
  backend = await startBackend()    // 2) 再启动后端，此时 backendPath() 才能算对
  registerIpc()
  createWindow()
})
```

顺序反了不会报错，但 `backendPath()` 会拿不到工具目录，导致"设置页显示已就绪、Agent 里却找不到 rg"的割裂。

**② 幂等判定用 `source === 'user'`，不是 `status === 'ready'`**

```javascript
// 只有"本应用管理的二进制"才跳过安装。
// 若用户机器上系统已装 15.2.0，status 也是 ready，
// 但它不是我们管理的，版本也不受控，仍需落一份内置版本到用户级。
if (mode !== 'upgrade' && current.status === 'ready' && current.source === 'user') {
  return { installed: false, message: '当前已安装，无需重复安装' }
}
```

**③ 探测绝不依赖 shell**

```javascript
export function probeRg(bin, { retries = 2, delayMs = 120 } = {}) {
  if (!bin || !fs.existsSync(bin)) return { ok: false, reason: '文件不存在' }
  // 直接 spawnSync(绝对路径)，不走 which / bash -c
}
```

这是整个方案的地基：`which rg` 在 GUI 进程里必然失败（§0.1 实测），用它做检测等于把 bug 写进检测逻辑。

### 9.1.4 内置二进制的落盘结构

```text
<repo>/agentforge-desktop-ui/runtime/tools/rg/mac-arm64/     # 打包产物（gitignore）
├── rg                                                        # 3.83 MB
└── manifest.json                                             # { version, target, asset, binSha256, preparedAt }

打包后：<resources>/runtime/tools/rg/mac-arm64/rg

首次启动复制到：
~/.agentforge-desktop/tools/rg/<version>/rg                    # 用户级（source: 'user'）
```

`manifest.json` 里记录 `binSha256`，安装时用它校验内置二进制完整性——防止打包/传输过程中文件损坏被静默接受。

---

## 9.2 验证结果（已实测，可复跑）

### 9.2.1 已验证项

| # | 验证内容 | 结果 |
| --- | --- | --- |
| 1 | 版本比较 `15.2.0 vs 13.0.0` / `12.1.0 vs 13.0.0` | `1` / `-1`，门槛行为正确 |
| 2 | 本机（系统已装 15.2.0）检测结果 | `ready` + `source: system` + `upgradeAvailable: true`，**未判 outdated**（符合 §5.3.1 决定） |
| 3 | 打包脚本 `prepare-rg.mjs mac-arm64` | 下载 → sha256 校验 → 解压 → `file` 架构自检 → 落盘 → `--version` 探活，全通过 |
| 4 | 脚本二次执行 | `already prepared`，幂等 |
| 5 | 从内置安装 | `installed: true`、`source: user`、落盘 `tools/rg/16.0.0/rg`、权限 `755` |
| 6 | 再次安装 | `installed: false`、`当前已安装，无需重复安装` |
| 7 | 用户级优先级 | `source: user`（高于内置与系统） |
| 8 | **PATH 注入前后对比** | 注入后 `which rg` 成功、`rg --version` 输出 16.0.0；**未注入时 `exit=1` 找不到**（复现原问题） |
| 9 | **端到端复刻 Agent 调用** | `rg --line-number --no-heading -- 'helloRg' '.'` → **`exitCode=0`** 且返回 `./a.java:1:class A {...}`；未注入时 **`exitCode=127` + `command not found`** |
| 10 | `broken` 判定 | 写入垃圾内容后 `status: broken`，message 含 `ENOEXEC` 真实原因 |
| 11 | `outdated` 判定 | 伪造 12.1.0 → `outdated` + `版本过低（12.1.0），需 ≥ 13.0.0` |
| 12 | 从内置修复损坏二进制 | 修复后 `ready`，`probeRg` 返回 `{ok:true, version:'16.0.0'}` |
| 13 | 不支持平台（linux） | `canAutoInstall: false` + 明确文案，按钮置灰而非点了才报错 |
| 14 | 在线路径错误分类 | 网络失败 → `NETWORK_UNREACHABLE: ENOTFOUND`；内容不符 → `CHECKSUM_MISMATCH`；挂起 → 60s 超时中止 |
| 15 | **真实 Electron 端到端** | 初始化落盘 → 写快照 → IPC 检测 → 后端健康检查 `true` → UI 渲染「能力增强」卡片，全通过 |
| 16 | UI 实际渲染 | 截图确认：环境行显示 `macOS · arm64 · 工具目录 …/tools`，列表显示 `ripgrep 16.0.0 [已支持] 已就绪（16.0.0，已安装）` |
| 17 | 前端构建 | `vite build` 通过 |

### 9.2.2 复跑命令

```bash
cd agentforge-desktop-ui

# 1) 准备内置二进制（幂等，已存在则跳过）
node scripts/build/prepare-rg.mjs mac-arm64

# 2) 验证检测可绕过 shell（关键假设）
/opt/homebrew/bin/rg --version | head -1        # 应输出 ripgrep 15.2.0（系统已装）
/bin/sh -lc 'which rg || echo NOT_FOUND'        # 应输出 NOT_FOUND（证明必须绕开 shell）

# 3) 验证注入后可用
PATH="$(pwd)/runtime/tools/rg/mac-arm64:$PATH" /bin/sh -lc 'rg --version | head -1'

# 4) 前端构建
npx vite build
```

### 9.2.3 已知限制（环境相关，非代码缺陷）

1. **本机在线下载无法端到端演示**：这台机器到 GitHub 直连超时（`scutil --proxy` 显示无代理，Electron `net.fetch` 与 Node `fetch` 均超时）。在线路径的**逻辑**已用注入式 `fetch` 完整验证（错误分类、校验、超时均正确），真实下载需在可访问 GitHub 的网络复测。
2. **Windows 未实测**：`win-x64` 的资源名、sha256、目录映射已按官方 release 落表，但缺少 Windows 机器做真机验证。
3. **§7.2 的 `/bin/sh` 问题仍在**：Windows 下 `rg` 工具要用起来，还需修 `LocalRuntimeOptions.shell` 与 `CommandExecutor` 的 `-lc` 适配（跨仓，建议单独立项）。**当前在 Windows 上"装好 rg"只是必要条件，不充分。**

---

## 10. 边界条件与风险

| # | 风险 / 边界 | 影响 | 应对 |
| --- | --- | --- | --- |
| 1 | **GUI PATH 缺失导致假阴性** | 用户装了 rg 仍报缺失 | 检测**禁用 `which`**，用绝对路径枚举；同时注入 PATH 修根因（§7.1） |
| 2 | **Windows 无 `/bin/sh`** | `rg`/`bash` 工具在 Windows 全部失效 | 独立立项修 `shell` + `-lc` 适配（§7.2）；**rg 安装对 Windows 是必要不充分条件** |
| 3 | 新增字段被 `normalizeSettings` 丢弃 | 首个检测快照丢失，页面反复闪烁 | 第 6 项改动必须与第 11 项**同批提交** |
| 4 | 打包遗漏 `runtime/tools` | 安装后检测为 `missing`，用户以为功能坏了 | 第 1 项 `extraResources` + 打包后自检（§11 验证项 4） |
| 5 | 架构不匹配（x64 包跑在 arm64 / 反之） | `broken`，报 bad CPU type | `prepare-rg.mjs` 里用 `file` 校验架构（照抄 `prepare-jre.mjs:46-53` 的做法） |
| 6 | 在线路径被墙 / 校验失败 | 安装失败 | 区分 `NETWORK_UNREACHABLE` / `CHECKSUM_MISMATCH`，不静默降级 |
| 7 | 安装覆盖正在运行的二进制 | macOS 可覆盖，Windows **文件被占用会失败** | 写入 `.tmp` + `rename` 原子替换；Windows 上若 `EBUSY` 则提示稍后重试 |
| 8 | 多版本目录堆积 | 磁盘占用 | 保留当前版本 + 上 1 个版本，其余在安装成功后清理 |
| 9 | 用户既有 Homebrew rg 被"接管" | 用户困惑版本变化 | `source: 'system'` 时默认不覆盖，仅在用户点「一键升级/安装」时接管 |
| 10 | 后端重启打断对话 | 正在进行的任务被终止 | 默认**不自动重启**，明确提示由用户点「立即重启」 |
| 11 | 版本钉死导致 `rg` 输出行为差异 | 未来 `--line-number --no-heading` 语义变化 | 钉版本 + 升级走显式流程，不静默跟随 latest |
| 12 | 企业内网完全无外网 | 在线兜底不可用 | 内置路径保证核心可用；在线按钮置灰并说明"内网环境请使用内置版本" |

---

## 11. 实施顺序与验证

### 11.1 顺序（每步可独立验收）

```text
S1  打包侧：prepare-rg.mjs + extraResources + .gitignore
    → 验收：npm run runtime:rg:mac-arm64 后 runtime/tools/rg/mac-arm64/rg 存在且 --version 正常

S2  主进程：enhancements.mjs（检测 + 内置安装）+ 3 个 IPC + preload 暴露
    → 验收：DevTools 里 await window.electronAPI.detectEnhancements({id:'rg'}) 返回 ready

S3  启动初始化：whenReady 里在 startBackend 之前静默复制 + PATH 注入
    → 验收：删除 tools 目录后重启应用，自动恢复；Agent 调 rg 工具成功

S4  UI：能力增强 tab + 列表 + 状态机 + 安装/重启交互
    → 验收：五种状态可人工构造并正确渲染

S5  在线兜底：下载 + sha256 + 解压 + quarantine 清理
    → 验收：清空内置目录后点「一键安装」可成功

S6  （独立）Windows shell 修复
    → 验收：Windows 上 bash/rg 工具可执行
```

### 11.2 关键验证项（含实测脚本）

```bash
# 1) 检测能力：绕过 shell 直接探测（本机应命中 Homebrew 的 15.2.0）
$ /opt/homebrew/bin/rg --version | head -1
ripgrep 15.2.0

# 2) 复现假阴性（证明必须绕开 shell）
$ /bin/sh -lc 'which rg || echo NOT_FOUND'
NOT_FOUND

# 3) 验证 PATH 注入可修复（方案核心假设）
$ PATH="<rgdir>:$PATH" /bin/sh -lc 'rg --version | head -1'
ripgrep 16.0.0 (rev 669cb4c755)

# 4) 验证内置二进制无 quarantine（不会弹 Gatekeeper）
$ xattr -l <extracted>/rg
com.apple.provenance

# 5) 验证 sha256 常量
$ shasum -a 256 ripgrep-16.0.0-aarch64-apple-darwin.tar.gz
5a145656d5067144001bfce6648767fd3254599aa736f1ffdb7fb907760b8ca1
```

**端到端验收口径**：在 Agent 里让模型执行 `rg` 工具检索工作空间代码，返回 `exitCode=0` 且有匹配行；同时把二进制移走，确认返回 `exitCode=127` + `command not found`（证明检测与工具链路指向同一事实源，不存在"设置页说好、实际用不了"的割裂）。

---

## 12. 明确不做的事

| 不做 | 原因 |
| --- | --- |
| 不改造核心 `LocalRuntimeTools`，不把 `rg` 换成 Java 实现 | 性能差距（忽略 gitignore、多线程、跳过二进制）无法用现有 `grep`（`LocalRuntimeTools.java:185`）弥合 |
| 不让用户手动填写 rg 路径 | 增加认知成本，且无法校验；检测链已覆盖三类来源 |
| 不在启动时联网检查更新 | 违背"不隐式联网"原则，且会拖慢冷启动 |
| 不自动重启后端 | 会打断正在进行的对话；改为显式「立即重启」 |
| 不把 Windows shell 修复混入本需求 | 涉及 `-lc` 语义适配，需跨仓评审，单独立项更可控 |
| 不为 `grep`/`glob` 等纯 Java 工具做"检测" | 它们无外部依赖，永远可用，列在设置里只会制造噪声 |

---

## 13. 遗留待确认

1. ~~**最低版本要求**取 `16.0.0` 还是放宽到 `14.x`？~~ → **已决定：`minVersion = 13.0.0`（硬门槛），`recommendedVersion = 16.0.0`（仅提示）**。理由与影响见 §5.3.1：本机已装 15.2.0 的状态为 `ready` + 可升级提示，不阻断。
2. **内置还是在线**为主路径，需产品侧确认是否接受 **+3.8MB/平台** 的安装包增量。
3. 能力列表后续是否要扩到 `git` / `node` / `python` 等。当前只做 rg，但 `EnhancementStatus` 结构（§4.2）已按多能力预留，无需返工。