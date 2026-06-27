<template>
  <div class="app">
    <router-view />
    <!-- 【2026-06-27 增量】全局 Toast 容器 -->
    <ToastContainer />
  </div>
</template>

<script setup>
import ToastContainer from './components/common/ToastContainer.vue'
</script>

<style>
.app {
  width: 100vw;
  height: 100vh;
  overflow: hidden;
}
</style>
