import { unref } from 'vue'
import axios from 'axios'

/**
 * 文档状态实时订阅 composable
 *
 * 架构：
 *   - Primary: EventSource 订阅 SSE 流（<100ms 延迟）
 *   - Fallback: SSE 失败时降级到 2s 轮询
 *   - Reconnect: 指数退避重连（1s → 2s → 4s → ... → MAX）
 *
 * 用法：
 *   const { start, stop } = useDocumentStream(currentKb, documents)
 *   onMounted(() => start())
 *   onUnmounted(() => stop())
 *
 * 设计要点：
 *   1. 合并而非替换：SSE 事件合并到现有 documents.value，保留乐观插入的临时 doc
 *   2. 自动重连：网络抖动时 EventSource 会断开，触发 reconnect
 *   3. 降级轮询：SSE 重连多次失败后启用轮询（避免 UI 完全卡死）
 *   4. 资源清理：stop() 关闭 EventSource 和 setInterval，防止内存泄漏
 *
 * 【2026-06-27 增量】替代 ChatView.vue 的 setInterval 轮询方案。
 */

const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 15000, 30000]  // 指数退避上限 30s
const FALLBACK_POLL_INTERVAL = 3000   // SSE 失败时降级轮询间隔
const MAX_RECONNECTS_BEFORE_FALLBACK = 6  // 6 次重连失败后启用轮询

export function useDocumentStream(kbIdRef, documentsRef, options = {}) {
  const {
    endpoint = '/api/knowledge-bases',
  } = options

  let es = null
  let fallbackTimer = null
  let reconnectAttempt = 0
  let stopped = false
  let fallbackActive = false

  /**
   * 把事件合并到 documents 列表（按 id 覆盖或追加）。
   */
  const mergeEvent = (event) => {
    if (!event || !event.documentId) return
    const idx = documentsRef.value.findIndex(d => d.id === event.documentId)
    if (idx >= 0) {
      // 用 reactive 替换触发 Vue 响应式更新
      documentsRef.value[idx] = {
        ...documentsRef.value[idx],
        status: event.status,
        progress: event.progress,
        errorMessage: event.errorMessage,
      }
    } else {
      // 文档不在列表中（可能在另一个 KB），忽略
      // 或追加（前端刚切换 KB 时的边界场景）
      documentsRef.value.push({
        id: event.documentId,
        knowledgeBaseId: event.knowledgeBaseId,
        status: event.status,
        progress: event.progress,
        errorMessage: event.errorMessage,
        // 其他字段（fileName/fileType/chunkCount 等）后续由 REST 拉取填充
      })
    }
  }

  /**
   * 启动 EventSource 订阅。
   */
  const startSSE = () => {
    const kbId = unref(kbIdRef)
    if (!kbId || stopped) return

    const token = localStorage.getItem('token') || ''
    // EventSource 不支持 header，token 通过 query 传
    const url = `${endpoint}/${kbId}/documents/stream?token=${encodeURIComponent(token)}`

    try {
      es = new EventSource(url)
    } catch (e) {
      console.error('Failed to create EventSource:', e)
      scheduleReconnect()
      return
    }

    es.addEventListener('doc-status', (e) => {
      reconnectAttempt = 0  // 重置退避计数
      try {
        const event = JSON.parse(e.data)
        mergeEvent(event)
      } catch (parseErr) {
        console.warn('Failed to parse SSE event:', parseErr)
      }
    })

    es.onerror = () => {
      // EventSource 关闭连接（网络断开/服务器重启/认证失败）
      if (es) es.close()
      es = null
      if (!stopped) scheduleReconnect()
    }

    es.onopen = () => {
      reconnectAttempt = 0
      // SSE 成功后停掉 fallback 轮询
      if (fallbackTimer) {
        clearInterval(fallbackTimer)
        fallbackTimer = null
      }
      fallbackActive = false
    }
  }

  /**
   * 安排重连（指数退避）。
   */
  const scheduleReconnect = () => {
    if (stopped) return
    if (reconnectAttempt >= MAX_RECONNECTS_BEFORE_FALLBACK) {
      // 多次重连失败 → 启用降级轮询
      startFallback()
      return
    }

    const delay = RECONNECT_DELAYS[
      Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1)
    ]
    reconnectAttempt++

    setTimeout(() => {
      if (!stopped) startSSE()
    }, delay)
  }

  /**
   * 降级轮询（SSE 失败时启用）。
   */
  const startFallback = async () => {
    if (fallbackActive || stopped) return
    fallbackActive = true
    console.warn('[useDocumentStream] SSE failed, falling back to polling')

    const poll = async () => {
      const kbId = unref(kbIdRef)
      if (!kbId || stopped) return
      try {
        const res = await axios.get(`${endpoint}/${kbId}/documents`, {
          timeout: 5000,
        })
        // 整体替换（轮询是完整数据源）
        documentsRef.value = Array.isArray(res.data) ? res.data : []
      } catch (e) {
        console.warn('[useDocumentStream] fallback poll failed:', e.message)
      }
    }

    await poll()  // 立即跑一次
    fallbackTimer = setInterval(poll, FALLBACK_POLL_INTERVAL)
  }

  /**
   * 启动订阅（立即生效）。
   */
  const start = () => {
    stopped = false
    reconnectAttempt = 0
    fallbackActive = false
    startSSE()
    // 同步启用 fallback（避免 SSE 启动延迟期间 UI 无数据）
    startFallback()
  }

  /**
   * 停止订阅并清理资源（必须在 onUnmounted 调用）。
   */
  const stop = () => {
    stopped = true
    if (es) {
      es.close()
      es = null
    }
    if (fallbackTimer) {
      clearInterval(fallbackTimer)
      fallbackTimer = null
    }
  }

  return { start, stop }
}