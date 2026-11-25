# 任务完成提醒（Dock 跳动 / 任务栏闪烁）本地落地方案

> 对应仓库：`AgentForge-Desktop`
> 关联调研：[`终端与Agent任务完成提醒交互调研（Dock跳动、任务栏闪烁与桌面通知）.md`](../../../方案调研/产品交互优化/终端与Agent任务完成提醒交互调研（Dock跳动、任务栏闪烁与桌面通知）.md)
> 涉及模块：`electron/main.mjs`、`electron/preload.cjs`、`src/pages/workbench/ChatWorkbench.vue`、`src/components/ConfigDrawer.vue`
> 版本：v3（文档结构调整）
> 编写时间：2026-11-25
> 实现状态：**已实现**

**一句话结论**：任务结束时，macOS Dock 图标**连续跳 3 次**、Windows 任务栏**闪烁**，只在用户离开应用时触发；纯前端（Electron 主进程）改造，**零后端改动、零通知权限依赖**。

---

## 文档导读

全文按「为什么做 → 查到了什么 → 产品上怎么定 → 代码上怎么做」四段推进：

| 部分 | 回答的问题 | 建议读者 |
| --- | --- | --- |
| **第一部分 背景** | 为什么要做这件事？做到什么程度、不做什么？ | 所有人（先看这 1 分钟） |
| **第二部分 调研分析** | 技术上到底可不可行？依据是什么？有哪些坑？ | 评审、技术决策 |
| **第三部分 核心产品设计约束 & 交互** | 能力边界在哪？用户实际看到什么？参数怎么定？ | 产品、设计、测试 |
| **第四部分 核心代码实现细节流程** | 具体改哪些文件、每段代码为什么这么写？ | 开发、CR |

附录含边界异常、验收清单、平台矩阵、风险与后续演进。

---

# 第一部分 · 背景

## 1.1 用户问题

Agent 的任务是长耗时、多轮的（一轮里可能几十次工具调用）。用户发起任务后，通常会切到别的窗口去做别的事，此时会遇到三个问题：

1. **任务在后台跑完，界面上没有任何"我知道了"的信号**；
2. **用户必须主动切回应用、逐个点开会话**，才知道哪一轮已经结束；
3. **对比 Codex CLI**（任务完成后终端图标会跳 / 会弹提示），桌面端反而更"安静"。

## 1.2 目标

| 目标 | 说明 |
| --- | --- |
| **多次跳动而非一次** | 单次跳动仅约 1 秒，容易被错过；连续 3 次（间隔 1.6 秒，总跨度约 4.2 秒），确保余光可感知 |
| 只在用户不在应用里时提醒 | 用户正盯着应用看的时候不打扰 |
| 用户回来即停 | 提醒过程中若用户切回应用，立即终止剩余跳动 |
| 可配置、默认开启 | 设置 → 系统配置 → 「任务完成提醒」，默认勾选 |
| 零后端改动 | 不新增接口、不改表结构、不新增 SSE 事件，复用已有事件流 |
| 零通知权限依赖 | 只用 `app.dock.bounce` / `win.flashFrame`，不需要系统通知授权、不受 macOS 签名状态影响 |

## 1.3 非目标（本阶段明确不做）

| 不做 | 原因 |
| --- | --- |
| 系统桌面通知（`new Notification`） | macOS 通知要求应用签名，当前是 ad-hoc 签名，稳定性不可控（详见 §2.4） |
| Dock 数字角标 / 未读数 | 需要先有未读计数模型，属于 P2 |
| 任务栏覆盖图标（`setOverlayIcon`） | 同上 |
| 点击提醒回到对应会话 | 需要通知通道，属于 P2 |
| 静音时段、提醒等级、声音 | 属于 P2/P3 的精细化能力 |
| 内置终端（`node-pty` + `xterm.js`）与 BEL / OSC 解析 | 与"任务完成提醒"无因果关系，属于 P4 |
| 任何后端字段、接口、数据库变更 | 本次严格限定为前端改造 |

---

# 第二部分 · 调研分析

## 2.1 Codex 的"终端图标会跳"不是 CLI 干的

这是本项目不照抄 Codex 方案的关键原因。

| 事实 | 说明 |
| --- | --- |
| Codex 只往终端写控制字符 | BEL（`\x07`）与 OSC 9（`ESC ] 9 ; <文本> ST`） |
| **真正跳图标的是终端模拟器** | 终端读到这些字符后，自己调用系统的注意力接口 / 通知接口 |
| 为什么"时有时无" | 第一道开关在**终端配置**：本机 `Terminal.app` 的 `Bell = 0`，BEL 被静默丢弃 |

**结论**：AgentForge Desktop 本身就是"终端 + 应用"的合体，**不需要绕 BEL/OSC 兼容路径**，直接调用 Electron 暴露的系统能力即可，且能携带"哪个会话完成了"这类 BEL/OSC 表达不了的信息。

## 2.2 Electron 侧可用的原生能力

| 能力 | API | 平台 | 关键语义 |
| --- | --- | --- | --- |
| Dock 图标跳动 | `app.dock.bounce(type)` / `app.dock.cancelBounce(id)` | macOS | `informational` 跳约 1 秒；`critical` 跳到应用激活为止；返回 **Integer 请求 ID**，须用它取消 |
| 任务栏 / 窗口闪烁 | `win.flashFrame(flag)` | 全平台 | macOS 底层是 `[NSApp requestUserAttention:NSCriticalRequest]`（= critical 级）；Windows 走 `FlashWindowEx`，**必须显式 `flashFrame(false)` 才能停** |
| 桌面通知 | `new Notification()` | 全平台 | macOS 依赖 `UNNotification`，**要求应用已签名** |

两个必须记住的坑：

1. **`flashFrame(true)` 在 macOS 上不是"轻提醒"** —— 它等价于 `bounce('critical')`，会一直跳。所以 macOS 做"任务完成"必须用 `bounce('informational')`，`flashFrame` 只留给 Windows。
2. **`dock.bounce()` 返回的 ID 可能是 `0`** —— `0` 是合法 ID，**不能用真假值判断**，否则会漏掉取消。要用 `null` 表示"没有待取消的请求"。

## 2.3 关键实测：三个决定实现结论的事实

### 事实一：应用在前台时，系统直接拒绝跳动

| 应用状态 | `win.isFocused()` | `bounce('informational')` 返回 | 图标是否跳 |
| --- | --- | --- | --- |
| 应用活跃 + 窗口聚焦 | `true` | `-1` | 否（系统拒绝） |
| 应用活跃 + 窗口失焦（点了别处但应用仍在前） | `false` | `-1` | 否（系统拒绝） |
| 应用被隐藏 / 用户切到其他 App | `false` | `0` 或正整数 | **是** |

> **这修正了一个原始假设**：仅凭 `win.isFocused()` 判断"用户是否在看应用"是不够的——窗口失焦但应用仍在前台时，系统依然拒绝跳。
> 因此代码必须把 `-1` 当作"未提醒成功"处理（并重置节流时间），而不是当成拿到了 ID。否则用户真正切走后，节流窗口已被错误占用，反而提醒不了。
>
> 附带价值：**"应用在前台"这一档系统本就跳不起来，与业务判断方向一致**，属于系统层双重保险。

### 事实二：每次 `bounce()` 创建独立请求，不取消就会叠加

```text
连续调用三次且不取消：
  app.dock.bounce('informational') → 0
  app.dock.bounce('informational') → 1
  app.dock.bounce('informational') → 2
```

三个不同 ID = **三个并行请求**，而非"排队播放"。若直接循环调用，用户会看到一团混乱抖动，且无法精确控制停止时机。

> 因此**每次搏动前必须先 `cancelBounce(上一个 ID)`**，让"新的一次"接管位置，形成干净的「跳一下 → 停 → 再跳一下」节奏。

### 事实三：`informational` 单次寿命约 1 秒，续跳间隔必须大于它

真实 Electron 环境实测（`app.hide()` 后连续三次，设定间隔 1600ms）：

| 序号 | 触发时刻 | 返回 ID | 是否有效 |
| --- | --- | --- | --- |
| 1 | +913ms | `0` | ✅ |
| 2 | +2516ms | `0` | ✅ |
| 3 | +4119ms | `0` | ✅ |

实测间隔 1603ms / 1603ms，与设定基本一致。

> 间隔若小于 1000ms，上一次跳动尚未结束就被取消，观感会退化成"连续抖动一次"，**比不做多次跳动更差**。1600ms 留出约 600ms 静止间隙，用户能明确数出"三下"。

### 补充：定时器不受窗口不可见影响

主进程 `setTimeout` 不受渲染进程 `backgroundThrottling` 影响（调研阶段已验证：窗口隐藏后 SSE 事件仍按秒级到达），因此续跳节奏在应用隐藏 / 最小化时依然准确。

## 2.4 方案选型：为什么不用系统通知

| 方案 | 可行性 | 结论 |
| --- | --- | --- |
| **Dock 跳动 + 任务栏闪烁**（采用） | ✅ 不依赖通知授权 | 本阶段采用 |
| 系统通知 `Notification` | ⚠️ macOS 走 `UNNotification`，**要求应用已签名**；本项目为 ad-hoc 签名（`scripts/package/after-pack-mac.mjs`），是否稳定出通知需打包实测 | 延后到 P3 |
| BEL / OSC 9 终端协议 | ❌ 本应用无内置终端；且提醒效果取决于终端配置（本机 `Bell = 0` 会被丢弃） | 不采用 |
| `flashFrame` 做 macOS 轻提醒 | ❌ 在 macOS 上等价于 `critical`（持续跳），观感过强 | 不采用 |

**打扰强度对位**：任务完成属于"信息级"事件，`bounce('informational')` 是最匹配的系统语义；通知属"弹窗级"，`critical` 属"催促级"，都留给更紧急的场景（P1 的等待审批）。

## 2.5 现有代码的真实接入点

| 位置 | 现状 | 本次改动 |
| --- | --- | --- |
| `main.mjs` `createWindow()` | `const win = new BrowserWindow({...})`，窗口是局部变量 | 提升为模块级 `mainWindow` |
| `main.mjs` `readSystemSettingsFile()` / `save-system-settings` | 只有 `interactionModeSupport`，硬编码白名单 | 扩为 `normalizeSettings()`，新增 `taskCompletionReminder` |
| `main.mjs` `registerIpc()` | 已有 `local-agent:*` 通道 | 新增 `notify-task-done` / `cancel-task-attention` |
| `preload.cjs` | `contextBridge` 白名单桥接 | 新增 `notifyTaskDone()` / `cancelTaskAttention()` |
| `ChatWorkbench.vue` `handleEvent()` `resp_end` / `error` | 只改状态，无任何外部信号 | 各加一行 `notifyTaskDone(answer)` |
| `ChatWorkbench.vue` `send()` / `submitInterrupt()` 的 `finally` | 统一收口 `running` 状态 | 作为兜底提醒点 |
| `ConfigDrawer.vue` 系统配置栏目 | 一行「交互模式支持切换」 | 追加一行「任务完成提醒」 |

> **安全前提**：窗口配置为 `contextIsolation: true` + `sandbox: true` + `nodeIntegration: false`，渲染进程拿不到 Electron API。因此所有系统级提醒必须走 preload 桥 → 主进程，与现有 `readSystemSettings` 完全一致，**不引入新的安全模型**。

---

# 第三部分 · 核心产品设计约束 & 交互

## 3.1 设计约束

先把边界说清楚，避免后续把"能力限制"误当成 bug。

### 3.1.1 平台约束：能力按操作系统分，不按 CPU 架构分

| 约束 | 说明 |
| --- | --- |
| macOS = Dock 跳动 | `Dock.bounce` 标注 `@platform darwin`，与 arm64/x64 无关 |
| Windows = 任务栏闪烁 | `flashFrame` 无平台注解（全平台可用），Windows 底层 `FlashWindowEx` |
| **代码不判断架构** | 只有 `process.platform === 'darwin'` 一个分支；不存在"arm64 版"和"x64 版" |

### 3.1.2 能力约束：这三件事系统层面就做不到

| 约束 | 原因 | 应对 |
| --- | --- | --- |
| **应用在前台时无法跳动** | 系统直接返回 `-1`，图标不跳（图标就在用户眼前，系统认为无需提醒） | 与产品意图一致，不做额外处理 |
| `informational` 单次只跳约 1 秒 | macOS 系统行为，不可配置 | 用间隔续跳实现"多次跳动" |
| Windows 无法"跳 N 下" | `flashFrame` 是"持续闪到停止"语义 | 观感上是"回过窗口前一直闪"，符合 Windows 用户认知 |

### 3.1.3 架构约束：同一时刻只消费一个会话的流 ⚠️ 重要限制

这是**本方案当前最重要的限制**，且**根源不在提醒功能，而在既有的流消费架构**。

前端用单个 `controller` 管理流式连接：

```js
async openSession(item) {
  if (this.controller) {
    this.controller.abort()   // ← 切换会话时，中断上一个会话的流
    this.controller = null
  }
```

`this.controller` 是**单例**，整个组件只有一个。切换会话时旧流被 `abort()`，而 `handleEvent(answer, ...)` 依赖的 `messages` 数组也会被整体替换。

**实际影响**：

| 用户操作 | 提醒能否触发 |
| --- | --- |
| 发起任务 A → **切到别的应用** | ✅ 流在后台继续，完成时正常跳 3 下 |
| 发起任务 A → 在应用内**切到会话 B** | ⚠️ 任务 A 的流被 abort，前端不再消费 |
| 发起任务 A → 切到会话 B → 再切走应用 | ❌ 任务 A 完成时**不会有任何提醒** |

> **注意方向**：这不是"只有停在当前会话才跳"的反向限制。实际是"**应用在前台时不跳，切走才跳**"；缺口在于**切换会话后，前一个会话的完成事件在前端就断了**。

**补充事实**：任务本身**在后端仍在正常执行并落库**（Java 侧有独立执行线程），只是前端这一刻没有监听者。所以切回那个会话时消息是完整的，只是当时没收到提醒。

**三种修法（均不在本次范围）**：

| 方案 | 做法 | 成本 | 风险 |
| --- | --- | --- | --- |
| 一 | 卸载时不 abort，后台继续消费，完成时照常上报 | 中 | 需维护按 `sessionId` 索引的后台任务表，并处理切回时的消息合并 |
| 二 | 主进程兜底轮询"是否有会话刚完成" | 中 | 需要新增查询接口，**打破"零后端改动"** |
| 三 | 仅补"切回应用时在会话列表标未读点" | 小 | 不是跳图标，是视觉标记 |

本方案选择**先交付 P0（当前会话 + 切走应用场景）**，把上述能力放到附录 E 的 P1。

### 3.1.4 其他工程约束

| 约束 | 说明 |
| --- | --- |
| 开关只在主进程判断 | 配置文件读写权限收敛在主进程，且避免渲染层伪造 IPC 参数绕过开关 |
| 开关默认开启 | 用 `!== false` 而非 `=== true`：配置缺失 / JSON 损坏都回退为开启（漏提醒比多跳一下伤害更大） |
| 关掉开关立即生效 | 续跳循环每次触发前都重新读开关，中途关闭会立刻停止剩余跳动 |

## 3.2 提醒决策矩阵

主进程按以下顺序判断，任一不通过即返回，**不调用系统 API**：

| 顺序 | 判断 | 不通过时 |
| --- | --- | --- |
| 1 | 窗口存在且未销毁 | `no-window` |
| 2 | `taskCompletionReminder === true` | `disabled` |
| 3 | `win.isFocused()` 为 false | `focused`（用户正在看） |
| 4 | 距上次提醒 ≥ 3000ms | `throttled`（合并） |
| 5 | 首跳 `bounce()` 返回有效 ID | `app-active`（系统拒绝，**且不占用节流窗口**） |

组合后的实际行为：

| 场景 | 窗口在前台 | 是否提醒 |
| --- | --- | --- |
| 用户正在看这个会话，任务完成 | ✅ | ❌ 不提醒 |
| 用户切到别的会话 / 别的页面 | ✅ | ❌ 不提醒（应用在前台，系统也拒绝跳） |
| 用户切到别的应用 | ❌ | ✅ 连续跳 3 次（约 4.2 秒） |
| 窗口最小化 | ❌ | ✅ 连续跳 3 次 |
| 跳动过程中用户切回应用 | — | ⏹ 立即停止剩余跳动 |
| 用户关闭「任务完成提醒」 | — | ❌ |
| 3 秒内多次收尾事件 | — | 只提醒一轮（3 次） |
| 新提醒遇上上一轮还在跳 | — | 清掉旧节奏，重新跳 3 次 |

## 3.3 交互效果：多次跳动

### 3.3.1 核心体验

| 版本 | 行为 | 用户感知 |
| --- | --- | --- |
| v1 | Dock 跳 1 次（约 1 秒）后停止 | 容易错过：用户正低头做事，抬头时已经结束了 |
| **v2+（当前）** | **Dock 连续跳 3 次，间隔 1.6 秒** | 有明确的"有人在叫我"信号，总时长约 4.2 秒，仍在"不烦人"范围内 |

节奏时间轴（macOS）：

```text
时间轴  0ms ──────── 1600ms ──────── 3200ms ──────── 结束
        │            │              │
       跳第 1 下     跳第 2 下       跳第 3 下
        │← 约1秒 →│ 停 │← 约1秒 →│ 停 │← 约1秒 →│
        └────── 每次跳完有短暂停顿，能数出"三下" ──────┘
```

**关键设计**：间隔 1600ms > 单次寿命（约 1000ms），每两下之间有约 600ms 静止间隙。这是"能数出次数"的前提。

### 3.3.2 各场景的最终观感

| 场景 | 用户看到什么 |
| --- | --- |
| 发起任务后切到浏览器，任务完成 | Dock 跳 3 下（约 4.2 秒），点回应用后立即停 |
| 任务完成时人刚好回来（跳动进行中） | 第 1 下之后立刻停止，不会再跳第 2、3 下 |
| 任务完成时用户正看着应用 | 完全不跳（系统也不允许） |
| 两个任务在 3 秒内先后完成 | 只跳一轮 3 下，不会变成 6 下 |
| 关闭「任务完成提醒」 | 完全不跳 |
| 应用最小化（未切走） | 正常跳 3 下 |
| Windows：任务栏 | 持续闪烁直到用户回到窗口，不是"跳 3 下" |

> **Windows 差异说明**：`flashFrame(true)` 本身是"持续闪到停止"语义。代码仍走 3 次脉冲，但每次只是再次置位闪烁状态，观感等价于"回到窗口前一直闪"——这符合 Windows 用户对任务栏提醒的既有认知，不需要为"次数"做额外适配。

### 3.3.3 为什么不做"无限跳到用户回来"

| 方案 | 观感 | 问题 |
| --- | --- | --- |
| 跳 1 次 | 太弱 | 容易错过，等于没提醒 |
| **跳 3 次（采用）** | 明确但不烦 | 约 4.2 秒内结束，不长期占据注意力 |
| 跳到用户回来（`critical`） | 极强 | 用户可能离开几十分钟，图标一直跳，属于骚扰级 |
| 无限循环 `informational` | 强 | 本质等同 `critical`，且长期占用定时器 |

`critical` 与无限循环留给更紧急的场景（P1 的"等待人工审批"）。

## 3.4 参数规格

| 参数 | 取值 | 位置 | 调整影响 |
| --- | --- | --- | --- |
| `REMINDER_BOUNCE_TIMES` 跳动次数 | **3** | `main.mjs` 模块顶部 | 调大更醒目但更打扰；建议 2~5 |
| `REMINDER_BOUNCE_INTERVAL_MS` 跳动间隔 | **1600ms** | 同上 | **必须 > 1000ms**，否则糊成一次 |
| `REMINDER_WINDOW_MS` 合并窗口 | 3000ms | 同上 | 控制"多快算重复"，与跳动节奏独立 |
| 跳动类型 | `informational` | `pulseAttention()` | 改 `critical` 会持续跳到应用激活，风格突变，不建议 |

四个参数集中在模块顶部，调参不需要进业务逻辑：

```js
const REMINDER_WINDOW_MS = 3000
const REMINDER_BOUNCE_TIMES = 3
const REMINDER_BOUNCE_INTERVAL_MS = 1600
```

## 3.5 交互状态机

```text
                   ┌──────────────┐
   任务完成事件 ───►│  前置判断     │
                   │ 开关/前台/节流│
                   └──────┬───────┘
                          │ 通过
                          ▼
                   ┌──────────────┐
                   │  清理上一轮   │  ← 防止两轮节奏叠加
                   └──────┬───────┘
                          ▼
                   ┌──────────────┐   被系统拒绝(-1)
                   │  搏动第 1 次  ├──────────────► 立即结束，不占节流窗口
                   └──────┬───────┘
                          │ 成功
                          ▼
              ┌───────────────────────┐
              │ 等待 1600ms           │
              │  ├ 用户回到窗口 → 终止 │
              │  ├ 窗口销毁     → 终止 │
              │  ├ 开关被关闭   → 终止 │
              │  └ 仍在后台     → 搏动 │
              └───────────┬───────────┘
                          │ 还有剩余次数
                          └──────► 回到「等待」循环（共 3 次）

  任意时刻用户点回应用：
    window 'focus' 事件 → cancelTaskAttention()
      → 清定时器 + cancelBounce + 重置节流
      → 剩余跳动立即取消
```

## 3.6 配置项设计

沿用现有系统配置体系（`<rootDir>/conf/settings.json`），不新增文件、不改路径规则。

```json
{
  "interactionModeSupport": false,
  "taskCompletionReminder": true
}
```

| 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `taskCompletionReminder` | boolean | `true` | 任务完成后是否让应用图标提醒；**只有显式写入 `false` 才关闭** |

默认值实现关键：用 `!== false` 而非 `=== true`。

```js
// 只有用户显式关掉才不提醒：缺字段、文件不存在、JSON 损坏都按“开启”处理
taskCompletionReminder: input.taskCompletionReminder !== false
```

---

# 第四部分 · 核心代码实现细节流程

## 4.1 分层与数据流

```text
Agent 事件流（已有，未改动）
  resp_end      ─┐
  error         ─┴─► ChatWorkbench.notifyTaskDone(answer)      渲染进程
                          │  answer.reminderSent 去重（同一轮只上报一次）
                          │  window.electronAPI.notifyTaskDone()
                          ▼
                    preload.cjs（白名单桥）
                          │  ipcRenderer.invoke('local-agent:notify-task-done')
                          ▼
                 main.mjs  requestTaskAttention()               主进程
                   1~5 前置判断（§3.2）
                   6. stopAttentionLoop() 清上一轮
                   7. pulseAttention() 首跳 → scheduleNextPulse() 续跳
                          ▼
                 macOS：Dock 连续跳 3 次（0 / 1600 / 3200ms）
                 Windows：任务栏闪烁（focus 时显式停止）
```

**单向数据流**：渲染 → 主进程。主进程不回调渲染层，**不需要新增任何反向事件通道**。

**职责划分**：渲染进程只负责"报告有事发生"，主进程负责"要不要打扰、怎么打扰"。这样后续加等级、静音时段只改主进程，不动业务代码。

## 4.2 主进程实现：`electron/main.mjs`

### 4.2.1 模块级状态与常量

```js
let mainWindow = null             // 主窗口引用（由 createWindow 赋值）
let attentionBounceId = null      // macOS Dock 跳动请求 ID；null 表示无待取消请求
let attentionTimer = null         // 续跳定时器
let attentionPulsesLeft = 0       // 本轮剩余跳动次数
let lastReminderAt = 0            // 上次提醒时间，用于合并
const REMINDER_WINDOW_MS = 3000
// 连续跳 3 次、间隔 1600ms；macOS 单次只跳约 1 秒，靠间隔续跳形成"多次跳动"
const REMINDER_BOUNCE_TIMES = 3
const REMINDER_BOUNCE_INTERVAL_MS = 1600
```

### 4.2.2 设置规范化（替换原 `readSystemSettingsFile`）

```js
const DEFAULT_SETTINGS = {
  interactionModeSupport: false,
  taskCompletionReminder: true
}

function normalizeSettings(value) {
  const input = value && typeof value === 'object' ? value : {}
  return {
    interactionModeSupport: input.interactionModeSupport === true,
    // 默认开启：只有显式写入 false 才关闭
    taskCompletionReminder: input.taskCompletionReminder !== false
  }
}

function readSystemSettingsFile() {
  try {
    return normalizeSettings(JSON.parse(fs.readFileSync(systemSettingsPath(), 'utf8')))
  } catch (_error) {
    return normalizeSettings(null)
  }
}
```

保存逻辑复用同一套规范化，保证磁盘内容与读取结果一致：

```js
ipcMain.handle('local-agent:save-system-settings', async (_event, settings) => {
  const target = systemSettingsPath()
  const normalized = normalizeSettings(settings)   // 白名单：未知字段被丢弃
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, `${JSON.stringify(normalized, null, 2)}\n`, 'utf8')
  return normalized
})
```

> **不缓存配置**：`settings.json` 体积极小、提醒是低频动作。每次读文件换取"设置一保存就生效"的一致性，比维护缓存失效逻辑更简单，也是"关掉开关立即生效"的基础。

### 4.2.3 核心函数一：停止续跳

```js
// 停止续跳并取消尚未结束的跳动请求。任何需要"立刻停"的场景都调用它。
function stopAttentionLoop() {
  if (attentionTimer) {
    clearTimeout(attentionTimer)
    attentionTimer = null
  }
  attentionPulsesLeft = 0
  if (attentionBounceId !== null && app.dock) {
    app.dock.cancelBounce(attentionBounceId)
  }
  attentionBounceId = null
}
```

**为什么先 `cancelBounce` 再置 `null`**：`0` 是合法 ID，用 `!== null` 判断才能覆盖到它（§2.2 坑 2）。

### 4.2.4 核心函数二：单次搏动

```js
// 触发一次搏动：macOS 用 Dock 跳动，Windows 用任务栏闪烁。
// 返回 false 表示系统拒绝（例如用户就在应用里），调用方应终止整轮续跳。
function pulseAttention(win) {
  if (!win || win.isDestroyed()) return false
  if (win.isFocused()) return false
  if (process.platform === 'darwin' && app.dock) {
    // 关键：先取消上一个请求，否则多次 bounce 会叠加成并行请求（§2.3 事实二）
    if (attentionBounceId !== null) {
      app.dock.cancelBounce(attentionBounceId)
      attentionBounceId = null
    }
    const id = app.dock.bounce('informational')
    // 实测返回 0 也是有效 ID；返回 -1 表示应用仍活跃，系统拒绝跳动
    if (!Number.isInteger(id) || id < 0) return false
    attentionBounceId = id
    return true
  }
  win.flashFrame(true)
  return true
}
```

### 4.2.5 核心函数三：提醒入口

```js
function requestTaskAttention() {
  const win = mainWindow
  if (!win || win.isDestroyed()) return { ok: false, reason: 'no-window' }
  if (!readSystemSettingsFile().taskCompletionReminder) return { ok: false, reason: 'disabled' }
  // 先自行判断前台，不依赖 bounce 返回 -1（该返回值在部分环境下不可靠）
  if (win.isFocused()) return { ok: false, reason: 'focused' }

  const now = Date.now()
  if (now - lastReminderAt < REMINDER_WINDOW_MS) return { ok: false, reason: 'throttled' }

  // 新提醒接管旧提醒：先清掉上一轮未跳完的节奏，避免叠加成 6 下
  stopAttentionLoop()

  if (!pulseAttention(win)) {
    // 被系统拒绝（应用仍活跃）：不占用节流窗口，用户真正切走后仍能提醒
    lastReminderAt = 0
    return { ok: false, reason: 'app-active' }
  }

  lastReminderAt = now
  attentionPulsesLeft = REMINDER_BOUNCE_TIMES - 1   // 首跳已用掉 1 次
  scheduleNextPulse()
  return { ok: true, times: REMINDER_BOUNCE_TIMES }
}
```

**三个易错点**：

| 易错点 | 后果 | 本实现的处理 |
| --- | --- | --- |
| 先记 `lastReminderAt` 再判断能否跳 | 系统拒绝时也占了节流窗口，用户切走后 3 秒内提醒不了 | 失败时重置 `lastReminderAt = 0` |
| 不清上一轮直接开新的 | 两轮节奏叠加成 6 下 | 先 `stopAttentionLoop()` |
| 用 `if (!id)` 判断有效性 | ID 为 `0` 时被误判失败 | 用 `Number.isInteger(id) && id >= 0` |

### 4.2.6 核心函数四：续跳调度

```js
function scheduleNextPulse() {
  if (attentionPulsesLeft <= 0) return
  attentionTimer = setTimeout(() => {
    attentionTimer = null
    const win = mainWindow
    // 四种终止条件：用户回来 / 窗口没了 / 提醒取消 / 中途关掉开关
    if (!win || win.isDestroyed() || win.isFocused() || !readSystemSettingsFile().taskCompletionReminder) {
      stopAttentionLoop()
      return
    }
    if (!pulseAttention(win)) {
      stopAttentionLoop()
      return
    }
    attentionPulsesLeft -= 1
    scheduleNextPulse()
  }, REMINDER_BOUNCE_INTERVAL_MS)
  // 定时器不应阻止进程退出
  if (attentionTimer.unref) attentionTimer.unref()
}
```

**为什么每次续跳都重读开关**：用户中途关闭「任务完成提醒」时，剩余跳动必须立刻停，否则"关闭立即生效"就是假话。这是 v2 迭代中修掉的一个缺陷。

**`timer.unref()` 的必要性**：Node 的定时器默认会阻止进程退出。续跳定时器只是 UI 提示，不应影响应用关闭流程。

### 4.2.7 核心函数五：取消与窗口绑定

```js
function cancelTaskAttention() {
  stopAttentionLoop()
  const win = mainWindow
  if (win && !win.isDestroyed() && process.platform !== 'darwin') win.flashFrame(false)
  lastReminderAt = 0
}

// 用户回到窗口即停止续跳与闪烁，避免"跳个不停"或 Windows 上"闪了停不下来"
function bindAttentionToWindow(win) {
  win.on('focus', () => cancelTaskAttention())
}
```

> Windows 必须显式 `flashFrame(false)`：Electron 源码注释明确写了上游 Chromium 的"停闪"实现有缺陷（*"The Chromium's implementation has a bug stopping flash."*），所以 Electron 自己用 `FlashWindowEx(FLASHW_STOP)` 兜底。**不能指望聚焦自动停闪**。

### 4.2.8 IPC 注册与窗口绑定

```js
// registerIpc() 内追加
ipcMain.handle('local-agent:notify-task-done', () => requestTaskAttention())
ipcMain.handle('local-agent:cancel-task-attention', () => cancelTaskAttention())
```

```js
// createWindow() 收尾
mainWindow = win
bindAttentionToWindow(win)
win.on('closed', () => {
  if (mainWindow === win) mainWindow = null
  cancelTaskAttention()          // 清理定时器与跳动请求
})
```

## 4.3 桥接层：`electron/preload.cjs`

```js
notifyTaskDone() {
  return ipcRenderer.invoke('local-agent:notify-task-done')
},
cancelTaskAttention() {
  return ipcRenderer.invoke('local-agent:cancel-task-attention')
},
```

只有这两个白名单方法，不暴露任意 IPC 通道。

## 4.4 渲染层：`ChatWorkbench.vue`

### 4.4.1 统一上报入口

```js
// 任务结束时的图标提醒：同一轮只上报一次；浏览器模式（无 electronAPI）静默跳过。
notifyTaskDone(answer) {
  if (!answer || answer.reminderSent) return
  answer.reminderSent = true
  const api = window.electronAPI
  if (!api || typeof api.notifyTaskDone !== 'function') return
  // 提醒失败不影响业务：不弹错、不打断对话
  api.notifyTaskDone().catch(() => {})
}
```

三个防护：`reminderSent` 去重、无 `electronAPI` 时静默跳过（浏览器模式）、IPC 失败不抛错。

### 4.4.2 五个触发点

| # | 位置 | 触发条件 | 作用 |
| --- | --- | --- | --- |
| 1 | `handleEvent()` `resp_end` 分支 | `status !== 'cancelled'` | 主路径：正常完成 |
| 2 | `handleEvent()` `error` 分支 | 非 cancelled | 主路径：执行失败 |
| 3 | `send()` 的 `finally` | `!waiting` | 兜底：网络中断无 `error` 事件 |
| 4 | `submitInterrupt()` 的 `finally` | `!waiting` | 兜底：审批恢复后完成 |
| 5 | `openSession()` 重连的 `finally` | `!running` | 覆盖"重新进入会话等待完成" |

第 1、2 处：主路径，事件一到即上报。

```js
case 'resp_end':
  if (answer.status !== 'cancelled') {
    promoteLastResponseSegment(answer)
    answer.status = 'success'
    this.finishParts(answer)
    this.notifyTaskDone(answer)          // ← 新增
  }
  break

case 'error':
  if (answer.status === 'cancelled') break
  finishResponseSegments(answer)
  answer.status = 'error'
  answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '生成失败')
  this.finishParts(answer)
  this.notifyTaskDone(answer)            // ← 新增：失败同样提醒
  break
```

第 3~5 处：兜底，覆盖"事件没来但流已结束"的异常路径。

```js
// send() / submitInterrupt() 的 finally
if (!waiting) this.notifyTaskDone(answer)   // ← 新增
```

**为什么需要兜底**：只靠事件分支会漏掉"连接异常断开、后端已结束但没发 `error`"这类情况。兜底与事件分支同时命中时，由 `reminderSent` 拦掉，不会重复提醒。

**为什么不提醒 `waiting`**：`waiting` 表示等待人工审批，任务尚未结束，用户点开应用本来就要处理确认项。这属于附录 E 的 P1"HITL 提醒"。

## 4.5 设置界面：`ConfigDrawer.vue`

模板新增一行（复用现有 `.system-setting-row` 结构）：

```html
<div class="system-setting-row">
  <div class="system-setting-copy">
    <b>任务完成提醒</b>
    <span>任务执行完成或失败时，应用图标跳动 / 任务栏闪烁提醒</span>
  </div>
  <el-checkbox v-model="systemForm.taskCompletionReminder" aria-label="任务完成提醒" />
</div>
```

状态同步三处，全部用 `!== false` 与主进程保持同口径：

```js
// data
systemForm: {
  interactionModeSupport: false,
  taskCompletionReminder: true
}

// load()：读配置回填
this.systemForm.taskCompletionReminder = systemSettings?.taskCompletionReminder !== false

// saveSystem()：用主进程返回的规范化结果回填，避免界面与磁盘不一致
this.systemForm.taskCompletionReminder = saved.taskCompletionReminder !== false
```

样式只需两行间距：

```css
.system-setting-row + .system-setting-row {
  margin-top: 12px;
}
```

> `ChatWorkbench.vue` 的 `applySystemSettings()` **不需要改**：开关只影响主进程行为，渲染层不持有这份状态，避免"两处状态不同步"。

## 4.6 完整时序（一次成功提醒）

```text
t=0ms      resp_end 事件到达渲染层
           → handleEvent() 置 status='success'
           → notifyTaskDone(answer)：reminderSent=false → 置 true
           → IPC invoke('local-agent:notify-task-done')

t≈1ms      主进程 requestTaskAttention()
           → 读 settings.json：taskCompletionReminder=true ✓
           → mainWindow.isFocused() = false ✓
           → 距上次提醒 > 3000ms ✓
           → stopAttentionLoop()（无旧节奏，空操作）
           → pulseAttention()：bounce('informational') → id=0 ✓
           → lastReminderAt = now；pulsesLeft = 2
           → scheduleNextPulse()

t=1ms      macOS 开始跳第 1 下（约 1 秒），随后静止

t=1601ms   定时器触发
           → 检查：窗口未销毁、未聚焦、开关仍开启 ✓
           → pulseAttention()：先 cancelBounce(0)，再 bounce → 第 2 下
           → pulsesLeft = 1 → scheduleNextPulse()

t=3201ms   定时器触发 → 第 3 下 → pulsesLeft = 0
           → scheduleNextPulse() 首行 return，循环结束

t=4200ms   第 3 下跳完，提醒自然结束，无残留定时器

── 若用户在 t=2000ms 点回应用 ──
           window 'focus' 事件 → cancelTaskAttention()
           → clearTimeout + cancelBounce
           → 第 3 下被取消，提醒提前结束
```

## 4.7 改动量汇总

| 文件 | 改动性质 | 规模 |
| --- | --- | --- |
| `electron/main.mjs` | 新增 6 个函数 + 3 个常量 + 2 个 IPC，改 1 处窗口收尾、2 处设置读写 | 约 +120 行 |
| `electron/preload.cjs` | 新增 2 个桥接方法 | +6 行 |
| `ChatWorkbench.vue` | 新增 1 个方法 + 5 处调用 | +12 行 |
| `ConfigDrawer.vue` | 1 个勾选项 + 2 处状态同步 + 1 条样式 | +18 行 |

无新增依赖、无构建配置改动、无后端改动。

---

# 附录

## 附录 A · 边界与异常

| 场景 | 行为 |
| --- | --- |
| 浏览器模式（`npm run dev`，无 `electronAPI`） | `notifyTaskDone` 直接 return，静默跳过，不报错 |
| `settings.json` 不存在 / JSON 损坏 | `normalizeSettings(null)`，提醒默认开启 |
| 用户关闭开关 | 主进程读到 `false`，后续不再提醒 |
| **在跳动过程中关闭开关** | 下次续跳触发时检测到 `false` → `stopAttentionLoop()`，剩余跳动立刻停止 |
| 应用在前台 | 直接返回，不调系统 API |
| 窗口最小化 | `isFocused()` 为 false → 正常跳 3 次 |
| 跳动中途用户回窗口 | `focus` → `cancelTaskAttention()` → 清定时器 + cancelBounce |
| 跳动中途窗口被关闭 | `closed` → `cancelTaskAttention()`；`scheduleNextPulse` 内也会因 `isDestroyed()` 自终止（双保险） |
| 3 秒内多次收尾 | 合并，只跳一轮 3 次 |
| 新提醒遇上旧节奏未跳完 | 先 `stopAttentionLoop()`，重新跳 3 次，不会叠加成 6 次 |
| 续跳定时器与进程退出 | 已 `unref()`，不阻止进程退出 |
| Windows 上"闪了停不下来" | `focus` 与 `closed` 都显式 `flashFrame(false)` |
| `app.dock` 不存在（非 macOS） | 走 `flashFrame` 分支，不会访问 `app.dock` |
| 通知权限 / 签名 | 本方案**不碰** `Notification`，完全不受影响 |
| **切换到别的会话后完成** | ⚠️ 前端流已 abort，不会提醒（§3.1.3 已知限制） |

## 附录 B · 验收清单

### B.1 macOS 功能验收

1. 启动应用，确认 `~/.agentforge-desktop/conf/settings.json` 含 `"taskCompletionReminder": true`。
2. **保持应用在前台**发任务：结束时 Dock **不应**跳动。
3. 切到别的应用再发任务：结束时 Dock **连续跳 3 下**，总跨度约 4.2 秒，每两下之间有明显停顿。
   - **判定标准**：能明确数出"三下"，而非糊成一次长抖动。
4. **跳动过程中立刻切回应用**：剩余跳动应立即停止，不会再跳第 2、3 下。
5. 连续快速完成多轮（< 3 秒间隔）：只跳一轮 3 下，不是 6 下。
6. 设置中取消勾选「任务完成提醒」并保存，重复第 3 步：不跳。
7. 重新勾选并保存（不重启）：立即生效。
8. 删除 `conf/settings.json` 后重启：设置页仍默认勾选，提醒可用。

> 若第 3 步数不出三下，优先检查 `REMINDER_BOUNCE_INTERVAL_MS` 是否被改成 ≤ 1000ms。

### B.2 Windows 功能验收

1. 窗口最小化后任务完成：任务栏图标闪烁。
2. 点击任务栏回窗口：闪烁立即停止。
3. 应用在前台时：不闪。

### B.3 回归验收

1. 取消/停止任务（`cancelled`）：不提醒。
2. 等待人工审批（`waiting`）：不提醒（本阶段）。
3. 正常对话渲染、流式输出、会话切换、断线续流：行为与改动前一致。
4. 浏览器模式：控制台无报错。

## 附录 C · 平台支持矩阵

### C.1 结论

> **决策：采用路径 A**（Windows ARM 设备运行 x64 版本，依赖系统 x64 仿真）。
> 本次交付**不改打包脚本、不升级后端 Java 版本**；提醒代码在四个组合上完全一致。

| 目标平台 | 提醒方式 | 可行性 | 需新增前端代码 | 需新增打包目标 |
| --- | --- | --- | --- | --- |
| macOS arm64 | Dock 跳动 | ✅ 已实测 | 0（同一套代码） | 无 |
| macOS x64 | Dock 跳动 | ✅ 平台级 API | 0（同一套代码） | 无 |
| Windows x64 | 任务栏闪烁 | ✅ `flashFrame` 全平台 | 0（同一套代码） | 无 |
| Windows arm64 | 任务栏闪烁 | ✅ 以 x64 运行 | 0（同一套代码） | 需确认分发策略 |

**提醒能力是"平台级"能力，不是"架构级"能力。** 打包矩阵覆盖平台，提醒就自动可用。

### C.2 为什么不需要按架构分支

| 事实 | 依据 |
| --- | --- |
| Electron 原生能力按**操作系统**分实现 | `native_window_mac.mm` / `native_window_views.cc` 按平台编译，arm64 与 x64 共用同一份实现 |
| `Dock` 类整体标注 `@platform darwin` | `electron.d.ts` 中 `bounce` / `cancelBounce` / `setBadge` 均无 `@platform arm64` 之类标注 |
| `flashFrame` 无平台注解 | 全平台 API |

### C.3 Windows arm64 的真实情况

如果要做**真正原生**的 Windows ARM64 包，当前不可行，卡点在后端运行时：

| 卡点 | 事实 |
| --- | --- |
| 后端锁 Java 8 | `pom.xml`：`<java.version>1.8</java.version>`、`source/target=8` |
| **Windows ARM64 无 Java 8** | Adoptium `JDK8+windows+aarch64` 返回 `[]`；Zulu `java_version=8&arch=aarch64` 返回 `[]`；Microsoft `jdk-8-windows-aarch64.zip` 不存在（只有 11/17/21 有 ARM64 包） |
| 现有打包脚本只产 x64 | `package-desktop.mjs` 的 `targets` 仅 `mac-arm64` / `mac-x64` / `win-x64` |

已具备的环节：Electron 有 `win32-arm64` 产物（112 MB，已探测确认）、electron-builder 的 NSIS 支持 `APP_ARM64`、`sqlite-jdbc-3.46.1.3` 已含 `Windows/aarch64/sqlitejdbc.dll`。

**唯一硬卡点就是"内置 Java 8 ARM64 运行时不存在"。** 若未来要出原生包，前置条件是后端升级到 Java 21+，建议单独立项。

### C.4 平台验收补充

| 平台 | 额外验收点 |
| --- | --- |
| macOS x64（Intel） | 跑 B.1 全量；重点确认 Dock 跳动与"前台不提醒" |
| Windows x64 | 跑 B.2 全量；重点确认"闪了能停" |
| Windows arm64（跑 x64 包） | 跑 B.2 全量；额外确认应用能正常启动（说明 x64 仿真生效） |

## 附录 D · 风险提示

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 跳动次数调得过高（如 10 次） | 变成骚扰，用户可能关掉开关 | 默认 3 次、总跨度约 4.2 秒；常量集中在模块顶部 |
| 间隔调到 ≤ 1000ms | 三次糊成一次长抖动，比不做更差 | 代码注释与本文档均标注"必须 > 单次寿命"；B.1-3 有明确判定标准 |
| 续跳定时器泄漏 | 应用退出被延迟 / 后台空转 | `timer.unref()` + `stopAttentionLoop()` 在 focus/closed/新提醒三处清理 |
| 两轮提醒节奏叠加 | 用户看到 6 下而不自知 | 新提醒进入时先 `stopAttentionLoop()` |
| **切换会话后完成不提醒** | 用户感知为"漏提醒" | 已知架构限制（§3.1.3），P1 解决 |
| 同一时刻多个会话完成 | 只提醒一轮 | 3 秒合并；P2 用角标表达"共 N 个完成" |
| 主进程频繁读 `settings.json` | 性能 | 单次提醒最多读一次小文件，可忽略 |
| `bounce` 返回 `-1`（应用仍活跃） | 图标不跳 | 已用 `isFocused()` 预判；`-1` 也按失败处理并重置节流 |

## 附录 E · 后续演进

| 阶段 | 内容 | 依赖 |
| --- | --- | --- |
| **P1** | **解除会话切换限制**：后台继续消费非当前会话的流，完成时照常提醒（§3.1.3 方案一） | 需重构流消费生命周期，维护按 `sessionId` 索引的后台任务表 |
| **P1** | 等待审批（`interrupt_requested`）用 `critical` 级别提醒 | 复用 `applyInterrupt` |
| **P2** | 点击提醒回到对应会话（`Notification.click` 或 Dock 激活后 IPC 切会话） | 需先引入通知通道 |
| **P2** | Dock 未读数 / Windows `setOverlayIcon`（注意 `setBadgeCount` 在 Windows 不存在） | 需先做未读计数模型 |
| **P3** | 系统通知（`Notification`）+ macOS 打包签名验证 | 依赖签名链路稳定 |
| **P4** | 内置终端 + BEL / OSC 9 / OSC 133 解析 | `node-pty` + `xterm.js` |

每一阶段都只是在 `requestTaskAttention()` 之后追加通道，**不需要改渲染层业务分支结构**——这是把判断逻辑放在主进程的价值。

## 附录 F · 涉及文件清单

```text
agentforge-desktop-ui/
├── electron/
│   ├── main.mjs                     # 设置规范化、6 个提醒函数、2 个 IPC、窗口引用提升
│   └── preload.cjs                  # 新增 notifyTaskDone / cancelTaskAttention
└── src/
    ├── components/
    │   └── ConfigDrawer.vue         # 系统配置新增「任务完成提醒」开关
    └── pages/workbench/
        └── ChatWorkbench.vue        # 5 处触发点 + notifyTaskDone 统一入口
```

后端 `agentforge-desktop-server`、数据库、SSE 事件协议：**零改动**。
