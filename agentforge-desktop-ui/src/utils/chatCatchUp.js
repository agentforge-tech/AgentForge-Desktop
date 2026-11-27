/**
 * 会话回放 —— 追赶（catch-up）与直播（live）模式判定。
 *
 * 背景：运行中的会话 `events` 列为空，打开时 `afterSeq = 0`，
 * 后端会把本轮全部历史事件按 100 条一批密集重推。这套积压若走"直播管道"渲染
 * （逐字动画、每条事件滚动一次），就会出现"顶部过程区先出来、正文过一会才到最新"。
 *
 * 本模块只做三件事，不改事件协议、不改渲染语义：
 *   1. 用事件到达密度判定"当前在追赶还是已追平"
 *   2. 追赶期间给出抑制信号，供滚动 / 锚点 / 中断卡片 / 正文动画各自短路
 *   3. 追平瞬间做一次落底收敛，保证视口一次性落到最新
 *
 * 判定依据：积压阶段后端 `awaitAfter` 每轮立即返回满批、连续无间隙；
 * 追平后事件间隔骤增。因此"密集突发 + 随后静默"是追赶终点的稳定特征。
 * 同时要求曾经密集过才进入追赶，稀疏直播永远不会被误判。
 *
 * 未接入时行为与现状完全一致。
 */

/** 静默多久认为追平。需大于后端 POLL_MS(200ms)，留出网络与主线程抖动余量。 */
const CATCHUP_GAP_MS = 400

/** 相邻事件间隔小于该值视为"同一批突发"。 */
const CATCHUP_BURST_GAP_MS = 120

/** 连续突发达到该条数才认为"确实在追赶"，普通直播事件稀疏、达不到。 */
const CATCHUP_BURST_MIN = 3

/**
 * 落底收敛的硬上限。
 * 必须留足余量：长会话首次渲染（Markdown 高亮、轨道让位重排）远超 320ms，
 * 上限过小会把收敛强行打断，表现就是"差一截，要自己往下滑"。
 * 正常情况由"高度连续稳定"判据提前收手，这里只是极端兜底。
 */
const SETTLE_MAX_MS = 1500

const now = () =>
  typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? performance.now()
    : Date.now()

/** 该消息当前是否处于追赶态。渲染层据此关闭正文逐字动画。 */
export function isCatchingUp(message) {
  return Boolean(message && message.catchingUp)
}

/**
 * 正文是否应走流式渲染。收敛到一处，
 * 避免 ElegantMessage / DebugMessage 各写一份判断而逐渐漂移。
 */
export function shouldStreamContent(message) {
  if (!message || isCatchingUp(message)) return false
  if (message.content) return false
  if (message.status !== 'running') return false
  const parts = message.parts || []
  for (let index = parts.length - 1; index >= 0; index -= 1) {
    const part = parts[index]
    if (part.type === 'response_segment' && part.status === 'streaming') return true
  }
  return false
}

export default {
  data: () => ({
    /** 会话级追赶总开关：中断卡片门禁读它，追赶期间不弹阻断式确认。 */
    catchUpActive: false
  }),
  created() {
    // 每条 answer 一份判定状态。用 WeakMap 挂载而非 Vue data，避免高频事件驱动重渲染。
    this._cuStates = new WeakMap()
    this._cuCatching = new Set()
    this._cuSettleObserver = null
    this._cuSettleTimer = null
    this._cuSettleRaf = null
    this._cuSettleTicker = null
    this._cuSettleIntent = null
    // 非响应式：供 onFeedScroll 短路程序化滚动，避免误判成用户上滑。
    this._cuSettleActive = false
  },
  beforeDestroy() {
    this.teardownCatchUp()
  },
  methods: {
    catchUpState(answer) {
      if (!answer) return null
      let state = this._cuStates.get(answer)
      if (!state) {
        state = { lastAt: 0, burst: 0, catching: false, timer: null }
        this._cuStates.set(answer, state)
      }
      return state
    },

    /**
     * 消息对象上的 catchingUp 标记必须可响应，渲染层才能跟着切换。
     * 用 $set 兜底：即使消息创建时未预置该字段也不会失效。
     */
    ensureCatchUpFlag(answer) {
      if (!answer || typeof answer !== 'object') return
      if (!Object.prototype.hasOwnProperty.call(answer, 'catchingUp')) {
        this.$set(answer, 'catchingUp', false)
      }
    },

    /** 每个 SSE 事件调一次。只做算术与一次 setTimeout 重置，不涉及 DOM 与遍历。 */
    noteCatchUpEvent(answer) {
      const state = this.catchUpState(answer)
      if (!state) return
      this.ensureCatchUpFlag(answer)

      const at = now()
      const gap = state.lastAt ? at - state.lastAt : Number.POSITIVE_INFINITY
      state.lastAt = at
      state.burst = gap <= CATCHUP_BURST_GAP_MS ? state.burst + 1 : 1

      // 首次达到突发阈值才进入追赶态：稀疏直播永远不会命中。
      if (!state.catching && state.burst >= CATCHUP_BURST_MIN) {
        this.enterCatchUp(answer, state)
      }
      this.armCatchUpIdle(answer, state)
    },

    enterCatchUp(answer, state) {
      const target = state || this.catchUpState(answer)
      if (!target || target.catching) return
      target.catching = true
      if (answer) {
        this.ensureCatchUpFlag(answer)
        answer.catchingUp = true
        this._cuCatching.add(answer)
      }
      this.catchUpActive = this._cuCatching.size > 0
    },

    /**
     * 显式进入追赶：用于"重连且 events 列为空"这种**确定性**场景。
     *
     * 密度判定要等连续 3 条事件才生效，在那之前首帧已经按 running 渲染了
     * （过程区展开、显示"正在思考"、中断卡片弹出）——这正是首帧闪烁的来源。
     * 而重连时若 lastEventSeq === 0，意味着整段内容都要靠 SSE 回放，
     * 必然处于追赶，可以直接前置判定，首帧就不会进直播态。
     */
    beginCatchUp(answer) {
      const state = this.catchUpState(answer)
      if (!state) return
      this.ensureCatchUpFlag(answer)
      this.enterCatchUp(answer, state)
      this.armCatchUpIdle(answer, state)
    },

    /** 静默窗口重新计时；到期即视为追平。追赶期每个事件都会重置，所以不会提前结束。 */
    armCatchUpIdle(answer, state) {
      if (!state || !state.catching) return
      if (state.timer) clearTimeout(state.timer)
      state.timer = setTimeout(() => {
        state.timer = null
        this.endCatchUp(answer, state)
      }, CATCHUP_GAP_MS)
    },

    /** 追平：撤抑制信号，并做一次落底收敛。 */
    endCatchUp(answer, state) {
      const target = state || this.catchUpState(answer)
      if (target) {
        if (target.timer) clearTimeout(target.timer)
        target.timer = null
        target.catching = false
      }
      if (answer) {
        if (typeof answer === 'object') answer.catchingUp = false
        this._cuCatching.delete(answer)
      }
      this.catchUpActive = this._cuCatching.size > 0
      this.settleBottom()
    },

    /** 供页面判定：该条消息的滚动 / 锚点同步是否应该被抑制。 */
    isCatchUp(answer) {
      const state = this.catchUpState(answer)
      return Boolean(state && state.catching)
    },

    /**
     * 落底收敛：一次性把视口落到最新。
     *
     * 只做一次 `scrollTop = scrollHeight` 不够 —— 首帧之后高度还会变：
     *   - 缩略导航刚就位导致让位 padding（chat.css 的 has-navigator）
     *   - Markdown 渲染、图片 / 字体 / 代码高亮引起的迟滞回流
     * 因此按"内容高度连续稳定"判断收敛完成，而不是固定等待一小段时间。
     *
     * @param {{ force?: boolean }} options
     *   force=true 用于"刚打开会话"：此时必然要落到最新，
     *   不能被残留的 isUserScrolledUp 挡住，也不把程序化滚动误判成用户上滑。
     *   中断条件改为真实输入（wheel / touchstart / 按键）。
     */
    settleBottom(options) {
      const force = Boolean(options && options.force)
      const feed = this.$refs && this.$refs.feed
      if (!feed) return
      this.teardownSettle()

      // force 模式下用户一有真实滚动意图就立刻让位，交还给用户。
      const onUserIntent = () => {
        this.teardownSettle()
      }
      if (force) {
        this._cuSettleIntent = onUserIntent
        feed.addEventListener('wheel', onUserIntent, { passive: true })
        feed.addEventListener('touchstart', onUserIntent, { passive: true })
      }

      const pin = () => {
        // 非 force（追平）时尊重跟随状态；force（打开会话）时必须到底。
        if (!force && this.isUserScrolledUp) return
        feed.scrollTop = feed.scrollHeight
      }

      // 收敛期关闭浏览器原生滚动锚定，避免它和我们的落底互相拉扯。
      feed.classList.add('is-settling')
      // 通知 onFeedScroll 忽略这段程序化滚动（否则会被判成用户上滑）。
      this._cuSettleActive = true

      // force（打开会话）先同步落一次：双 rAF 要等下一帧，若渲染繁忙，
      // 中间会有一帧停在顶部，用户能感知到"没到底"。
      if (force) pin()

      // 双 rAF：覆盖让位 padding 与本帧内新增高度。
      this._cuSettleRaf = requestAnimationFrame(() => {
        this._cuSettleRaf = requestAnimationFrame(() => {
          this._cuSettleRaf = null
          pin()
        })
      })

      // 高度稳定判据：连续两次采样高度不再变化，才认为真正到底。
      // 单靠固定毫秒数会在"渲染比窗口慢"时提前收手，正是停在中间的成因。
      let lastHeight = -1
      let stableRounds = 0
      const sample = () => {
        pin()
        const height = feed.scrollHeight
        if (height === lastHeight) {
          stableRounds += 1
        } else {
          stableRounds = 0
          lastHeight = height
        }
        // 连续 3 次稳定（约 3 帧）判定收敛完成。
        if (stableRounds >= 3) {
          this.teardownSettle()
          return true
        }
        return false
      }

      if (typeof ResizeObserver !== 'undefined' && feed.firstElementChild) {
        this._cuSettleObserver = new ResizeObserver(() => {
          if (sample()) return
          this.armSettleTicker(sample)
        })
        this._cuSettleObserver.observe(feed.firstElementChild)
      }

      // 兜底轮询：ResizeObserver 不触发（例如只有宽度变化无高度变化）时也能推进。
      this._cuSettleTicker = setInterval(() => {
        if (sample()) return
      }, 80)

      // 硬上限：极端情况下（超长文档持续渲染）也要收手，避免长期占用主线程。
      this._cuSettleTimer = setTimeout(() => this.teardownSettle(), SETTLE_MAX_MS)
    },

    armSettleTicker(sample) {
      if (this._cuSettleTicker) return
      this._cuSettleTicker = setInterval(() => {
        if (sample()) return
      }, 80)
    },

    teardownSettle() {
      if (this._cuSettleObserver) {
        this._cuSettleObserver.disconnect()
        this._cuSettleObserver = null
      }
      if (this._cuSettleTicker) {
        clearInterval(this._cuSettleTicker)
        this._cuSettleTicker = null
      }
      if (this._cuSettleTimer) {
        clearTimeout(this._cuSettleTimer)
        this._cuSettleTimer = null
      }
      if (this._cuSettleRaf) {
        cancelAnimationFrame(this._cuSettleRaf)
        this._cuSettleRaf = null
      }
      const feed = this.$refs && this.$refs.feed
      if (feed) {
        feed.classList.remove('is-settling')
        if (this._cuSettleIntent) {
          feed.removeEventListener('wheel', this._cuSettleIntent)
          feed.removeEventListener('touchstart', this._cuSettleIntent)
        }
      }
      this._cuSettleIntent = null
      this._cuSettleActive = false
    },

    /** 切会话 / 新建会话 / 组件销毁时复位，避免上一轮计时器跨会话生效。 */
    teardownCatchUp() {
      this.teardownSettle()
      if (this._cuCatching) {
        this._cuCatching.forEach((message) => {
          // 必须清掉挂起计时器：WeakMap 换成新实例后旧 timer 仍会触发，
          // 那时 endCatchUp 会调用 settleBottom()，把刚打开的新会话又拉到底部。
          const state = this._cuStates.get(message)
          if (state && state.timer) clearTimeout(state.timer)
          if (state) state.catching = false
          if (message && typeof message === 'object') message.catchingUp = false
        })
        this._cuCatching.clear()
      }
      this._cuStates = new WeakMap()
      this.catchUpActive = false
    }
  }
}
