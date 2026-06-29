/**
 * Vue Router 配置
 * - /          → ChatView（受保护）
 * - /knowledge → KnowledgeView（占位，受保护）
 * - /login     → LoginView
 *
 * 路由守卫：未登录 → /login；已登录访问 /login → /
 */
import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import KnowledgeView from '@/views/KnowledgeView.vue'
import LoginView from '@/views/LoginView.vue'

const routes = [
  { path: '/', component: ChatView, meta: { requiresAuth: true } },
  { path: '/knowledge', component: KnowledgeView, meta: { requiresAuth: true } },
  { path: '/login', component: LoginView, meta: { guest: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) return { path: '/login' }
  if (to.meta.guest && token) return { path: '/' }
  return true
})

export default router