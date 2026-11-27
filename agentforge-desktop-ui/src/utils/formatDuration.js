/**
 * 将毫秒耗时格式化为适合会话状态栏的紧凑文案。
 * < 60s: xx秒；< 1h: xx分xx秒；>= 1h: xx小时xx分。
 */
export function formatElapsedDuration(milliseconds) {
  const value = Number(milliseconds)
  if (!Number.isFinite(value) || value <= 0) return ''
  const totalSeconds = Math.max(1, Math.round(value / 1000))
  if (totalSeconds < 60) return `${totalSeconds}秒`
  if (totalSeconds < 3600) {
    return `${Math.floor(totalSeconds / 60)}分${totalSeconds % 60}秒`
  }
  return `${Math.floor(totalSeconds / 3600)}小时${Math.floor((totalSeconds % 3600) / 60)}分`
}

/**
 * 把后端时间字符串（'2026-11-26 22:56:03'）或数值统一转成毫秒时间戳。
 *
 * 后端 SessionMessageVo.startedAt / finishedAt 是字符串，而前端新建消息用的是
 * Date.now()。两者混用时 `Date.now() - '2026-11-26 ...'` 会得到 NaN，
 * 最终 formatElapsedDuration 返回空串 —— 表现为"已完成但没有耗时"。
 */
export function toEpochMs(value) {
  if (value == null) return 0
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  const text = String(value).trim()
  if (!text) return 0
  // 纯数字字符串按时间戳处理
  if (/^\d+$/.test(text)) {
    const numeric = Number(text)
    return Number.isFinite(numeric) ? numeric : 0
  }
  const parsed = new Date(text.replace(' ', 'T'))
  return Number.isNaN(parsed.getTime()) ? 0 : parsed.getTime()
}
