<template>
  <div class="chat-view">
    <div class="sidebar">
      <div class="sidebar-header">
        <h2>知识库</h2>
        <button class="btn-primary">新建</button>
      </div>
      <div class="knowledge-list">
        <div class="knowledge-item" v-for="kb in knowledgeBases" :key="kb.id">
          <span>{{ kb.name }}</span>
        </div>
      </div>
    </div>
    <div class="main">
      <div class="chat-area">
        <div class="messages">
          <div class="message assistant">
            <div class="avatar">AI</div>
            <div class="content">您好！我是RAG智能问答助手。请选择或创建一个知识库，然后开始提问。</div>
          </div>
        </div>
        <div class="input-area">
          <textarea placeholder="请输入您的问题..."></textarea>
          <button class="btn-send">发送</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const knowledgeBases = ref([
  { id: 1, name: '技术文档' },
  { id: 2, name: '产品说明' }
])
</script>

<style scoped>
.chat-view {
  display: flex;
  width: 100%;
  height: 100%;
}

.sidebar {
  width: 240px;
  background: var(--color-bg-secondary);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: var(--space-md);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.sidebar-header h2 {
  font: var(--font-heading-2);
}

.btn-primary {
  background: var(--color-primary);
  color: white;
  border: none;
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  font: var(--font-button);
  cursor: pointer;
}

.btn-primary:hover {
  background: var(--color-primary-hover);
}

.knowledge-list {
  flex: 1;
  overflow-y: auto;
}

.knowledge-item {
  padding: var(--space-sm) var(--space-md);
  cursor: pointer;
  font: var(--font-body);
}

.knowledge-item:hover {
  background: var(--color-bg-hover);
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.messages {
  flex: 1;
  padding: var(--space-md);
  overflow-y: auto;
}

.message {
  display: flex;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.message.assistant .content {
  background: var(--color-bg-primary);
  border: 1px solid var(--color-border);
}

.avatar {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-full);
  background: var(--color-bg-hover);
  display: flex;
  align-items: center;
  justify-content: center;
  font: var(--font-body-small);
}

.content {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  max-width: 70%;
  font: var(--font-body);
}

.input-area {
  padding: var(--space-md);
  border-top: 1px solid var(--color-border);
  display: flex;
  gap: var(--space-sm);
}

.input-area textarea {
  flex: 1;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-sm);
  font: var(--font-body);
  resize: none;
}

.btn-send {
  background: var(--color-primary);
  color: white;
  border: none;
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
</style>
