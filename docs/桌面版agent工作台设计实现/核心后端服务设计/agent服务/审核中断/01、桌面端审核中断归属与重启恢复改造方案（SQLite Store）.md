# 桌面端审核中断归属与重启恢复改造方案（SQLite Store）

> 版本：方案评审稿 v2（对齐 1.0.2-SNAPSHOT）
> 目标仓库：`AgentForge-Desktop`
> 上游基线：`AgentForge` `1.0.2-SNAPSHOT`（SPI `save(snapshot, context)` + `cancelPendingByMemoryId`）
> 对标设计：`AgentForge` 服务端《HITL 中断归属与持久化恢复优化》（1.0.2-SNAPSHOT 成果）
> 关联文档：`核心后端服务设计/AgentForge-Desktop对话交互、HITL与停止实现详解.md`

---

## 1. 要解决的问题

桌面端现状和上游服务端改造前的问题**完全同构**，都是"双数据源 + 归属依赖进程内存"：

| 现状问题 | 桌面端具体表现 |
| --- | --- |
| 双数据源 | `SqliteAgentInterruptStore`（SQLite 快照）与 `DesktopInterruptRuntimeRegistry`（内存）同时维护批次 |
| 双状态源 | 批次状态既在 `ai_agent_interrupt_snapshot.status`，又在 `BatchContext.status`（`AtomicReference`） |
| 内存批次中间层 | `DesktopInterruptRuntimeRegistry.batches` 用 `BatchContext` 再复制一份 `AgentSuspendInfo` / 消息 / 状态；事件只能从内存批次构造，重启后彻底丢失 |
| 归属依赖 JVM | 归属信息（`memoryId / sessionId / sessionPk / aiMessageId / executionId / HarnessAgent`）只存在于 Registry 的 `RuntimeContext` |
| 重启即失效 | Registry 是 `ConcurrentHashMap`，进程重启后 `requireBatch` 直接抛 `INTERRUPT_BATCH_NOT_FOUND`，用户点"通过"必然失败 |
| Service 重复 claim | Controller/Service 先 `registry.claim`，Framework 内部再走 Store `claim`，两次状态机并存 |

代码层面的直接证据：

- `modules/chat/service/ChatTaskServiceImpl.java:208`：`interruptRegistry.requireBatch(...)` 是恢复的第一步，重启后必抛异常。
- `modules/chat/service/ChatTaskServiceImpl.java:234`：归属信息只能靠 `interruptRegistry.register(...)` 在运行时注册。
- `modules/chat/middleware/ServiceStreamingMiddleware.java:120`：`interruptRegistry.suspend(...)` 依赖 `register` 过的 `RuntimeContext`，没有就抛 `HITL_RUNTIME_NOT_FOUND`。
- `modules/chat/interrupt/DesktopInterruptRuntimeRegistry.java:19`：注释已明确"进程重启后不尝试伪恢复，未完成批次按过期处理"。

**目标**：中断批次只有一个数据源（SQLite），归属信息写进快照，重启后仍可提交审批并继续 ReAct。

---

## 2. 结论与最终架构

> **SQLite Store 是中断批次的唯一数据源；桌面端 Service 不再维护 HITL 运行期注册表。**

```text
用户提交 batchId + responses
  -> SqliteAgentInterruptStore.get(batchId)              # 唯一数据源
  -> contextAccessor.requireForHitl(snapshot)            # 归属上下文（snapshot_json.contextAttributes）
  -> 校验 messageId 与 sessionId 归属
  -> 从会话记录解析 aid / modelId / workspacePath / permissionMode
  -> AgentRuntimeService.getOrCreate(...)                # 已缓存复用，重启则重建
  -> 重建 SessionLifecycleMiddleware / Streaming 运行态绑定
  -> HarnessAgent.resumeInterruptStream(command)
  -> Framework -> PermissionInterruptModule.resolve -> store.claim(batchId)   # 唯一 CAS
  -> Framework -> SqliteChatMemoryStore 恢复记忆 -> 执行冻结工具 -> 继续 ReAct
  -> Framework -> store.complete(batchId)
```

| 组件 | 改造后职责 |
| --- | --- |
| `SqliteAgentInterruptStore` | 保存/查询/claim/complete/取消 中断快照；归属写入 `snapshot_json.contextAttributes` |
| `DesktopAgentContextAccessor` | 对齐 `ServiceAgentContextAccessor`：读写 `extensions`、快照 `contextAttributes`，fail-closed 必填校验 |
| `ChatTaskServiceImpl` | 校验归属、重建 Agent 与运行态、提交恢复，仅做编排 |
| `ServiceStreamingMiddleware` | 只转换和发送 `requested / resuming / resumed / failed / cancelled` 事件，不持有批次 |
| `AgentRuntimeService` | 通过 `getOrCreate` 提供"有缓存复用、无缓存重建"语义 |
| `DesktopInterruptRuntimeRegistry` | **删除**（含 `batches` / `BatchContext` / `RuntimeContext`） |

### 2.1 彻底删除 `batches` 中间层（与 agentforge-service 对齐）

上游 `agentforge-service` 的最终落地是**直接删除** `AgentInterruptRuntimeRegistry`：Store 是唯一批次源，`ServiceStreamingMiddleware` 只做事件转换、完全不持有批次（见《2、HITL中断归属与持久化恢复优化改造方案》第五节“不读写 Store，不维护批次”）。桌面端应保持同一取舍，而不是“保留 Registry、只删 `runtimes` 字段”。

`batches` 里缓存的 `BatchContext`（`AgentSuspendInfo` + `aiMessageId` + `sessionId` + `AtomicReference<Status>`）本质上是 SQLite 快照的一份**冗余副本**，正是“双数据源 / 双状态源”的残留；它唯一的额外价值是给事件构造提供 `interrupts` 与状态，而这两者都能从回调入参直接得到。

`batches` 原承担的职责与替代关系：

| `DesktopInterruptRuntimeRegistry` 现有能力 | 删除后替代 |
| --- | --- |
| `register(...)` 保存 `HarnessAgent` | 删除；恢复时 `AgentRuntimeService.getOrCreate` 按归属四要素重建 |
| `suspend(memoryId, suspendInfo)` 建 `BatchContext` | 删除；`onInterruptRequested` 直接用回调入参 `suspendInfo` + `chatContext` 构造事件，不落内存 |
| `requireBatch(batchId)` | 删除；恢复侧一律 `store.get(batchId)` |
| `claim(batchId)`（内存 CAS） | 删除；只保留 Framework 内部 `store.claim` 这一处 CAS |
| `completed` / `failed`（改内存状态） | 删除；成功由 Framework `store.complete`，失败沿 `InterruptResumeContext.getSnapshot()` 构造事件 |
| `cancelByMemoryId(memoryId)` | 换成 `store.cancelPendingByMemoryId(memoryId)` |
| `release(memoryId)` | 删除；会话输出只保留 `streamingMiddleware.unbind(memoryId)` |

关键点：**事件内容不需要再从内存批次取**。

- `requested`：`onInterruptRequested(step, suspendInfo, chatContext)` 的 `suspendInfo` 自带 `batchId` / `interrupts`，`chatContext` 提供 `memoryId` 与归属 `extensions`。
- `resuming / resumed / failed`：Framework 回调的 `InterruptResumeContext.getSnapshot()` 已含 `batchId`、`interrupts`、`memoryId`、`step` 与 `contextAttributes`，状态由回调类型本身决定。
- `cancelled`：由 `cancelPendingByMemoryId` 返回的 `AgentInterruptSnapshot` 提供。

因此把 `BatchContext.eventContent()` 抽成**无状态的 `DesktopInterruptEventContentFactory`**（对齐上游 `AgentInterruptEventContentFactory`）即可，中间件本身不再有 `Map`、不再有 `AtomicReference`：

```java
// ServiceStreamingMiddleware：无批次、只转换事件
@Override
public void onInterruptRequested(int step, AgentSuspendInfo suspendInfo, AgentChatContext ctx) {
    StreamEventResponse event =
            InterruptEventProcessor.requested(eventContentFactory.requested(suspendInfo, ctx));
    publish(ctx.getMemoryId(), event);
    lifecycleMiddleware.markWaitingForHitl(ctx.getMemoryId(), event);
}

@Override
public void onBeforeInterruptResume(InterruptResumeContext rc, AgentChatContext ctx) {
    StreamEventResponse event =
            InterruptEventProcessor.resuming(
                    eventContentFactory.snapshot(rc.getSnapshot(), "RESUMING"));
    publish(ctx.getMemoryId(), event);
    lifecycleMiddleware.markResuming(ctx.getMemoryId(), event);
}
```

`ServiceStreamingMiddleware` 中保留的 `sessions`（`SessionContext`）是 SSE 输出上下文，属于运行期连接管理，和中断批次无关，应当保留；被删除的只有与批次状态相关的 `batches` 与 `BatchContext`。

`interruptResumeFailed(memoryId, batchId, error)` 同步改为接收 `AgentInterruptSnapshot`（Service 已从 `store.get` 取得），避免中间件为拼事件而回读 Store，保持“中间件不读写 Store”的边界。

---

## 3. 与上游 1.0.2 对齐（升级基线，复用 contextAttributes）

上游 `agentforge-service` 的落地方式：归属上下文放进框架快照（`AgentInterruptSnapshot.contextAttributes`），由 `AgentInterruptSnapshotCodec` 序列化进 `snapshot_json`，**不新增任何数据库列**，`schema_version` 保持 `1`。框架 SPI 为：

```java
// 1.0.2-SNAPSHOT
void save(AgentInterruptSnapshot snapshot, AgentChatContext context);
AgentInterruptSnapshot get(String batchId);
void claim(String batchId);
void complete(String batchId);
AgentInterruptSnapshot cancelPendingByMemoryId(Object memoryId);
```

桌面端当前基线 `1.0.1-SNAPSHOT` 不含这些能力（`AgentInterruptSnapshot` 无 `contextAttributes`，`InterruptBatchStatus` 无 `CANCELLED`，SPI 只有 `save(snapshot)`）。本方案**把桌面端依赖升级到 `1.0.2-SNAPSHOT`**，直接复用上游对象与 SPI，而不是在桌面端另造 `attribution_json` 列或旁路 Binder：

| 能力 | 1.0.2 提供 | 桌面采用 |
| --- | --- | --- |
| 归属承载 | `AgentInterruptSnapshot.contextAttributes` | 直接复用，写入 `snapshot_json` |
| 落库取上下文 | `save(snapshot, context)` | 直接实现 |
| 取消待审批 | `cancelPendingByMemoryId(memoryId)` | 直接实现 |
| 状态枚举 | `PENDING / RESUMING / COMPLETED / CANCELLED` | 直接复用 |

### 3.1 归属在执行上下文绑定（同构 `ServiceAgentContextAccessor`）

`PermissionInterruptModule.preflight` 落库时把 `AgentChatContext` 交给 Store。桌面端只需在执行入口把归属字段写进 `AgentRequest.extensions`，框架 `BaseAgent` 会原样复制到 `AgentChatContext.extensions`，`save` 时由 `DesktopAgentContextAccessor` 读出。

`DesktopAgentExecutionContext` 只保留桌面恢复真正用到的字段（其余一律不放）：`aiMessageId`、`sessionId`、`sessionPk`、`aid`、`modelId`、`workspacePath`、`permissionMode`；`executionId` 用快照列 `source_execution_id`，`memoryId` 用快照列 `memory_id`，都不重复写进 `contextAttributes`。对应 key 统一定义在 `DesktopAgentContextAccessor`：`agentforge.execution.aiMessageId / sessionId / sessionPk / aid / modelId / workspacePath / permissionMode`。

```java
// 发起执行时：写入归属，随 request.extensions 进入 AgentChatContext
accessor.write(extensions, executionContext);
AgentRequest request = AgentRequest.builder()
        .memoryId(init.getSessionId())
        .executionId(executionId)
        .question(question)
        .extensions(extensions)
        .build();
```

```java
// SqliteAgentInterruptStore：归属与快照同一次写入，无“先落库再补写”的空窗
@Override
public void save(AgentInterruptSnapshot snapshot, AgentChatContext context) {
    DesktopAgentExecutionContext executionContext = accessor.requireForHitl(context);
    persist(snapshot.withContextAttributes(accessor.toSnapshotAttributes(executionContext)));
}
```

"同线程同步执行"已核实：`ReActTokenStream.start()` 在调用线程内推进 think/act 循环，`request.extensions` 与 `preflight -> save(snapshot, context)` 处于同一段同步执行内，归属随上下文自然可用，无需跨线程传递。

### 3.2 fail-closed 规则

`accessor.requireForHitl` 在必填字段缺失时直接抛 `HITL_CONTEXT_ATTRIBUTE_MISSING: <key>`，**不写入半截快照**，与上游 `ServiceAgentContextAccessor.requireForHitl` 一致。这样宁可本轮中断失败并提示，也不留下"能查到、但恢复时无法归属"的脏批次。

---

## 4. 数据模型

### 4.1 不改表结构

`ai_agent_interrupt_snapshot` 与 `20261115000000_v1.0.x.sql` 一致，**不新增列**：归属写入 `snapshot_json.contextAttributes`，`schema_version` 仍为 `1`（与上游 `AgentInterruptSnapshotCodec.CURRENT_SCHEMA_VERSION = 1` 一致）。

> 归属恢复查询按 `(memory_id, status)` 组合，索引已存在于 `20261116000000_desktop_message_append.sql:31-32`，无需新增。
> `DesktopSchemaMigrator` 本轮不新增脚本。

### 4.2 归属载体 `snapshot_json.contextAttributes`

```json
{
  "schemaVersion": 1,
  "contextAttributes": {
    "agentforge.execution.aiMessageId": "9012",
    "agentforge.execution.sessionId": "2001",
    "agentforge.execution.sessionPk": "2001",
    "agentforge.execution.aid": "agt_default",
    "agentforge.execution.modelId": "2",
    "agentforge.execution.workspacePath": "/Users/edy/projects/demo",
    "agentforge.execution.permissionMode": "DEFAULT"
  },
  "toolRequests": [],
  "interrupts": [],
  "denials": []
}
```

> 归属上下文与 `toolRequests / interrupts / denials` 一起由 `AgentInterruptSnapshotCodec` 写入 `snapshot_json`，不新增 `pojo` 属性，因此无需增量 SQL。

字段来源与用途：

| 字段 | 来源 | 恢复时用途 |
| --- | --- | --- |
| `aiMessageId` | `SessionInitResult` | 定位消息、续流、状态收口 |
| `sessionId` | `SessionInitResult` | 对外会话标识，同时是 `memoryId` |
| `sessionPk` | `SessionInitResult` | 生命周期中间件的 `ai_agent_session.id` |
| `aid` | 请求 | `configStore.findAgent(aid)` |
| `modelId` | `configStore.effectiveModelId` | `configStore.findModel(modelId)` |
| `workspacePath` | `WorkspaceBindingService.Binding` | `LocalRuntime` 工作区还原 |
| `permissionMode` | `DesktopPermissionModule.resolveMode` | 重建 Agent 时保持同一权限模式 |

> `sourceExecutionId` 已由 `source_execution_id` 列承载，不重复放入 `contextAttributes`。
> 与上游一致：**只持久化必要字段**，不整体保存 `extensions`，避免把密钥、运行对象写进快照。

### 4.3 为什么复用 `contextAttributes` 而不是新增列

新增 `attribution_json` 会让桌面端与上游出现两套归属结构，恢复时还得再写一份解析；而 `contextAttributes` 本就是 1.0.2 为“Store 落库时携带业务上下文”设计的官方承载位。复用它可让序列化结构、fail-closed 规则、恢复读取方式与 `agentforge-service` 完全一致，后续上游演进时无需再迁移。

---

## 5. Store 改造要点

### 5.1 复用 1.0.2 SPI（归属随 save 写入）

```java
/** 保存快照：归属与快照同一次写入；必填字段缺失即 fail-closed。 */
@Override
public void save(AgentInterruptSnapshot snapshot, AgentChatContext context);

/** 取消指定 memoryId 下 PENDING 批次（1.0.2 SPI 已提供）。 */
@Override
public AgentInterruptSnapshot cancelPendingByMemoryId(Object memoryId);
```

不单独提供 `readAttribution`：恢复时对 `store.get(batchId)` 返回的快照调用 `contextAccessor.requireForHitl(snapshot)` 读取归属，与上游 `MysqlAgentInterruptStore` 一致；`save` 内 `snapshot.withContextAttributes(accessor.toSnapshotAttributes(...))` 后由 Codec 写入 `snapshot_json`。

`DesktopInterruptRuntimeRegistry` 的 `suspend / claim / completed / failed / cancelByMemoryId` 不再有任何对应实现：提交恢复时由 Framework 内部 `store.claim` / `store.complete` 推进状态，等待期取消由 `cancelPendingByMemoryId` 承担；内存批次层整体消失。

### 5.2 claim 仍然是唯一 CAS

```java
// 1.0.2 框架内部：PermissionInterruptModule.resolve -> store.claim(batchId)
if (mapper.claim(batchId, now()) == 1) return;   // PENDING -> RESUMING
```

- Service **不再调用** `claim`，彻底消除"二次状态机"。
- `claim` 冲突分支保留语义化错误：`COMPLETED` / `RESUMING` / 其他状态分别给出明确异常，便于前端区分"已处理"与"正在处理"。

### 5.3 取消链路

```text
stop(messageId) 且消息状态为 WAITING_HITL
  -> store.cancelPendingByMemoryId(sessionId)
       UPDATE ... SET status='CANCELLED' WHERE batch_id=? AND status='PENDING'
  -> 成功：发送 interrupt_cancelled 事件 + 收口消息为 CANCELLED
  -> 无待审批批次：走原有 cancel / timeoutCancel 链路
```

`CANCELLED` 是 1.0.2 原生枚举（`InterruptBatchStatus.CANCELLED`）：`cancelPendingByMemoryId` 条件更新后由 Codec 读回，框架侧拿到的是终态，不会再次进入待恢复。

---

## 6. Service 恢复编排（改造后）

```java
public AgentInterruptResumeVo resumeInterrupt(AgentInterruptResumeReq request) {
    String batchId = requireBatchId(request);

    // 1. 唯一数据源 + 归属上下文（snapshot_json.contextAttributes）
    AgentInterruptSnapshot snapshot = interruptStore.get(batchId);
    DesktopAgentExecutionContext attribution = contextAccessor.requireForHitl(snapshot);

    // 2. 归属校验（桌面端单用户，校验消息与会话仍然有效）
    AgentSessionMessagePojo message = requireOwnedAiMessage(attribution.getAiMessageId());
    verifySession(message, attribution.getSessionId());

    // 3. 解析配置并获取/重建 Agent
    DesktopConfigStore.AgentConfig agent = requireAgent(attribution.getAid());
    DesktopConfigStore.ModelConfig model = requireModel(attribution.getModelId());
    HarnessAgent runtime = runtimeService.getOrCreate(
            agent, model, attribution.getWorkspacePath(), attribution.getPermissionMode());

    // 4. 重建运行态绑定（重启后 Memory 中的 state 为空，必须显式恢复）
    streamingMiddleware.bind(attribution.getSessionId(), eventFactory.createSink(message.getId()));
    lifecycleMiddleware.registerRun(
            attribution.getSessionId(), attribution.getSessionId(),
            attribution.getSessionPk(), message.getId());

    // 5. 构造恢复命令并提交；继续原执行链路用 snapshot.getSourceExecutionId()
    InterruptBatchResumeCommand command = toResumeCommand(snapshot, request);
    executor.execute(
            () -> resume(runtime, attribution, snapshot.getSourceExecutionId(), command));
    return toResumeVo(batchId, message.getId());
}
```

关键点：

1. **`toResumeCommand` 基于快照**。读 `snapshot.getInterrupts()`，重启后天然可用。
2. **`lifecycleMiddleware.registerRun` 必须重放**。`SessionLifecycleMiddleware.states` 是内存态，重启后为空，不重放则 `state(chatContext)` 返回 `null`，恢复期间的 `resuming / resumed` 事件不会落库，消息状态也不会推进。
3. **执行链路沿用 `sourceExecutionId`**：它已由 `source_execution_id` 列承载，无需放进 `contextAttributes`。
4. **不再调用 `interruptRegistry.register(...)`**：`AgentRuntimeService.getOrCreate` 重建 Agent，`bind` / `registerRun` 重建输出与生命周期，批次状态只由 Store 推进；没有内存批次可依赖。

---

## 7. 运行态重建的边界

重启后**不需要**伪造"原 Java 对象"，但有四类运行态必须重建：

| 运行态 | 所在位置 | 重建方式 |
| --- | --- | --- |
| `HarnessAgent` | `AgentRuntimeService.cache` | `getOrCreate` 无缓存时按配置重建 |
| `SessionLifecycleMiddleware.RunState` | 内存 `ConcurrentHashMap` | `registerRun` 重放 |
| `ServiceStreamingMiddleware.SessionContext` | 内存 `ConcurrentHashMap` | `bind` 重新挂载 `StreamEventSink` |
| 对话记忆 | SQLite `ai_agent_memory` | `SqliteChatMemoryStore` 按 `memoryId` 读回 |

`AgentRuntimeService.getOrCreate` 的缓存 key 是 `aid::modelId::mode::workspaceRoot`（`AgentRuntimeService.java:107`），因此只要归属里保存了这四要素，重建结果与中断当时的 Agent 形态一致。

> 与上游同一取舍：**使用恢复时的 Agent 配置**。若后续要求"固化中断当时的配置版本"，再在 `contextAttributes` 增加配置版本标识。

---

## 8. 需要删除/收敛的代码

| 文件 | 动作 |
| --- | --- |
| `modules/chat/interrupt/DesktopInterruptRuntimeRegistry.java` | 删除；`batches` / `BatchContext` / `RuntimeContext` 全部移除（`ServiceStreamingMiddleware.sessions` 为 SSE 输出，保留） |
| `modules/chat/interrupt/AgentInterruptSnapshotCodec.java` | `StoredSnapshot` 增加 `contextAttributes`（encode/decode），对齐上游 Codec；`schema_version` 保持 `1` |
| 新增 `modules/chat/context/DesktopAgentContextAccessor.java` | 对齐 `ServiceAgentContextAccessor`：`write/read/requireForHitl/toSnapshotAttributes`，缺失必填字段抛 `HITL_CONTEXT_ATTRIBUTE_MISSING` |
| 新增 `modules/chat/interrupt/DesktopInterruptEventContentFactory.java` | 对齐 `AgentInterruptEventContentFactory`：从 `suspendInfo` / `snapshot` 无状态构造事件内容 |
| `config/DesktopAgentConfiguration.java:26-29` | 删除 `desktopInterruptRuntimeRegistry` Bean |
| `config/DesktopAgentConfiguration.java:41-45` | `ServiceStreamingMiddleware` 构造参数收敛为 `(SessionLifecycleMiddleware)`；事件内容由回调入参构造，不注入 Store / Registry |
| `modules/chat/enums/SessionMessageStatusEnum`（仅新增场景） | 沿用已有 `WAITING_HITL / RESUMING / CANCELLED`，不新增 |
| `modules/chat/controller/ChatController.java:216-221` | 独立 SSE 入口的注册表调用改为 Store + 重建编排（或标注为废弃入口） |
| `test/.../SqliteAgentInterruptStoreTest.java` | 补 `contextAttributes` 读写、取消、重启恢复用例 |

`ChatController` 与 `ChatTaskController` 目前存在两套恢复入口（旧 SSE 直连 + 任务化）。本轮以任务化 `/api/chat/tasks/interrupts/resume` 为主链路，旧入口同步改造，避免两套语义各写一份。

---

## 9. 重启恢复的完整时序

```text
[中断时]
ReActAgent.act
  -> PermissionInterruptModule.preflight(step, tools, ctx)
       -> SqliteAgentInterruptStore.save(snapshot, ctx)
            -> accessor.requireForHitl(ctx) -> snapshot.withContextAttributes(...)
            -> Codec 写入 snapshot_json.contextAttributes
       -> INSERT ai_agent_interrupt_snapshot(status=PENDING)
  -> triggerOnInterruptRequested -> 推送 interrupt_requested，消息置 WAITING_HITL
  -> StepResult.toSuspended，Agent 线程退出（进程可安全关闭）

[桌面端重启]
Spring 启动 -> DesktopSchemaMigrator 幂等执行（本轮无新增 SQL）
用户打开会话 -> 历史 messages.events 中已有 interrupt_card（status=PENDING）
用户点击"通过"

[恢复时]
POST /api/chat/tasks/interrupts/resume {batchId, responses}
  -> store.get(batchId)                          # PENDING
  -> contextAccessor.requireForHitl(snapshot)    # 从 contextAttributes 读归属
  -> 校验消息与会话仍有效
  -> runtimeService.getOrCreate(...)             # 重建 HarnessAgent
  -> bind / registerRun                          # 重建运行态
  -> executor -> runtimeService.resumeInterruptStream(agent, command)
       -> PermissionInterruptModule.resolve
            -> store.claim(batchId)         # PENDING -> RESUMING（唯一 CAS）
       -> ChatMemory 读回上下文
       -> 执行/拒绝冻结工具，写 Tool Result
       -> store.complete(batchId)           # -> COMPLETED
       -> 继续 ReAct，直到本轮结束
  -> 生命周期中间件收口消息为 SUCCESS
```

---

## 10. 兼容与数据库影响

- **不新增字段**：`ai_agent_interrupt_snapshot` 表结构不变，归属写入 `snapshot_json.contextAttributes`。
- **历史批次处理**：旧快照 `contextAttributes` 为空，`requireForHitl` 抛 `HITL_CONTEXT_ATTRIBUTE_MISSING`，前端将该卡片置为 `FAILED` 并提示"该审批来自旧版本，请在会话中重新发起"。不尝试猜测归属，避免误继续。
- **`schema_version`**：保持 `1`（与上游 `CURRENT_SCHEMA_VERSION = 1` 一致），`contextAttributes` 为同一 JSON 内的新增属性。
- **状态枚举**：直接使用 1.0.2 四态 `PENDING / RESUMING / COMPLETED / CANCELLED`，无需映射。

---

## 11. 验证计划

| 层级 | 用例 | 期望 |
| --- | --- | --- |
| 单测 | `save(snapshot, context)` 缺少必填归属 | 抛 `HITL_CONTEXT_ATTRIBUTE_MISSING`，表内无记录 |
| 单测 | `save` -> `contextAccessor.requireForHitl(snapshot)` | 字段与 `SessionInitResult` / 解析配置逐项一致 |
| 单测 | `cancelPendingByMemoryId` | `PENDING -> CANCELLED`；`RESUMING` 批次不被取消 |
| 单测 | 双次 `claim` | 第二次抛 `being resumed`；`COMPLETED` 后 claim 抛 `already completed` |
| 单测 | 旧批次（`contextAttributes` 为空）恢复 | 抛 `HITL_CONTEXT_ATTRIBUTE_MISSING`，不产生工具副作用 |
| 集成 | 触发 bash 高风险审批 -> 停进程 -> 重启 -> 提交通过 | 工具重新执行，消息最终 `SUCCESS`，批次 `COMPLETED` |
| 集成 | 同上但提交拒绝 | 冻结工具不执行，写入拒绝 Tool Result，ReAct 继续 |
| 集成 | 等待审批中 stop | `CANCELLED` 事件 + 消息收口，无残留 `PENDING` |
| 回归 | 模型/工作区配置在重启后被修改 | 恢复使用新配置，恢复后链路可用（符合"使用恢复时配置"取舍） |

执行方式：

```bash
cd agentforge-desktop-server
mvn -o test -Dtest='SqliteAgentInterruptStoreTest,AgentInterruptSnapshotCodecTest'
```

---

## 12. 本地构建基线

本方案要求桌面端依赖升级到上游 `1.0.2-SNAPSHOT`（即 `AgentForge` 当前源码 HEAD，`<revision>1.0.2-SNAPSHOT</revision>`），需先安装到本机 Maven 仓库：

```text
源码：AgentForge 当前 HEAD（<revision>1.0.2-SNAPSHOT</revision>）
安装：-pl ':agentforge-ai-bom,:agentforge-model-api,:agentforge-model-core,
       :agentforge-model-openai,:agentforge-model-anthropic,:agentforge-model-registry,
       :agentforge-agent-core,:agentforge-harness-agent,:agentforge-service-protocal' -am install
产物：~/softwares/maven/LocalRespority/cloud/changlu/agentforge/*/1.0.2-SNAPSHOT
验证：agentforge-desktop-server 在 JDK 8 下 `mvn -o compile` 通过
```

> 同步把 `agentforge-desktop-server/pom.xml` 的 `agentforge.version` 与 `agentforge-desktop-ui/scripts/lib/maven.mjs` 的 `requiredArtifacts()` 从 `1.0.1-SNAPSHOT` 改为 `1.0.2-SNAPSHOT`。脚本内 `localRepository()` 走 `mvn help:evaluate`，本机实际仓库为自定义路径 `~/softwares/maven/LocalRespority`，打包前需确认该路径可被正确求值，否则会出现"构件已装但脚本判定缺失 -> 触发重建"。

---

## 13. 实施顺序建议

1. 桌面端依赖升级到 `1.0.2-SNAPSHOT`（获得 `AgentInterruptSnapshot.contextAttributes` / 新 SPI / `CANCELLED`）。
2. `DesktopAgentContextAccessor`（对齐 `ServiceAgentContextAccessor`）+ `DesktopInterruptEventContentFactory`。
3. `AgentInterruptSnapshotCodec` 的 `StoredSnapshot` 增加 `contextAttributes`。
4. `SqliteAgentInterruptStore` 实现 `save(snapshot, context)` 与 `cancelPendingByMemoryId`。
5. `ServiceStreamingMiddleware` 去 Registry / 去 `batches`，事件改由回调入参经 `DesktopInterruptEventContentFactory` 无状态构造（不注入 Store）。
6. `ChatTaskServiceImpl` 恢复编排改造 + `toResumeCommand` 基于快照 + `contextAccessor.requireForHitl(snapshot)`。
7. 删除 `DesktopInterruptRuntimeRegistry` 并清理装配。
8. 前端仅在"旧批次恢复失败"时补充 `FAILED` 提示文案。
9. 跑第 11 节验证计划。

---

## 14. 待确认项

| 问题 | 建议 |
| --- | --- |
| 是否同轮把 `ChatController` 旧 SSE 恢复入口一并改造 | 建议一并改，避免两套语义 |
| `contextAttributes` 是否需要加密 | 当前只存 ID / 路径 / 模式，不含密钥，建议不加密 |
| 是否需要在会话打开时主动提示"存在待审批批次" | 建议后续迭代，本轮先保障点击恢复可用 |
| 升级到上游 `1.0.2-SNAPSHOT` 的风险 | 与 `agentforge-service` 同源同版本，能力已具备；按第 12 节先安装再切 pom，回归编译即可 |
