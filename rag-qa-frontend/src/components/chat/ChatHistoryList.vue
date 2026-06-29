<!--
  ChatHistoryList — 侧边栏对话历史列表
-->
<template>
  <div class="hist-list">
    <div v-if="chat.sessions.length === 0" class="hist-empty">
      暂无对话记录
    </div>

    <ChatHistoryItem
      v-for="s in chat.sessions"
      :key="s.sessionId"
      :session="s"
    />
  </div>
</template>

<script setup>
import ChatHistoryItem from './ChatHistoryItem.vue'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()
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