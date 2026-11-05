import { request, post } from './client'

const BASE = '/api/model-config'

export function fetchModels() {
  return request(BASE + '/list')
}

export function fetchModelOptions() {
  return request(BASE + '/options')
}

export function upsertModel(payload) {
  return post(BASE + '/upsert', payload)
}

export function deleteModel(id) {
  return request(BASE + '/' + id, { method: 'DELETE' })
}

export function testConnectivity(payload) {
  return post(BASE + '/testConn', payload)
}

export function testFunctionCall(payload) {
  return post(BASE + '/testFunctionCall', payload)
}
