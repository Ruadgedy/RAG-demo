/**
 * 知识库 store
 *
 * - list: 全部知识库
 * - currentKb: 当前选中的 KB
 * - documents: 当前 KB 的文档列表
 *
 * 注意：文档流（SSE 状态推送）由 useDocumentStream composable 订阅，
 * 它接受 documents ref 做 in-place 更新；store 只负责「选 KB → 拉文档 → 启动流」。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as kbApi from '@/api/knowledgeBase'

export const useKnowledgeBaseStore = defineStore('knowledgeBase', () => {
  const list = ref([])
  const currentKb = ref(null)
  const documents = ref([])

  const hasCurrent = computed(() => !!currentKb.value)
  const currentKbName = computed(() => currentKb.value?.name || '')

  async function fetchAll() {
    list.value = await kbApi.listKnowledgeBases()
    if (!currentKb.value && list.value.length > 0) {
      currentKb.value = list.value[0]
    }
    return list.value
  }

  async function select(kb) {
    documents.value = []
    currentKb.value = kb
    if (kb) {
      await fetchDocuments(kb.id)
    }
  }

  async function fetchDocuments(kbId) {
    documents.value = await kbApi.listDocuments(kbId)
  }

  async function create(name) {
    const kb = await kbApi.createKnowledgeBase(name)
    list.value.push(kb)
    return kb
  }

  async function uploadDoc(kbId, file) {
    const doc = await kbApi.uploadDocument(kbId, file)
    // 乐观插入到列表，避免 SSE 慢到达时出现空白
    if (doc?.id && !documents.value.some(d => d.id === doc.id)) {
      documents.value = [...documents.value, doc]
    }
    return doc
  }

  return {
    list, currentKb, documents,
    hasCurrent, currentKbName,
    fetchAll, select, fetchDocuments, create, uploadDoc,
  }
})