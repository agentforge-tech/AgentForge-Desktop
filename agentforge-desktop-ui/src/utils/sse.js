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

  await readSseBody(response.body, onEvent)
}

export async function getSse(path, { signal, onEvent }) {
  const { baseUrl } = await resolveServerInfo()
  const headers = await authHeaders({ Accept: 'text/event-stream' })
  const response = await fetch((baseUrl || '') + path, {
    method: 'GET',
    headers,
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
  await readSseBody(response.body, onEvent)
}

async function readSseBody(stream, onEvent) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() || ''
    frames.forEach((frame) => {
      const lines = frame.split(/\r?\n/)
      const data = lines
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n')
      const idLine = lines.find((line) => line.startsWith('id:'))
      if (data) onEvent(JSON.parse(data), idLine ? idLine.slice(3).trim() : null)
    })
    if (done) break
  }
}
