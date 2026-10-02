<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchTodayTianrenReading, type TianrenReading } from '@/api/tianren'

defineProps<{ integrated?: boolean }>()

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
  <div class="home-tianren" :class="{ integrated }">
    <RouterLink class="tianren-link" to="/tianren" :aria-label="reading ? `今日卦象：${reading.primary.name}卦，查看详情` : '今日卦象，进入天人一卦'">
      <span v-if="!integrated" class="tianren-icon" aria-hidden="true">☯</span>
      <span class="tianren-copy">
        <small><span v-if="integrated" class="integrated-icon" aria-hidden="true">☯</span>今日卦象</small>
        <strong v-if="loading">读取中…</strong>
        <strong v-else-if="reading">{{ reading.primary.name }}卦</strong>
        <strong v-else-if="error">暂不可用</strong>
        <strong v-else>尚未起卦</strong>
        <span v-if="integrated" class="tianren-hint">查看卦象</span>
      </span>
      <span v-if="!integrated" class="tianren-symbol" aria-hidden="true">{{ reading?.primary.symbol || '☰' }}</span>
    </RouterLink>
    <button v-if="error" class="tianren-retry" type="button" :disabled="loading" @click="load">重试</button>
  </div>
</template>

<style scoped>
.home-tianren { position: relative; }
.tianren-link { display: inline-flex; align-items: center; gap: 9px; height: var(--home-day-control-height, 42px); box-sizing: border-box; padding: 3px 12px; border: 1px solid var(--wao-line-control); border-radius: var(--home-day-control-radius, 10px); color: var(--wao-primary-text); background: var(--wao-bg-card); text-decoration: none; transition: border-color .18s, background .18s; }
.tianren-link:hover { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.tianren-link:focus-visible, .tianren-retry:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.tianren-icon { color: var(--wao-primary); font-size: 19px; line-height: 1; }
.tianren-copy { display: grid; gap: 2px; }
.tianren-copy small { color: var(--wao-text-3); font-size: 10px; }
.tianren-copy strong { font-size: 13px; font-weight: 650; }
.tianren-symbol { display: grid; width: 29px; height: 29px; place-items: center; border-radius: 8px; background: var(--wao-primary-soft); color: var(--wao-primary-text); font-size: 17px; }
.tianren-retry { position: absolute; top: calc(100% + 2px); right: 0; border: 0; background: transparent; color: var(--wao-primary-text); font-size: 11px; cursor: pointer; }
.integrated { min-width: 0; }
.integrated .tianren-link { display: flex; width: 100%; height: 100%; min-height: 62px; border: 0; border-radius: 0; padding: 1px 14px; background: transparent; }
.integrated .tianren-link:hover { background: var(--wao-primary-soft); }
.integrated .tianren-copy { min-width: 0; gap: 5px; }
.integrated .tianren-copy small { display: flex; align-items: center; gap: 5px; font-size: 11px; }
.integrated .tianren-copy strong { font-size: 16px; overflow-wrap: anywhere; }
.integrated-icon { color: var(--wao-primary-text); }
.tianren-hint { font-size: 11px; color: var(--wao-text-3); }
.integrated .tianren-retry { position: static; display: block; margin: 0 14px 6px; }
@media (max-width: 600px) { .integrated .tianren-link { padding: 12px 14px; } }
</style>
