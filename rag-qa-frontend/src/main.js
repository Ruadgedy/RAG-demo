/**
 * 应用入口
 * - 创建 Pinia + Router
 * - 全局注册 ToastContainer 组件
 * - 引入 tokens.css + doubao.css（顺序：tokens 先于 doubao 兜底变量）
 *
 * 【2026-06-29 调试补丁】装上全局错误捕获并显示到 DOM 上
 * 原因：之前登录后空白页，无法从外部定位。挂上 errorHandler 后，
 *       任何 render 错误 / 异步异常都会写到 #app 里让用户能看到。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import ToastContainer from './components/common/ToastContainer.vue'

import './styles/tokens.css'
import './styles/doubao.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// 全局注册 Toast 组件（在 App.vue 中用 <ToastContainer /> 渲染）
app.component('ToastContainer', ToastContainer)

// ===== 调试用：错误展示器 =====
function showBootError(title, detail) {
  const el = document.getElementById('app')
  if (!el) return
  el.innerHTML = `
    <div style="position:fixed;inset:0;display:flex;align-items:center;justify-content:center;
                background:#fff;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;
                padding:24px;z-index:99999;">
      <div style="max-width:720px;background:#fff5f5;border:2px solid #f54a45;border-radius:12px;
                  padding:20px 24px;color:#14151A;">
        <div style="font-size:18px;font-weight:600;color:#f54a45;margin-bottom:8px;">⚠️ ${title}</div>
        <pre style="margin:0;font-size:12px;line-height:1.5;white-space:pre-wrap;color:#374151;
                    max-height:60vh;overflow:auto;">${String(detail).replace(/</g, '&lt;')}</pre>
      </div>
    </div>
  `
}

app.config.errorHandler = (err, instance, info) => {
  console.error('[Vue error]', err, info)
  showBootError('Vue render error: ' + info, (err && (err.stack || err.message)) || String(err))
}

window.addEventListener('error', e => {
  console.error('[window.error]', e.error || e.message)
  showBootError('JS error: ' + (e.message || 'unknown'), e.error?.stack || e.message)
})

window.addEventListener('unhandledrejection', e => {
  console.error('[unhandledrejection]', e.reason)
  showBootError('Unhandled promise rejection', e.reason?.stack || e.reason?.message || String(e.reason))
})

app.mount('#app')