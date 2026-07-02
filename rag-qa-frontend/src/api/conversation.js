/**
 * 对话组 API（V6 新增）
 *
 * 【V6 2026-06-30】
 * - 创建/删除对话组
 * - 查询对话组列表
 * - 获取对话组消息
 * - 更新滑动窗口
 */
import http from './client'

const BASE = '/api/conversations'

/**
 * 创建新对话组
 */
export function createConversation(knowledgeBaseId, historyWindow = 3) {
  return http.post(BASE, null, {
    params: { knowledgeBaseId, historyWindow }
  }).then(r => r.data)
}

/**
 * 获取对话组列表
 */
export function getConversations() {
  return http.get(BASE).then(r => r.data)
}

/**
 * 获取对话组详情
 */
export function getConversation(id) {
  return http.get(`${BASE}/${id}`).then(r => r.data)
}

/**
 * 获取对话组消息
 */
export function getConversationMessages(id) {
  return http.get(`${BASE}/${id}/messages`).then(r => r.data)
}

/**
 * 删除对话组
 */
export function deleteConversation(id) {
  return http.delete(`${BASE}/${id}`)
}

/**
 * 更新滑动窗口大小
 */
export function updateHistoryWindow(id, historyWindow) {
  return http.patch(`${BASE}/${id}/window`, { historyWindow }).then(r => r.data)
}

/**
 * 更新对话组标题
 */
export function updateTitle(id, title) {
  return http.patch(`${BASE}/${id}/title`, { title }).then(r => r.data)
}

// ==================== 兼容旧接口 ====================

/**
 * @deprecated V6 已废弃，建议使用 getConversations
 */
export function listHistory() {
  return http.get('/api/chat-history').then(r => r.data)
}

/**
 * @deprecated V6 已废弃，建议使用 getConversationMessages
 */
export function getSessionMessages(sessionId) {
  return http.get(`/api/chat-history/${sessionId}`).then(r => r.data)
}