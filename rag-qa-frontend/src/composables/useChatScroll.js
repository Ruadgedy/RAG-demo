/**
 * useChatScroll — 自动滚到底 + 悬浮「回到最新」按钮
 *
 * 用法：
 *   const { scrollRef, fabVisible, scrollToBottom, onScroll } = useChatScroll()
 *   <div ref="scrollRef" @scroll="onScroll">...</div>
 *   <ScrollToBottomFab v-if="fabVisible" @click="scrollToBottom" />
 *
 * 触发 scrollToBottom 的时机由调用方决定（nextTick 之后）；
 * 提供 watchElement 工具方便绑定 ref。
 */
import { ref, nextTick } from 'vue'

const FAB_THRESHOLD = 120  // 距底 > 120px 时显示 FAB

export function useChatScroll() {
  const scrollRef = ref(null)
  const fabVisible = ref(false)

  function isAtBottom() {
    const el = scrollRef.value
    if (!el) return true
    return el.scrollHeight - el.scrollTop - el.clientHeight < FAB_THRESHOLD
  }

  function updateFab() {
    fabVisible.value = !isAtBottom()
  }

  function onScroll() {
    updateFab()
  }

  async function scrollToBottom(smooth = false) {
    await nextTick()
    const el = scrollRef.value
    if (!el) return
    el.scrollTo({
      top: el.scrollHeight,
      behavior: smooth ? 'smooth' : 'auto',
    })
    fabVisible.value = false
  }

  return { scrollRef, fabVisible, onScroll, scrollToBottom }
}