import { ref } from 'vue'

/**
 * 全局 Toast 通知系统
 *
 * 用法：
 *   import { useToast } from '@/composables/useToast'
 *   const toast = useToast()
 *   toast.success('保存成功')
 *   toast.error('保存失败', 5000)
 *
 * 设计：
 *   - 模块级单例：整个应用共享同一 toast 队列
 *   - 自动消失：默认 3s（error 默认 5s），可自定义
 *   - 可手动 dismiss（点击 toast）
 *   - 不阻塞 JS 执行（替代原生 alert）
 *
 * 【2026-06-27 增量】替代 ChatView.vue 中的 alert() 调用，
 * 解决 alert 阻塞主线程导致的 UI 卡死问题。
 */

// 模块级共享 state（所有 useToast() 调用共享同一队列）
const toasts = ref([])
let nextId = 0

export function useToast() {
  const dismiss = (id) => {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }

  const show = (message, type = 'info', duration = 3000) => {
    const id = nextId++
    toasts.value.push({ id, message, type })

    if (duration > 0) {
      setTimeout(() => dismiss(id), duration)
    }
  }

  return {
    toasts,
    success: (msg, duration = 3000) => show(msg, 'success', duration),
    error:   (msg, duration = 5000) => show(msg, 'error', duration),
    info:    (msg, duration = 3000) => show(msg, 'info', duration),
    warning: (msg, duration = 4000) => show(msg, 'warning', duration),
    dismiss,
  }
}