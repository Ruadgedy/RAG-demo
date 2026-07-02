<!--
  ChatHistoryList — 侧边栏对话历史列表（V6 重构版）

  【V6 2026-06-30】
  - 对话组（conversation）替代会话（session）
  - 显示 firstQuery 作为摘要
-->
<template>
  <div class="hist-list">
    <div v-if="chat.conversations.length === 0" class="hist-empty">
      暂无对话记录
    </div>

    <ChatHistoryItem
      v-for="conv in chat.conversations"
      :key="conv.id"
      :conversation="conv"
      @delete="handleDelete"
    />
  </div>
</template>

<script setup>
import ChatHistoryItem from './ChatHistoryItem.vue'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()

async function handleDelete(convId) {
  await chat.deleteConversation(convId)
}
</script>

<style scoped>
.hist-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 0 4px;
}

.hist-empty {
  padding: 20px 12px;
  text-align: center;
  font-size: var(--text-xs);
  color: var(--text-tertiary);
}
</style>