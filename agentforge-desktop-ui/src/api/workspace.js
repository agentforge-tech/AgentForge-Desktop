import { post, request } from './client'

export function fetchWorkspaces() {
  return request('/api/workspaces')
}

export function createWorkspace(name, remark) {
  return post('/api/workspaces', { name, remark })
}

export function importWorkspace(folderPath, name) {
  return post('/api/workspaces/import', { folderPath, name })
}

export function renameWorkspace(id, name) {
  return request(`/api/workspaces/${id}/name`, { method: 'PUT', body: JSON.stringify({ name }) })
}

export function deleteWorkspace(id) {
  return request(`/api/workspaces/${id}`, { method: 'DELETE' })
}
