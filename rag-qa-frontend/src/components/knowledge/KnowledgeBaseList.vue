<!--
  KnowledgeBaseList — 知识库列表
  顶部 +「新建」按钮 + 空态提示 + 渲染 KnowledgeBaseItem
-->
<template>
  <div class="kb-list">
    <button class="kb-new-btn" type="button" @click="showCreate = true">
      <Plus :size="14" :stroke-width="2.4" />
      <span>新建知识库</span>
    </button>

    <div v-if="kbStore.list.length === 0" class="kb-empty">
      暂无知识库，点击上方新建
    </div>

    <KnowledgeBaseItem
      v-for="kb in kbStore.list"
      :key="kb.id"
      :kb="kb"
      :expanded="kbStore.currentKb?.id === kb.id"
      @select="onSelect(kb)"
      @upload="onUpload(kb)"
    />

    <!-- 创建模态 -->
    <CreateKBModal v-model:show="showCreate" />
    <!-- 上传模态 -->
    <UploadDocModal v-model:show="showUpload" :kb-id="uploadKbId" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Plus } from 'lucide-vue-next'
import KnowledgeBaseItem from './KnowledgeBaseItem.vue'
import CreateKBModal from './CreateKBModal.vue'
import UploadDocModal from './UploadDocModal.vue'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'

const kbStore = useKnowledgeBaseStore()

const showCreate = ref(false)
const showUpload = ref(false)
const uploadKbId = ref(null)

async function onSelect(kb) {
  if (kbStore.currentKb?.id !== kb.id) {
    await kbStore.select(kb)
  }
}

function onUpload(kb) {
  uploadKbId.value = kb.id
  showUpload.value = true
}
</script>

<style scoped>
.kb-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kb-new-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  margin: 0 0 6px 0;
  font-size: var(--text-xs);
  color: var(--brand);
  border-radius: var(--r-sm);
  transition: background var(--t-fast);
}

.kb-new-btn:hover {
  background: var(--brand-soft);
}

.kb-empty {
  padding: 20px 12px;
  text-align: center;
  font-size: var(--text-xs);
  color: var(--text-tertiary);
}
</style>