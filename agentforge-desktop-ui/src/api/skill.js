import { post, request } from './client'

export function fetchGlobalSkills() {
  return request('/api/skills')
}

export function fetchSkillRoot() {
  return request('/api/skills/root')
}

export function importGlobalSkill(sourcePath) {
  return post('/api/skills/import', { sourcePath })
}
