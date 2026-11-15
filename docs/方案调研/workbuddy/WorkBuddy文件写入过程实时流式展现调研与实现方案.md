# WorkBuddy 文件写入过程实时流式展现调研与实现方案

> 调研对象：腾讯 WorkBuddy 的本地 Agent / 文件处理体验
>
> 调研时间：2026-11-08
>
> 目标：解释“让 Agent 写一个文件时，页面为什么能实时看到文件正在被写入”，并给出 AgentForge Desktop 可落地的实现路线。

## 1. 先给结论

WorkBuddy 的“文件正在写入”视觉效果，不能简单理解为模型把 `edit` 工具的完整参数一次性返回后，后端直接把这一次调用原样展示出来。

更合理的技术拆解是：

```text
模型生成工具调用参数
        │
        ├─ 可能以 tool-call argument delta 逐段抵达
        │
        ▼
Agent 编排层接收并聚合工具参数
        │
        ├─ 生成文件草稿 / 变更预览
        ├─ 发出 file-write progress 事件
        └─ 等工具参数完整、校验通过后才真正提交文件
                │
                ▼
        临时文件 / staging buffer
                │
                ├─ 原子替换目标文件
                ├─ 生成最终 diff、hash、诊断结果
                └─ 通知页面刷新文件预览和变更列表
```

这里有两个经常被混淆的“流式”：

1. **模型输出流式**：模型正在生成工具名和工具参数，参数可能以 JSON 片段逐步返回。
2. **界面展示流式**：页面把已收到的文件草稿、diff 或工具进度按节奏呈现出来。

二者可以同时存在，也可以完全解耦。即使模型只返回了一次完整 `edit` 工具调用，页面仍然可以把最终变更拆成行或块，以动画方式展示。因此，用户看到的“像流式写文件”，不一定意味着磁盘文件真的被逐字符写入。

本方案的核心建议是：

> **模型参数增量用于生成草稿预览，目标文件采用暂存和原子提交；页面实时展示的是可回滚的预览状态，最终提交才改变真实文件。**

这样可以同时满足实时感、安全性、可取消、可回退和文件不被半成品破坏。

## 2. 调研边界：哪些是 WorkBuddy 已公开的事实

WorkBuddy 是商业产品，公开文档没有披露其内部 Agent 编排器、文件工具源码、SSE/IPC 事件名或内部数据结构。下面分为“官方可确认”和“工程推断”，避免把推断当成 WorkBuddy 的确定实现。

### 2.1 官方资料可以确认的产品行为

WorkBuddy 官方文档描述了以下能力：

| 产品行为 | 公开证据 | 对实现的启发 |
| --- | --- | --- |
| Agent 可以在授权目录内读写本地文件 | 腾讯 WorkBuddy 产品介绍 | 工具执行器必须有工作区授权和文件安全边界 |
| 任务执行过程会实时呈现在对话界面 | WorkBuddy 任务栏、任务对话文档 | 需要独立于最终文本的过程事件流 |
| 任务概览可以查看本轮执行步骤 | WorkBuddy 右侧边栏文档 | 需要 task step / tool lifecycle 事件 |
| 右侧结果区有产物、工作空间文件、变更等视图 | WorkBuddy 结果查看文档 | 文件产物、文件快照、diff 应作为独立领域对象 |
| 变更视图可查看本次任务修改的文件和具体变更 | WorkBuddy 结果查看文档 | 不能只返回“写入成功”，还应保存 before/after 或 diff 元数据 |
| 文件预览支持随产物变化自动刷新 | WorkBuddy 更新日志、内容管理文档 | 需要文件变更通知或前端订阅刷新机制 |
| 有“显示文件变更过程详情”的开关 | WorkBuddy 更新日志 | 产品层存在“过程详情”与“最终结果”两种展示粒度 |
| 允许任务执行中继续排队消息，但当前轮工具调用结束后再处理 | WorkBuddy 任务对话文档 | 一轮执行需要明确状态边界、取消和排队策略 |
| 本地 Markdown / HTML 支持 AI 编辑和人机协作 | WorkBuddy 内容管理文档 | 文件预览不应只做静态下载，而应是可订阅的编辑视图 |

这些资料说明 WorkBuddy 的用户体验至少包含三层：

```text
任务过程层：正在分析、调用工具、写入文件、校验结果
文件变更层：哪些文件变了、before/after、diff、版本
产物预览层：打开文件、实时刷新、最终验收和保存
```

但官方资料没有说明它是否使用了“模型工具参数流”“本地文件 watcher”“虚拟文件系统”或“服务端重放”。下面的具体协议属于面向 AgentForge 的实现建议。

### 2.2 同类 Agent 的公开实现给出的共性

主流代码 Agent 的公开资料有几个共性：

- 工具是模型可以调用的结构化能力，模型请求调用工具，应用侧执行工具，再把工具结果反馈给模型。
- `apply_patch`、`edit`、`write_to_file` 等工具通常是一次工具调用完成一个逻辑变更，而不是让模型每写一行就重新调用一次工具。
- 工具执行过程可以展示 diff、审批、错误和最终结果，但文件是否直接落盘取决于宿主实现。
- 对大参数，部分模型 API 支持流式传输 tool input；这不意味着宿主必须在参数还不完整时执行文件写入。

参考资料：

- [Cline Tools Reference](https://github.com/cline/cline/blob/main/docs/tools-reference/all-cline-tools.mdx)：模型决定调用工具，Cline 执行工具并把结果返回给模型；同时提供 `apply_patch` 和 `editor` 工具。
- [Roo Code `apply_diff`](https://roocodeinc.github.io/Roo-Code/advanced-usage/available-tools/apply-diff/)：工具会先校验路径、读取文件、匹配变更、准备 diff，再展示 diff 并等待批准，最后才应用到文件。
- [Roo Code Background Editing](https://roocodeinc.github.io/Roo-Code/features/experimental/background-editing/)：是否展示 diff 是 UI 行为配置，后台编辑仍会修改磁盘文件，并由 source control、watcher 和诊断系统感知变化。
- [OpenHands `str_replace_editor`](https://github.com/togethercomputer/OpenHands/blob/main/openhands/agenthub/codeact_agent/tools/str_replace_editor.py)：编辑工具强调精确匹配、唯一匹配、查看上下文和可撤销编辑。

## 3. `edit` 工具到底发生了什么

### 3.1 常规工具调用不是“模型直接改文件”

模型没有文件系统权限。典型调用链是：

```text
用户：请创建 report.md
  │
  ▼
LLM：返回 tool_call
    name = edit / write_file
    arguments = { path, content 或 patch }
  │
  ▼
Agent Runtime：解析参数、检查权限、执行本地代码
  │
  ▼
File Tool：读原文件、计算新内容、写临时文件、提交
  │
  ▼
Agent Runtime：把工具结果发回模型
  │
  ▼
LLM：继续思考，可能继续调用工具或输出最终答复
```

OpenAI 的函数调用文档把这个过程描述为：应用把工具定义给模型，模型返回工具调用，应用侧执行代码，再把工具输出发回模型；这不是模型直接执行函数。[OpenAI Function Calling](https://developers.openai.com/api/docs/guides/function-calling)

Anthropic 的工具调用文档也明确说明：工具是应用提供的能力，模型发出结构化请求，应用执行文件写入等副作用操作，再把 `tool_result` 返回给模型。[Claude Tool Use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/how-tool-use-works)

### 3.2 “一次性返回”具体指什么

以常见的 `edit` 为例，模型最终可能给出：

```json
{
  "path": "docs/report.md",
  "operation": "create",
  "content": "# 报告\n\n这是第一段内容。\n"
}
```

从工具执行器的角度看，这通常是一次完整的 `ToolExecutionRequest`。工具拿到完整参数后执行一次，并返回：

```json
{
  "ok": true,
  "path": "docs/report.md",
  "bytes": 42,
  "sha256": "..."
}
```

如果当前 Agent Core 只在 `beforeToolExecution` / `afterToolExecution` 触发回调，那么宿主只能知道：

```text
工具开始 -> 工具结束
```

并不知道工具参数在模型侧是如何逐 token 产生的。此时页面要做“实时写入效果”，只能：

1. 在工具执行前拿到完整 `content` 后，构造草稿预览并分块播放；或
2. 改造模型适配层，让 tool argument delta 能向上游传播；或
3. 改造工具协议，让写入操作本身具备 chunk / append / commit 生命周期。

### 3.3 工具参数本身也可能是流式的

OpenAI 文档说明，开启流式函数调用后，应用可以看到模型正在填写哪个函数以及参数内容；工具调用的 `delta` 需要由应用聚合成最终工具调用。文档示例中，工具调用的 `id`、名称和 `arguments` 会分片到达，应用需要把参数增量拼接起来。[OpenAI Function Calling · Streaming](https://developers.openai.com/api/docs/guides/function-calling)

Anthropic 文档对 `tool_use` 的 `input_json_delta` 有更明确的描述：增量是部分 JSON 字符串，客户端需要持续拼接，等 `content_block_stop` 后再解析完整输入。[Claude Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming)

Anthropic 还提供了 fine-grained tool streaming：工具输入可以在模型生成时直接流向客户端，减少大参数的首片段等待；但由于服务端不再缓冲和校验完整 JSON，客户端可能收到不完整、非法或被 `max_tokens` 截断的参数，所以必须先累积、容错解析，不能无条件执行。[Claude Fine-grained Tool Streaming](https://platform.claude.com/docs/en/agents-and-tools/tool-use/fine-grained-tool-streaming)

这回答了一个关键问题：

> **模型的 `edit` 参数确实可能逐步抵达，但“收到参数片段”与“允许文件落盘”必须是两个状态。**

### 3.4 `write_file` 工具契约应该如何设计

建议先把文件写入能力抽象为一个结构化工具。模型看到的是工具定义，模型最终生成的是完整参数；页面看到的则是该完整参数在生成期间被拆解后的草稿事件。

```json
{
  "name": "write_file",
  "description": "创建或覆盖工作区中的文件，文件内容必须经过工作区权限校验后提交",
  "parameters": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "工作区相对路径，例如 src/main/java/UserService.java"
      },
      "operation": {
        "type": "string",
        "enum": ["create", "overwrite"]
      },
      "content": {
        "type": "string",
        "description": "完整 UTF-8 文本内容"
      }
    },
    "required": ["path", "content"],
    "additionalProperties": false
  }
}
```

假设模型生成如下参数流，实际分片可以发生在任意字符位置：

```text
Chunk 1: {"path":"src/main/java/UserService.java",
Chunk 2: "content":"package com.example.service;\n\npublic class
Chunk 3:  UserService {\n
Chunk 4:     public void addUser(Long id, String name) {
Chunk 5:         users.put(id, name);\n    }\n}
Chunk 6: "}
```

上面的 chunk 是 JSON 参数片段，不是代码片段。`\\n`、`\\"`、Unicode 转义以及代码中的反斜杠都可能跨 chunk；前端最终需要的是解码后的 Java 文本。因此不能把原始 arguments delta 直接拼到 Monaco 编辑器，也不能在前端用字符串查找 `"content":"` 后按下一个双引号截取。

还有一个协议设计问题：如果模型先生成大段 `content`，最后才生成 `path`，页面暂时无法确定预览标签应该打开哪个文件。工程上有两个处理方法：

1. 保持标准 Function Calling，由后端累积参数，等 `path` 字段确定后再创建预览；此前只显示“正在准备文件”。
2. 将 `path`、`operation` 设计为先确定的元数据，内容通过独立的草稿流传输；这是后期的 `begin / append / commit` 协议，不是普通 JSON Function Call 的默认语义。

不要为了页面展示而要求模型每生成一行就重新调用一次 `write_file`。一次工具调用代表一个逻辑文件变更，delta 只负责预览，完整工具调用才负责执行。

### 3.5 LangChain4j 中的 Function Call Streaming 接入点

如果 AgentForge 的 Agent Core 使用或可以适配 LangChain4j 的低层流式模型接口，接入点可以放在 `StreamingChatResponseHandler`：

```java
model.chat(request, new StreamingChatResponseHandler() {

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall) {
        String callId = partialToolCall.id();
        int index = partialToolCall.index();
        String argumentsDelta = partialToolCall.partialArguments();

        toolArgumentsStreamAssembler.append(
            callId,
            index,
            partialToolCall.name(),
            argumentsDelta
        );
    }

    @Override
    public void onCompleteToolCall(CompleteToolCall completeToolCall) {
        // 这里只执行完整参数：校验、暂存、原子提交
        toolExecutor.execute(completeToolCall);
    }

    @Override
    public void onError(Throwable error) {
        fileWriteProgressPublisher.error(error);
    }
});
```

LangChain4j 官方接口对两个回调的语义是：`onPartialToolCall` 在模型生成工具参数片段时多次触发；`onCompleteToolCall` 在一个工具调用的参数完整组装后触发。多个并行工具调用应使用 `index` 和 `id` 关联，不能用一个全局字符串缓冲区混合拼接。[LangChain4j Response Streaming](https://docs.langchain4j.dev/tutorials/response-streaming/)、[LangChain4j Tools](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/tools.md)

LangChain4j 也明确说明，并不是所有 Provider 都会返回部分工具调用；部分 Provider 只触发完整工具调用回调。因此 AgentForge 需要同时支持：

```text
支持 partial tool call：onPartialToolCall -> file_write/delta -> onCompleteToolCall
不支持 partial tool call：onCompleteToolCall -> file_write/回放 -> commit
```

这也是为什么不能把“Function Call Streaming”作为文件安全提交的前提。它只决定页面能否更早看到草稿，不改变正式文件必须完整校验后提交的规则。

### 3.6 增量 JSON 参数解析器的职责

建议把参数流解析封装成独立组件，而不是放在 SSE Controller 或 Vue 页面中：

```java
public interface ToolArgumentsStreamAssembler {

    void append(
        String callId,
        int index,
        String toolName,
        String argumentsDelta
    );

    void complete(
        String callId,
        int index,
        String fullArguments
    );

    void fail(String callId, Throwable error);
}
```

对 `write_file` 可以维护如下上下文：

```java
final class WriteFileStreamContext {
    String callId;
    int index;
    String toolName;
    StringBuilder rawArguments;
    String path;
    String operation;
    StringBuilder decodedContent;
    boolean contentStarted;
    boolean contentCompleted;
    int seq;
}
```

推荐处理步骤：

1. 按 `executionId + callId + index` 隔离每一个工具调用。
2. 原样累积 `argumentsDelta`，保留完整 JSON 作为最终执行依据。
3. 使用增量 JSON 状态机识别对象层级、字段名、字符串开始/结束、反斜杠和 Unicode 转义。
4. `path` 一旦确认且通过 workspace 校验，就发送 `file_write/start`。
5. `content` 中只有已经完成解码的安全文本才能进入 `FileDraftBuffer`，然后发送 `file_write/delta`。
6. `onCompleteToolCall` 到达后，用完整 arguments 重新反序列化，不能只相信增量解析结果。
7. 完整参数校验通过后才进入 staging、冲突检测和原子提交。

这里的“增量解析”不等于“每收到一个字符就 `JSON.parse`”。更稳妥的实现是：

```text
raw arguments delta
      │
      ▼
参数扫描状态机
  ├─ path 已完成？
  ├─ content 字符串是否开始？
  ├─ 当前是否处于 escape？
  ├─ 是否正在收集 \uXXXX？
  └─ 是否遇到字符串结束？
      │
      ▼
已解码的 content 文本
      │
      ▼
草稿缓冲区 + file_write/delta
```

如果解析器遇到非法转义、字符串尚未结束、`max_tokens` 截断或工具调用被取消，应停止发送内容 delta，并发送明确的 `file_write/error` 或 `file_write/cancel`。页面可以保留已经展示的草稿，但正式文件不得被覆盖。

## 4. WorkBuddy 可能采用的实现模式

下面列出四种可行模式。由于 WorkBuddy 没有公布内部实现，不能断言它使用哪一种；对 AgentForge 的方案会在第 4.1 节明确推荐组合。

### 模式 A：完整工具调用后，前端模拟逐步展示

流程：

```text
模型一次性返回 edit
  -> 后端完成文件写入
  -> 读取 after 内容并生成 diff
  -> SSE 推送 file_preview_delta
  -> 前端按行/块展示
```

优点：

- 不需要改模型 SDK 或 Agent Core。
- 文件写入和页面展示解耦，稳定性最高。
- 适合第一版快速实现。

缺点：

- 页面展示的是“提交后的回放”，不是模型真正生成时的过程。
- 文件很大时，工具已经完成，页面仍需播放一段时间。
- 如果 UI 文案写成“模型正在生成”，会产生语义误导。

### 模式 B：模型 tool arguments 增量 + 暂存草稿预览

流程：

```text
model delta: {"path":"...
model delta: ","content":"# 标题...
  -> 参数聚合器追加 raw fragment
  -> 草稿解析器提取 path/content 增量
  -> file_preview_delta 推给页面
  -> content 完整且校验通过
  -> 原子提交目标文件
```

优点：

- 页面最接近真实的模型生成过程。
- 长文档的首屏内容能更早出现。
- 可展示“正在生成写入内容”和“正在提交文件”两个阶段。

缺点：

- 需要模型适配层暴露 tool-call delta。
- JSON 参数可能处于半截、转义不完整或非法状态。
- 如果 `content` 是一个巨大 JSON 字符串，增量解析复杂。
- 不能把未完成参数直接写到正式文件。

### 模式 C：分块写入协议

把一个 `edit` 拆成多个工具调用：

```text
begin_file_write(path, baseHash)
append_file_chunk(writeId, seq, content)
append_file_chunk(writeId, seq, content)
commit_file_write(writeId)
```

优点：

- 协议明确，工具执行器天然有进度。
- 适合超大文件、二进制生成、长时间生成任务。

缺点：

- 模型需要多次调用工具，token 和往返次数增加。
- 中途失败、重试、乱序、重复 chunk 都需要处理。
- 模型未必能稳定管理 `writeId`、`seq` 和 commit。
- 对普通代码文件过于复杂。

### 模式 D：文件系统 watcher 反向感知

流程：

```text
Agent 或 shell 直接写磁盘
  -> WatchService / chokidar 监听文件变化
  -> 重新读取文件并计算快照
  -> SSE 推送 file_changed
  -> 页面刷新预览
```

优点：

- 可以感知 `run_command`、脚本、外部编辑器产生的文件变化。
- 不要求所有写文件工具都实现专门回调。

缺点：

- watcher 通常只能知道“文件变了”，不知道变更来自哪个 toolCall。
- 多次写入可能合并成一次或产生多次碎片事件。
- 读到的可能是半写入文件。
- 不适合作为 Agent 文件写入主链路的唯一来源。

### 4.1 推荐组合

建议采用：

```text
MVP：模式 A + 模式 D
P1：模式 A + 模式 B
P2：对超大文件或特殊产物增加模式 C
```

具体来说：

- `edit` 工具走“完整参数 + 暂存 + 原子提交”。
- 文件写入工具主动发布生命周期事件。
- 普通代码文件的页面预览可在提交前使用草稿回放。
- `run_command` 或外部进程写文件时，由 watcher 做补充感知。
- 页面以文件变更事件为主，以 watcher 为兜底，不用 watcher 猜测模型意图。

## 5. 为什么不建议直接边收到边写正式文件

“模型每吐出一段就直接追加到目标文件”看起来最直观，但不建议作为默认方案。

### 5.1 会产生半成品文件

模型可能在 JSON 中途停止：

```text
{"path":"src/App.vue","content":"<template>...
```

此时目标文件可能只有半个 Vue 组件。构建工具、IDE、文件预览器和 watcher 可能立即读取并报语法错误。

### 5.2 JSON 转义会破坏增量内容

如果文件内容放在 JSON 字符串中，以下内容都可能跨 chunk：

- `\n`
- `\"`
- Unicode 多字节字符
- 代码中的反斜杠
- JSON 字符串结尾引号

如果仅按字符串切片写入，容易把转义字符处理错，最终文件与模型原始意图不一致。

### 5.3 取消和重试很难回滚

用户点击停止后，模型连接可能停止，但磁盘文件已经被写了一半。此时必须额外保存原文件、临时文件和写入偏移，否则容易留下不可用文件。

### 5.4 并发任务会互相覆盖

WorkBuddy 支持多任务并行处理。若两个任务都直接写同一个文件，后写入的内容可能覆盖先写入的内容，页面也无法判断哪个事件属于当前任务。

### 5.5 正确的文件提交模型

建议使用：

```text
读取原文件 -> 计算目标内容 -> 写同目录临时文件 -> fsync -> 原子 rename
```

对于编辑已有文件，还应带上 `baseHash`：

```text
模型读取文件时：baseHash = sha256(oldContent)
提交前重新计算：currentHash = sha256(currentFile)

currentHash != baseHash
  -> 文件已被外部修改，拒绝覆盖，进入冲突流程
currentHash == baseHash
  -> 允许提交
```

## 6. 面向 AgentForge Desktop 的现状分析

当前 AgentForge 已经具备“对话级 SSE + 工具生命周期”的基础，但还没有“工具参数增量”和“文件写入进度”能力。

### 6.1 当前后端已经存在的能力

当前实现中：

- `ChatController` 通过 `POST /api/chat/stream` 返回 `SseEmitter`。
- `ServiceStreamingMiddleware` 在 `onPartialThinking` 和 `onPartialResponse` 时推送增量事件。
- `beforeToolExecution` 推送一次工具执行中的 `tool_call`。
- `afterToolExecution` 推送一次工具执行完成的 `tool_call`。
- `ToolCallResult` 当前包含 `toolCallId`、`toolName`、`description`、`inputParams`、`outputResult`、`duration`。
- `StreamEventEnums` 当前只有 `session`、`think`、`resp`、`tool_call`、`resp_end`、`error`。
- `ChatWorkbench.vue` 会按 `toolCallId` 合并工具卡片，`ToolCard.vue` 展示入参和结果。
- `sse.js` 已经可以解析任意 JSON SSE 数据帧，因此传输层不需要重做。
- `SessionLifecycleMiddleware` 会在运行期间收集事件，结束时统一持久化会话事件。
- `AgentRuntimeService` 当前通过 `new ToolService()` 给 `ReActAgent` 提供工具能力。

因此当前链路是：

```text
beforeToolExecution
  -> tool_call(isFinish=false)
  -> 工具执行
  -> afterToolExecution
  -> tool_call(isFinish=true)
```

缺少的是：

```text
工具执行中间的 file_write_start / delta / progress / commit 事件
```

### 6.2 当前实现的关键限制

当前 `IStreamingIAgentMiddleware` 暴露的是工具开始和工具结束回调，没有看到工具参数 delta 或工具内部进度回调。因此，仅修改 `ServiceStreamingMiddleware`，无法得到模型生成 `edit.content` 的逐 token 事件。

要实现真正的“模型写入内容实时流”，至少要在以下两处之一增加能力：

1. **模型适配层**：把 provider 返回的 tool-call argument delta 向 `ReActAgent` 上游透传。
2. **文件工具执行层**：让 `edit/write_file` 工具在准备、暂存、提交等阶段调用进度回调。

如果暂时不改 Agent Core，仍然可以先做“工具完成后 diff 逐步回放”，这是风险最低的 MVP。

## 7. 推荐的总体架构

### 7.1 领域对象

建议新增三个对象概念，不要把大量文件内容直接塞进现有通用 `ToolCallResult`：

```text
ToolRun
  └── 一次模型工具调用，负责 toolCallId、toolName、状态、耗时

FileWriteSession
  └── 一次文件写入草稿，负责路径、原始 hash、草稿 hash、阶段、序号

FileChange
  └── 最终文件变更，负责 before/after、diff、版本和可回退信息
```

推荐字段：

```text
FileWriteSession
-----------------
executionId       当前 Agent 运行 ID
toolCallId        当前工具调用 ID
writeId           文件写入 ID
path              工作区相对路径
operation         create | overwrite | patch | delete
baseHash          工具开始时的原文件 hash，可空
draftHash         草稿内容 hash，可空
phase             preparing | streaming | validating | committing | completed | failed | cancelled
seq               当前增量序号
bytes             当前草稿字节数
totalBytes        已知总字节数，可空
startedAt         开始时间
finishedAt        完成时间
```

### 7.2 事件流

可以新增独立的 `file_write` SSE 事件，不建议继续把所有字段塞进 `tool_call` 的 `ToolCallResult`。

建议事件：

```text
file_write / start
file_write / delta
file_write / snapshot
file_write / progress
file_write / commit
file_write / error
file_write / cancel
```

统一结构示例：

```json
{
  "event": "file_write",
  "type": "delta",
  "title": "正在生成文件内容",
  "content": {
    "executionId": "exec_01",
    "toolCallId": "call_01",
    "writeId": "write_01",
    "path": "docs/report.md",
    "operation": "create",
    "phase": "streaming",
    "seq": 7,
    "delta": "## 实现方案\\n\\n",
    "previewText": null,
    "bytes": 28,
    "totalBytes": null
  },
  "isFinish": false
}
```

结束事件示例：

```json
{
  "event": "file_write",
  "type": "commit",
  "title": "文件写入完成",
  "content": {
    "executionId": "exec_01",
    "toolCallId": "call_01",
    "writeId": "write_01",
    "path": "docs/report.md",
    "operation": "create",
    "phase": "completed",
    "seq": 12,
    "bytes": 4280,
    "sha256": "new-file-sha256",
    "baseHash": null,
    "changedLines": 120,
    "diffStat": { "additions": 120, "deletions": 0 }
  },
  "isFinish": true
}
```

### 7.3 Function Call Streaming 与文件事件的映射

Function Call Streaming 是模型到 Agent Runtime 的输入流，`file_write` 是 Agent Runtime 到页面的展示流。两者不要直接复用同一份 JSON：

```text
LangChain4j / Provider
  onPartialToolCall(callId, index, partialArguments)
          │
          ▼
ToolArgumentsStreamAssembler
  累积 raw arguments、解码 content、识别 path
          │
          ▼
FileWriteProgressPublisher
  发布 file_write start / delta / progress
          │
          ▼
SSE
  ChatWorkbench -> FileWritePreview -> Monaco Model
```

如果使用原生 SSE 的 `event` 字段，可以表达为：

```text
event: file.started
data: {"writeId":"w1","callId":"c1","path":"src/UserService.java"}

event: file.delta
data: {"writeId":"w1","seq":1,"content":"package com.example;\\n"}

event: file.committed
data: {"writeId":"w1","path":"src/UserService.java","sha256":"..."}
```

但 AgentForge 当前 `sse.js` 主要解析 `data` 中的 JSON，并由 `event` 字段进行业务分发。为了兼容现有协议，建议采用统一包络：

```json
{
  "event": "file_write",
  "type": "start | delta | progress | commit | error | cancel",
  "content": {
    "executionId": "exec_01",
    "toolCallId": "call_01",
    "writeId": "write_01",
    "seq": 1
  },
  "isFinish": false
}
```

两种命名方式语义等价，但项目内只能选一种作为正式协议。本文后续统一使用 AgentForge 的 `event=file_write + type` 方案，避免前端同时维护 `file.delta` 和 `file_write/delta` 两套事件。

### 7.4 事件语义

| 事件 | 发送时机 | 是否落盘 | 页面动作 |
| --- | --- | --- | --- |
| `start` | 已确认工具是文件写入，并完成路径安全检查 | 是 | 创建文件写入卡片，显示路径和“准备中” |
| `delta` | 草稿缓冲区收到新的可展示内容 | 默认不完整落盘 | 增量更新代码/文本预览 |
| `snapshot` | 生成一个可恢复的完整草稿快照 | 可按采样落盘 | 断线重连时恢复预览 |
| `progress` | 工具知道阶段或字节进度 | 可落盘 | 更新进度条、阶段文案 |
| `commit` | 原子替换成功 | 是 | 标记完成、刷新文件树和 diff |
| `error` | 解析、权限、冲突、写入或校验失败 | 是 | 标记失败并保留错误原因 |
| `cancel` | 用户主动停止 | 是 | 删除 staging，显示已取消 |

## 8. 三种实现层级

### 8.1 MVP：完整 `edit` 后做“真实 diff + 页面回放”

这是当前 AgentForge 最适合先落地的方案。

#### 后端流程

```text
beforeToolExecution(edit)
  -> 识别 path / operation
  -> 读取 oldContent 和 baseHash
  -> file_write/start

edit 工具执行
  -> 生成 newContent
  -> 写临时文件并原子提交
  -> 读取实际提交后的文件
  -> 计算 diff / sha256

afterToolExecution(edit)
  -> file_write/commit
  -> tool_call 完成
```

如果要让过程更有“流式感”，后端在拿到最终 `newContent` 后，可以按行或按固定字节切分：

```text
file_write/delta seq=1  "# 标题\\n"
file_write/delta seq=2  "\\n"
file_write/delta seq=3  "## 背景\\n"
...
file_write/commit
```

这里页面文案应使用：

```text
正在应用文件变更
正在生成预览
文件写入完成
```

不要把它标成“模型正在逐字输出”，因为这时模型和文件工具可能已经完成。

#### MVP 需要的代码改造

后端：

1. 增加 `FileWriteProgressPublisher`，由 `ServiceStreamingMiddleware` 持有。
2. 在工具开始时识别文件工具和参数，推送 `file_write/start`。
3. 工具完成后计算 before/after diff。
4. 以限速方式推送 `file_write/delta`。
5. 最后推送 `file_write/commit`。
6. `SessionLifecycleMiddleware` 只保存 start、commit、error 等关键事件，避免把每个字符全部写入会话数据库。

前端：

1. `ChatWorkbench.handleEvent` 增加 `file_write` 分支。
2. 按 `writeId` 创建和更新文件写入状态。
3. `FileWritePreview.vue` 显示路径、阶段、预览文本和 diff 统计。
4. 复用现有 Markdown/代码渲染思路，但预览内容应优先按纯文本或代码块展示。
5. `sessionReplay.js` 只根据最终 `commit` 和变更摘要恢复，不重放所有 delta。

### 8.2 P1：工具参数 delta 驱动草稿流

在模型供应商或 Agent Core 能提供 tool-call delta 的前提下，增加：

```text
ProviderStreamAdapter
  -> ToolArgumentDelta
  -> ToolArgumentAccumulator
  -> FileDraftBuffer
  -> FileWriteProgressPublisher
```

#### 工具参数累积器

工具参数不能直接 `JSON.parse` 每个片段，需要以原始字符串累积：

```java
class ToolArgumentAccumulator {
    private final StringBuilder rawJson = new StringBuilder();

    void append(String partialJson) {
        rawJson.append(partialJson);
    }

    Optional<EditArguments> tryParse() {
        try {
            return Optional.of(objectMapper.readValue(rawJson.toString(), EditArguments.class));
        } catch (JsonProcessingException incomplete) {
            return Optional.empty();
        }
    }
}
```

但对于 `content` 很大的 JSON 字符串，推荐不要在每个 delta 上强行解析整个 JSON。可以采用以下顺序：

1. 先从增量中提取已经确定的 `path` 和 `operation`。
2. 对 `content` 的原始 JSON 字符串做一个支持转义的增量扫描器。
3. 只有扫描器确认拿到完整字符串片段后，才追加到草稿缓冲区。
4. 如果无法安全解析，则只展示“工具参数正在生成”，不要把不可信片段展示为正式代码。

#### 更适合流式的工具输入形态

普通 JSON 函数参数：

```json
{
  "path": "README.md",
  "content": "大量带转义符的文本"
}
```

更适合增量传输的内部协议：

```text
tool = write_file_stream
input = {
  "path": "README.md",
  "operation": "create",
  "contentEncoding": "utf8-text",
  "contentStream": "原始文本或 patch 流"
}
```

或者干脆让工具输入采用受控的 free-form patch，并在宿主侧把文件路径作为单独的结构化字段。具体能否使用，取决于模型供应商和 Agent Core 的工具协议支持。

### 8.3 P2：分块写入和长任务进度

对大文件、图片、压缩包、长时间生成任务，可以引入 `begin / append / commit`。

```text
begin_file_write
  -> 返回 writeId

append_file_chunk(writeId, seq, bytes)
  -> 写入 staging 文件
  -> 推送 progress

commit_file_write(writeId)
  -> 校验 hash
  -> 原子替换目标文件
```

该模式适合真正的长任务，但不建议把普通源代码编辑都改成多轮模型工具调用。

如果未来接入 MCP 工具，可以参考 MCP 的 progress 机制：调用方在请求元数据中携带唯一 `progressToken`，工具通过 `notifications/progress` 发送当前进度、总量和人类可读消息；协议也要求进度只关联活跃请求，并建议限频。[MCP Progress Specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress)

MCP progress 适合表达：

```text
正在读取文件 30/100
正在生成草稿 65/100
正在提交文件 90/100
```

但它本身不等于文件内容 delta。文件内容预览仍需 `file_write/delta` 或快照事件。

## 9. AgentForge 后端落地设计

### 9.1 扩展事件枚举

当前 `StreamEventEnums` 只有 `TOOL_CALL` 等事件。建议新增：

```java
FILE_WRITE("file_write", "文件写入事件",
    TypeEnum.FILE_START,
    TypeEnum.FILE_DELTA,
    TypeEnum.FILE_SNAPSHOT,
    TypeEnum.FILE_PROGRESS,
    TypeEnum.FILE_COMMIT,
    TypeEnum.FILE_ERROR,
    TypeEnum.FILE_CANCEL)
```

如果不希望扩大 `TypeEnum`，也可以保留 `event=file_write`，把阶段放入 `content.phase`。推荐后者，便于后续增加工具阶段而不频繁修改枚举。

### 9.2 新增 FileWriteProgressPublisher

建议接口：

```java
public interface FileWriteProgressPublisher {

    void start(FileWriteStart start, AgentChatContext context);

    void delta(FileWriteDelta delta, AgentChatContext context);

    void progress(FileWriteProgress progress, AgentChatContext context);

    void commit(FileWriteCommit commit, AgentChatContext context);

    void error(FileWriteError error, AgentChatContext context);
}
```

实现类负责三件事：

1. 向当前 `SseEmitter` 发送事件。
2. 向运行期 `RunState` 收集关键事件。
3. 对 delta 做限频、合并和丢弃策略。

不要让文件工具直接依赖 `SseEmitter`，否则工具层会和 HTTP 层耦合，未来接入 CLI、WebSocket、任务重连会比较困难。

### 9.3 文件写入服务

建议新增 `DesktopFileWriteService`，而不是在 middleware 中直接操作文件：

```java
public interface DesktopFileWriteService {

    FileWriteSession begin(FileWriteRequest request);

    void appendDraft(String writeId, String delta);

    FileWritePreview preview(String writeId);

    FileWriteCommit commit(String writeId);

    void cancel(String writeId);
}
```

服务内部包含：

- `WorkspacePathResolver`：解析相对路径并阻止越界。
- `FileSnapshotService`：读取原文件、计算 hash、生成 diff。
- `StagedFileWriter`：写临时文件、flush、fsync、原子替换。
- `FileConflictDetector`：检查 `baseHash` 是否仍然匹配。
- `FileHistoryStore`：保存回退需要的旧版本或 patch。
- `FileWatchBridge`：补充感知外部命令产生的文件变化。

### 9.4 工具接入方式

当前 `AgentRuntimeService` 使用：

```java
.toolService(new ToolService())
```

建议改成一个桌面端工具服务或工具装饰器：

```java
ToolService toolService = new DesktopToolService(
        baseToolService,
        desktopFileWriteService,
        fileWriteProgressPublisher,
        workspacePolicy);

return ReActAgent.builder()
        ...
        .toolService(toolService)
        .build();
```

具体改法取决于 Agent Core 的 `ToolService` 是否支持注册自定义工具和执行拦截：

| Agent Core 能力 | 方案 |
| --- | --- |
| 支持自定义工具注册 | 注册桌面版 `edit/write_file`，将写入过程全部纳入 `DesktopFileWriteService` |
| 支持执行前后 middleware，但无 delta | 先做 MVP；在 before/after 间做 diff 回放 |
| 支持工具进度回调 | 工具通过 callback 发布 `delta/progress` |
| 支持 provider tool-call delta | 增加 P1 的参数增量适配器 |
| 工具服务不可扩展 | 在 Agent Runtime 外包一层 file tool，或修改 Agent Core 接口 |

### 9.5 真实文件提交策略

创建或覆盖文件：

```text
1. 校验 path 在 workspace 内
2. 读取原文件和 baseHash
3. 创建 staging/<executionId>/<writeId>.tmp
4. 将草稿内容写入 staging
5. flush + fsync
6. 重新计算 draftHash
7. 检查 currentHash == baseHash
8. 原子 rename 到目标文件
9. 生成 FileChange 和 diffStat
10. 推送 file_write/commit
```

编辑 patch：

```text
1. 先读目标文件
2. 依据 patch 或 old/new block 计算 newContent
3. 匹配失败时不修改目标文件，返回明确错误
4. 对 newContent 做同样的 staging + atomic move
```

删除文件：

```text
1. 先做权限和确认检查
2. 将原文件移动到可恢复的 trash/version 目录
3. 记录 beforeHash
4. 最终确认后再清理历史版本
```

## 10. AgentForge 前端落地设计

### 10.1 现有前端可以直接复用的部分

当前 `sse.js` 已经按 SSE 帧读取 JSON，只要服务端推送的是合法 `data: {...}`，无需修改底层传输。

当前 `ChatWorkbench.vue` 已经具备：

- 按事件类型分发。
- 按 `toolCallId` 合并工具卡片。
- 维护 `answer.parts`。
- 运行中滚动和结束状态处理。

当前 `ToolCard.vue` 已经具备：

- 工具执行中/完成状态。
- 入参和结果展开查看。
- 工具耗时展示。

因此建议新增文件写入卡片，而不是推翻现有工具卡片。

### 10.2 前端状态模型

```js
{
  type: 'file_write',
  writeId: 'write_01',
  toolCallId: 'call_01',
  path: 'docs/report.md',
  operation: 'create',
  phase: 'streaming',
  seq: 7,
  previewText: '# 报告\\n\\n',
  diff: null,
  bytes: 12,
  totalBytes: null,
  error: null,
  startedAt: 1730000000000,
  finishedAt: null
}
```

更新规则：

```js
onStart(event) {
  fileWrites[event.content.writeId] = createFileWrite(event)
}

onDelta(event) {
  const write = fileWrites[event.content.writeId]
  if (!write) return
  if (event.content.seq <= write.seq) return
  write.seq = event.content.seq
  write.previewText += event.content.delta || ''
}

onCommit(event) {
  const write = fileWrites[event.content.writeId]
  if (!write) return
  Object.assign(write, event.content)
  write.phase = 'completed'
}
```

必须按 `seq` 去重，因为断线重连、代理重试或多路推送可能造成重复事件。

### 10.3 展示组件

建议 `FileWritePreview.vue` 至少包含：

```text
┌─────────────────────────────────────────────┐
│ 正在写入  docs/report.md          进行中     │
├─────────────────────────────────────────────┤
│ # 报告                                       │
│                                             │
│ ## 实现方案                                  │
│                                             │
│ 正在生成内容…                                │
├─────────────────────────────────────────────┤
│ 生成中 · 128 B · seq 7                      │
└─────────────────────────────────────────────┘
```

阶段文案建议：

| phase | 页面文案 |
| --- | --- |
| `preparing` | 正在准备文件 |
| `streaming` | 正在生成文件内容 |
| `validating` | 正在校验文件 |
| `committing` | 正在写入文件 |
| `completed` | 文件写入完成 |
| `failed` | 文件写入失败 |
| `cancelled` | 文件写入已取消 |

### 10.4 是否做打字机动画

当前项目的 `MarkdownView.vue` 已有基于 `requestAnimationFrame` 的 typewriter、blur tail 和 caret 动画。这套动画可以复用到文件预览，但要区分两类内容：

- **真实 delta**：页面收到新内容后直接追加，动画延迟只用于视觉缓冲。
- **完整内容回放**：后端已经给了最终内容，页面按行或按块播放，必须标识为“预览回放”而非模型原始流。

文件预览不建议对每个字符都重新执行 Markdown 渲染。推荐：

1. 代码文件按纯文本 + 语法高亮展示。
2. Markdown 文件按段落或行批量追加。
3. 50～100 ms 合并一次 delta。
4. 大于阈值时直接跳到最新快照，避免页面积压。

### 10.5 Monaco 编辑器的实时更新策略

如果页面使用 Monaco，建议为每个 `writeId` 建立独立的虚拟模型，而不是把所有文件内容写入当前激活文件：

```text
writeId = write_01
path    = src/main/java/UserService.java
uri     = agentforge://draft/exec_01/write_01/UserService.java
model   = Monaco TextModel
```

事件处理可以按以下方式组织：

```js
const draftStates = new Map()

function onFileStart(event) {
  const file = event.content
  const model = getOrCreateMonacoModel(file.path, file.writeId)

  draftStates.set(file.writeId, {
    path: file.path,
    seq: 0,
    buffer: '',
    model,
    phase: 'preparing'
  })
}

function onFileDelta(event) {
  const file = event.content
  const state = draftStates.get(file.writeId)
  if (!state || file.seq <= state.seq) return

  state.seq = file.seq
  state.buffer += file.delta || ''
  state.phase = 'streaming'

  // 小文件可以直接刷新；生产实现应在 requestAnimationFrame 中合并更新。
  scheduleModelRefresh(state)
}

function onFileCommit(event) {
  const state = draftStates.get(event.content.writeId)
  if (!state) return

  state.phase = 'completed'
  markModelSaved(state.model, event.content.sha256)
  refreshWorkspaceFileTree(event.content.path)
}
```

`scheduleModelRefresh` 不应对每一个 token 都触发一次完整 Markdown 渲染。建议采用两级缓冲：

```text
SSE file.delta
  -> writeState.buffer
  -> 16～50 ms requestAnimationFrame / debounce
  -> Monaco model 更新
```

更新方式可以按文件大小选择：

| 文件规模 | Monaco 更新策略 | 原因 |
| --- | --- | --- |
| 小于 32 KB | 用最新 `buffer` 刷新模型 | 实现简单，刷新开销可接受 |
| 32 KB～512 KB | 合并多个 delta 后追加编辑 | 避免频繁重建整个文本模型 |
| 大于 512 KB | 使用 snapshot、分页或独立文件预览 | 避免聊天 SSE 和主线程被大文本拖慢 |

当页面切换到真实工作区文件时，应将 `agentforge://draft/...` 模型与实际文件模型区分开：

```text
草稿模型：只读或受控编辑，显示“未提交”
真实模型：文件 commit 后刷新，显示“已保存”
```

这样可以避免草稿内容覆盖用户正在编辑的真实文件，也能清晰表达“页面已经看到内容”和“磁盘已经提交”并不是同一个状态。

## 11. SSE 事件和持久化设计

### 11.1 事件不能全部长期保存

如果一个 200 KB 文件按字符发送 20,000 个 delta，并且所有 delta 都被 `SessionLifecycleMiddleware` 收集和持久化，会带来：

- 内存占用增加。
- SQLite `events` 字段膨胀。
- 历史会话加载慢。
- 重放时重复构造大量字符串。

建议按两类处理：

```text
实时事件：start / delta / progress
  -> 只发 SSE，必要时保留最近一个快照

事实事件：commit / error / cancel
  -> 写入会话事件和文件变更记录
```

### 11.2 断线恢复

建议每个文件写入事件携带：

```text
executionId
writeId
seq
```

断线重连时客户端发送：

```text
lastSeenSeq = 7
```

服务端可以选择：

1. 从内存 ring buffer 补发 seq 8 之后的 delta。
2. 补发最新完整 snapshot。
3. 如果任务已结束，只返回 commit 和最终文件内容/变更摘要。

不要要求客户端依赖“从历史消息逐字符重放”恢复文件预览。

### 11.3 与当前会话事件的兼容

当前历史回放主要处理 `think`、`resp`、`tool_call`、`error`。新增文件事件后建议：

```text
历史消息仍保留工具卡片
文件变更单独从 file_change 记录恢复
```

这样不会把完整文件内容重复存入 AI 消息正文，也不会让对话气泡变成一个巨大的 JSON。

## 12. 文件 watcher 的正确位置

watcher 应作为补充链路，不应作为模型文件写入的唯一实现。

### 12.1 Agent 主动写入

```text
FileWriteService
  -> 先发 start/delta/commit
  -> watcher 只用于确认最终文件确实出现
```

需要忽略：

- 自己创建的 staging 临时文件。
- 同一个 `writeId` 产生的中间 rename 事件。
- `.git`、`.agentforge`、临时目录等内部文件。

### 12.2 shell / 脚本 / 外部应用写入

```text
watcher 发现变化
  -> debounce 100～300 ms
  -> 读取稳定快照
  -> 计算 hash 和 diff
  -> 生成 external_change 事件
  -> 刷新预览
```

对于外部写入，页面文案应是：

```text
检测到文件发生变化
```

不要强行显示为 Agent 正在写入，因为变化可能来自用户编辑器或其他进程。

## 13. 权限、安全和一致性要求

WorkBuddy 官方文档提到只访问用户主动授权的文件夹，敏感目录和高危操作会拦截或二次确认。AgentForge 实现时至少需要覆盖：

### 13.1 路径安全

- 所有模型路径先转成 workspace 相对路径。
- `normalize` 后检查是否仍位于 workspace 根目录。
- 拒绝 `..` 越界、绝对路径、非法盘符和未授权目录。
- 对符号链接进行 realpath 检查，防止通过软链接逃逸。
- 不允许工具自行修改 workspace 根目录之外的文件。

### 13.2 写入安全

- 先生成 staging 文件，再原子提交。
- 提交前检查 `baseHash`，发现外部修改就进入冲突。
- 对高风险目录、删除、批量覆盖要求二次确认。
- 保存 before snapshot 或可回滚 patch。
- 限制单文件大小、单次任务总写入量和事件速率。

### 13.3 并发安全

- `writeId`、`executionId` 和 `seq` 全局唯一/有序。
- 同一文件同时只允许一个 commit。
- 其他任务读取到旧版本时，必须重新读取后再编辑。
- 事件必须带任务标识，前端切换任务不能串流。

### 13.4 内容安全

- 不把未完成的 JSON 片段直接当 HTML 渲染。
- Markdown/HTML 预览必须做 sanitizer。
- 代码预览默认使用纯文本或安全的代码高亮。
- API Key、Token、密码等敏感字段不进入 file delta 日志。

## 14. 推荐实施路线

### Phase 0：先确认 Agent Core 能力

需要先回答：

1. `ToolService` 能否注册自定义工具？
2. `ToolExecutionRequest` 是否支持工具内部 progress callback？
3. LLM 流式接口是否能拿到 tool-call argument delta？
4. `edit` 工具实际由哪个模块实现？
5. 是否支持工具取消和执行上下文传递？

如果这些接口不在当前仓库，需要检查 AgentForge 核心源码或本地 Maven 构件对应源码。

### Phase 1：MVP，先实现“文件过程卡片”

目标：用户能看到文件名、写入阶段、最终 diff 和完成状态。

实现项：

1. 新增 `FileWriteProgressPublisher`。
2. 用 before/after diff 识别文件写入。
3. 增加 `file_write/start`、`file_write/commit`、`file_write/error`。
4. 前端增加 `FileWritePreview.vue`。
5. `ToolCard` 内嵌文件路径和 diff 统计。
6. 先不做真实 provider argument delta。

验收：

```text
发起“创建一个 markdown 文件”
  -> 页面马上出现“准备写入 xxx.md”
  -> 工具完成后显示新增行数和最终预览
  -> 文件可在工作空间文件中打开
  -> 历史消息能恢复最终变更
```

### Phase 2：草稿增量预览

目标：在正式提交前看到草稿内容。

实现项：

1. Agent Core 暴露工具参数 delta 或工具内部 draft callback。
2. 增加 `file_write/delta` 和 `file_write/snapshot`。
3. 使用内存 draft buffer，不修改正式文件。
4. 支持取消、超时、断线和序号去重。
5. 页面增加“模型生成中”和“正在提交”区分。

验收：

```text
模型还在生成长文档时
  -> 页面预览已经出现前几段
  -> 正式文件仍保持旧版本或不存在
  -> 中途取消后目标文件不出现半成品
  -> 完成后一次性提交并刷新文件预览
```

### Phase 3：文件变更中心和 watcher

目标：接近 WorkBuddy 的工作空间文件、变更和预览体验。

实现项：

1. 增加 `file_change` 持久化表或本地变更索引。
2. 支持 before/after diff、文件列表和版本回退。
3. 接入 workspace watcher，感知 shell/外部编辑器变化。
4. 支持任务切换、历史任务补齐、断线重连。
5. 对大文件使用快照和差分压缩。

### Phase 4：多文件和大产物

目标：支持多文件同时生成、图片/压缩包/Office 文件等产物。

实现项：

1. 一个 toolCall 下多个 `writeId`。
2. 每个文件独立进度，任务层汇总总进度。
3. 二进制文件只展示大小、缩略图或生成预览，不传输原始二进制 delta 到聊天 SSE。
4. 大文件转为独立文件流或本地 IPC，避免聊天 SSE 被大内容阻塞。

## 15. 测试方案

### 15.1 单元测试

- 路径越界、符号链接和 workspace 规则。
- `baseHash` 冲突检测。
- UTF-8 多字节字符跨 chunk。
- JSON 转义跨 chunk。
- delta 重复、乱序和丢失。
- patch 唯一匹配和匹配失败。
- staging 写入失败和原子 rename 失败。
- cancel 后目标文件不变。

### 15.2 集成测试

```text
1. 创建新文件
2. 覆盖已有文件
3. patch 修改文件中间一段
4. 生成多个文件
5. 工具执行中取消
6. SSE 连接中断后重连
7. 外部编辑器同时修改
8. shell 命令产生文件变化
9. 任务失败后查看变更
10. 历史会话重开后查看最终 diff
```

### 15.3 前端验收

- 文件卡片不会随着每个 delta 产生重复。
- 长文件预览不会阻塞对话滚动。
- 切换会话后不会串显其他任务的文件内容。
- 文件完成后自动刷新，但不跳动用户当前滚动位置。
- 用户可以折叠过程详情，只看最终结果。
- 关闭“显示文件变更过程详情”后仍保留最终变更和产物。

## 16. 对“WorkBuddy 是怎么做到的”的最终判断

基于公开资料，能确认 WorkBuddy 有任务过程、文件变更、工作空间文件、文件预览刷新和文件变更过程详情能力；不能确认它内部是否使用某一个名为 `edit` 的工具、是否将 tool arguments 原样流式到前端、是否使用虚拟文件系统或具体事件协议。

从工程合理性判断，最可能是以下组合，而不是“直接把模型输出写进正式文件”：

```text
Agent 工具调用生命周期
  + 文件快照 / diff
  + 任务过程事件
  + 文件预览刷新
  + 暂存/版本/回退能力
  + 页面侧增量渲染或预览回放
```

对 AgentForge，建议遵循以下优先级：

1. 先有可追踪、可回滚的文件变更事实。
2. 再增加 `file_write` 过程事件。
3. 再接入模型 tool argument delta，做真正的草稿流。
4. 最后再做多文件、大产物和跨任务恢复。

不要把“视觉上像流式”与“磁盘上逐 token 写入”绑定在一起。对桌面 Agent 来说，用户真正需要的是：知道 Agent 当前在做什么、能看到变更内容、能取消或回退、最终文件不会被半成品污染。

## 17. 参考资料

### WorkBuddy 官方资料

- [WorkBuddy 任务对话：查看执行过程](https://www.workbuddy.cn/docs/workbuddy/Conversation)
- [WorkBuddy 新建任务栏：执行过程实时呈现](https://www.workbuddy.cn/docs/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Task-Bar)
- [WorkBuddy 右侧边栏：任务进程、工作空间文件、变更](https://www.workbuddy.cn/docs/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Right-Sidebar)
- [WorkBuddy 结果查看：产物、工作空间文件、变更](https://www.codebuddy.cn/docs/workbuddy/Results)
- [WorkBuddy 内容管理：文件读取、AI 编辑、预览刷新](https://www.workbuddy.cn/docs/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Library/Content-Management)
- [WorkBuddy 更新日志：文件变更过程详情、文件预览自动刷新](https://www.codebuddy.cn/docs/workbuddy/Changelog)
- [腾讯 WorkBuddy 产品介绍：本地文件读写](https://cloud.tencent.com/product/workbuddy)

### 模型工具流和协议

- [OpenAI Function Calling](https://developers.openai.com/api/docs/guides/function-calling)
- [OpenAI Streaming API Responses](https://developers.openai.com/api/docs/guides/streaming-responses)
- [Claude Tool Use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/how-tool-use-works)
- [Claude Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [Claude Fine-grained Tool Streaming](https://platform.claude.com/docs/en/agents-and-tools/tool-use/fine-grained-tool-streaming)
- [LangChain4j Response Streaming](https://docs.langchain4j.dev/tutorials/response-streaming/)
- [LangChain4j Tools and Partial Tool Calls](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/tools.md)
- [LangChain4j `StreamingChatResponseHandler` API](https://docs.langchain4j.dev/apidocs/dev/langchain4j/model/chat/response/StreamingChatResponseHandler.html)
- [MCP Progress Specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress)

### 同类开源 Agent 的文件编辑实现

- [Cline Tools Reference](https://github.com/cline/cline/blob/main/docs/tools-reference/all-cline-tools.mdx)
- [Roo Code `apply_diff`](https://roocodeinc.github.io/Roo-Code/advanced-usage/available-tools/apply-diff/)
- [Roo Code Background Editing](https://roocodeinc.github.io/Roo-Code/features/experimental/background-editing/)
- [OpenHands `str_replace_editor`](https://github.com/togethercomputer/OpenHands/blob/main/openhands/agenthub/codeact_agent/tools/str_replace_editor.py)

### AgentForge 当前实现索引

- `agentforge-desktop-server/src/main/java/cloud/changlu/agentforge/desktop/modules/chat/controller/ChatController.java`
- `agentforge-desktop-server/src/main/java/cloud/changlu/agentforge/desktop/modules/chat/middleware/ServiceStreamingMiddleware.java`
- `agentforge-desktop-server/src/main/java/cloud/changlu/agentforge/desktop/modules/chat/middleware/SessionLifecycleMiddleware.java`
- `agentforge-desktop-server/src/main/java/cloud/changlu/agentforge/desktop/protocol/StreamEventResponse.java`
- `agentforge-desktop-server/src/main/java/cloud/changlu/agentforge/desktop/protocol/ToolCallResult.java`
- `agentforge-desktop-ui/src/pages/workbench/ChatWorkbench.vue`
- `agentforge-desktop-ui/src/modes/debug/ToolCard.vue`
- `agentforge-desktop-ui/src/utils/sse.js`
- `agentforge-desktop-ui/src/utils/sessionReplay.js`
