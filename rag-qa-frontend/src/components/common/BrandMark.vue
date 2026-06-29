<!--
  BrandMark — 渐变圆角 logo
  - size: 像素，控制外框尺寸
  - 内部 Sparkles 图标 + "豆" 字（双元素叠加）
-->
<template>
  <div class="brand-mark" :style="{ width: size, height: size, borderRadius: radius }">
    <Sparkles :size="iconSize" :stroke-width="2.5" class="brand-mark__icon" />
    <span class="brand-mark__char">{{ char }}</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Sparkles } from 'lucide-vue-next'

const props = defineProps({
  size: { type: [Number, String], default: 36 },
  char: { type: String, default: 'R' },
  showChar: { type: Boolean, default: true },
})

const radius = computed(() => `${Math.round(Number(props.size) * 0.28)}px`)
const iconSize = computed(() => Math.round(Number(props.size) * 0.55))
</script>

<style scoped>
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  position: relative;
  background: var(--gradient-brand);
  color: var(--text-on-brand);
  box-shadow: var(--shadow-brand);
  flex-shrink: 0;
  user-select: none;
}

.brand-mark__icon {
  position: absolute;
  inset: 0;
  margin: auto;
}

.brand-mark__char {
  font-weight: 700;
  letter-spacing: -0.02em;
  z-index: 1;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.18);
  mix-blend-mode: overlay;
  opacity: 0.55;
}
</style>