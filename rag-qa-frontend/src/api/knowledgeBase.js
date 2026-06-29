/**
 * 知识库 / 文档 相关 API
 */
import http from './client'

const BASE = '/api/knowledge-bases'

export function listKnowledgeBases() {
  return http.get(BASE).then(r => r.data)
}

export function createKnowledgeBase(name) {
  return http.post(BASE, { name }).then(r => r.data)
}

export function listDocuments(kbId) {
  return http.get(`${BASE}/${kbId}/documents`).then(r => Array.isArray(r.data) ? r.data : [])
}

export function uploadDocument(kbId, file) {
  const fd = new FormData()
  fd.append('file', file)
  return http.post(`${BASE}/${kbId}/documents`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120_000,            // 上传大文件可能慢
  }).then(r => r.data)
}