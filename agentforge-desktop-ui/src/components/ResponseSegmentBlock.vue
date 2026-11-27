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

/**
 * 过程区里"每一轮模型单独发言"的正文块。
 *
 * 历史实现直接把文本塞进 pre-wrap 容器，`**` / 反引号 / 列表 / 代码块
 * 全部原样露出。这里改为走 MarkdownView，与最终回答保持一致的渲染结果。
 *
 * 用 mode="instant" 且不传 streaming：
 *   - 过程区文本是轮次中间产物，不需要逐字动画；
 *   - 也避免与 chatCatchUp 的追赶期抑制互相干扰。
 */
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

<style scoped>
.response-segment {
  min-width: 0;
  padding: 2px 0 5px;
  color: #46566c;
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.response-segment.elegant { color: #58677a; }

/*
 * 过程区是次级信息：标题降字号、间距收紧，
 * 避免整段过程比最终回答更抢眼。
 */
.response-segment ::v-deep .markdown-body {
  font-size: 13px;
  line-height: 1.7;
  color: inherit;
}
.response-segment ::v-deep .markdown-body > *:first-child { margin-top: 0; }
.response-segment ::v-deep .markdown-body > *:last-child { margin-bottom: 0; }
.response-segment ::v-deep .markdown-body p { margin: 6px 0; }
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
</style>
