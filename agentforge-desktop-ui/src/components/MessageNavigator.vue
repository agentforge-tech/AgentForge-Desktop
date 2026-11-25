<template>
  <div
    ref="root"
    class="message-navigator"
    :class="{ 'is-scrollable': scrollable, 'is-dragging': dragging }"
    role="listbox"
    aria-label="会话轮次定位"
    aria-orientation="vertical"
    tabindex="0"
    @keydown="onKeydown"
    @pointerdown="onPointerDown"
    @pointerenter="onRootEnter"
    @pointermove="onListMove"
    @pointerleave="onListLeave"
    @focus="onFocus"
    @blur="onBlur"
  >
    <div
      ref="list"
      class="message-navigator-list"
      :style="listStyle"
    >
      <div
        v-for="(tick, index) in ticks"
        :key="tick.key"
        class="mn-row"
        :class="[
          'mn-tick--' + (tick.status || 'ok'),
          {
            'is-active': tick.key === activeKey,
            'is-hover': tick.key === hoverKey,
            'is-pointer': tick.key === pointerKey,
            'is-group': tick.groupSize
          }
        ]"
        role="option"
        :aria-selected="tick.key === activeKey ? 'true' : 'false'"
        :aria-label="tickAriaLabel(tick)"
      >
        <span class="mn-tick"></span>
      </div>
    </div>

    <button
      v-if="hasNew"
      type="button"
      class="message-navigator-dot"
      title="有新内容，点击查看最新"
      aria-label="有新内容，点击查看最新"
      @click.stop="emitJumpLatest"
    ></button>

    <!-- 拖动时卡片会随每次指针移动大幅平移，改用轻量标签提示，避免抖动 -->
    <div
      v-if="dragging && dragTick"
      class="message-navigator-scrub-tip"
      :style="scrubTipStyle"
      aria-hidden="true"
    >第 {{ dragRoundLabel }} 轮</div>

    <MessageNavigatorCard
      v-if="hoverTick"
      ref="card"
      class="message-navigator-card-float"
      :anchor="hoverTick"
      :index="hoverIndex"
      :round-start="hoverRoundStart"
      :round-count="hoverRoundCount"
      :total="anchors.length"
      :compact="compact"
      :style="cardStyle"
    />

    <!--
      文档 §8.1 的「轨道 + 卡片左缘透明延伸」：
      卡片本身 pointer-events: none（不拦截正文链接），若没有这条延伸带，
      指针从刻度滑向卡片时会穿过 4px 空隙落到正文上，轨道收到 pointerleave，
      160ms 后卡片消失——表现为"一移向卡片就闪断"。
      延伸带是轨道的子元素，指针经过时不会触发 pointerleave。
    -->
    <div
      v-if="hoverTick && cardHeight"
      class="message-navigator-hover-bridge"
      :style="bridgeStyle"
      aria-hidden="true"
    ></div>
  </div>
</template>

<script>
import MessageNavigatorCard from './MessageNavigatorCard.vue'
import { NAV_MAX_TICKS, foldStatus } from '../utils/messageAnchors'

const ENTER_DELAY = 110
const LEAVE_DELAY = 160
// 刻度视觉（§7.1）：常规 8×2 → hover 12×2 → 当前 16×3，横向伸长而非变粗
const MIN_TICK = 2
const MAX_TICK = 2
const GAP = 12
const GAP_LARGE = 15
// 空间不足时的最小间距，再挤就退化为轨道自身滚动
const MIN_GAP = 4
const DRAG_THRESHOLD = 4
// 窗口被压扁时的刻度上限（§6.6：H < 320 时聚合到 ≤ 20 根）
const NAV_MAX_TICKS_COMPACT = 20

function chunk(list, size) {
  const groups = []
  for (let index = 0; index < list.length; index += size) {
    groups.push(list.slice(index, index + size))
  }
  return groups
}

export default {
  name: 'MessageNavigator',
  components: { MessageNavigatorCard },
  props: {
    anchors: { type: Array, default: () => [] },
    activeKey: { type: String, default: '' },
    hasNew: { type: Boolean, default: false },
    compact: { type: Boolean, default: false }
  },
  data() {
    return {
      hoverKey: '',
      hoverIndex: -1,
      // 指针当前命中的刻度：先给即时视觉反馈，卡片再延迟 110ms 出现
      pointerKey: '',
      availHeight: 0,
      dragging: false,
      dragIndex: -1,
      dragStartY: 0,
      dragStartIndex: -1,
      dragTipTop: 0,
      cardTop: 0,
      cardHeight: 0
    }
  },
  computed: {
    /**
     * 刻度上限：常态 60；窗口被压扁（可用高度 < 320px）时聚合到 ≤ 20 根，
     * 否则 2px 刻度在极矮空间里既点不中也看不清（§6.6）。
     */
    maxTicks() {
      const height = this.availHeight || 0
      if (height && height < 320) return NAV_MAX_TICKS_COMPACT
      return NAV_MAX_TICKS
    },
    /**
     * 聚合策略：节点数硬上限 maxTicks。
     * 每组保留 key/title/status/digest，点击跳到组内首轮。
     */
    ticks() {
      const anchors = this.anchors || []
      const limit = this.maxTicks
      if (anchors.length <= limit) {
        return anchors.map((anchor, index) => ({
          key: anchor.key,
          index,
          // 真实轮次号（1 基）与归一化步长分开：index 是刻度序号，会因聚合而压缩，
          // 卡片与读屏都必须显示真实轮次，否则会出现"第 26 根刻度 / 第 51 轮"的错位。
          roundStart: index + 1,
          roundCount: 1,
          status: anchor.status,
          anchor
        }))
      }
      const size = Math.ceil(anchors.length / limit)
      const groups = chunk(anchors, size)
      let offset = 0
      return groups.map((group) => {
        const first = group[0]
        const tick = {
          key: first.key,
          index: offset,
          roundStart: offset + 1,
          roundCount: group.length,
          groupSize: group.length,
          status: foldStatus(group),
          anchor: first
        }
        offset += group.length
        return tick
      })
    },
    /**
     * 高度分配。
     *
     * 关键约束：整列总高 = count × row ≤ 可用高度，否则刻度会溢出容器上下边界
     * （早先按 `count*unit + (count-1)*gap` 计算，少算了最后一行的间距，
     *  30 轮时总高 420 > 416，导致首末刻度被裁掉）。
     * 因此先由 row = floor(H / count) 决定步长，再在行内分配 刻度高 + 间距。
     */
    metrics() {
      const count = this.ticks.length || 1
      const height = this.availHeight || 0
      if (!height) return { unit: MAX_TICK, gap: GAP, row: MAX_TICK + GAP, scrollable: false }

      // 轮次极少时用更大间距，视觉更接近参考图的"舒展"节奏
      const gapWanted = count <= 9 ? GAP_LARGE : GAP
      const rowMax = Math.floor(height / count)
      // 行高上限 = 刻度高 + 期望间距，避免少轮次时行距被拉得很散
      const row = Math.min(rowMax, MAX_TICK + gapWanted)

      if (row - MIN_TICK < MIN_GAP) {
        // 极矮 + 极多轮次：退化为轨道自身滚动，保证每根刻度仍可点中
        const denseRow = MIN_TICK + MIN_GAP
        return { unit: MIN_TICK, gap: MIN_GAP, row: denseRow, scrollable: true }
      }

      const gap = Math.min(gapWanted, row - MIN_TICK)
      const unit = Math.min(MAX_TICK, row - gap)
      return { unit, gap, row, scrollable: false }
    },
    scrollable() {
      return this.metrics.scrollable
    },
    listStyle() {
      // 行高（含间距）与刻度高通过 CSS 变量下发到整列：
      // - 避免每个刻度行都生成一个内联 style 对象（60 行 × 每次 hover 重建）
      // - 行总高既表示视觉间距，也是 indexFromPoint 使用的命中步长
      return {
        gap: '0px',
        '--mn-row-h': this.metrics.row + 'px',
        '--mn-tick-h': this.metrics.unit + 'px'
      }
    },
    hoverTick() {
      if (!this.hoverKey) return null
      const tick = this.ticks.find((item) => item.key === this.hoverKey)
      return tick ? tick.anchor : null
    },
    hoverRoundStart() {
      const tick = this.ticks[this.hoverIndex]
      return tick ? tick.roundStart : this.hoverIndex + 1
    },
    hoverRoundCount() {
      const tick = this.ticks[this.hoverIndex]
      return tick ? tick.roundCount : 1
    },
    dragTick() {
      return this.dragIndex >= 0 ? this.ticks[this.dragIndex] : null
    },
    /** 拖动提示按真实轮次显示（聚合时给出区间），避免"第 26 根 / 第 51 轮"的错位。 */
    dragRoundLabel() {
      const tick = this.dragTick
      if (!tick) return ''
      const total = this.anchors.length
      if (tick.roundCount > 1) {
        return `${tick.roundStart}–${tick.roundStart + tick.roundCount - 1} / ${total}`
      }
      return `${tick.roundStart} / ${total}`
    },
    scrubTipStyle() {
      return { top: this.dragTipTop + 'px' }
    },
    cardStyle() {
      return { transform: 'translate3d(0, ' + this.cardTop + 'px, 0)' }
    },
    /**
     * 透明延伸带覆盖「刻度列右缘 → 卡片左缘」这段空隙。
     * 卡片 left 为 24px、刻度列宽 20px，空隙 4px，额外向两侧各放 1px 防抖。
     * 高度与卡片一致，便于指针斜向移动时也能留在 hover 区内。
     */
    bridgeStyle() {
      return {
        transform: 'translate3d(0, ' + this.cardTop + 'px, 0)',
        height: this.cardHeight + 'px'
      }
    }
  },
  watch: {
    anchors() {
      if (this.hoverKey && !this.ticks.some((tick) => tick.key === this.hoverKey)) this.clearHover()
    },
    /**
     * 行高由 metrics.row 决定。聚合档位或可用高度变化后必须刷新命中几何，
     * 但此刻 CSS 变量还没应用到 DOM，同步测量会拿到旧行高，所以要等一帧。
     */
    metrics() {
      this.cacheGeometrySoon()
    },
    // 刻度数量变化会改变列表总高与居中偏移，命中几何随之失效
    ticks() {
      this.cacheGeometrySoon()
      if (this.hoverKey && !this.ticks.some((tick) => tick.key === this.hoverKey)) this.clearHover()
    },
    // 轨道可滚动时，当前轮若滚出可视区要自动带回，否则高亮看起来"消失"
    activeKey(key) {
      if (!this.scrollable || !key || this.dragging) return
      const index = this.ticks.findIndex((tick) => tick.key === key)
      if (index >= 0) this.revealTick(index)
    }
  },
  created() {
    // 非响应式运行时数据：指针落点、缓存几何、计时器句柄
    this._pendingIndex = -1
    this._geo = null
    this._measureKey = ''
    this._moveRaf = null
    this._cacheRaf = null
    // 聚焦来源：指针按下会置 false，键盘操作置 true。
    // 用于避免"鼠标点击轨道"时被 focus 逻辑当成键盘导航而弹出卡片。
    this.isKeyboardFocus = false
  },
  mounted() {
    this.observer = null
    if (typeof ResizeObserver !== 'undefined') {
      this.observer = new ResizeObserver(() => this.measure())
      this.observer.observe(this.$refs.root)
    }
    this.measure()
    window.addEventListener('resize', this.measure)
  },
  beforeDestroy() {
    if (this.observer) this.observer.disconnect()
    this.observer = null
    window.removeEventListener('resize', this.measure)
    if (this._cacheRaf) {
      cancelAnimationFrame(this._cacheRaf)
      this._cacheRaf = null
    }
    this.clearTimers()
  },
  methods: {
    clearTimers() {
      if (this.enterTimer) clearTimeout(this.enterTimer)
      if (this.leaveTimer) clearTimeout(this.leaveTimer)
      if (this._moveRaf) cancelAnimationFrame(this._moveRaf)
      this.enterTimer = null
      this.leaveTimer = null
      this._moveRaf = null
    },
    measure() {
      const root = this.$refs.root
      if (!root) return
      const height = root.clientHeight
      if (height && height !== this.availHeight) this.availHeight = height
      this.cacheGeometrySoon()
    },
    /**
     * 几何刷新合并到一帧：尺寸、聚合档位、刻度数量常常连续变化，
     * 逐个同步测量会多次强制布局。
     */
    cacheGeometrySoon() {
      if (this._cacheRaf) return
      this._cacheRaf = requestAnimationFrame(() => {
        this._cacheRaf = null
        this.cacheGeometry()
      })
    },
    /**
     * 缓存命中区几何：pointermove 里若每帧调用 getBoundingClientRect()，
     * 会强制同步布局，长会话下表现为明显的跟手卡顿。
     * 几何只在尺寸/聚合/刻度数变化时刷新一次，逐帧只做纯算术。
     */
    cacheGeometry() {
      const root = this.$refs.root
      const list = this.$refs.list
      if (!root || !list) return
      const rootRect = root.getBoundingClientRect()
      const listRect = list.getBoundingClientRect()
      this._geo = {
        listTop: listRect.top,
        listHeight: listRect.height,
        rootHeight: rootRect.height,
        // 列表在 root 内垂直居中，卡片定位需要相对 root 的偏移
        listOffset: listRect.top - rootRect.top,
        step: this.metrics.row
      }
    },
    /** 刻度中心相对轨道顶部的偏移，拖动标签与卡片定位共用。 */
    centerOf(index) {
      const geo = this._geo
      const step = this.metrics.row
      const offset = geo ? geo.listOffset : 0
      return offset + index * step + step / 2
    },
    tickAriaLabel(tick) {
      const range = tick.roundCount > 1
        ? `第 ${tick.roundStart}–${tick.roundStart + tick.roundCount - 1} 轮`
        : `第 ${tick.roundStart} 轮`
      return `${range}：${tick.anchor.title}`
    },
    /**
     * 整条轨道参与命中：鼠标一进入就映射到最近的刻度。
     * 刻度本身只有 2px 高，单独悬停在它上面几乎不可能，因此不能用逐刻度
     * pointerenter，否则就会出现"必须点击才有反应"的体感。
     */
    onListMove(event) {
      if (this.dragging) return
      // 卡片是轨道的子节点，指针移到卡片上时不能重新映射刻度，
      // 否则一边看预览一边被跳到别处。
      const card = this.$refs.card
      if (card && card.$el && card.$el.contains(event.target)) return
      // 透明延伸带同理：它只负责维持 hover，不参与刻度映射。
      if (event.target && event.target.classList && event.target.classList.contains('message-navigator-hover-bridge')) return

      this._pendingPointerY = event.clientY
      if (this._moveRaf) return
      this._moveRaf = requestAnimationFrame(() => {
        this._moveRaf = null
        this.applyPointerMove(this._pendingPointerY)
      })
    },
    applyPointerMove(clientY) {
      const index = this.indexFromPoint(clientY)
      const tick = index >= 0 ? this.ticks[index] : null
      this._pendingIndex = tick ? index : -1
      // 即时反馈：命中的刻度先亮起来，卡片再延迟出现。
      // 之前只有卡片出现才有视觉变化，110ms 内指针像是"没反应"。
      this.pointerKey = tick ? tick.key : ''

      this.cancelLeave()
      if (!tick) {
        // 指针离开刻度列：收起卡片
        if (this.hoverKey) this.scheduleLeave()
        return
      }
      // 已经停在同一刻度上时不做任何事，避免卡片反复闪
      if (tick.key === this.hoverKey) return
      // 卡片已显示后，切到相邻刻度直接跟随，不再等延迟，减少"跟不上手"
      if (this.hoverKey) {
        this.showCard(tick, index)
        return
      }
      // 未显示时只保证有一个入场计时器在跑：
      // 早先每次换刻度都重启计时，鼠标持续移动时预览永远不出现（看起来像卡顿）
      this.startEnterTimer()
    },
    startEnterTimer() {
      if (this.enterTimer) return
      this.enterTimer = setTimeout(() => {
        this.enterTimer = null
        const index = this._pendingIndex
        if (index < 0) return
        const tick = this.ticks[index]
        if (tick) this.showCard(tick, index)
      }, ENTER_DELAY)
    },
    onListLeave() {
      if (this.enterTimer) {
        clearTimeout(this.enterTimer)
        this.enterTimer = null
      }
      this._pendingIndex = -1
      this.pointerKey = ''
      if (this.dragging) return
      this.scheduleLeave()
    },
    scheduleLeave() {
      if (this.leaveTimer) clearTimeout(this.leaveTimer)
      this.leaveTimer = setTimeout(() => {
        this.leaveTimer = null
        this.hoverKey = ''
        this.hoverIndex = -1
      }, LEAVE_DELAY)
    },
    cancelLeave() {
      if (this.leaveTimer) {
        clearTimeout(this.leaveTimer)
        this.leaveTimer = null
      }
    },
    /** 指针进入轨道子树（含浮动卡片、延伸带）时立即取消收起计时。 */
    onRootEnter() {
      this.cancelLeave()
      if (this.hoverKey) return
      if (this._pendingIndex >= 0) this.startEnterTimer()
    },
    showCard(tick, index) {
      this.hoverKey = tick.key
      this.hoverIndex = index
      this.$nextTick(this.positionCard)
    },
    /**
     * 卡片跟随刻度：超出轨道范围时整体上下平移，不做翻转箭头。
     * 位置按索引纯算术推导，不再 querySelector + getBoundingClientRect，
     * 避免每帧强制布局。
     */
    positionCard() {
      const card = this.$refs.card
      if (!card || this.hoverIndex < 0) return
      if (!this._geo) this.cacheGeometry()

      const center = this.centerOf(this.hoverIndex)

      // 卡片高度只在"切到新锚点"时量一次：连续滑动不每格都触发同步布局
      if (card.$el && this._measureKey !== this.hoverKey) {
        this._measureKey = this.hoverKey
        const measured = card.$el.offsetHeight
        if (measured && measured !== this.cardHeight) this.cardHeight = measured
      }

      const height = this.cardHeight || 0
      const rootHeight = (this._geo && this._geo.rootHeight) || this.availHeight || 0
      const max = Math.max(0, rootHeight - height)
      // 取整：避免 0.1px 级抖动导致卡片文字亚像素模糊
      this.cardTop = Math.round(Math.min(Math.max(0, center - height / 2), max))
    },
    clearHover() {
      this.clearTimers()
      this._pendingIndex = -1
      this.pointerKey = ''
      this.hoverKey = ''
      this.hoverIndex = -1
    },
    onFocus() {
      // 鼠标点击也会让 tabindex 元素获得焦点，但那时指针命中逻辑已经接管，
      // 若在这里再弹一次"当前轮"卡片，点击/拖动一开始就会闪出一张不相干的卡片。
      // 用 :focus-visible 区分来源：只有键盘聚焦（Tab）才会命中该选择器。
      const root = this.$refs.root
      let keyboard = false
      try {
        keyboard = Boolean(root && root.matches && root.matches(':focus-visible'))
      } catch (_error) {
        keyboard = this.isKeyboardFocus === true
      }
      if (!keyboard || this.hoverKey) return
      // 键盘进入时以当前轮为起点，避免先跳回第一轮
      const index = Math.max(0, this.ticks.findIndex((tick) => tick.key === this.activeKey))
      const tick = this.ticks[index]
      if (tick) this.showCard(tick, index)
    },
    onBlur() {
      this.clearHover()
    },
    onKeydown(event) {
      if (!this.ticks.length) return
      const key = event.key
      if (key === 'Escape') {
        this.clearHover()
        this.$refs.root.blur()
        return
      }
      if (key === 'Enter' || key === ' ' || key === 'Spacebar') {
        event.preventDefault()
        const tick = this.ticks[this.hoverIndex] || this.ticks.find((item) => item.key === this.activeKey)
        if (tick) this.commit(tick)
        return
      }
      if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(key)) return
      event.preventDefault()
      const current = this.hoverIndex >= 0 ? this.hoverIndex : Math.max(0, this.ticks.findIndex((t) => t.key === this.activeKey))
      let next = current
      if (key === 'ArrowUp') next = Math.max(0, current - 1)
      else if (key === 'ArrowDown') next = Math.min(this.ticks.length - 1, current + 1)
      else if (key === 'Home') next = 0
      else if (key === 'End') next = this.ticks.length - 1
      this.showCard(this.ticks[next], next)
      if (this.scrollable) this.revealTick(next)
    },
    commit(tick) {
      this.$emit('jump', tick.anchor)
    },
    emitJumpLatest() {
      this.$emit('jump-latest')
    },
    // ---------- scrub ----------
    onPointerDown(event) {
      if (event.button !== undefined && event.button !== 0) return
      // 圆点是独立按钮，不能进入 scrub 流程
      if (event.target && event.target.closest && event.target.closest('.message-navigator-dot')) return
      this.isKeyboardFocus = false
      const root = this.$refs.root
      const index = this.indexFromPoint(event.clientY)
      if (index < 0) return
      this.dragging = true
      this.dragStartY = event.clientY
      this.dragStartIndex = index
      this.dragIndex = index
      this.dragTipTop = Math.round(this.centerOf(index))
      this.clearTimers()
      this.hoverKey = ''
      this.hoverIndex = -1
      if (root && root.setPointerCapture) {
        try { root.setPointerCapture(event.pointerId) } catch (_error) { /* 忽略不支持的指针 */ }
      }
      window.addEventListener('pointermove', this.onPointerMove)
      window.addEventListener('pointerup', this.onPointerUp)
      window.addEventListener('pointercancel', this.onPointerCancel)
    },
    onPointerMove(event) {
      if (!this.dragging) return
      const moved = Math.abs(event.clientY - this.dragStartY)
      if (moved > DRAG_THRESHOLD) event.preventDefault()
      const index = this.indexFromPoint(event.clientY)
      if (index < 0) return
      if (index === this.dragIndex) return
      this.dragIndex = index
      this.dragTipTop = Math.round(this.centerOf(index))
      if (this.scrollable) this.revealTick(index)
    },
    onPointerUp(event) {
      if (!this.dragging) return
      const moved = Math.abs(event.clientY - this.dragStartY)
      const tick = this.ticks[this.dragIndex] || this.ticks[this.dragStartIndex]
      const startIndex = this.dragStartIndex
      this.endDrag()
      if (!tick) return
      this.commit(tick)
      // 单击（未越过拖动阈值）：留在轨道上继续悬浮预览，与"点一下就跳"的预期一致
      if (moved <= DRAG_THRESHOLD) this.showCard(tick, startIndex)
    },
    onPointerCancel() {
      this.endDrag()
      this.clearHover()
    },
    endDrag() {
      this.dragging = false
      this.dragStartY = 0
      this.dragIndex = -1
      window.removeEventListener('pointermove', this.onPointerMove)
      window.removeEventListener('pointerup', this.onPointerUp)
      window.removeEventListener('pointercancel', this.onPointerCancel)
    },
    /**
     * 命中行高 = metrics.row，按行高等分即可，行首即该刻度。
     * 使用缓存几何，逐帧只做算术，避免 pointermove 触发同步布局。
     *
     * 整条轨道都是命中区（§8.1）：刻度列上下留白处同样映射到最近的刻度，
     * 而不是留成"死区"。否则长会话里轨道上下各几十像素点了没反应，
     * 用户会误以为轨道坏了。偏移量夹取到 [0, listHeight]，索引夹取到首/末轮。
     */
    indexFromPoint(clientY) {
      const geo = this._geo
      if (!geo || geo.step <= 0) return -1
      // 仅在可滚动档位才读 scrollTop，避免常规路径上多一次布局读取
      const scrollTop = this.scrollable && this.$refs.list ? this.$refs.list.scrollTop : 0
      const raw = clientY - geo.listTop + scrollTop
      const offset = Math.max(0, Math.min(geo.listHeight - 0.001, raw))
      const index = Math.floor(offset / geo.step)
      return Math.max(0, Math.min(this.ticks.length - 1, index))
    },
    /** 超长会话轨道可滚动：保证当前操作刻度始终可见。 */
    revealTick(index) {
      const list = this.$refs.list
      if (!list) return
      const step = this.metrics.row
      const top = index * step
      const bottom = top + step
      if (top < list.scrollTop) list.scrollTop = top
      else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight
    }
  }
}
</script>

<style scoped>
.message-navigator {
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 6px;
  z-index: 8;
  display: flex;
  width: 20px;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  outline: none;
  user-select: none;
  touch-action: none;
}

.message-navigator:focus-visible {
  outline: 2px solid rgba(59, 111, 182, 0.35);
  outline-offset: 2px;
  border-radius: 6px;
}

.message-navigator-list {
  display: flex;
  width: 100%;
  max-height: 100%;
  flex-direction: column;
  align-items: flex-start;
  box-sizing: border-box;
  /* 与正文重绘隔离：长会话滚动时不因轨道内部变化牵连外部布局 */
  contain: layout style;
}

.message-navigator.is-scrollable .message-navigator-list {
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
}

.message-navigator.is-scrollable .message-navigator-list::-webkit-scrollbar {
  width: 0;
  height: 0;
}

.mn-row {
  position: relative;
  display: flex;
  width: 100%;
  /* 行总高 = 刻度高 + 间距，既是命中区也是 indexFromPoint 的步长 */
  height: var(--mn-row-h, 14px);
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-start;
}

.mn-row .mn-tick {
  display: block;
  width: 16px;
  height: var(--mn-tick-h, 2px);
  flex: 0 0 auto;
  border-radius: 1px;
  /* 默认刻度在浅色底上略加深（原 #d8dee9 在缩略轨尺寸下几乎不可见） */
  background: #cbd3e0;
  /* 常规 8px：不靠改宽度，避免列宽抖动 */
  transform: scaleX(0.5);
  transform-origin: left center;
  transition: transform 0.14s ease, background-color 0.14s ease;
}

/* 聚合刻度略高，体现"这一组包含更多轮" */
.mn-row.is-group .mn-tick {
  height: calc(var(--mn-tick-h, 2px) + 1px);
}

/* hover 只横向伸长、不变高，避免整列随指针上下跳动 */
.mn-row.is-hover .mn-tick {
  background: #9aa7ba;
  transform: scaleX(0.75);
}

/*
 * 指针命中态：卡片有 110ms 延迟，但刻度必须立刻有反应，
 * 否则快速划过时整条轨道毫无反馈，像是没响应的死区。
 * 用比"已展开卡片"更轻的层级，保证卡片出现后的状态更明确。
 */
.mn-row.is-pointer:not(.is-hover) .mn-tick {
  background: #b4bfd0;
  transform: scaleX(0.62);
}

/* 当前轮：最长（16px）、略粗、品牌蓝，与参考图"当前项向右延伸"一致 */
.mn-row.is-active .mn-tick {
  height: calc(var(--mn-tick-h, 2px) + 1px);
  background: #3b6fb6;
  transform: scaleX(1);
}

.mn-row.mn-tick--error .mn-tick {
  background: #e6a1a1;
}

.mn-row.mn-tick--error.is-active .mn-tick,
.mn-row.mn-tick--error.is-hover .mn-tick {
  background: #d64545;
}

.mn-row.mn-tick--interrupt .mn-tick {
  background: #eabd76;
}

.mn-row.mn-tick--interrupt.is-active .mn-tick,
.mn-row.mn-tick--interrupt.is-hover .mn-tick {
  background: #c47d00;
}

.mn-row.mn-tick--cancelled .mn-tick {
  background: #d6dbe3;
}

.mn-row.mn-tick--running .mn-tick {
  animation: mn-pulse 1.2s ease-in-out infinite;
}

/*
 * 状态点（§7.2）：只在需要用户注意的状态出现，统一贴在刻度右侧使状态列对齐。
 * 用伪元素而非额外 DOM，60 根刻度也不增加节点数。
 */
.mn-row.mn-tick--error::after,
.mn-row.mn-tick--interrupt::after {
  content: '';
  position: absolute;
  top: 50%;
  left: 15px;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  transform: translateY(-50%);
}

.mn-row.mn-tick--error::after {
  background: #d64545;
}

.mn-row.mn-tick--interrupt::after {
  background: #d98800;
}

@keyframes mn-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

.message-navigator-dot {
  position: absolute;
  bottom: -9px;
  left: 5px;
  width: 6px;
  height: 6px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #3b6fb6;
  cursor: pointer;
}

/* 拖动中显示的轻量标签，替代会大幅抖动的跟随卡片 */
.message-navigator-scrub-tip {
  position: absolute;
  left: 26px;
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(23, 43, 77, 0.82);
  color: #fff;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
  pointer-events: none;
  transform: translateY(-50%);
}

.message-navigator-card-float {
  position: absolute;
  top: 0;
  left: 24px;
  /*
   * 卡片默认是 pointer-events: none（不拦截正文链接），但那样鼠标移向卡片时
   * 会落到正文上、触发轨道 pointerleave，卡片随即消失——用户看到的就是
   * "一移到卡片上就没了"。浮层只在 hover 轨道时存在，此时用户意图就是看卡片，
   * 因此这里改为可交互，保证"悬浮预览"能停留查看。
   */
  pointer-events: auto;
  will-change: transform;
  /* 入场只做淡入：位移必须瞬时，补间会让"首次出现从顶部滑下 + 跟手迟滞" */
  animation: mn-card-in 0.12s ease-out;
}

@keyframes mn-card-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

/*
 * 透明延伸带：覆盖「刻度列右缘(20px) → 卡片左缘(24px)」的空隙，
 * 并向上/下各扩展 1px，保证指针斜向滑向卡片时不落到正文、不触发 pointerleave。
 * 本身不可见、不接收点击以外的语义，仅用于维持 hover 区连续。
 */
.message-navigator-hover-bridge {
  position: absolute;
  top: 0;
  left: 19px;
  width: 6px;
  pointer-events: auto;
}

@media (prefers-reduced-motion: reduce) {
  .mn-row .mn-tick,
  .message-navigator-card-float {
    transition: none;
  }

  .mn-row.mn-tick--running .mn-tick {
    animation: none;
  }
}

@media (prefers-color-scheme: dark) {
  .mn-row .mn-tick {
    background: #39404d;
  }

  .mn-row.is-hover .mn-tick {
    background: #566173;
  }

  .mn-row.is-active .mn-tick {
    background: #7aa6e1;
  }
}
</style>
