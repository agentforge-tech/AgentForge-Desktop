const RESPONSE_SEGMENT_TYPE = 'response_segment'

function now(clock) {
  return typeof clock === 'function' ? clock() : Date.now()
}

function normalizeText(content) {
  if (content == null) return ''
  return typeof content === 'string' ? content : JSON.stringify(content)
}

function responsePhase(event) {
  return event.responsePhase || event.respPhase || ''
}

function lastOpenSegment(answer) {
  if (!answer || !Array.isArray(answer.parts)) return null
  for (let index = answer.parts.length - 1; index >= 0; index -= 1) {
    const part = answer.parts[index]
    if (part.type === RESPONSE_SEGMENT_TYPE && part.status === 'streaming') return part
  }
  return null
}

export function shouldAppendResponseSegment(answer, event) {
  return Boolean(normalizeText(event && event.content).trim()) || Boolean(lastOpenSegment(answer))
}

export function appendResponseSegment(answer, event, clock) {
  if (!answer) return null
  if (!Array.isArray(answer.parts)) answer.parts = []
  if (!shouldAppendResponseSegment(answer, event)) return null
  const text = normalizeText(event && event.content)
  const roundNo = Number(event && event.roundNo) || null
  const phase = responsePhase(event || {})
  const last = answer.parts[answer.parts.length - 1]
  const compatible = last &&
    last.type === RESPONSE_SEGMENT_TYPE &&
    last.status === 'streaming' &&
    (!roundNo || !last.roundNo || last.roundNo === roundNo) &&
    (!phase || !last.responsePhase || last.responsePhase === phase)

  let part = compatible ? last : null
  if (!part) {
    const startedAt = now(clock)
    const count = answer.parts.filter((candidate) => candidate.type === RESPONSE_SEGMENT_TYPE).length
    part = {
      type: RESPONSE_SEGMENT_TYPE,
      roundNo: roundNo || count + 1,
      content: '',
      status: 'streaming',
      startedAt,
      finishedAt: null,
      duration: null,
      isFinal: false
    }
    if (phase) part.responsePhase = phase
    answer.parts.push(part)
  }
  part.content += text
  if (event && event.isFinish) closeOpenResponseSegment(answer, clock)
  return part
}

export function closeOpenResponseSegment(answer, clock) {
  const part = lastOpenSegment(answer)
  if (!part) return null
  part.status = 'complete'
  part.finishedAt = now(clock)
  part.duration = Math.max(0, part.finishedAt - (part.startedAt || part.finishedAt))
  return part
}

export function promoteLastResponseSegment(answer, clock) {
  if (!answer || !Array.isArray(answer.parts)) return null
  closeOpenResponseSegment(answer, clock)
  for (let index = answer.parts.length - 1; index >= 0; index -= 1) {
    const part = answer.parts[index]
    if (part.type !== RESPONSE_SEGMENT_TYPE) continue
    part.isFinal = true
    answer.content = part.content || ''
    answer.parts.splice(index, 1)
    return part
  }
  return null
}

export function finishResponseSegments(answer, clock) {
  return closeOpenResponseSegment(answer, clock)
}
