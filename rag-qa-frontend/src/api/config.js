/**
 * 全局运行时配置 API（Agentic RAG F23）。
 *
 * GET /api/config —— 取 rag.mode 等全局默认值
 */
import http from './client'

const BASE = '/api/config'

/**
 * 获取后端全局配置
 *
 * @returns {Promise<{ragMode: string, defaultHistoryWindow: number}>}
 */
export function getConfig() {
  return http.get(BASE).then(r => r.data)
}
