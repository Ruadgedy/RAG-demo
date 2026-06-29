<!--
  WelcomeEmptyState — 无知识库时的占位空态
  独立组件文件：避免在 ChatView 中用 dual-script 模式注册子组件（不可靠）
-->
<template>
  <div class="empty-state anim-fade-up">
    <div class="empty-icon-wrap">
      <Sparkles :size="36" :stroke-width="1.6" class="empty-icon" />
    </div>
    <h2 class="empty-title">欢迎使用 RAG 智能问答</h2>
    <p class="empty-sub">请先在左侧创建或选择一个知识库开始对话</p>
    <div class="empty-tips">
      <p>·  支持 PDF / Word / TXT 文档上传</p>
      <p>·  基于知识库内容精准回答</p>
    </div>
  </div>
</template>

<script setup>
import { Sparkles } from 'lucide-vue-next'
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 24px 24px;
  text-align: center;
  gap: 10px;
}

.empty-icon-wrap {
  width: 72px;
  height: 72px;
  border-radius: var(--r-xl);
  background: var(--gradient-brand-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 8px;
}

.empty-icon {
  color: var(--brand);
  filter: drop-shadow(0 2px 8px rgba(77, 110, 245, 0.3));
}

.empty-title {
  font-size: var(--text-xl);
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.empty-sub {
  font-size: var(--text-sm);
  color: var(--text-secondary);
  margin: 0 0 12px;
}

.empty-tips {
  font-size: var(--text-xs);
  color: var(--text-tertiary);
  line-height: 1.9;
}

.empty-tips p { margin: 0; }
</style>