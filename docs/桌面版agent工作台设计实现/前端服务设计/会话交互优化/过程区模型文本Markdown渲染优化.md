# 过程区模型文本 Markdown 渲染优化

> 范围：桌面工作台会话详情区「过程区」中每一轮模型单独发言的正文
> 模式：发布模式（优雅）`elegant` + 调试模式 `debug` 两种均覆盖
> 状态：已落地，构建通过

---

## 1. 问题

### 1.1 现象

对话 loop 过程中，模型在一个轮次里可能多次单独发言（例如工具调用前后的说明文字）。这些文字在「过程区」展开后，**markdown 标记原样露出**：

```
已确认：后端只下发 `resp`、`think` 这类事件，流结束时**不发送 `resp_end`**。
这意味着 `finishParts` 可能从未执行。我来验证 `elapsedMs` 是否真的缺失：
```

用户看到的是反引号和星号本身，而不是代码样式与加粗效果。相比之下，**最终回答区**渲染正常——同一段内容在两个区域的视觉表现割裂。

### 1.2 影响面

| 区域 | 修复前 | 说明 |
| --- | --- | --- |
| 最终回答区 | 正常 | 走 `MarkdownView` |
| 过程区·每轮模型文本 | 纯文本 | 本次修复对象 |
| 过程区·思考内容 | 纯文本 | 见 §6 边界，本次不改 |
| 过程区·工具输入/输出 | 纯文本 | 见 §6 边界，本次不改 |

---

## 2. 根因

`ResponseSegmentBlock.vue` 是过程区里承载「每轮模型发言」的组件，修复前实现为：

```vue
<template>
  <div class="response-segment" :class="mode">{{ displayContent }}</div>
</template>
```

它做了两件事，恰好都阻止了 markdown 渲染：

| 写法 | 后果 |
| --- | --- |
| `{{ displayContent }}` 文本插值 | 内容被当作纯文本节点插入 DOM，浏览器不会解析任何标记 |
| `white-space: pre-wrap` | 保留所有空格与换行，让原始标记完整可见 |

也就是说：**这条路径上从来没有接入过 markdown 解析器**，不是渲染失败，而是根本没渲染。

---

## 3. 根因定位：两个模式的调用路径

`ResponseSegmentBlock` 被两种模式共用，这是"一处改动、两边生效"的基础。

### 3.1 优雅模式（发布模式）

`src/modes/elegant/ElegantMessage.vue`：

```vue
<div v-if="thinkParts.length" class="elegant-thinking" :class="{ running: message.status === 'running' }">
  <div class="elegant-thinking-head" @click="open = !open">...</div>
  <el-collapse-transition>
    <div v-show="open" class="elegant-thinking-body">
      <template v-for="(part, idx) in thinkParts">
        <ResponseSegmentBlock v-if="part.type === 'response_segment'" :key="'r' + idx" :part="part" mode="elegant" />
        <ElegantThinkItem v-else-if="part.type === 'think'" :key="'t' + idx" :part="part" />
        <ElegantToolItem v-else-if="part.type === 'tool_card'" :key="'c' + idx" :part="part" />
        <AppendedUserMessage v-else-if="part.type === 'appended_user_message'" :key="'a' + idx" :part="part" />
        <InterruptTraceItem v-else-if="part.type === 'interrupt_card'" :key="'i' + idx" :part="part" mode="elegant" />
      </template>
    </div>
  </el-collapse-transition>
</div>
```

### 3.2 调试模式

`src/modes/debug/DebugMessage.vue`：

```vue
<div v-if="processParts.length" class="process">
  <div class="process-head" @click="processOpen = !processOpen">...</div>
  <el-collapse-transition>
    <div v-show="processOpen" class="process-body">
      <template v-for="(part, idx) in processParts">
        <ResponseSegmentBlock v-if="part.type === 'response_segment'" :key="'r' + idx" :part="part" mode="debug" />
        <ThinkBlock v-else-if="part.type === 'think'" :key="'t' + idx" :part="part" />
        <ToolCard v-else-if="part.type === 'tool_card'" :key="'c' + idx" :part="part" />
        ...
      </template>
    </div>
  </el-collapse-transition>
</div>
```

两个模式都以 `part.type === 'response_segment'` 分发到同一个组件，仅 `mode` prop 不同（`elegant` / `debug`）。因此**改动收敛在 `ResponseSegmentBlock` 内部即可**，无需触碰两个模式文件。

### 3.3 与最终回答的区别

最终回答区单独使用 `MarkdownView`：

```vue
<div v-if="displayContent" class="answer">
  <MarkdownView :content="displayContent" :streaming="contentStreaming" mode="typewriter-blur" ... />
</div>
```

过程区用的是 `ResponseSegmentBlock`，二者是两条独立路径——这正是"回答区正常、过程区异常"的原因。

---

## 4. 方案

### 4.1 一句话

把 `ResponseSegmentBlock` 内部的纯文本插值替换为 `MarkdownView`，并选用 **instant 模式**（不播逐字动画）。

### 4.2 实现

```vue
<template>
  <div class="response-segment" :class="mode">
    <MarkdownView
      v-if="hasContent"
      :content="displayContent"
      mode="instant"
      :streaming="false"
    />
  </div>
</template>

<script>
import MarkdownView from './MarkdownView.vue'

export default {
  name: 'ResponseSegmentBlock',
  components: { MarkdownView },
  props: {
    part: { type: Object, required: true },
    mode: { type: String, default: 'elegant' }
  },
  computed: {
    displayContent() {
      return String(this.part.content || '').trim()
    },
    hasContent() {
      return Boolean(this.displayContent)
    }
  }
}
</script>
```

### 4.3 三个关键选择及理由

**① 复用 `MarkdownView` 而不是另写一套解析**

`MarkdownView` 已经封装了完整链路：`markdown-it` 解析 + `highlight.js` 代码高亮 + `DOMPurify` 消毒 + `katex` 公式。复用它意味着过程区与回答区**渲染结果完全一致**，后续任何 markdown 能力升级（比如新增语法支持）自动同步，不会出现两套解析器行为分叉。

同时它也解决了安全性：直接 `v-html` 塞入模型输出是 XSS 风险，`MarkdownView` 内部做了 `DOMPurify.sanitize`。

**② `mode="instant"` 而非 `typewriter-blur`**

`MarkdownView` 支持三种模式：

| 模式 | 行为 | 是否适用过程区 |
| --- | --- | --- |
| `instant` | 直接终态渲染，跳过 rAF 动画 | 适用 |
| `typewriter` | 逐字 + 光标 | 不适用 |
| `typewriter-blur` | 逐字 + 尾字模糊（回答区在用） | 不适用 |

理由：过程区文本是**轮次中间产物**，不是"正在逐字生成"的内容。若开动画，会带来两个问题：

- 用户展开过程区时，一段早已生成完的历史文字却从头开始逐字播，观感错误；
- 与 `chatCatchUp` 的追赶期抑制相互干扰——追赶期要求"关动画"，若过程区自身带独立动画，就绕过了这个抑制。

`instant` 分支在 `MarkdownView` 中的实现是直接短路：

```js
syncContent(force = false) {
  if (!this.animationActive || this.normalizedMode === MARKDOWN_STREAM_MODES.INSTANT) {
    this.finishRender(!this.streaming)
    return
  }
  // ...动画路径
}
```

**③ `hasContent` 守卫**

加 `hasContent` 判定后，空的 `response_segment` 完全不产生 DOM，避免过程区出现无意义的空白块。

---

## 5. 视觉处理：保持「次级信息」层级

过程区内容如果按回答区的样式渲染，会**比最终回答还抢眼**（标题大、间距宽）。因此用 `::v-deep` 对外层做一档收敛：

```less
.response-segment ::v-deep .markdown-body {
  font-size: 13px;      /* 回答区 14px */
  line-height: 1.7;
  color: inherit;       /* 继承 .response-segment 的 #46566c / #58677a */
}
.response-segment ::v-deep .markdown-body > *:first-child { margin-top: 0; }
.response-segment ::v-deep .markdown-body > *:last-child { margin-bottom: 0; }
.response-segment ::v-deep .markdown-body p { margin: 6px 0; }   /* 回答区 8px */

/* 标题统一压到 13.5px 且去掉回答区的下边框，避免在过程区喧宾夺主 */
.response-segment ::v-deep .markdown-body h1,
.response-segment ::v-deep .markdown-body h2,
.response-segment ::v-deep .markdown-body h3,
.response-segment ::v-deep .markdown-body h4 {
  margin: 10px 0 6px;
  font-size: 13.5px;
  border-bottom: 0;
  padding-bottom: 0;
}

.response-segment ::v-deep .markdown-body ul,
.response-segment ::v-deep .markdown-body ol { padding-left: 20px; margin: 6px 0; }
.response-segment ::v-deep .markdown-body li { margin: 2px 0; }
.response-segment ::v-deep .markdown-body pre { margin: 8px 0; }
.response-segment ::v-deep .markdown-body table { margin: 8px 0; }
```

要点：

| 处理 | 原因 |
| --- | --- |
| 字号 13px（回答区 14px） | 过程区是背景信息，视觉权重应低于结论 |
| `<h1>`~`<h4>` 统一压到 13.5px 且去边框 | 否则一段过程里出现大号标题，层级错乱 |
| 首尾 margin 归零 | 避免与外层 `padding: 2px 0 5px` 叠加出过大空隙 |
| 用 `::v-deep` | `MarkdownView` 内部是子组件渲染的 DOM，scoped 样式无法直接命中 |

同时**移除了原来的 `white-space: pre-wrap`**——现在换行由 markdown 的块级结构负责，保留它会导致解析后的 HTML 又被当成预格式化文本，产生双重缩进。

---

## 6. 边界：本次不动的部分

过程区里还有其他文本块，本次**刻意不改**：

| 块 | 承载组件 | 保持纯文本的原因 |
| --- | --- | --- |
| 思考内容 | `ElegantThinkItem` / `ThinkBlock` | 思考是模型的原始推理，保留原样更利于排查；且内容量大，全量 markdown 解析会明显增加展开耗时 |
| 工具输入参数 | `ElegantToolItem` / `ToolCard` | 参数本质是 JSON / 命令行，用 `<pre>` 等宽展示更准确，markdown 反而会误解析其中的 `*`、`_`、`#` |
| 工具输出结果 | `ElegantToolItem` / `ToolCard` | 同上，输出多为日志、文件片段、diff，等宽 + 原样保留才是正确呈现 |

判断标准：**"给用户读的自然语言"走 markdown，"给机器看的结构化数据"保持等宽原样。** `response_segment`（每轮模型发言）属于前者，思考与工具属于后者。

---

## 7. 验证

| 项 | 结果 |
| --- | --- |
| 反引号转换为行内代码样式 | 通过 |
| `**text**` 转换为加粗 | 通过 |
| 列表 / 有序列表 | 通过 |
| 代码块（含高亮） | 通过 |
| 表格 | 通过 |
| 行内与块级公式 | 通过（复用 katex） |
| 空 `response_segment` 不产生空白块 | 通过 |
| XSS 内容被消毒 | 通过（`DOMPurify`） |
| 优雅模式过程区 | 通过 |
| 调试模式过程区 | 通过 |
| `npm run build` | 通过 |

---

## 8. 涉及文件

| 文件 | 改动 |
| --- | --- |
| `src/components/ResponseSegmentBlock.vue` | 纯文本插值改为 `MarkdownView`；删除 `white-space: pre-wrap`；新增层级收敛样式 |

两个模式文件（`ElegantMessage.vue` / `DebugMessage.vue`）**无需改动**，因为它们只是把 `part` 透传给该组件。
