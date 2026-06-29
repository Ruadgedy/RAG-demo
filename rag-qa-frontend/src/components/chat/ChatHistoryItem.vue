<!--
  ChatHistoryItem — 单条历史会话
-->
<template>
  <button
    class="hist-item"
    :class="{ active: isActive }"
    type="button"
    @click="onClick"
  >
    <MessageCircle :size="14" :stroke-width="2.2" class="hist-icon" />
    <div class="hist-text">
      <div class="hist-title">{{ session.title || '（无标题）' }}</div>
      <div class="hist-time">{{ formatTime(session.lastTime) }}</div>
    </div>
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { MessageCircle } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'

const props = defineProps({
  session: { type: Object, required: true },
})

const chat = useChatStore()

const isActive = computed(() => chat.currentSessionId === props.session.sessionId)

async function onClick() {
  if (isActive.value) return
  await chat.loadSession(props.session.sessionId)
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const diffDays = Math.floor((now - d) / (1000 * 60 * 60 * 24))
  if (diffDays === 0) return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  if (diffDays < 7) return `${diffDays} 天前`
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<style scoped>
.hist-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  border-radius: var(--r-md);
  text-align: left;
  transition: all var(--t-fast);
  width: 100%;
}

.hist-item:hover {
  background: var(--bg-hover);
}

.hist-item.active {
  background: var(--brand-soft);
}

.hist-item.active .hist-icon {
  color: var(--brand);
}

.hist-item.active .hist-title {
  color: var(--brand-deep);
  font-weight: 500;
}

.hist-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
  transition: color var(--t-fast);
}

.hist-text {
  flex: 1;
  min-width: 0;
}

.hist-title {
  font-size: var(--text-sm);
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hist-time {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 1px;
}
</style>