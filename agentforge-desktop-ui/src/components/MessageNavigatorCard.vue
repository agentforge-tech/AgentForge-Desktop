<template>
  <div class="message-navigator-card" :class="[{ 'is-compact': compact }, statusClass]" role="presentation">
    <div class="mnc-meta">
      <span class="mnc-round">{{ roundLabel }}<span v-if="timeLabel"> · {{ timeLabel }}</span></span>
      <span v-if="statusLabel" class="mnc-status" :class="statusClass">{{ statusLabel }}</span>
    </div>
    <div class="mnc-title">{{ anchor.title }}</div>
    <div v-if="anchor.digest" class="mnc-digest">{{ anchor.digest }}</div>
    <div v-if="footnotes.length" class="mnc-foot">
      <span v-for="item in footnotes" :key="item">{{ item }}</span>
    </div>
  </div>
</template>

<script>
const STATUS_LABEL = {
  error: '错误',
  running: '执行中',
  interrupt: '待确认',
  cancelled: '已停止'
}

export default {
  name: 'MessageNavigatorCard',
  props: {
    anchor: { type: Object, required: true },
    index: { type: Number, default: 0 },
    // 真实轮次区间：聚合态一根刻度代表多轮，displayIndex 会与真实轮次脱节
    roundStart: { type: Number, default: 0 },
    roundCount: { type: Number, default: 1 },
    total: { type: Number, default: 0 },
    compact: { type: Boolean, default: false }
  },
  computed: {
    roundLabel() {
      const start = this.roundStart || this.index + 1
      if (this.roundCount > 1) return `第 ${start}–${start + this.roundCount - 1} 轮`
      return `第 ${start} 轮`
    },
    statusClass() {
      const status = this.anchor.status || 'ok'
      return status === 'ok' ? '' : 'is-' + status
    },
    statusLabel() {
      return STATUS_LABEL[this.anchor.status] || ''
    },
    timeLabel() {
      return this.anchor.timeLabel || ''
    },
    footnotes() {
      const notes = []
      const anchor = this.anchor
      if (anchor.resources) notes.push(`📎 ${anchor.resources} 个资源`)
      if (anchor.toolCount) notes.push(`${anchor.toolCount} 个工具`)
      if (anchor.appendCount) notes.push(`${anchor.appendCount} 条追加指令`)
      return notes
    }
  }
}
</script>

<style scoped>
.message-navigator-card {
  position: relative;
  width: 300px;
  max-width: 42vw;
  /* §7.3：卡片高度上限 132px，避免长摘要把浮层撑高、跳动感明显 */
  max-height: 132px;
  box-sizing: border-box;
  padding: 10px 12px 10px 14px;
  border: 1px solid #e4e9f2;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(23, 43, 77, 0.12);
  overflow: hidden;
  text-align: left;
  pointer-events: none;
}

/* 左侧状态色条：让"这一轮是什么状态"一眼可辨，不额外增加 DOM */
.message-navigator-card::before {
  content: '';
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 5px;
  width: 2px;
  border-radius: 1px;
  background: #dbe2ee;
}

.message-navigator-card.is-error::before {
  background: #d64545;
}

.message-navigator-card.is-running::before {
  background: #3b6fb6;
}

.message-navigator-card.is-interrupt::before {
  background: #d98800;
}

.message-navigator-card.is-cancelled::before {
  background: #c2cad6;
}

.message-navigator-card.is-compact {
  width: 260px;
  padding: 9px 11px 9px 13px;
}

.mnc-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  color: #8a94a6;
  font-size: 11px;
  line-height: 1.4;
}

.mnc-round {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mnc-status {
  margin-left: auto;
  flex: 0 0 auto;
  padding: 0 5px;
  border-radius: 4px;
  font-size: 10.5px;
  line-height: 16px;
}

.mnc-status.is-error {
  background: #fff0f0;
  color: #d64545;
}

.mnc-status.is-running {
  background: #eef4fd;
  color: #3b6fb6;
}

.mnc-status.is-interrupt {
  background: #fff6e6;
  color: #c47d00;
}

.mnc-status.is-cancelled {
  background: #f3f5f8;
  color: #8a94a6;
}

.mnc-title {
  display: -webkit-box;
  overflow: hidden;
  color: #1f2937;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
  word-break: break-word;
}

.mnc-digest {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 4px;
  color: #5b6675;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.is-compact .mnc-digest {
  -webkit-line-clamp: 2;
}

.mnc-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
  color: #98a2b3;
  font-size: 11px;
}

@media (prefers-color-scheme: dark) {
  .message-navigator-card {
    border-color: #2f3742;
    background: #1e232b;
    box-shadow: 0 10px 28px rgba(0, 0, 0, 0.45);
  }

  .message-navigator-card::before {
    background: #39404d;
  }

  .mnc-title {
    color: #e6eaf2;
  }

  .mnc-digest {
    color: #a7b0bf;
  }
}
</style>
