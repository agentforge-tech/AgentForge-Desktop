# AgentForge Desktop 输入框 Skill 唤起与指令实现方案

> 适用范围：AgentForge Desktop（Vue2 + Vite + Electron + 本地 Java Runtime）
>
> 关联能力：`modules/skill`（`DesktopSkillLoader` / `DesktopSkillTool`）、`modules/resource`（`DesktopResourceService`）、`modules/chat`（提交与追加链路）

---

## 1. 结论先行

输入框里任意位置输入 `/` 唤起 Skill 面板，选中后**在光标处插入一个内联标记**，和资源 chip 完全同一套机制。后端只做一件事：把标记替换成 `[使用 Skill：name（path）]`。

不需要 `skills[]` 字段，不需要后端再查一次 Skill 元数据，也不需要单独拼 description / scope / 执行要求。

| 决策点 | 采用方案 | 原因 |
| --- | --- | --- |
| Skill 在输入框中的形态 | 内联标记，与资源标记同构 | 位置语义天然保留（Skill 作用在哪段话就放哪），复用现成的序列化 / 渲染 / 回放 |
| 传输方式 | 就在 `message` 文本里，不新增字段 | 三条链路都已是「取 message → 交给 `DesktopResourceService` 转换」，改一处等于全改 |
| 后端转换 | 正则替换成 `[使用 Skill：name（path）]` | 纯文本替换，不查 loader、不拼 description |
| Skill 列表接口 | **只有 `GET /api/skills`**，可选带 `workspacePath` | 不新增 `/available`；不加 `query` 参数，筛选在前端做，零延迟 |
| 持久化 | 无需新增字段 | `rawMessage` 已存标记，草稿也在会话上下文里，刷新自动还原 |

---

## 2. 现状（不需要重构）

```text
modules/skill/
├── api/SkillLoader.java                        # listGlobalSkills / listEffectiveSkills / skill / importGlobalSkill
├── service/DesktopSkillLoader.java             # listEffectiveSkills(workspaceRoot)：global + workspace，同名 workspace 覆盖
├── controller/DesktopSkillController.java      # GET /api/skills（当前只返回 global）
└── tool/DesktopSkillTool.java                  # skill(path) 读 SKILL.md，自身校验 path 必须在 Skill 根目录内
```

三条链路全部收敛在同一个转换器上：

```text
POST /api/chat/tasks/submit   ChatTaskServiceImpl.submit        displayText + buildAgentQuestion
POST /api/chat/stream         ChatController.stream             同上
POST /api/chat/append         UserMessageAppendController.append  buildAppendContent
                              ↑ 全部来自 DesktopResourceService
```

前端已具备的基础设施：

- `ComposerInput.renderValue`：按标记渲染 inline chip；`serializeEditor` 通过 `data-resource-marker` 把 chip 还原成标记文本；`insertResourceMarkers` 在光标处插入标记并重渲染；`renderValuePreservingCaret` / `locateCaret` 已在处理重绘保光标。
- `utils/resourceType.js`：`RESOURCE_MARKER_SOURCE` / `createResourceMarkerRegExp` / `buildResourceMarker` / `decodeResourcePath`，标记的编解码与类型判定都集中在这里。
- `ChatWorkbench.send()` / `sendAppend()`：取 `draft` + `activeComposerResources(text)`；`buildSessionContext` 保存含标记的 `message`；`stripResourceMarkers` 生成可见文本。
- `sessionReplay.js`：用 `req.rawMessage` 回放输入框原文。

**Skill 只要做成「和资源一样的标记」，链路、持久化、回放全部免费。**

---

## 3. 交互

### 3.1 触发规则

| 项 | 规则 |
| --- | --- |
| 触发字符 | `/`，前置为行首或空白（`https://` 不触发） |
| query | `/` 之后到光标，连续非空白非 `/` 字符，长度 ≤ 20 |
| 光标 | 必须在编辑器内；`isComposing` / `keyCode 229` 时不触发 |
| 空输入框 | 空态零宽锚点 `\u200B` 按空格归一化，否则行首 `/` 匹配不到 |
| 关闭 | `Esc`、选中、光标离开匹配片段、发送、切换会话/工作空间、点击面板外 |
| 无匹配 | 显示「没有匹配的 Skill」，`Enter` 不拦截，按普通发送处理 |

### 3.2 面板

单列列表（不做右侧预览），最多 8 条；`↑↓` 移动、`Enter` 选中、`Esc` 关闭、鼠标可点；已插入的 Skill 不再出现；排序为 name 前缀 → name 包含 → description 包含。

定位 `position:absolute; bottom:calc(100% + 8px)`，`z-index:3050`（低于 `ComposerAddMenu` 的 3100），绝对定位不占位，避免输入框高度抖动。

### 3.3 选中后就是插 chip

1. 删除光标前的 `/query` 片段（长度 = query 长度 + 1）。
2. 在光标处插入标记 `[[agentforge-skill:<name>:<encodedPath>]]`，直接复用 `insertResourceMarkers`。
3. 编辑器重渲染，标记变 Skill chip，光标落在 chip 之后。

删除：chip 上的 `×` 从 `draft` 中删掉该标记字符串（与 `removeComposerResource` 的现有写法一致），正文其余内容不动。

### 3.4 键盘语义

| 场景 | Enter | Shift+Enter | ↑/↓ | Esc |
| --- | --- | --- | --- | --- |
| 面板打开且有匹配 | 选中 Skill，不发送 | 换行 | 移动高亮 | 关闭 |
| 面板打开且无匹配 | 正常发送 | 换行 | 不拦截 | 关闭 |
| 面板关闭 | 发送（运行中则追加） | 换行 | — | — |

实现：`ComposerInput` 新增函数型 prop `shortcutHandler(event): boolean`，在 `handleKeydown` 最前置调用；返回 `true` 表示已消费，组件内不再 emit `enter`。

---

## 4. 标记格式与后端转换（核心）

### 4.1 标记

```text
[[agentforge-skill:<encodeURIComponent(name)>:<encodeURIComponent(path)>]]
```

与资源标记 `[[agentforge-resource:type:encodedPath]]` 并列，共用同一套采集/序列化规则。`path` 由列表接口返回、前端原样写入；即便被伪造，`DesktopSkillTool` 读取时也会用 `insideExistingRoot` 拦截到两个 Skill 根目录之外，不会变成任意文件读取。

### 4.2 转换结果

```text
用户输入：  /code-audit 看下这次改动
question：  [使用 Skill：code-audit（/Users/me/proj/.agentforge/skills/code-audit）] 看下这次改动
```

- 多个 Skill 就产生多段，各自留在用户放置的位置。
- 不输出 description / scope / 执行要求清单——这些要么系统 Prompt 里已有，要么 Skill 正文里有。

### 4.3 实现位置

全部落在 `DesktopResourceService` 一个类里，新增一个 pattern 和一个渲染方法：

```java
private static final Pattern SKILL_PATTERN =
        Pattern.compile("\\[\\[agentforge-skill:([^:\\]]+):([^\\]]+)\\]\\]");

private String skillReference(String encodedName, String encodedPath) {
    return "[使用 Skill：" + decode(encodedName) + "（" + decode(encodedPath) + "）]";
}
```

三个方法各加一次替换（与资源标记的替换并列，顺序自然保持）：

| 方法 | 改动 |
| --- | --- |
| `displayText` | 删资源标记后再删 Skill 标记 → 可见文本 / 标题干净 |
| `buildAgentQuestion` | 标记原位替换为 `[使用 Skill：name（path）]`，不进入 `resources` 的重复追加逻辑 |
| `buildAppendContent` | 同上 |

**因此 `ChatTaskServiceImpl`、`ChatController`、`UserMessageAppendController` 三个调用点一行都不用改。**

### 4.4 只选 Skill 不写正文

`message` 含标记 → 校验通过；但 `displayText` 为空，需要兜底：

```java
// submit 与 stream 各自已有的兜底链，加一个分支
if (displayMessage.isEmpty() && !resources.isEmpty()) {
    displayMessage = "请处理我提供的本地资源。";
} else if (displayMessage.isEmpty()) {
    displayMessage = "请按所选 Skill 执行。";   // 只选了 Skill
}
```

标题由此非空，`SessionTitleUtil.resolve` 正常工作。

---

## 5. 系统 Prompt

`DesktopAgentPrompt.render` 的 `# Skills` 段只加两条，不复述参数细节：

```text
- 用户消息中出现「[使用 Skill：名称（path）]」表示用户显式选择了该 Skill：
  · 开始执行前必须调用 skill(path) 读取完整 SKILL.md，不得只凭索引描述推断流程；
  · 该轮以 Skill 的步骤、检查项与输出格式为准；多个 Skill 冲突时以后出现者为准，并说明取舍。
```

---

## 6. 后端改动清单

| 文件 | 改动 |
| --- | --- |
| `modules/resource/service/DesktopResourceService.java` | 新增 `SKILL_PATTERN`、`replaceSkillMarkers`、`hasSkillMarker`；`displayText` / `buildAgentQuestion` / `buildAppendContent` 各用一次替换 |
| `modules/skill/controller/DesktopSkillController.java` | `list` 增加可选 `workspacePath`，目录不可用降级为全局 |
| `modules/chat/service/ChatTaskServiceImpl.java` | 可见文本兜底加「只选 Skill」分支（以 `hasSkillMarker` 判定） |
| `modules/chat/controller/ChatController.java` | 同上（旧 SSE 链路） |
| `config/DesktopAgentPrompt.java` | 常驻段落新增「显式选择的 Skill」三条规则 |

```http
GET /api/skills                                  # 全局（技能管理页，行为不变）
GET /api/skills?workspacePath=/Users/me/proj     # 全局 + 该工作空间，同名 workspace 覆盖
```

```java
@GetMapping
public List<SkillMetadata> list(@RequestParam(required = false) String workspacePath) {
    Path root = resolveWorkspaceRoot(workspacePath);   // 非目录 → null
    return root == null ? loader.listGlobalSkills() : loader.listEffectiveSkills(root);
}
```

目录不存在时不报错、降级为全局列表，输入框面板不会因为工作空间异常整体不可用。

Skill 规则放在 `DEFAULT` 的常驻段落而非 `# Skills`：后者只在 `skillIndex` 非空时注入，而「用户显式选择」必须始终生效。

---

## 7. 前端改动清单

### 7.1 `src/utils/resourceType.js`

与资源标记并列新增，保持编解码只有一份：

```js
export const SKILL_MARKER_SOURCE = '\\[\\[agentforge-skill:([^:\\]]+):([^\\]]+)\\]\\]'
export function createSkillMarkerRegExp() { return new RegExp(SKILL_MARKER_SOURCE, 'g') }
export function buildSkillMarker(name, path) {
  return `[[agentforge-skill:${encodeURIComponent(name)}:${encodeURIComponent(path)}]]`
}
```

### 7.2 `src/api/skill.js`

```js
export function fetchSkills({ workspacePath } = {}) {
  const query = workspacePath ? `?workspacePath=${encodeURIComponent(workspacePath)}` : ''
  return request(`/api/skills${query}`)
}
```

### 7.3 `src/components/SkillShortcutPanel.vue`（新增）

props：`skills` / `insertedNames` / `query` / `loading`；emits：`select` / `close`；暴露 `handleKey(event): boolean`。筛选在组件内完成，取前 8 条。

### 7.4 `src/components/ComposerSkillChip.vue`（新增）

`contenteditable=false` 的 inline span，复用 `data-resource-marker`，这样 `serializeEditor` 无需改动即可把它还原成标记；另带 `data-skill-name` / `data-skill-path` 供点击时直接取用，不必再解析标记：

```html
<span class="composer-inline-skill" contenteditable="false"
      :data-resource-marker="marker"
      :data-skill-name="name" :data-skill-path="path" :title="path">
  ✦ {{ name }} <button class="inline-skill-remove">×</button>
</span>
```

> 删除按钮不写 `@click.stop`：inline chip 在编辑器内，点击由 `ComposerInput.handleEditorClick` 统一委托处理，避免事件被提前吞掉。

### 7.5 `src/components/ComposerInput.vue`

| 位置 | 改动 |
| --- | --- |
| `renderValue` | 用 `RESOURCE_MARKER_SOURCE \| SKILL_MARKER_SOURCE` 组合一次扫描：资源走分组 1/2，Skill 走 3/4，各自按原文位置渲染 |
| `serializeValue` | 一并去掉 Skill 标记（空态判断用） |
| `handleEditorClick` | 增加 `.composer-inline-skill` 分支：`×` → emit `remove-skill`（带 name），主体 → emit `open-skill`（带 path） |
| `handleKeydown` | 最前置调用 `shortcutHandler`；`/`、`←→`、`Home/End` 时 emit `caret-change` |
| 新增 | `getCaretTextBefore(limit)`：序列化「编辑器起点 → 光标」后取尾部（不能用 `range.toString()`，`<br>` 不产生换行）；`deleteBeforeCaret(n)`：按纯文本字符数从后往前扣减，删完显式把光标收敛到删除点 |
| 新增 | `serializeNodes(nodes)`：`serializeEditor` 与 `getCaretTextBefore` 共用同一套遍历规则，避免两套实现分叉 |
| `insertResourceMarkers` | 插入后按「最后一个标记原文」定位 chip（`focusAfterMarker`），资源与 Skill 共用 |
| `isEmpty` | 纳入 `skills`，「只选 Skill 不写正文」不再被当成空输入 |

### 7.6 `src/pages/workbench/ChatWorkbench.vue`

```js
data: { availableSkills: [], skillsLoading: false, skillShortcutOpen: false, skillShortcutQuery: '', skillShortcutDismissed: false }
```

- `loadAvailableSkills()`：取当前 `selectedWorkspaceId` 对应工作空间的 `folderPath` 调 `fetchSkills({ workspacePath })`；时机为切换 Agent 后、切换/清空工作空间后、从技能管理页返回、首次打开面板且列表为空。
- `onComposerInput` / `@caret-change` → `detectSkillShortcut()`：读 `getCaretTextBefore(40)` 决定开关与 query。
- `selectComposerSkill(skill)`：`deleteBeforeCaret(query.length + 1)` → `insertResourceMarkers(buildSkillMarker(name, path))` → 关面板；同名不重复插入。
- `removeComposerSkill(name)`：从 `draft` 中删掉该标记字符串（与 `removeComposerResource` 同写法）。
- `handleComposerShortcutKey(event)`：面板打开时委托 `panel.handleKey`，返回值交给 `shortcutHandler`。
- `composerSkills`：computed，直接从 `draft` 标记解析，不额外维护一份状态，避免两处不同步。
- `stripResourceMarkers`：一并去掉 Skill 标记，保证可见文本干净。
- `dismissSkillShortcut`：用户按 `Esc` 后置 `skillShortcutDismissed`，只要 `/query` 片段还在就不自动重开，片段消失后自动复位。

### 7.7 回放与上下文

- `sessionReplay.js` 无需改：`replayUserRawText` 直接返回含 Skill 标记的 `rawMessage`。
- `UserMessageContent.segments()` 增加一次 Skill 标记扫描，在原文位置渲染只读 `ComposerSkillChip`（`removable=false`，点击打开目录）。
- 会话上下文无需新增字段：`buildSessionContext` 存的 `message` 就是含标记的草稿，`applySessionContext` 还原后 chip 自动回来。

---

## 8. 边界与已知取舍

| 情况 | 行为 |
| --- | --- |
| Skill 发送前被删除 | 不校验。模型按 path 调 `skill()` 失败并回报用户；输入框与草稿不受影响 |
| Skill 被改名 | 同上；旧 name 的 chip 仍可见，用户可自行删除 |
| 切换工作空间 | 重拉列表即可；已插入的 chip 属于正文，不自动清除（用户可见、可删） |
| `/` 与 URL 冲突 | 前置必须是行首或空白，`https://` 不触发 |
| 用户真想输入 `/Users/...` | 面板只拦截 `↑↓/Esc/Enter`，继续输入后 query 变长自动关闭；`Esc` 后可自由输入 |
| 输入法组合期 | `isComposing` / `keyCode 229` 双重判断 |
| chip 造成高度抖动 | 复用 `resizeShell` 的 `scrollHeight` 测量，行高固定 24px |
| 只选 Skill 不写正文 | 后端兜底可见文本「请按所选 Skill 执行。」，校验与标题均正常 |
| 无 Skill 场景 | 没有标记就没有替换，`question` 与现状完全一致（零回归） |

---

## 9. 验证要点

后端：

1. `DesktopResourceServiceTest` 补三条：标记替换格式正确、`displayText` 剥离干净、无标记时输出与改造前一致。
2. `GET /api/skills` 无参 → 仅全局；带 `workspacePath` → 全局 + 工作空间合并、同名取 workspace；目录不存在 → 降级全局，不 500。

前端手测：

1. 行首、句中、多行第二行输入 `/` 都能唤起；`https://` 不唤起。
2. `↑↓/Enter` 选中后 `/query` 片段消失、chip 落在光标位置、光标在 chip 之后。
3. 同一 Skill 重复选择不重复插入；`×` 只移除该 chip。
4. 面板打开时 `Enter` 只选 Skill 不发送；无匹配时 `Enter` 正常发送。
5. 发送后气泡内 chip 在原文位置；刷新 / 重开会话后仍在。
6. 运行中追加带 Skill：本轮 Prompt 含 `[使用 Skill：…]`。
7. 只选 Skill 发送：正常出会话，标题为兜底文案。
