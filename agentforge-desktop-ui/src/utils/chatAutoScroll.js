export default {
  data: () => ({
    isUserScrolledUp: false,
    followScroll: true
  }),
  methods: {
    onFeedScroll() {
      // 导航轨跳转期间的滚动由跳转逻辑接管：
      // 平滑滚动会被误判成"用户上滑"，导致回到底部按钮与跟随态抖动。
      if (this.navigatorJumping) return
      // 落底收敛期间同样是程序化滚动：内容刚挂载时 scrollHeight 还在增长，
      // distance 会瞬间大于阈值，若不屏蔽会把 followScroll 误置为 false。
      if (this._cuSettleActive) return
      const el = this.$refs.feed
      if (!el) return
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight
      this.isUserScrolledUp = distance > 80
      this.followScroll = !this.isUserScrolledUp
      if (this.isUserScrolledUp) this.navigatorHasNew = false
      // 无 IntersectionObserver 时按 rAF 兜底更新当前轮（同一帧只测一次）
      if (typeof this.updateActiveByLayout === 'function' && !this._navObserver) {
        this.updateActiveByLayout()
      }
    },
    scroll() {
      this.$nextTick(() => {
        const el = this.$refs.feed
        if (el && this.followScroll) el.scrollTop = el.scrollHeight
      })
    },
    scrollToBottom() {
      this.followScroll = true
      this.isUserScrolledUp = false
      this.$nextTick(() => {
        const el = this.$refs.feed
        if (el) el.scrollTop = el.scrollHeight
      })
    },
    resetScrollFollow() {
      this.followScroll = true
      this.isUserScrolledUp = false
    }
  }
}
