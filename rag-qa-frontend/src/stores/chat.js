/**
 * 聊天 store
 *
 * - sessions: 会话摘要列表（来自 GET /api/chat-history）
 * - currentSessionId: 当前打开的会话
 * - messages: 当前会话的消息数组 [{role, content, sources?, ...}]
 * - streamMode: 'streaming' | 'oneShot'
 * - isStreaming: 流式问答进行中
 * - abortController: 流式中断句柄
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as chatApi from '@/api/chat'
import * as historyApi from '@/api/chatHistory'
import { useKnowledgeBaseStore } from './knowledgeBase'
import { useToast } from '@/composables/useToast'

const STREAM_DEFAULT = import.meta.env.VITE_CHAT_STREAM !== 'false'

export const useChatStore = defineStore('chat', () => {
  const sessions = ref([])                       // [{sessionId, title, lastTime}]
  const currentSessionId = ref(null)
  const messages = ref([])
  const streamMode = ref(STREAM_DEFAULT ? 'streaming' : 'oneShot')
  const isStreaming = ref(false)
  const abortController = ref(null)

  const toast = useToast()

  const hasMessages = computed(() => messages.value.length > 0)
  const streamingEnabled = computed(() => streamMode.value === 'streaming')

  function setStreamMode(mode) {
    if (isStreaming.value) return
    streamMode.value = mode
  }

  async function fetchSessions() {
    const raw = await historyApi.listHistory()
    // 后端返回扁平 chat_history 列表；按 sessionId 聚合，取首条 content 作为标题
    const map = {}
    raw.forEach(h => {
      if (!map[h.sessionId]) {
        map[h.sessionId] = {
          sessionId: h.sessionId,
          title: (h.content || '').substring(0, 24) + ((h.content || '').length > 24 ? '…' : ''),
          lastTime: h.createdAt,
        }
      }
    })
    sessions.value = Object.values(map).sort(
      (a, b) => new Date(b.lastTime) - new Date(a.lastTime)
    )
    return sessions.value
  }

  async function loadSession(sessionId) {
    currentSessionId.value = sessionId
    const arr = await historyApi.getSessionMessages(sessionId)
    messages.value = arr.map(h => ({ role: h.role, content: h.content }))
  }

  function startNew() {
    currentSessionId.value = null
    messages.value = []
  }

  /**
   * 发送消息（统一入口）
   * @param {string} text 用户输入
   */
  async function sendMessage(text) {
    const kb = useKnowledgeBaseStore().currentKb
    if (!text?.trim() || isStreaming.value || !kb) return

    messages.value.push({ role: 'user', content: text })
    isStreaming.value = true

    try {
      if (streamMode.value === 'streaming') {
        await sendStream(text, kb.id)
      } else {
        await sendOneShot(text, kb.id)
      }
      // 刷新侧边栏会话列表
      await fetchSessions()
    } catch (e) {
      messages.value.push({ role: 'assistant', content: `抱歉，请求失败：${e.message}` })
      toast.error('发送失败：' + e.message)
    } finally {
      isStreaming.value = false
      abortController.value = null
    }
  }

  async function sendOneShot(text, kbId) {
    const data = await chatApi.oneShotChat({
      message: text,
      knowledgeBaseId: kbId,
      history: messages.value.slice(0, -1).map(m => ({ role: m.role, content: m.content })),
    })
    if (data?.sessionId) currentSessionId.value = data.sessionId
    messages.value.push({ role: 'assistant', content: data?.answer ?? '' })
  }

  async function sendStream(text, kbId) {
    // 占位 assistant
    const assistantIndex = messages.value.length
    messages.value.push({ role: 'assistant', content: '' })
    let accumulated = ''

    abortController.value = chatApi.streamChat(
      {
        message: text,
        knowledgeBaseId: kbId,
        history: messages.value.slice(0, -2).map(m => ({ role: m.role, content: m.content })),
      },
      {
        onSessionId: (sid) => { currentSessionId.value = sid },
        onChunk: (chunk) => {
          accumulated += chunk
          messages.value[assistantIndex] = { role: 'assistant', content: accumulated }
        },
        onError: (e) => {
          messages.value[assistantIndex] = {
            role: 'assistant',
            content: accumulated + `\n\n⚠️ 生成中断：${e.message}`,
          }
        },
        onDone: () => {
          // 流结束；isStreaming 已在 sendMessage finally 中清掉
        },
      }
    )
  }

  function stop() {
    if (abortController.value) {
      abortController.value.abort()
    }
  }

  return {
    sessions, currentSessionId, messages, streamMode, isStreaming, abortController,
    hasMessages, streamingEnabled,
    setStreamMode, fetchSessions, loadSession, startNew, sendMessage, stop,
  }
})