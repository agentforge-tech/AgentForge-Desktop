import { post, request } from './client'

export function fetchGlobalSkills() {
  return request('/api/skills')
}

/**
 * 获取 Skill 列表。
 *
 * 传入 workspacePath 时返回「全局 + 该工作空间」的生效列表（同名工作空间覆盖全局），
 * 不传则等同于 fetchGlobalSkills。用于输入框 `/` 面板。
 */
export function fetchSkills({ workspacePath } = {}) {
  const path = workspacePath ? `?workspacePath=${encodeURIComponent(workspacePath)}` : ''
  return request(`/api/skills${path}`)
}

export function fetchSkillRoot() {
  return request('/api/skills/root')
}

export function importGlobalSkill(sourcePath) {
  return post('/api/skills/import', { sourcePath })
}
