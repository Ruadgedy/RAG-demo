<template>
  <div class="toast-container">
    <TransitionGroup name="toast">
      <div
        v-for="t in toasts"
        :key="t.id"
        :class="['toast', `toast-${t.type}`]"
        @click="dismiss(t.id)"
        role="alert"
        aria-live="polite"
      >
        <span class="toast-icon">{{ iconFor(t.type) }}</span>
        <span class="toast-message">{{ t.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup>
import { useToast } from '../../composables/useToast.js'

const { toasts, dismiss } = useToast()

const iconFor = (type) => ({
  success: '✓',
  error: '✕',
  warning: '⚠',
  info: 'ℹ',
}[type] || 'ℹ')
</script>

<style>
.toast-container {
  position: fixed;
  top: 24px;
  right: 24px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;       /* 容器不拦截点击 */
  max-width: 400px;
}

.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 18px;
  border-radius: 8px;
  color: white;
  font-size: 14px;
  font-weight: 500;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.15);
  min-width: 200px;
  pointer-events: auto;       /* toast 自身可点击 */
  cursor: pointer;
  user-select: none;
}

.toast-icon {
  font-weight: bold;
  font-size: 16px;
  flex-shrink: 0;
}

.toast-message {
  flex: 1;
  word-break: break-word;
}

.toast-success { background: #10b981; }
.toast-error   { background: #ef4444; }
.toast-warning { background: #f59e0b; }
.toast-info    { background: #3b82f6; }

/* TransitionGroup 动画 */
.toast-enter-from {
  opacity: 0;
  transform: translateX(40px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateX(40px);
}
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-move {
  transition: transform 0.3s ease;
}
</style>