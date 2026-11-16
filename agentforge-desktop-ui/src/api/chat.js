import { post, request } from './client'
import { postSse } from '../utils/sse'

export function stopChat({ messageId, sessionId } = {}) {
  const params = new URLSearchParams()
  if (messageId != null) params.set('messageId', String(messageId))
  if (sessionId) params.set('sessionId', sessionId)
  return request(`/api/chat/stop?${params.toString()}`, { method: 'POST' })
}

/** 提交 HITL 批次审批/回答，并以 SSE 续写同一个 AI 消息。 */
export function resumeChatInterrupt(payload, options) {
  return postSse('/api/chat/interrupts/resume', payload, options)
}

export function appendChatMessage(payload) {
  return post('/api/chat/append', payload)
}

export function cancelChatAppend(payload) {
  return post('/api/chat/append/cancel', payload)
}
