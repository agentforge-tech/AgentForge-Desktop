<template>
  <el-button
    type="text"
    class="test-action-btn"
    :class="{ 'is-testing': loading }"
    :aria-busy="loading ? 'true' : 'false'"
    :title="loading ? loadingLabel : label"
    @click="handleClick"
  >
    <span v-if="loading" class="test-action-spinner" aria-hidden="true">
      <svg viewBox="0 0 16 16">
        <circle class="test-action-spinner-track" cx="8" cy="8" r="6" />
        <circle class="test-action-spinner-arc" cx="8" cy="8" r="6" />
      </svg>
    </span>
    <span class="test-action-label">{{ loading ? loadingLabel : label }}</span>
  </el-button>
</template>

<script>
/**
 * 模型连通性测试入口：点击后按钮进入「测试中」旋转态。
 *
 * 连通性测试常在几十毫秒内返回，原生文本按钮没有任何中间态，
 * 用户会觉得“点了没反应”。这里用环形旋转替代纯文字，并在测试
 * 结束后恢复为「测试」，让交互结果可感知。
 */
export default {
  name: 'TestActionButton',
  props: {
    loading: { type: Boolean, default: false },
    label: { type: String, default: '测试' },
    loadingLabel: { type: String, default: '测试中' }
  },
  methods: {
    handleClick(event) {
      // 同一条模型重复点击直接忽略，避免并发请求把反馈状态刷乱。
      if (this.loading) {
        event.stopPropagation()
        return
      }
      this.$emit('click', event)
    }
  }
}
</script>

<style scoped>
/* 固定最小宽度让「测试 / 测试中」两种状态尺寸一致，既避免相邻按钮跳动，
   也顺带把点击热区从 2 个字扩大到整块按钮。 */
.test-action-btn {
  display: inline-flex;
  min-width: 56px;
  align-items: center;
  justify-content: center;
  padding-left: 4px;
  padding-right: 4px;
  transition: color 0.15s ease;
}

.test-action-btn.is-testing,
.test-action-btn.is-testing:hover,
.test-action-btn.is-testing:focus {
  color: #7c97bd;
  cursor: default;
}

.test-action-label {
  line-height: 1;
}

.test-action-spinner {
  display: inline-flex;
  width: 12px;
  height: 12px;
  margin-right: 4px;
  animation: test-action-rotate 0.85s linear infinite;
}

.test-action-spinner svg {
  width: 12px;
  height: 12px;
  fill: none;
  stroke-width: 2;
}

.test-action-spinner-track {
  stroke: currentColor;
  opacity: 0.2;
}

.test-action-spinner-arc {
  stroke: currentColor;
  stroke-linecap: round;
  stroke-dasharray: 11 27;
}

@keyframes test-action-rotate {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  /* 尊重「减弱动态效果」：降低速度而不是完全取消，仍能表达进行中。 */
  .test-action-spinner {
    animation-duration: 2.4s;
  }
}
</style>
