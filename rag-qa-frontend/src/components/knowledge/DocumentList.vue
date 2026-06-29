<!--
  DocumentList — 当前 KB 的文档列表
  每个文档：文件名 + 状态 pill + 进度条
-->
<template>
  <div class="doc-list">
    <div class="doc-list-title">
      <FileText :size="11" :stroke-width="2.2" />
      <span>文档 ({{ documents.length }})</span>
    </div>
    <div v-for="doc in documents" :key="doc.id" class="doc-item">
      <div class="doc-name" :title="doc.fileName">{{ doc.fileName }}</div>
      <div class="doc-meta">
        <span class="doc-status" :class="statusClass(doc.status)">
          <span class="status-dot" />
          {{ statusText(doc.status) }}
          <span v-if="doc.progress && doc.status !== 'COMPLETED' && !isFailed(doc.status)" class="doc-progress">
            {{ doc.progress }}%
          </span>
        </span>
      </div>
      <div
        v-if="doc.progress && doc.status !== 'COMPLETED' && !isFailed(doc.status)"
        class="progress-bar"
      >
        <div class="progress-fill" :style="{ width: doc.progress + '%' }" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { FileText } from 'lucide-vue-next'

defineProps({
  documents: { type: Array, required: true },
})

const STATUS_MAP = {
  UPLOADING: { text: '上传中', cls: 'processing' },
  UPLOAD_FAILED: { text: '上传失败', cls: 'failed' },
  PARSING: { text: '解析中', cls: 'processing' },
  PARSE_FAILED: { text: '解析失败', cls: 'failed' },
  CHUNKING: { text: '切片中', cls: 'processing' },
  CHUNK_FAILED: { text: '切片失败', cls: 'failed' },
  EMBEDDING: { text: '向量化', cls: 'processing' },
  EMBEDDING_FAILED: { text: '向量化失败', cls: 'failed' },
  COMPLETED: { text: '已完成', cls: 'success' },
  FAILED: { text: '失败', cls: 'failed' },
}

function statusText(s) { return STATUS_MAP[s]?.text || s }
function statusClass(s) { return STATUS_MAP[s]?.cls || '' }
function isFailed(s) { return s?.includes('FAILED') || s === 'FAILED' }
</script>

<style scoped>
.doc-list {
  margin: 2px 6px 6px 14px;
  padding: 8px 10px;
  background: var(--bg-card);
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.doc-list-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--text-tertiary);
  font-weight: 500;
  letter-spacing: 0.02em;
}

.doc-item {
  padding: 4px 0;
}

.doc-name {
  font-size: var(--text-xs);
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-meta {
  margin-top: 2px;
}

.doc-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--text-tertiary);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--r-pill);
  background: currentColor;
  flex-shrink: 0;
}

.doc-status.processing { color: var(--brand); }
.doc-status.success    { color: var(--color-success); }
.doc-status.failed     { color: var(--color-error); }

.doc-progress {
  font-variant-numeric: tabular-nums;
}

.progress-bar {
  margin-top: 4px;
  height: 3px;
  background: var(--border-subtle);
  border-radius: var(--r-pill);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--gradient-brand);
  border-radius: var(--r-pill);
  transition: width var(--t-normal);
}
</style>