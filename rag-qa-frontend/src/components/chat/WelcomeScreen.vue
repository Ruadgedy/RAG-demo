<!--
  WelcomeScreen — 欢迎屏
  - 大标题：你好，我是「RAG 智能助手」（关键词渐变）
  - 副标题：基于你的知识库回答问题…
  - 2x2 提问建议卡片
  - 「卡片点击 → 写入输入框 + 自动聚焦」由 emit('pick', text) 上抛
-->
<template>
  <div class="welcome anim-fade-up">
    <div class="welcome__head">
      <BrandMark :size="72" char="R" />
      <h1 class="welcome__title">
        你好，我是 <span class="text-gradient">RAG 智能助手</span>
      </h1>
      <p class="welcome__sub">基于你的知识库回答问题，先选择一个知识库开始吧</p>
    </div>

    <div class="welcome__grid">
      <button
        v-for="(card, i) in cards"
        :key="i"
        class="suggest-card"
        type="button"
        @click="$emit('pick', card.prompt)"
      >
        <span class="suggest-card__icon" :style="{ background: card.bg }">
          <component :is="card.icon" :size="18" :stroke-width="2.2" />
        </span>
        <span class="suggest-card__title">{{ card.title }}</span>
        <span class="suggest-card__desc">{{ card.desc }}</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import {
  FileText, ListChecks, GitCompareArrows, AlertTriangle,
} from 'lucide-vue-next'
import BrandMark from '@/components/common/BrandMark.vue'

defineEmits(['pick'])

const cards = [
  {
    title: '总结关键观点',
    desc: '从文档中提取核心论点',
    prompt: '请总结这篇文档的关键观点，并列出 3-5 条要点',
    icon: ListChecks,
    bg: 'linear-gradient(135deg, rgba(77,110,245,0.12), rgba(123,91,245,0.12))',
  },
  {
    title: '提取结构化数据',
    desc: '把表格/列表抽出来',
    prompt: '请提取文档中所有的表格数据和列表项，按结构化格式输出',
    icon: FileText,
    bg: 'linear-gradient(135deg, rgba(16,197,130,0.12), rgba(77,110,245,0.08))',
  },
  {
    title: '对比两个版本',
    desc: '找出差异与变化',
    prompt: '请对比文档中提到的两个版本之间的主要差异，逐条说明',
    icon: GitCompareArrows,
    bg: 'linear-gradient(135deg, rgba(245,158,11,0.12), rgba(239,68,68,0.08))',
  },
  {
    title: '列出潜在风险',
    desc: '从内容里识别风险点',
    prompt: '请基于文档内容，列出潜在的风险点和需要注意的问题',
    icon: AlertTriangle,
    bg: 'linear-gradient(135deg, rgba(239,68,68,0.10), rgba(245,158,11,0.10))',
  },
]
</script>

<style scoped>
.welcome {
  max-width: var(--welcome-max-w);
  margin: 0 auto;
  padding: 48px 32px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.welcome__head {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 14px;
  margin-bottom: 36px;
}

.welcome__title {
  font-size: var(--text-3xl);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text-primary);
  margin: 0;
}

.welcome__sub {
  font-size: var(--text-md);
  color: var(--text-secondary);
  margin: 0;
}

/* ===== 2×2 卡片 ===== */
.welcome__grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
  width: 100%;
}

.suggest-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 18px 18px 16px;
  background: var(--bg-page);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  text-align: left;
  transition: all var(--t-normal);
  position: relative;
  overflow: hidden;
}

.suggest-card::after {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--gradient-brand-soft);
  opacity: 0;
  transition: opacity var(--t-normal);
  pointer-events: none;
}

.suggest-card > * { position: relative; z-index: 1; }

.suggest-card:hover {
  border-color: var(--border-brand);
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.suggest-card:hover::after { opacity: 1; }

.suggest-card__icon {
  width: 36px;
  height: 36px;
  border-radius: var(--r-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--brand);
}

.suggest-card__title {
  font-size: var(--text-md);
  font-weight: 500;
  color: var(--text-primary);
}

.suggest-card__desc {
  font-size: var(--text-xs);
  color: var(--text-tertiary);
  line-height: 1.5;
}

@media (max-width: 640px) {
  .welcome__grid { grid-template-columns: 1fr; }
}
</style>