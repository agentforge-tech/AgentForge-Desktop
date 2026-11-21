import { post, request } from './client'
import { getSse } from '../utils/sse'

export function submitChatTask(payload) {
  return post('/api/chat/tasks/submit', payload)
}

export function streamChatTask(messageId, afterSeq = 0, options) {
  const params = new URLSearchParams({
    messageId: String(messageId),
    afterSeq: String(Math.max(0, Number(afterSeq) || 0))
  })
  return getSse(`/api/chat/tasks/stream?${params.toString()}`, options)
}

export function stopChat({ messageId, sessionId } = {}) {
  const params = new URLSearchParams()
  if (messageId != null) params.set('messageId', String(messageId))
  if (sessionId) params.set('sessionId', sessionId)
  return request(`/api/chat/tasks/stop?${params.toString()}`, { method: 'POST' })
}

/** 提交 HITL 批次审批/回答，并返回同一个 AI 消息的续流任务。 */
export function resumeChatInterrupt(payload) {
  return post('/api/chat/tasks/interrupts/resume', payload)
}

export function appendChatMessage(payload) {
  return post('/api/chat/append', payload)
}

export function cancelChatAppend(payload) {
  return post('/api/chat/append/cancel', payload)
}
