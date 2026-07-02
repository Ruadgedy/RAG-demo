<!--
  AppSidebar — 侧边栏整块
  结构顺序：
    1. Header（BrandMark + 标题 + 折叠按钮）
    2. 「新对话」渐变胶囊按钮（开启新会话）
    3. 知识库分区（KnowledgeBaseList + 文档列表）
    4. 对话历史分区（ChatHistoryList）
    5. Footer（用户信息 + 退出）
-->
<template>
  <div class="sidebar">
    <!-- ===== Header ===== -->
    <header class="sidebar__header">
      <div class="sidebar__brand">
        <BrandMark :size="36" char="R" />
        <div class="sidebar__brand-text">
          <div class="sidebar__brand-title">RAG 智能问答</div>
          <div class="sidebar__brand-subtitle">Doubao Style</div>
        </div>
      </div>
      <button
        class="sidebar__icon-btn"
        type="button"
        aria-label="收起侧边栏"
        @click="ui.toggleSidebar()"
      >
        <PanelLeftClose :size="18" :stroke-width="2.2" />
      </button>
    </header>

    <!-- ===== 新对话 ===== -->
    <div class="sidebar__newchat">
      <button
        class="newchat-btn"
        type="button"
        @click="handleNewChat"
      >
        <Plus :size="18" :stroke-width="2.4" />
        <span>新对话</span>
      </button>
    </div>

    <!-- ===== 知识库 ===== -->
    <section class="sidebar__section scroll-thin">
      <div class="section-title">
        <BookOpen :size="14" :stroke-width="2.2" />
        <span>知识库</span>
        <span class="section-count">{{ kbStore.list.length }}</span>
      </div>
      <KnowledgeBaseList />
    </section>

    <div class="sidebar__divider" />

    <!-- ===== 对话历史 ===== -->
    <section class="sidebar__section sidebar__section--grow scroll-thin">
      <div class="section-title">
        <MessageSquare :size="14" :stroke-width="2.2" />
        <span>对话历史</span>
        <span class="section-count">{{ chat.conversations.length }}</span>
      </div>
      <ChatHistoryList />
    </section>

    <!-- ===== Footer ===== -->
    <footer class="sidebar__footer">
      <button class="user-chip" type="button" @click="handleLogout">
        <span class="user-avatar">{{ avatarText }}</span>
        <span class="user-name">{{ auth.username || '未登录' }}</span>
        <LogOut :size="16" :stroke-width="2.2" class="user-icon" />
      </button>
    </footer>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Plus, BookOpen, MessageSquare, PanelLeftClose, LogOut,
} from 'lucide-vue-next'

import BrandMark from '@/components/common/BrandMark.vue'
import KnowledgeBaseList from '@/components/knowledge/KnowledgeBaseList.vue'
import ChatHistoryList from '@/components/chat/ChatHistoryList.vue'

import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useToast } from '@/composables/useToast'

const ui = useUiStore()
const auth = useAuthStore()
const chat = useChatStore()
const kbStore = useKnowledgeBaseStore()
const toast = useToast()
const router = useRouter()

const avatarText = computed(() => {
  const u = auth.username || '?'
  return u.charAt(0).toUpperCase()
})

function handleNewChat() {
  chat.startNewConversation()
}

function handleLogout() {
  auth.logout()
  chat.startNewConversation()
  toast.info('已退出登录')
  router.push('/login')
}
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  width: var(--sidebar-w);
  height: 100%;
  min-height: 0;
}

/* ===== Header ===== */
.sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 14px 12px;
  flex-shrink: 0;
}

.sidebar__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.sidebar__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.sidebar__brand-title {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
}

.sidebar__brand-subtitle {
  font-size: 11px;
  color: var(--text-tertiary);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.sidebar__icon-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-md);
  color: var(--text-tertiary);
  transition: all var(--t-fast);
}

.sidebar__icon-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

/* ===== 新对话按钮 ===== */
.sidebar__newchat {
  padding: 4px 14px 14px;
  flex-shrink: 0;
}

.newchat-btn {
  width: 100%;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--gradient-brand);
  color: var(--text-on-brand);
  border-radius: var(--r-md);
  font-size: var(--text-md);
  font-weight: 500;
  letter-spacing: 0.02em;
  box-shadow: var(--shadow-brand);
  transition: all var(--t-fast);
}

.newchat-btn:hover {
  filter: brightness(1.05);
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(77, 110, 245, 0.4);
}

.newchat-btn:active {
  transform: translateY(0);
}

/* ===== Section 通用 ===== */
.sidebar__section {
  padding: 6px 8px;
  flex-shrink: 0;
  max-height: 40%;
  overflow-y: auto;
}

.sidebar__section--grow {
  flex: 1 1 auto;
  max-height: none;
  min-height: 0;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 8px 6px;
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-tertiary);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.section-count {
  margin-left: auto;
  font-size: 11px;
  background: var(--bg-card);
  color: var(--text-tertiary);
  border-radius: var(--r-pill);
  padding: 1px 8px;
  font-weight: 500;
  letter-spacing: 0;
  text-transform: none;
}

.sidebar__divider {
  height: 1px;
  background: var(--border-subtle);
  margin: 4px 14px;
  flex-shrink: 0;
}

/* ===== Footer ===== */
.sidebar__footer {
  padding: 12px 14px 14px;
  border-top: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border-radius: var(--r-md);
  transition: background var(--t-fast);
  text-align: left;
}

.user-chip:hover {
  background: var(--bg-hover);
}

.user-avatar {
  width: 28px;
  height: 28px;
  border-radius: var(--r-pill);
  background: var(--gradient-brand);
  color: var(--text-on-brand);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-sm);
  font-weight: 600;
  flex-shrink: 0;
}

.user-name {
  flex: 1;
  font-size: var(--text-sm);
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.user-chip:hover .user-icon {
  color: var(--color-error);
}
</style>