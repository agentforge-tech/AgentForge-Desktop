import { toEpochMs } from './formatDuration'

/**
 * 任务看板的三栏分类与排序（纯函数，不依赖 Vue，便于单测）。
 *
 * 分类依据是**会话级** `ai_agent_session.status`（SessionStatusEnum），
 * 不能用 `SessionVo.running`：后者只在 status==1 时为 true，
 * 等待人工审批(5) 与取消中(4) 都会被判成 false（见 AgentSessionService#toVo）。
 *
 * 分类原则：按"是否需要我"和"是否在推进"切分，而不是按"结束了没"。
 *   等待审批 = 5           → 阻塞中，等我操作
 *   运行中   = 1           → 推进中，不需要我
 *   已完成   = 0,2,3,4     → 不需要我，且不再推进
 *
 * 取消中(4) 归入已完成：它是瞬时态，ChatTaskServiceImpl#stop 先置 CANCELLING，
 * 任务真正停下后由 SessionLifecycleMiddleware#finishState 收口成 FINISHED(2)。
 *
 * ⚠️ 不要复用 ChatWorkbench 里消息流的 `live = [1,2,7]`：
 * 那是 SessionMessageStatusEnum 的码值（消息 4=取消、5=成功），
 * 与本文件的会话状态码值含义冲突，混用会静默错分类。
 */

export const SESSION_STATUS = {
  IDLE: 0,
  RUNNING: 1,
  FINISHED: 2,
  FAILED: 3,
  CANCELLING: 4,
  WAITING_HITL: 5
}

/** 各栏包含的会话状态码。三栏互斥且完备（0-5 全部有归属）。 */
const RUNNING_CODES = [SESSION_STATUS.RUNNING]
const WAITING_CODES = [SESSION_STATUS.WAITING_HITL]
const DONE_CODES = [
  SESSION_STATUS.IDLE,
  SESSION_STATUS.FINISHED,
  SESSION_STATUS.FAILED,
  SESSION_STATUS.CANCELLING
]

/** 已完成栏内的状态徽标文案（该栏混合四种语义，必须逐条区分）。 */
export const DONE_STATUS_LABEL = {
  [SESSION_STATUS.IDLE]: '未开始',
  [SESSION_STATUS.FINISHED]: '已完成',
  [SESSION_STATUS.FAILED]: '失败',
  [SESSION_STATUS.CANCELLING]: '取消中'
}

/** 会话活动时间（与左栏 formatSessionTime 的取值口径保持一致）。 */
export function activityTime(item) {
  if (!item) return 0
  return toEpochMs(item.lastMessageAt || item.modifyTime || item.createTime)
}

function byActivityDesc(a, b) {
  return activityTime(b) - activityTime(a)
}

/**
 * 把会话列表切成三栏。
 *
 * @param {Array} list 原始会话列表（SessionVo 数组）
 * @returns {{running: Array, waiting: Array, done: Array}}
 */
export function groupSessions(list) {
  const all = Array.isArray(list) ? list : []
  return {
    // 最近有动静的在上
    running: all.filter((item) => RUNNING_CODES.includes(Number(item.status))).sort(byActivityDesc),
    // 等待审批：越久没结论越靠前，避免老任务被新任务顶下去
    waiting: all.filter((item) => WAITING_CODES.includes(Number(item.status))).sort((a, b) => {
      return activityTime(a) - activityTime(b)
    }),
    // 最近完成的在上
    done: all.filter((item) => DONE_CODES.includes(Number(item.status))).sort(byActivityDesc)
  }
}

/** 已完成栏的状态徽标；未收录时回退为空字符串（不显示徽标）。 */
export function doneStatusLabel(item) {
  if (!item) return ''
  return DONE_STATUS_LABEL[Number(item.status)] || ''
}

/**
 * 本轮已进行多少分钟（仅用于等待审批栏的紧急度提示）。
 *
 * 语义说明（不要写成"已等待"）：
 * `lastMessageAt` 的 SQL 是 MAX(COALESCE(finished_at, create_time))。
 * 等待审批时该轮 AI 消息尚未收尾（没有 finished_at），所以取到的是这条消息的
 * create_time，也就是**本轮开始时间**，而不是"从进入审批到现在"。
 * 因此它衡量的是"这轮跑了多久"，若任务跑 10 分钟后才请求确认、刚等 1 分钟，
 * 这里会显示 11 分钟。要精确等待时长需读 ai_agent_interrupt_snapshot.create_time（v2）。
 *
 * @returns {Number|null} 分钟数（向下取整），无法解析时返回 null
 */
export function turnElapsedMinutes(item) {
  const at = activityTime(item)
  if (!at) return null
  return Math.max(0, Math.floor((Date.now() - at) / 60000))
}

/**
 * 相对活动时间文案（与左栏 formatSessionTime 完全一致，供两处共用）。
 */
export function formatRelativeActivity(item) {
  if (!item) return ''
  const raw = item.lastMessageAt || item.modifyTime || item.createTime
  if (!raw) return ''
  const date = new Date(String(raw).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return ''
  const elapsed = Math.max(0, Date.now() - date.getTime())
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (elapsed < minute) return '刚刚'
  if (elapsed < hour) return `${Math.floor(elapsed / minute)}分钟前`
  if (elapsed < day) return `${Math.floor(elapsed / hour)}小时前`
  return `${Math.floor(elapsed / day)}天前`
}

/**
 * 工作空间名：优先用已加载的 workspaces 名称，缺失时退化为路径末段。
 * 只为防御"工作空间已删除但会话仍带 id"的历史数据。
 */
export function workspaceNameOf(item, workspaces) {
  if (!item || item.workspaceId == null) return ''
  const list = Array.isArray(workspaces) ? workspaces : []
  const found = list.find((workspace) => workspace.id === item.workspaceId)
  if (found && found.name) return found.name
  const raw = String(item.workspacePath || '').replace(/[\\/]+$/, '')
  return raw.split(/[\\/]/).pop() || '工作空间'
}
