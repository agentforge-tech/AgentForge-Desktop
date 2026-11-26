# AgentForge-Desktop 能力增强工具注入执行流程（以 ripgrep 为例）

> 对应仓库：`AgentForge-Desktop` + 上游 `AgentForge`（只读依赖，未改动）
> 相关模块：`electron/enhancements.mjs`、`electron/main.mjs`、`desktop-server` 的 `DesktopProperties` / `AgentRuntimeService`、上游 `CommandExecutor`
> 适用范围：macOS / Linux（**Windows 见 §10 限制说明**）
> 状态：已落地并实测；本文只讲**执行流程**，方案选型与检测设计见同目录《AgentForge-Desktop增强能力（ripgrep）检测与一键安装方案.md》

**阅读指引**

| 你想了解 | 看哪节 |
| --- | --- |
| 整体是怎么串起来的 | §2 七步流程图、§4.6 端到端时序 |
| **安装 / 升级 rg 具体做了什么** | **§3** |
| **为什么换个 shell 就能注入环境变量** | **§4** |
| 包装脚本长什么样（含完整示范） | §5 |
| Java 侧怎么接住的 | §6 |
| 实测数据 | §7 |
| 怎么排查故障 | §11 |

---

## 0. 一句话结论

Agent 执行 `rg` 时能否成功，取决于**后端 JVM 内 `ProcessBuilder` 用哪个 shell**。本方案不修改任何环境变量，而是**安装一个自带 PATH 的 shell 包装脚本**，让它在被调用时自己注入 PATH。这样无论后端由 Electron、IDEA 还是任何外部进程启动，`rg` 都能被解析到。

```text
Electron 生成包装脚本  →  作为启动参数传给后端  →  Spring 绑定配置
        ↓
Java 校验后交给 LocalRuntime  →  上游核心存进 options  →  ProcessBuilder 用它执行命令
        ↓
包装脚本在执行瞬间注入 PATH  →  exec 转交真 shell  →  rg 被找到
```

---

## 1. 为什么需要这条链路（问题本质）

### 1.1 上游执行命令的代码

```java
// AgentForge/.../harness/runtime/local/command/CommandExecutor.java:36
process =
        new ProcessBuilder(options.shell(), "-lc", command)
                .directory(workspace.workingDirectory().toFile())
                .redirectErrorStream(true)
                .start();
```

关键点：**没有调用 `.environment()`**。

因此子进程环境**完全继承启动它的那个 JVM 进程的环境**。而这个 JVM 的 PATH 由"谁启动了它"决定：

| 后端由谁启动 | 该 JVM 的 PATH | `rg` 能否被找到 |
| --- | --- | --- |
| Electron（`startBackend()`，spawn 时注入过 PATH） | 含工具目录 | ✅ |
| IDEA / 命令行 / 任何外部进程 | 不含工具目录（尤其 GUI 环境缺 `/opt/homebrew/bin`） | ❌ `command not found` |

### 1.2 由此产生的两个失败模式

| 模式 | 现象 | 原因 |
| --- | --- | --- |
| **A. GUI PATH 缺失** | 用户终端里 `rg` 能用，Agent 里不能用 | macOS GUI 进程不读 shell 的 profile，PATH 里没有 Homebrew 目录 |
| **B. 外部进程启动后端** | 设置页显示"已支持"，Agent 执行却失败 | IDEA 启动的 JVM 的 PATH 里没有工具目录，而检测是独立按绝对路径做的 |

模式 B 是最具迷惑性的：**检测是对的，执行环境是错的**，两者都对，但对用户呈现为"显示正常却不能用"。

### 1.3 为什么不能在 spawn 时解决

`startBackend()` 里的 `env: { PATH: backendPath() }` 只在 **Electron 自己拉起后端**时生效。开发态用 IDEA 启动后端（便于断点调试）时，那段代码根本不执行。所以必须有**不依赖启动方式**的方案。

---

## 2. 整体执行流程（7 步）

```text
┌──────────────────────────────────────────────────────────────────┐
│ ①  生成包装脚本                                                   │
│    enhancements.mjs :: ensureShellWrapper()                       │
│    输出 ~/.agentforge-desktop/tools/bin/agentforge-sh             │
│    触发时机：安装/升级成功后、每次启动后端前                       │
└───────────────────────────┬──────────────────────────────────────┘
                            │ 返回脚本绝对路径
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ ②  Electron 传给后端进程                                          │
│    main.mjs :: startBackend()                                     │
│    ...(shellWrapper ? [`--agentforge.desktop.shell=${shellWrapper}`] : []) │
└───────────────────────────┬──────────────────────────────────────┘
                            │ 命令行参数
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ ③  Spring Boot 绑定配置属性                                       │
│    @ConfigurationProperties(prefix = "agentforge.desktop")        │
│    private String shell;   ← --agentforge.desktop.shell 自动绑定  │
└───────────────────────────┬──────────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ ④  Java 校验 + 安全回退                                            │
│    AgentRuntimeService :: resolveShell()                          │
│    不存在 / 不可执行 → 回退 /bin/sh（宁可退化不让链路崩）           │
└───────────────────────────┬──────────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ ⑤  交给 LocalRuntime                                              │
│    LocalRuntimeOptions.builder().shell(resolveShell())            │
└───────────────────────────┬──────────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ ⑥  上游核心保存（零改动，本来就有 shell 配置项）                   │
│    LocalRuntimeOptions.java:14                                    │
│    this.shell = isBlank(builder.shell) ? "/bin/sh" : builder.shell;│
└───────────────────────────┬──────────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ ⑦  执行时生效                                                      │
│    new ProcessBuilder(options.shell(), "-lc", command)            │
│    包装脚本先注入 PATH，再 exec /bin/sh -lc <command>              │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. 安装 / 升级 ripgrep 的完整流程

这一节讲清楚"点一下安装按钮，到底发生了什么"。**安装和升级走同一条路径**，区别只在 `mode` 参数。

### 3.1 触发入口

三个入口最终都调用同一个 `installRg()`：

| 入口 | 触发时机 | mode |
| --- | --- | --- |
| 启动静默初始化 | 应用启动、后端启动前（`initEnhancements()`） | `bundled`（**禁止联网**） |
| 设置页「一键安装」 | 用户点击，状态为 `missing`/`broken` | `auto`（有内置用内置，否则在线） |
| 设置页「一键升级」 | 用户点击，状态为 `outdated` 或可升级 | `upgrade`（允许覆盖已有版本） |

```javascript
// main.mjs :: app.whenReady()
await initEnhancements()        // ← ① 先装工具
backend = await startBackend()  // ← ② 再起后端（此时 backendPath 才算得准）
```

**顺序不能反**：先起后端再装工具，后端进程的 PATH 和环境已经固化，装完还得重启。

### 3.2 `installRg()` 主流程（含幂等判定）

```javascript
// enhancements.mjs :: installRg()
export async function installRg({ rootDir, bundledToolsDir, mode = 'auto', target, log, fetchImpl, systemCandidates }) {
  // ① 先检测：已就绪且是本应用管理的 → 直接返回，不做任何写操作
  const current = detectRg({ rootDir, bundledToolsDir, target, systemCandidates })
  if (mode !== 'upgrade' && current.status === 'ready' && current.source === 'user') {
    return { status: current, installed: false, needsRestart: false, message: '当前已安装，无需重复安装' }
  }

  // ② 选择来源：有内置优先用内置（无网络），否则在线下载
  const hasBundled = Boolean(bundledRgPath(bundledToolsDir, target) && fs.existsSync(bundledRgPath(bundledToolsDir, target)))
  const preferBundled = mode === 'bundled' || (mode !== 'online' && hasBundled)

  let result
  if (preferBundled)            result = installFromBundled({ rootDir, bundledToolsDir, target })
  else if (mode === 'bundled')  throw new Error('内置 ripgrep 缺失：...')   // 禁止联网时直接失败
  else                          result = await installFromOnline({ rootDir, target, log, fetchImpl })

  // ③ 关键：安装完立即生成包装脚本，保证"装完就能用"
  const wrapper = ensureShellWrapper({ rootDir, bundledToolsDir, target })

  // ④ 返回新状态 + 需重启标记
  return {
    status: { ...detectRg({ rootDir, bundledToolsDir, target, systemCandidates }), needsRestart: true },
    installed: true,
    needsRestart: true,
    shellWrapper: wrapper,
    message: `已安装 ripgrep ${result.version}，需重启本地服务后生效`
  }
}
```

**幂等判定的关键细节**：判断条件是 `source === 'user'`（本应用管理的），**而不是只看 `status === 'ready'`**。

```javascript
// 反例：如果只判 status，用户系统里已装 15.2.0 也会被当成"无需安装"，
//       于是永远不会落一份受控版本到用户级目录，版本行为不可控。
if (mode !== 'upgrade' && current.status === 'ready' && current.source === 'user')
```

### 3.3 从内置安装（默认路径，无网络）

```javascript
// enhancements.mjs :: installFromBundled()
function installFromBundled({ rootDir, bundledToolsDir, target }) {
  // ① 定位内置二进制
  const source = bundledRgPath(bundledToolsDir, target)     // <resources>/runtime/tools/rg/mac-arm64/rg
  if (!source || !fs.existsSync(source)) throw new Error(`内置 ripgrep 缺失：${source}`)

  // ② 完整性校验：优先信任 manifest 里的 binSha256
  const manifest = readManifest(path.dirname(source))
  if (manifest?.binSha256) {
    const actual = sha256File(source)
    if (actual !== manifest.binSha256) {
      throw new Error(`内置 ripgrep 校验失败：期望 ${manifest.binSha256}，实际 ${actual}`)
    }
  }

  // ③ 可执行性探测（拿到真实版本号，用于决定落盘目录名）
  const probed = probeRg(source)
  if (!probed.ok) throw new Error(`内置 ripgrep 不可执行：${probed.reason}`)

  // ④ 原子落盘到用户级目录：tools/rg/<真实版本>/rg
  const destination = path.join(rootDir, 'tools', 'rg', probed.version, RG_BIN_NAME)
  atomicCopy(source, destination)
  return { version: probed.version, path: destination, source: 'user' }
}
```

**原子落盘**（防止写一半留下坏文件）：

```javascript
function atomicCopy(source, destination) {
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  const temp = `${destination}.tmp-${process.pid}`
  fs.copyFileSync(source, temp)      // 先写临时文件
  fs.renameSync(temp, destination)   // 再原子改名（同目录内 rename 是原子的）
  ensureExecutable(destination)      // chmod 755
}
```

> 为什么必须原子：若直接覆盖写入时进程被杀，用户会得到一个**半截的坏二进制**，之后 `--version` 失败，被误判为"文件损坏"。用临时文件 + rename 可以保证"要么全新，要么全旧"。

**版本目录名取自真实探测结果**，不是写死的 `16.0.0`。这样即使内置包被替换成别的版本，目录名也自动正确。

### 3.4 在线安装（兜底，仅内置缺失时）

```javascript
// enhancements.mjs :: installFromOnline()
const doFetch = fetchImpl || globalThis.fetch          // 主进程注入 Electron net.fetch（走系统代理）
const base = process.env.AGENTFORGE_RG_DOWNLOAD_BASE || RG_RELEASE_BASE
const url = `${base}/${target.asset}`                  // 钉死版本，不取 latest

// ① 下载（60s 超时，防受限网络挂起）
response = await withTimeout(doFetch(url, { redirect: 'follow' }), 60_000)

// ② 校验 sha256（不符立即删除并报 CHECKSUM_MISMATCH）
if (sha256File(archive) !== target.sha256) { fs.rmSync(archive); throw ... }

// ③ 解压，只取 rg 一个文件（丢弃 doc/、complete/、README）
const found = findFileRecursive(extracted, RG_BIN_NAME)

// ④ 原子落盘 + 清理 quarantine
atomicCopy(found, destination)
spawnSync('xattr', ['-d', 'com.apple.quarantine', destination])

// ⑤ 写 manifest 记录 binSha256，供后续校验
```

**错误必须可区分**，否则用户只会看到"安装失败"：

| 错误码 | 含义 | 用户该做什么 |
| --- | --- | --- |
| `NETWORK_UNREACHABLE` | GitHub 不可达 / 超时 | 检查网络或代理；内网请用内置版本 |
| `CHECKSUM_MISMATCH` | 下载内容与预期不符 | 重试；反复失败可能有中间人 |

### 3.5 三个入口的差异化行为

| 场景 | mode | 会联网吗 | 会覆盖已有吗 |
| --- | --- | --- | --- |
| 启动静默初始化 | `bundled` | ❌ **绝对不**（冷启动禁止隐式网络） | 只补用户级目录 |
| 设置页「一键安装」 | `auto` | 仅在无内置时 | 不覆盖已就绪的用户级 |
| 设置页「一键升级」 | `upgrade` | 可能 | ✅ 允许覆盖 |

**启动时禁止联网**是刻意设计：用户双击打开应用时，不应因为一个可选的增强能力而卡在网络上。所以 `initEnhancements()` 用的是 `bundled` 模式，内置缺失就跳过，交由用户显式点击。

---

## 4. 核心机制：为什么"用自己的 shell"就能注入环境变量

这是整个方案的技术支点，需要讲透。

### 4.1 先明确一个事实：环境变量是进程私有的

环境变量**不属于"系统"，而属于每个进程**。子进程从父进程**复制一份**环境（fork 时继承），此后的修改**只影响自己**，不会回写父进程，也不会影响兄弟进程。

实测验证：

```bash
# 父进程（PATH 里没有 rg）
$ export PATH=/usr/bin:/bin
$ command -v rg || echo "父进程找不到"
父进程找不到

# 同一环境下，让包装脚本执行（它是子进程）
$ /tmp/flowdemo/root/tools/bin/agentforge-sh -lc 'command -v rg'
/tmp/flowdemo/root/tools/rg/15.2.0/rg        ← 子进程里找到了

# 关键：执行完之后，父进程的 PATH 没有任何变化
$ command -v rg || echo "父进程仍然找不到"
父进程仍然找不到
```

**这就是能生效的根本原因**：Agent 的每条命令都是通过 `ProcessBuilder` 新起的**子进程**，它天然会读取"被复制过去的环境"。我们在子进程内部改 PATH，就只对这条命令生效——**精准、无副作用、不污染用户系统**。

### 4.2 对比：为什么 `export PATH` 在父进程里做不到

Agent 命令的父进程是 **Java 后端 JVM**。要让注入生效，只有三种位置可选：

| 注入位置 | 可行性 | 原因 |
| --- | --- | --- |
| ① 用户自己的终端里 `export PATH` | ❌ | 影响的是用户 shell，与后端 JVM 无关 |
| ② 后端 JVM 启动时改 `env.PATH` | ⚠️ 部分 | 只对"Electron 自己启动的后端"生效；IDEA 启动的后端绕过此路径 |
| ③ **每次执行命令时用的 shell 里改** | ✅ | **不依赖谁启动的 JVM**，与启动方式完全解耦 |

本方案选 ③。

### 4.3 具体是怎么"钻进去"的

上游执行命令的代码：

```java
new ProcessBuilder(options.shell(), "-lc", command)
```

`options.shell()` 是我们可控的配置项。把它从 `/bin/sh` 换成 `agentforge-sh` 后：

```text
ProcessBuilder 启动子进程
  └─ 可执行文件 = agentforge-sh        ← 我们自己写的脚本
       ├─ 第 1~N 行：往自己的 PATH 里塞目录（此时是"脚本进程"，属子进程）
       ├─ export PATH                    ← 修改只作用于脚本进程及其后续 exec 的进程
       └─ exec /bin/sh -lc "rg ..."     ← 用新 PATH 转交
             └─ shell 解析 rg → 命中注入目录 → 执行成功
```

**为什么 `exec` 这一步很关键**：`exec` 是**替换**当前进程，不是新建。所以：

- 包装脚本进程"变身"成了真正的 `/bin/sh`，**PATH 修改被继承下去**；
- 调用方（JVM）看到的退出码、信号，都是**真实命令**的，不是外壳的。

若不用 `exec` 而是 `sh "$@"`，会多一层父子进程，信号传递与退出码可能失真（实测 `exec` 写法 `exit 42` → 退出码 42，正确）。

### 4.4 实测：脚本内 PATH 的真实样子

```bash
$ env -i PATH=/usr/bin:/bin /tmp/flowdemo/root/tools/bin/agentforge-sh -c 'echo $PATH'
```

输出（按冒号拆行）：

```text
1  /tmp/flowdemo/root/tools/rg/15.2.0      ← 用户级，最前（版本优先）
2  /tmp/flowdemo/bundled/rg/mac-arm64      ← 内置
3  /opt/homebrew/bin                       ← 系统
4  /usr/local/bin
...
```

对照：此时**父进程的 PATH 仍是 `/usr/bin:/bin`**，一个工具目录都没有。

### 4.5 一句话总结机制

> **环境变量是进程私有的；Agent 的每条命令都是一次新进程；把"改环境 + 执行"封进一个 shell 脚本，就能让每次命令都带着我们想要的 PATH，且不影响任何人。**

这也是为什么"改装一个 shell"比"改环境变量"更稳：**它把注入动作和命令执行绑定成原子的一步**，不存在"忘了改"或"改了又被覆盖"的时序问题。

### 4.6 端到端完整时序（一次安装到一次成功的 rg 调用）

下面这条时间线把前面所有环节串起来，可以对照代码逐段验证。

```text
【阶段一：安装】
 t1  用户点「一键安装」（或应用首次启动触发 initEnhancements）
      │
 t2  installRg() 先 detectRg() 判定：source != 'user' → 需要安装
      │
 t3  installFromBundled()
      ├─ 校验内置二进制 sha256（对比 manifest.binSha256）
      ├─ probeRg() 探测真实版本 → 得到 "16.0.0"
      └─ atomicCopy() → ~/.agentforge-desktop/tools/rg/16.0.0/rg（755）
      │
 t4  ensureShellWrapper()
      └─ 写 ~/.agentforge-desktop/tools/bin/agentforge-sh（755）
          内容里已包含 tools/rg/16.0.0 这一行（因为上一步刚建好目录）
      │
 t5  settings.json 写入 enhancements.rg 快照（供设置页秒开显示）
      │
 t6  返回 needsRestart: true → UI 提示「需重启本地服务后生效」

【阶段二：启动后端】
 t7  startBackend()
      ├─ shellWrapper = ensureShellWrapper()   ← 再次确保脚本存在且是最新的
      └─ spawn(java, [..., `--agentforge.desktop.shell=${shellWrapper}`])
      │
 t8  Spring Boot 绑定 → DesktopProperties.shell = "/Users/...../agentforge-sh"
      │
 t9  AgentRuntimeService.build()
      ├─ resolveShell() 校验：isRegularFile + isExecutable → 通过
      └─ LocalRuntimeOptions.builder().shell(<包装脚本>)
      │
 t10 LocalRuntimeOptions 保存 shell（上游零改动）

【阶段三：Agent 执行 rg】
 t11 模型调用 rg 工具 → LocalRuntimeTools.rg()
      └─ commands.execute("rg --line-number --no-heading -- '<pattern>' '.'")
      │
 t12 CommandExecutor.execute()
      └─ new ProcessBuilder(<agentforge-sh>, "-lc", <命令>).start()
            ↑ 新进程，继承 JVM 环境（PATH 里没有 rg）
      │
 t13 agentforge-sh 运行：
      ├─ AGENTFORGE_EXTRA_PATH 逐段累加 → "/Users/....tools/rg/16.0.0:/opt/homebrew/bin:..."
      ├─ PATH = EXTRA + 原 PATH
      └─ exec /bin/sh -lc "<命令>"
      │
 t14 /bin/sh 用新 PATH 解析 rg
      └─ 命中 /Users/...../tools/rg/16.0.0/rg（最前，版本优先）
      │
 t15 返回 exitCode=0 + 匹配行  →  模型拿到检索结果
```

**关键观察点**：t12 时新进程的 PATH **仍然没有 rg**（继承自 JVM），但 t13 在**同一个新进程内**改写了 PATH，t14 才解析成功。**这就是全部秘密**。

---


---

## 5. 包装脚本如何生成（第 ① 步详解）

### 5.1 实现位置

```javascript
// agentforge-desktop-ui/electron/enhancements.mjs
export function ensureShellWrapper({ rootDir, bundledToolsDir, target = resolveTarget() }) {
  if (IS_WINDOWS) return ''                       // Windows 无 /bin/sh，不走此路径
  const binDir = path.join(rootDir, 'tools', 'bin')
  const wrapper = path.join(binDir, 'agentforge-sh')
  // ... 见 §3.3
}
```

### 5.2 目录优先级（与检测链保持一致）

脚本里收集目录的顺序是**有序的**，靠前的优先：

| 序 | 来源 | 依据 |
| --- | --- | --- |
| 1 | 用户级工具目录 `tools/rg/<版本>/` | 本应用管理，版本可控 → 最高优先 |
| 2 | 内置工具目录 `<resources>/runtime/tools/rg/<平台>/` | 随包分发，版本确定 |
| 3 | `/opt/homebrew/bin`、`/usr/local/bin` | 系统常见安装位置 |
| 4 | `~/.cargo/bin` | Rust 工具链安装位置 |

**只写入真实存在的目录**（`fs.existsSync` 过滤），避免污染 PATH。

### 5.3 成品示范（可直接对照）

假设：macOS arm64，用户级已装 `16.0.0`，系统有 Homebrew。

**输入**

```javascript
ensureShellWrapper({
  rootDir: '/Users/edy/.agentforge-desktop',
  bundledToolsDir: '/Applications/AgentForge Desktop.app/Contents/Resources/runtime/tools'
})
```

**输出路径**

```text
/Users/edy/.agentforge-desktop/tools/bin/agentforge-sh        （权限 755）
```

**生成内容**

```sh
#!/bin/sh
# AgentForge Desktop 自动生成：为 Agent 工具注入能力目录，请勿手工编辑。
AGENTFORGE_EXTRA_PATH=""
[ -d '/Users/edy/.agentforge-desktop/tools/rg/16.0.0' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'/Users/edy/.agentforge-desktop/tools/rg/16.0.0:'
[ -d '/opt/homebrew/bin' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'/opt/homebrew/bin:'
[ -d '/usr/local/bin' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'/usr/local/bin:'
[ -d '/Users/edy/.cargo/bin' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'/Users/edy/.cargo/bin:'
[ -n "$AGENTFORGE_EXTRA_PATH" ] && PATH="$AGENTFORGE_EXTRA_PATH$PATH"
export PATH
exec /bin/sh "$@"
```

**实测生成（本机真实环境，内置目录不存在时的实际产物）**

```sh
#!/bin/sh
# AgentForge Desktop 自动生成：为 Agent 工具注入能力目录，请勿手工编辑。
AGENTFORGE_EXTRA_PATH=""
[ -d '/opt/homebrew/bin' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'/opt/homebrew/bin:'
[ -d '/usr/local/bin' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'/usr/local/bin:'
[ -d '/Users/edy/.cargo/bin' ] && AGENTFORGE_EXTRA_PATH="$AGENTFORGE_EXTRA_PATH"'/Users/edy/.cargo/bin:'
[ -n "$AGENTFORGE_EXTRA_PATH" ] && PATH="$AGENTFORGE_EXTRA_PATH$PATH"
export PATH
exec /bin/sh "$@"
```

> 用户级目录未命中时（还没安装），这一行不会出现——脚本是**按实际情况动态生成**的，不是模板套用。

### 5.4 三个关键设计细节

**① 先累加、最后一次性前置（顺序正确性）**

```sh
# ❌ 错误写法：边循环边前置 → 后加入的反而在最前，顺序颠倒
for d in a b c; do PATH="$d:$PATH"; done     # 结果：c:b:a

# ✅ 正确写法：先拼 EXTRA，最后一次前置
AGENTFORGE_EXTRA_PATH=""                      # 逐段拼成 a:b:c:
PATH="$AGENTFORGE_EXTRA_PATH$PATH"            # 结果：a:b:c  ← 第一个目录真的在最前
```

这保证了**用户级 16.0.0 优先于系统的 15.2.0**。

**② 用 `exec` 而非直接调用（信号与退出码透传）**

```sh
exec /bin/sh "$@"     # 替换当前进程，退出码/信号都是真实命令的
```

实测：`agentforge-sh -lc 'exit 42'` → **退出码 42**（若不用 `exec`，会被外壳吞掉）。

**③ 内容比对后按需写入（幂等）**

```javascript
if (!fs.existsSync(wrapper) || fs.readFileSync(wrapper, 'utf8') !== content) {
  fs.writeFileSync(wrapper, content, 'utf8')
}
fs.chmodSync(wrapper, 0o755)
```

内容没变就不重写，避免无意义的磁盘写与 mtime 抖动。

### 5.5 转义处理

路径含空格或单引号时（如 `/Users/edy/我的 项目/`），用 `shellQuote()` 转义：

```javascript
function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`
}
```

例如 `it's` → `'it'\''s'`，符合 POSIX 单引号转义规则。

---

## 6. 传参与生效链路（第 ②~⑦ 步详解）

### 6.1 ② Electron 传参

```javascript
// main.mjs :: startBackend()
const shellWrapper = process.platform === 'win32' ? '' : ensureShellWrapper(enhancementContext())

const args = [
  '-Xms128m',
  `-Xmx${process.env.AGENTFORGE_DESKTOP_JAVA_XMX || '1536m'}`,
  '-Dfile.encoding=UTF-8',
  '-jar', jar,
  '--server.address=127.0.0.1',
  `--server.port=${port}`,
  `--agentforge.desktop.token=${token}`,
  `--agentforge.desktop.root-dir=${rootDir}`,
  ...(shellWrapper ? [`--agentforge.desktop.shell=${shellWrapper}`] : [])   // ← 关键
]
```

### 6.2 ③ Spring Boot 绑定

```java
// DesktopProperties.java
@Component
@ConfigurationProperties(prefix = "agentforge.desktop")
public class DesktopProperties {
    private String shell = "";        // --agentforge.desktop.shell 自动绑定到这里
    public String getShell() { return shell; }
    public void setShell(String shell) { this.shell = shell; }
}
```

### 6.3 ④ 校验与安全回退

```java
// AgentRuntimeService.java
private String resolveShell() {
    String shell = properties.getShell();
    if (shell == null || shell.trim().isEmpty()) {
        return "/bin/sh";                                   // 未配置
    }
    Path path = Paths.get(shell.trim());
    if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
        return "/bin/sh";                                   // 文件缺失或无执行权限
    }
    return path.toString();
}
```

**为什么必须回退**：脚本可能被用户误删、被清理工具扫掉、权限被改。若这里直接抛异常，**整个 Agent 命令链路都会不可用**（连 `bash` 工具也废掉）。回退到 `/bin/sh` 是"功能退化但不断"，符合可用性优先。

> 注意：回退后 `rg` 又会不可用（回到原问题），但**其它命令仍能跑**。设置页的 `effective` 字段会如实显示「待修复」。

### 6.4 ⑤⑥ 传入运行时并保存

```java
// AgentRuntimeService.java:246-254
LocalRuntime runtime = LocalRuntime.builder()
        .options(LocalRuntimeOptions.builder()
                .commandTimeoutMillis(5 * 60 * 1000)
                .shell(resolveShell())                    // ← 注入点
                .build())
        .workspace(workspace).build();
```

```java
// 上游 LocalRuntimeOptions.java:14（未改动）
this.shell = isBlank(builder.shell) ? "/bin/sh" : builder.shell;
```

**上游零改动**：`LocalRuntimeOptions` 本来就有 `shell` 配置项，只是此前没人设置过。

### 6.5 ⑦ 执行时生效

```java
// 上游 CommandExecutor.java:36
new ProcessBuilder(options.shell(), "-lc", command)
```

实际发生的等价过程：

```text
ProcessBuilder('/Users/edy/.agentforge-desktop/tools/bin/agentforge-sh', '-lc', 'rg --line-number ...')
        ↓
agentforge-sh 注入 PATH（把工具目录放到最前）
        ↓
exec /bin/sh -lc 'rg --line-number ...'
        ↓
shell 用新 PATH 解析 rg → 命中用户级 16.0.0 → 执行成功
```

---

## 7. 实测证据

### 7.1 注入前后对比（干净环境，模拟 IDEA）

```bash
# 未注入
$ env -i HOME=/tmp/wdemo PATH=/usr/bin:/bin /bin/sh -lc 'command -v rg || echo NOT_FOUND'
NOT_FOUND

# 注入后（用包装脚本当 shell）
$ env -i HOME=/tmp/wdemo PATH=/usr/bin:/bin /tmp/wdemo/tools/bin/agentforge-sh -lc 'command -v rg'
/tmp/wdemo/tools/rg/16.0.0/rg
```

### 7.2 复刻 Agent 真实调用

```bash
$ cmd="rg --line-number --no-heading -- 'hello' '.'"
$ echo 'class A { void hello(){} }' > a.java

# 用包装脚本（= 修复后）：exitCode=0
$ env -i HOME=/tmp/wdemo PATH=/usr/bin:/bin /tmp/wdemo/tools/bin/agentforge-sh -lc "$cmd"
./a.java:1:class A { void hello(){} }
# exitCode = 0

# 用 /bin/sh（= 修复前）：exitCode=127
$ env -i HOME=/tmp/wdemo PATH=/usr/bin:/bin /bin/sh -lc "$cmd"
/bin/sh: rg: command not found
# exitCode = 127
```

### 7.3 exec 保留退出码

```bash
$ env -i HOME=/tmp/wdemo PATH=/usr/bin:/bin /tmp/wdemo/tools/bin/agentforge-sh -lc 'exit 42'
$ echo $?
42
```

### 7.4 真实 Electron 端到端

环境：`PATH` 故意设为干净的 `/usr/bin:/bin`（模拟 IDEA 侧环境）

```text
wrapper            存在 = true
ash detect         status=ready, version=15.2.0, effective=true
repair             shellOk=true, shellPath=/opt/homebrew/bin/rg
list               rg:ready:effective=true
```

---

## 8. 检测口径必须与执行口径一致

这是落地过程中踩到的**连带缺陷**，必须记录。

### 8.1 问题

最初 `resolveInShell()` 固定用 `/bin/sh` 检测，而**实际生效的是包装脚本**。结果：修复成功后仍显示"未修复"（因为用错误的 shell 去问）。

```javascript
// ❌ 修复前的错误口径
resolveInShell({})                        // 固定 /bin/sh

// ✅ 修复后：按 rootDir 推断实际生效的 shell
resolveInShell({ rootDir: root })         // 有包装脚本就用它
```

```javascript
export function defaultShellFor(rootDir) {
  const wrapper = path.join(rootDir, 'tools', 'bin', 'agentforge-sh')
  return fs.existsSync(wrapper) ? wrapper : '/bin/sh'
}
```

**原则**：**用什么 shell 执行，就用什么 shell 检测**。否则"显示"与"现实"会脱节。

### 8.2 `effective` 字段

检测结果区分"文件存在"与"Agent 真能用"：

| 字段 | 含义 | UI 表现 |
| --- | --- | --- |
| `status: 'ready'` | 找到可执行的 rg 文件 | 基础状态 |
| `effective: true` | Agent 的 shell 里**解析得到** | ✅ 已支持 |
| `effective: false` | 文件在，但 shell 里**解析不到** | ⚠️ 待修复 +「修复 PATH」按钮 |

这保证了**UI 上每个 ✅ 都代表 Agent 真的能用**。

---

## 9. 已知脆弱点（实测发现，需留意）

### 9.1 登录 shell 的 `path_helper` 会重排 PATH

macOS 的 `/etc/profile` 调用 `/usr/libexec/path_helper`，`zsh`/`bash` 的启动脚本也可能重建 PATH，**把注入的目录挤到后面**。

实测（`-lc` 登录 shell）：

```text
最终 PATH 顺序：
  ...（系统目录）
  13  /tmp/wdemo2/tools/rg/16.0.0     ← 我们注入的，被推到第 13 位
  14  /opt/homebrew/bin                ← 系统 Homebrew
```

**危害评估**：注入目录虽然被推后，但**仍然排在 `/opt/homebrew/bin` 之前**，所以版本优先级**依然正确**（用户级 16.0.0 优先于系统 15.2.0）。实测 `command -v rg` 命中的仍是用户级路径。

**但这依赖一个前提**：注入目录被追加到 PATH 时，系统中没有更靠前的同名额目录。若用户在 `/usr/local/bin` 也装了 rg，而 `path_helper` 把 `/usr/local/bin` 排在注入目录前，就可能命中系统版本。

**现有缓解**：检测时用**与执行相同的 shell**（§6.1），所以出现不一致时 `effective` 会如实反映，UI 显示「待修复」，不会出现"显示 ✅ 但实际用错版本"。

**未来可选加固**（未实施）：
- 在包装脚本中改用 `-c` 而非依赖登录 shell 的 profile；
- 或在脚本里重新前置一次（在 `exec` 之前再断言顺序）。

### 9.2 多版本并存

`tools/rg/` 下可能有多个版本目录（如 `15.2.0`、`16.0.0`）。脚本按 `userRgCandidates()` 的排序（版本从高到低）取第一个，因此**高版本优先**。旧版本目录不会自动清理（方案文档 §10 已列为待优化项）。

---

## 10. 平台限制：Windows 不做此注入

```javascript
if (IS_WINDOWS) return ''      // 直接返回空，不生成脚本
```

原因：

1. Windows 没有 `/bin/sh`，而包装脚本是 sh 语法；
2. 上游 `CommandExecutor` 的 `-lc` 参数是 POSIX shell 的写法，`cmd.exe` 需要 `/c`、PowerShell 需要 `-Command`。

**因此 Windows 上"装好 rg"只是必要条件，不充分。** 要让 Windows 可用，需要在上游改 shell 类型适配（`LocalRuntimeOptions` 暴露 shell 类型或 args prefix），属于**跨仓改动**，方案文档 §7.2 已标注为独立立项项，本文不涉及。

---

## 11. 排查手册

遇到"设置页说能用但 Agent 报 command not found"时，按序执行：

```bash
# 1) 包装脚本是否存在且可执行
ls -l ~/.agentforge-desktop/tools/bin/agentforge-sh
# 期望：-rwxr-xr-x

# 2) 脚本内容是否符合预期（看注入的目录是否对）
cat ~/.agentforge-desktop/tools/bin/agentforge-sh

# 3) 用脚本能否解析到 rg
~/.agentforge-desktop/tools/bin/agentforge-sh -lc 'command -v rg; rg --version | head -1'

# 4) 后端进程实际用的 shell（看启动参数）
ps -ax -o pid=,command= | grep agentforge-desktop-server | tr ' ' '\n' | grep shell=

# 5) 后端进程的 PATH（含 rg 目录吗）
ps eww -p <PID> | tr ' ' '\n' | grep '^PATH='

# 6) 若第 4 步没有 --agentforge.desktop.shell，说明后端是旧代码或外部启动
#    重启后端使配置生效
```

**最常见原因**：后端是**改动前**启动的进程，尚未重启加载新配置。重启即可。

---

## 12. 关键代码索引

| 环节 | 文件 | 位置 |
| --- | --- | --- |
| 生成包装脚本 | `agentforge-desktop-ui/electron/enhancements.mjs` | `ensureShellWrapper()` |
| 路径转义 | 同上 | `shellQuote()` |
| 实际 shell 推断 | 同上 | `defaultShellFor()` |
| shell 内探测 | 同上 | `resolveInShell()` |
| 传参给后端 | `agentforge-desktop-ui/electron/main.mjs` | `startBackend()` 第 192/202 行 |
| 配置属性 | `desktop-server/.../config/DesktopProperties.java` | `private String shell` |
| 校验与回退 | `desktop-server/.../AgentRuntimeService.java` | `resolveShell()` |
| 传入运行时 | 同上 | 第 246–254 行 |
| 上游保存 | `AgentForge/.../LocalRuntimeOptions.java` | 第 14 行 |
| 上游执行 | `AgentForge/.../CommandExecutor.java` | 第 36 行 |

---

## 13. 设计取舍小结

| 方案 | IDEA 启动后端时 | 需改上游核心 | 采纳 |
| --- | --- | --- | --- |
| `spawn` 时注入 `env.PATH` | ❌ 代码不执行 | 否 | 保留（Electron 启动场景） |
| 改 `ProcessBuilder.environment()` | ✅ | ❌ 需跨仓评审 | 否 |
| 要求用户改 IDEA 启动配置 | ❌ 推给用户 | 否 | 否 |
| **传一个自带 PATH 的 shell** | ✅ | ✅ 零改动 | **✅ 主方案** |

选择它的根本理由：**注入点从"环境变量"转移到"可执行文件本身"**。脚本路径固定（`tools/bin/agentforge-sh`），只要它存在，任何进程都能用它，**与启动方式解耦**。