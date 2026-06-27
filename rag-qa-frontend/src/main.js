import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import ChatView from './views/ChatView.vue'
import KnowledgeView from './views/KnowledgeView.vue'
import LoginView from './views/LoginView.vue'
import ToastContainer from './components/common/ToastContainer.vue'
import axios from 'axios'
import './styles/tokens.css'

// Vite 支持 @ 别名指向 src 根（vite.config.js 已配置 server.proxy，无需 baseURL）
axios.defaults.baseURL = ''

const routes = [
  { path: '/', component: ChatView },
  { path: '/knowledge', component: KnowledgeView },
  { path: '/login', component: LoginView }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/')
  } else {
    next()
  }
})

axios.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

axios.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 403 || error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      router.push('/login')
    }
    return Promise.reject(error)
  }
)

const pinia = createPinia()
const app = createApp(App)

// 全局注册 Toast 组件
app.component('ToastContainer', ToastContainer)

app.use(pinia)
app.use(router)
app.mount('#app')
