<!--
  UploadDocModal — 上传文档模态
  支持拖拽 + 点击选择
-->
<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="show" class="modal-overlay" @click.self="close">
        <div class="modal">
          <header class="modal-header">
            <h3>上传文档</h3>
            <button class="modal-close" type="button" aria-label="关闭" @click="close">
              <X :size="18" :stroke-width="2.2" />
            </button>
          </header>

          <div class="modal-body">
            <p class="modal-hint-top">支持 PDF / Word(.docx) / TXT 格式</p>

            <label
              class="dropzone"
              :class="{ active: dragOver, has: file }"
              @dragenter.prevent="dragOver = true"
              @dragover.prevent="dragOver = true"
              @dragleave.prevent="dragOver = false"
              @drop.prevent="onDrop"
            >
              <input
                type="file"
                accept=".pdf,.docx,.txt"
                class="dropzone-input"
                @change="onChange"
              />
              <div v-if="!file" class="dropzone-empty">
                <UploadCloud :size="32" :stroke-width="1.6" />
                <p class="dz-title">点击或拖拽文件到这里</p>
                <p class="dz-sub">单个文件 ≤ 50MB</p>
              </div>
              <div v-else class="dropzone-file">
                <FileText :size="24" :stroke-width="1.8" />
                <div class="file-info">
                  <div class="file-name">{{ file.name }}</div>
                  <div class="file-size">{{ formatSize(file.size) }}</div>
                </div>
                <button class="file-remove" type="button" @click.prevent="file = null">
                  <X :size="16" :stroke-width="2.4" />
                </button>
              </div>
            </label>

            <div v-if="progress > 0 && progress < 100" class="progress-bar">
              <div class="progress-fill" :style="{ width: progress + '%' }" />
            </div>
          </div>

          <footer class="modal-footer">
            <button class="btn-ghost" type="button" @click="close">取消</button>
            <button
              class="btn-primary"
              type="button"
              :disabled="!file || uploading"
              @click="submit"
            >
              {{ uploading ? `上传中 ${progress}%` : '上传' }}
            </button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch } from 'vue'
import { X, UploadCloud, FileText } from 'lucide-vue-next'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useToast } from '@/composables/useToast'

const props = defineProps({
  show: Boolean,
  kbId: { type: [String, Number], default: null },
})

const emit = defineEmits(['update:show'])

const kbStore = useKnowledgeBaseStore()
const toast = useToast()

const file = ref(null)
const uploading = ref(false)
const progress = ref(0)
const dragOver = ref(false)

watch(() => props.show, (v) => {
  if (v) {
    file.value = null
    progress.value = 0
    dragOver.value = false
  }
})

function close() { emit('update:show', false) }

function onChange(e) {
  const f = e.target.files?.[0]
  if (f) file.value = f
}

function onDrop(e) {
  dragOver.value = false
  const f = e.dataTransfer.files?.[0]
  if (f) file.value = f
}

function formatSize(b) {
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  return (b / 1024 / 1024).toFixed(2) + ' MB'
}

async function submit() {
  if (!file.value || !props.kbId || uploading.value) return
  uploading.value = true
  progress.value = 0
  // 模拟进度：上传接口本身不返回进度条，用渐进 setInterval 提升 UX
  const timer = setInterval(() => {
    if (progress.value < 90) progress.value += 8
  }, 200)

  try {
    await kbStore.uploadDoc(props.kbId, file.value)
    progress.value = 100
    toast.success('文档上传成功，正在处理…')
    close()
  } catch (e) {
    toast.error('上传失败：' + (e.response?.data?.error || e.message), 5000)
  } finally {
    clearInterval(timer)
    uploading.value = false
    progress.value = 0
  }
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: var(--bg-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: var(--z-modal);
  backdrop-filter: blur(4px);
}

.modal {
  width: 460px;
  background: var(--bg-page);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-xl);
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
}

.modal-header h3 {
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--text-primary);
}

.modal-close {
  width: 32px;
  height: 32px;
  border-radius: var(--r-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
  transition: all var(--t-fast);
}

.modal-close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.modal-body { padding: 4px 20px 12px; }

.modal-hint-top {
  font-size: var(--text-xs);
  color: var(--text-tertiary);
  margin-bottom: 12px;
}

.dropzone {
  position: relative;
  display: block;
  border: 2px dashed var(--border-strong);
  border-radius: var(--r-lg);
  padding: 24px;
  text-align: center;
  cursor: pointer;
  transition: all var(--t-fast);
  background: var(--bg-card);
}

.dropzone:hover { border-color: var(--brand); }

.dropzone.active {
  border-color: var(--brand);
  background: var(--brand-soft);
}

.dropzone.has {
  border-style: solid;
  border-color: var(--brand);
  background: var(--bg-page);
}

.dropzone-input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.dropzone-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--text-tertiary);
}

.dz-title {
  font-size: var(--text-sm);
  color: var(--text-secondary);
  margin: 0;
}

.dz-sub {
  font-size: 11px;
  color: var(--text-tertiary);
  margin: 0;
}

.dropzone-file {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--brand);
}

.file-info {
  flex: 1;
  text-align: left;
  min-width: 0;
}

.file-name {
  font-size: var(--text-sm);
  color: var(--text-primary);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-size {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 2px;
}

.file-remove {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
  transition: all var(--t-fast);
}

.file-remove:hover {
  background: var(--bg-hover);
  color: var(--color-error);
}

.progress-bar {
  margin-top: 14px;
  height: 4px;
  background: var(--bg-card);
  border-radius: var(--r-pill);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--gradient-brand);
  border-radius: var(--r-pill);
  transition: width var(--t-normal);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 20px 18px;
  border-top: 1px solid var(--border-subtle);
}

.btn-ghost {
  height: 36px;
  padding: 0 16px;
  font-size: var(--text-sm);
  color: var(--text-secondary);
  border-radius: var(--r-md);
}

.btn-ghost:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.btn-primary {
  height: 36px;
  padding: 0 18px;
  font-size: var(--text-sm);
  font-weight: 500;
  background: var(--gradient-brand);
  color: var(--text-on-brand);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-brand);
}

.btn-primary:hover:not(:disabled) { filter: brightness(1.05); }
.btn-primary:disabled { filter: grayscale(0.4); opacity: 0.6; }

/* ===== 模态过渡 ===== */
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-from .modal, .modal-leave-to .modal { transform: translateY(8px) scale(0.98); }
.modal-enter-active, .modal-leave-active { transition: opacity var(--t-normal); }
.modal-enter-active .modal, .modal-leave-active .modal { transition: transform var(--t-normal); }
.modal-enter-to .modal, .modal-leave-from .modal { transform: translateY(0) scale(1); }
</style>