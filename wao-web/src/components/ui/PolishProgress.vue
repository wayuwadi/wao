<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
const props = defineProps<{ message?: string; startedAt?: number }>()
const mountedAt = Date.now()
const now = ref(mountedAt)
const timer = setInterval(() => { now.value = Date.now() }, 1000)
onBeforeUnmount(() => clearInterval(timer))
const seconds = computed(() => Math.max(0, Math.floor((now.value - (props.startedAt ?? mountedAt)) / 1000)))
</script>

<template>
  <div class="polish-progress" role="status">
    <strong>{{ message || '正在提交润色任务…' }}</strong>
    <span aria-live="off">已等待 {{ seconds }} 秒</span>
    <small v-if="seconds < 30">完成后会显示结果，原文保持不变。</small>
    <small v-else>这次等待较长，无需重复提交；任务仍会继续处理。</small>
  </div>
</template>

<style scoped>
.polish-progress { display: flex; flex-direction: column; gap: 10px; text-align: center; line-height: 1.6; }
.polish-progress strong { font-weight: 500; }
.polish-progress span { font-variant-numeric: tabular-nums; }
.polish-progress small { opacity: .8; }
</style>
