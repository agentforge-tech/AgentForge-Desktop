/**
 * 保证「测试中」这类短反馈至少展示一段时间，再收起加载态。
 *
 * 本地/内网模型连通性测试常常几十毫秒就返回，如果直接收尾，
 * 旋转图标会一闪而过，用户几乎感知不到自己点过按钮。这里按
 * 调用方记录的开始时间补足剩余等待时长。
 *
 * @param {number} startedAt 开始时间戳（Date.now()）
 * @param {number} minDuration 最小可见时长，默认 600ms
 */
export async function keepFeedbackVisible(startedAt, minDuration = 600) {
  const remain = minDuration - (Date.now() - Number(startedAt || 0))
  if (remain > 0) {
    await new Promise((resolve) => setTimeout(resolve, remain))
  }
}
