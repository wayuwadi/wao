<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchTodayTianrenReading, type TianrenReading } from '@/api/tianren'

const reading = ref<TianrenReading | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    reading.value = await fetchTodayTianrenReading()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '今日卦象暂时无法读取'
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="home-tianren">
    <RouterLink class="tianren-link" to="/tianren" :aria-label="reading ? `今日卦象：${reading.primary.name}卦，查看详情` : '今日卦象，进入天人一卦'">
      <span class="tianren-icon" aria-hidden="true">☯</span>
      <span class="tianren-copy">
        <small>今日卦象</small>
        <strong v-if="loading">读取中…</strong>
        <strong v-else-if="reading">{{ reading.primary.name }}卦</strong>
        <strong v-else-if="error">暂不可用</strong>
        <strong v-else>尚未起卦</strong>
      </span>
      <span class="tianren-symbol" aria-hidden="true">{{ reading?.primary.symbol || '☰' }}</span>
    </RouterLink>
    <button v-if="error" class="tianren-retry" type="button" :disabled="loading" @click="load">重试</button>
  </div>
</template>

<style scoped>
.home-tianren { position: relative; }
.tianren-link { display: inline-flex; align-items: center; gap: 9px; min-height: 38px; padding: 3px 10px; border: 1px solid var(--wao-line-control); border-radius: 9px; color: var(--wao-text); background: var(--wao-bg-card); text-decoration: none; transition: border-color .18s, background .18s; }
.tianren-link:hover { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.tianren-link:focus-visible, .tianren-retry:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.tianren-icon { color: var(--wao-primary); font-size: 19px; line-height: 1; }
.tianren-copy { display: grid; gap: 2px; }
.tianren-copy small { color: var(--wao-text-3); font-size: 10px; }
.tianren-copy strong { font-size: 12px; font-weight: 650; }
.tianren-symbol { display: grid; width: 27px; height: 27px; place-items: center; border-radius: 7px; background: var(--wao-primary-soft); color: var(--wao-primary-text); font-size: 17px; }
.tianren-retry { position: absolute; top: calc(100% + 2px); right: 0; border: 0; background: transparent; color: var(--wao-primary-text); font-size: 11px; cursor: pointer; }
</style>
