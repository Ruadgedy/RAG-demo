/**
 * 认证 store
 * - 单一信源：localStorage 持久化 token/username
 * - App.vue onMounted 调用 bootstrap() 从 localStorage 水合
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')

  const isAuthenticated = computed(() => !!token.value)

  function bootstrap() {
    token.value = localStorage.getItem('token') || ''
    username.value = localStorage.getItem('username') || ''
  }

  async function login(payload) {
    const data = await authApi.login(payload)
    if (data?.token) {
      token.value = data.token
      username.value = data.username || payload.username
      localStorage.setItem('token', data.token)
      localStorage.setItem('username', username.value)
    }
    return data
  }

  async function register(payload) {
    return authApi.register(payload)
  }

  function logout() {
    token.value = ''
    username.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('username')
  }

  return { token, username, isAuthenticated, bootstrap, login, register, logout }
})