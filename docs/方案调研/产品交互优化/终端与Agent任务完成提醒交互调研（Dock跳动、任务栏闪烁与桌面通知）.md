# 终端与 Agent 任务完成提醒交互调研（Dock 跳动、任务栏闪烁与桌面通知）

> 调研对象：Codex CLI 在终端里的「任务完成提醒」交互，以及 Electron 桌面端的等价实现
>
> 调研时间：2026-11-24
>
> 目标：解释「我在 shell 里跑 Codex，任务完成后终端为什么会跳/会弹通知」，并把同一套效果在 AgentForge Desktop（Electron 31.7.7）里用**原生 API**落地，全程不依赖 AI 模型参与。
>
> 证据等级约定：
> - **【一手】** 官方文档原文、官方仓库源码、本机二进制字面量、本机实测输出
> - **【社区】** 第三方文章/教程，未经源码或实测确认
> - **【推断】** 基于工程经验的实现建议，不代表任何产品的真实实现

---

## 1. 先给结论

这套提醒能力可以拆成三层，**只有最上层的"要不要提醒"需要业务判断，下面两层全是操作系统与终端能力，不需要任何模型参与**：

```text
第一层：任务生命周期事件        谁完成了？成功/失败/等待审批？
        （Shell 进程退出码、Agent 运行时事件、终端 BEL/OSC）
                    │
                    ▼
第二层：应用内提醒策略            用户在看这个会话吗？要不要打扰？
        （聚焦判断、去重、限流、事件->提醒等级映射）
                    │
                    ▼
第三层：操作系统原生注意力机制    Dock 跳动 / 任务栏闪烁 / 通知 / 角标
        （NSApp requestUserAttention / FlashWindowEx / UNNotification）
```

三条关键结论：

1. **Codex CLI 并没有"操作 Dock"的能力**，它只往终端写控制字符（BEL `\x07` 或 OSC 9 转义序列）。**真正让图标跳的是终端模拟器**——它读到这些字符后，自己调用系统通知或请求注意力接口。【一手：Codex 官方配置参考 + 本机二进制】
2. **Electron 在 macOS 上并不需要 BEL/OSC 这条路**，它已经把系统注意力机制封装成 `app.dock.bounce()` 与 `win.flashFrame()`。而且 `flashFrame` 在 macOS 上的底层实现是 `[NSApp requestUserAttention:NSCriticalRequest]`，**和 `dock.bounce('critical')` 是同一个系统调用**，只是封装层级不同。【一手：Electron 源码 + 本机实测】
3. **提醒效果能不能被你看到，取决于"终端/应用自己的配置"，而不是写代码的那一方**。本次调研发现了一个非常典型的反例：本机 `Terminal.app` 的配置里 `Bell = 0`（响铃关闭），在这种配置下 BEL 会被静默丢弃，用户什么也看不到。【一手：本机 `defaults read com.apple.Terminal`】

所以对 AgentForge Desktop 的建议是：

> **不要照抄终端的 BEL/OSC 协议，直接走「运行时事件 → Electron 主进程 NotificationManager → 系统原生 API」这条链路；BEL/OSC 只作为"兼容第三方 CLI"的补充解析通道。**

---

## 2. 调研边界：哪些是事实，哪些是推断

| 待验证说法 | 结论 | 证据 |
| --- | --- | --- |
| Codex 有 `tui.notifications` / `notification_method` / `notification_condition` 三个配置项 | ✅ 官方确有 | 【一手】OpenAI 官方配置参考原文 |
| `notification_method` 取值是 `auto / osc9 / bel` | ✅ 正确 | 【一手】官方文档 + 本机二进制 `save-allNotificationMethodosc9bel` |
| `notification_condition` 取值是 `unfocused / always`，默认 `unfocused` | ✅ 正确 | 【一手】官方文档 + 本机二进制 `NotificationConditionunfocusedalways` |
| Codex 支持外部 `notify` 程序接收 JSON 载荷 | ✅ 官方确有 | 【一手】官方文档 `notify array<string>` |
| `notify` 载荷字段含 `thread-id / turn-id / cwd / input-messages / last-assistant-message` | ✅ 与官方一致 | 【一手】本机二进制字符串序列 |
| Codex 的 TUI 通知事件只有 `agent-turn-complete` | ✅ 与官方文档描述一致 | 【一手】本机二进制 + 官方 `notify` 说明 |
| Codex 还支持 `approval-requested` 作为 TUI 通知事件 | ⚠️ 社区资料提到，本次未在一手源码确认 | 【社区】第三方教程；本机二进制中未检到该字面量 |
| `app.dock.bounce('informational')` 只跳 1 秒，`critical` 跳到应用激活为止 | ✅ 官方文档原文如此 | 【一手】Electron `docs/api/dock.md` |
| 应用处于聚焦状态时 `dock.bounce()` 返回 `-1` | ⚠️ 官方文档如此声明，本机未复现（测试窗口未成为真正前台应用，返回 0/1） | 【一手】文档 + 【实测】偏差说明见 §4.6 |
| `app.setBadgeCount()` / `app.getBadgeCount()` 是跨平台能力 | ❌ **只在 macOS / Linux 存在**，Windows 无此能力（应改用 `win.setOverlayIcon`） | 【一手】`electron.d.ts` 的 `@platform linux,darwin` 标注 |
| `win.flashFrame(true)` 在 macOS 上是「informational 级别」 | ❌ **不成立**，源码里是 `NSCriticalRequest`（critical 级别，持续跳） | 【一手】`native_window_mac.mm` 源码 |

> ⚠️ 关于最后一条：网上大量教程（包括最初的需求描述）把 `dock.bounce()` 与 `flashFrame()` 当成两个不同强度的能力。**在 macOS 上它们其实是同一个系统调用的两个入口**，`flashFrame(true)` 的强度是 critical，不能用来做"轻提醒"。

---

## 3. Codex CLI 到底是怎么提醒你的

### 3.1 官方配置项（原文摘录）

OpenAI 官方 Codex 配置参考里，与通知直接相关的三行原文如下【一手】：

| 配置项 | 类型 | 官方说明（节选） |
| --- | --- | --- |
| `tui.notifications` | `boolean \| array<string>` | "Enable TUI notifications; optionally restrict to specific event types." |
| `tui.notification_method` | `auto \| osc9 \| bel` | "Notification method for terminal notifications (default: auto)." |
| `tui.notification_condition` | `unfocused \| always` | "Control whether TUI notifications fire only when the terminal is unfocused or regardless of focus. Defaults to unfocused." |
| `notify` | `array<string>` | "Command invoked for notifications; receives a JSON payload from Codex." |

对应到 `~/.codex/config.toml` 的写法：

```toml
[tui]
notifications = true              # 或 ["agent-turn-complete"] 只订阅部分事件
notification_method = "auto"      # auto 优先 osc9，失败回退 bel
notification_condition = "unfocused"

# 与 TUI 通知相互独立的第二条通道：执行外部程序
notify = ["/usr/bin/python3", "/Users/me/.codex/notify.py"]
```

**这里有一个容易被忽略的设计点：`notify` 与 TUI 通知是两条互不相干的通道。** `notify` 走的是外部进程 + JSON 载荷，可以接任意桌面通知、IM 机器人、语音播报；`tui.notifications` 走的是终端控制字符，只能影响终端自身表现。

### 3.2 本机二进制的字面量证据

除了文档，本次直接对用户本机的 Codex 二进制做了字符串取证（`/Applications/ChatGPT.app/Contents/Resources/codex`），拿到以下字面量【一手】：

```text
save-allNotificationMethodosc9bel            # NotificationMethod 的枚举取值
NotificationConditionunfocusedalways          # NotificationCondition 的枚举取值
TuiNotificationSettingsnotificationsnotification_methodnotification_condition
agent-turn-complete                           # TUI/notify 事件名
thread-id turn-id cwd client input-messages last-assistant-message   # notify 载荷字段
legacy_notify                                 # 旧版 notify 通道
codex_tui::notifications::osc9::PostNotification                     # OSC 9 实现模块
```

这几条字面量同时验证了三件事：**枚举取值正确、事件名正确、OSC 9 确实是 TUI 内的独立模块实现**。

### 3.3 用户本机 Codex 的实际配置

```toml
# ~/.codex/config.toml（节选）
notify = ["/Users/edy/.codex/computer-use/Codex Computer Use.app/.../SkyComputerUseClient", "turn-ended"]

[tui]
screen_reader_detection_done = true
```

可以看到：**用户本机的 Codex 没有开启 `tui.notifications`**，只配了 `notify` 外部程序（给 Computer Use 用的）。这解释了为什么"任务完成后不一定有终端提醒"——`tui.notifications` 未显式开启时行为取决于版本默认值，而 `notify` 通道并不会让终端图标跳动。

建议的自测开关（仅本地试验，不影响项目代码）：

```toml
[tui]
notifications = true
notification_method = "auto"
notification_condition = "unfocused"
```

### 3.4 终端层协议：BEL 与 OSC 9 到底是什么

**BEL（`\x07`）**

一个 ASCII 控制字符，含义就是"响铃"。它没有任何参数，也不携带消息文本，所以终端只能用它触发"提醒一下"，无法表达"哪个任务完成了"。

```bash
# 5 秒后响铃：切到别的应用观察终端图标是否跳动
sleep 5 && printf '\a'
```

**OSC 9（`ESC ] 9 ; <文本> ST`）**

OSC = Operating System Command，是 ANSI 转义序列的一族。OSC 9 的语义是"给用户发一条桌面通知，内容是这段文本"【一手：iTerm2 官方 Escape Codes 与 Ghostty 官方 VT 文档】：

```bash
# 5 秒后弹出带文本的桌面通知
sleep 5 && printf '\033]9;构建任务执行完成\007'
```

iTerm2 的官方文档原文：`Post a notification` → `OSC 9 ; [Message content goes here] ST`；Ghostty 的官方文档也写明 `OSC 9` 为 "Show a desktop notification"，并注明该序列 "Originated from iTerm since before 2010"。

> 💡 顺带一个冷知识：iTerm2 还复用了 OSC 9 做**进度条**——`OSC 9 ; 4 ; <state> ; <percent> ST`。所以如果通知文本以"数字 + 分号"开头，会与 ConEmu 扩展冲突，这也是 Ghostty 文档特意提醒"标题不要以数字加 ; 开头"的原因。

**OSC 133（语义提示符）**

`OSC 133` 是 shell 与终端约定的"命令区域标记"，让终端知道哪一段是提示符、哪一段是命令、哪一段是输出、命令退出码是多少【一手：WezTerm 官方 shell integration 文档 + 其 shell 脚本源码】。WezTerm 的 bash 集成脚本里可以直接看到写法：

```bash
# WezTerm 官方 assets/shell-integration/wezterm.sh 片段
PS1=$'\e]133;P;k=i\a'$PS1$'\e]133;B\a'
```

标准语义大致是 `A`=提示符开始、`B`=提示符结束、`C`=命令开始执行、`D;<exitcode>`=执行结束并带退出码。VS Code 另有一套私有版本 `OSC 633`（`A/B/C/D [; <exitcode>]`），官方文档明确说明"其他终端应当忽略这些序列"【一手：VS Code Shell Integration 文档】。

**这条协议才是"精确判断命令执行完成"的正解**：BEL 只是"我要响铃"，OSC 133 D 才带退出码。

### 3.5 各终端对这几条协议的支持情况

| 终端 | BEL 表现 | OSC 9 桌面通知 | OSC 133 | 说明 |
| --- | --- | --- | --- | --- |
| iTerm2 | 可配置（响铃/视觉/忽略） | ✅ 原生支持 | ✅ 支持 shell integration | OSC 9 是 iTerm2 的源头实现 |
| Ghostty | 可配置 | ✅ 官方 VT 文档明确支持 | ✅ 支持 | 文档提醒注意与 ConEmu 冲突 |
| Windows Terminal | `bellStyle` 默认 `audible`；`all` 会**同时响铃 + 闪任务栏图标 + 闪窗口** | ❌（不在官方 schema 中） | ⚠️ 未在本次核实 | `bellStyle` 支持数组 `audible/window/taskbar`【一手：官方 `profiles.schema.json`】 |
| WezTerm | 可配置 | ⚠️ 未在本次核实 | ✅ 原生支持并用它做 shell integration | — |
| VS Code 集成终端 | 可配置 + 命令失败音频提示 | ⚠️ 未核实 | 用私有 `OSC 633` | 官方文档明确"建议其他终端忽略" |
| macOS `Terminal.app` | **取决于配置，本机为关闭** | ❌ 不支持 | ⚠️ 未核实 | 见 §3.6 |

### 3.6 一个非常关键的反例：本机 Terminal.app 的响铃是关的

本次直接读取了用户本机的 Terminal.app 配置【一手】：

```bash
$ defaults read com.apple.Terminal "Window Settings" | grep -A1 'Basic ='
Basic =     {
        Bell = 0;          # 响铃关闭
```

这个 `Bell = 0` 说明：**在用户当前的默认终端配置下，Codex 输出 BEL 大概率不会产生任何可见/可听效果**。这也解释了"为什么我有时感觉有提醒、有时完全没有"——提醒是否可见，第一道开关握在终端手里，而不是 CLI 手里。

> 排查建议：如果你希望先不改代码就能体验，"换一个对 BEL/OSC 9 支持更积极的终端"或"在终端设置里打开响铃/视觉提示"通常比调 CLI 配置更有效。

---

## 4. Electron 侧：真实实现依据

本节是本次调研最硬的部分：**不只看 API 文档，而是直接读 Electron 源码，并在本机跑一遍验证**。

### 4.1 三个可用的原生能力

| 能力 | API | 平台 | 底层 |
| --- | --- | --- | --- |
| Dock 图标跳动 | `app.dock.bounce(type)` / `app.dock.cancelBounce(id)` | macOS | `NSApp -requestUserAttention:` |
| 窗口/任务栏闪烁 | `win.flashFrame(flag)` | 全平台 | macOS 同上；Windows 为 `FlashWindowEx` |
| 桌面通知 | `new Notification({...})` | 全平台 | macOS `UNNotification` |
| 数字角标 | `app.setBadgeCount(n)` / `app.dock.setBadge(text)` | **仅 macOS / Linux**（Windows 无此 API） | `DockSetBadgeText` |
| 任务栏覆盖图标 | `win.setOverlayIcon(img, desc)` | Windows | — |
| 任务栏进度条 | `win.setProgressBar(p)` | Windows / macOS / Linux | — |

> ⚠️ 注意签名：**`app.dock` 是属性（`Dock` 实例），不是方法**。`app.dock()` 是错误写法，本次已在项目依赖的 `electron 31.7.7` 类型定义中确认：`class Dock { bounce(type?: 'critical' | 'informational'): number; ... }`。

### 4.2 源码级依据一：macOS 的 `flashFrame` 就是 critical 级 Dock 跳动

Electron 仓库 `shell/browser/native_window_mac.mm`【一手】：

```objc
void NativeWindowMac::FlashFrame(bool flash) {
  if (flash) {
    attention_request_id_ = [NSApp requestUserAttention:NSCriticalRequest];
  } else {
    [NSApp cancelUserAttentionRequest:attention_request_id_];
    attention_request_id_ = 0;
  }
}
```

**这条源码直接推翻了一个常见误解**：

- `win.flashFrame(true)` 在 macOS 上**不是**"informational 级别的一次性跳动"，而是 `NSCriticalRequest`——**跳到应用被激活或显式取消为止**。
- 想要"轻轻跳一下就好"，在 macOS 上只能用 `app.dock.bounce('informational')`；`flashFrame` 属于"持续催促"级别。
- 对应地，`flashFrame(false)` 内部会 `cancelUserAttentionRequest`，所以它天然适合"用户回到窗口就停止提醒"的语义。

### 4.3 源码级依据二：Windows 的 `flashFrame` 走 `FlashWindowEx`

Electron 仓库 `shell/browser/native_window_views.cc`【一手】：

```cpp
void NativeWindowViews::FlashFrame(bool flash) {
#if BUILDFLAG(IS_WIN)
  // The Chromium's implementation has a bug stopping flash.
  if (!flash) {
    FLASHWINFO fwi;
    fwi.cbSize = sizeof(fwi);
    fwi.hwnd = GetAcceleratedWidget();
    fwi.dwFlags = FLASHW_STOP;
    fwi.uCount = 0;
    FlashWindowEx(&fwi);
    return;
  }
#endif
  widget()->FlashFrame(flash);
}
```

两个工程细节值得记住：

1. Windows 上 **停止闪烁是 Electron 自己用 `FlashWindowEx(FLASHW_STOP)` 兜底的**，因为上游 Chromium 实现"停不下来"存在缺陷（源码注释原文：*"The Chromium's implementation has a bug stopping flash."*）。
2. 这也解释了为什么 Windows 上"闪了不停"是历史高频问题——**必须显式调用一次 `flashFrame(false)`**，不能指望聚焦自动停止（虽然实践中窗口获得焦点时系统通常会停）。

### 4.4 源码级依据三：Dock 角标走的同一条 macOS 通道

Electron 仓库 `shell/browser/browser_mac.mm`【一手】：

```objc
bool Browser::SetBadgeCount(std::optional<int> count) {
  DockSetBadgeText(!count.has_value() || count.value() != 0
                       ? badging::BadgeManager::GetBadgeString(count)
                       : "");
  ...
  return true;
}
```

也就是说 `app.setBadgeCount()` 与 `app.dock.setBadge()` 最终都落到 `DockSetBadgeText`，**是两个入口、一条通道**。

### 4.5 官方文档对两个 bounce 类型的定义

Electron `docs/api/dock.md`【一手】原文：

```text
#### dock.bounce([type]) _macOS_
* type string (optional) - Can be critical or informational. The default is informational
Returns Integer - an ID representing the request.

When critical is passed, the dock icon will bounce until either the
application becomes active or the request is canceled.

When informational is passed, the dock icon will bounce for one second.
However, the request remains active until either the application becomes active
or the request is canceled.

NOTE: This method can only be used while the app is not focused; when the app is
focused it will return -1.
```

三个必须记住的点：

1. 返回的是 **request ID（Integer）**，不是布尔值——要取消必须拿这个 ID 调 `cancelBounce(id)`。
2. `informational` 虽然"只跳 1 秒"，但**请求本身会一直挂起到应用激活**，语义上仍然是"未读提醒"。
3. 只在**应用未聚焦**时才有效，聚焦时返回 `-1`（官方明确声明）。

### 4.6 本机实测：Electron 31.7.7 真实返回值

用项目自己依赖的 Electron 跑了一个最小探针（不修改项目任何代码）：

```javascript
// /tmp/afnotify/main.js（节选，完整脚本见附录）
const { app, BrowserWindow, Notification } = require('electron')

app.whenReady().then(() => {
  const win = new BrowserWindow({ show: false, width: 800, height: 600 })
  const idInfo = app.dock.bounce('informational')
  const idCrit = app.dock.bounce('critical')
  win.flashFrame(true); win.flashFrame(false)
  app.setBadgeCount(3)
  const n = new Notification({ title: 'AgentForge 验证', body: '能力探针' })
  n.on('show', () => {}); n.on('failed', (_e, err) => {})
  n.show()
})
```

执行与输出【一手：本机实测】：

```bash
$ export PATH="$HOME/.nvm/versions/node/v22.16.0/bin:$PATH"
$ ./node_modules/.bin/electron /tmp/afnotify/main.js
[TEST] platform darwin electron 31.7.7
[TEST] win.isFocused false
[TEST] dock.bounce(informational) -> 0
[TEST] dock.bounce(critical) -> 1
[TEST] cancelBounce ok 1
[TEST] flashFrame(true) called; win.isFocused= false
[TEST] setBadgeCount(3) -> true getBadge= 3
[TEST] Notification.isSupported() -> true
[TEST] notification: show
```

**测试结论（含一处与文档的偏差）**：

| 验证项 | 实测结果 | 与官方文档是否一致 |
| --- | --- | --- |
| `dock.bounce('informational')` 返回正数 ID | 返回 `0` | ✅（ID 从 0 起） |
| `dock.bounce('critical')` 返回正数 ID | 返回 `1` | ✅ |
| `cancelBounce(id)` 生效 | 无异常 | ✅ |
| `setBadgeCount(3)` 返回 true 且 `dock.getBadge()` 读到 `3` | 一致 | ✅ |
| `Notification.isSupported()` | `true` | ✅ |
| `Notification` 能真实触发 `show` 事件 | 一致 | ✅ |
| **聚焦时 `bounce()` 返回 `-1`** | **未复现**（测试窗口未成为真正的前台应用，`isFocused` 始终为 false，返回 0/1） | ⚠️ 偏差 |

> 关于偏差：文档声明"聚焦时返回 -1"，实测是在**无 GUI 焦点抢占**的探针环境里跑的，窗口拿不到真正的前台焦点，所以始终走了"未聚焦"分支。这不影响实际使用结论，但**代码里不要把 `-1` 当唯一判据**，应该先自己判断 `win.isFocused()`。

---

## 5. 三层的职责划分（以及最容易踩的坑）

### 5.1 为什么不能在渲染进程直接调这些 API

本项目 `electron/main.mjs` 里窗口打开了 `contextIsolation: true`、`nodeIntegration: false`、`sandbox: true`，渲染进程只能通过 `preload.cjs` 暴露的 `window.electronAPI` 桥接。所以**所有系统提醒必须落到主进程**：

```text
Renderer（Vue2 业务事件）
   │  window.electronAPI.notifyTaskDone(payload)   ← preload 白名单桥
   ▼
Main（NotificationManager）
   │  聚焦判断 / 去重 / 限流 / 等级映射
   ▼
OS（bounce / flashFrame / Notification / badge）
```

### 5.2 三个必须做的"防打扰"判断

| 判断 | 原因 | 实现 |
| --- | --- | --- |
| **应用是否在前台** | 用户正盯着看，再弹通知就是骚扰 | 先判 `win.isFocused()`；应用聚焦时 `bounce()` 会返回 -1（空转），不要依赖它的返回值当判断 |
| **是否在看同一个会话** | 用户切到别的会话了，当前会话完成应当提醒 | 渲染进程上报 `activeSessionId`，主进程比对 |
| **是否短时间重复** | Agent 一轮几十个工具调用，逐个提醒会炸 | 按 `sessionId` 做时间窗合并（例如 3 秒内合并为一条） |

### 5.3 提醒等级建议（对齐"不要过度打扰"）

| 事件 | Dock 跳动 (macOS) | 任务栏闪烁 (Windows) | 系统通知 | 角标 |
| --- | --- | --- | --- | --- |
| 普通 Shell Tool 单步完成 | 否 | 否 | 否 | 否 |
| 用户主动发起的长耗时任务完成 | `informational` | `flashFrame(true)` | ✅ | +1 |
| Agent 整轮回答完成（`resp_end`） | `informational` | `flashFrame(true)` | ✅ | +1 |
| Agent 执行失败（`error`） | `critical` | `flashFrame(true)` | ✅（带错误摘要） | +1 |
| 等待人工审批 / 等待用户回答（`interrupt_requested`） | `critical` | `flashFrame(true)` | ✅ | 保持 |
| 用户正在查看该会话 | 否 | 否 | 否 | 否 |

**等级映射的关键**：`flashFrame` 在 macOS 上等价于 critical，所以如果产品希望"成功=轻提醒"，macOS 应该**优先用 `bounce('informational')`**，而 `flashFrame` 留给失败与 HITL 场景。

---

## 6. AgentForge Desktop 落地设计

### 6.1 现有代码的真实接入点

| 位置 | 现状 | 改造点 |
| --- | --- | --- |
| `electron/main.mjs` `createWindow()`（约 197 行） | `const win = new BrowserWindow({...})`，窗口引用是函数内局部变量 | 提升为模块级 `mainWindow`，供 NotificationManager 复用 |
| `electron/main.mjs` `registerIpc()`（约 328 行起） | 已有一批 `ipcMain.handle('local-agent:*')` | 新增 `local-agent:notify` 通道 |
| `electron/preload.cjs` | `contextBridge.exposeInMainWorld('electronAPI', {...})`，含 `onBackendExit` 监听器范式 | 新增 `notifyTaskDone()` / `cancelAttention()` |
| `src/pages/workbench/ChatWorkbench.vue` `handleEvent()` | `case 'resp_end'`（约 1363 行）置 `status='success'`；`case 'error'`（约 1371 行） | 在这两个分支触发提醒 |
| `src/pages/workbench/ChatWorkbench.vue` `applyInterrupt()`（约 1384 行） | `interrupt_requested` 时 `status='waiting'` | 此处触发"等待你处理"的 critical 提醒 |
| `src/pages/workbench/ChatWorkbench.vue` `send()` 的 `finally`（约 1315 行） | 统一收口 `running` 状态 | 可作为兜底提醒点（避免漏事件） |
| 后端 `SessionMessageStatusEnum` | `RUNNING(2)` / `WAITING_HITL(8)` | 与前端状态语义对齐，不额外新增事件类型 |

> 注意：`resp_end` / `interrupt_requested` 这些事件名来自 AgentForge 运行时（后端 SSE），**不需要为通知再造一套事件**——这是这套方案"零模型参与"的另一个好处：**复用已有事件流即可**。

### 6.2 主进程 NotificationManager（建议实现）

```javascript
// electron/notification-manager.mjs
import { app, Notification } from 'electron'

const LEVEL = { silent: 0, info: 1, critical: 2 }

export class NotificationManager {
  constructor({ getWindow, getActiveSessionId }) {
    this.getWindow = getWindow
    this.getActiveSessionId = getActiveSessionId
    this.lastBySession = new Map()   // sessionId -> timestamp，用于合并
    this.bounceId = 0
  }

  notify({ sessionId, title, body, level = 'info', badge = true }) {
    const win = this.getWindow()
    if (!win || win.isDestroyed()) return
    if (win.isFocused() && this.getActiveSessionId() === sessionId) return   // 正在看，不打扰

    const now = Date.now()
    const last = this.lastBySession.get(sessionId) || 0
    if (now - last < 3000) return                                            // 3 秒内同会话合并
    this.lastBySession.set(sessionId, now)

    this.requestAttention(level)

    // 角标仅 macOS / Linux 支持；Windows 上这个分支不会执行
    if (badge && typeof app.setBadgeCount === 'function') {
      const next = (typeof app.getBadgeCount === 'function' ? app.getBadgeCount() : 0) + 1
      app.setBadgeCount(next)
    }

    if (!Notification.isSupported()) return
    const n = new Notification({ title, body, silent: level !== 'critical' })
    n.on('click', () => this.focusSession(sessionId))
    n.show()
  }

  requestAttention(level = 'info') {
    const win = this.getWindow()
    if (process.platform === 'darwin') {
      // macOS：轻提醒用 informational，重提醒才用 critical
      const type = level === 'critical' ? 'critical' : 'informational'
      if (this.bounceId) app.dock.cancelBounce(this.bounceId)
      const id = app.dock.bounce(type)
      this.bounceId = id > 0 ? id : 0
    } else if (process.platform === 'win32') {
      win.flashFrame(true)
      win.once('focus', () => win.flashFrame(false))   // 回到窗口即停
    }
  }

  cancelAttention() {
    const win = this.getWindow()
    if (process.platform === 'darwin' && this.bounceId) {
      app.dock.cancelBounce(this.bounceId)
      this.bounceId = 0
    } else if (process.platform === 'win32' && win && !win.isDestroyed()) {
      win.flashFrame(false)
    }
    if (typeof app.setBadgeCount === 'function') app.setBadgeCount(0)
    this.lastBySession.clear()
  }

  focusSession(sessionId) {
    const win = this.getWindow()
    if (!win) return
    if (win.isMinimized()) win.restore()
    win.show(); win.focus()
    win.webContents.send('local-agent:focus-session', { sessionId })
  }
}
```

设计要点逐条对应前面的源码事实：

- `app.dock` 当**属性**用，不当函数调（§4.1）。
- `bounce` 返回 ID，先 `cancelBounce` 再发新的，避免多次请求叠加（§4.5 第 1、2 点）。
- Windows 侧显式 `flashFrame(false)`，不依赖系统自动停（§4.3 源码注释）。
- **角标做了能力探测**：`app.setBadgeCount` 只在 macOS / Linux 存在，Windows 上直接跳过（类型定义 `@platform linux,darwin`，§4.6 实测）。
- 通知点击回连到具体会话，这是 Electron `Notification` 相对 BEL/OSC 的核心优势（后者连"哪个任务"都带不出来）。
- **先判断 `win.isFocused()`，不把 `-1` 当唯一判据**（§4.6 偏差）。

### 6.3 preload 桥接（对齐现有风格）

```javascript
// electron/preload.cjs 追加
notifyTaskDone(payload) {
  return ipcRenderer.invoke('local-agent:notify', payload)
},
cancelAttention() {
  return ipcRenderer.invoke('local-agent:cancel-attention')
},
onFocusSession(callback) {
  const listener = (_event, payload) => callback(payload)
  ipcRenderer.on('local-agent:focus-session', listener)
  return () => ipcRenderer.removeListener('local-agent:focus-session', listener)
}
```

### 6.4 渲染进程接线（最小改动）

```javascript
// ChatWorkbench.vue handleEvent 内，仅追加提醒调用，不改动状态机
case 'resp_end':
  if (answer.status !== 'cancelled') {
    promoteLastResponseSegment(answer)
    answer.status = 'success'
    this.finishParts(answer)
    this.notifyTaskDone({ sessionId: this.sessionId, level: 'info', title: '任务已完成', body: this.sessionTitle || 'Agent 已生成回复' })
  }
  break

case 'error':
  if (answer.status === 'cancelled') break
  finishResponseSegments(answer)
  answer.status = 'error'
  answer.error = ...
  this.finishParts(answer)
  this.notifyTaskDone({ sessionId: this.sessionId, level: 'critical', title: '任务执行失败', body: answer.error })
  break
```

```javascript
// applyInterrupt 内
if (event.type === 'interrupt_requested') {
  answer.status = 'waiting'
  this.running = true
  this.notifyTaskDone({ sessionId: this.sessionId, level: 'critical', title: '需要你的确认', body: 'Agent 正在等待你的审批或回答' })
}
```

> 建议把这些调用收敛成一个 `notifyTaskDone()` 方法（内部判断 `window.electronAPI` 是否存在），保证**在浏览器里跑（`npm run dev` 纯 Vite）时不报错**。

### 6.5 第三方 CLI 兼容通道（第二阶段再做）

如果后续要内置终端（`node-pty` + `xterm.js`）来跑 Codex / Claude Code，才需要解析终端协议。xterm.js 的官方类型定义提供了两个正好够用的钩子【一手：`xterm.d.ts`】：

```typescript
onBell: IEvent<void>;                                        // BEL 已解析完成
registerOscHandler(ident: number, cb: (data: string) => boolean | Promise<boolean>): IDisposable;
onTitleChange: IEvent<string>;                               // OSC 0 / OSC 2
```

对应实现：

```javascript
// 仅在内置终端场景启用
const term = new Terminal({ /* ... */ })

term.onBell(() => window.electronAPI.notifyTaskDone({
  sessionId: currentPtySessionId, level: 'info', title: '终端任务完成', body: '收到 BEL 响铃'
}))

term.parser.registerOscHandler(9, (message) => {
  window.electronAPI.notifyTaskDone({
    sessionId: currentPtySessionId, level: 'info', title: '终端通知', body: message
  })
  return true   // 返回 true 表示已消费，避免回退到上一个 handler
})
```

如果想做"精确完成 + 退出码"，则要解析 `OSC 133`（`A/B/C/D;<exitcode>`），这也正是 WezTerm / VS Code 的做法（§3.4）。

---

## 7. 分阶段实施建议

| 阶段 | 范围 | 工作量 | 价值 |
| --- | --- | --- | --- |
| **P0** | 主进程 `NotificationManager` + `resp_end` / `error` / `interrupt_requested` 三个触发点；聚焦判断 + 3 秒合并 | 小（约 1 个文件 + 3 处调用） | 立刻获得"切走也能知道任务完成"的核心体验 |
| **P1** | Dock 角标未读数、点击通知回到对应会话、`win.once('focus')` 自动停闪 | 小 | 提醒闭环，避免"提醒了但找不到" |
| **P2** | 设置项（开关/等级/静音时段，例如"开会时间不提醒"） | 中 | 可配置，减少长期投诉 |
| **P3** | 内置终端（`node-pty` + `xterm.js`）+ BEL/OSC 9/OSC 133 解析 | 大 | 兼容第三方 CLI 的原生提醒协议 |

**明确不建议现在就做的**：

- ❌ 在 P0 阶段引入 `node-pty` / `xterm.js` —— 与"任务完成提醒"无因果关系，属于终端能力建设。
- ❌ 直接把 BEL 逻辑硬编码进 Shell 工具 —— 会把"提醒策略"耦合进工具执行层，后续改等级/静音都要动工具。

---

## 8. 风险与坑清单

| 风险 | 表现 | 应对 |
| --- | --- | --- |
| **macOS 未签名应用发不出通知** | Electron 官方文档明确：macOS 通知底层是 `UNNotification`，**要求应用被签名**，未签名二进制会触发 `failed` 事件 | 本项目 `scripts/package/after-pack-mac.mjs` 目前是 **ad-hoc 签名**（`codesign --force --deep --sign -`）。ad-hoc 是否足以稳定出通知**需要打包后实测**，若无效应优先保证 Dock 跳动 + 角标这两条不依赖通知权限的路径 |
| **Dock 跳动在聚焦时静默失效** | `bounce()` 返回 -1，无任何效果 | 先判 `win.isFocused()`，聚焦时改用应用内 UI 提示（如会话列表高亮） |
| **Windows 闪烁停不下来** | 历史高频问题 | 显式 `flashFrame(false)`；监听 `focus` 事件兜底 |
| **提醒风暴** | Agent 一轮几十次工具调用 | 按 `sessionId` + 时间窗合并；只在"整轮完成/失败/等待 HITL"三个语义点提醒 |
| **把 `flashFrame` 当轻提醒** | macOS 上变成持续 critical 跳动，观感很凶 | 轻提醒用 `bounce('informational')`（§4.2） |
| **终端配置吞掉 BEL** | 本机 `Terminal.app` 的 `Bell = 0`，什么都不会发生 | 不要依赖 BEL 做产品级提醒；终端能力只作兼容补充（§3.6） |
| **Windows 没有 Dock 角标** | 想让 Windows 用户看到"3 个未读"会无处显示 | Windows 用 `win.setOverlayIcon(图标, 描述)` 叠加角标图，或用 `win.setProgressBar` 表达进度；不要调用 `setBadgeCount`（该 API 在 Windows 上不存在） |
| **通知点击后找不到会话** | 用户点通知只把应用拉到前台，仍停在旧会话 | 通知 `click` 里带 `sessionId`，通过 IPC 让渲染进程切换会话（§6.2 `focusSession`） |

---

## 9. 参考文档

**Codex / OpenAI 官方**

- Codex 配置参考（`tui.notifications` / `notification_method` / `notification_condition` / `notify` 原始定义）
  <https://developers.openai.com/codex/config-reference>
- Codex 配置示例
  <https://developers.openai.com/codex/config-sample>
- Codex 仓库（配置类型定义 `codex-rs/config/src/types.rs`）
  <https://github.com/openai/codex/blob/main/codex-rs/config/src/types.rs>

**终端协议与终端实现**

- iTerm2 Escape Codes（OSC 9 桌面通知、OSC 9;4 进度条原文）
  <https://iterm2.com/documentation-escape-codes.html>
- iTerm2 Shell Integration（OSC 133 语义标记）
  <https://iterm2.com/documentation-shell-integration.html>
- Ghostty VT 文档 OSC 9（"Show a desktop notification"，含与 ConEmu 的冲突说明）
  <https://ghostty.org/docs/vt/osc/9>
- WezTerm Shell Integration（OSC 133 区域划分、OSC 7 工作目录、OSC 1337 用户变量）
  <https://wezterm.org/shell-integration.html>
- WezTerm 官方 shell 集成脚本（`\e]133;P;k=i\a` 实际写法）
  <https://github.com/wezterm/wezterm/blob/main/assets/shell-integration/wezterm.sh>
- Windows Terminal `profiles.schema.json`（`bellStyle` 支持 `audible/window/taskbar`，`all` 会闪任务栏）
  <https://github.com/microsoft/terminal/blob/main/doc/cascadia/profiles.schema.json>
- VS Code Shell Integration（私有 `OSC 633` A/B/C/D 协议）
  <https://code.visualstudio.com/docs/terminal/shell-integration>

**Electron 官方文档**

- `app` API（`setBadgeCount`、`app.dock` 为只读属性）
  <https://www.electronjs.org/docs/latest/api/app>
- `Dock` 类（`bounce` 返回 Integer ID、`informational` 跳 1 秒、聚焦返回 -1、`cancelBounce`、`setBadge`）
  <https://www.electronjs.org/docs/latest/api/dock>
- `BrowserWindow`（`flashFrame`、`setOverlayIcon`、`setProgressBar`）
  <https://www.electronjs.org/docs/latest/api/browser-window>
- `Notification`（macOS 依赖 `UNNotification`、**要求签名**、`isSupported()`）
  <https://www.electronjs.org/docs/latest/api/notification>
- Windows 任务栏定制教程
  <https://www.electronjs.org/docs/latest/tutorial/windows-taskbar>
- 通知教程
  <https://www.electronjs.org/docs/latest/tutorial/notifications>

**Electron 源码（本次核对过的实现依据）**

- `shell/browser/native_window_mac.mm` —— `FlashFrame` → `[NSApp requestUserAttention:NSCriticalRequest]`
  <https://github.com/electron/electron/blob/main/shell/browser/native_window_mac.mm>
- `shell/browser/native_window_views.cc` —— Windows `FlashWindowEx(FLASHW_STOP)` + Chromium 停闪缺陷注释
  <https://github.com/electron/electron/blob/main/shell/browser/native_window_views.cc>
- `shell/browser/browser_mac.mm` —— `SetBadgeCount` → `DockSetBadgeText`
  <https://github.com/electron/electron/blob/main/shell/browser/browser_mac.mm>
- `window.flashFrame(bool)` 在 macOS 上改为持续闪 Dock 的 breaking change（PR #41391）
  <https://github.com/electron/electron/pull/41391>
- `xterm.js` 类型定义（`onBell`、`registerOscHandler`、`onTitleChange`）
  <https://github.com/xtermjs/xterm.js/blob/master/typings/xterm.d.ts>

**Apple 平台语义**

- `NSApplication.requestUserAttention(_:)`（Dock 跳动两种类型的系统级语义）
  <https://developer.apple.com/documentation/appkit/nsapplication/requestuserattention(_:)>

**【社区，仅供参考】**

- Codex CLI 通知实践整理（`tui.notifications` 与 `notify` 用法、`agent-turn-complete` 载荷说明）
  <https://goatbar.melbora.com/guides/codex-cli-notifications>
- Codex 通知管道（OSC 9 / notify hooks / TUI 三层架构解读）
  <https://codex.danielvaughan.com/2026/04/13/codex-cli-notification-pipeline-osc9-hooks-alerts/>

---

## 附录：本机验证脚本与输出

**A. 能力探针（`/tmp/afnotify/main.js`）**

```javascript
const { app, BrowserWindow, Notification } = require('electron')
const log = (...a) => console.log('[TEST]', ...a)

app.whenReady().then(async () => {
  log('platform', process.platform, 'electron', process.versions.electron)
  const win = new BrowserWindow({ show: false, width: 800, height: 600 })
  log('win.isFocused', win.isFocused())

  const idInfo = app.dock.bounce('informational')
  log('dock.bounce(informational) ->', idInfo)
  const idCrit = app.dock.bounce('critical')
  log('dock.bounce(critical) ->', idCrit)
  if (idCrit > 0) { app.dock.cancelBounce(idCrit); log('cancelBounce ok', idCrit) }
  if (idInfo >= 0) { app.dock.cancelBounce(idInfo); log('cancelBounce ok', idInfo) }

  win.flashFrame(true)
  log('flashFrame(true) called; win.isFocused=', win.isFocused())
  win.flashFrame(false)

  const badgeOk = app.setBadgeCount(3)
  log('setBadgeCount(3) ->', badgeOk, 'getBadge=', app.dock.getBadge())
  app.setBadgeCount(0)

  log('Notification.isSupported() ->', Notification.isSupported())
  const n = new Notification({ title: 'AgentForge 验证', body: 'Dock 跳动与通知能力探针' })
  n.on('show', () => log('notification: show'))
  n.on('failed', (_e, err) => log('notification: failed ->', err))
  n.on('click', () => log('notification: click'))
  n.show()
  setTimeout(() => { log('done'); app.exit(0) }, 2500)
})
```

**B. 本机环境**

```text
macOS 14.6 / aarch64
Electron 31.7.7（项目 node_modules 实际版本）
Node v22.16.0（nvm）
Shell: /bin/zsh（未检测到 OSC 133 shell integration 配置）
Terminal.app: Basic profile Bell = 0（响铃关闭）
Codex: /Applications/ChatGPT.app/Contents/Resources/codex（配置仅 notify，未开 tui.notifications）
```

**C. 输出**

```text
[TEST] platform darwin electron 31.7.7
[TEST] win.isFocused false
[TEST] dock.bounce(informational) -> 0
[TEST] dock.bounce(critical) -> 1
[TEST] cancelBounce ok 1
[TEST] flashFrame(true) called; win.isFocused= false
[TEST] setBadgeCount(3) -> true getBadge= 3
[TEST] Notification.isSupported() -> true
[TEST] notification: show
[TEST] done
```

**D. 用户可自行做的终端侧验证（不涉及本项目代码）**

```bash
# 1) BEL：切到别的应用，5 秒后观察终端图标是否跳动/是否响铃
sleep 5 && printf '\a'

# 2) OSC 9：支持该协议的终端会弹出桌面通知
sleep 5 && printf '\033]9;构建任务执行完成\007'

# 3) 若要观察 Codex 自带提醒，先在 ~/.codex/config.toml 开启
#    [tui]
#    notifications = true
#    notification_method = "auto"
#    notification_condition = "unfocused"
```
