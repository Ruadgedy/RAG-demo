/**
 * 对话接口
 *
 * - oneShotChat：POST /api/chat（一次性返回）
 * - streamChat ：POST /api/chat/stream（SSE 逐字推送）
 *
 * 流式走 raw fetch + ReadableStream，因为 axios 无法解析 SSE；
 * fetch 路径**必须显式带 Authorization header**（拦截器对它无效）。
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
 * 流式问答（调用方提供 onChunk / onDone / onError 三个回调）
 * 返回一个 AbortController，调用方调用 .abort() 即可中断流。
 */
export function streamChat({ message, knowledgeBaseId, history }, { onChunk, onSessionId, onError, onDone }) {
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

      // SSE 事件形如 "event: session-start\ndata: <sid>\n\n" 或 "data: <chunk>\n\n"
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
          if (!data || data === '[DONE]') continue

          if (evtName === 'session-start') {
            onSessionId && onSessionId(data)
          } else if (evtName === 'error') {
            throw new Error(data)
          } else {
            onChunk && onChunk(data)
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