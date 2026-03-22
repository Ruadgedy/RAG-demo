<template>
  <div class="login-container">
    <div class="login-box">
      <h1>RAG智能问答系统</h1>
      <div class="tabs">
        <button :class="{ active: isLogin }" @click="isLogin = true">登录</button>
        <button :class="{ active: !isLogin }" @click="isLogin = false">注册</button>
      </div>
      
      <form @submit.prevent="handleSubmit">
        <div class="form-group">
          <input 
            v-model="form.username" 
            type="text" 
            placeholder="用户名" 
            required
          />
        </div>
        <div class="form-group">
          <input 
            v-model="form.password" 
            type="password" 
            placeholder="密码" 
            required
          />
        </div>
        <div v-if="!isLogin" class="form-group">
          <input 
            v-model="form.email" 
            type="email" 
            placeholder="邮箱（可选）"
          />
        </div>
        
        <div v-if="error" class="error">{{ error }}</div>
        
        <button type="submit" class="submit-btn" :disabled="loading">
          {{ loading ? '处理中...' : (isLogin ? '登录' : '注册') }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'

const router = useRouter()
const isLogin = ref(true)
const loading = ref(false)
const error = ref('')

const form = reactive({
  username: '',
  password: '',
  email: ''
})

const handleSubmit = async () => {
  error.value = ''
  loading.value = true
  
  try {
    const endpoint = isLogin.value ? '/api/auth/login' : '/api/auth/register'
    const data = isLogin.value 
      ? { username: form.username, password: form.password }
      : { username: form.username, password: form.password, email: form.email }
    
    const response = await axios.post(endpoint, data)
    
    if (response.data.token) {
      localStorage.setItem('token', response.data.token)
      localStorage.setItem('username', response.data.username)
      router.push('/')
    }
  } catch (err) {
    error.value = err.response?.data?.error || err.response?.data?.message || '操作失败，请重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  width: 100vw;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-box {
  background: white;
  padding: 40px;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.2);
  width: 360px;
}

h1 {
  text-align: center;
  color: #333;
  margin-bottom: 30px;
  font-size: 24px;
}

.tabs {
  display: flex;
  margin-bottom: 24px;
  border-bottom: 2px solid #eee;
}

.tabs button {
  flex: 1;
  padding: 12px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 16px;
  color: #666;
  transition: all 0.3s;
}

.tabs button.active {
  color: #667eea;
  border-bottom: 2px solid #667eea;
  margin-bottom: -2px;
}

.form-group {
  margin-bottom: 16px;
}

input {
  width: 100%;
  padding: 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}

input:focus {
  outline: none;
  border-color: #667eea;
}

.submit-btn {
  width: 100%;
  padding: 12px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
  transition: opacity 0.3s;
}

.submit-btn:hover {
  opacity: 0.9;
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error {
  color: #e74c3c;
  font-size: 14px;
  margin-bottom: 16px;
  text-align: center;
}
</style>
