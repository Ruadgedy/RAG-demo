<!--
  KnowledgeBaseItem — 单个知识库条目
  - 默认显示：图标 + 名称 + 时间 + 上传按钮
  - 选中态：渐变左条 + 浅紫底 + 展开文档列表
-->
<template>
  <div class="kb-item-wrap">
    <div
      class="kb-item"
      :class="{ active: isActive }"
      @click="$emit('select')"
    >
      <Database :size="16" :stroke-width="2.2" class="kb-icon" />
      <div class="kb-text">
        <div class="kb-name">{{ kb.name }}</div>
        <div class="kb-time">{{ formatTime(kb.createdAt) }}</div>
      </div>
      <button
        v-if="isActive"
        class="kb-upload-btn"
        type="button"
        title="上传文档"
        @click.stop="$emit('upload')"
      >
        <Upload :size="13" :stroke-width="2.4" />
      </button>
    </div>

    <Transition name="expand">
      <DocumentList v-if="expanded && documents.length" :documents="documents" />
    </Transition>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Database, Upload } from 'lucide-vue-next'
import DocumentList from './DocumentList.vue'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'

const props = defineProps({
  kb: { type: Object, required: true },
  expanded: { type: Boolean, default: false },
})

defineEmits(['select', 'upload'])

const kbStore = useKnowledgeBaseStore()

const isActive = computed(() => kbStore.currentKb?.id === props.kb.id)
const documents = computed(() => isActive.value ? kbStore.documents : [])

function formatTime(t) {
  if (!t) return ''
  return new Date(t).toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.kb-item-wrap {
  position: relative;
}

.kb-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: var(--r-md);
  cursor: pointer;
  position: relative;
  transition: background var(--t-fast), color var(--t-fast);
}

.kb-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  background: transparent;
  border-radius: 0 var(--r-pill) var(--r-pill) 0;
  transition: background var(--t-fast);
}

.kb-item:hover {
  background: var(--bg-hover);
}

.kb-item.active {
  background: var(--brand-soft);
}

.kb-item.active::before {
  background: var(--gradient-brand);
}

.kb-item.active .kb-icon {
  color: var(--brand);
}

.kb-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
  transition: color var(--t-fast);
}

.kb-text {
  flex: 1;
  min-width: 0;
}

.kb-name {
  font-size: var(--text-sm);
  color: var(--text-primary);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-time {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 1px;
}

.kb-upload-btn {
  width: 26px;
  height: 26px;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  background: var(--bg-page);
  border: 1px solid var(--border-subtle);
  transition: all var(--t-fast);
}

.kb-upload-btn:hover {
  color: var(--brand);
  border-color: var(--border-brand);
}

/* ===== 展开动画 ===== */
.expand-enter-from,
.expand-leave-to {
  max-height: 0;
  opacity: 0;
}
.expand-enter-active,
.expand-leave-active {
  transition: max-height var(--t-normal), opacity var(--t-normal);
  overflow: hidden;
}
.expand-enter-to,
.expand-leave-from {
  max-height: 300px;
  opacity: 1;
}
</style>