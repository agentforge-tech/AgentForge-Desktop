import { resolveServerInfo } from './client'

export function stopChat(sessionId) {
  return resolveServerInfo().then(({ baseUrl, token }) => {
    const headers = token ? { 'X-AgentForge-Desktop-Token': token } : {}
    return fetch((baseUrl || '') + `/api/chat/stop?sessionId=${encodeURIComponent(sessionId)}`, {
      method: 'POST',
      headers
    })
  })
}
