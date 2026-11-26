// 本地工作台工具元信息：展示名称 + 图标标识。
//
// 约定：
// 1. 展示名称仅在缺少 __thought 意图文案时作为降级信息使用，不参与搜索、不写入消息内容。
// 2. iconKey 与 ElegantToolItem.vue 的 SVG 分支一一对应；未映射工具使用 'tool'（扳手）兜底。
// 3. bash 的终端图标与判定关键字保持历史行为，作为已发布视觉基线不做调整。

/** 终端类工具关键字：工具名小写后包含任一关键字即视为命令执行类。 */
export const TERMINAL_KEYWORDS = ['bash', 'shell', 'terminal', 'exec_command', 'command']

/** 兜底图标标识：未命中任何专用映射时使用通用扳手。 */
export const DEFAULT_ICON_KEY = 'tool'

/**
 * 工具元信息表。
 *
 * key 为工具名小写规范形式；iconKey 对应组件内的 SVG 分支。
 */
export const TOOL_META = {
  read: { label: '读取文件', iconKey: 'read' },
  write: { label: '写入文件', iconKey: 'write' },
  edit: { label: '编辑文件', iconKey: 'edit' },
  glob: { label: '查找文件', iconKey: 'glob' },
  grep: { label: '搜索内容', iconKey: 'grep' },
  rg: { label: '快速搜索', iconKey: 'rg' },
  bash: { label: '执行命令', iconKey: 'terminal' },
  skill: { label: '加载技能', iconKey: 'skill' },
  read_resource: { label: '读取资源', iconKey: 'resource' },
  ask_user: { label: '询问确认', iconKey: 'ask' }
}

/**
 * 将原始 toolName 归一为元信息表的 key。
 *
 * 支持带前缀的工具名（如 MCP 的 `mcp__fs__read`）按末段匹配；大小写不敏感。
 *
 * @param {string} toolName 原始工具名
 * @returns {string} 命中的规范 key，未命中返回空串
 */
export function resolveToolKey(toolName) {
  const raw = String(toolName == null ? '' : toolName).trim()
  if (!raw) return ''
  const lower = raw.toLowerCase()
  if (TOOL_META[lower]) return lower
  // 兼容 mcp__server__tool / server.tool 这类带命名空间的工具名
  const segments = lower.split(/__|[./]/).filter(Boolean)
  const last = segments.length ? segments[segments.length - 1] : ''
  return TOOL_META[last] ? last : ''
}

/** 是否为命令执行类工具（兼容未登记到元信息表的自定义终端工具）。 */
export function isTerminalToolName(toolName) {
  const lower = String(toolName == null ? '' : toolName).toLowerCase()
  return TERMINAL_KEYWORDS.some((keyword) => lower.includes(keyword))
}

/**
 * 解析工具图标标识。
 *
 * @param {string} toolName 原始工具名
 * @returns {string} 图标标识，未映射时为 DEFAULT_ICON_KEY
 */
export function toolIconKey(toolName) {
  const key = resolveToolKey(toolName)
  if (key) return TOOL_META[key].iconKey
  return isTerminalToolName(toolName) ? 'terminal' : DEFAULT_ICON_KEY
}

/**
 * 解析工具展示名称：命中映射用业务名称，否则回退原始工具名。
 *
 * @param {string} toolName 原始工具名
 * @returns {string} 用户可读名称
 */
export function toolLabel(toolName) {
  const key = resolveToolKey(toolName)
  if (key) return TOOL_META[key].label
  // 未登记但命终端关键字的自定义工具，同样收敛为用户可读名称，避免暴露 shell 术语
  if (isTerminalToolName(toolName)) return TOOL_META.bash.label
  return String(toolName == null ? '' : toolName).trim()
}