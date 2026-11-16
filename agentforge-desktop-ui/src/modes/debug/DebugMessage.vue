<template>
  <div class="agent-message">
    <!-- 执行过程时间线 -->
    <div v-if="processParts.length" class="process">
      <div class="process-head" @click="processOpen = !processOpen">
        <i :class="processOpen ? 'el-icon-caret-bottom' : 'el-icon-caret-right'"></i>
        <span class="process-title">{{ processTitle }}</span>
        <span class="process-meta">
          <template v-if="responseCount">{{ responseCount }} 个响应片段 · </template>{{ thinkCount }} 段思考<template v-if="toolCount"> · {{ toolCount }} 个工具</template>
          <template v-if="askCount"> · {{ askCount }} 次询问</template>
          <template v-if="elapsed"> · {{ elapsed }}</template>
        </span>
      </div>
      <el-collapse-transition>
        <div v-show="processOpen" class="process-body">
          <template v-for="(part, idx) in processParts">
            <ResponseSegmentBlock v-if="part.type === 'response_segment'" :key="'r' + idx" :part="part" mode="debug" />
            <ThinkBlock v-else-if="part.type === 'think'" :key="'t' + idx" :part="part" />
            <ToolCard v-else-if="part.type === 'tool_card'" :key="'c' + idx" :part="part" />
            <AppendedUserMessage v-else-if="part.type === 'appended_user_message'" :key="'a' + idx" :part="part" />
            <InterruptTraceItem v-else-if="part.type === 'interrupt_card'" :key="'i' + idx" :part="part" mode="debug" />
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

    <!-- 运行中占位：只要本轮仍在执行就保留，避免思考/工具阶段出现空白 -->
    <div v-if="message.status === 'running'" class="typing">
      <span class="typing-text">正在思考</span>
    </div>

    <!-- 错误 -->
    <div v-if="message.error && message.status !== 'cancelled'" class="error-box">
      <i class="el-icon-warning-outline"></i>
      <span>{{ message.error }}</span>
    </div>
  </div>
</template>

<script>
import MarkdownView from '../../components/MarkdownView.vue'
import { formatElapsedDuration } from '../../utils/formatDuration'
import ThinkBlock from './ThinkBlock.vue'
import ToolCard from './ToolCard.vue'
import AppendedUserMessage from '../../components/AppendedUserMessage.vue'
import InterruptTraceItem from '../../components/InterruptTraceItem.vue'
import ResponseSegmentBlock from '../../components/ResponseSegmentBlock.vue'

export default {
  name: 'DebugMessage',
  components: { MarkdownView, ThinkBlock, ToolCard, AppendedUserMessage, InterruptTraceItem, ResponseSegmentBlock },
  props: {
    message: { type: Object, required: true }
  },
  data() {
    return {
      processOpen: this.message.status === 'running' || this.message.status === 'waiting'
    }
  },
  computed: {
    processParts() {
      return (this.message.parts || []).filter((p) =>
        p.type !== 'text' &&
        p !== this.activeResponseSegment &&
        (p.type !== 'response_segment' || String(p.content || '').trim())
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
    thinkCount() {
      return this.processParts.filter((p) => p.type === 'think').length
    },
    responseCount() {
      return this.processParts.filter((p) => p.type === 'response_segment').length
    },
    toolCount() {
      return this.processParts.filter((p) => p.type === 'tool_card').length
    },
    askCount() {
      return this.processParts.filter((p) => p.type === 'interrupt_card').length
    },
    processTitle() {
      if (this.message.status === 'running') return '执行中...'
      if (this.message.status === 'waiting') return '等待确认'
      return '执行过程'
    },
    elapsed() {
      return formatElapsedDuration(this.message.elapsedMs)
    }
  },
  watch: {
    'message.status'(val) {
      // 运行中与等待确认时保持展开，只有完成/失败/取消才收拢。
      this.processOpen = val === 'running' || val === 'waiting'
    }
  }
}
</script>

<style scoped>
.process {
  max-width: 680px;
  border: 0;
  border-radius: 0;
  background: transparent;
  margin-bottom: 10px;
  overflow: hidden;
}
.process-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
  cursor: pointer;
  user-select: none;
  font-size: 12.5px;
  color: #5a6472;
}
.process-head:hover { background: transparent; }
.process-title { font-weight: 600; color: #3b6fb6; }
.process-meta { color: #98a2b3; font-size: 11.5px; margin-left: auto; }
.process-body { padding: 4px 0 10px; }

.answer { margin: 2px 0 8px; padding-left: 0; }

.typing { display: flex; min-height: 22px; align-items: center; padding: 0; margin-top: 2px; }
.typing-text {
  display: inline-block;
  background: linear-gradient(90deg, #c4cdd9 0%, #c4cdd9 40%, #3b6fb6 50%, #c4cdd9 60%, #c4cdd9 100%);
  background-size: 220% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: .3px;
  animation: typing-shimmer 1.8s linear infinite;
}
.typing-text::after { content: '...'; }
@keyframes typing-shimmer {
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
