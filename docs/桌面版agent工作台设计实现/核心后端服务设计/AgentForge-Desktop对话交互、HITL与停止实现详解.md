# AgentForge-Desktop 对话交互、HITL 与停止实现详解

> 对应仓库：`AgentForge-Desktop`
> 参考设计：`AgentForge/agentforge-service` 的 HITL、追加消息与二段式停止方案
> 适用范围：当前桌面端单用户、本地 SQLite、单实例运行模式

---

## 1. 能力范围

本会话在桌面端补齐了四类交互能力，默认随 Agent 构建启用，不需要额外配置开关：

| 能力 | 说明 |
| --- | --- |
| AskUser | 模型可调用 `ask_user` 发起单选、多选、文本问题，等待用户回答后继续执行 |
| 权限审核 | `bash` 中删除、提权、磁盘等高风险命令触发审批，支持允许/拒绝 |
| 运行中追加指令 | Agent 执行期间可继续追加要求，在下一个模型调用边界注入 |
| 真停止与超时取消 | 停止接口下发真实 cancel 信号，等待 Agent 收口；超时后用条件更新兜底 |

同时同步了 `agentforge-service` 调试/发布模式的过程记录展示：中断、审批和 AskUser 问答会作为可展开的执行过程条目保留在消息时间线中。

---

## 2. 总体组件

```text
ChatWorkbench.vue
  ├─ InterruptCard / AskUserQuestionStep / PermissionApprovalStep
  ├─ InterruptTraceItem
  ├─ append queue + 追加指令输入
  └─ stop button
        │ HTTP / SSE
        ▼
ChatController
  ├─ POST /api/chat/stream
  ├─ POST /api/chat/interrupts/resume
  ├─ POST /api/chat/append (+ /cancel)
  └─ POST /api/chat/stop
        │
        ▼
AgentRuntimeService (HarnessAgent)
  ├─ PermissionInterruptModule
  │    ├─ AskUserTool
  │    ├─ ShellCommandPermissionRule
  │    └─ SqliteAgentInterruptStore
  ├─ UserMessageAppendMiddleware
  ├─ SessionLifecycleMiddleware
  └─ ServiceStreamingMiddleware
        │
        ▼
SQLite
  ├─ ai_agent_session_message
  ├─ ai_agent_session_message_append
  ├─ ai_agent_interrupt_snapshot
  └─ ai_agent_memory
```

---

## 3. 增量 SQL

桌面端本会话涉及两个增量脚本：

- `20261116000000_desktop_message_append.sql`：追加消息队列表 + HITL 冻结快照表；
- `20261116010000_desktop_message_execution_id.sql`：AI 消息执行 ID 字段。

### 3.1 追加消息队列表

```sql
CREATE TABLE IF NOT EXISTS ai_agent_session_message_append (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    status      INTEGER NOT NULL DEFAULT 1,
    queued_at   DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

短生命周期队列：Agent Run 打开窗口后追加，模型调用边界消费成功即删除；未消费内容在 Run 结束时回填前端输入框。

### 3.2 HITL 冻结快照表

```sql
CREATE TABLE IF NOT EXISTS ai_agent_interrupt_snapshot (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id            TEXT    NOT NULL,
    memory_id           TEXT    NOT NULL,
    source_execution_id TEXT    NOT NULL,
    step                INTEGER NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    schema_version      INTEGER NOT NULL DEFAULT 1,
    snapshot_json       TEXT    NOT NULL,
    version             INTEGER NOT NULL DEFAULT 0,
    claimed_at          DATETIME DEFAULT NULL,
    resolved_at         DATETIME DEFAULT NULL,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (batch_id)
);
```

索引：

```sql
CREATE INDEX IF NOT EXISTS idx_ai_agent_interrupt_snapshot_memory_status
    ON ai_agent_interrupt_snapshot (memory_id, status);

CREATE INDEX IF NOT EXISTS idx_ai_agent_interrupt_snapshot_status_modify
    ON ai_agent_interrupt_snapshot (status, modify_time);
```

### 3.3 消息执行 ID

文件：`agentforge-desktop-server/sql/sqllite/1.0.x/20261116010000_desktop_message_execution_id.sql`

```sql
ALTER TABLE ai_agent_session_message ADD COLUMN execution_id TEXT;
```

`execution_id` 在 `SessionLifecycleMiddleware.onInitComplete` 时从 `AgentChatContext.getExecutionId()` 写入，用于把停止请求精确绑定到某一轮 Agent Run。

---

## 4. AskUser 与权限审核

### 4.1 HarnessAgent 装配

`AgentRuntimeService#build` 为每个 Agent 构建同一个持久化 Store：

```java
HarnessAgent.builder()
    .runtime(runtime)
    .permissionInterruptModule(DesktopPermissionModule.build(agentInterruptStore))
    .middleware(new UserMessageAppendMiddleware(appendService, appendEventListener))
    .middleware(new LoggingIAgentMiddleware())
    .middleware(sessionLifecycleMiddleware)
    .middleware(streamingMiddleware)
    .build();
```

`DesktopPermissionModule` 使用 `PermissionMode.DEFAULT`：

- 启用 `ask_user`；
- 注册 `ShellCommandPermissionRule`；
- 注入单例 `AgentInterruptStore`；
- 未命中规则的工具直接放行。

### 4.2 内置 bash 高风险规则

规则位于：

- `modules/chat/governance/ShellCommandPermissionRule.java`
- `modules/chat/governance/ShellCommandPermissionRuleHandler.java`

命中后返回 `ASK`，进入审批卡片。当前覆盖：

- `rm` / `rmdir` / `sudo` / `su`；
- `shutdown` / `reboot` / `halt` / `poweroff`；
- `mkfs` / `dd` / `fdisk` / `diskutil` / `format`；
- `git reset --hard` / `git clean -f` / 强制 push；
- `find -delete` / `find -exec rm`；
- 递归 `chmod` / `chown`；
- 组合命令、`sh -c`、`xargs`、`env`、`sudo` 等包装形式。

规则按 shell 分隔符拆分简单命令并跳过包装器，不依赖简单字符串包含，降低误判。

### 4.3 事件协议

复用 `agentforge-service-protocal`：

```text
interrupt / interrupt_requested
interrupt / interrupt_resuming
interrupt / interrupt_resumed
interrupt / interrupt_resume_failed
interrupt / interrupt_cancelled
```

requested 事件内容：

```json
{
  "batchId": "batch_xxx",
  "messageId": 12,
  "sessionId": "session-xxx",
  "status": "PENDING",
  "interrupts": [
    {
      "interruptId": "int_xxx",
      "type": "ASK_USER 或 PERMISSION_APPROVAL",
      "toolRequest": {"id": "call_1", "name": "bash", "arguments": "{...}"},
      "payload": {"questions": [...]}
    }
  ]
}
```

### 4.4 恢复接口

`POST /api/chat/interrupts/resume`

请求：

```json
{
  "batchId": "batch_xxx",
  "responses": [
    {
      "interruptId": "int_xxx",
      "action": "APPROVE_ONCE / APPROVE_ALWAYS / REJECT",
      "answers": [
        {"questionId": "q1", "selectedOptionIds": ["a"], "customValue": null}
      ]
    }
  ]
}
```

恢复流程：

1. `DesktopInterruptRuntimeRegistry` 先校验/抢占 Service 批次；
2. Framework `PermissionInterruptModule.resolve` 读取快照并执行 Store CAS；
3. `ReActAgent.applyInterruptBatch` 按原顺序写回 Tool Result 或执行冻结工具；
4. `resumeInterruptStream` 从原消息继续 ReAct；
5. 成功发 `interrupt_resumed`，失败发 `interrupt_resume_failed`。

### 4.5 持久化 Store

组件：

- `AgentInterruptSnapshotPojo`
- `AgentInterruptSnapshotMapper`
- `AgentInterruptSnapshotCodec`
- `SqliteAgentInterruptStore`

语义与 `InMemoryAgentInterruptStore` 对齐：

- 重复 `save` → `interrupt batch already exists`；
- `get` 不存在 → `interrupt batch not found`；
- `claim` 单条 `UPDATE ... WHERE status='PENDING'` 完成 CAS；
- 已完成/正在恢复返回明确异常；
- `complete` 幂等。

Codec 持久化 `toolRequests / interrupts / denials`，不保存可由列字段表达的状态。反序列化执行 fail-closed：

- `schemaVersion` 必须一致；
- 未知状态/中断类型/问题类型直接失败；
- AskUser payload 重建后必须与持久化内容逐字段一致；
- 工具请求在 `toolRequests` 中找不到即视为快照损坏。

边界：快照可跨 Runtime 重建保留，但不承诺跨进程自动恢复执行；Service Registry 与 ChatMemory 仍在内存中。

---

## 5. 运行中追加指令

### 5.1 数据链路

```text
POST /api/chat/append
  → UserMessageAppendService.enqueue
  → ai_agent_session_message_append
  → SSE user_message / append_queued
  → 下一次 beforeModelCall
  → 注入 ChatMemory
  → SSE user_message / append_effective
```

Run 结束仍未消费：

```text
UserMessageAppendMiddleware.onStop
  → closeAndDrain
  → SSE user_message / append_deferred
  → 前端回填输入框
```

### 5.2 接口

```text
POST /api/chat/append
{ "aid": "agt_default", "sessionId": "...", "message": "补充要求" }

POST /api/chat/append/cancel
{ "aid": "agt_default", "sessionId": "...", "appendId": 1 }
```

限制：

- 每个 Run 最多 10 条待生效指令；
- 仅运行中的 Run 可入队；
- 等待 HITL 时前端禁止继续追加，避免绕过确认。

---

## 6. 真停止与超时取消

### 6.1 为什么原实现是假停止

旧实现点击停止只做两件事：

1. 前端立即 `abort()` SSE；
2. 后端/前端本地把消息标记为已取消。

Agent 线程仍在执行，模型请求与工具可能继续运行。SSE 断开不等同于任务停止。

### 6.2 当前流程

```text
POST /api/chat/stop?sessionId=...   （也支持 messageId）
  → 定位活动 AI 消息
  → 终态直接返回
  → WAITING_HITL 先取消中断批次
  → CAS: CREATED/RUNNING/WAITING_HITL → CANCELLING
  → 会话状态 → CANCELLING
  → 读取 message.execution_id
  → 调用 agent.cancel(memoryId, executionId)
  → 轮询等待 onStop(CANCEL)
      ├─ CANCELLED
      ├─ SUCCEEDED / FAILED（自然终态获胜）
      └─ 超时
  → 超时执行条件 UPDATE → TIMEOUT_CANCELLED
```

`agent.cancel()` 只表示取消信号被接受，接口最终返回以数据库消息状态为准。

### 6.3 状态扩展

消息状态：

| 状态 | 编码 |
| --- | ---: |
| CREATED | 1 |
| RUNNING | 2 |
| FAILED | 3 |
| CANCELLED | 4 |
| SUCCEEDED | 5 |
| TIMEOUT_CANCELLED | 6 |
| CANCELLING | 7 |
| WAITING_HITL | 8 |

会话状态新增 `CANCELLING(4)`。

### 6.4 条件更新

标记取消意图：

```sql
UPDATE ai_agent_session_message
SET status = 7, modify_time = ?
WHERE id = ? AND status IN (1, 2, 8) AND is_deleted = 0;
```

正常收口：

```sql
UPDATE ai_agent_session_message
SET status = ?, content = ?, events = ?, elapsed_ms = ?, finished_at = ?, modify_time = ?
WHERE id = ? AND status IN (1, 2, 7, 8) AND is_deleted = 0;
```

超时兜底：

```sql
UPDATE ai_agent_session_message
SET status = 6, elapsed_ms = ?, finished_at = ?, modify_time = ?
WHERE id = ? AND status IN (1, 2, 7, 8) AND is_deleted = 0;
```

晚到的 Agent 回调无法覆盖 `TIMEOUT_CANCELLED`。

### 6.5 返回结构

```json
{
  "messageId": 12,
  "cancelRequested": true,
  "status": 4,
  "statusName": "CANCELLED",
  "timedOut": false
}
```

```json
{
  "messageId": 12,
  "cancelRequested": false,
  "status": 6,
  "statusName": "TIMEOUT_CANCELLED",
  "timedOut": true
}
```

### 6.6 等待配置

`application.yml`：

```yaml
agentforge:
  desktop:
    cancel-wait-timeout-ms: ${AGENTFORGE_CHAT_CANCEL_WAIT_TIMEOUT_MS:15000}
    cancel-poll-interval-ms: ${AGENTFORGE_CHAT_CANCEL_POLL_INTERVAL_MS:200}
```

### 6.7 前端行为

- 点击停止后调用 `/api/chat/stop`，按钮进入“停止中…”，禁止重复提交；
- 不立即 abort SSE，继续接收服务端 `resp_end`；
- 返回 `CANCELLED`：显示“用户已停止”；
- 返回 `TIMEOUT_CANCELLED`：显示“停止超时，任务已强制收口”，并终止本地订阅；
- 返回 `SUCCEEDED/FAILED`：以自然终态为准；
- 接口失败不伪造终态。

---

## 7. 调试/发布模式的过程记录

参考 `agentforge-service-ui` 同步以下组件与结构：

- `InterruptTraceItem.vue`
- `DebugMessage.vue`
- `ElegantMessage.vue`
- `interruptEventReducer.js`

### 7.1 调试模式

执行过程展开后按顺序展示：

```text
思考过程
用户已确认，已恢复执行  已答 3/3 题
  ├─ batchId
  ├─ ASK_USER / PERMISSION_APPROVAL
  ├─ 问题与答案
  └─ 选项 ID（调试模式）
工具调用卡片
```

摘要行显示：

```text
N 段思考 · N 个工具 · N 次询问
```

### 7.2 发布模式

同一份 `interrupt_card` 数据以轻量样式展示，保留确认结果与问答内容，不展示 batchId 与选项 ID。

### 7.3 历史回放

- `interrupt` 事件按 `batchId` 归并生命周期；
- AskUser 答复优先由本地提交快照保留；
- Tool Result 中的结构化 answers 通过 `reduceInterruptAnswers` 还原；
- 与 service 当前实现一致：历史事件未记录审批动作时显示“已处理（历史未记录动作）”。

---

## 8. 关键文件索引

```text
agentforge-desktop-server/src/main/java/cloud/changlu/agentforge/desktop/
  modules/chat/controller/ChatController.java
  modules/chat/controller/UserMessageAppendController.java
  modules/chat/governance/DesktopPermissionModule.java
  modules/chat/governance/ShellCommandPermissionRule.java
  modules/chat/governance/ShellCommandPermissionRuleHandler.java
  modules/chat/interrupt/DesktopInterruptRuntimeRegistry.java
  modules/chat/interrupt/AgentInterruptSnapshotCodec.java
  modules/chat/interrupt/SqliteAgentInterruptStore.java
  modules/chat/append/SqliteUserMessageAppendStore.java
  modules/chat/middleware/ServiceStreamingMiddleware.java
  modules/chat/middleware/SessionLifecycleMiddleware.java
  modules/session/dao/AgentSessionMessageMapper.java
  modules/session/enums/SessionMessageStatusEnum.java

agentforge-desktop-ui/src/
  api/chat.js
  components/InterruptCard.vue
  components/AskUserQuestionStep.vue
  components/PermissionApprovalStep.vue
  components/InterruptTraceItem.vue
  components/AppendedUserMessage.vue
  modes/debug/DebugMessage.vue
  modes/elegant/ElegantMessage.vue
  pages/workbench/ChatWorkbench.vue
  utils/interruptEventReducer.js
  utils/appendEventReducer.js
  utils/sessionReplay.js

agentforge-desktop-server/sql/sqllite/1.0.x/
  20261116000000_desktop_message_append.sql
  20261116010000_desktop_message_execution_id.sql
```

---

## 9. 验证与边界

已验证：

- 权限规则单测：`rm` 审批、安全命令放行、`ask_user` 注册；
- Codec 单测：AskUser/权限快照 round-trip、schema 漂移 fail-closed；
- Store 集成测试：save/get/重复 save/claim/重复 claim/complete；
- 停止条件更新测试：executionId 持久化、CANCELLING、超时 CAS、迟到回调不覆盖；
- 停止接口超时路径：实际启动服务后返回 `TIMEOUT_CANCELLED`；
- 前端 `npm run build`、后端 `mvn clean test package`。

当前边界：

1. 单实例、内存 Registry；多实例需要粘性路由或取消命令通道；
2. 不承诺跨进程自动恢复 HITL 执行；
3. `TIMEOUT_CANCELLED` 不代表模型请求一定已物理终止；
4. 恢复期间再次中断会产生新 `batchId`；
5. 历史回放中的审批动作依赖事件是否携带，缺失时按 service 现有行为降级展示。