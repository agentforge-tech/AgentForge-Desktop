/**
 * 长会话消息缩略导航 —— 控制器。
 *
 * 只负责"观测 / 跳转 / 节流 / 生命周期"，不渲染节点、不改 messages。
 * 页面需要提供 `ref="feed"` 的滚动容器，并在消息变更处调用 `scheduleAnchorSync()`。
 */
import { ANCHOR_ATTR, NAV_MIN, buildAnchors, diffAnchors } from './messageAnchors'

const STORAGE_KEY = 'af:message-navigator'
const SYNC_INTERVAL = 200
const JUMP_WINDOW = 420
const NARROW_WIDTH = 1152
const MIN_HEIGHT = 200
const JUMP_PADDING = 12
const FLASH_MS = 1200

function readEnabled() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    return raw === null ? true : raw !== '0'
  } catch (_error) {
    return true
  }
}

function prefersReducedMotion() {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch (_error) {
    return false
  }
}

const ANCHOR_FIELDS = ['key', 'userIndex', 'title', 'digest', 'resources', 'toolCount', 'appendCount', 'status', 'timeLabel']

/**
 * 把目标锚点的变化字段就地写进已有对象。
 * 保持对象引用不变，Vue 2 的 v-for(:key) 才会复用已有 DOM 节点，
 * 而不是每 200ms 流式同步时整列重建。
 */
function copyAnchor(target, source) {
  if (!target || !source) return target
  for (let index = 0; index < ANCHOR_FIELDS.length; index += 1) {
    const field = ANCHOR_FIELDS[index]
    if (target[field] !== source[field]) target[field] = source[field]
  }
  return target
}


export default {
  data: () => ({
    anchorList: [],
    navigatorActiveKey: '',
    navigatorFlashKey: '',
    navigatorJumping: false,
    navigatorHasNew: false,
    navigatorEnabled: readEnabled(),
    navigatorWidth: 0,
    navigatorHeight: 0
  }),
  computed: {
    /**
     * 显隐的唯一真值来源：轨道让位、卡片宽度、按钮位置都由它派生，
     * 避免"按钮已让位但轨道没显示"的空洞。
     */
    hasNavigator() {
      if (!this.navigatorEnabled) return false
      if (this.anchorList.length < NAV_MIN) return false
      if (this.navigatorWidth && this.navigatorWidth < NARROW_WIDTH) return false
      if (this.navigatorHeight && this.navigatorHeight < MIN_HEIGHT) return false
      return true
    },
    navigatorCompact() {
      return this.navigatorWidth > 0 && this.navigatorWidth < 1280
    }
  },
  created() {
    // 非响应式运行时数据，避免 Vue 2 深度劫持 Map / DOM 引用
    this._navVisible = new Map()
    this._navObserved = new WeakSet()
    this._navSyncTimer = null
    this._navJumpTimer = null
    this._navFlashTimer = null
    this._navRafPending = false
    this._navLastKey = ''
  },
  mounted() {
    this.observeNavigatorSize()
    // 首屏已加载的会话需要补一次
    if (this.messages && this.messages.length) this.commitAnchors({ rebuild: true })
  },
  beforeDestroy() {
    this.teardownAnchorObserver()
    this.clearNavigatorTimers()
    if (this._navSizeObserver) {
      this._navSizeObserver.disconnect()
      this._navSizeObserver = null
    }
    if (this._navResizeBound) {
      window.removeEventListener('resize', this.measureNavigatorSize)
      this._navResizeBound = false
    }
  },
  methods: {
    clearNavigatorTimers() {
      if (this._navSyncTimer) clearTimeout(this._navSyncTimer)
      if (this._navJumpTimer) clearTimeout(this._navJumpTimer)
      if (this._navFlashTimer) clearTimeout(this._navFlashTimer)
      this._navSyncTimer = null
      this._navJumpTimer = null
      this._navFlashTimer = null
    },

    // -------------------- 尺寸与显隐 --------------------

    observeNavigatorSize() {
      if (typeof ResizeObserver === 'undefined') {
        this.measureNavigatorSize()
        this._navResizeBound = true
        window.addEventListener('resize', this.measureNavigatorSize)
        return
      }
      const feed = this.$refs.feed
      if (!feed) return
      this._navSizeObserver = new ResizeObserver(() => this.measureNavigatorSize())
      this._navSizeObserver.observe(feed)
      this.measureNavigatorSize()
    },
    measureNavigatorSize() {
      const feed = this.$refs.feed
      if (!feed) return
      const width = feed.clientWidth
      const height = feed.clientHeight
      if (width !== this.navigatorWidth) this.navigatorWidth = width
      if (height !== this.navigatorHeight) this.navigatorHeight = height
    },

    // -------------------- 派生与同步 --------------------

    /**
     * 消息变更的统一入口。
     * 运行中按 SYNC_INTERVAL 节流，空闲时立即提交，避免流式期间逐 token 重算。
     */
    scheduleAnchorSync() {
      if (this._navSyncTimer) return
      if (!this.running) {
        this.commitAnchors()
        return
      }
      this._navSyncTimer = setTimeout(() => {
        this._navSyncTimer = null
        this.commitAnchors()
      }, SYNC_INTERVAL)
    },
    commitAnchors(options) {
      const rebuild = Boolean(options && options.rebuild)
      const prev = rebuild ? [] : this.anchorList
      const { next, tailFrom } = rebuild
        ? { next: buildAnchors(this.messages), tailFrom: 0 }
        : diffAnchors(prev, this.messages)

      if (tailFrom === -1) return

      // 共同前缀保持对象引用不变：流式期间每 200ms 都会走到这里，
      // 若整体替换数组，刻度列每 200ms 全量重建，悬停与滚动都会感到卡顿。
      const added = this.applyAnchorDelta(prev, next, tailFrom, rebuild)

      const lastKey = next.length ? next[next.length - 1].key : ''
      if (lastKey !== this._navLastKey) {
        // 新增轮次：非跟随态下提示"有新内容"，跟随态直接跟到最新
        if (this._navLastKey && !this.followScroll) this.navigatorHasNew = true
        else this.navigatorHasNew = false
        this._navLastKey = lastKey
      }

      if (this.needsActiveReset()) this.navigatorActiveKey = ''

      // 运行中且视口跟随底部：当前轮恒为末节点。
      // 长回答会把当轮用户消息顶出视口，靠 IntersectionObserver 取"顶部最靠上锚点"
      // 会长时间无命中，表现为"运行中没有高亮"。这里直接以末轮为准。
      if (this.running && this.followScroll && lastKey) {
        this.navigatorActiveKey = lastKey
      }

      this.$nextTick(() => this.observeAnchors(added, rebuild || tailFrom === 0))
    },
    /**
     * 把 next 的尾部变更并入 anchorList，尽量复用已有对象。
     * 返回需要新 observe 的锚点（仅新增的那些）。
     */
    applyAnchorDelta(prev, next, tailFrom, rebuild) {
      if (rebuild) {
        // 切会话：整体替换，旧对象不复用
        this.anchorList = next
        return next.slice()
      }

      const list = this.anchorList
      const added = []
      const overlap = Math.min(prev.length, next.length)

      // 1) 尾部变化部分就地覆盖，保留对象引用（避免刻度行重建）
      for (let index = tailFrom; index < overlap; index += 1) {
        copyAnchor(list[index], next[index])
      }

      // 2) 新增轮次：追加 + 记录，用于增量 observe
      for (let index = prev.length; index < next.length; index += 1) {
        const item = next[index]
        list.push(item)
        added.push(item)
      }

      // 3) 轮次减少：截断尾部
      if (next.length < prev.length) list.splice(next.length, prev.length - next.length)

      return added
    },
    /** 目标轮已不在当前会话（切会话/重建）时清空高亮，避免指向不存在的刻度。 */
    needsActiveReset() {
      return Boolean(this.navigatorActiveKey) &&
        !this.anchorList.some((anchor) => anchor.key === this.navigatorActiveKey)
    },

    /** 切会话 / 新建会话时整体复位。 */
    resetAnchorNav() {
      this.clearNavigatorTimers()
      this.anchorList = []
      this.navigatorActiveKey = ''
      this.navigatorFlashKey = ''
      this.navigatorHasNew = false
      this._navLastKey = ''
      if (this._navVisible) this._navVisible.clear()
      this.teardownAnchorObserver()
    },

    // -------------------- 视口观测 --------------------

    teardownAnchorObserver() {
      if (this._navObserver) {
        this._navObserver.disconnect()
        this._navObserver = null
      }
      if (this._navVisible) this._navVisible.clear()
    },
    /**
     * 只观测新增锚点，不做全量 querySelectorAll：
     * 流式期间每 200ms 一次全量查询是长会话的主要掉帧来源。
     */
    observeAnchors(added, rebuild) {
      const feed = this.$refs.feed
      if (!feed) return

      if (typeof IntersectionObserver === 'undefined') {
        this.updateActiveByLayout()
        return
      }

      if (rebuild || !this._navObserver) {
        this.teardownAnchorObserver()
        this._navObserver = new IntersectionObserver(this.onAnchorIntersect, {
          root: feed,
          rootMargin: '-12px 0px -60% 0px',
          threshold: 0
        })
        this._navObserved = new WeakSet()
        this.anchorList.forEach((anchor) => this.observeOne(feed, anchor.key))
        return
      }

      ;(added || []).forEach((anchor) => this.observeOne(feed, anchor.key))
    },
    observeOne(feed, key) {
      if (!this._navObserver) return
      const el = feed.querySelector(`[${ANCHOR_ATTR}="${key}"]`)
      if (!el || this._navObserved.has(el)) return
      this._navObserved.add(el)
      this._navObserver.observe(el)
    },
    onAnchorIntersect(entries) {
      entries.forEach((entry) => {
        const key = entry.target.getAttribute(ANCHOR_ATTR)
        if (!key) return
        if (entry.isIntersecting) this._navVisible.set(key, entry.boundingClientRect.top)
        else this._navVisible.delete(key)
      })
      // 锚点按 DOM 顺序排列，命中区已收缩到视口顶部 40%，取第一个即为最靠上一轮
      const active = this.anchorList.find((anchor) => this._navVisible.has(anchor.key))
      if (active) this.navigatorActiveKey = active.key
      else if (this.running && this.followScroll && this.anchorList.length) {
        // 运行中长回答会把当轮用户消息顶出视口，顶部命中区可能一个锚点都没有。
        // 此时末节点应直接指向最后位置，而不是让整条轨道失去高亮。
        // 必须同时要求 followScroll：用户上滑查看历史时应按真实位置高亮，
        // 否则高亮会一直钉在末轮，与视口里的内容对不上。
        this.navigatorActiveKey = this.anchorList[this.anchorList.length - 1].key
      }
    },
    /** 无 IntersectionObserver 时的兜底：滚动中按 rAF 单次测量。 */
    updateActiveByLayout() {
      if (this._navRafPending) return
      this._navRafPending = true
      const run = () => {
        this._navRafPending = false
        const feed = this.$refs.feed
        if (!feed) return
        const feedTop = feed.getBoundingClientRect().top + JUMP_PADDING
        const rows = feed.querySelectorAll('[' + ANCHOR_ATTR + ']')
        let activeKey = ''
        for (let index = 0; index < rows.length; index += 1) {
          const rect = rows[index].getBoundingClientRect()
          if (rect.bottom > feedTop) {
            activeKey = rows[index].getAttribute(ANCHOR_ATTR) || ''
            break
          }
        }
        if (activeKey) this.navigatorActiveKey = activeKey
      }
      if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run)
      else setTimeout(run, 16)
    },

    // -------------------- 跳转 --------------------

    scrollToAnchor(anchor) {
      if (!anchor || !anchor.key) return
      const feed = this.$refs.feed
      this.navigatorActiveKey = anchor.key
      this.navigatorHasNew = false
      this.navigatorJumping = true
      this.followScroll = false
      this.isUserScrolledUp = true

      if (this._navJumpTimer) clearTimeout(this._navJumpTimer)
      this._navJumpTimer = setTimeout(() => {
        this._navJumpTimer = null
        this.navigatorJumping = false
      }, JUMP_WINDOW)

      if (!feed) return

      const el = feed.querySelector(`[${ANCHOR_ATTR}="${anchor.key}"]`)
      if (!el) {
        // 虚拟滚动等未挂载场景：按轮次比例估算，滚动后再校正一次
        const index = this.anchorList.findIndex((item) => item.key === anchor.key)
        this.scrollToRatio(this.anchorList.length ? (index < 0 ? 0 : index) / this.anchorList.length : 0)
        this.$nextTick(() => {
          const retry = feed.querySelector(`[${ANCHOR_ATTR}="${anchor.key}"]`)
          if (retry) this.alignToElement(feed, retry, 'auto')
        })
        return
      }

      this.alignToElement(feed, el, prefersReducedMotion() ? 'auto' : 'smooth')
      this.flashAnchor(anchor.key)
    },
    alignToElement(feed, el, behavior) {
      const top = feed.scrollTop + (el.getBoundingClientRect().top - feed.getBoundingClientRect().top) - JUMP_PADDING
      const target = Math.max(0, top)
      if (typeof feed.scrollTo === 'function') feed.scrollTo({ top: target, behavior })
      else feed.scrollTop = target
    },
    scrollToRatio(ratio) {
      const feed = this.$refs.feed
      if (!feed) return
      const range = feed.scrollHeight - feed.clientHeight
      feed.scrollTop = Math.max(0, Math.min(1, ratio || 0)) * Math.max(0, range)
    },
    jumpToLatest() {
      this.navigatorHasNew = false
      this.navigatorJumping = true
      this.followScroll = true
      this.isUserScrolledUp = false
      if (this._navJumpTimer) clearTimeout(this._navJumpTimer)
      this._navJumpTimer = setTimeout(() => {
        this._navJumpTimer = null
        this.navigatorJumping = false
      }, JUMP_WINDOW)
      this.scrollToBottom()
      const last = this.anchorList[this.anchorList.length - 1]
      if (last) this.navigatorActiveKey = last.key
    },
    flashAnchor(key) {
      // 跳转落点高亮改为"状态驱动"（页面在行上绑定 :class），
      // 不再直接改 DOM classList：流式期间该行会被 Vue 重渲染，
      // 手工加的 class 会被清掉，表现为"跳过去闪一下就没高亮了"。
      this.navigatorFlashKey = key
      if (this._navFlashTimer) clearTimeout(this._navFlashTimer)
      this._navFlashTimer = setTimeout(() => {
        this._navFlashTimer = null
        this.navigatorFlashKey = ''
      }, FLASH_MS)
    },

    // -------------------- 开关 --------------------

    toggleNavigator() {
      this.navigatorEnabled = !this.navigatorEnabled
      try {
        window.localStorage.setItem(STORAGE_KEY, this.navigatorEnabled ? '1' : '0')
      } catch (_error) {
        // 忽略存储不可用
      }
      if (this.navigatorEnabled) this.commitAnchors({ rebuild: true })
    }
  }
}
