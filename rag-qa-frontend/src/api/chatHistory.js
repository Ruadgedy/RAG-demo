/**
 * 聊天历史 API
 */
import http from './client'

const BASE = '/api/chat-history'

export function listHistory() {
  return http.get(BASE).then(r => r.data)
}

export function getSessionMessages(sessionId) {
  return http.get(`${BASE}/${sessionId}`).then(r => r.data)
}