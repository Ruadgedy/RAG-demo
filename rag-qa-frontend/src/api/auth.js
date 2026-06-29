/**
 * 认证相关 API（不走拦截器实例，用裸 axios，避免 401 触发登出跳转）
 */
import axios from 'axios'

const BASE = '/api/auth'

export function login(payload) {
  return axios.post(`${BASE}/login`, payload).then(r => r.data)
}

export function register(payload) {
  return axios.post(`${BASE}/register`, payload).then(r => r.data)
}