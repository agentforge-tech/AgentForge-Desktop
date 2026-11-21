# AgentForge Desktop 跨工作空间访问授权设计

> 目标：保持会话主工作空间不变；Agent 首次访问其外部目录时，在执行前请求用户授权；授权按“会话 × 规范化目录 × 能力”管理。本方案描述目标设计，当前代码尚不具备跨工作空间文件授权能力。

## 1. 现状与结论

会话创建后绑定工作目录。`AgentRuntimeService` 用该目录构建 `Workspace`，按 `aid::modelId::workspace.root` 缓存 `HarnessAgent`。Framework 的 `Workspace.resolveExisting/resolveForCreate` 对路径穿越和符号链接逃逸采取拒绝策略，因此当前结构化文件工具越出主目录会直接失败，而不是触发审批。

权限模块在 `AgentRuntimeService.build()` 中通过 `DesktopPermissionModule.build(agentInterruptStore)` 装配。现有 `PermissionContext` 只有 `memoryId`、`executionId`、原始 Tool Call 和 Mode；`DEFAULT` 模式下未命中规则即放行。Desktop 目前主要对高风险 bash 命令发起 ASK。审批中断冻结原始 Tool Call，恢复时执行该调用；默认 `APPROVE_ALWAYS` 存储是内存态，仅以 `memoryId + 工具名 + 完整参数` 记忆，不是目录授权，不能用于跨会话/重启持久的目录权限。

因此不能只添加一条解析 Shell 文本的权限规则。跨工作空间授权必须在 Runtime 工具边界识别结构化资源、规范化路径，并在执行点重新校验；权限模块负责把 ASK 接入现有 HITL 中断与恢复流程。

## 2. 授权语义

每个会话有一个固定主工作空间 `primaryRoot`，以及零到多个附加目录授权。授权查询始终按 `sessionId` 隔离，即使多个会话复用相同缓存的 `HarnessAgent`，也不得共享授权。

| 用户选择 | 语义 | 是否写目录授权表 |
|---|---|---|
| 允许本次 | 仅批准当前冻结的 Tool Call，执行后失效 | 否 |
| 本会话允许 | 当前会话后续可在授权目录内使用已批准能力 | 是 |
| 拒绝 | 当前调用不执行，拒绝结果返回 Agent | 否 |

首期不提供跨会话“始终允许”。现有 `APPROVE_ALWAYS` 继续保持旧的同调用审批语义或逐步废弃，不得改称目录授权。

能力分为 `READ`、`WRITE`、`EXECUTE`，互不隐式包含：READ 覆盖读取/搜索；WRITE 覆盖宿主控制的文件修改；EXECUTE 仅在真实 OS 级进程沙箱可约束访问时才可授予。OS 文件权限仍是最终限制。

## 3. 判定与执行链路

```text
Tool Call
 → 按工具类型提取资源与能力
 → 解析并规范化真实路径
 → 按 sessionId 查询授权
 → 主目录/已授权目录：ALLOW；未授权外部目录：ASK；非法或禁止路径：DENY
 → ASK 时冻结原调用并展示后端生成的目标与能力
 → 本次批准：仅恢复原调用；本会话批准：先保存 grant，再恢复原调用
 → 工具执行前重新解析与校验
```

审批必须发生在文件副作用或进程启动之前。恢复时执行原冻结调用，不要求模型重新生成参数。批准后路径、符号链接目标或资源能力若改变，必须拒绝或重新 ASK。路径解析失败、权限记录损坏、未知能力均失败关闭。

资源描述应来自工具实现的结构化解析，而非 Desktop RuleHandler 猜测原始 JSON。Framework 权限上下文需要补充经校验的资源（目标、授权根、能力）；中断 payload 和持久快照也需保存该资源摘要。前端展示信息以服务端冻结数据为准，恢复请求不得自行提交目录或能力。

## 4. 路径与工具边界

- 路径归属用规范化 `Path.startsWith`，不得用字符串前缀判断。已存在目标使用 `toRealPath()`；新建目标校验最近已存在父目录的真实路径，并在写入时复核。禁止符号链接逃逸。
- 授权根只按明确请求确定：单文件通常取其父目录；明确请求访问目录才申请该目录。多目标分别呈现，不能静默扩大到共同父目录。已登记工作空间不代表当前会话已获访问许可。
- `read/write/edit/grep/rg` 应由统一的路径 Resolver 同时服务预检与实际执行。`glob` 当前只遍历主工作空间；若增加外部根参数，必须纳入同一授权流程。
- 当前 bash 仅将工作目录设为 Workspace，没有 OS 文件系统沙箱。命令正则不能可靠识别变量、脚本、子进程、重定向等间接路径访问。首期保留现有高风险命令审批，但不得宣称其限制了跨目录访问；只有接入 OS 级沙箱并验证读写边界后，才允许用目录 grant 授予外部 Shell 能力。沙箱不可用时禁止该能力。
- MCP/自定义工具不会自动继承内置文件工具边界；未声明并落实资源约束的本地文件工具不得绕过授权策略。

## 5. SQLite 会话授权表

“允许本次”仅属于中断恢复，不需要落授权表；“本会话允许”写入以下表。首期可不建单独审计表，优先复用中断快照与事件；权限中心/审计需要时再扩展。

```sql
CREATE TABLE IF NOT EXISTS ai_session_workspace_grant (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id          TEXT    NOT NULL,
    primary_root        TEXT    NOT NULL,
    target_root         TEXT    NOT NULL,
    capabilities        TEXT    NOT NULL, -- JSON 数组：READ / WRITE / EXECUTE
    status              INTEGER NOT NULL DEFAULT 1, -- 1 生效，0 撤销
    source_batch_id     TEXT    NOT NULL,
    source_interrupt_id TEXT    NOT NULL,
    create_time         TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoke_time         TEXT
);

CREATE INDEX IF NOT EXISTS idx_session_workspace_grant_lookup
    ON ai_session_workspace_grant(session_id, status);
```

关键约束：

1. 查询必须限定 `session_id`；不得按 Agent、模型、Runtime 或目录全局共享。
2. `primary_root` 保存授权时的规范化主目录快照，用于会话绑定一致性校验；主目录变更后不能静默沿用旧授权。
3. `target_root` 保存真实路径校验后的授权根，不保存未经验证的输入文本。
4. `capabilities` 由后端从冻结请求和用户选择的交集生成；拒绝未知、空集合或超出原请求的能力。不得信任前端提交的授权路径/能力。
5. 查询以路径组件关系和能力包含关系匹配；撤销通过更新 `status` 完成，下一次请求重新 ASK。重复授权可由 Service 事务合并同一 session/target 的能力。
6. 在 `agentforge-desktop-server/sql/sqllite/1.0.x` 新增增量脚本；既有已发布迁移脚本不得原地修改。

如需独立审计，记录 session、batch/interrupt、工具、目标、能力、决定和时间；不记录文件内容、密钥或完整 Shell 输出。

## 6. 审批与恢复

现有 `PermissionApprovalStep.vue` 只有允许/拒绝。需增加“本会话允许”，并在中断事件中呈现服务端提供的当前根、目标根、能力和工具信息。恢复协议只传 `interruptId + action`，不接受客户端任意传 `approvedRoot` 或 `capabilities`。

服务端流程：校验批次及 session/execution/interrupt 归属 → 原子 claim 批次 → 对“本会话允许”从冻结资源生成 grant 并事务写入 → 恢复冻结调用 → 工具执行点再次校验。写入失败不得恢复；重复恢复不得重复执行。快照 schema 需要版本升级并明确旧版本兼容策略。用户撤销 grant 后后续访问重新 ASK。

### 6.1 规则审批的核心落地代码

关键实现原则：权限规则不解析原始 JSON 猜路径；Framework 在工具执行前调用与工具相同的资源解析器，将可信 `WorkspaceAccessRequest` 放进权限上下文。规则只消费结构化结果。以下为接口草图，需在 Framework 补齐 `PermissionContext.resources`、`InterruptRequest.payload` 和工具预检扩展点后落地：

```java
public enum WorkspaceCapability { READ, WRITE, EXECUTE }
public enum WorkspaceAccessDecision { ALLOW, ASK, DENY }

public final class WorkspaceResource {
    private final String requestedPath; // 仅用于显示/诊断
    private final Path realPath;        // 执行时使用的校验后路径
    private final Path grantRoot;       // 本次明确申请授权的根
    private final WorkspaceCapability capability;
    private final boolean primary;
    // 构造时校验非空；getter 省略
}

public final class WorkspaceAccessRequest {
    private final String sessionId;
    private final String executionId;
    private final Path primaryRoot;
    private final String toolName;
    private final List<WorkspaceResource> resources;
    // 构造时复制为不可变集合；getter 省略
}

public final class WorkspaceAccessEvaluation {
    private final WorkspaceAccessDecision decision;
    private final List<WorkspaceResource> resources;
    private final String reason;
}
```

Framework 预检调用授权服务并把 ASK 的资源摘要和冻结调用一起存入中断快照；已有的 `PermissionEngine` 首命中规则可作为决策入口，但需要让资源规则在普通 Shell 规则之前评估，并明确 DENY 优先，不允许后续 ASK 覆盖 DENY：

```java
public final class WorkspaceAccessPermissionRule implements PermissionRule {
    public static final String TYPE = "workspace_access";
    private final PermissionDecision decision;

    public WorkspaceAccessPermissionRule(PermissionDecision decision) {
        this.decision = decision;
    }
    public String getId() { return "workspace_access"; }
    public String getType() { return TYPE; }
    public PermissionDecision getDecision() { return decision; }
}

public final class WorkspaceAccessPermissionRuleHandler implements PermissionRuleHandler {
    private final WorkspaceAccessService accessService;
    public String type() { return WorkspaceAccessPermissionRule.TYPE; }

    public boolean matches(PermissionRule rule, PermissionContext context) {
        if (!(rule instanceof WorkspaceAccessPermissionRule)) return false;
        // 资源必须由 Runtime 预检产生；缺失时不能当作“无越界”而放行。
        if (context == null || context.getResources() == null) {
            throw new IllegalStateException("workspace resources were not resolved");
        }
        return accessService.evaluate(context.getMemoryId(), context.getResources())
                .getDecision() != WorkspaceAccessDecision.ALLOW;
    }
}
```

上面 handler 仅说明责任边界，生产实现不应把“命中规则”本身当成决策结果。建议 Framework 将规则接口演进为返回 `PermissionDecision`/结构化判定结果，或由统一 `WorkspaceAccessPreflight` 直接产出 ALLOW/ASK/DENY，并将 `ASK` 转为现有 `InterruptType.PERMISSION_APPROVAL`。特别是 `DEFAULT` 未命中规则会 ALLOW，因此资源预检必须是强制的执行前置步骤，不能因无规则或资源解析失败走默认放行。

授权判定的核心伪实现：

```java
public WorkspaceAccessEvaluation evaluate(String sessionId,
        List<WorkspaceResource> resources) {
    List<WorkspaceResource> external = new ArrayList<>();
    for (WorkspaceResource r : resources) {
        if (r == null || r.getRealPath() == null || r.getGrantRoot() == null) {
            return denied("invalid resolved resource");
        }
        if (r.isPrimary()) continue;
        if (!grantStore.hasGrant(sessionId, r.getRealPath(), r.getCapability())) {
            external.add(r);
        }
    }
    return external.isEmpty() ? allowed(resources) : ask(external);
}
```

`hasGrant` 必须先验证 session 主目录快照仍匹配，再按规范路径组件关系检查 `realPath.startsWith(targetRoot)`，并检查能力集合包含本次能力。文件写入工具即使预检 ALLOW，也须在落盘前重新解析路径并再次调用 Guard；不得将未经复核的原始参数直接传给 `Files.*`。

### 6.2 授权表访问代码

Desktop 层建议通过 MyBatis Mapper + Service 操作 SQLite，不在 PermissionRuleHandler 中拼 SQL。Mapper 的必要语义如下（字段映射/异常处理略）：

```java
public interface WorkspaceGrantMapper {
    List<WorkspaceGrantRow> selectActiveBySession(@Param("sessionId") String sessionId);
    int insert(WorkspaceGrantRow grant);
    int revoke(@Param("sessionId") String sessionId,
               @Param("grantId") long grantId,
               @Param("revokeTime") String revokeTime);
}
```

`WorkspaceAccessGrantService` 负责规范化、校验、能力合并和事务边界。不得直接接受客户端传来的根目录：

```java
@Service
public class WorkspaceAccessGrantService {
    private final WorkspaceGrantMapper mapper;

    public boolean hasGrant(String sessionId, Path realPath,
                            WorkspaceCapability capability) {
        for (WorkspaceGrantRow row : mapper.selectActiveBySession(sessionId)) {
            Path root = Paths.get(row.getTargetRoot()).toAbsolutePath().normalize();
            if (!realPath.startsWith(root)) continue;
            Set<WorkspaceCapability> caps = decodeCapabilitiesStrict(row.getCapabilities());
            if (caps.contains(capability)) return true;
        }
        return false;
    }

    @Transactional
    public void grantFromFrozenRequest(String sessionId, Path currentPrimaryRoot,
            String batchId, String interruptId, WorkspaceAccessPayload frozen,
            Set<WorkspaceCapability> requestedByUser) {
        requireNonBlank(sessionId, batchId, interruptId);
        verifyPrimaryRoot(frozen.getPrimaryRoot(), currentPrimaryRoot);
        Set<WorkspaceCapability> allowed = intersection(
                frozen.getCapabilities(), requestedByUser);
        if (allowed.isEmpty() || !allowed.equals(requestedByUser)) {
            throw new IllegalArgumentException("grant exceeds frozen request");
        }
        Path targetRoot = resolveAndVerifyGrantRoot(frozen.getTargetRoot());
        mapper.insert(WorkspaceGrantRow.of(sessionId,
                currentPrimaryRoot.toRealPath().toString(), targetRoot.toString(),
                encodeCapabilities(allowed), batchId, interruptId));
    }
}
```

说明：SQL 列表查询后在 Java 中做路径组件判断，避免 SQLite 字符串 `LIKE` 将 `/repo/a` 错误匹配 `/repo/abc`。生产实现还需处理重复 grant（事务内合并同 session/target 的能力）、严格解析 capabilities JSON、审计时间以及目录被替换的复核。`@Transactional` 只能保证数据库操作；中断批次 claim 与 SQLite grant 写入若不在同一事务资源中，必须采用“先校验并 claim、写授权失败则不恢复”的失败关闭策略，避免声称跨 Store 原子事务。

### 6.3 确认请求与授权更新

恢复 API 不接受 `approvedRoot` / `capabilities`，只接收用户动作；授权范围来自服务端已冻结的中断 payload：

```json
{
  "batchId": "batch_xxx",
  "responses": [
    { "interruptId": "int_xxx", "action": "APPROVE_SESSION_SCOPE" }
  ]
}
```

Desktop 的核心处理顺序应在 `ChatTaskServiceImpl.resumeInterrupt` 附近实现：

```java
ResolvedBatch batch = interruptService.validateAndClaim(request);
try {
    for (InterruptRequest item : batch.permissionInterrupts()) {
        ApprovalAction action = batch.actionOf(item);
        if (action == ApprovalAction.APPROVE_SESSION_SCOPE) {
            WorkspaceAccessPayload frozen = requireWorkspacePayload(item);
            grantService.grantFromFrozenRequest(
                    batch.sessionId(), batch.primaryRoot(), batch.batchId(),
                    item.getInterruptId(), frozen, frozen.getCapabilities());
        }
        // APPROVE_ONCE 不写 grant；REJECT 不写 grant。
    }
    interruptService.resumeOriginalFrozenCalls(batch);
} catch (RuntimeException e) {
    interruptService.markResumeFailed(batch);
    throw e; // 必须停止恢复，不得继续执行工具
}
```

实际实现需让 `APPROVE_SESSION_SCOPE` 成为明确的恢复动作（Framework `ApprovalAction` 与前端响应同时升级），并在 `resolve/claim` 阶段校验每个响应与冻结 interrupt 一一对应。不要先启动异步恢复再异步补写 grant；也不要对原调用重新向模型请求生成。授权持久化成功但恢复执行失败时，授权仍可保留并允许用户撤销，但必须有失败状态可见；若产品要求“失败时撤销 grant”，需额外设计补偿事务并处理并发访问。

## 7. 实施顺序与代码落点

1. **Framework 先行**：为资源型工具增加结构化权限预检/解析扩展点；统一预检与执行使用的真实路径 Resolver，补齐路径、链接和拒绝无副作用测试。
2. **Desktop 会话授权**：实现 SQLite Grant Store 与判定服务；由 `PermissionContext.memoryId`（当前即 sessionId）定位授权。不得把当前 session/grant 放在复用的 Agent 或 RuleHandler 可变字段中。
3. **HITL 与 UI**：扩展权限中断 payload、快照 codec、恢复 action 和审批卡片；实现本次/本会话/拒绝及撤销路径。
4. **Shell 沙箱后置**：按平台建设真实文件系统隔离；未完成前不为外部 Shell 访问提供授权保证。

当前关键落点：

- Runtime 装配：`agentforge-desktop-server/.../modules/agent/runtime/AgentRuntimeService.java`
- 权限装配与 Shell 规则：`.../modules/chat/governance/DesktopPermissionModule.java`、`ShellCommandPermissionRuleHandler.java`
- 路径边界与工具：`agentforge-framework/agentforge-harness-agent/.../runtime/local/workspace/Workspace.java`、`.../tool/LocalRuntimeTools.java`
- 权限上下文/引擎：`agentforge-framework/agentforge-agent-core/.../governance/permission/engine/PermissionContext.java`、`PermissionEngine.java`
- 中断快照、恢复与 UI：Desktop `AgentInterruptSnapshotCodec`、`DesktopInterruptRuntimeRegistry`、`ChatTaskServiceImpl`；UI `PermissionApprovalStep.vue`

## 8. 验收测试

- 主目录内正常执行；外部路径必须 ASK；拒绝后不读、不写、不启动进程。
- 相对/绝对路径、相似前缀目录、符号链接逃逸、新建文件父目录链接不能绕过检查。
- READ 不允许 WRITE/EXECUTE；本次只作用于冻结调用；本会话 grant 跨应用重启保留但不跨 session；撤销后重新 ASK。
- 同一缓存 Runtime 服务多个 session 时授权不串用；主目录快照不匹配时旧 grant 不生效。
- 审批后目标/能力变化、重复恢复、未知能力、损坏记录均失败关闭。
- 验证 `DEFAULT` 未命中规则放行的框架语义：跨区 ASK 必须由结构化预检明确产生。
- 未启用 OS 沙箱时，不得把 Bash 当前工作目录描述成文件系统隔离，也不得授予其外部目录执行能力。

## 9. 最终建议

采用“**固定主工作空间 + sessionId 隔离的 SQLite 附加目录授权 + 结构化资源预检 + HITL 审批 + 原调用恢复 + 执行点复核**”。权限确实跟会话走，但不是信任整个会话，而是精确绑定“会话、规范化目录、能力”。OpenCode 调研可借鉴外部目录独立审批、审批先于副作用及一次/长期授权分离；其实现不能直接照搬，因为 AgentForge 当前权限上下文和 Workspace 工具边界不同。