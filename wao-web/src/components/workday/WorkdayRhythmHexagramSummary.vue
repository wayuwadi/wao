<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchSharedTodayTianrenReading, type TianrenReading } from '@/api/tianren'

const props = defineProps<{ date: string }>()
const reading = ref<TianrenReading | null>(null)
const loading = ref(true)
const loadError = ref('')
const factualPreview = computed(() => {
  if (!reading.value) return ''
  const current = `${reading.value.primary.name}卦（上${reading.value.primary.upper.nature}下${reading.value.primary.lower.nature}）`
  return reading.value.relating
    ? `本卦为${current}，变化后为${reading.value.relating.name}卦，共 ${reading.value.changingLines.length} 个变爻。`
    : `本卦为${current}，今日没有变爻。`
})
async function load() {
  loading.value = true
  loadError.value = ''
  try { reading.value = await fetchSharedTodayTianrenReading() }
  catch (cause) { loadError.value = cause instanceof Error ? cause.message : '今日卦象暂时无法读取' }
  finally { loading.value = false }
}
watch(() => props.date, () => void load())
let timer: ReturnType<typeof setInterval>
onMounted(() => { void load(); timer = setInterval(() => void load(), 60_000) })
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <aside class="hero-hexagram" aria-label="今日卦象">
    <div class="hexagram-head"><small>天人一卦 · 今日卦象</small></div>
    <div v-if="loading" class="hexagram-state"><i class="ri-loader-4-line spin"></i>正在读取今日卦象…</div>
    <div v-else-if="loadError" class="hexagram-state error" role="alert"><span>{{ loadError }}</span><button type="button" @click="load">重试</button></div>
    <template v-else-if="reading">
      <div class="hexagram-result"><strong>{{ reading.primary.symbol }}</strong><span><b>{{ reading.primary.name }}卦</b><small v-if="reading.relating">变化后 · {{ reading.relating.name }}卦</small><small v-else>今日无变爻</small></span></div>
      <p>{{ factualPreview }}</p>
    </template>
    <p v-else>今日卦象将在每天早上 8 点生成。</p>
  </aside>
</template>

<style scoped>
.hero-hexagram {
  position: relative;
  z-index: 1;
  align-self: stretch;
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 14px;
  background: rgba(11, 58, 47, 0.28);
  backdrop-filter: blur(4px);
  color: #fff;
}
.hexagram-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.hexagram-head small {
  color: #cce9df;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
.hexagram-head a,
.hexagram-result {
  color: #fff;
  text-decoration: none;
}
.hexagram-head a {
  font-size: 12px;
  opacity: 0.72;
}
.hexagram-result {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0 7px;
  border-radius: 10px;
}
.hexagram-result:focus-visible {
  outline: 2px solid #b0ead5;
  outline-offset: 3px;
}
.hexagram-result > strong {
  font-size: 42px;
  line-height: 1;
  color: #f0d4a8;
}
.hexagram-result span {
  min-width: 0;
  display: grid;
  gap: 2px;
}
.hexagram-result b {
  font-family: 'STKaiti', 'KaiTi', serif;
  font-size: 17px;
}
.hexagram-result small,
.hero-hexagram p,
.hexagram-state {
  color: rgba(255, 255, 255, 0.85);
  font-size: 13px;
  line-height: 1.5;
}
.hero-hexagram p {
  margin: 0;
  overflow-wrap: anywhere;
}
.hexagram-state {
  min-height: 92px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  text-align: center;
}
.hexagram-state.error {
  flex-direction: column;
  color: #ffd0d0;
}
.hero-hexagram button {
  border: 0;
  background: none;
  color: #b0ead5;
  cursor: pointer;
  font: inherit;
}
.hero-hexagram .cast-button {
  margin-top: 10px;
  padding: 6px 9px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
}
.error {
  color: #ffd0d0 !important;
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
