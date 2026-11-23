import { reduceInterruptAnswers, reduceInterruptEvent } from './interruptEventReducer'
import {
  appendResponseSegment,
  closeOpenResponseSegment,
  finishResponseSegments,
  promoteLastResponseSegment,
  shouldAppendResponseSegment
} from './responseSegmentReducer'

function parseEvents(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch (_error) {
    return []
  }
}

function parseReq(raw) {
  if (!raw) return null
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(raw)
  } catch (_error) {
    return null
  }
}

function replayUserResources(record) {
  const req = parseReq(record.req)
  const files = (req && Array.isArray(req.files) && req.files) || []
  return files
    .filter((item) => item && item.path)
    .map((item) => ({
      path: item.path,
      name: item.originFileName || item.name || '',
      fileType: item.fileType || '',
      size: item.fileSize || item.size || 0,
      type: item.type || ''
    }))
}

function replayUserRawText(record) {
  const req = parseReq(record.req)
  return req && typeof req.rawMessage === 'string' ? req.rawMessage : ''
}

function applyThink(message, event) {
  const last = message.parts[message.parts.length - 1]
  const open = last && last.type === 'think' && last.status === 'thinking' ? last : null
  const text = event.content || ''
  if (!String(text).trim() && !open) return
  if (!event.isFinish) {
    if (open) open.content += text
    else message.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: 0, duration: null })
    return
  }
  if (open) {
    if (String(text).trim()) open.content = text
    open.status = 'end'
    open.duration = null
  } else if (text) {
    message.parts.push({ type: 'think', content: text, status: 'end', startedAt: 0, duration: null })
  }
}

function closeOpenThink(message) {
  const last = message.parts[message.parts.length - 1]
  if (last && last.type === 'think' && last.status === 'thinking') {
    last.status = 'end'
    last.duration = null
  }
}

function applyTool(message, event) {
  const content = event.content || {}
  let part = message.parts.find(
    (item) => item.type === 'tool_card' && item.toolCallId && item.toolCallId === content.toolCallId
  )
  if (!part) {
    part = {
      type: 'tool_card',
      toolCallId: content.toolCallId || '',
      toolName: content.toolName || 'unknown',
      description: '',
      inputParams: null,
      outputResult: null,
      duration: null,
      isFinish: false
    }
    message.parts.push(part)
  }
  part.toolCallId = content.toolCallId || part.toolCallId
  part.toolName = content.toolName || part.toolName
  if (content.description) part.description = content.description
  if (content.inputParams != null) part.inputParams = content.inputParams
  if (content.outputResult != null) part.outputResult = content.outputResult
  const duration = Number(content.duration)
  if (Number.isFinite(duration) && duration > 0) part.duration = duration
  part.isFinish = !!event.isFinish
}

function applyAppendPart(message, event) {
  const content = event.content || {}
  if (Number(content.status) !== 2) return
  if (!content.appendId || message.parts.some((part) => part.type === 'appended_user_message' && part.appendId === content.appendId)) return
  message.parts.push({
    type: 'appended_user_message',
    appendId: content.appendId,
    content: content.content || '',
    effectiveStep: content.effectiveStep
  })
}

export function replayMessage(record) {
  const events = parseEvents(record.events)
  let sawRespEnd = false
  const message = {
    role: 'assistant',
    messageId: record.id,
    lastEventSeq: 0,
    seenEventSeq: new Set(),
    status: 'success',
    parts: [],
    content: '',
    error: '',
    startedAt: record.startedAt || 0,
    finishedAt: record.finishedAt || null,
    elapsedMs: record.elapsedMs || null
  }
  events.forEach((event) => {
    const eventSeq = Number(event && event.eventSeq)
    if (Number.isFinite(eventSeq) && eventSeq > 0) {
      message.seenEventSeq.add(eventSeq)
      message.lastEventSeq = Math.max(message.lastEventSeq, eventSeq)
    }
    switch (event.event) {
      case 'think':
        closeOpenResponseSegment(message, () => 0)
        applyThink(message, event)
        break
      case 'resp':
        if (shouldAppendResponseSegment(message, event)) {
          closeOpenThink(message)
          appendResponseSegment(message, event, () => 0)
        }
        break
      case 'tool_call':
        closeOpenResponseSegment(message, () => 0)
        closeOpenThink(message)
        applyTool(message, event)
        reduceInterruptAnswers(message, event)
        break
      case 'interrupt':
        closeOpenResponseSegment(message, () => 0)
        closeOpenThink(message)
        reduceInterruptEvent(message, event)
        break
      case 'user_message':
        closeOpenResponseSegment(message, () => 0)
        applyAppendPart(message, event)
        break
      case 'compact':
      case 'retry':
        closeOpenResponseSegment(message, () => 0)
        break
      case 'resp_end':
        sawRespEnd = true
        promoteLastResponseSegment(message, () => 0)
        break
      case 'error':
        finishResponseSegments(message, () => 0)
        message.status = 'error'
        message.error =
          typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '生成失败')
        break
      default:
        break
    }
  })
  if (record.status === 3) {
    message.status = 'error'
    if (!message.error) message.error = record.contentText || '生成失败'
  } else if (record.status === 4) {
    message.status = 'cancelled'
    if (!message.error) message.error = '用户已停止'
  } else if (record.status === 6) {
    message.status = 'cancelled'
    if (!message.error) message.error = '停止超时，任务已强制收口'
  } else if (record.status === 7) {
    message.status = 'running'
  } else if (record.status === 8) {
    message.status = 'waiting'
  } else if (record.status === 1 || record.status === 2) {
    message.status = 'running'
  }
  if (message.status === 'success' && !sawRespEnd && !message.content) {
    promoteLastResponseSegment(message, () => 0)
  }
  if (!events.length && !message.content && record.contentText) message.content = record.contentText
  message.parts.forEach((part) => {
    if (part.type === 'think' && part.status === 'thinking') part.status = 'end'
    if (part.type === 'tool_card' && !part.isFinish) part.isFinish = true
    if (part.type === 'response_segment' && part.status === 'streaming') part.status = 'complete'
  })
  return message
}

export function replaySessionMessages(records) {
  const messages = []
  ;(records || []).forEach((record) => {
    if (record.roleType === 1) {
      messages.push({
        role: 'user',
        text: record.contentText || '',
        rawText: replayUserRawText(record),
        resources: replayUserResources(record)
      })
      return
    }
    messages.push(replayMessage(record))
  })
  return messages
}
