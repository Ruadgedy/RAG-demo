/**
 * 聊天 store（V6 重构版）
 *
 * 【V6 2026-06-30】
 * - 对话组（conversation）替代会话（session）
 * - 多轮对话支持滑动窗口
 *
 * 状态：
 * - conversations: 对话组列表 [{id, title, firstQuery, historyWindow, turnCount}]
 * - currentConversationId: 当前对话组
 * - messages: 当前对话消息 [{role, content, sources?, chatId?}]
 * - streamMode: 'streaming' | 'oneShot'
 * - isStreaming: 流式问答进行中
 * - abortController: 流式中断句柄
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as chatApi from '@/api/chat'
import * as conversationApi from '@/api/conversation'
import { useKnowledgeBaseStore } from './knowledgeBase'
import { useToast } from '@/composables/useToast'

const STREAM_DEFAULT = import.meta.env.VITE_CHAT_STREAM !== 'false'

export const useChatStore = defineStore('chat', () => {
  // ==================== 状态 ====================
  const conversations = ref([])                    // 对话组列表
  const currentConversationId = ref(null)        // 当前对话组 ID
  const messages = ref([])                       // 当前对话消息
  const streamMode = ref(STREAM_DEFAULT ? 'streaming' : 'oneShot')
  const isStreaming = ref(false)
  const abortController = ref(null)

  const toast = useToast()

  const hasMessages = computed(() => messages.value.length > 0)
  const streamingEnabled = computed(() => streamMode.value === 'streaming')

  // ==================== 对话组操作 ====================

  /**
   * 获取对话组列表
   */
  async function fetchConversations() {
    const list = await conversationApi.getConversations()
    conversations.value = list.map(c => ({
      id: c.id,
      title: c.title || c.firstQuery || '新对话',
      firstQuery: c.firstQuery,
      historyWindow: c.historyWindow || 3,
      turnCount: c.turnCount || 0,
      updatedAt: c.updatedAt,
    }))
    return conversations.value
  }

  /**
   * 加载对话组消息
   */
  async function loadConversation(convId) {
    currentConversationId.value = convId
    const msgs = await conversationApi.getConversationMessages(convId)
    messages.value = msgs.map(m => ({
      role: 'user',
      content: m.query,
      sources: m.sources || [],
      chatId: m.chatId,
    }))
    // 添加 AI 回复
    msgs.forEach(m => {
      if (m.content) {
        messages.value.push({
          role: 'assistant',
          content: m.content,
          sources: m.sources || [],
          chatId: m.chatId,
        })
      }
    })
  }

  /**
   * 创建新对话（点击"新对话"按钮）
   */
  async function startNewConversation() {
    const kb = useKnowledgeBaseStore().currentKb
    if (!kb) {
      toast.error('请先选择知识库')
      return
    }

    try {
      const conv = await conversationApi.createConversation(kb.id, 3)
      // 添加到列表头部
      conversations.value.unshift({
        id: conv.id,
        title: '新对话',
        firstQuery: null,
        historyWindow: conv.historyWindow || 3,
        turnCount: 0,
        updatedAt: conv.createdAt,
      })
      // 切换到新对话
      currentConversationId.value = conv.id
      messages.value = []
      return conv
    } catch (e) {
      toast.error('创建对话失败：' + e.message)
    }
  }

  /**
   * 删除对话组
   */
  async function deleteConversation(convId) {
    try {
      await conversationApi.deleteConversation(convId)
      conversations.value = conversations.value.filter(c => c.id !== convId)
      if (currentConversationId.value === convId) {
        currentConversationId.value = null
        messages.value = []
      }
      toast.success('对话已删除')
    } catch (e) {
      toast.error('删除失败：' + e.message)
    }
  }

  /**
   * 更新滑动窗口
   */
  async function updateWindow(convId, windowSize) {
    try {
      const updated = await conversationApi.updateHistoryWindow(convId, windowSize)
      const conv = conversations.value.find(c => c.id === convId)
      if (conv) {
        conv.historyWindow = updated.historyWindow
      }
      return updated
    } catch (e) {
      toast.error('更新失败：' + e.message)
    }
  }

  /**
   * 刷新当前对话组信息（标题等）
   */
  async function refreshCurrentConversation() {
    if (!currentConversationId.value) return
    try {
      const conv = await conversationApi.getConversation(currentConversationId.value)
      const localConv = conversations.value.find(c => c.id === currentConversationId.value)
      if (localConv && conv.title) {
        localConv.title = conv.title
        localConv.firstQuery = conv.firstQuery
        localConv.turnCount = conv.turnCount
      }
    } catch (e) {
      // 静默失败，不影响主流程
    }
  }

  // ==================== 模式切换 ====================

  function setStreamMode(mode) {
    if (isStreaming.value) return
    streamMode.value = mode
  }

  // ==================== 发送消息 ====================

  /**
   * 发送消息（统一入口）
   */
  async function sendMessage(text) {
    const kb = useKnowledgeBaseStore().currentKb
    if (!text?.trim() || isStreaming.value || !kb) return

    // 如果没有当前对话组，先创建
    if (!currentConversationId.value) {
      await startNewConversation()
      if (!currentConversationId.value) return
    }

    messages.value.push({ role: 'user', content: text })
    isStreaming.value = true

    try {
      if (streamMode.value === 'streaming') {
        await sendStream(text, kb.id)
      } else {
        await sendOneShot(text, kb.id)
      }
    } catch (e) {
      // 不再新增第二条 assistant 气泡（避免"回答两次"）：
      // 若末尾已有占位 assistant 气泡（流式占位或 oneShot 未写入），把错误合并进去；
      // 否则才补一条。
      const last = messages.value[messages.value.length - 1]
      const errText = `抱歉，请求失败：${e.message}`
      if (last && last.role === 'assistant') {
        last.content = (last.content ? last.content + '\n\n' : '') + errText
      } else {
        messages.value.push({ role: 'assistant', content: errText })
      }
      toast.error('发送失败：' + e.message)
    } finally {
      isStreaming.value = false
      abortController.value = null
    }

    // 流后刷新对话组信息与列表 —— 独立容错：
    // 失败不连累已成功展示的答案、不抛 axios 30s timeout 进上面的 catch 再造第二条气泡。
    try {
      await refreshCurrentConversation()
    } catch (e) {
      console.warn('[chat] refreshCurrentConversation 失败:', e)
    }
    try {
      await fetchConversations()
    } catch (e) {
      console.warn('[chat] fetchConversations 失败:', e)
    }
  }

  async function sendOneShot(text, kbId) {
    const data = await chatApi.oneShotChat({
      conversationId: currentConversationId.value,
      message: text,
      knowledgeBaseId: kbId,
    })
    // 更新当前对话组 ID（可能是新建的）
    if (data?.conversationId) {
      currentConversationId.value = data.conversationId
    }
    messages.value.push({
      role: 'assistant',
      content: data?.answer ?? '',
      sources: Array.isArray(data?.sources) ? data.sources : [],
      chatId: data?.chatId,
    })
  }

  async function sendStream(text, kbId) {
    // 占位 assistant
    const assistantIndex = messages.value.length
    messages.value.push({ role: 'assistant', content: '', sources: [] })
    let accumulated = ''
    let sourcesBuffer = []

    abortController.value = chatApi.streamChat(
      {
        conversationId: currentConversationId.value,
        message: text,
        knowledgeBaseId: kbId,
      },
      {
        onSessionId: (sid) => {
          // sid 格式: conversationId|chatId
          const [convId, chatId] = sid.split('|')
          if (convId && currentConversationId.value !== convId) {
            currentConversationId.value = convId
          }
          // 记录 chatId 到当前消息
          if (chatId && messages.value[assistantIndex]) {
            messages.value[assistantIndex].chatId = chatId
          }
        },
        onChunk: (chunk) => {
          accumulated += chunk
          messages.value[assistantIndex] = {
            role: 'assistant',
            content: accumulated,
            sources: sourcesBuffer,
          }
        },
        onSources: (sources) => {
          sourcesBuffer = Array.isArray(sources) ? sources : []
          messages.value[assistantIndex] = {
            role: 'assistant',
            content: accumulated,
            sources: sourcesBuffer,
          }
        },
        onError: (e) => {
          messages.value[assistantIndex] = {
            role: 'assistant',
            content: accumulated + `\n\n⚠️ 生成中断：${e.message}`,
            sources: sourcesBuffer,
          }
        },
        onDone: () => {
          // 流结束
        },
      }
    )
  }

  function stop() {
    if (abortController.value) {
      abortController.value.abort()
    }
  }

  // ==================== 导出 ====================

  return {
    // 状态
    conversations, currentConversationId, messages, streamMode, isStreaming, abortController,
    hasMessages, streamingEnabled,
    // 对话组操作
    fetchConversations, loadConversation, startNewConversation,
    deleteConversation, updateWindow, refreshCurrentConversation,
    // 模式切换
    setStreamMode,
    // 发送消息
    sendMessage, stop,
  }
})