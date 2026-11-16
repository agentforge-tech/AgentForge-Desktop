<template>
  <div class="elegant-message">
    <!-- 思考过程：模型思考 + 工具调用，统一收拢为轻量事件流 -->
    <div v-if="thinkParts.length" class="elegant-thinking" :class="{ running: message.status === 'running' }">
      <div class="elegant-thinking-head" @click="open = !open">
        <span class="elegant-thinking-title">
          {{ statusTitle }}<span v-if="doneElapsed" class="elegant-thinking-elapsed"> {{ doneElapsed }}</span>
        </span>
        <i class="el-icon-arrow-down elegant-thinking-arrow" :class="{ open }"></i>
      </div>

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

    <!-- 最终回答 -->
    <div v-if="displayContent" class="answer">
      <MarkdownView
        :content="displayContent"
        :streaming="contentStreaming"
        mode="typewriter-blur"
        @render-complete="$emit('render-complete', $event)"
      />
    </div>

    <div v-if="message.status === 'running'" class="elegant-typing">
      <span class="elegant-typing-text">正在思考</span>
    </div>

    <div v-if="message.error && message.status !== 'cancelled'" class="error-box">
      <i class="el-icon-warning-outline"></i>
      <span>{{ message.error }}</span>
    </div>
  </div>
</template>

<script>
import MarkdownView from '../../components/MarkdownView.vue'
import { formatElapsedDuration } from '../../utils/formatDuration'
import ElegantThinkItem from './ElegantThinkItem.vue'
import ElegantToolItem from './ElegantToolItem.vue'
import AppendedUserMessage from '../../components/AppendedUserMessage.vue'
import InterruptTraceItem from '../../components/InterruptTraceItem.vue'
import ResponseSegmentBlock from '../../components/ResponseSegmentBlock.vue'

export default {
  name: 'ElegantMessage',
  components: { MarkdownView, ElegantThinkItem, ElegantToolItem, AppendedUserMessage, InterruptTraceItem, ResponseSegmentBlock },
  props: {
    message: { type: Object, required: true }
  },
  data() {
    return { open: this.message.status === 'running' || this.message.status === 'waiting' }
  },
  computed: {
    thinkParts() {
      return (this.message.parts || []).filter(
        (p) => p !== this.activeResponseSegment &&
          (p.type !== 'response_segment' || String(p.content || '').trim()) &&
          (p.type === 'response_segment' || p.type === 'think' || p.type === 'tool_card' || p.type === 'appended_user_message' || p.type === 'interrupt_card')
      )
    },
    activeResponseSegment() {
      const parts = this.message.parts || []
      for (let index = parts.length - 1; index >= 0; index -= 1) {
        const part = parts[index]
        if (part.type === 'response_segment' && part.status === 'streaming') return part
      }
      return null
    },
    displayContent() {
      return this.message.content || (this.activeResponseSegment && this.activeResponseSegment.content) || ''
    },
    contentStreaming() {
      return !this.message.content && !!this.activeResponseSegment && this.message.status === 'running'
    },
    elapsed() {
      return formatElapsedDuration(this.message.elapsedMs)
    },
    statusTitle() {
      if (this.message.status === 'running') return '正在处理'
      if (this.message.status === 'waiting') return '等待确认'
      if (this.message.status === 'error') return '处理失败'
      if (this.message.status === 'cancelled') return '已取消'
      return '已完成'
    },
    doneElapsed() {
      return this.message.status !== 'running' ? this.elapsed : ''
    }
  },
  watch: {
    'message.status'(val) {
      this.open = val === 'running' || val === 'waiting'
    }
  }
}
</script>

<style scoped>
.elegant-thinking { margin: 0 0 4px; }
/* 思考/工具过程与下方“正在思考”紧邻时收紧间距，避免出现大段空白。 */
.elegant-thinking.running { margin-bottom: 2px; }
.elegant-thinking.running .elegant-thinking-body { padding-bottom: 0; }
.elegant-thinking-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  height: 32px; /* 与发布页左侧 .bot-avatar 保持同高，使状态行与 Logo 垂直居中 */
  padding: 0;
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  color: #8490a2;
}
.elegant-thinking-head:hover .elegant-thinking-title { color: #4f6f9f; }
.elegant-thinking-title { font-weight: 400; line-height: 1; color: #7c889b; transform: translateY(-0.5px); }
.elegant-thinking.running .elegant-thinking-title { color: #657791; }
.elegant-thinking-elapsed { color: #98a2b3; font-weight: 400; font-size: 11.5px; }
.elegant-thinking-arrow { color: #aeb7c4; font-size: 11px; transition: transform 0.2s; }
.elegant-thinking-arrow.open { transform: rotate(180deg); }
.elegant-thinking-body { padding: 4px 0 1px; }

.answer { margin: 0 0 8px; }

.elegant-typing {
  display: flex;
  align-items: center;
  height: 22px;
  padding: 0;
  margin-top: 2px;
}
.elegant-typing-text {
  display: inline-block;
  font-size: 13.5px;
  line-height: 1;
  font-weight: 500;
  letter-spacing: 0.5px;
  transform: translateY(-0.5px); /* 校正中文字形相对行盒的视觉中心 */
  background: linear-gradient(90deg, #c4cdd9 0%, #c4cdd9 40%, #3b6fb6 50%, #c4cdd9 60%, #c4cdd9 100%);
  background-size: 220% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  animation: elegant-shimmer 1.8s linear infinite;
}
.elegant-typing-text::after { content: '...'; }
@keyframes elegant-shimmer {
  0% { background-position: 120% 0; }
  100% { background-position: -120% 0; }
}

.error-box {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: #fff5f5;
  border: 1px solid #ffd7d7;
  color: #d64545;
  border-radius: 8px;
  font-size: 12.5px;
  margin-bottom: 8px;
}
</style>
