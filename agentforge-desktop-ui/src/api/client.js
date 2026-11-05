let infoPromise = null

export async function resolveServerInfo() {
  if (window.electronAPI && window.electronAPI.getLocalServerInfo) {
    if (!infoPromise) infoPromise = window.electronAPI.getLocalServerInfo()
    return infoPromise
  }
  return { baseUrl: '', token: '', runtime: 'browser' }
}

export async function authHeaders(extra = {}) {
  const { token } = await resolveServerInfo()
  const headers = { 'Content-Type': 'application/json', ...extra }
  if (token) headers['X-AgentForge-Desktop-Token'] = token
  return headers
}

export async function request(path, options = {}) {
  const { baseUrl } = await resolveServerInfo()
  const headers = await authHeaders(options.headers || {})
  const response = await fetch((baseUrl || '') + path, { ...options, headers })
  const text = await response.text()
  let body = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch (_error) {
      body = text
    }
  }
  if (!response.ok) {
    throw new Error((body && (body.msg || body.message)) || `HTTP ${response.status}`)
  }
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code !== 0) throw new Error(body.msg || '请求失败')
    return body.data
  }
  return body
}

export function post(path, payload) {
  return request(path, { method: 'POST', body: JSON.stringify(payload || {}) })
}
