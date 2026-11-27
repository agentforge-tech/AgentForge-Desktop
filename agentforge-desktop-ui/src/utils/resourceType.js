/**
 * 本地资源类型判定与标记工具。
 *
 * 输入框 Chip、用户消息气泡、后端 prompt 三处必须使用同一份类型结果，否则会出现
 * 「输入框是文件图标 + marker=file，发出后后端识别成 folder，重新进入会话又是文件夹图标」。
 *
 * 端上优先采用 Electron `describeResource` 读到的真实元信息（是否目录、扩展名、大小），
 * 本文件只负责兜底推断，映射表与后端 `DesktopResourceService#resolveType`、
 * `electron/main.mjs#describeResource` 保持一致。
 */

/** 后端与端上共用的资源类型取值。 */
export const RESOURCE_TYPES = ['image', 'folder', 'document', 'pdf', 'html', 'file']

const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg']
const DOCUMENT_EXTENSIONS = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx']
const HTML_EXTENSIONS = ['html', 'htm']
// 文本/代码类在 UI 上按 document 展示；后端只把它们当文本附件，类型由端上声明透传
const TEXT_EXTENSIONS = [
  'txt', 'md', 'markdown', 'json', 'yaml', 'yml', 'xml', 'csv', 'log',
  'java', 'js', 'ts', 'tsx', 'jsx', 'vue', 'py', 'go', 'rs', 'sh', 'sql', 'css', 'scss'
]

/** 资源标记：`[[agentforge-resource:<type>:<encodeURIComponent(path)>]]`。 */
export const RESOURCE_MARKER_SOURCE = '\\[\\[agentforge-resource:([^:\\]]+):([^\\]]+)\\]\\]'

/** 每次返回新的全局正则，避免共享 lastIndex 造成漏匹配。 */
export function createResourceMarkerRegExp() {
  return new RegExp(RESOURCE_MARKER_SOURCE, 'g')
}

export function buildResourceMarker(type, path) {
  return `[[agentforge-resource:${type}:${encodeURIComponent(path)}]]`
}

export function decodeResourcePath(value) {
  try {
    return decodeURIComponent(value)
  } catch (_error) {
    // 保留原始编码，避免异常标记导致资源整体不可用
    return String(value || '')
  }
}

/** Skill 标记：`[[agentforge-skill:<encodeURIComponent(name)>:<encodeURIComponent(path)>]]`。 */
export const SKILL_MARKER_SOURCE = '\\[\\[agentforge-skill:([^:\\]]+):([^\\]]+)\\]\\]'

/** 每次返回新的全局正则，避免共享 lastIndex 造成漏匹配。 */
export function createSkillMarkerRegExp() {
  return new RegExp(SKILL_MARKER_SOURCE, 'g')
}

export function buildSkillMarker(name, path) {
  return `[[agentforge-skill:${encodeURIComponent(name)}:${encodeURIComponent(path)}]]`
}

/** 小写扩展名（不含点）；优先用 Electron 给出的 fileType，其次从路径推断。 */
export function resourceFileType(resource) {
  const source = resource || {}
  const declared = String(source.fileType || '').trim().toLowerCase().replace(/^\./, '')
  if (declared) return declared
  const name = String(source.path || source.name || '')
  const base = name.split(/[\\/]/).filter(Boolean).pop() || ''
  const dot = base.lastIndexOf('.')
  return dot > 0 && dot < base.length - 1 ? base.slice(dot + 1).toLowerCase() : ''
}

/**
 * 推断资源类型。
 *
 * 顺序：目录声明 → 扩展名（强证据）→ 声明类型 → file。
 *
 * 要点：
 * - `type === 'folder'` 只可能来自文件系统确证（Electron stat / 拖拽 entry），直接采信；
 * - 扩展名是强证据：`.md` 声明成 file 或未声明，都应得到 document，
 *   否则同一文件在不同来源下会渲染出不同图标；
 * - 声明类型只在扩展名无法判定时兜底（如无扩展名文件）。
 */
export function inferResourceType(resource) {
  const source = resource || {}
  const declared = String(source.type || '').trim().toLowerCase()
  // 目录只能由真实文件系统判定，已确认 folder 时直接采信
  if (declared === 'folder') return 'folder'
  const fileType = resourceFileType(source)
  if (IMAGE_EXTENSIONS.includes(fileType)) return 'image'
  if (fileType === 'pdf') return 'pdf'
  if (HTML_EXTENSIONS.includes(fileType)) return 'html'
  // office 文档与文本/代码类在 UI 上统一按 document 展示
  if (DOCUMENT_EXTENSIONS.includes(fileType) || TEXT_EXTENSIONS.includes(fileType)) return 'document'
  return RESOURCE_TYPES.includes(declared) ? declared : 'file'
}
