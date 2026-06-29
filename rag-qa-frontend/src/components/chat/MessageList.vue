<!--
  MessageList — 消息流
  - 持有滚动容器
  - 自动滚到底（消息/流式追加时）
  - 上滚后显示 ScrollToBottomFab
-->
<template>
  <div class="msg-list-wrap scroll-thin">
    <div ref="scrollRef" class="msg-list" @scroll="onScroll">
      <div class="msg-list__inner">
        <slot />
      </div>

      <Transition name="fab">
        <ScrollToBottomFab v-if="fabVisible" @click="scrollToBottom(true)" />
      </Transition>
    </div>
  </div>
</template>

<script setup>
import { watch, nextTick } from 'vue'
import ScrollToBottomFab from '@/components/common/ScrollToBottomFab.vue'
import { useChatScroll } from '@/composables/useChatScroll'

const props = defineProps({
  streamTick: { type: Number, default: 0 },   // 流式追加时父组件 +1
})

const { scrollRef, fabVisible, onScroll, scrollToBottom } = useChatScroll()

// 流式追加每次 +1 都自动滚到底
watch(() => props.streamTick, async () => {
  await scrollToBottom(false)
})

// 显式暴露方法给父组件调用
defineExpose({ scrollToBottom })

// 初次挂载滚到底
nextTick(() => scrollToBottom(false))
</script>

<style scoped>
.msg-list-wrap {
  flex: 1 1 0;
  min-height: 0;
  position: relative;
  background: var(--bg-page);
}

.msg-list {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  position: relative;
}

.msg-list__inner {
  max-width: var(--content-max-w);
  margin: 0 auto;
  padding: 24px 24px 12px;
}

/* FAB 过渡 */
.fab-enter-from, .fab-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
.fab-enter-active, .fab-leave-active {
  transition: all var(--t-normal);
}
</style>