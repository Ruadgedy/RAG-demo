<!--
  ScrollToBottomFab — 「回到最新」悬浮按钮
  使用 ChevronDown 图标，懒汉式包装器
-->
<template>
  <button
    class="scroll-fab"
    type="button"
    aria-label="回到最新消息"
    @click="$emit('click')"
  >
    <ChevronDown :size="18" :stroke-width="2.5" />
  </button>
</template>

<script setup>
import { ChevronDown } from 'lucide-vue-next'

defineEmits(['click'])
</script>

<style scoped>
.scroll-fab {
  position: absolute;
  right: 24px;
  bottom: 24px;
  width: 36px;
  height: 36px;
  border-radius: var(--r-pill);
  background: var(--bg-page);
  color: var(--text-secondary);
  box-shadow: var(--shadow-md);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-fab);
  transition: transform var(--t-fast), box-shadow var(--t-normal), color var(--t-fast);
  border: 1px solid var(--border-subtle);
}

.scroll-fab:hover {
  color: var(--brand);
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}

.scroll-fab:active {
  transform: translateY(0);
}
</style>