<template>
  <div class="app-container">
    <div class="sidebar">
      <!-- 知识库区域 -->
      <div class="sidebar-section kb-section">
        <div class="sidebar-header">
          <h2>知识库</h2>
          <button class="btn-new" @click="showCreateModal = true">
            <span>+</span> 新建
          </button>
        </div>
        <div class="knowledge-list">
          <div 
            v-for="kb in knowledgeBases" 
            :key="kb.id"
            class="knowledge-item-wrapper"
          >
            <div 
              class="knowledge-item"
              :class="{ active: currentKb?.id === kb.id }"
              @click="selectKnowledgeBase(kb)"
            >
              <div class="kb-icon">📚</div>
              <div class="kb-info">
                <div class="kb-name">{{ kb.name }}</div>
                <div class="kb-time">{{ formatTime(kb.createdAt) }}</div>
              </div>
              <button 
                class="btn-upload" 
                @click.stop="showUploadModal = true; uploadKbId = kb.id"
              >
                上传
              </button>
            </div>
            <div v-if="currentKb?.id === kb.id && documents.length > 0" class="document-list">
              <div class="document-list-title">文档列表</div>
              <div v-for="doc in documents" :key="doc.id" class="document-item">
                <div class="document-info">
                  <div class="document-name">{{ doc.fileName }}</div>
                  <div class="document-status" :class="{'status-failed': isStatusFailed(doc.status), 'status-completed': doc.status === 'COMPLETED'}">
                    {{ getStatusText(doc.status) }}
                    <span v-if="doc.progress && doc.status !== 'COMPLETED' && !isStatusFailed(doc.status)">({{ doc.progress }}%)</span>
                  </div>
                  <div v-if="doc.progress && doc.status !== 'COMPLETED' && !isStatusFailed(doc.status)" class="progress-bar">
                    <div class="progress-fill" :style="{width: doc.progress + '%'}"></div>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div v-if="knowledgeBases.length === 0" class="empty-tip">
            暂无知识库，点击新建
          </div>
        </div>
      </div>
      
      <!-- 聊天历史区域 -->
      <div class="sidebar-section history-section">
        <div class="sidebar-header">
          <h2>聊天历史</h2>
        </div>
        <div class="chat-history-list">
          <div 
            v-for="session in sessions" 
            :key="session.sessionId"
            class="history-item"
            :class="{ active: currentSession === session.sessionId }"
            @click="loadSession(session.sessionId)"
          >
            <div class="history-icon">💬</div>
            <div class="history-info">
              <div class="history-title">{{ session.title }}</div>
              <div class="history-time">{{ formatTime(session.lastTime) }}</div>
            </div>
          </div>
          <div v-if="sessions.length === 0" class="empty-tip">
            暂无聊天记录
          </div>
        </div>
      </div>
    </div>
    
    <div class="main">
      <div class="chat-area">
        <div class="messages" ref="messagesRef">
          <div v-if="!currentKb" class="welcome-tip">
            <div class="welcome-icon">🤖</div>
            <h3>欢迎使用RAG智能问答</h3>
            <p>请先选择或创建一个知识库开始使用</p>
          </div>
          <template v-else>
            <div 
              v-for="(msg, index) in messages" 
              :key="index"
              class="message"
              :class="msg.role"
            >
              <div class="avatar">{{ msg.role === 'user' ? '你' : 'AI' }}</div>
              <div class="content" v-html="renderMarkdown(msg.content)"></div>
            </div>
            <div v-if="loading" class="message assistant">
              <div class="avatar">AI</div>
              <div class="content loading">
                <span class="dot">.</span>
                <span class="dot">.</span>
                <span class="dot">.</span>
              </div>
            </div>
          </template>
        </div>
        
        <div class="input-area" v-if="currentKb">
          <textarea 
            v-model="inputMessage" 
            placeholder="请输入您的问题..."
            @keydown.enter.exact.prevent="sendMessage"
            :disabled="loading"
          ></textarea>
          <button class="btn-send" @click="sendMessage" :disabled="loading || !inputMessage.trim()">
            发送
          </button>
        </div>
      </div>
    </div>
    
    <!-- 创建知识库弹窗 -->
    <div v-if="showCreateModal" class="modal-overlay" @click.self="showCreateModal = false">
      <div class="modal">
        <div class="modal-header">
          <h3>新建知识库</h3>
          <button class="btn-close" @click="showCreateModal = false">×</button>
        </div>
        <div class="modal-body">
          <input 
            v-model="newKbName" 
            type="text" 
            placeholder="输入知识库名称"
            @keydown.enter="createKnowledgeBase"
          />
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showCreateModal = false">取消</button>
          <button class="btn-confirm" @click="createKnowledgeBase" :disabled="!newKbName.trim()">创建</button>
        </div>
      </div>
    </div>
    
    <!-- 上传文档弹窗 -->
    <div v-if="showUploadModal" class="modal-overlay" @click.self="showUploadModal = false">
      <div class="modal">
        <div class="modal-header">
          <h3>上传文档</h3>
          <button class="btn-close" @click="showUploadModal = false">×</button>
        </div>
        <div class="modal-body">
          <p class="upload-tip">支持 PDF、Word(.docx)、TXT 格式</p>
          <input type="file" accept=".pdf,.docx,.txt" @change="handleFileSelect" />
          <p v-if="uploadFile" class="file-name">{{ uploadFile.name }}</p>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showUploadModal = false">取消</button>
          <button class="btn-confirm" @click="uploadDocument" :disabled="!uploadFile || uploading">
            {{ uploading ? '上传中...' : '上传' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import axios from 'axios'
import { marked } from 'marked'

const knowledgeBases = ref([])
const currentKb = ref(null)
const messages = ref([])
const documents = ref([])
const inputMessage = ref('')
const loading = ref(false)
const messagesRef = ref(null)
const showCreateModal = ref(false)
const newKbName = ref('')
const showUploadModal = ref(false)
const uploadKbId = ref(null)
const uploadFile = ref(null)
const uploading = ref(false)
const sessions = ref([])
const currentSession = ref(null)

const API_BASE = 'http://localhost:8080/api'

const loadKnowledgeBases = async () => {
  try {
    const res = await axios.get(`${API_BASE}/knowledge-bases`)
    knowledgeBases.value = res.data
    if (knowledgeBases.value.length > 0 && !currentKb.value) {
      selectKnowledgeBase(knowledgeBases.value[0])
    }
  } catch (e) {
    console.error('加载知识库失败:', e)
  }
}

const selectKnowledgeBase = (kb) => {
  currentKb.value = kb
  messages.value = []
  loadDocuments(kb.id)
  startDocPolling(kb.id)
}

let docPollInterval = null

const startDocPolling = (kbId) => {
  if (docPollInterval) clearInterval(docPollInterval)
  docPollInterval = setInterval(() => {
    loadDocuments(kbId)
  }, 2000)
}

const isStillProcessing = (status) => {
  return status === 'UPLOADING' || status === 'PARSING' || status === 'CHUNKING' || status === 'EMBEDDING'
}

const loadDocuments = async (kbId) => {
  try {
    const res = await axios.get(`${API_BASE}/knowledge-bases/${kbId}/documents`)
    documents.value = res.data
    
    const hasProcessing = documents.value.some(d => isStillProcessing(d.status))
    if (!hasProcessing && docPollInterval) {
      clearInterval(docPollInterval)
      docPollInterval = null
    }
  } catch (e) {
    console.error('加载文档失败:', e)
  }
}

const loadSessions = async () => {
  try {
    const res = await axios.get(`${API_BASE}/chat-history`)
    const history = res.data
    const sessionMap = {}
    history.forEach(h => {
      if (!sessionMap[h.sessionId]) {
        sessionMap[h.sessionId] = { 
          sessionId: h.sessionId, 
          title: h.content.substring(0, 20) + (h.content.length > 20 ? '...' : ''),
          lastTime: h.createdAt
        }
      }
    })
    sessions.value = Object.values(sessionMap).sort((a, b) => 
      new Date(b.lastTime) - new Date(a.lastTime)
    )
  } catch (e) {
    console.error('加载聊天历史失败:', e)
  }
}

const loadSession = async (sessionId) => {
  currentSession.value = sessionId
  try {
    const res = await axios.get(`${API_BASE}/chat-history/${sessionId}`)
    messages.value = res.data.map(h => ({ role: h.role, content: h.content }))
  } catch (e) {
    console.error('加载会话失败:', e)
  }
}

const getStatusText = (status) => {
  const map = {
    UPLOADING: '上传中',
    UPLOAD_FAILED: '上传失败',
    PARSING: '解析中',
    PARSE_FAILED: '解析失败',
    CHUNKING: '切片中',
    CHUNK_FAILED: '切片失败',
    EMBEDDING: '向量化中',
    EMBEDDING_FAILED: '向量化失败',
    COMPLETED: '已完成',
    FAILED: '失败'
  }
  return map[status] || status
}

const isStatusFailed = (status) => {
  return status.includes('FAILED') || status === 'FAILED'
}

const createKnowledgeBase = async () => {
  if (!newKbName.value.trim()) return
  try {
    const res = await axios.post(`${API_BASE}/knowledge-bases`, {
      name: newKbName.value
    })
    knowledgeBases.value.push(res.data)
    selectKnowledgeBase(res.data)
    showCreateModal.value = false
    newKbName.value = ''
  } catch (e) {
    alert('创建失败: ' + e.message)
  }
}

const handleFileSelect = (event) => {
  const file = event.target.files[0]
  if (file) {
    uploadFile.value = file
  }
}

const uploadDocument = async () => {
  if (!uploadFile.value || !uploadKbId.value) return
  
  uploading.value = true
  const formData = new FormData()
  formData.append('file', uploadFile.value)
  
  try {
    await axios.post(`${API_BASE}/knowledge-bases/${uploadKbId.value}/documents`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    alert('文档上传成功！')
    showUploadModal.value = false
    uploadFile.value = null
    loadDocuments(uploadKbId.value)
    uploadKbId.value = null
  } catch (e) {
    alert('上传失败: ' + e.message)
  }
  
  uploading.value = false
}

const sendMessage = async () => {
  if (!inputMessage.value.trim() || loading.value || !currentKb.value) return
  
  const userMsg = inputMessage.value
  messages.value.push({ role: 'user', content: userMsg })
  inputMessage.value = ''
  loading.value = true
  
  await nextTick()
  scrollToBottom()
  
  try {
    const res = await axios.post(`${API_BASE}/chat`, {
      message: userMsg,
      knowledgeBaseId: currentKb.value.id,
      history: messages.value.slice(0, -1).map(m => ({ role: m.role, content: m.content }))
    })
    messages.value.push({ role: 'assistant', content: res.data })
  } catch (e) {
    messages.value.push({ role: 'assistant', content: '抱歉，请求失败: ' + e.message })
  }
  
  loading.value = false
  await nextTick()
  scrollToBottom()
}

const scrollToBottom = () => {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

const formatTime = (time) => {
  if (!time) return ''
  return new Date(time).toLocaleDateString('zh-CN')
}

const renderMarkdown = (content) => {
  return marked(content || '')
}

onMounted(() => {
  loadKnowledgeBases()
  loadSessions()
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.app-container {
  display: flex;
  width: 100vw;
  height: 100vh;
  background: #f5f5f5;
}

.sidebar {
  width: 260px;
  background: #fff;
  border-right: 1px solid #e5e7eb;
  display: flex;
  flex-direction: column;
}

.sidebar-section {
  display: flex;
  flex-direction: column;
}

.kb-section {
  flex: 1;
  min-height: 200px;
  overflow: hidden;
}

.history-section {
  border-top: 1px solid #e5e7eb;
  max-height: 250px;
}

.chat-history-list {
  flex: 1;
  overflow-y: auto;
}

.history-item {
  display: flex;
  align-items: center;
  padding: 12px 20px;
  cursor: pointer;
  transition: background 0.2s;
}

.history-item:hover {
  background: #f3f4f6;
}

.history-item.active {
  background: #dbeafe;
}

.history-icon {
  font-size: 16px;
  margin-right: 10px;
}

.history-title {
  font-size: 13px;
  color: #374151;
}

.history-time {
  font-size: 11px;
  color: #9ca3af;
  margin-top: 2px;
}

.sidebar-header {
  padding: 20px;
  border-bottom: 1px solid #e5e7eb;
}

.sidebar-header h2 {
  font-size: 18px;
  font-weight: 600;
  color: #111827;
  margin-bottom: 12px;
}

.btn-new {
  width: 100%;
  padding: 10px;
  background: #3b82f6;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-new:hover {
  background: #2563eb;
}

.knowledge-list {
  flex: 1;
  overflow-y: auto;
}

.knowledge-item {
  display: flex;
  align-items: center;
  padding: 14px 20px;
  cursor: pointer;
  transition: background 0.2s;
  border-left: 3px solid transparent;
}

.knowledge-item-wrapper {
  border-bottom: 1px solid #f3f4f6;
}

.knowledge-item:hover {
  background: #f3f4f6;
}

.knowledge-item.active {
  background: #dbeafe;
  border-left-color: #3b82f6;
}

.kb-icon {
  font-size: 20px;
  margin-right: 12px;
}

.kb-name {
  font-size: 14px;
  color: #374151;
}

.kb-time {
  font-size: 12px;
  color: #9ca3af;
  margin-top: 2px;
}

.document-list {
  border-top: 1px solid #e5e7eb;
  padding: 12px 20px;
  max-height: 150px;
  overflow-y: auto;
  background: #fafafa;
}

.document-list-title {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 8px;
}

.document-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 6px 8px;
  border-radius: 4px;
  margin-bottom: 4px;
  background: #fff;
}

.document-info {
  flex: 1;
  min-width: 0;
}

.document-name {
  font-size: 12px;
  color: #374151;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 140px;
}

.document-status {
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.progress-bar {
  width: 100%;
  height: 4px;
  background: #e5e7eb;
  border-radius: 2px;
  margin-top: 4px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #3b82f6;
  transition: width 0.3s ease;
}

.status-pending { color: #f59e0b; }
.status-processing { color: #3b82f6; }
.status-completed { color: #10b981; }
.status-failed { color: #ef4444; }

.btn-upload {
  background: #f3f4f6;
  border: 1px solid #d1d5db;
  cursor: pointer;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 4px;
  color: #374151;
}

.btn-upload:hover {
  background: #e5e7eb;
}

.empty-tip {
  padding: 40px 20px;
  text-align: center;
  color: #9ca3af;
  font-size: 14px;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.messages {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
}

.welcome-tip {
  text-align: center;
  padding-top: 100px;
}

.welcome-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.welcome-tip h3 {
  font-size: 20px;
  color: #111827;
  margin-bottom: 8px;
}

.welcome-tip p {
  color: #6b7280;
  font-size: 14px;
}

.message {
  display: flex;
  margin-bottom: 20px;
}

.message.user {
  flex-direction: row-reverse;
}

.message .avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #6b7280;
  flex-shrink: 0;
}

.message.assistant .avatar {
  background: #3b82f6;
  color: #fff;
}

.message .content {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  margin: 0 12px;
}

.message.user .content {
  background: #3b82f6;
  color: #fff;
}

.message.assistant .content {
  background: #f3f4f6;
  color: #111827;
}

.message.assistant .content code {
  background: #e5e7eb;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'JetBrains Mono', monospace;
}

.message.assistant .content pre {
  background: #1f2937;
  color: #e5e7eb;
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
}

.loading .dot {
  animation: blink 1.4s infinite;
}

.loading .dot:nth-child(2) {
  animation-delay: 0.2s;
}

.loading .dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 60%, 100% { opacity: 0; }
  30% { opacity: 1; }
}

.input-area {
  padding: 16px 20px;
  border-top: 1px solid #e5e7eb;
  display: flex;
  gap: 12px;
}

.input-area textarea {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 14px;
  resize: none;
  font-family: inherit;
}

.input-area textarea:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.btn-send {
  padding: 12px 24px;
  background: #3b82f6;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-send:hover:not(:disabled) {
  background: #2563eb;
}

.btn-send:disabled {
  background: #93c5fd;
  cursor: not-allowed;
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal {
  background: #fff;
  border-radius: 12px;
  width: 400px;
  overflow: hidden;
}

.modal-header {
  padding: 16px 20px;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.modal-header h3 {
  font-size: 16px;
  font-weight: 600;
}

.btn-close {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: #9ca3af;
}

.modal-body {
  padding: 20px;
}

.modal-body input {
  width: 100%;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 14px;
}

.modal-body input:focus {
  outline: none;
  border-color: #3b82f6;
}

.modal-footer {
  padding: 16px 20px;
  border-top: 1px solid #e5e7eb;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.btn-cancel, .btn-confirm {
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
}

.btn-cancel {
  background: #f3f4f6;
  border: none;
  color: #374151;
}

.btn-confirm {
  background: #3b82f6;
  border: none;
  color: #fff;
}

.btn-confirm:disabled {
  background: #93c5fd;
  cursor: not-allowed;
}

.upload-tip {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 12px;
}

.modal-body input[type="file"] {
  padding: 8px 0;
}

.file-name {
  margin-top: 8px;
  font-size: 14px;
  color: #374151;
}
</style>
