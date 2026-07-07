<!--
  ChatView — 编排层
  只做：AppShell + 各组件组合 + store 初始化与 SSE 订阅生命周期
  无业务逻辑、无 API 调用（全部走 store）、无 inline <style>
-->
<template>
  <AppShell>
    <template #sidebar>
      <AppSidebar />
    </template>

    <!-- 主区 -->
    <div class="chat-main">
      <!-- F23：顶部模式切换条（当前对话有效 RAG 模式 / 流式中禁用） -->
      <div v-if="kbStore.currentKb" class="chat-header">
        <RagModeToggle :disabled="chat.isStreaming || !chat.currentConversationId" />
      </div>

      <MessageList ref="msgListRef" :stream-tick="streamTick">
        <template v-if="!kbStore.currentKb">
          <WelcomeEmptyState />
        </template>

        <template v-else-if="!chat.hasMessages">
          <WelcomeScreen @pick="onPickSuggestion" />
        </template>

        <template v-else>
          <MessageBubble
            v-for="(m, i) in chat.messages"
            :key="i"
            :msg="m"
            :streaming="chat.isStreaming && i === chat.messages.length - 1 && m.role === 'assistant' && !m.content"
          />
        </template>
      </MessageList>

      <ComposerInput
        v-if="kbStore.currentKb"
        ref="composerRef"
        @send="onSend"
      />
    </div>
  </AppShell>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'

import AppShell from '@/components/layout/AppShell.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import WelcomeScreen from '@/components/chat/WelcomeScreen.vue'
import WelcomeEmptyState from '@/components/chat/WelcomeEmptyState.vue'
import MessageList from '@/components/chat/MessageList.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ComposerInput from '@/components/chat/ComposerInput.vue'
import RagModeToggle from '@/components/chat/RagModeToggle.vue'

import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useChatStore } from '@/stores/chat'
import { useConfigStore } from '@/stores/config'
import { useDocumentStream } from '@/composables/useDocumentStream'

const kbStore = useKnowledgeBaseStore()
const chat = useChatStore()
const configStore = useConfigStore()

const composerRef = ref(null)
const msgListRef = ref(null)

// 流式追加心跳：每收到一个 chunk +1，触发 MessageList 滚到底
const streamTick = ref(0)

// 文档 SSE 订阅（KB 切换时关闭旧连接、开启新连接）
// 注意：useDocumentStream 内部用 unref()，所以**必须传 ref**（不要传 getter）
const docStream = useDocumentStream(kbStore.currentKb, kbStore.documents)

// ===== 生命周期 =====
onMounted(async () => {
  try {
    // 【F23】全局默认值（rag.mode 等）—— 与 conversations 并行拉，不互相依赖
    await Promise.all([
      kbStore.fetchAll(),
      chat.fetchConversations(),
      configStore.fetchConfig(),
    ])
    if (kbStore.currentKb) {
      await kbStore.fetchDocuments(kbStore.currentKb.id)
    }
    docStream.start()
  } catch (e) {
    console.error('[ChatView] init failed:', e)
  }
})

onUnmounted(() => {
  docStream.stop()
})

// KB 切换时：清空聊天（豆包风格：换 KB 开启新对话）
watch(
  () => kbStore.currentKb?.id,
  (newId, oldId) => {
    if (oldId && newId && newId !== oldId) {
      chat.startNewConversation()
    }
  }
)

// ===== 行为 =====
async function onSend(text) {
  streamTick.value++
  await chat.sendMessage(text)
  streamTick.value++
}

function onPickSuggestion(prompt) {
  composerRef.value?.setText(prompt)
}
</script>

<style scoped>
.chat-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  position: relative;
}

/* F23：顶部模式切换条 —— 与消息列表衔接，左对齐 */
.chat-header {
  display: flex;
  align-items: center;
  padding: 10px 20px 6px;
  flex-shrink: 0;
}
</style>