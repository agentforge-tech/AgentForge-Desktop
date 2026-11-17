# AgentForge Desktop 工作空间设计方案

## 一、设计背景

AgentForge Desktop 当前在发起对话时，前端通过 `projectPath` 把本地文件夹路径传给后端，`AgentRuntimeService` 再以该路径创建 `Workspace` 和 `LocalRuntime`。这套方式能够让 Agent 直接操作本机目录，但存在以下问题：

1. 工作空间只是一个请求参数，没有业务实体，无法命名、排序、重用和管理。
2. 会话与工作空间没有持久化关系，重新打开历史会话时无法准确恢复工作目录。
3. 未选择文件夹时，所有会话共用 `<root>/workspace`，多个任务的文件容易相互污染。
4. 左侧栏只能平铺会话，无法表达“工作空间—会话—文件夹”之间的归属关系。

本方案将“工作空间”升级为独立业务实体，并在第一条消息真正发送时完成目录创建、会话创建和绑定。

## 二、核心概念

### 2.1 任务

“任务”是没有归属到用户显式工作空间的会话。用户点击“新建对话”后直接输入内容，系统会为该会话创建独立的默认目录，但在左侧栏中仍展示在“任务”分组下，不展示为一个工作空间。

默认目录格式：

```text
<root>/workspace/yyyy-MM-dd-HH-mm-ss

# 安装版示例
~/.agentforge-desktop/workspace/2026-10-22-10-51-54
```

### 2.2 工作空间

“工作空间”是用户显式创建或选择的可重用目录。多个会话可以共享一个工作空间，并在左侧栏中按工作空间分组展示。

工作空间有两种来源：

- 内置工作空间：用户输入名称，目录位于 `<root>/workspace/${workspaceName}`。
- 本地工作空间：用户选择已存在的本机文件夹，系统只记录路径，不搬迁、不复制其内容。

### 2.3 草稿选择与真实绑定

新建对话页的“选择工作空间”只是对会话草稿的配置。在用户发送第一条消息前：

- 不创建 `ai_agent_session`。
- 不创建默认任务目录。
- 内置工作空间可以先创建业务记录，但实际文件夹延迟到首次发送时创建。
- 用户取消新建对话时，不产生空会话和无用的时间戳目录。

## 三、数据模型

### 3.1 `ai_workspace`

```sql
CREATE TABLE IF NOT EXISTS ai_workspace (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL,
    folder_path    TEXT    NOT NULL,
    workspace_type INTEGER NOT NULL DEFAULT 1,
    status         INTEGER NOT NULL DEFAULT 1,
    remark         TEXT             DEFAULT NULL,
    sort_order     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

在需求给出的基础字段上，建议增加 `workspace_type` 和 `status`，避免通过路径或备注猜测业务语义。

| 字段 | 说明 |
| --- | --- |
| `id` | 工作空间主键 |
| `name` | 界面展示名称；创建后第一阶段不支持修改 |
| `folder_path` | 规范化后的绝对路径 |
| `workspace_type` | `1` 内置工作空间，`2` 外部本地文件夹 |
| `status` | `0` 待初始化，`1` 可用，`2` 不可用，`3` 已移除 |
| `remark` | 备注或最后一次不可用原因 |
| `sort_order` | 左侧栏排序，值越大越靠前 |
| `create_time` / `modify_time` | 创建和修改时间 |

约束由 Service 层保证：

- `name` 去除首尾空格后不得为空。
- 内置工作空间名不得包含 `/`、`\`、`..` 或平台非法字符。
- 不允许两条有效记录指向同一个规范化路径。
- 内置工作空间必须位于 `<root>/workspace`之下，不允许路径穿越。
- 外部文件夹必须是已存在、可读且尽可能可写的目录。

### 3.2 `ai_agent_session` 扩展

```sql
ALTER TABLE ai_agent_session ADD COLUMN workspace_id INTEGER DEFAULT NULL;
ALTER TABLE ai_agent_session ADD COLUMN workspace_path TEXT DEFAULT NULL;
```

| 字段 | 说明 |
| --- | --- |
| `workspace_id` | 用户显式选择的工作空间 ID；默认任务为 `NULL` |
| `workspace_path` | 会话实际运行目录快照，默认任务也必须保存 |

仅增加 `workspace_id` 不足以支持默认任务。默认任务不应出现在“空间”列表，因此 `workspace_id` 为空；但历史会话、中断恢复和追加指令又必须恢复原运行目录，所以需要 `workspace_path` 作为会话级不可变快照。

即使会话绑定了 `workspace_id`，运行时也建议优先使用 `ai_agent_session.workspace_path`：

- 避免工作空间记录修改后，历史会话静默切换到新目录。
- 可以对“原目录不存在”做明确的恢复或重新定位交互。

### 3.3 关系语义

```text
ai_workspace 1 ── N ai_agent_session

任务会话：
  workspace_id   = NULL
  workspace_path = <root>/workspace/yyyy-MM-dd-HH-mm-ss

工作空间会话：
  workspace_id   = ai_workspace.id
  workspace_path = ai_workspace.folder_path 的绑定时快照
```

SQLite 不强制新增外键，与现有基线风格保持一致；关联完整性由 Service 层保证。

## 四、三种核心场景

### 4.1 新建对话后直接发送

```text
点击新建对话
  → 前端 workspaceSelection = none
  → 用户发送第一条消息
  → 后端生成会话 ID
  → 在 <root>/workspace 下生成时间戳目录名
  → 原子创建目录
  → 创建 ai_agent_session
      workspace_id   = NULL
      workspace_path = 新目录绝对路径
  → 创建首轮消息
  → 以 workspace_path 创建 Agent Runtime
```

并发到同一秒时，目录名依次附加 `-02`、`-03`，例如：

```text
2026-10-22-10-51-54
2026-10-22-10-51-54-02
```

这类会话一律出现在左侧“任务”列表。

### 4.2 创建并选中内置工作空间

```text
打开工作空间选择器
  → 点击“新建工作空间”
  → 输入名称
  → POST /api/workspaces
  → 校验名称和目标路径
  → 写入 ai_workspace(status=0)
  → 返回工作空间并自动选中
  → 用户发送第一条消息
  → 创建 <root>/workspace/${workspaceName}
  → 将 ai_workspace.status 更新为 1
  → 创建 ai_agent_session 并绑定 workspace_id/workspace_path
  → 启动 Agent Runtime
```

创建接口应保证名称和路径不冲突。如果用户创建后未发起对话，该记录可保留为待初始化空间；用户下次选中并发送时再创建目录。

### 4.3 选择已有本地文件夹

```text
打开工作空间选择器
  → 点击“打开本地文件夹”
  → Electron 目录选择器返回绝对路径
  → POST /api/workspaces/import
  → 后端规范化路径并检查目录可用性
  → 已存在同路径记录时返回原工作空间
  → 否则写入 ai_workspace(workspace_type=2, status=1)
  → 前端自动选中
  → 用户首次发送时创建会话并绑定
```

外部目录不由 AgentForge Desktop 创建或删除。若路径后续丢失，会话仍保留，但在继续发送前要求用户重新定位目录。

## 五、首次发送的原子流程

### 5.1 请求模型

现有 `ChatStreamRequest.projectPath` 建议逐步替换为明确的工作空间选择语义：

```json
{
  "aid": "agent-id",
  "sessionId": null,
  "message": "帮我创建一个项目",
  "modelId": 1,
  "workspaceId": 12
}
```

规则：

- 新会话且 `workspaceId = null`：创建独立默认任务目录。
- 新会话且 `workspaceId != null`：校验并绑定工作空间。
- 已有会话：必须使用会话已持久化的 `workspace_path`，忽略或拒绝客户端传入的新 `workspaceId/projectPath`，防止会话中途静默换目录。

### 5.2 服务端编排

建议新增 `WorkspaceBindingService.prepareForChat(...)`，由 `ChatController` 在初始化会话前调用：

```text
ChatController.stream
  → 校验 Agent / Model / message
  → WorkspaceBindingService.prepareForChat(sessionId, workspaceId)
      ├─ 已有会话：查询并返回 session.workspace_path
      ├─ 新会话 + workspaceId：校验或初始化 ai_workspace
      └─ 新会话 + 无 workspaceId：创建时间戳目录
  → AgentSessionService.initChat(..., workspaceBinding)
      ├─ 写入 ai_agent_session.workspace_id
      └─ 写入 ai_agent_session.workspace_path
  → AgentRuntimeService.getOrCreate(agent, model, workspace_path)
  → 创建 executionId 并启动流式执行
```

### 5.3 一致性边界

数据库事务不能回滚文件系统操作，因此采用“目录先准备，数据再提交，失败补偿”的方式：

1. 完成路径规范化和安全校验。
2. 对内置目录执行 `Files.createDirectory`，不对未验证的外部父目录递归创建。
3. 在数据库事务中创建/更新工作空间、创建会话和首轮消息。
4. 数据库失败时，仅删除“本次创建且仍为空”的内置目录；绝不删除外部目录或已有内容的目录。
5. Runtime 创建失败时保留会话和目录，消息记录为失败，允许用户重试。

## 六、工作空间接口设计

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/api/workspaces` | 获取可用工作空间，支持名称搜索 |
| `POST` | `/api/workspaces` | 创建内置工作空间记录 |
| `POST` | `/api/workspaces/import` | 登记一个已有本地文件夹 |
| `GET` | `/api/workspaces/{id}` | 获取详情和目录可用性 |
| `POST` | `/api/workspaces/{id}/open` | 通过 Electron 打开目录；也可保留为前端 IPC |
| `PUT` | `/api/workspaces/{id}/sort` | 更新排序 |
| `DELETE` | `/api/workspaces/{id}` | 从管理列表移除，不删除本地文件 |
| `GET` | `/api/workspaces/sidebar` | 一次返回任务和工作空间会话树 |

### 6.1 创建内置工作空间

```json
POST /api/workspaces

{
  "name": "数据分析",
  "remark": "可选备注"
}
```

返回：

```json
{
  "id": 12,
  "name": "数据分析",
  "folderPath": "/Users/demo/.agentforge-desktop/workspace/数据分析",
  "workspaceType": 1,
  "status": 0
}
```

### 6.2 登记本地文件夹

```json
POST /api/workspaces/import

{
  "folderPath": "/Users/demo/projects/my-project",
  "name": "my-project"
}
```

`name` 默认取目录末级名称，也允许用户在登记时指定展示名。

### 6.3 左侧栏聚合结果

```json
{
  "tasks": [
    {
      "id": 101,
      "sessionId": "session-xxx",
      "title": "查询下最新 AI 资讯",
      "modifyTime": "2026-11-16 22:55:00"
    }
  ],
  "workspaces": [
    {
      "id": 12,
      "name": "数据分析",
      "folderPath": "/Users/demo/.agentforge-desktop/workspace/数据分析",
      "available": true,
      "sessions": [
        {
          "id": 102,
          "sessionId": "session-yyy",
          "title": "分析销售数据",
          "modifyTime": "2026-11-17 09:30:00"
        }
      ]
    }
  ]
}
```

## 七、前端交互设计

### 7.1 输入区工作空间选择器

输入框左下角展示：

```text
[文件夹图标] 选择工作空间 [箭头]
```

展开后按以下顺序展示：

1. 搜索框。
2. 最近使用或按 `sort_order` 排序的工作空间。
3. “新建工作空间”。
4. “打开本地文件夹”。

选中后：

- 按钮文案切换为工作空间名。
- 新建会话状态下允许重新选择或清空。
- 首条消息发送成功后锁定，后续轮次不允许直接换工作空间。
- 点击“新建对话”时恢复为未选中状态。

### 7.2 新建工作空间弹窗

弹窗包含：

- 标题：“新建工作空间”。
- 说明：“为工作空间命名，本地将在首次使用时自动创建同名文件夹。”
- 名称输入框。
- 取消和确认按钮。

交互规则：

- 名称为空时确认按钮禁用。
- 输入时即时校验长度、非法字符和重名。
- 创建成功后关闭弹窗，并在当前对话草稿中自动选中。
- 第一阶段不提供重命名，避免业务名与实际目录名失配。

### 7.3 左侧栏

左侧栏分为两层：

```text
任务 (N)
  ├─ 会话 A
  ├─ 会话 B
  └─ 会话 C

空间 (M)
  ├─ 工作空间 1
  │    ├─ 会话 D
  │    └─ 会话 E
  └─ 工作空间 2
       └─ 会话 F
```

归类规则：

- `ai_agent_session.workspace_id IS NULL` 的会话进入“任务”。
- `workspace_id IS NOT NULL` 的会话进入对应的“空间”子树。
- 置顶会话保留现有 `pin_order` 语义，在所属分组内靠前展示，不改变归属。
- 工作空间默认按 `sort_order DESC, modify_time DESC` 排序。
- 会话默认按 `pin_order DESC, modify_time DESC` 排序。
- 工作空间可独立收起/展开，展开状态保存在前端本地配置。
- 会话重命名、置顶和删除操作继续复用现有接口。

### 7.4 打开历史会话

打开历史会话后：

1. 前端显示会话所绑定的工作空间名称；默认任务可显示“任务目录”或目录末级名。
2. 选择器变为只读，不允许在原会话中替换目录。
3. 继续对话、追加指令、HITL 恢复和任务重试都使用 `session.workspace_path`。
4. 目录不存在或不可访问时，禁止发送，提供“重新定位”或“打开新对话”。

## 八、后端模块划分

```text
modules/workspace
  ├─ controller
  │    └─ WorkspaceController
  ├─ service
  │    ├─ WorkspaceService
  │    └─ WorkspaceBindingService
  ├─ dao
  │    └─ WorkspaceMapper
  └─ domain
       ├─ WorkspacePojo
       ├─ WorkspaceCreateReq
       ├─ WorkspaceImportReq
       └─ WorkspaceVo
```

职责划分：

- `WorkspaceController`：接口参数校验和响应转换。
- `WorkspaceService`：工作空间 CRUD、路径规范化、可用性检查和排序。
- `WorkspaceBindingService`：专门负责首次发送时的目录初始化与会话绑定。
- `AgentSessionService`：接收已解析的工作空间绑定，不直接处理文件系统。
- `AgentRuntimeService`：仅接收服务端确认后的路径，不再直接信任客户端 `projectPath`。

## 九、安全与边界场景

### 9.1 路径安全

- 所有路径先转绝对路径并执行 `normalize()`。
- 内置工作空间规范化后必须仍以 `<root>/workspace` 为前缀。
- 不允许把 `<root>`、用户主目录或文件系统根目录当作可删除工作空间。
- 工作空间“移除”默认只删业务记录，不递归删除本地目录。
- 向 Agent 注入的一定是会话服务端快照路径，而不是本轮请求可任意修改的字符串。

### 9.2 同名和同路径

- 内置工作空间同名时返回 `409 Conflict`。
- 外部目录已登记时返回已有记录，前端直接选中，不重复插入。
- Windows 路径比较需处理盘符大小写，macOS 需考虑文件系统是否大小写敏感。

### 9.3 目录丢失

- 列表查询不因目录丢失删除会话或工作空间记录。
- 左侧栏对不可用空间显示警告状态。
- 发送前再次检查，并返回可理解的业务错误，不让 Runtime 在深层抛出模糊的 I/O 错误。

### 9.4 删除与移除

第一阶段只提供“从列表移除工作空间”：

- 不删除文件夹。
- 不删除历史会话。
- 有历史会话时，空间可在“已移除”分组中恢复，或仅在打开相关会话时展示。
- 真正删除本地文件属于高风险操作，不纳入首期。

## 十、迁移与兼容策略

### 10.1 SQLite 增量脚本

建议新增一个 `1.0.x` 增量脚本：

```sql
CREATE TABLE IF NOT EXISTS ai_workspace (...);
ALTER TABLE ai_agent_session ADD COLUMN workspace_id INTEGER DEFAULT NULL;
ALTER TABLE ai_agent_session ADD COLUMN workspace_path TEXT DEFAULT NULL;
```

### 10.2 已有会话

旧会话没有可信的工作目录信息，不应自动猜测为某个工作空间。迁移后：

- `workspace_id = NULL`。
- `workspace_path = NULL`。
- 在左侧栏归入“任务”。
- 打开旧会话继续发送时，提示选择一个工作空间，或为该会话创建新的默认任务目录并回填 `workspace_path`。

### 10.3 `projectPath` 过渡

为了兼容旧前端，可保留一个过渡版本：

1. 优先使用 `workspaceId`。
2. 无 `workspaceId` 但有 `projectPath` 时，按“外部本地文件夹”登记或查找工作空间后绑定。
3. 两者都没有时，创建默认任务目录。
4. 前端和数据迁移稳定后，移除客户端直传任意路径的能力。

## 十一、分阶段落地建议

### 第一阶段：数据与运行时闭环

1. 新增 `ai_workspace`、`workspace_id` 和 `workspace_path`。
2. 新增 Workspace Mapper、Service 和基础接口。
3. 首次发送时创建默认目录或初始化内置工作空间。
4. 会话绑定工作路径，Runtime 只使用服务端绑定结果。
5. 增加路径安全、并发创建、会话恢复和迁移测试。

### 第二阶段：新建对话交互

1. 工作空间选择下拉层。
2. 新建工作空间弹窗。
3. Electron 本地目录选择与登记。
4. 首轮发送后锁定工作空间。
5. 新建对话时清空上一个草稿选择。

### 第三阶段：左侧树和管理能力

1. 左侧栏分为“任务”与“空间”。
2. 工作空间下展开会话。
3. 置顶、排序、搜索、打开本地目录和移除能力。
4. 目录丢失、重新定位和旧会话兼容交互。

## 十二、验收标准

### 数据正确性

- 每个新会话在首次发送后都有唯一、明确的 `workspace_path`。
- 默认任务 `workspace_id` 为空，用户显式选择工作空间时 `workspace_id` 正确绑定。
- 继续历史会话、追加指令、HITL 恢复始终使用原会话路径。
- 多个会话可绑定同一工作空间，且不会互相覆盖会话数据。

### 文件系统正确性

- 默认任务目录在首次发送时创建，不在点击“新建对话”时创建。
- 内置工作空间位于 `<root>/workspace/${name}`。
- 外部目录内容不被自动搬迁或删除。
- 任何内置目录均不能通过名称穿越到 `<root>/workspace` 之外。

### 交互正确性

- 新建对话默认不选中工作空间。
- 创建或选择工作空间后，输入区立即显示选中状态。
- 发送首条消息后工作空间锁定。
- 默认任务只出现在“任务”列表，不会生成大量可见的时间戳工作空间。
- 显式工作空间下能够展开并打开其所有会话。

## 十三、结论

本方案将 AgentForge Desktop 的工作空间从一个临时 `projectPath` 升级为可持久化、可重用、可恢复的业务实体，同时保留了“新建对话后立即开始”的轻量体验：

- 未显式选择工作空间时，每个会话拥有独立的默认任务目录，并归入“任务”。
- 用户创建或选择工作空间时，会话持久绑定该目录，并归入对应“空间”。
- 文件夹创建、会话创建和 Runtime 初始化都收口到首次真实发送，避免空会话和无用目录。
- 会话级 `workspace_path` 快照保证历史恢复、追加指令和 HITL 续跑始终在同一个工作目录中执行。
