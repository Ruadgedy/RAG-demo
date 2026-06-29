/**
 * UI store：当前仅含侧边栏折叠状态
 * 用 useStorage 持久化到 localStorage，刷新后保留
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useStorage } from '@vueuse/core'

export const useUiStore = defineStore('ui', () => {
  // localStorage key: ui.sidebarCollapsed
  const sidebarCollapsed = useStorage('ui.sidebarCollapsed', false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  return { sidebarCollapsed, toggleSidebar }
})