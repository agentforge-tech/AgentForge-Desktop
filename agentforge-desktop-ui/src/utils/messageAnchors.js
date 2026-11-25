/**
 * 长会话消息缩略导航 —— 轮次锚点派生。
 *
 * 纯函数模块：只依赖 messages 数据，不读 DOM、不依赖 Vue，便于直接对
 * replaySessionMessages 的输出做单元断言。
 *
 * 约定：一个"轮次"= 一条 user 消息 + 其后到下一轮 user 之前的全部 assistant 消息。
 */

/** 消息行上的锚点属性名，刻度与 DOM 通过它关联。 */
export const ANCHOR_ATTR = 'data-anchor-key'

/** 少于该轮次不渲染轨道：短会话用原生滚动条更直接。 */
export const NAV_MIN = 6

/** 刻度节点硬上限，超出后聚合分组，保证 DOM 规模可控。 */
export const NAV_MAX_TICKS = 60

const TITLE_MAX = 48
const DIGEST_MAX = 96

const STATUS_RANK = { ok: 1, cancelled: 2, running: 3, error: 4, interrupt: 5 }

function collapse(text) {
  return String(text == null ? '' : text).replace(/\s+/g, ' ').trim()
}

function clip(text, max) {
  const value = collapse(text)
  return value.length > max ? value.slice(0, max) + '…' : value
}

function pad2(value) {
  return String(value).padStart(2, '0')
}

/**
 * 从多种时间表示里取出 HH:mm。
 * 兼容 Date、'2026-11-25 14:21:03'、ISO 串与毫秒时间戳。
 */
export function formatClock(raw) {
  if (!raw) return ''
  if (raw instanceof Date) {
    return Number.isNaN(raw.getTime()) ? '' : `${pad2(raw.getHours())}:${pad2(raw.getMinutes())}`
  }
  const text = String(raw)
  const clock = text.match(/(\d{1,2}):(\d{2})/)
  if (clock) return `${pad2(clock[1])}:${clock[2]}`
  const time = Number(text)
  if (Number.isFinite(time) && time > 0) {
    const date = new Date(time)
    return Number.isNaN(date.getTime()) ? '' : `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
  }
  const parsed = new Date(text.replace(' ', 'T'))
  if (Number.isNaN(parsed.getTime())) return ''
  return `${pad2(parsed.getHours())}:${pad2(parsed.getMinutes())}`
}

/** 轮次标题：优先用户提问，其次资源条数，最后兜底文案。 */
export function toTitle(message) {
  const text = clip(message && message.text, TITLE_MAX)
  if (text) return text
  const resources = (message && message.resources) || []
  if (resources.length > 1) return `${resources.length} 个本地资源`
  if (resources.length === 1) return collapse(resources[0].name) || '本地资源'
  return '（空消息）'
}

/** 助手侧可展示的正文：最终回答优先，其次最后一段流式片段。 */
function assistantText(message) {
  if (!message) return ''
  if (message.content) return message.content
  const parts = message.parts || []
  for (let index = parts.length - 1; index >= 0; index -= 1) {
    const part = parts[index]
    if (part.type === 'response_segment' && part.content) return part.content
  }
  return ''
}

function raiseStatus(anchor, status) {
  if ((STATUS_RANK[status] || 0) > (STATUS_RANK[anchor.status] || 0)) anchor.status = status
}

/**
 * 由扁平 messages 派生轮次锚点数组。
 *
 * @param {Array} messages
 * @returns {Array<{key,userIndex,title,digest,resources,toolCount,appendCount,status,timeLabel}>}
 */
export function buildAnchors(messages) {
  const anchors = []
  const list = messages || []
  let current = null

  for (let index = 0; index < list.length; index += 1) {
    const message = list[index]
    if (!message) continue

    if (message.role === 'user') {
      current = {
        key: 'u-' + index,
        userIndex: index,
        title: toTitle(message),
        digest: '',
        resources: (message.resources || []).length,
        toolCount: 0,
        appendCount: 0,
        status: 'ok',
        timeLabel: formatClock(message.createTime)
      }
      anchors.push(current)
      continue
    }

    // 会话开头出现孤立助手消息（历史数据异常）：不产生空标题刻度。
    if (!current) continue

    const parts = message.parts || []
    for (let cursor = 0; cursor < parts.length; cursor += 1) {
      const part = parts[cursor]
      if (part.type === 'tool_card') current.toolCount += 1
      else if (part.type === 'appended_user_message') current.appendCount += 1
      else if (part.type === 'interrupt_card' && part.status === 'PENDING') raiseStatus(current, 'interrupt')
    }

    // 摘要只在首次取到正文时固化，避免流式期间预览文字不断跳动。
    if (!current.digest) current.digest = clip(assistantText(message), DIGEST_MAX)

    const status = message.status
    if (status === 'cancelled') {
      // 终态优先：用户点"停止"后若残留 PENDING 中断卡片，不能被降级成"待确认"，
      // 否则刻度会一直橙色闪烁，与 §11.4「停止后保持 cancelled」冲突。
      current.status = 'cancelled'
    } else if (status === 'waiting') {
      raiseStatus(current, 'interrupt')
    } else if (status === 'error') {
      raiseStatus(current, 'error')
    } else if (status === 'running') {
      raiseStatus(current, 'running')
    }
  }

  anchors.forEach((anchor) => {
    if (anchor.digest) return
    if (anchor.toolCount) anchor.digest = `已运行 ${anchor.toolCount} 个工具`
    else if (anchor.status === 'running') anchor.digest = '正在生成…'
    else if (anchor.status === 'cancelled') anchor.digest = '已停止'
  })

  return anchors
}

function sameAnchor(a, b) {
  return Boolean(a && b) &&
    a.key === b.key &&
    a.title === b.title &&
    a.digest === b.digest &&
    a.status === b.status &&
    a.toolCount === b.toolCount &&
    a.appendCount === b.appendCount &&
    a.resources === b.resources &&
    a.timeLabel === b.timeLabel
}

/**
 * 增量比对：返回 `tailFrom`（自该下标起需要替换），-1 表示无变化。
 *
 * 流式输出期间轮次数不变、只有末尾一轮的摘要与状态在变，因此共同前缀
 * 扫描后通常只需替换最后一项，配合页面侧 splice 即可避免整列重渲染。
 */
export function diffAnchors(prev, messages) {
  const next = buildAnchors(messages)
  const before = prev || []
  if (!before.length) return { next, tailFrom: next.length ? 0 : -1 }

  const max = Math.min(before.length, next.length)
  let common = 0
  while (common < max && sameAnchor(before[common], next[common])) common += 1

  if (common === before.length && common === next.length) return { next, tailFrom: -1 }
  return { next, tailFrom: common }
}

/** 一组锚点里最需要提示用户的状态，用于聚合刻度。 */
export function foldStatus(anchors) {
  let status = 'ok'
  ;(anchors || []).forEach((anchor) => {
    if ((STATUS_RANK[anchor.status] || 0) > (STATUS_RANK[status] || 0)) status = anchor.status
  })
  return status
}
