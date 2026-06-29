<!--
  MessageBubble — 单条消息气泡
  - role=user    ：右对齐，渐变胶囊背景 + 白字
  - role=assistant：左对齐，无背景，纯文本流；上方小品牌标识 + 「RAG」小字
  - markdown 通过 marked 渲染，统一 doubao.css 中的 .md-body 样式
-->
<template>
  <div class="msg" :class="['msg--' + msg.role]">
    <!-- 用户消息：右对齐胶囊 -->
    <template v-if="msg.role === 'user'">
      <div class="msg__bubble msg__bubble--user md-body" v-html="rendered" />
    </template>

    <!-- AI 消息：左侧无气泡 -->
    <template v-else>
      <div class="msg__brand">
        <Sparkles :size="14" :stroke-width="2.4" class="msg__brand-icon" />
        <span class="msg__brand-name">RAG</span>
      </div>
      <div class="msg__content md-body" v-html="rendered" />
      <div v-if="!msg.content && streaming" class="msg__loading">
        <span class="loading-dots"><span /><span /><span /></span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import { Sparkles } from 'lucide-vue-next'

const props = defineProps({
  msg: { type: Object, required: true },
  streaming: { type: Boolean, default: false },
})

const rendered = computed(() => marked(props.msg.content || ''))
</script>

<style scoped>
.msg {
  display: flex;
  flex-direction: column;
  margin-bottom: 20px;
  animation: fade-up var(--t-normal) both;
}

@keyframes fade-up {
  from { opacity: 0; transform: translateY(4px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* ===== 用户 ===== */
.msg--user {
  align-items: flex-end;
}

.msg__bubble--user {
  max-width: 70%;
  padding: 10px 16px;
  background: var(--gradient-brand);
  color: var(--text-on-brand);
  border-radius: var(--r-pill);
  font-size: var(--text-md);
  line-height: 1.6;
  word-wrap: break-word;
  white-space: pre-wrap;
  box-shadow: var(--shadow-brand);
}

.msg__bubble--user :deep(p) { margin: 0; }
.msg__bubble--user :deep(code) {
  background: rgba(255, 255, 255, 0.18);
  color: var(--text-on-brand);
  padding: 1px 6px;
  border-radius: var(--r-xs);
  font-family: var(--font-mono);
  font-size: 0.9em;
}

/* ===== AI ===== */
.msg--assistant {
  align-items: flex-start;
}

.msg__brand {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
  color: var(--brand);
}

.msg__brand-icon {
  filter: drop-shadow(0 1px 1px rgba(77, 110, 245, 0.3));
}

.msg__brand-name {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.msg__content {
  max-width: 100%;
  font-size: var(--text-md);
  line-height: 1.75;
  color: var(--text-primary);
  word-wrap: break-word;
}

.msg__loading {
  margin-top: 4px;
}
</style>