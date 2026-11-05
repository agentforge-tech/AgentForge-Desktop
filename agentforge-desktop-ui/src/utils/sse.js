import { resolveServerInfo, authHeaders } from '../api/client'

export async function postSse(path, body, { signal, onEvent }) {
  const { baseUrl } = await resolveServerInfo()
  const headers = await authHeaders({ Accept: 'text/event-stream' })
  const response = await fetch((baseUrl || '') + path, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal
  })
  const contentType = response.headers.get('content-type') || ''
  if (!response.ok || !contentType.includes('text/event-stream')) {
    const text = await response.text().catch(() => '')
    let message = text
    try {
      const data = JSON.parse(text)
      message = data.msg || data.message || text
    } catch (_error) {
      // keep raw text
    }
    throw new Error(message || `HTTP ${response.status}`)
  }
  if (!response.body) throw new Error('浏览器不支持流式响应')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() || ''
    frames.forEach((frame) => {
      const data = frame
        .split(/\r?\n/)
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n')
      if (data) onEvent(JSON.parse(data))
    })
    if (done) break
  }
}
