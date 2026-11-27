/**
 * contenteditable 光标定位的纯计算逻辑。
 *
 * 背景：输入框里插入资源时，`composerResources` 的变更会先触发 Chip 重绘，
 * 整体替换 `editor.innerHTML`。一旦 innerHTML 被替换，之前保存的 `Range`
 * 对象所属节点就变成游离节点；更糟的是浏览器会把选区塌陷到编辑器开头，
 * 使 `editor.contains(selection.anchorNode)` 仍然为真（anchorNode 就是编辑器本身），
 * 于是"光标有效"的判定失效，资源被插到最前面。
 *
 * 因此选区必须用「序列化文本偏移量」表示：它只依赖字符计数，天然跨 DOM 重建存活。
 * 本文件只做偏移换算，不碰 DOM，便于单独验证。
 */

/** 零宽光标锚点字符：参与 DOM 但不参与序列化文本。 */
export const CARET_ANCHOR = '\u200B'

/**
 * 游标单元类型。
 *
 * - `text`：文本节点，length 为去掉锚点后的字符数
 * - `chip`：内联 Chip，length 为标记原文长度（参与序列化）
 * - `br`：换行元素，length 为 1
 * - `newline`：块级容器尾部隐式换行，length 为 1
 */
export const UNIT = {
  TEXT: 'text',
  CHIP: 'chip',
  BR: 'br',
  NEWLINE: 'newline'
}

/**
 * 把偏移量解析成「第几个单元、落在单元前/内/后」。
 *
 * @param {Array<{kind: string, length: number}>} units 按文档顺序排列的单元
 * @param {number} offset 序列化文本偏移
 * @returns {{type: 'text'|'before'|'after'|'end', index: number, offset: number}}
 */
export function resolveCaretPoint(units, offset) {
  const list = Array.isArray(units) ? units : []
  const target = Number.isFinite(offset) ? Math.max(0, Math.floor(offset)) : 0
  let remaining = target

  for (let index = 0; index < list.length; index += 1) {
    const unit = list[index]
    const length = Math.max(0, Number(unit && unit.length) || 0)

    if (unit && unit.kind === UNIT.TEXT) {
      // 光标可以停在文本单元内部的任意位置（含末尾）
      if (remaining <= length) return { type: 'text', index, offset: remaining }
      remaining -= length
      continue
    }

    // 非文本单元不可拆分：只能停在它之前或之后
    if (remaining <= 0) return { type: 'before', index, offset: 0 }
    remaining -= length
    if (remaining <= 0) return { type: 'after', index, offset: 0 }
  }

  // 超出全部单元：置到末尾
  return { type: 'end', index: list.length, offset: 0 }
}

/**
 * 文本节点内：序列化偏移 → DOM 偏移。
 *
 * 零宽锚点会占据一个 DOM 位置但不计入序列化文本，必须逐个字符映射，
 * 否则 chip 前后的光标会偏移 1。
 *
 * @param {string} raw 文本节点的原始内容
 * @param {number} serializedOffset 序列化偏移
 * @returns {number} 可直接用于 Range 的 DOM 偏移
 */
export function textOffsetToDomOffset(raw, serializedOffset) {
  const text = String(raw || '')
  const target = Math.max(0, Math.floor(Number(serializedOffset) || 0))
  if (target === 0) return 0
  let count = 0
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] === CARET_ANCHOR) continue
    count += 1
    if (count === target) return index + 1
  }
  return text.length
}

/** 去掉锚点后的文本长度，用于构造单元长度。 */
export function serializedTextLength(raw) {
  return String(raw || '').split(CARET_ANCHOR).join('').length
}
