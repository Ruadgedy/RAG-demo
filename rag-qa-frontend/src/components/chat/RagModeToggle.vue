<!--
  RagModeToggle — per-conversation RAG 模式切换（Agentic RAG F23）

  视觉：两个 pill（传统 / 智能体），agentic 选中态用品牌渐变 + Sparkles 图标。
  来源：chat.effectiveRagMode（conv.rag_mode ?? 全局默认）
  落点：store.chat.updateRagMode(convId, mode) → PATCH /api/conversations/{id}/rag-mode
       乐观更新 + 失败回滚在 store 内统一处理。
  禁用：未选对话 / 流式生成中 / 无知识库。
-->
<template>
  <div class="rag-toggle" :class="{ 'is-disabled': disabled }">
    <button
      type="button"
      class="rag-toggle__pill"
      :class="{ active: current === 'linear', 'linear-active': current === 'linear' }"
      :disabled="disabled"
      :title="disabled ? '请先选择对话' : '传统 RAG：单次检索 + 改写 + 生成'"
      @click="onSelect('linear')"
    >
      <ListOrdered :size="14" :stroke-width="2.2" />
      <span>传统</span>
    </button>

    <button
      type="button"
      class="rag-toggle__pill"
      :class="{ active: current === 'agentic', 'agentic-active': current === 'agentic' }"
      :disabled="disabled"
      :title="disabled ? '请先选择对话' : '智能体：LLM 自主编排 KB / Web / 直答多源工具'"
      @click="onSelect('agentic')"
    >
      <Sparkles :size="14" :stroke-width="2.2" />
      <span>智能体</span>
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ListOrdered, Sparkles } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'

const props = defineProps({
  /** 为 true 时禁用（流式生成中 / 未选对话 / 切换中） */
  disabled: { type: Boolean, default: false },
})

const chat = useChatStore()

const current = computed(() => chat.effectiveRagMode)

async function onSelect(mode) {
  if (props.disabled) return
  if (mode === current.value) return
  const convId = chat.currentConversationId
  if (!convId) return
  await chat.updateRagMode(convId, mode)
}
</script>

<style scoped>
.rag-toggle {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  background: var(--bg-input, #F4F4F7);
  border: 1px solid var(--border-subtle, #EAECF0);
  border-radius: var(--r-pill, 999px);
  font-size: var(--text-xs, 12px);
  user-select: none;
  transition: opacity var(--t-fast, 0.15s ease);
}

.rag-toggle.is-disabled {
  opacity: 0.5;
  pointer-events: none;
}

.rag-toggle__pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 0;
  background: transparent;
  color: var(--text-secondary, #6B7280);
  border-radius: var(--r-pill, 999px);
  cursor: pointer;
  font-size: inherit;
  line-height: 1;
  transition: all var(--t-fast, 0.15s ease);
}

.rag-toggle__pill:hover:not(:disabled):not(.active) {
  background: rgba(0, 0, 0, 0.04);
  color: var(--text-primary, #1F2328);
}

.rag-toggle__pill.active.linear-active {
  background: var(--bg-page, #FFFFFF);
  color: var(--text-primary, #1F2328);
  font-weight: 600;
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.06));
}

.rag-toggle__pill.active.agentic-active {
  /* agentic 选中态：品牌渐变 + 白文 */
  background: var(--gradient-brand, linear-gradient(135deg, #4D6EF5 0%, #7B5BF5 100%));
  color: #fff;
  font-weight: 600;
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.06));
}

.rag-toggle__pill:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>
