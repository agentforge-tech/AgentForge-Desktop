export default {
  data: () => ({
    isUserScrolledUp: false,
    followScroll: true
  }),
  methods: {
    onFeedScroll() {
      const el = this.$refs.feed
      if (!el) return
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight
      this.isUserScrolledUp = distance > 80
      this.followScroll = !this.isUserScrolledUp
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
