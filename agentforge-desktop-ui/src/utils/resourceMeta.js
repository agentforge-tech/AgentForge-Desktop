/**
 * 本地资源元信息补全。
 *
 * 拖拽、粘贴与 OS 文件选择器拿到的数据并不一致：
 * - Electron 原生选择器已返回 `describeResource` 结果（含真实 type/fileType/size）；
 * - 拖拽与粘贴（webUtils.getPathForFile）只拿得到路径，扩展名之外的信息全缺。
 *
 * 如果渲染进程直接按扩展名猜类型，无扩展名的文件夹会被当成普通文件，
 * 输入框于是显示文件图标、标记写成 `:file:`，而后端按真实文件系统识别为 folder，
 * 重新进入会话时按 folder 渲染，两边样式就对不上了。
 *
 * 因此统一走这里：优先用主进程 stat，其次用拖拽提供的 `webkitGetAsEntry` 钩子，
 * 最后才退回扩展名推断。
 */
import { inferResourceType, resourceFileType } from './resourceType'

/**
 * 把路径列表补全为 `{ path, name, fileType, size, type }`，保持入参顺序。
 *
 * @param {string[]} paths 本机绝对路径
 * @param {Map<string, boolean>} [directoryHints] 由渲染进程提供的「是否目录」提示
 *        （拖拽时 `webkitGetAsEntry().isDirectory` 是唯一无需 IPC 的可靠来源）
 */
export async function describeLocalResources(paths, directoryHints) {
  const list = (paths || []).map((item) => String(item || '').trim()).filter(Boolean)
  if (!list.length) return []
  const hints = directoryHints instanceof Map ? directoryHints : null
  const withHint = (item) => (hints && hints.get(item.path) ? { ...item, type: 'folder' } : item)
  const fallback = () => list.map((item) => withHint(normalizeResource({ path: item })))

  const api = typeof window !== 'undefined' ? window.electronAPI : null
  if (!api || typeof api.describeResources !== 'function') {
    // 旧 preload / 打包版未更新时没有该 IPC，用拖拽提示兜底，避免文件夹被判成文件
    return fallback()
  }
  try {
    const described = await api.describeResources(list)
    const byPath = new Map()
    ;(Array.isArray(described) ? described : []).forEach((item) => {
      if (item && item.path) byPath.set(item.path, item)
    })
    return list.map((item) => withHint(normalizeResource(byPath.get(item) || { path: item })))
  } catch (_error) {
    // 主进程不可用时降级，保证资源仍可加入输入框
    return fallback()
  }
}

/** 从 File 对象取本机绝对路径（Electron 沙箱下只能由 webUtils 提供）。 */
export function pathForFile(file) {
  const api = typeof window !== 'undefined' ? window.electronAPI : null
  if (!api || typeof api.getPathForFile !== 'function' || !file) return ''
  try {
    return String(api.getPathForFile(file) || '')
  } catch (_error) {
    return ''
  }
}

export function baseName(target) {
  const value = String(target || '')
  return value.split(/[\\/]/).filter(Boolean).pop() || value
}

/**
 * 归一化单个资源：补齐 name / fileType / type。
 *
 * 已存在的值优先保留（后端与历史快照可能出现字段名差异），缺失时再推断，
 * 从而保证「输入框 → 请求体 → 历史回放」三处拿到同一份类型。
 */
export function normalizeResource(resource) {
  if (!resource || !resource.path) return null
  const path = String(resource.path)
  const name = String(resource.name || resource.originFileName || '').trim() || baseName(path)
  const fileType = String(resource.fileType || '').trim() || resourceFileType({ path, name })
  const size = Number(resource.size != null ? resource.size : resource.fileSize)
  return {
    ...resource,
    path,
    name,
    fileType,
    size: Number.isFinite(size) && size > 0 ? size : 0,
    type: inferResourceType({ ...resource, path, name, fileType })
  }
}

export function normalizeResources(resources) {
  const seen = new Set()
  const result = []
  ;(resources || []).forEach((item) => {
    const resource = normalizeResource(item)
    if (!resource || seen.has(resource.path)) return
    seen.add(resource.path)
    result.push(resource)
  })
  return result
}
