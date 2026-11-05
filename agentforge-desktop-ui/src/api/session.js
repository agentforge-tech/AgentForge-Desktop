import { post } from './client'

const SESSION_BASE = '/api/agent/sessions'
const MESSAGE_BASE = '/api/agent/session-messages'

export function fetchSessionList(aid, limit) {
  return post(SESSION_BASE + '/sessionList', { aid, limit })
}

export function fetchTopSessionList(aid) {
  return post(SESSION_BASE + '/topList', { aid })
}

export function setSessionTop(sessionId, isTop) {
  return post(SESSION_BASE + '/setTop', { sessionId, isTop })
}

export function changeSessionTitle(sessionId, title) {
  return post(SESSION_BASE + '/changeTitle', { sessionId, title })
}

export function deleteSession(sessionId) {
  return post(SESSION_BASE + '/delete', { sessionId })
}

export function fetchSessionMessages(sessionId) {
  return post(MESSAGE_BASE + '/detail', { sessionId })
}

export function resolveSessionTitle(text) {
  const normalized = String(text || '').trim().replace(/\s+/g, ' ')
  if (!normalized) return '新会话'
  const chars = Array.from(normalized)
  return chars.length <= 20 ? normalized : chars.slice(0, 20).join('')
}
