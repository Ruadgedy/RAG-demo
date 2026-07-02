/**
 * 对话接口（V6 重构版）
 *
 * 【V6 2026-06-30】
 * - conversationId：对话组 ID
 * - history：现在由后端根据 conversationId + historyWindow 自动管理
 *
 * - oneShotChat  : POST /api/chat（一次性返回）
 * - streamChat   : POST /api/chat/stream（SSE 逐字推送）
 *
 * 流式走 raw fetch + ReadableStream，因为 axios 无法解析 SSE；
 * fetch 路径必须显式带 Authorization header。
 */

/**
 * @typedef {Object} SourceRef
 * @property {string}  documentId   文档 UUID
 * @property {string}  fileName     原始文件名
 * @property {number=} chunkIndex   切片索引
 * @property {string=} snippet      切片内容摘要
 * @property {number=} score        相关性分数
 */

/**
 * @typedef {Object} ChatResponse
 * @property {string}      conversationId  对话组ID
 * @property {string}      chatId          单次问答ID
 * @property {string}      answer          完整回答
 * @property {SourceRef[]=} sources        引用的文档来源
 */

const CHAT_BASE = '/api/chat'

/**
 * 一次性问答
 *
 * @param {object} params - { conversationId?, message, knowledgeBaseId }
 */
export async function oneShotChat({ conversationId, message, knowledgeBaseId }) {
  const res = await fetch(`${CHAT_BASE}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${localStorage.getItem('token') || ''}`,
    },
    body: JSON.stringify({ conversationId, message, knowledgeBaseId }),
  })
  if (!res.ok) {
    const txt = await res.text().catch(() => '')
    throw new Error(`HTTP ${res.status}: ${txt || res.statusText}`)
  }
  return res.json()
}

/**
 * 流式问答
 *
 * 【V6 2026-06-30】SSE 协议：
 *
 *   event: session-start     data: <conversationId|chatId>  ← 首条
 *   event: chunk             data: <text fragment>           ← 普通文本片段
 *   event: sources           data: <JSON SourceRef[]>        ← 收尾前，携带来源
 *   event: end               data: ""                        ← 结束标记
 *   event: error             data: <error message>            ← 错误
 *
 * @param {object} params    - { conversationId?, message, knowledgeBaseId }
 * @param {object} callbacks - { onSessionId, onChunk, onSources, onDone, onError }
 * @returns {AbortController}
 */
export function streamChat(
  { conversationId, message, knowledgeBaseId },
  { onSessionId, onChunk, onSources, onError, onDone }
) {
  const controller = new AbortController()

  ;(async () => {
    try {
      const res = await fetch(`${CHAT_BASE}/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('token') || ''}`,
        },
        body: JSON.stringify({ conversationId, message, knowledgeBaseId }),
        signal: controller.signal,
      })

      if (!res.ok || !res.body) {
        throw new Error(`HTTP ${res.status}`)
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        const events = buffer.split('\n\n')
        buffer = events.pop() || ''

        for (const evt of events) {
          const lines = evt.split('\n').map(l => l.trim()).filter(Boolean)
          let evtName = 'message'
          let dataLines = []
          for (const line of lines) {
            if (line.startsWith('event:')) evtName = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
          }
          const data = dataLines.join('\n')
          if (!data && evtName !== 'end') continue

          switch (evtName) {
            case 'session-start':
              onSessionId && onSessionId(data)
              break
            case 'chunk':
              onChunk && onChunk(data)
              break
            case 'sources':
              try {
                const sources = JSON.parse(data)
                if (Array.isArray(sources)) {
                  onSources && onSources(sources)
                }
              } catch (e) {
                console.warn('[streamChat] sources 解析失败:', e)
              }
              break
            case 'end':
              break
            case 'error':
              throw new Error(data)
            default:
              break
          }
        }
      }
      onDone && onDone()
    } catch (e) {
      if (e.name === 'AbortError') {
        onDone && onDone()
        return
      }
      onError && onError(e)
      onDone && onDone()
    }
  })()

  return controller
}