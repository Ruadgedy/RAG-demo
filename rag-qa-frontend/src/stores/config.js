/**
 * 全局配置 store（Agentic RAG F23）。
 *
 * 后端 GET /api/config 返回的运行时默认值。F23 主要用 ragMode：
 * - 新对话未设置时，默认 mode 来自此处
 * - mode toggle 在新对话（conv.rag_mode=null）时显示全局默认值
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as configApi from '@/api/config'

export const useConfigStore = defineStore('config', () => {
  // ==================== 状态 ====================
  const ragMode = ref('linear')           // 全局默认 RAG 模式
  const defaultHistoryWindow = ref(3)     // 全局默认滑动窗口
  const loaded = ref(false)               // 是否已加载过（避免重复请求）

  // ==================== Actions ====================

  /**
   * 从后端拉取一次配置
   */
  async function fetchConfig(force = false) {
    if (loaded.value && !force) return
    try {
      const cfg = await configApi.getConfig()
      ragMode.value = cfg.ragMode || 'linear'
      defaultHistoryWindow.value = cfg.defaultHistoryWindow ?? 3
      loaded.value = true
    } catch (e) {
      // 静默 fallback 到默认 linear
      console.warn('[config] fetch failed, fallback to linear:', e?.message)
      ragMode.value = 'linear'
      defaultHistoryWindow.value = 3
      loaded.value = true
    }
  }

  return {
    // 状态
    ragMode, defaultHistoryWindow, loaded,
    // Actions
    fetchConfig,
  }
})
