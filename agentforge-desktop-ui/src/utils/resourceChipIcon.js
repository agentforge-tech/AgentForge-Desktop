/**
 * 资源 Chip 图标片段。
 *
 * 单独立出来是因为需要「原地换图标」：输入框里的 Chip 是 contenteditable 内的
 * 静态 HTML 节点，一旦整体重建 innerHTML，光标就会塌陷到编辑器开头。
 * 因此类型被纠正（如文件夹从 file 变成 folder）时只替换图标元素，
 * 不动周围的文本节点与选区。
 */

const ICONS = {
  image:
    '<rect x="2.25" y="2.25" width="11.5" height="11.5" rx="2"/>' +
    '<circle cx="5.4" cy="5.5" r="1.15"/>' +
    '<path d="m3.6 12 3.05-3.1 1.8 1.75 1.55-1.5 2.4 2.35"/>',
  folder:
    '<path d="M1.9 4.4c0-.8.65-1.45 1.45-1.45h3.1l1.4 1.5h4.35c.8 0 1.45.65 1.45 1.45v5.75c0 .8-.65 1.45-1.45 1.45H3.35c-.8 0-1.45-.65-1.45-1.45z"/>' +
    '<path d="M2 6.3h12"/>',
  file:
    '<path d="M4 1.75h5.2L12.75 5.3V14.25H4z"/>' +
    '<path d="M9 1.9v3.6h3.55M6 8h4.6M6 10.5h4.6"/>'
}

/** 图标内部路径片段（用于模板内联与原地替换复用同一份定义）。 */
export function chipIconPaths(kind) {
  return ICONS[kind] || ICONS.file
}

/** 完整 svg 字符串，用于在已有 DOM 上原地替换图标。 */
export function chipIconMarkup(kind) {
  return `<svg class="resource-chip-icon" viewBox="0 0 16 16" aria-hidden="true">${chipIconPaths(kind)}</svg>`
}

/** 把图标规范化为 CSS 类名可用的 kind。 */
export function normalizeChipKind(type) {
  const value = String(type || '').trim().toLowerCase()
  return ICONS[value] ? value : 'file'
}