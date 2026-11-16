import { USER_MESSAGE_APPEND_STATUS } from './userMessageAppendStatus'

/** 追加消息事件 reducer：实时 SSE 与历史重放共用。 */
export function reduceAppendEvent(state, answer, event) {
  const content = (event && event.content) || {}
  const appendId = content.appendId
  if (!appendId || !state || !Array.isArray(state.pendingAppends) || !Array.isArray(state.deferredAppends)) {
    return
  }
  if (content.status === USER_MESSAGE_APPEND_STATUS.QUEUED) {
    if (!state.pendingAppends.some((item) => item.appendId === appendId)) {
      state.pendingAppends.push({
        appendId,
        content: content.content || '',
        queuePosition: content.queuePosition,
        queuedAt: content.queuedAt
      })
    }
    return
  }
  state.pendingAppends = state.pendingAppends.filter((item) => item.appendId !== appendId)
  if (content.status === USER_MESSAGE_APPEND_STATUS.EFFECTIVE) {
    if (answer && Array.isArray(answer.parts) &&
        !answer.parts.some((part) => part.type === 'appended_user_message' && part.appendId === appendId)) {
      answer.parts.push({
        type: 'appended_user_message',
        appendId,
        content: content.content || '',
        effectiveStep: content.effectiveStep
      })
    }
  } else if (content.status === USER_MESSAGE_APPEND_STATUS.DEFERRED) {
    if (!state.deferredAppends.some((item) => item.appendId === appendId)) {
      state.deferredAppends.push({
        appendId,
        content: content.content || '',
        queuedAt: content.queuedAt
      })
    }
  }
}

export function createAppendState() {
  return { pendingAppends: [], deferredAppends: [] }
}