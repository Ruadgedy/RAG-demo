/**
 * 对话接口
 *
 * - oneShotChat  : POST /api/chat（一次性返回，含 sources 字段）
 * - streamChat   : POST /api/chat/stream（SSE 逐字推送，多事件类型）
 *
 * 流式走 raw fetch + ReadableStream，因为 axios 无法解析 SSE；
 * fetch 路径**必须显式带 Authorization header**（拦截器对它无效）。
 */

/**
 * @typedef {Object} SourceRef
 * @property {string}  documentId   文档 UUID（字符串形式）
 * @property {string}  fileName     原始文件名（如 "产品手册.pdf"）
 * @property {number=} chunkIndex   切片在文档中的索引（0-based）
 * @property {string=} snippet      切片内容摘要（前 200 字符 + 省略号）
 * @property {number=} score        相关性分数（cosine 或 rerank）
 */

/**
 * @typedef {Object} ChatResponse
 * @property {string}      sessionId  会话ID
 * @property {string}      answer     完整回答
 * @property {SourceRef[]=} sources   引用的文档来源列表（可能为空）
 */

const CHAT_BASE = '/api/chat'

/** 一次性问答 */
export async function oneShotChat({ message, knowledgeBaseId, history }) {
  const res = await fetch(`${CHAT_BASE}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${localStorage.getItem('token') || ''}`,
    },
    body: JSON.stringify({ message, knowledgeBaseId, history }),
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
 * 【2026-06-29 增量 P0-01】SSE 协议扩展为多事件类型：
 *
 *   event: session-start     data: <sessionId>          ← 首条，带 sessionId
 *   event: chunk             data: <text fragment>      ← 普通文本片段
 *   event: sources           data: <JSON SourceRef[]>   ← 收尾前，携带来源列表
 *   event: end               data: ""                   ← 结束标记
 *
 * 旧协议（只有纯文本片段）继续兼容：旧的 onChunk 回调照常被调用。
 *
 * @param {object} params        - { message, knowledgeBaseId, history }
 * @param {object} callbacks     - { onSessionId, onChunk, onSources, onDone, onError }
 * @returns {AbortController}    - 调用方 .abort() 中断流
 */
export function streamChat(
  { message, knowledgeBaseId, history },
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
        body: JSON.stringify({ message, knowledgeBaseId, history }),
        signal: controller.signal,
      })

      if (!res.ok || !res.body) {
        throw new Error(`HTTP ${res.status}`)
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      // SSE 事件以 \n\n 分隔，每个事件由多行组成：
      //   event: <name>
      //   data: <payload>
      //   \n   (空行结束)
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // 按 \n\n 拆出完整事件
        const events = buffer.split('\n\n')
        buffer = events.pop() || ''   // 最后一段可能不完整，留到下次

        for (const evt of events) {
          // 解析事件名 + data
          const lines = evt.split('\n').map(l => l.trim()).filter(Boolean)
          let evtName = 'message'
          let dataLines = []
          for (const line of lines) {
            if (line.startsWith('event:')) evtName = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
          }
          const data = dataLines.join('\n')
          if (!data) continue

          // 按事件类型分发
          switch (evtName) {
            case 'session-start':
              onSessionId && onSessionId(data)
              break
            case 'chunk':
              // 普通文本片段 → 旧版 onChunk 继续兼容
              onChunk && onChunk(data)
              break
            case 'sources':
              // 【P0-01 新增】解析来源列表
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
              // 结束标记
              break
            case 'error':
              throw new Error(data)
            default:
              // 未知事件类型忽略
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