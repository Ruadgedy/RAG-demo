<!--
  SourceCard — 「参考 N 篇文档」可展开卡片
  -
  位置：AI 消息气泡下方
  数据：msg.sources（来自后端 ChatResponse.sources）
  行为：默认折叠，点击展开看每篇文档的 fileName + snippet + score
-->
<template>
  <details v-if="sources && sources.length" class="source-card" :open="defaultOpen">
    <summary class="source-card__summary">
      <BookMarked :size="14" :stroke-width="2.2" class="source-card__icon" />
      <span class="source-card__title">
        参考 {{ sources.length }} 篇文档
      </span>
      <ChevronDown :size="14" :stroke-width="2.2" class="source-card__chevron" />
    </summary>

    <div class="source-card__list">
      <div
        v-for="(src, idx) in sources"
        :key="`${src.documentId}_${src.chunkIndex ?? 0}`"
        class="source-item"
      >
        <div class="source-item__header">
          <span class="source-item__index">【{{ idx + 1 }}】</span>
          <FileText :size="13" :stroke-width="2.2" class="source-item__file-icon" />
          <span class="source-item__filename" :title="src.fileName">
            {{ src.fileName }}
          </span>
          <span v-if="src.chunkIndex != null" class="source-item__chunk">
            片段 {{ src.chunkIndex + 1 }}
          </span>
          <span v-if="src.score != null" class="source-item__score" :title="`相关度 ${(src.score * 100).toFixed(1)}%`">
            {{ formatScore(src.score) }}
          </span>
        </div>
        <div v-if="src.snippet" class="source-item__snippet">
          {{ src.snippet }}
        </div>
      </div>
    </div>
  </details>
</template>

<script setup>
import { BookMarked, ChevronDown, FileText } from 'lucide-vue-next'

defineProps({
  sources: { type: Array, default: () => [] },
  // 是否默认展开（流式生成时建议折叠，避免滚动抖动）
  defaultOpen: { type: Boolean, default: false },
})

/**
 * 格式化相关性分数
 * - cosine (P1-01)：范围 [-1, 1]，转成百分比显示
 * - rerank (P1-02)：范围 [0, 1]，直接百分比
 */
function formatScore(score) {
  if (score == null) return ''
  // 负数（cosine 范围）按绝对值显示相关性
  const v = score < 0 ? -score : score
  return `${(v * 100).toFixed(0)}%`
}
</script>

<style scoped>
.source-card {
  margin-top: 10px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  background: var(--bg-card);
  font-size: var(--text-xs);
  overflow: hidden;
  transition: border-color var(--t-fast);
}

.source-card:hover {
  border-color: var(--border-strong);
}

.source-card[open] {
  border-color: var(--border-brand);
  background: var(--bg-page);
}

/* ===== summary 行 ===== */
.source-card__summary {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  list-style: none;
  user-select: none;
  color: var(--text-secondary);
  font-weight: 500;
}

.source-card__summary::-webkit-details-marker {
  display: none;
}

.source-card__icon {
  color: var(--brand);
}

.source-card__title {
  flex: 1;
  font-size: var(--text-xs);
}

.source-card__chevron {
  color: var(--text-tertiary);
  transition: transform var(--t-fast);
}

.source-card[open] .source-card__chevron {
  transform: rotate(180deg);
}

/* ===== 列表 ===== */
.source-card__list {
  padding: 4px 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-top: 1px solid var(--border-subtle);
}

.source-item {
  padding: 8px 10px;
  background: var(--bg-card);
  border-radius: var(--r-sm);
  border: 1px solid transparent;
  transition: all var(--t-fast);
}

.source-item:hover {
  background: var(--brand-soft);
  border-color: var(--border-brand);
}

.source-item__header {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--text-xs);
}

.source-item__index {
  color: var(--brand);
  font-weight: 600;
  flex-shrink: 0;
}

.source-item__file-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.source-item__filename {
  color: var(--text-primary);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.source-item__chunk {
  color: var(--text-tertiary);
  font-size: 11px;
  white-space: nowrap;
  flex-shrink: 0;
}

.source-item__score {
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--r-pill);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.source-item__snippet {
  margin-top: 4px;
  padding: 6px 8px;
  background: var(--bg-page);
  border-left: 2px solid var(--brand);
  border-radius: 0 var(--r-xs) var(--r-xs) 0;
  color: var(--text-secondary);
  font-size: var(--text-xs);
  line-height: 1.6;
  word-break: break-word;
}
</style>