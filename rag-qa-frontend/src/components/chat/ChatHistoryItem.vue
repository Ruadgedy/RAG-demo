<!--
  ChatHistoryItem — 单条对话组（V6 重构版）

  【V6 2026-06-30】
  - 对话组（conversation）替代会话（session）
  - 支持右键删除
-->
<template>
  <div
    class="hist-item"
    :class="{ active: isActive }"
    @click="onClick"
    @contextmenu.prevent="showMenu"
  >
    <MessageCircle :size="14" :stroke-width="2.2" class="hist-icon" />
    <div class="hist-text">
      <div class="hist-title">{{ conversation.title || conversation.firstQuery || '新对话' }}</div>
      <div class="hist-meta">
        <span>{{ conversation.turnCount || 0 }} 轮</span>
        <span class="hist-dot">·</span>
        <span>{{ formatTime(conversation.updatedAt) }}</span>
      </div>
    </div>
    <button
      v-if="isActive"
      class="hist-delete"
      title="删除对话"
      @click.stop="$emit('delete', conversation.id)"
    >
      <Trash2 :size="12" :stroke-width="2" />
    </button>
  </div>

  <!-- 简易确认删除弹窗 -->
  <Teleport to="body">
    <div v-if="confirmDelete" class="delete-modal-overlay" @click="confirmDelete = false">
      <div class="delete-modal" @click.stop>
        <p>确定删除这个对话？</p>
        <div class="delete-modal-btns">
          <button class="btn-cancel" @click="confirmDelete = false">取消</button>
          <button class="btn-confirm" @click="doDelete">删除</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, ref } from 'vue'
import { MessageCircle, Trash2 } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'

const props = defineProps({
  conversation: { type: Object, required: true },
})

defineEmits(['delete'])

const chat = useChatStore()
const confirmDelete = ref(false)

const isActive = computed(() => chat.currentConversationId === props.conversation.id)

async function onClick() {
  if (isActive.value) return
  await chat.loadConversation(props.conversation.id)
}

function showMenu() {
  confirmDelete.value = true
}

async function doDelete() {
  confirmDelete.value = false
  await chat.deleteConversation(props.conversation.id)
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
  transition: all var(--t-fast);
  cursor: pointer;
  position: relative;
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

.hist-meta {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 1px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.hist-dot {
  opacity: 0.5;
}

.hist-delete {
  padding: 4px;
  border-radius: var(--r-sm);
  color: var(--text-tertiary);
  transition: all var(--t-fast);
  flex-shrink: 0;
}

.hist-delete:hover {
  color: var(--red);
  background: var(--red-soft);
}

/* 删除确认弹窗 */
.delete-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.delete-modal {
  background: var(--bg-surface);
  border-radius: var(--r-lg);
  padding: 20px 24px;
  min-width: 240px;
  box-shadow: var(--shadow-lg);
}

.delete-modal p {
  margin: 0 0 16px;
  font-size: var(--text-sm);
  color: var(--text-primary);
}

.delete-modal-btns {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.btn-cancel, .btn-confirm {
  padding: 6px 16px;
  border-radius: var(--r-md);
  font-size: var(--text-sm);
  transition: all var(--t-fast);
}

.btn-cancel {
  background: var(--bg-hover);
  color: var(--text-secondary);
}

.btn-cancel:hover {
  background: var(--bg-elevated);
}

.btn-confirm {
  background: var(--red);
  color: white;
}

.btn-confirm:hover {
  opacity: 0.9;
}
</style>