<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { fetchWorkdayRhythm } from '@/api/workdayRhythm'
import type { WorkdayRhythmBlock } from '@/utils/workdayRhythm'

defineProps<{ date: string; today: string }>()
const router = useRouter()
const session = useSessionStore()
const blocks = ref<WorkdayRhythmBlock[]>([])
const loading = ref(false)
const error = ref('')
let sequence = 0
async function load() {
  const request = ++sequence
  loading.value = true
  error.value = ''
  blocks.value = []
  try {
    const config = await fetchWorkdayRhythm()
    if (request === sequence) blocks.value = config.blocks
  } catch {
    if (request === sequence) error.value = '今日计划模板读取失败'
  } finally {
    if (request === sequence) loading.value = false
  }
}
watch(() => session.currentUser.id, () => { void load() }, { immediate: true })
</script>

<template>
  <section class="day-plan" aria-label="每日计划参考">
    <header><strong>{{ date === today ? '今日计划' : `${date} 计划` }}</strong><span>每日节奏模板 · 参考安排</span><button type="button" @click="router.push('/workday-rhythm')">调整模板<i class="ri-arrow-right-up-line"></i></button></header>
    <p v-if="loading" role="status">正在读取计划模板…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <div v-else-if="blocks.length" class="plan-blocks">
      <article v-for="block in blocks" :key="block.id"><time>{{ block.start }}–{{ block.end }}{{ block.end <= block.start ? ' 次日' : '' }}</time><b>{{ block.title }}</b><p>{{ block.guidance }}</p></article>
    </div>
    <p v-else>尚未设置计划时间段，可前往每日节奏调整模板。</p>
  </section>
</template>

<style scoped>
.day-plan { padding: 10px 14px; border-top: 1px solid var(--wao-divider); background: var(--wao-primary-tint); }
header { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; font-size: 12px; }
header span { color: var(--wao-text-weak); font-size: 11px; }
button { border: 0; background: transparent; color: var(--wao-primary-text); cursor: pointer; font: inherit; }
header button { margin-left: auto; }
.plan-blocks { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 7px; }
article { display: grid; align-content: start; gap: 4px; padding: 9px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); }
time { font-size: 11px; color: var(--wao-primary-text); }
b { font-size: 12px; }
p { margin: 0; color: var(--wao-text-weak); font-size: 11px; line-height: 1.6; }
</style>
