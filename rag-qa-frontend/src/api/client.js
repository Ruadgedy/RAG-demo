/**
 * axios 单例 + 拦截器
 *
 * 设计要点：
 * - 拦截器内**懒加载** useAuthStore，避免 api/client ↔ stores/auth 的 ESM 循环导入
 * - 401/403 触发 logout + 跳转 /login
 * - 流式接口 (POST /api/chat/stream) 必须走 raw fetch，不走本实例
 */

import axios from 'axios'

export const http = axios.create({
  baseURL: '',                  // Vite proxy: /api → http://localhost:8080
  timeout: 30_000,
})

http.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  res => res,
  async error => {
    if (error.response?.status === 401 || error.response?.status === 403) {
      // 懒加载：避免模块顶层互相 import 触发循环
      const { useAuthStore } = await import('@/stores/auth')
      const auth = useAuthStore()
      // 避免在登录页请求失败时再次跳转登录页
      if (auth.token) {
        auth.logout()
        const { default: router } = await import('@/router')
        router.push('/login')
      }
    }
    return Promise.reject(error)
  }
)

export default http