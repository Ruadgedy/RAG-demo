<!--
  AppShell — 全局壳层
  - 左侧：AppSidebar（可折叠，状态由 stores/ui 持久化）
  - 右侧：默认 <slot>（主区）
  - 侧边栏完全收起后，左边缘出现 Menu 按钮触发展开（豆包式）
-->
<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': ui.sidebarCollapsed }">
    <Transition name="sidebar">
      <aside v-if="!ui.sidebarCollapsed" class="app-shell__sidebar">
        <slot name="sidebar" />
      </aside>
    </Transition>

    <main class="app-shell__main">
      <slot />
    </main>

    <!-- 侧边栏收起后，浮一个 Menu 按钮在左边缘 -->
    <button
      v-if="ui.sidebarCollapsed"
      class="app-shell__reopen"
      type="button"
      aria-label="展开侧边栏"
      @click="ui.toggleSidebar()"
    >
      <Menu :size="18" :stroke-width="2.4" />
    </button>
  </div>
</template>

<script setup>
import { Menu } from 'lucide-vue-next'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<style scoped>
.app-shell {
  display: grid;
  grid-template-columns: var(--sidebar-w) 1fr;
  height: 100vh;
  width: 100vw;
  background: var(--bg-page);
  position: relative;
  overflow: hidden;
  transition: grid-template-columns var(--t-slow);
}

.app-shell.sidebar-collapsed {
  grid-template-columns: 0 1fr;
}

.app-shell__sidebar {
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-subtle);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
  z-index: var(--z-sidebar);
}

.app-shell__main {
  position: relative;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-page);
}

.app-shell__reopen {
  position: fixed;
  left: 12px;
  top: 14px;
  z-index: calc(var(--z-sidebar) + 1);
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-page);
  color: var(--text-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-sm);
  transition: all var(--t-fast);
}

.app-shell__reopen:hover {
  color: var(--brand);
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

/* sidebar 折叠/展开过渡 */
.sidebar-enter-from,
.sidebar-leave-to {
  transform: translateX(-100%);
  opacity: 0;
}
.sidebar-enter-active,
.sidebar-leave-active {
  transition: transform var(--t-slow), opacity var(--t-slow);
  overflow: hidden;
}
</style>