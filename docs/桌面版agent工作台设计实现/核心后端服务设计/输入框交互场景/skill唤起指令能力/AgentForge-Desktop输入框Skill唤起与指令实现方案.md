# AgentForge Desktop 输入框 Skill 唤起与指令实现方案

> 适用范围：AgentForge Desktop（Vue2 + Vite + Electron + 本地 Java Runtime）
>
> 关联能力：`modules/skill`（`DesktopSkillLoader` / `DesktopSkillTool`）、`modules/resource`（`DesktopResourceService`）、`modules/chat`（提交与追加链路）
>
> 目标：在输入框中通过 `/` 唤起 Skill 面板，列表由后端接口实时返回；支持键盘选择、回车确认、单输入框多 Skill 组合；明确「System Prompt 规则 + 用户消息指令」双层转义规则，并把选择结果贯通到会话上下文、追加指令与历史回放。

---

## 1. 结论先行

### 1.1 一句话方案

`/` 触发浮层 → 调 `GET /api/skills/available` 拉取「当前工作空间生效 Skill」→ 键盘/鼠标选择 → Skill 以 chip 形式挂在输入框顶部（可多个、可单独移除）→ 发送时以结构化字段 `skills[]` 提交 → 后端按 name 精确校验后转换为「显式 Skill 指令块」拼接到 Agent question 前 → 系统 Prompt 中新增使用规则约束模型必须先读 `SKILL.md`。

### 1.2 关键取舍

| 决策点 | 采用方案 | 原因 |
| --- | --- | --- |
| Skill 在输入框中的形态 | **块级 chip（输入框顶部一行）**，不做文本内联标记 | Skill 是「整轮任务属性」，不像图片/文件有「针对这一段」的位置语义；避免在 contenteditable 内嵌自定义节点带来的光标、删除、粘贴、序列化边界问题 |
| Skill 的传输标识 | 前端只传 `name`（+ 展示用 `scope`/`path` 快照），后端以 name 在 effective 列表中精确解析 | 前端传 path 等于把本地路径写入指令，存在路径注入风险；name 由 loader 保证唯一（同名按 workspace 覆盖 global） |
| Skill 指令生成位置 | **后端生成**，前端不拼 Prompt | 与现有资源标记转换（`DesktopResourceService`）保持一致；追加指令、旧 SSE 链路、回放都复用同一处逻辑 |
| 列表来源 | 新增 `GET /api/skills/available`，**保留** `GET /api/skills` 语义（仅全局，技能管理页在用） | 不破坏已上线的技能管理页 |
| 与资源的关系 | 两条链路互不干扰：资源仍是 inline 标记，Skill 是结构化数组 | 已上线的资源能力零改动 |

---

## 2. 参考实现（agentx-desktop-ui）复盘

参考项目已经跑通「输入框 `/` 唤起 Skill」，核心由 4 个部件构成：

| 部件 | 参考文件 | 职责 |
| --- | --- | --- |
| 触发检测 | `Workbench.vue` → `handleSkillShortcutInput` | 用 `(?:^|\s)\/([^\s/]*)$` 匹配 textarea 文本尾部，决定浮层开关与 query |
| 浮层 | `SkillShortcutPanel.vue` | 左侧列表 + 右侧描述预览；`↑/↓/Enter/Esc` 键盘导航；已选 Skill 从列表过滤；最多 8 条 |
| 已选展示 | `TemplateComposerInput.vue` + `SkillReference.vue` | 已选 Skill 以 chip 排在编辑器上方，带 `×` 移除 |
| 指令转换 | `Workbench.vue` → `buildSkillPrompt` | **前端**拼 `请明确组合使用以下 Skills：「A」（标识：a）、「B」。开始执行前依次读取…` 前缀到用户消息 |

可复用的部分：面板交互模型（列表 + 预览 + 键盘）、chips 视觉、`/` 正则。

需要改进的部分：

1. 前端拼 Prompt：无法校验 Skill 是否仍存在，且追加/SSE/回放三条链路要各拼一遍。
2. 无后端查询接口：Skill 列表来自能力探测全量快照，切换工作空间后 workspace Skill 不会刷新。
3. 无持久化：刷新会话后已选 Skill 丢失（参考实现只做了当轮 chips）。
4. 无数量与失效兜底：选中已被删除的 Skill 会静默失效。

本方案保留交互，替换掉 1、2、3、4 的实现路径。

---

## 3. 现状梳理（AgentForge-Desktop）

### 3.1 后端已有能力

```text
modules/skill/
├── api/SkillLoader.java              # 接口：globalRoot / ensureGlobalRoot / listGlobalSkills
│                                     #       listEffectiveSkills / loadSkillIndex / skill / importGlobalSkill
├── api/SkillMetadata.java            # name / description / scope / path / skillFile / modifyTime
├── service/DesktopSkillLoader.java   # 扫描 global(~/.agentforge-desktop/skills) + workspace(<ws>/.agentforge/skills)
│                                     # 同名 workspace 覆盖 global；只扫 root 与一层子目录
├── controller/DesktopSkillController.java  # GET /api/skills、GET /api/skills/root、POST /api/skills/import
└── tool/DesktopSkillTool.java        # skill(path) 读取 SKILL.md，带根目录与大小校验
```

**已有但未对外暴露**的关键方法：`listEffectiveSkills(Path workspaceRoot)`。它正是输入框需要的「当前工作空间生效列表」，只是缺少一个能拿到 `workspaceRoot` 的 HTTP 入口。

### 3.2 提交与追加链路（Skill 指令需要接入的三处）

```text
POST /api/chat/tasks/submit      ChatTaskController -> ChatTaskServiceImpl.submit
    ├─ resourceService.resolve(files)
    ├─ displayMessage = resourceService.displayText(message)     # 用户可见文本（去标记）
    ├─ question       = resourceService.buildAgentQuestion(...)  # 送给模型的文本
    ├─ reqJson        = attachmentService.buildUserReqJson(...)  # 落库快照，供回放
    ├─ sessionService.initChat(..., displayMessage, ..., reqJson)
    └─ runtimeService.getOrCreate(agent, model, workspacePath, mode)

POST /api/chat/stream            ChatController.stream           # 旧 SSE 链路，逻辑同上（需同步）
POST /api/chat/append            UserMessageAppendController.append
    └─ resourceService.buildAppendContent(message, files)         # 运行中追加
```

### 3.3 前端现状

- `ComposerInput.vue`：contenteditable，`[[agentforge-resource:type:encodedPath]]` inline chip；`Enter` 转发 `enter` 事件；已有 `serializeEditor` / `insertResourceMarkers` / `focusAfterResource` / `saveSelection` 等基础设施。
- `ChatWorkbench.vue`：`draft` / `composerResources` / `send()` / `sendAppend()` / `buildSessionContext()` / `applySessionContext()`，会话上下文防抖落库，历史由 `sessionReplay.replaySessionMessages` 回放。
- `SkillManager.vue`：技能管理页，已用 `GET /api/skills`（全局）与 `POST /api/skills/import`。
- `UserMessageContent.vue`：按 rawText 中的资源标记 + `resources` 渲染用户气泡。

结论：**基础设施齐全，缺的是 Skill 的「查询接口 / 选择态 / 指令转换 / 持久化」四块拼图**，不需要重构现有资源机制。

---

## 4. 总体链路

```text
[用户输入 /]
    │
    ▼
[ComposerInput] 光标前文本 → (?:^|\s)\/([^\s/]*)$
    │  命中
    ▼
[ChatWorkbench] 打开 SkillShortcutPanel(query)
    │  首次打开 / 切换工作空间 / 切换会话
    ├──────────────► GET /api/skills/available?workspaceId=&sessionId=
    │                       │
    │                       ▼
    │                DesktopSkillLoader.listEffectiveSkills(workspaceRoot)
    │                       │  global + workspace，同名 workspace 覆盖
    │                       ▼
    │                [{name, description, scope, path, skillFile, modifyTime}]
    │
    │  ↑/↓ 移动 · Enter 确认 · Esc 关闭 · 鼠标 hover 预览
    ▼
[ComposerInput] chips 行新增一个 Skill（去重，≤5）
    │
    ▼
[发送]
    message = 可见文本（不含 Skill 任何标记）
    skills  = [{ name, scope, path }]
    files   = [{ path, name, fileType, size, type }]
    │
    ▼
[DesktopSkillPromptService]
    ├─ resolve(workspaceRoot, skills)      按 name 精确匹配 effective 列表，不采信前端 path
    └─ buildSkillInstruction(resolved)     生成「显式 Skill 指令块」
    │
    ▼
question = skillInstruction + resourceQuestion
reqJson  = { ...原有字段, skills: [ {name, description, scope, path} ] }
title    = SessionTitleUtil.resolve(displayMessage)
    │
    ▼
[HarnessAgent] 系统 Prompt 已含 Skill 使用规则 + 本轮显式 Skill 指令
    │
    ▼
skill(path) 工具读取 SKILL.md → 按 Skill 流程执行
```

---

## 5. 交互设计（前端）

### 5.1 触发规则

| 项 | 规则 |
| --- | --- |
| 触发字符 | `/` |
| 合法前置 | 行首，或 `/` 前一个字符是空白（空格/换行）。因此 `https://` 不会误触发 |
| query 范围 | `/` 之后到光标的连续非空白、非 `/` 字符，长度 ≤ 20 |
| 光标位置 | 必须位于编辑器内（`editor.contains(range.commonAncestorContainer)`） |
| 输入法 | `event.isComposing || keyCode === 229` 时不触发 |
| 空输入框 | `ComposerInput` 在空态会写入零宽锚点 `\u200B`；检测前须归一化为空格，否则行首 `/` 匹配不到 `(?:^\|\s)` |
| 关闭时机 | `Esc`、选中、光标离开匹配片段、发送、切换会话/工作空间、点击面板外 |
| 面板为空 | 显示「没有匹配的 Skill」，`Enter` 不拦截，按普通发送处理 |

### 5.2 面板布局

```text
┌───────────────────────────────────────────────┐
│ 可用 Skills              ↑↓ 选择 · Enter 确认 · Esc 关闭 │
├──────────────────────┬────────────────────────┤
│ ✦ document-review    │ document-review        │
│ ✦ spring-service…    │ 审查文档结构和内容表达。  │
│ ✦ code-audit         │ 全局 · ~/.agentforge-desktop/skills/document-review │
└──────────────────────┴────────────────────────┘
```

- 定位：`position:absolute; bottom:calc(100% + 8px); left:0; right:0;`，挂在 `.composer` 内（与 `ComposerAddMenu` 同一套上浮定位思路）。
- 列表项最多 8 条；已选 Skill 不出现在列表；已选超限时列表底部提示「最多选择 5 个 Skill」。
- 排序：name 前缀匹配 → name 包含 → description 包含 → 原顺序（scope 为 workspace 的优先展示）。

层级与溢出注意：`.composer` 目前没有 `overflow` 声明，但 `ComposerAddMenu` 的面板用了 `z-index:3100`，权限弹层由 `el-popover` 挂在 `body` 上。因此：

1. 浮层用 `z-index:3050`（低于加号菜单，高于输入框内其他元素），保证两者同时打开时加号菜单在上。
2. 若浮层被 `.composer-input-shell` 的 `overflow-y:hidden` 裁切，改为挂到 `composer-wrap` 层级，用 `bottom: calc(100% - var(--composer-height))` 或直接 `teleport` 语义的「父容器绝对定位」方案。
3. 浮层打开时不改变 `.composer` 高度（绝对定位不占位），避免输入框在打字时上下跳动。

### 5.3 已选 chips 行

```text
┌───────────────────────────────────────────────┐
│ ✦ document-review Skill ×   ✦ code-audit Skill × │
│ 请把这份设计文档过一遍，重点看边界与风险           │
│ ⬆ 权限  ⚙ 模型                            [发送]│
└───────────────────────────────────────────────┘
```

- chip 位于 editor 上方、输入框内部（复用 `SkillManager` 的 `el-icon-magic-stick` 视觉语言）。
- 点击 chip 主体：打开该 Skill 目录（`window.electronAPI.openPath`），便于用户核对内容。
- `×` 移除：只影响 `composerSkills`，不动正文。
- 垂向间距与现有资源行保持一致（与 `chat.css` 中 `has-resources` 行距对齐，避免高度抖动）。

### 5.4 键盘语义（关键冲突处理）

| 场景 | Enter | Shift+Enter | ↑/↓ | Esc |
| --- | --- | --- | --- | --- |
| 面板打开且有匹配 | 选中 Skill，**不发送** | 换行 | 移动高亮 | 关闭面板 |
| 面板打开且无匹配 | 正常发送 | 换行 | 不拦截 | 关闭面板 |
| 面板关闭 | 发送（运行中则追加） | 换行 | — | — |

实现方式：`ComposerInput` 新增函数型 prop `shortcutHandler(event): boolean`，在 `handleKeydown` 最前置调用；返回 `true` 表示已被面板消费，组件内部 `preventDefault()` 并直接返回，**不**再 emit `enter`。这样避免了「父组件 emit 无法同步返回布尔值」的问题。

---

## 6. 后端接口设计

### 6.1 新增：当前生效 Skill 列表

```http
GET /api/skills/available?workspaceId=12
GET /api/skills/available?sessionId=3f1c...      # 会话已绑定工作空间时优先
GET /api/skills/available?projectPath=/Users/me/proj
```

参数优先级：`sessionId` → `workspaceId` → `projectPath`；全部缺省时只返回全局 Skill。

返回（保持与其他 GET 一致的裸数组风格，`client.js` 会自动解包 `{code,msg,data}`）：

```json
[
  {
    "name": "code-audit",
    "description": "按团队规范审查代码改动。",
    "scope": "workspace",
    "path": "/Users/me/proj/.agentforge/skills/code-audit",
    "skillFile": "/Users/me/proj/.agentforge/skills/code-audit/SKILL.md",
    "modifyTime": 1794990000000
  },
  {
    "name": "document-review",
    "description": "审查文档结构和内容表达。",
    "scope": "global",
    "path": "/Users/me/.agentforge-desktop/skills/document-review",
    "skillFile": "/Users/me/.agentforge-desktop/skills/document-review/SKILL.md",
    "modifyTime": 1794969000000
  }
]
```

实现要点：

```java
@GetMapping("/available")
public List<SkillMetadata> available(
        @RequestParam(required = false) Long workspaceId,
        @RequestParam(required = false) String sessionId,
        @RequestParam(required = false) String projectPath) {
    return loader.listEffectiveSkills(workspaceRootResolver.resolve(workspaceId, sessionId, projectPath));
}
```

- **只读解析**：新增 `DesktopSkillWorkspaceResolver`，只查库/校验目录存在，**不复用** `WorkspaceBindingService.prepare`（后者有创建默认目录的副作用，不能出现在查询接口里）。
- 解析失败（目录已删除等）不抛错，降级为只返回全局 Skill，避免输入框浮层因工作空间异常整体不可用。

```java
@Service
public class DesktopSkillWorkspaceResolver {
    /** 三个入口共用一个只读方法；返回 null 表示"无可用工作空间"，loader 会退化为仅全局。 */
    public Path resolve(Long workspaceId, String sessionId, String projectPath) {
        Path path = null;
        if (StringUtils.hasText(sessionId)) {
            AgentSessionPojo session = sessionMapper.selectBySessionId(sessionId.trim());
            if (session != null && StringUtils.hasText(session.getWorkspacePath())) {
                path = Paths.get(session.getWorkspacePath().trim()).toAbsolutePath().normalize();
            }
        }
        if (path == null && workspaceId != null) {
            WorkspacePojo workspace = workspaceMapper.selectById(workspaceId);
            if (workspace != null && StringUtils.hasText(workspace.getFolderPath())) {
                path = Paths.get(workspace.getFolderPath().trim()).toAbsolutePath().normalize();
            }
        }
        if (path == null && StringUtils.hasText(projectPath)) {
            path = Paths.get(projectPath.trim()).toAbsolutePath().normalize();
        }
        return path != null && Files.isDirectory(path) ? path : null;
    }
}
```

注意：`AgentSessionMapper` 已有 `selectBySessionId(sessionId)`（第 22 行，`is_deleted=0`），可直接复用，无需新增 SQL；`sessionId` 已带全局唯一性，不必再传 `aid`。

### 6.2 沿用（不改语义）

| 接口 | 用途 | 说明 |
| --- | --- | --- |
| `GET /api/skills` | 技能管理页全局列表 | 保持只返回 global，避免与 `/available` 混淆 |
| `GET /api/skills/root` | 打开全局目录 | 不变 |
| `POST /api/skills/import` | 导入 Skill | 不变；导入成功后前端需刷新 `availableSkills` |

### 6.3 提交/追加请求扩展

```java
// 新增传输对象
public class SkillRef {
    private String name;   // 必填，唯一标识
    private String scope;  // 可选，仅作展示与快照，不参与解析
    private String path;   // 可选，仅作快照对比；后端不采信
}

// ChatStreamRequest
private List<SkillRef> skills;
public boolean hasSkills() { return skills != null && !skills.isEmpty(); }

// UserMessageAppendController.AppendRequest 同步增加 skills
```

校验规则：

1. `skills` 为空 → 不做任何转换，链路行为与今天完全一致（零回归）。
2. 数量 ≤ `MAX_SELECTED_SKILLS = 5`，超出抛 `IllegalArgumentException("一次最多选择 5 个 Skill")`。
3. `name` 去空白后必须非空、长度 ≤ 128、不含 `/`、`\`、`..`、控制字符。
4. 按 `listEffectiveSkills(workspaceRoot)` 精确匹配 name；匹配不到抛
   `IllegalArgumentException("Skill 不可用或已被移除：" + name)`。
5. 解析结果中的 `path` **一律取自 loader**，前端传入的 path 只用于日志比对，绝不进入 Prompt。
6. 去重：同名只保留第一次出现的位置（保持用户选择顺序）。

---

## 7. 指令规则设定（本方案的核心）

Skill 要被模型可靠使用，必须同时约束「系统层规则」与「本轮消息层指令」。二者职责不同：

| 层 | 位置 | 作用 | 是否随轮次变化 |
| --- | --- | --- | --- |
| 系统层 | `DesktopAgentPrompt.render` 的 `# Skills` 段 | 定义「Skill 是什么、什么时候用、如何读」的通用纪律 | 否（仅随工作空间索引变化） |
| 消息层 | `DesktopSkillPromptService.buildSkillInstruction` | 声明「本轮用户显式选择了哪几个 Skill」，把选择变成硬约束 | 是（每轮） |

### 7.1 系统层规则补强

现有 `DesktopAgentPrompt` 的 `# Skills` 段只有「按 description 匹配就调用 skill」的软约束。新增 4 条：

```text
# Skills
- desktop-skills 中是当前对话可用的 Skill 索引，包含全局 Skill 和当前工作空间 Skill。
- 当用户任务与某个 Skill 的 description 匹配时，先调用 skill，并将索引中的 path 原样传入。
- 用户显式选择 Skill 时，消息中会出现「[用户显式选择的 Skill]」区块：
  · 必须在开始执行前对区块内每个 Skill 调用 skill(path) 读取完整 SKILL.md，不得只依据 description 推断流程；
  · 该轮任务以 Skill 的步骤、检查项与输出格式为准；与通用习惯冲突时优先 Skill，并在回复中说明取舍；
  · 多个 Skill 时按其出现顺序组合执行；若步骤或结论冲突，以后出现者为准，并明确告知用户。
- 显式选择 Skill 不等于跳过必要的上下文阅读：仍需按「先理解再修改」读取相关代码与配置。
- 只加载当前任务需要的 Skill，不要一次性读取索引中的全部 SKILL.md。
```

注意：`skillIndex` 为空时该段落不注入（保持现状），但「显式选择」相关规则不应依赖索引存在——因为存在「Skill 目录临时不可读」的极端情况，此时索引为空但用户已选 Skill，后端 resolve 也会直接报错，故不影响一致性。

### 7.2 消息层指令格式

后端生成的指令块（**固定格式，便于测试断言**）：

```text
[用户显式选择的 Skill]
用户在本轮对话中明确选择了以下 Skill，请按顺序加载并严格遵循：
- name: code-audit
  scope: workspace
  description: 按团队规范审查代码改动。
  path: /Users/me/proj/.agentforge/skills/code-audit
- name: document-review
  scope: global
  description: 审查文档结构和内容表达。
  path: /Users/me/.agentforge-desktop/skills/document-review

执行要求：
1. 开始执行前，依次调用 skill(path) 读取上述每个 Skill 的完整 SKILL.md。
2. 严格遵循 Skill 中的步骤、检查项与输出格式；不要只凭 description 猜测流程。
3. 多个 Skill 冲突时，以靠后的 Skill 为准，并在回复中说明取舍。
4. 不要在回复中原样复述本区块内容。

```

拼接位置：**用户在输入框中写的位置不可表达，因此统一放在 question 最前**，随后接资源转换结果与用户正文：

```text
question = skillInstruction + resourceQuestion
```

即最终顺序为：显式 Skill 指令 → 用户正文（含资源替换后的 `[本地资源(...)：路径]`）。

### 7.3 为什么不由前端拼

1. 前端拼会让「Skill 已删除」这类失效选择静默污染 Prompt，后端可精确报错。
2. 权威路径必须来自 loader 的扫描结果（含同名覆盖后的真实生效项），前端快照可能过期。
3. 追加指令（`/api/chat/append`）、旧 SSE 链路（`/api/chat/stream`）、历史回放三处都要一致，逻辑集中在一处最省。
4. 规则文案属于产品资产，放后端便于统一演进而不发版前端。

---

## 8. 后端转换实现

### 8.1 新增文件

```text
modules/skill/
├── domain/SkillRef.java                    # name / scope / path
├── domain/ResolvedSkill.java               # name / description / scope / path（loader 权威值）
├── service/DesktopSkillPromptService.java  # resolve + buildSkillInstruction
└── service/DesktopSkillWorkspaceResolver.java  # 只读解析 workspaceRoot
```

### 8.2 `DesktopSkillPromptService` 骨架

```java
@Service
public class DesktopSkillPromptService {
    private static final int MAX_SELECTED_SKILLS = 5;

    private final SkillLoader loader;

    /** 按 name 精确解析；返回顺序与用户选择顺序一致，name 去重。 */
    public List<ResolvedSkill> resolve(Path workspaceRoot, List<SkillRef> requested) {
        if (requested == null || requested.isEmpty()) return Collections.emptyList();
        List<SkillMetadata> effective = loader.listEffectiveSkills(workspaceRoot);
        Map<String, SkillMetadata> byName = new LinkedHashMap<String, SkillMetadata>();
        for (SkillMetadata item : effective) byName.put(item.getName(), item);

        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        List<ResolvedSkill> result = new ArrayList<ResolvedSkill>();
        for (SkillRef ref : requested) {
            String name = normalize(ref == null ? null : ref.getName());
            // 空 name 说明前端传了脏数据；静默跳过会让用户"选了却没生效"，直接报错更可诊断
            if (name.isEmpty()) throw new IllegalArgumentException("Skill 名称为空");
            if (!seen.add(name)) continue;
            if (result.size() >= MAX_SELECTED_SKILLS) {
                throw new IllegalArgumentException("一次最多选择 " + MAX_SELECTED_SKILLS + " 个 Skill");
            }
            SkillMetadata skill = byName.get(name);
            if (skill == null) throw new IllegalArgumentException("Skill 不可用或已被移除：" + name);
            result.add(new ResolvedSkill(skill, name));
        }
        return result;
    }

    /** 生成的指令块以空行结尾，便于与后续正文分隔。 */
    public String buildSkillInstruction(List<ResolvedSkill> skills) {
        if (skills == null || skills.isEmpty()) return "";
        StringBuilder out = new StringBuilder("[用户显式选择的 Skill]\n")
            .append("用户在本轮对话中明确选择了以下 Skill，请按顺序加载并严格遵循：\n");
        for (ResolvedSkill skill : skills) {
            out.append("- name: ").append(skill.getName())
               .append("\n  scope: ").append(skill.getScope())
               .append("\n  description: ").append(oneLine(skill.getDescription()))
               .append("\n  path: ").append(skill.getPath());
        }
        out.append("\n\n执行要求：\n")
           .append("1. 开始执行前，依次调用 skill(path) 读取上述每个 Skill 的完整 SKILL.md。\n")
           .append("2. 严格遵循 Skill 中的步骤、检查项与输出格式；不要只凭 description 猜测流程。\n")
           .append("3. 多个 Skill 冲突时，以靠后的 Skill 为准，并在回复中说明取舍。\n")
           .append("4. 不要在回复中原样复述本区块内容。\n\n");
        return out.toString();
    }

    private static String normalize(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        if (trimmed.length() > 128) throw new IllegalArgumentException("Skill 名称过长");
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException("Skill 名称格式非法");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new IllegalArgumentException("Skill 名称格式非法");
            }
        }
        return trimmed;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
```

### 8.3 三处链路接入

**① `ChatTaskServiceImpl.submit`**

```java
WorkspaceBindingService.Binding binding = workspaceBindingService.prepare(
        body.getAid().trim(), sessionId, body.getWorkspaceId(), body.getProjectPath());

// 新增：必须在 binding 之后，因为需要 workspaceRoot
List<ResolvedSkill> skills = skillPromptService.resolve(
        Paths.get(binding.getWorkspacePath()), body.getSkills());
String skillInstruction = skillPromptService.buildSkillInstruction(skills);

String question = skillInstruction + resourceService.buildAgentQuestion(body.getMessage(), resources);
String reqJson = attachmentService.buildUserReqJson(body, resources, displayMessage, skills);
```

**② `ChatController.stream`（旧 SSE 链路）** 同样处理，只是 `skills` 解析异常要走已有的 `notice(...)` 分支。

**③ `UserMessageAppendController.append`**

```java
Path workspaceRoot = workspaceRootResolver.resolveBySession(request.getSessionId());
List<ResolvedSkill> skills = skillPromptService.resolve(workspaceRoot, request.getSkills());
String content = skillPromptService.buildSkillInstruction(skills)
        + resourceService.buildAppendContent(request.getMessage(), request.getFiles());
```

### 8.4 持久化快照

`modules/agent/attachment/DesktopAgentAttachmentService.buildUserReqJson` 增加 `skills` 入参（调用方仅 `ChatController.stream` 与 `ChatTaskServiceImpl.submit` 两处，一并修改即可，无需保留旧重载）：

```java
List<Map<String, Object>> skillSnapshot = new ArrayList<Map<String, Object>>();
for (ResolvedSkill skill : skills) {
    Map<String, Object> item = new LinkedHashMap<String, Object>();
    item.put("name", skill.getName());
    item.put("scope", skill.getScope());
    item.put("path", skill.getPath());
    item.put("description", skill.getDescription());
    skillSnapshot.add(item);
}
req.put("skills", skillSnapshot);
```

落库字段 `ai_agent_message.req`（复用现有 JSON 列），**不需要数据库迁移**。

### 8.5 标题与可见文本

- `displayText` 保持不变（Skill 不在正文里，无需剥离标记）。
- 但「只选 Skill 不写正文」是合法用法，因此需要两处兜底：

```java
// ① 提交校验（ChatTaskServiceImpl.validate / ChatController.stream 同款判断）
boolean hasMessage = request.getMessage() != null && !request.getMessage().trim().isEmpty();
if (!hasMessage && !request.hasFiles() && !request.hasSkills()) {
    throw new IllegalArgumentException("对话内容或资源不能同时为空");
}

// ② 可见文本兜底（SessionTitleUtil 与 initChat 都要求非空）
String displayMessage = resourceService.displayText(body.getMessage());
if (displayMessage.isEmpty() && !resources.isEmpty()) {
    displayMessage = "请处理我提供的本地资源。";
} else if (displayMessage.isEmpty() && body.hasSkills()) {
    displayMessage = "请按所选 Skill 执行。";
}
```

- `SessionTitleUtil.resolve(displayMessage)` 因此不会拿到空串，标题为兜底文案而不是报错。
- 用户气泡由 `replayUserResources` + `replayUserSkills` 渲染，正文为空时气泡内只有 chips，视觉上仍是「有内容」的一轮。

---

## 9. 前端实现

### 9.1 新增组件

| 文件 | 说明 |
| --- | --- |
| `src/components/SkillShortcutPanel.vue` | `/` 浮层。props：`skills` / `selectedSkills` / `query` / `loading`；emits：`select` / `close`；暴露 `handleKey(event): boolean` |
| `src/components/ComposerSkillChip.vue` | 已选 chip（可移除、可点击打开目录），非 inline |
| `src/api/skill.js` 追加 | `fetchAvailableSkills({ workspaceId, sessionId })` |

`SkillShortcutPanel.handleKey` 与参考实现一致：

```js
handleKey(event) {
  if (event.key === 'Escape') { event.preventDefault(); this.$emit('close'); return true }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    const size = this.filteredSkills.length
    if (!size) return false
    event.preventDefault()
    this.currentIndex = (this.currentIndex + (event.key === 'ArrowDown' ? 1 : -1) + size) % size
    return true
  }
  if (event.key === 'Enter' && !event.shiftKey) {
    if (!this.filteredSkills.length) return false      // 无匹配交给发送
    event.preventDefault()
    this.select(this.filteredSkills[this.currentIndex])
    return true
  }
  return false
}
```

### 9.2 `ComposerInput.vue` 改造点

1. 新增 props：`skills: Array`、`shortcutHandler: Function`。
2. template 顶部渲染 chips 行（**在 editor 之外**，`@click.stop`）：

```html
<div v-if="skills.length" class="composer-skill-row">
  <ComposerSkillChip
    v-for="skill in skills"
    :key="skill.name"
    :skill="skill"
    removable
    @remove="$emit('remove-skill', skill.name)"
    @open="$emit('open-skill', skill)"
  />
</div>
<span ref="editor" class="composer-input-editor" ... />
```

3. `handleKeydown` 最前置插入：

```js
handleKeydown(event) {
  if (event.isComposing || event.keyCode === 229) return
  if (this.shortcutHandler && this.shortcutHandler(event)) return   // 面板已消费
  if (event.key !== 'Enter') return
  if (event.shiftKey) return
  event.preventDefault()
  this.$emit('enter', event)
}
```

4. 新增光标上下文与片段删除能力：

```js
// 模块级：与现有 serializeEditor 共用同一套遍历规则（文本 / <br> / DIV 换行 / chip 标记）
function serializeNode(node) {
  if (node.nodeType === Node.TEXT_NODE) {
    return String(node.textContent || '').replace(/[\u200B\u00A0]/g, ' ')
  }
  const element = node
  if (element.dataset && element.dataset.resourceMarker) return element.dataset.resourceMarker
  if (element.tagName === 'BR') return '\n'
  return Array.from(node.childNodes).map(serializeNode).join('')
    + (element.tagName === 'DIV' ? '\n' : '')
}

// 现有 serializeEditor 改为复用 serializeNode，避免两套遍历规则分叉
serializeEditor() {
  const editor = this.$refs.editor
  if (!editor) return ''
  return Array.from(editor.childNodes).map(serializeNode).join('').replace(/\n$/, '')
},

/** 返回光标前的纯文本（不含 chip 节点），最多 limit 个字符。 */
getCaretTextBefore(limit = 40) {
  const editor = this.$refs.editor
  const selection = window.getSelection()
  if (!editor || !selection || !selection.rangeCount) return ''
  const range = selection.getRangeAt(0)
  if (!editor.contains(range.commonAncestorContainer)) return ''
  // 不能用 range.toString()：contenteditable 中 <br> 不产生换行，会把第二行行首的 "/"
  // 误判成行内。这里直接序列化「编辑器起点 → 光标」的片段，规则与 serializeEditor 一致。
  const pre = range.cloneRange()
  pre.selectNodeContents(editor)
  pre.setEnd(range.endContainer, range.endOffset)
  const text = Array.from(pre.cloneContents().childNodes).map(serializeNode).join('')
  return text.slice(-limit)
},

/** 删除光标前的 n 个字符（用于抹掉 "/query" 触发片段）。 */
deleteBeforeCaret(count) {
  const editor = this.$refs.editor
  const selection = window.getSelection()
  if (!editor || !selection || !selection.rangeCount || count <= 0) return
  const caret = selection.getRangeAt(0)
  if (!editor.contains(caret.commonAncestorContainer)) return

  // 只收集「编辑器起点 → 光标」之间的文本节点，从后往前扣减字符数
  const pre = document.createRange()
  pre.selectNodeContents(editor)
  pre.setEnd(caret.endContainer, caret.endOffset)

  const targets = []
  const walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT, null)
  let node
  while ((node = walker.nextNode())) {
    if (!pre.intersectsNode(node)) continue
    const end = node === caret.endContainer ? caret.endOffset : node.length
    if (end > 0) targets.push({ node, end })
  }

  let remaining = count
  let startNode = null
  let startOffset = 0
  for (let i = targets.length - 1; i >= 0 && remaining > 0; i--) {
    const { node: target, end } = targets[i]
    if (remaining <= end) {
      startNode = target
      startOffset = end - remaining
      remaining = 0
    } else {
      remaining -= end
    }
  }
  if (!startNode) return   // 光标前字符不足（含零宽锚点等），放弃删除，避免误杀正文

  const range = document.createRange()
  range.setStart(startNode, startOffset)
  range.setEnd(caret.endContainer, caret.endOffset)
  range.deleteContents()
  editor.normalize()
  this.handleInput()
  this.$nextTick(() => {
    this.ensureCaretAnchor()
    this.resizeShell()
  })
}
```

> 不用 `selection.modify` / `execCommand`：二者在换行与 chip 节点附近的退格粒度不一致，容易出现「删掉整行」或「删到 chip」的问题。上面实现按纯文本字符计数，`/query` 片段与删除长度严格等长。

5. `focus()` 与 `resizeShell()` 需把 chips 行高度计入 `min-height` 计算（现为 `scrollHeight` 自动测）。

6. 空态判定必须纳入 Skill：

```js
isEmpty() {
  return !this.value && !(this.skills && this.skills.length)
}
```

否则「只选了 Skill、没写正文」时仍会显示 placeholder，且 `send-btn` 的 `disabled` 条件、`send()` 的空值早退都需要一并放宽为：

```js
(!text && !resources.length && !skills.length)
```

7. 触发通道要覆盖两条：

```js
// input 事件：键入 /、删除、粘贴都可能改变结尾匹配
handleInput(event) { ... this.$emit('input', text); this.$emit('caret-change') },

// keydown：输入 "/" 的瞬间 input 尚未触发，若只监听 input 会有一帧延迟；
// 同时 <-/->、Home/End 移动光标后也需要重算
handleKeydown(event) {
  if (event.isComposing || event.keyCode === 229) return
  if (this.shortcutHandler && this.shortcutHandler(event)) return
  if (event.key === '/') this.$emit('caret-change')
  if (['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) this.$emit('caret-change')
  ...原有 Enter 逻辑
}
```

父组件把 `@caret-change="detectSkillShortcut"` 与 `@input="onComposerInput"` 都接到同一个检测函数，`props` 变化后 `$nextTick` 再算一次，避免读到旧 DOM。

### 9.3 `ChatWorkbench.vue` 改造点

**data**

```js
skillShortcutOpen: false,
skillShortcutQuery: '',
availableSkills: [],
skillsLoading: false,
composerSkills: [],
```

**computed**

```js
canSelectSkill() { return this.canChat && !this.activeInterrupt },
selectedSkillNames() { return this.composerSkills.map((item) => item.name) },
```

**输入监听**

```js
onComposerInput(value) {
  this.draft = value
  this.syncComposerResources(value)
  this.detectSkillShortcut()
},

detectSkillShortcut() {
  if (!this.canSelectSkill) { this.skillShortcutOpen = false; return }
  const before = this.$refs.composerInput?.getCaretTextBefore?.(40) || ''
  const match = before.match(/(?:^|\s)\/([^\s/]*)$/)
  if (!match || match[1].length > 20) {
    this.skillShortcutOpen = false
    this.skillShortcutQuery = ''
    return
  }
  this.skillShortcutOpen = true
  this.skillShortcutQuery = match[1]
}
```

**模板接线**（`ChatWorkbench.vue` 中 `.composer` 内部）

```html
<div class="composer" @dragover.prevent @drop.prevent="handleComposerDrop">
  <SkillShortcutPanel
    v-if="skillShortcutOpen"
    ref="skillShortcutPanel"
    :skills="availableSkills"
    :selected-skills="composerSkills"
    :query="skillShortcutQuery"
    :loading="skillsLoading"
    @select="selectComposerSkill"
    @close="skillShortcutOpen = false"
  />
  <ComposerInput
    ref="composerInput"
    :value="draft"
    :skills="composerSkills"
    :disabled="!canChat || !!activeInterrupt"
    :placeholder="composerPlaceholder"
    :resources="composerResources"
    :shortcut-handler="handleComposerShortcutKey"
    @input="onComposerInput"
    @caret-change="detectSkillShortcut"
    @enter="onEnter"
    @remove-skill="removeComposerSkill"
    @open-skill="openSkillDirectory"
    @remove-resource="removeComposerResource"
    @preview-resource="previewComposerResource"
    @paste-files="handleComposerPaste"
  />
  <div class="composer-toolbar"> ... </div>
```

`SkillShortcutPanel` 放在 `ComposerInput` **之前**，靠绝对定位悬浮，不参与 flex 高度计算。

**键盘托管**

```js
handleComposerShortcutKey(event) {
  if (!this.skillShortcutOpen) return false
  return Boolean(this.$refs.skillShortcutPanel?.handleKey?.(event))
},
```

模板：`<ComposerInput :shortcut-handler="handleComposerShortcutKey" />`

**选中**

```js
selectComposerSkill(skill) {
  if (!skill) return
  if (this.composerSkills.some((item) => item.name === skill.name)) {
    this.$message.info('该 Skill 已选择')
  } else if (this.composerSkills.length >= 5) {
    this.$message.warning('一次最多选择 5 个 Skill')
  } else {
    this.composerSkills.push({ name: skill.name, scope: skill.scope, path: skill.path, description: skill.description })
  }
  // 抹掉 "/query" 触发片段（含 "/" 本身）
  const removeLength = this.skillShortcutQuery.length + 1
  this.skillShortcutOpen = false
  this.skillShortcutQuery = ''
  this.$nextTick(() => {
    this.$refs.composerInput?.deleteBeforeCaret?.(removeLength)
    this.$refs.composerInput?.focus?.()
  })
},

removeComposerSkill(name) {
  this.composerSkills = this.composerSkills.filter((item) => item.name !== name)
  this.$nextTick(() => this.$refs.composerInput?.focus?.())
},
```

> 若用户是「先输字，再回退到中间插 `/`」，`deleteBeforeCaret` 删除的仍是光标前 N 个字符，与触发片段完全等长，语义正确。

**加载列表**

```js
async loadAvailableSkills() {
  if (!this.canChat) { this.availableSkills = []; return }
  this.skillsLoading = true
  try {
    this.availableSkills = await fetchAvailableSkills({
      workspaceId: this.selectedWorkspaceId,
      sessionId: this.sessionId || null
    })
  } catch (error) {
    this.availableSkills = []
  } finally {
    this.skillsLoading = false
  }
},
```

触发时机：

1. `bootstrap()` 完成后首次拉取；
2. `selectWorkspace` / `clearWorkspace` 之后（并清空 `composerSkills`，因为 workspace Skill 可能已失效）；
3. 打开会话（`applySessionContext` 之前）后用 `sessionId` 拉一次；
4. 首次打开浮层时若 `availableSkills` 为空则补拉（避免每次都请求）；
5. 从 `SkillManager` 返回工作台时重拉（`@saved`/关闭回调）。

**发送**

```js
const skills = this.composerSkills.map(({ name, scope, path }) => ({ name, scope, path }))
if (!text && !resources.length && !skills.length) return
...
const submitted = await submitChatTask({ ..., files: ..., skills })
```

发送成功后 `this.composerSkills = []`；失败回滚时不还原 Skill（与现有 draft 处理保持一致，或按需一并还原，实施时统一）。

**追加**

`sendAppend` 的 payload 增加 `skills`；`pendingAppends` 展示文本前加 `✦ SkillName` 前缀便于用户识别。

**会话上下文**

```js
buildSessionContext() {
  return {
    ...,
    skills: this.composerSkills.map(({ name, scope, path }) => ({ name, scope, path }))
  }
},
```

`applySessionContext` 恢复时按 name 与 `availableSkills` 匹配，匹配不到则丢弃（并 `$message.info('部分 Skill 已不可用，已忽略')`）。watch 列表补 `composerSkills`（deep）。

### 9.4 历史回放

`src/utils/sessionReplay.js`：

```js
function replayUserSkills(record) {
  const req = parseReq(record.req)
  const skills = (req && Array.isArray(req.skills) && req.skills) || []
  return skills.filter((item) => item && item.name).map((item) => ({
    name: item.name,
    scope: item.scope || '',
    path: item.path || '',
    description: item.description || ''
  }))
}
```

用户消息对象增加 `skills` 字段；`UserMessageContent.vue` 增加 `skills` prop，在资源段之前渲染只读 `ComposerSkillChip`（`removable=false`，点击可打开目录）。

### 9.5 样式

- `.composer-skill-row { display:flex; flex-wrap:wrap; gap:6px; margin:0 0 8px; }`
- chip：`height:24px`，蓝紫底（`#f0f4ff` / 边框 `#b9c7ee`），与资源 chip 的灰底形成区分，避免「两个 chip 分不清」。
- 面板：`border-radius:12px`，`box-shadow:0 16px 42px rgba(31,42,68,.18)`，与 `ComposerAddMenu` 保持同一视觉层级。

---

## 10. 边界与风险

| 风险 | 处理 |
| --- | --- |
| 选中的 Skill 在发送前被删除/改名 | 后端 `resolve` 抛错，前端提示「Skill 不可用或已被移除：xxx」，草稿与 chips 保留不丢 |
| 切换工作空间导致 workspace Skill 失效 | 切换时清空 `composerSkills` 并重拉列表；不静默保留失效项 |
| `/` 与 URL 冲突 | 正则要求 `/` 前为行首或空白，`https://` 不触发 |
| `/` 与命令式输入冲突（用户真想输入 `/Users/...`） | 面板只拦截 `↑/↓/Esc/Enter`；用户继续输入后 query 变长，浮层自动关闭；`Esc` 后可自由输入 |
| 输入法组合期误触发 | `isComposing` 双重判断（`keydown` 与 `input` 事件） |
| chips 行导致输入框高度抖动 | `resizeShell` 用 `scrollHeight`，并把 chips 行纳入测量；行高固定 24px |
| 多 Skill 数量失控导致 Prompt 膨胀 | 上限 5；`description` 单行化并截断到 200 字 |
| 前端伪造 path | 后端只用 loader 返回的 path；前端 path 仅作快照 |
| `GET /api/skills/available` 拖慢首屏 | 只在需要时拉取（首次打开浮层/切换空间/打开会话），列表规模为「根目录 + 一层子目录」，可控；可加 60s 内存缓存 + 导入/刷新时失效 |
| 旧 SSE 链路遗漏 | `ChatController.stream` 与 `ChatTaskServiceImpl.submit` 同步改造，并补测试 |
| 追加链路遗漏 | `AppendRequest` 增加 `skills`，`buildAppendContent` 前拼接 Skill 指令 |
| 无 Skill 场景回归 | `skills` 为空时所有逻辑短路，`question` 与现状完全一致 |

---

## 11. 验证

### 11.1 后端单测

`DesktopSkillPromptServiceTest`：

- 正常解析：传入 `[code-audit]` → 返回 workspace 版本（同名覆盖生效）。
- 未知名：`[not-exist]` → `IllegalArgumentException`，消息含 Skill 名。
- 数量上限：6 个 → 抛错。
- 非法名称：`../etc/passwd`、`a/b`、含 `\u0000` → 抛错。
- 空列表 → 返回空字符串，`question` 与无 Skill 完全一致。
- 指令块格式断言（含固定小标题、4 条执行要求、path 来自 loader）。

`DesktopSkillControllerTest`：

- `/api/skills/available` 无参 → 仅全局。
- 带 `workspaceId` → 全局 + workspace 合并，同名取 workspace。
- `workspaceId` 指向已删除目录 → 降级返回全局，不报 500。

### 11.2 前端手测清单

1. 输入 `/` → 浮层出现，列表为当前工作空间生效 Skill。
2. 继续输入 `/doc` → 列表过滤，右侧预览跟随高亮变化。
3. `↑↓` 循环、`Enter` 选中后 `/doc` 片段被删除、chip 出现、光标回到原位。
4. 再次 `/` 选第二个 → 两个 chip 并存，列表不再出现已选项。
5. 选满 5 个后再选 → 提示上限。
6. chip `×` 移除 → 正文不受影响。
7. 面板打开时 `Shift+Enter` 换行、`Enter` 选 Skill 不发送；面板关闭后 `Enter` 正常发送。
8. 发送后气泡上方显示 Skill chip；刷新页面/重开会话后 chip 仍在（回放）。
9. 运行中追加并带 Skill → 追加队列显示 Skill 前缀，生效轮次 Prompt 含指令块。
10. 选中后删除该 Skill 目录再发送 → 明确报错，草稿保留。
11. `/` 后直接 `Enter`（无匹配）→ 正常发送，不残留 `/`。
12. 无任何 Skill 的工作空间输入 `/` → 「没有匹配的 Skill」，发送不受影响。

### 11.3 回归点

- 资源 inline chip 的插入、删除、粘贴、历史回放完全不受影响。
- 技能管理页 `GET /api/skills`、导入、打开目录行为不变。
- 会话上下文保存/恢复（模型、输入、资源、工作空间）不因新增 `skills` 字段失效。

---

## 12. 文件清单

### 新增

```text
agentforge-desktop-server/src/main/java/.../modules/skill/domain/SkillRef.java
agentforge-desktop-server/src/main/java/.../modules/skill/domain/ResolvedSkill.java
agentforge-desktop-server/src/main/java/.../modules/skill/service/DesktopSkillPromptService.java
agentforge-desktop-server/src/main/java/.../modules/skill/service/DesktopSkillWorkspaceResolver.java
agentforge-desktop-server/src/test/java/.../modules/skill/DesktopSkillPromptServiceTest.java
agentforge-desktop-ui/src/components/SkillShortcutPanel.vue
agentforge-desktop-ui/src/components/ComposerSkillChip.vue
```

### 修改

```text
后端
  modules/skill/api/SkillLoader.java                    # 无需改动（listEffectiveSkills 已够用）
  modules/skill/controller/DesktopSkillController.java  # + GET /available
  modules/chat/domain/ChatStreamRequest.java            # + skills
  modules/chat/controller/ChatController.java           # 旧 SSE 链路接入
  modules/chat/controller/UserMessageAppendController.java  # AppendRequest + skills
  modules/chat/service/ChatTaskServiceImpl.java         # resolve + 指令拼接 + reqJson
  modules/agent/attachment/DesktopAgentAttachmentService.java  # buildUserReqJson 增加 skills 快照（2 处调用同步改）
  config/DesktopAgentPrompt.java                        # # Skills 段落补强

前端
  src/api/skill.js                                      # + fetchAvailableSkills
  src/components/ComposerInput.vue                      # + skills / shortcutHandler / getCaretTextBefore / deleteBeforeCaret
  src/components/UserMessageContent.vue                 # + skills 只读渲染
  src/pages/workbench/ChatWorkbench.vue                 # 状态、检测、面板、发送、上下文、回放接线
  src/utils/sessionReplay.js                            # + replayUserSkills
  src/styles/chat.css                                   # chips 行与面板样式
```

---

## 13. 实施阶段

### 阶段一：后端接口与指令转换（可独立验收）

1. `DesktopSkillWorkspaceResolver` 只读解析（session / workspaceId / projectPath）。
2. `DesktopSkillController` 新增 `GET /api/skills/available`，并对异常做降级。
3. `SkillRef` / `ResolvedSkill` / `DesktopSkillPromptService`。
4. `ChatStreamRequest`、`AppendRequest` 扩展 `skills`。
5. 三处链路接入 + `reqJson` 快照。
6. `DesktopAgentPrompt` 规则补强。
7. 单测：解析、上限、非法名、格式断言、降级。

### 阶段二：前端唤起与选择

1. `fetchAvailableSkills` + `availableSkills` 状态与刷新时机。
2. `ComposerSkillChip`、`SkillShortcutPanel`。
3. `ComposerInput` 四个改造点（chips 行、shortcutHandler、光标上下文、片段删除）。
4. `ChatWorkbench` 检测、键盘托管、选中/移除、上限提示。
5. 样式对齐。

### 阶段三：持久化与回放

1. `buildSessionContext` / `applySessionContext` 携带 `skills`，失效项过滤。
2. `sendAppend` 带 `skills`，`pendingAppends` 前缀展示。
3. `sessionReplay` + `UserMessageContent` 回放。

### 阶段四：验证与打磨

1. 单测与手测清单全过。
2. 空态/加载态/无匹配/上限/失效五类提示文案统一。
3. 与 `ComposerAddMenu`、权限弹层、模型选择的层级（z-index）冲突检查。

---

## 14. 验收标准

1. 输入 `/` 可在 300ms 内看到浮层，列表来自后端且为「当前工作空间生效 Skill」（同名 workspace 覆盖 global）。
2. `↑↓/Enter/Esc` 与鼠标 hover 均可用；`Enter` 选中后触发片段被清除、输入框内出现 chip、光标不丢失。
3. 单个输入框可选多个 Skill（≤5），chips 可单独移除，列表自动过滤已选项。
4. 发送请求体包含 `skills[]`，且不含任何 Skill 文本标记；`req.skills` 落库可回放。
5. 送给模型的 question 以「[用户显式选择的 Skill]」区块开头，path 全部来自后端扫描结果。
6. 系统 Prompt 含「显式选择必须读取 SKILL.md」「多 Skill 顺序与冲突取舍」规则。
7. 追加指令与旧 SSE 链路行为一致。
8. 切换工作空间/会话后 chips 与列表同步刷新，失效 Skill 明确提示而不静默丢弃。
9. 无 Skill 场景下，消息、标题、Prompt 与当前版本完全一致（零回归）。