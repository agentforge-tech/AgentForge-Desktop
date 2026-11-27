# AgentForge Desktop 本地资源输入与模态识别实现

> 适用范围：AgentForge Desktop（Vue2 + Vite + Electron + 本地 Java Runtime）
>
> 关联框架：`agentforge-agent-core`、`agentforge-harness-agent`、`agentforge-model-api`
>
> 目标：说明桌面工作台输入框中的图片/文件/文件夹如何原位填充、如何随消息进入 Agent、如何完成 prompt 替换与多模态附件转换，以及 Agent 如何通过 `read_resource` 工具主动读取本地资源。

---

## 1. 背景与桌面端差异

Web 版 AgentForge 需要把文件上传到服务端，接口返回 `fileId` 和 `visitUrl`，再由服务端反解本地路径。

桌面版 AgentForge Desktop 的 Runtime 与用户文件在同一台机器上，因此**不需要上传接口**：

- 输入框直接引用本机绝对路径；
- 请求只携带资源元信息（路径、名称、类型、大小）；
- 图片在进入模型前由本地图片加载器读取为 Base64；
- 文本文件直接读取正文；文件夹交给 Agent 的 `read_resource` 工具按需展开。

因此桌面端的关键点从「上传与 URL 可达性」变成了「资源原位表达、路径安全、模态路由、历史回放」。

---

## 2. 总体链路

```text
[Electron]
  选择文件 / 文件夹 / 剪贴板图片
        │
        ▼
[输入框 ComposerInput]
  在光标位置插入资源标记：
  [[agentforge-resource:image:%2FUsers%2Fme%2Fa.png]]
        │
        ▼
[发送]
  message = 含标记的原始文本
  files   = [{ path, name, fileType, size, type }]
        │
        ▼
[Desktop Server]
  DesktopResourceService
    ├─ displayText(message)          去掉标记，得到用户可见文本
    ├─ buildAgentQuestion(message)   按原位置替换为 [本地资源(type)：路径]
    └─ resolve(files)                校验路径、类型、数量
        │
        ├─ DesktopAgentAttachmentService
        │     ├─ 图片：AgentAttachment(INLINE/URL/TEXT)
        │     ├─ 文本：AgentAttachment(TEXT + 正文)
        │     └─ 文件夹/文档：AgentAttachment(TEXT + 路径引用)
        │
        ▼
[HarnessAgent / ReActAgent]
  BaseAgent.buildUserMessage
    TextContent(prompt) + ImageContent / TextContent(attachments)
        │
        ▼
[模型协议]
  OpenAI: content[{type:"text"}, {type:"image_url"}]
  Anthropic: content[{type:"text"}, {type:"image", source:{type:"base64"}}]
```

运行期 Agent 主动读取：

```text
模型需要更多资源
   │
   ▼
read_resource(paths=[本地路径或 URL])
   │
   ▼
DesktopReadResourceTool -> DesktopLocalReadFetcher
   │  本地图片：返回元数据
   │  本地文本：返回元数据 + 正文摘要
   │  文件夹：返回目录概览
   ▼
ReadArtifactMiddleware.afterToolExecution 收集
   │
   ▼
ReadArtifactMiddleware.beforeModelCall 装配附件
   │
   ▼
AttachmentContentAssembler -> UserMessage(TextContent + ImageContent)
```

---

## 3. 输入框资源交互与标识协议

### 3.1 相关前端组件

| 组件 | 作用 |
| --- | --- |
| `ComposerInput.vue` | contenteditable 输入框，负责光标位置插入、序列化与反序列化 |
| `ComposerResourceChip.vue` | 输入框内联资源 Chip，展示图标、名称和移除按钮 |
| `UserMessageContent.vue` | 用户消息气泡中的资源原位渲染，保证与输入框一致 |
| `ChatWorkbench.vue` | 选择资源、拖拽、粘贴、剪贴板、发送与回放 |
| `electron/main.mjs` | 原生文件/文件夹选择与剪贴板读取 |

### 3.2 资源标记格式

统一标记：

```text
[[agentforge-resource:<type>:<encodeURIComponent(path)>]]
```

例如：

```text
请分析这张截图 [[agentforge-resource:image:%2FUsers%2Fedy%2FDesktop%2Ferror.png]] 并修复问题
```

类型 `type` 用于 UI 图标与后端兜底识别：

| type | 含义 |
| --- | --- |
| `image` | 图片 |
| `folder` | 文件夹 |
| `document` | 文档/文本文件 |
| `pdf` | PDF |
| `html` | HTML |
| `file` | 其他普通文件 |

### 3.3 原位填充

`ComposerInput` 的工作方式：

1. 输入框是 contenteditable 容器；
2. 资源标记在 DOM 中渲染为不可编辑的 Chip；
3. 序列化时 Chip 还原为标记字符串，光标位置与前后文本顺序完整保留；
4. 反序列化时按标记拆分文本与 Chip，重新渲染。

关键方法：

- `insertResourceMarkers(markers)`：在保存的光标位置插入标记；
- `renderValue()`：把 `value` 中的标记还原为 Chip；
- `serializeEditor()`：把 Chip 还原为标记；
- `focusAfterResource(path)`：插入后把光标移动到最后一个资源之后。

### 3.4 输入框布局

- `+` 固定在输入框工具栏左侧；
- 「上传文件」为一级菜单，二级菜单提供「本地文件」「本地文件夹」；
- 新建会话：工作空间选择器位于输入框下方的灰色底栏中；
- 已有会话：工作空间选择器移动到输入框上方的灰色底栏中；
- 非工作空间会话：隐藏工作空间选择器及其灰色底栏，输入框恢复完整圆角；
- 工作空间会话：保留工作空间选择器与灰色底栏。

### 3.5 资源来源

| 来源 | 处理方式 |
| --- | --- |
| 「+」→「上传文件」子菜单选择本地文件/文件夹 | Electron `dialog.showOpenDialog` → `describeResources` |
| 拖拽文件 | `webUtils.getPathForFile(file)` → IPC `describe-resources` 补全元信息 |
| 粘贴文件 | `getPathForFile` → 同上；拿不到路径时读取剪贴板 |
| 粘贴剪贴板图片 | `clipboard.readImage().toPNG()` 写入临时目录 |
| 历史回放 | 从 `req.rawMessage` 还原标记 |

单条消息可不限数量地引用资源，前端与后端均不做数量限制（仅按绝对路径去重）。

### 3.6 类型判定统一口径

拖拽与粘贴只能拿到路径。如果渲染进程自行按扩展名推断，无扩展名的文件夹会被判成普通文件：
输入框显示文件图标、标记写成 `:file:`；而后端按真实文件系统识别为 `folder`，
重新进入会话时气泡又按 `folder` 渲染，于是出现**同一资源在输入框与历史消息里图标不一致**。

因此三处必须使用同一口径，优先级为「目录 → 扩展名 → 客户端声明 → `file`」：

| 层 | 实现 | 说明 |
| --- | --- | --- |
| Electron 主进程 | `describeResource` / IPC `describe-resources` | `fs.statSync` 判定目录，返回 `fileType/type/size` |
| 渲染进程 | `utils/resourceType.js`、`utils/resourceMeta.js` | `inferResourceType` 兜底推断，`normalizeResource` 统一字段 |
| 后端 | `DesktopResourceService#resolveType` | `Files.isDirectory` 优先，扩展名次之，最后才采信请求声明 |

补充规则：

- 目录只能由文件系统确证，Electron 判定为 `folder` 时前端直接采信；
- 扩展名是强证据，可纠正过期类型（例如历史数据里 `.png` 被记成 `file`）；
- 后端 `buildAgentQuestion` / `buildAppendContent` 输出的 `[本地资源(type)]` 以解析结果为准，
  不使用标记里的历史类型，避免过期类型泄漏进模型提示；
- `ComposerInput` 与 `UserMessageContent` 渲染 Chip 时同样以资源真实类型为准；
- 会话上下文恢复出的资源若为「无扩展名 + `type=file`」，会异步回主进程核验真实类型，
  并按结果改写 `draft` 中的标记，避免再次序列化又退化成 `file`。


---

## 4. 用户消息列表与输入框保持一致

### 4.1 问题

如果用户消息只展示「资源卡片 + 纯文本」，资源位置会丢失，与输入框中的原位效果不一致。

### 4.2 实现

用户消息对象同时保存：

```javascript
{
  role: 'user',
  text: '请分析这张截图  并修复问题',   // 去掉标记的可见文本
  rawText: '请分析这张截图 [[agentforge-resource:image:...]] 并修复问题',
  resources: [{ path, name, fileType, size, type }]
}
```

`UserMessageContent.vue` 按 `rawText` 中的标记拆分，文本与 Chip 原位交替渲染，因此：

- 输入框里资源在句子中间，消息气泡里也在同一位置；
- 文本换行、前后空格、多个资源顺序保持一致；
- 历史消息通过 `req.rawMessage` 还原相同的原位效果。

### 4.3 历史回放

`ai_agent_session_message.req` 保存请求快照：

```json
{
  "message": "请分析这张截图  并修复问题",
  "rawMessage": "请分析这张截图 [[agentforge-resource:image:%2F...]] 并修复问题",
  "files": [
    {
      "path": "/Users/edy/Desktop/error.png",
      "originFileName": "error.png",
      "fileType": "png",
      "fileSize": 183462,
      "type": "image"
    }
  ]
}
```

`sessionReplay.js` 读取 `req.rawMessage` 生成 `rawText`，`UserMessageContent` 负责最终渲染。

---

## 5. 后端请求与 prompt 替换

### 5.1 请求结构

`ChatStreamRequest`：

```java
private String aid;
private String sessionId;
private String message;
private Long modelId;
private Long workspaceId;
private String projectPath;
private List<ResourceRef> files;
```

`ResourceRef`：

```json
{
  "path": "/Users/edy/Desktop/error.png",
  "name": "error.png",
  "fileType": "png",
  "size": 183462,
  "type": "image"
}
```

### 5.2 `DesktopResourceService`

| 方法 | 作用 |
| --- | --- |
| `resolve(files)` | 校验资源存在、去重、限制数量、识别类型与大小 |
| `displayText(message)` | 去掉资源标记，得到消息列表展示文本 |
| `buildAgentQuestion(message, resources)` | 标记原位替换为 `[本地资源(type)：路径]`，并追加读取提示 |
| `buildAppendContent(message, files)` | 运行期追加消息的标记替换 |

替换示例：

```text
模型实际收到的 question：
请分析这张截图
[本地资源(image)： /Users/edy/Desktop/error.png]
并修复问题

以上资源来自用户本机。图片会作为附件提供；其他文件或文件夹可使用 read_resource 工具按路径读取，或使用文件工具在工作空间内检索。
```

### 5.3 校验顺序

`ChatTaskServiceImpl.submit` 中先解析资源，再创建会话：

1. 解析并校验资源；
2. 生成展示文本、Agent question、req 快照；
3. 创建会话与用户/AI 消息；
4. 创建 Runtime；
5. 启动 Agent。

这样资源非法时不会产生「会话已创建但消息失败」的脏数据。

---

## 6. 附件转换与模型模态路由

### 6.1 `DesktopAgentAttachmentService`

把 `ResolvedResource` 转成框架层 `AgentAttachment`：

| 资源 | AgentAttachmentType | 默认载荷 |
| --- | --- | --- |
| 图片 | `IMAGE` | `INLINE`（本地路径）或 `URL`（公网且非 Anthropic） |
| 文本文件 | `TEXT` | `TEXT`，正文最多 12000 字符 |
| 文件夹 | `UNKNOWN` | `TEXT`，附路径、目录内容概览与 `read_resource` 提示 |
| PDF/Office 等 | `UNKNOWN` | `TEXT`，附路径引用 |

图片超过 4MB 或模型不支持视觉时降级为 `TEXT`。

### 6.2 `DesktopAttachmentRoutingService`

决策顺序：

```text
模型 supportsImage = false  -> TEXT
非图片                      -> TEXT
Anthropic                   -> INLINE
公网 URL 且非 Anthropic     -> URL
本地路径/私网且 <= 4MB      -> INLINE
其他                        -> TEXT
```

### 6.3 模型视觉能力

`DesktopModelCapabilities`：

1. 模型配置显式 `supportsImage` 优先；
2. 否则查询 `LlmModelConfigRegistry`；
3. 未注册的自定义模型：
   - Anthropic 协议默认按视觉模型处理；
   - 其他协议默认仅文本。

桌面端模型配置页新增「图片输入能力」：自动 / 支持图片 / 仅文本。

### 6.4 多模态消息装配

框架层 `BaseAgent.buildUserMessage` 使用 `AttachmentContentAssembler`：

- 文本附件 → `TextContent`；
- INLINE 图片 → `ImageContent.fromBase64`；
- URL 图片 → `ImageContent.fromUrl`；
- 最终形成 `UserMessage(List<Content>)`。

`DesktopLocalImageLoader` 注册为 `AttachmentContentAssembler` 的全局默认图片加载器，支持：

- 本机绝对路径；
- `file://`；
- `http/https`。

---

## 7. AgentForge 核心框架工具与中间件对接

### 7.1 工具注册

`AgentRuntimeService.build` 中：

```java
.additionalTool(new DesktopReadResourceTool())
```

`DesktopReadResourceTool`：

- 工具名与框架保持一致：`read_resource`；
- 入参：`paths: List<String>`、`hint: String`；
- 支持本地绝对路径与 http/https；
- 返回稳定 JSON 契约 `agentforge.read.v1`。

### 7.2 读取器

`DesktopLocalReadFetcher`：

| 资源 | 返回 |
| --- | --- |
| 图片 | `kind=IMAGE`、fileType、size、mimeType |
| 文本/代码 | `kind=TEXT`、正文摘要（最多 12000 字符） |
| 文件夹 | `kind=FILE`、目录概览、`extra.isDirectory=true` |
| 其他 | `kind=FILE`、元数据 |
| http/https | 委托框架 `HttpReadResourceFetcher` |

### 7.3 中间件注册

`ReadArtifactMiddleware` 放在追加消息中间件之后、压缩/日志/流式中间件之前：

```text
UserMessageAppendMiddleware
ReadArtifactMiddleware
LoggingIAgentMiddleware
ServiceStreamingMiddleware
SessionLifecycleMiddleware
```

作用：

1. `afterToolExecution` 收集 `read_resource` 返回的元数据；
2. `beforeModelCall` 把图片资源装配成附件并加入本轮模型消息；
3. 本轮结束或异常时清理暂存队列；
4. 同一轮重复路径按 `dedupeKey` 去重。

`ReadArtifactMiddleware` 的视觉能力来自当前模型：

```java
ReadArtifactMiddleware.builder()
    .supportsImage(modelCapabilities.supportsImage(model))
    .requiresInlineImage(modelCapabilities.requiresInlineImage(model))
    .build();
```

### 7.4 系统提示词

`DesktopAgentPrompt` 新增本地资源说明：

- 消息中的 `[本地资源(type)：路径]` 是用户提供的资源；
- 图片会作为多模态附件；
- 文本、代码、文件夹可用 `read_resource` 读取；
- 资源可能不在工作空间内，优先使用消息中的真实本机路径。

---

## 8. 持久化与历史回放

### 8.1 增量 SQL

```sql
ALTER TABLE ai_agent_session_message ADD COLUMN req TEXT DEFAULT NULL;
```

文件：

```text
agentforge-desktop-server/sql/sqllite/1.0.x/20261122000000_desktop_message_req.sql
```

### 8.2 写入时机

`AgentSessionService.initChat(..., reqJson)` 在插入用户消息时写入：

```java
userMessage.setContent(displayMessage);
userMessage.setReq(reqJson);
```

`content` 保存可见文本，`req` 保存请求快照（含 `rawMessage` 与 `files`），两者职责分离。

### 8.3 回放

`SessionMessageVo.req` 返回给前端；`sessionReplay.js` 解析：

- `req.files` → 资源元数据；
- `req.rawMessage` → 原位标记；
- `UserMessageContent` → 与输入框一致的内联 Chip。

---

## 9. 关键接口与数据结构

### 9.1 发送对话

```http
POST /api/chat/tasks/submit
```

```json
{
  "aid": "agt_default",
  "sessionId": "chat-xxx",
  "message": "请分析这张截图 [[agentforge-resource:image:%2FUsers%2Fme%2Fa.png]] 并修复问题",
  "modelId": 1,
  "workspaceId": null,
  "projectPath": null,
  "files": [
    {
      "path": "/Users/me/a.png",
      "name": "a.png",
      "fileType": "png",
      "size": 183462,
      "type": "image"
    }
  ]
}
```

### 9.2 追加消息

```http
POST /api/chat/append
```

同样携带 `message` 与 `files`。追加消息不重新装配首轮附件，只把标记替换为路径引用，Agent 可通过 `read_resource` 继续读取。

### 9.3 会话消息回放

```http
POST /api/agent/session-messages/detail
```

返回 `SessionMessageVo.req`，前端据此还原资源位置。

---

## 10. 边界与风险

| 场景 | 处理 |
| --- | --- |
| 资源被移动/删除 | 发送前校验，返回明确错误，不创建会话 |
| 超过 5 个资源 | 前端提示，后端再次校验 |
| 模型不支持图片 | 图片降级为文本路径引用 |
| 图片超过 4MB | INLINE 降级为 TEXT，避免请求体过大 |
| 文本文件过大 | 最多读取 12000 字符 |
| 文件夹 | 首次附件附带目录概览，Agent 可按需继续读取 |
| Anthropic | 强制 INLINE，避免外链图片协议错误 |
| 非桌面环境 | 浏览器模式不提供本地文件选择，提示使用桌面端 |
| 历史消息缺少 `rawMessage` | 退化为「文本 + 资源列表」展示 |

---

## 11. 验证

### 11.1 自动验证

```bash
cd agentforge-desktop-server && mvn -o test
cd agentforge-desktop-ui && npm run build
```

新增测试：

- `DesktopResourceServiceTest`：资源解析、标记替换、数量限制，
  以及「标记类型过期时以文件系统/扩展名确证结果为准」；
- `DesktopAttachmentRoutingServiceTest`：多模态载荷路由。

### 11.2 手工验证

| 场景 | 预期 |
| --- | --- |
| 输入框中间插入图片 | 图片 Chip 出现在文本中间，发送后气泡位置一致 |
| 仅添加资源不输入文字 | 正常发送，消息气泡只展示资源 Chip |
| 视觉模型 + 本地图片 | 模型能描述图片内容 |
| 文本模型 + 本地图片 | 模型收到路径引用，不报错 |
| 添加文本文件 | 模型收到正文（超长截断） |
| 添加文件夹 | 首次附件即包含目录概览，Agent 可继续调用 `read_resource` 深入读取 |
| 刷新/切换会话 | 资源 Chip 与输入时位置一致 |
| 拖拽无扩展名文件夹 | 输入框即显示文件夹图标，发送后与重新进入会话的图标一致 |
| 运行中追加含资源消息 | 追加内容进入上下文，Agent 可按路径读取 |

---

## 12. 文件清单

### 12.1 前端

```text
agentforge-desktop-ui/src/components/ComposerInput.vue
agentforge-desktop-ui/src/components/ComposerResourceChip.vue
agentforge-desktop-ui/src/components/UserMessageContent.vue
agentforge-desktop-ui/src/pages/workbench/ChatWorkbench.vue
agentforge-desktop-ui/src/utils/resourceType.js
agentforge-desktop-ui/src/utils/resourceMeta.js
agentforge-desktop-ui/src/utils/sessionReplay.js
agentforge-desktop-ui/src/styles/chat.css
agentforge-desktop-ui/electron/main.mjs
agentforge-desktop-ui/electron/preload.cjs
```

### 12.2 后端

```text
agentforge-desktop-server/src/main/java/.../modules/resource/
├── domain/ResourceRef.java
├── domain/ResolvedResource.java
├── loader/DesktopLocalImageLoader.java
├── read/DesktopReadResourceTool.java
├── read/DesktopLocalReadFetcher.java
└── service/DesktopResourceService.java

agentforge-desktop-server/src/main/java/.../modules/agent/attachment/
├── DesktopModelCapabilities.java
├── DesktopAttachmentRoutingService.java
└── DesktopAgentAttachmentService.java

agentforge-desktop-server/src/main/java/.../modules/agent/runtime/AgentRuntimeService.java
agentforge-desktop-server/src/main/java/.../config/DesktopAgentPrompt.java
agentforge-desktop-server/src/main/java/.../modules/chat/domain/ChatStreamRequest.java
agentforge-desktop-server/src/main/java/.../modules/chat/service/ChatTaskServiceImpl.java
agentforge-desktop-server/src/main/java/.../modules/chat/controller/UserMessageAppendController.java
agentforge-desktop-server/src/main/java/.../modules/session/service/AgentSessionService.java
agentforge-desktop-server/src/main/java/.../modules/session/dao/AgentSessionMessageMapper.java
```

### 12.3 SQL

```text
agentforge-desktop-server/sql/sqllite/1.0.x/20261122000000_desktop_message_req.sql
```

---

## 13. 输入框与工作空间 UI 设计思路与细节

### 13.1 视觉模型

输入区由「白色输入卡片 + 灰色底栏」组成，整体是一个连续的圆角容器：

- 白色卡片承载文本输入、`+`、模型选择与发送按钮；
- 灰色底栏承载「选择工作空间」；
- 灰色底栏左右贯通到容器边缘，并在外侧保留 16px 圆角；
- 白色卡片使用 16px 圆角，压在灰色底栏上，让底部圆角处自然露出灰底。

颜色与尺寸基线：

| 项目 | 取值 |
| --- | --- |
| 内容最大宽度 | `760px`，输入区与会话消息列表保持一致并居中 |
| 灰色底栏背景 | `#f2f3f5` |
| 容器描边 | `#e7e9ed` |
| 卡片圆角 | `16px` |
| 底栏外侧圆角 | `16px` |
| 底栏与卡片重叠 | `16px` |
| 工作空间按钮字号 / 行高 | `12px / 18px` |

### 13.2 两种状态：新建会话与已有会话

输入区根据当前是否已有消息切换两种布局：

| 状态 | 工作空间底栏位置 | 视觉 |
| --- | --- | --- |
| 新建会话（无消息） | 输入框**下方** | 白色卡片在上，灰色底栏在下 |
| 已有会话（有消息） | 输入框**上方** | 灰色底栏在上，白色卡片在下 |

实现方式：

- `.composer-surface` 使用纵向 flex；
- `.composer` 与 `.composer-workspace-row` 通过 `order` 切换上下位置；
- 已有会话时添加 `is-history` 状态类；
- 两种状态使用镜像内边距，保证灰色底栏**可见高度一致**：
  - 新建会话：`padding: 22px 12px 7px`，向上重叠 `-16px`；
  - 已有会话：`padding: 7px 12px 22px`，向下重叠 `-16px`。

### 13.3 非工作空间会话的隐藏规则

工作空间底栏的显示规则：

```text
新建会话                         -> 始终显示
已有会话 + workspaceId 存在      -> 显示（位于输入框上方）
已有会话 + workspaceId 为空      -> 隐藏选择器与灰色底栏
```

隐藏后：

- 不渲染 `.composer-workspace-row`；
- 输入框恢复完整 16px 圆角；
- 不再出现灰色背景带。

这样非工作空间会话不会展示无意义的工作空间选择入口，同时工作空间会话仍能快速确认当前空间。

### 13.4 `+` 按钮

- 位置固定在输入框工具栏左侧，不随工作空间底栏移动；
- 去掉外边框，改为透明图标按钮；
- hover 时显示浅色背景，按下时颜色略深；
- 禁用态降低颜色对比度；
- 使用 `mousedown.prevent` 避免点击按钮时抢走编辑器焦点。

### 13.5 `+` 菜单结构

菜单使用自定义弹层，而不是扁平列表：

```text
+ 
└── 上传文件 >
    ├── 本地文件
    └── 本地文件夹
```

细节：

- 一级菜单「上传文件」带纸夹图标与右箭头；
- 二级菜单在悬停或点击时展开；
- 点击外部区域或按 `Esc` 关闭；
- 已移除「从剪贴板添加」菜单项；
- `Ctrl/Cmd + V` 粘贴文件/图片仍然可用。

### 13.6 光标位置优先

资源必须插入到输入框当前光标位置，而不是固定追加到末尾。

交互顺序：

1. 点击 `+` 时立即捕获编辑器选区；
2. 打开系统文件/文件夹选择框；
3. 选择完成后恢复选区；
4. 在选区处插入资源标记；
5. 渲染为 Chip，并把光标移动到最后一个资源之后。

健壮性处理：

- 只有选区真正位于编辑器内部时才保存；
- 保存的选区失效时退化为插入到编辑器末尾；
- 粘贴与拖拽资源前也会保存当前选区；
- 资源较多时按标记顺序保持原位。

### 13.7 内联资源 Chip

资源标记：

```text
[[agentforge-resource:<type>:<encodeURIComponent(path)>]]
```

Chip 展示规则：

| 类型 | 图标 | 名称 |
| --- | --- | --- |
| `image` | 图片图标 | 文件名 |
| `folder` | 文件夹图标 | 文件夹名 + `/` |
| `pdf` / `document` / `html` | 文件图标 | 文件名 |
| `file` | 文件图标 | 文件名 |

- Chip 在输入框中不可编辑，删除时同时移除标记与资源列表；
- 点击 Chip 使用系统默认程序打开本地路径；
- 用户消息气泡复用同一套 Chip，保证输入框与消息列表视觉一致。

### 13.8 用户消息一致性

- 发送时保留 `rawText`（含资源标记）与 `text`（可见文本）；
- 用户消息气泡使用 `UserMessageContent` 按标记拆分；
- 文本与 Chip 原位交替渲染，换行与空格保持；
- 历史回放从 `req.rawMessage` 还原相同位置；
- 缺少 `rawMessage` 的旧消息退化为「文本 + 资源列表」。

### 13.9 工具栏与模型选择

- `+` 在左，模型选择在右；
- 模型选择使用自定义展示区 + 隐藏原生下拉；
- 发送按钮固定在输入框右下角；
- 运行中仍可输入补充指令，有资源时也可追加；
- 工作空间锁定后仍展示名称，但不可切换。

### 13.10 关键样式类

| 类名 | 作用 |
| --- | --- |
| `.composer-surface` | 输入区外层，纵向 flex 与状态切换 |
| `.composer-surface.is-history` | 已有会话状态，底栏在上 |
| `.composer-workspace-row` | 灰色工作空间底栏 |
| `.composer-add-menu` | `+` 触发器与弹层容器 |
| `.composer-add-panel` / `.composer-add-submenu` | 一级/二级菜单 |
| `.user-message-content` | 用户消息内联资源渲染 |

### 13.11 交互验收点

| 场景 | 预期 |
| --- | --- |
| 新建会话 | 工作空间在输入框下方 |
| 发送首条消息后 | 有工作空间时底栏移动到输入框上方 |
| 非工作空间会话 | 选择器与灰色底栏完全隐藏 |
| 光标在句子中间时添加图片 | Chip 插入到光标位置 |
| `+` 打开二级菜单 | 一级「上传文件」，二级「本地文件/本地文件夹」 |
| 点击 Chip | 打开对应本地文件/文件夹 |
| 消息列表 vs 输入框 | 资源位置一致 |
| 切换历史会话 | 工作空间底栏位置与非工作空间隐藏规则正确 |

