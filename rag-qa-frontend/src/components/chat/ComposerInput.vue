<!--
  ComposerInput — 底部输入框
  视觉：
    ┌───────────────────────────────────────────────┐
    │  KB chip     textarea...            Send    │
    └───────────────────────────────────────────────┘
  功能：
    - Enter 发送，Shift+Enter 换行
    - 自动撑高（CSS min/max-height）
    - 流式模式开关：icon-only toggle（Zap=流式 / FileText=一次性）
    - 发送中：Send 按钮变 Square（停止图标），点击触发 chat.stop()
    - 暴露 focus() 给父组件（欢迎卡片点击后聚焦）
-->
<template>
  <div class="composer-wrap">
    <div class="composer" :class="{ focused: isFocused }">
      <!-- 当前 KB 信息 -->
      <div v-if="kbStore.currentKb" class="composer__kb">
        <span class="kb-dot" />
        <span class="kb-name">{{ kbStore.currentKb.name }}</span>
      </div>

      <!-- 允许编辑，发送时由 store 守住 -->
      <textarea
        ref="textareaRef"
        v-model="text"
        class="composer__input"
        rows="1"
        :placeholder="placeholder"
        @keydown.enter.exact.prevent="onSend"
        @keydown.shift.enter.exact="onShiftEnter"
        @focus="isFocused = true"
        @blur="isFocused = false"
        @input="autosize"
      />

      <div class="composer__toolbar">
        <!-- 附件图标（占位） -->
        <button class="tool-btn" type="button" title="附件（即将支持）" disabled>
          <Paperclip :size="16" :stroke-width="2.2" />
        </button>
        <!-- 图片图标（占位） -->
        <button class="tool-btn" type="button" title="图片（即将支持）" disabled>
          <Image :size="16" :stroke-width="2.2" />
        </button>

        <!-- 流式模式切换（icon-only toggle） -->
        <button
          class="tool-btn mode-chip"
          :class="{ active: chat.streamingEnabled }"
          type="button"
          :title="chat.streamingEnabled ? '流式输出（点击切换为一次性）' : '一次性输出（点击切换为流式）'"
          @click="toggleStream"
        >
          <Zap v-if="chat.streamingEnabled" :size="16" :stroke-width="2.4" />
          <FileText v-else :size="16" :stroke-width="2.2" />
        </button>

        <!-- 发送 / 停止 -->
        <button
          class="send-btn"
          :class="{ sending: chat.isStreaming }"
          type="button"
          :disabled="chat.isStreaming ? false : !text.trim()"
          :title="chat.isStreaming ? '停止生成' : '发送'"
          @click="onPrimary"
        >
          <Square v-if="chat.isStreaming" :size="14" :stroke-width="2.4" fill="currentColor" />
          <Send v-else :size="16" :stroke-width="2.4" />
        </button>
      </div>
    </div>

    <p class="composer__hint">
      Enter 发送 · Shift+Enter 换行
      <span v-if="chat.streamingEnabled" class="hint-stream">
        · 当前为流式输出
      </span>
    </p>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { Paperclip, Image, Zap, FileText, Send, Square } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'

const emit = defineEmits(['send'])

const chat = useChatStore()
const kbStore = useKnowledgeBaseStore()

const text = ref('')
const isFocused = ref(false)
const textareaRef = ref(null)

const placeholder = computedPlaceholder()

function computedPlaceholder() {
  if (!kbStore.currentKb) return '请先在左侧选择一个知识库…'
  return '输入你的问题，RAG 会基于知识库回答'
}

async function onSend() {
  const t = text.value.trim()
  if (!t || chat.isStreaming) return
  text.value = ''
  resetSize()
  emit('send', t)
  await nextTick()
  textareaRef.value?.focus()
}

function onPrimary() {
  if (chat.isStreaming) {
    chat.stop()
  } else {
    onSend()
  }
}

function onShiftEnter(e) {
  // 默认行为插入换行即可
}

function toggleStream() {
  if (chat.isStreaming) return
  chat.setStreamMode(chat.streamingEnabled ? 'oneShot' : 'streaming')
}

function autosize(e) {
  const ta = e?.target || textareaRef.value
  if (!ta) return
  ta.style.height = 'auto'
  ta.style.height = Math.min(ta.scrollHeight, 180) + 'px'
}

function resetSize() {
  if (textareaRef.value) textareaRef.value.style.height = 'auto'
}

function focus() {
  textareaRef.value?.focus()
}

function setText(t) {
  text.value = t
  nextTick(() => autosize())
  focus()
}

defineExpose({ focus, setText })
</script>

<style scoped>
.composer-wrap {
  width: 100%;
  max-width: var(--composer-max-w);
  margin: 0 auto;
  padding: 8px 24px 24px;
}

.composer {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 10px 8px;
  background: var(--bg-page);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-md);
  transition: all var(--t-normal);
}

.composer.focused {
  border-color: var(--border-brand);
  box-shadow: 0 0 0 4px var(--brand-soft), var(--shadow-md);
}

.composer__kb {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  gap: 5px;
  padding: 3px 9px 3px 7px;
  background: var(--brand-soft);
  color: var(--brand-deep);
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: 500;
  max-width: 240px;
}

.kb-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--r-pill);
  background: var(--brand);
}

.kb-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.composer__input {
  width: 100%;
  min-height: 24px;
  max-height: 180px;
  padding: 4px 8px 4px;
  background: transparent;
  border: none;
  font-size: var(--text-md);
  line-height: 1.6;
  color: var(--text-primary);
  resize: none;
  outline: none;
  font-family: inherit;
}

.composer__input::placeholder {
  color: var(--text-tertiary);
}

.composer__toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 4px;
  border-top: 1px solid var(--border-subtle);
}

.tool-btn {
  width: 32px;
  height: 32px;
  border-radius: var(--r-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
  transition: all var(--t-fast);
}

.tool-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-secondary);
}

.tool-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.tool-btn.mode-chip {
  width: auto;
  padding: 0 8px;
  gap: 4px;
  font-size: var(--text-xs);
}

.tool-btn.mode-chip.active {
  background: var(--brand-soft);
  color: var(--brand);
}

.send-btn {
  margin-left: auto;
  width: 36px;
  height: 36px;
  border-radius: var(--r-pill);
  background: var(--gradient-brand);
  color: var(--text-on-brand);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-brand);
  transition: all var(--t-fast);
}

.send-btn:hover:not(:disabled) {
  filter: brightness(1.05);
  transform: scale(1.05);
}

.send-btn:active:not(:disabled) {
  transform: scale(0.95);
}

.send-btn:disabled {
  filter: grayscale(0.4) brightness(1.1);
  opacity: 0.6;
}

.send-btn.sending {
  background: var(--bg-card);
  color: var(--text-secondary);
  box-shadow: none;
}

.send-btn.sending:hover {
  color: var(--color-error);
  background: var(--bg-hover);
}

.composer__hint {
  margin: 8px 4px 0;
  font-size: 11px;
  color: var(--text-tertiary);
  text-align: center;
}

.hint-stream {
  color: var(--brand);
}
</style>