import { request, post } from './client'

const BASE = '/api/agent-config'

export function fetchAgents() {
  return request(BASE + '/list')
}

export function getAgent(aid) {
  return request(BASE + '/' + encodeURIComponent(aid))
}

export function fetchBuiltInSystemPrompt() {
  return request(BASE + '/built-in-system-prompt')
}

export function upsertAgent(payload) {
  return post(BASE + '/upsert', payload)
}

export function deleteAgent(aid) {
  return request(BASE + '/' + encodeURIComponent(aid), { method: 'DELETE' })
}
