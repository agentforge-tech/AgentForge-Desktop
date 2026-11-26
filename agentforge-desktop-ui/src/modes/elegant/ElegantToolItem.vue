<template>
  <div class="elegant-tool" :class="{ done: part.isFinish }">
    <div class="elegant-tool-head" @click="open = !open">
      <!-- 执行中统一使用加载图标，避免静态图标误导用户认为操作已完成 -->
      <i v-if="!part.isFinish" class="elegant-tool-icon el-icon-loading"></i>
      <!-- 命令终端类：历史视觉基线，保持不变 -->
      <svg v-else-if="iconKey === 'terminal'" class="elegant-tool-icon elegant-tool-terminal" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <rect x="3" y="4" width="18" height="16" rx="2.5" stroke="currentColor" stroke-width="1.7" />
        <path d="m7 9 3 3-3 3M12.5 15H17" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <!-- 读取文件 -->
      <svg v-else-if="iconKey === 'read'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M6 3h8l4 4v14H6z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M14 3v5h4M9 12h6M9 16h6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <!-- 写入文件 -->
      <svg v-else-if="iconKey === 'write'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M4 3h7l3 3v13H4z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M11 3v4h3M7 9h4M7 12.5h4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        <path d="m14.5 20 1-3.4L20 12l2 2-4.6 4.6z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <!-- 编辑文件 -->
      <svg v-else-if="iconKey === 'edit'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="m4 20 1-4L15 6l3 3L8 19z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M14 7l3 3M5 16l3 3" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
      </svg>
      <!-- 查找文件 -->
      <svg v-else-if="iconKey === 'glob'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M3 6h6l2 2h10v10H3z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <circle cx="16" cy="12.5" r="2.8" stroke="currentColor" stroke-width="1.7" />
        <path d="m18 14.5 1.8 1.8" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
      </svg>
      <!-- 搜索内容 -->
      <svg v-else-if="iconKey === 'grep'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="11" cy="10" r="5" stroke="currentColor" stroke-width="1.7" />
        <path d="m15 14 4 4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
        <path d="M9 9h4M9 11.5h2.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
      </svg>
      <!-- 快速搜索 -->
      <svg v-else-if="iconKey === 'rg'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="11" cy="10" r="5" stroke="currentColor" stroke-width="1.7" />
        <path d="m15 14 4 4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
        <path d="m11.4 7.2-2.1 2.9h2.4l-1 2.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <!-- 读取资源 -->
      <svg v-else-if="iconKey === 'resource'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M6 3h7l4 4v14H6z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M13 3v4h4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M13.5 7.5v9.2a1.4 1.4 0 0 1-2.8 0V9a1 1 0 0 1 2 0v7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <!-- 加载技能 -->
      <svg v-else-if="iconKey === 'skill'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="m12 3 7 4v10l-7 4-7-4V7z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <path d="m12 9 1.1 2.3 2.5.3-1.9 1.7.5 2.5-2.2-1.2-2.2 1.2.5-2.5-1.9-1.7 2.5-.3z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <!-- 询问确认（备用：当前 ask_user 由中断卡片承载，不进入工具行） -->
      <svg v-else-if="iconKey === 'ask'" class="elegant-tool-icon elegant-tool-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M4 6h16v9H9l-4 3z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M11 9.5a1.5 1.5 0 1 1 2 1.4v1M12 14.3h.01" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <!-- 未映射工具兜底：通用扳手 -->
      <svg v-else class="elegant-tool-icon elegant-tool-wrench" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M14.7 6.3a4.1 4.1 0 0 0-5.1 5.1L3.5 17.5a2.1 2.1 0 0 0 3 3l6.1-6.1a4.1 4.1 0 0 0 5.1-5.1l-2.5 2.5-3-3 2.5-2.5Z"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      <span class="elegant-tool-name">{{ displayName }}</span>
      <span v-if="thought" class="elegant-tool-thought" :title="thought">{{ thought }}</span>
      <span v-if="part.isFinish && durationText" class="elegant-tool-dur">（{{ durationText }}）</span>
      <i class="el-icon-arrow-down elegant-tool-arrow" :class="{ open }"></i>
    </div>
    <el-collapse-transition>
      <div v-show="open" class="elegant-tool-body">
        <div v-if="part.outputResult" class="elegant-tool-section">
          <div class="elegant-tool-label">结果</div>
          <pre class="elegant-tool-code">{{ format(part.outputResult) }}</pre>
        </div>
        <div v-else class="elegant-tool-empty">暂无返回结果</div>
      </div>
    </el-collapse-transition>
  </div>
</template>

<script>
import { toolIconKey, toolLabel as resolveToolLabel } from '../../constants/toolMeta'

const THOUGHT_PARAMETER_NAMES = ['__thought', '_thought']

export default {
  name: 'ElegantToolItem',
  props: {
    part: { type: Object, required: true }
  },
  data() {
    return { open: false }
  },
  computed: {
    iconKey() {
      return toolIconKey(this.part.toolName)
    },
    displayName() {
      return resolveToolLabel(this.part.toolName)
    },
    thought() {
      const params = this.parseInputParams(this.part.inputParams)
      if (!params || typeof params !== 'object' || Array.isArray(params)) return ''
      const key = THOUGHT_PARAMETER_NAMES.find((name) => typeof params[name] === 'string' && params[name].trim())
      const thought = key ? params[key] : ''
      return typeof thought === 'string' ? thought.trim() : ''
    },
    durationText() {
      const duration = Number(this.part.duration)
      if (!Number.isFinite(duration) || duration <= 0) return ''
      if (duration < 1000) return `${Math.max(1, Math.round(duration))}ms`
      return `${(duration / 1000).toFixed(1)}s`
    }
  },
  methods: {
    parseInputParams(value) {
      if (value == null || typeof value === 'object') return value
      if (typeof value !== 'string' || !value.trim()) return null
      try {
        return JSON.parse(value)
      } catch (_error) {
        return null
      }
    },
    format(value) {
      if (value == null) return ''
      try {
        return typeof value === 'string'
          ? JSON.stringify(JSON.parse(value), null, 2)
          : JSON.stringify(value, null, 2)
      } catch (e) {
        return String(value)
      }
    }
  }
}
</script>

<style scoped>
.elegant-tool { margin: 7px 0 10px; }
.elegant-tool-head {
  display: flex;
  align-items: center;
  gap: 7px;
  width: fit-content;
  max-width: 100%;
  padding: 0;
  cursor: pointer;
  font-size: 12.5px;
  user-select: none;
}
.elegant-tool-head:hover .elegant-tool-name { color: #3f5570; }
.elegant-tool-icon { font-size: 13px; color: #91a0b6; flex: 0 0 auto; }
/* 所有 SVG 图标统一 15×15，终端图标保留历史配色 */
.elegant-tool-wrench,
.elegant-tool-terminal,
.elegant-tool-glyph { width: 15px; height: 15px; }
.elegant-tool-terminal { color: #7891b2; }
.elegant-tool.done .elegant-tool-icon { color: #91a0b6; }
/* 工具名始终显示，保持常规字重，避免与过程文字抢层级 */
.elegant-tool-name { flex: 0 0 auto; font-weight: 400; color: #718096; }
/* 意图文案作为次级说明；负 margin 抵消 flex gap，使分隔点紧贴工具名 */
.elegant-tool-thought {
  min-width: 0;
  margin-left: -5px;
  overflow: hidden;
  color: #97a3b4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.elegant-tool-thought::before {
  content: '·';
  margin-right: 2px;
  color: #c3cbd6;
}
.elegant-tool-dur { flex: 0 0 auto; color: #98a2b3; font-size: 11px; padding: 0; }
.elegant-tool-arrow { color: #b0b8c4; font-size: 11px; transition: transform 0.2s; }
.elegant-tool-arrow.open { transform: rotate(180deg); }
.elegant-tool-body { margin-top: 6px; padding: 9px 10px; border-radius: 6px; background: #f7f9fc; }
.elegant-tool-section { margin-bottom: 8px; }
.elegant-tool-label { font-size: 11px; color: #98a2b3; margin-bottom: 4px; }
.elegant-tool-code {
  margin: 0;
  padding: 8px 10px;
  background: #f7f8fa;
  border: 1px solid #eceff4;
  border-radius: 6px;
  font-size: 11.5px;
  font-family: Consolas, monospace;
  color: #4a5568;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.elegant-tool-empty { font-size: 12px; color: #b0b8c4; }
</style>