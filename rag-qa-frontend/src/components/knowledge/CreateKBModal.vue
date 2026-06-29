<!--
  CreateKBModal — 创建知识库模态
-->
<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="show" class="modal-overlay" @click.self="close">
        <div class="modal">
          <header class="modal-header">
            <h3>新建知识库</h3>
            <button class="modal-close" type="button" aria-label="关闭" @click="close">
              <X :size="18" :stroke-width="2.2" />
            </button>
          </header>

          <div class="modal-body">
            <label class="modal-label">知识库名称</label>
            <input
              ref="inputRef"
              v-model="name"
              class="modal-input"
              type="text"
              placeholder="例如：产品手册、合同模板…"
              maxlength="40"
              @keydown.enter="submit"
            />
            <p class="modal-hint">创建后可以上传 PDF / Word / TXT 文档</p>
          </div>

          <footer class="modal-footer">
            <button class="btn-ghost" type="button" @click="close">取消</button>
            <button
              class="btn-primary"
              type="button"
              :disabled="!name.trim() || loading"
              @click="submit"
            >
              {{ loading ? '创建中…' : '创建' }}
            </button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { X } from 'lucide-vue-next'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useToast } from '@/composables/useToast'

const props = defineProps({ show: Boolean })
const emit = defineEmits(['update:show'])

const kbStore = useKnowledgeBaseStore()
const toast = useToast()

const name = ref('')
const loading = ref(false)
const inputRef = ref(null)

watch(() => props.show, async (v) => {
  if (v) {
    name.value = ''
    await nextTick()
    inputRef.value?.focus()
  }
})

function close() {
  emit('update:show', false)
}

async function submit() {
  const n = name.value.trim()
  if (!n || loading.value) return
  loading.value = true
  try {
    const kb = await kbStore.create(n)
    await kbStore.select(kb)
    toast.success('知识库创建成功')
    close()
  } catch (e) {
    toast.error('创建失败：' + (e.response?.data?.error || e.message))
  } finally {
    loading.value = false
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
  width: 420px;
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

.modal-body {
  padding: 4px 20px 16px;
}

.modal-label {
  display: block;
  font-size: var(--text-sm);
  color: var(--text-secondary);
  margin-bottom: 8px;
}

.modal-input {
  width: 100%;
  height: 42px;
  padding: 0 14px;
  background: var(--bg-card);
  border: 1px solid transparent;
  border-radius: var(--r-md);
  font-size: var(--text-md);
  color: var(--text-primary);
  transition: all var(--t-fast);
}

.modal-input::placeholder {
  color: var(--text-tertiary);
}

.modal-input:focus {
  background: var(--bg-page);
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-soft);
}

.modal-hint {
  margin-top: 8px;
  font-size: var(--text-xs);
  color: var(--text-tertiary);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px 18px;
  border-top: 1px solid var(--border-subtle);
}

.btn-ghost {
  height: 36px;
  padding: 0 16px;
  font-size: var(--text-sm);
  color: var(--text-secondary);
  border-radius: var(--r-md);
  transition: all var(--t-fast);
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
  transition: all var(--t-fast);
}

.btn-primary:hover:not(:disabled) {
  filter: brightness(1.05);
}

.btn-primary:disabled {
  filter: grayscale(0.4);
  opacity: 0.6;
}

/* ===== 模态过渡 ===== */
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .modal,
.modal-leave-to .modal {
  transform: translateY(8px) scale(0.98);
}
.modal-enter-active,
.modal-leave-active {
  transition: opacity var(--t-normal);
}
.modal-enter-active .modal,
.modal-leave-active .modal {
  transition: transform var(--t-normal);
}
.modal-enter-to .modal,
.modal-leave-from .modal {
  transform: translateY(0) scale(1);
}
</style>