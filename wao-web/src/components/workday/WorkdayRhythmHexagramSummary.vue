<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchAssistantConversation } from '@/api/assistant'
import {
  createTodayTianrenReading,
  fetchTodayTianrenReading,
  type TianrenReading,
} from '@/api/tianren'
import { toast } from '@/composables/toast'

const props = defineProps<{ date: string }>()
const reading = ref<TianrenReading | null>(null)
const loading = ref(true)
const loadError = ref('')
const casting = ref(false)
const readingSummary = ref('')
const summaryLoading = ref(false)
const summaryError = ref('')

const factualPreview = computed(() => {
  if (!reading.value) return ''
  const current = `${reading.value.primary.name}卦（上${reading.value.primary.upper.nature}下${reading.value.primary.lower.nature}）`
  if (!reading.value.relating) return `本卦为${current}，今日没有变爻。`
  return `本卦为${current}，变化后为${reading.value.relating.name}卦，共 ${reading.value.changingLines.length} 个变爻。`
})

function compactSummary(value: string) {
  const plain = value
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[#>*_`~[\]]/g, '')
    .replace(/\([^)]*\)/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return plain.length > 180 ? `${plain.slice(0, 180).trim()}…` : plain
}

async function loadReadingSummary(current: TianrenReading | null) {
  readingSummary.value = ''
  summaryError.value = ''
  if (!current?.conversationId) return
  const readingId = current.id
  summaryLoading.value = true
  try {
    const detail = await fetchAssistantConversation(current.conversationId)
    if (reading.value?.id !== readingId) return
    const latest = [...detail.messages]
      .reverse()
      .find((message) => message.role === 'assistant')?.content
    readingSummary.value = latest ? compactSummary(latest) : ''
  } catch (cause) {
    if (reading.value?.id !== readingId) return
    summaryError.value = cause instanceof Error ? cause.message : '今日启示暂时无法读取'
  } finally {
    if (reading.value?.id === readingId) summaryLoading.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    reading.value = await fetchTodayTianrenReading()
    void loadReadingSummary(reading.value)
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '今日卦象暂时无法读取'
  } finally {
    loading.value = false
  }
}

async function cast() {
  if (casting.value) return
  casting.value = true
  try {
    reading.value = await createTodayTianrenReading()
    await loadReadingSummary(reading.value)
    toast('今日卦象已保存', 'ri-sparkling-line')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '今日卦象生成失败', 'ri-error-warning-line')
  } finally {
    casting.value = false
  }
}

watch(
  () => props.date,
  () => void load(),
)
onMounted(load)
</script>

<template>
  <aside class="hero-hexagram" aria-label="今日卦象与启示">
    <div class="hexagram-head">
      <small>天人一卦 · 今日启示</small>
      <RouterLink to="/tianren" aria-label="进入天人一卦详情">
        详情<i class="ri-arrow-right-up-line"></i>
      </RouterLink>
    </div>
    <div v-if="loading" class="hexagram-state">
      <i class="ri-loader-4-line spin"></i>正在读取今日卦象…
    </div>
    <div v-else-if="loadError" class="hexagram-state error" role="alert">
      <span>{{ loadError }}</span
      ><button type="button" @click="load">重试</button>
    </div>
    <template v-else-if="reading">
      <RouterLink class="hexagram-result" to="/tianren">
        <strong>{{ reading.primary.symbol }}</strong>
        <span>
          <b>{{ reading.primary.name }}卦</b>
          <small v-if="reading.relating">变化后 · {{ reading.relating.name }}卦</small>
          <small v-else>今日无变爻</small>
        </span>
      </RouterLink>
      <p v-if="summaryLoading">{{ factualPreview }} 正在读取已保存的 AI 自照摘要…</p>
      <p v-else-if="readingSummary">{{ readingSummary }}</p>
      <p v-else-if="summaryError" class="error">
        {{ factualPreview }} {{ summaryError }}
        <button type="button" @click="loadReadingSummary(reading)">重试</button>
      </p>
      <p v-else>{{ factualPreview }} 进入详情可生成结合当日状态的 AI 自照。</p>
    </template>
    <template v-else>
      <p>今天还没有起卦；由你主动开始，不会因打开页面自动生成。</p>
      <button class="cast-button" type="button" :disabled="casting" @click="cast">
        <i :class="casting ? 'ri-loader-4-line spin' : 'ri-sparkling-line'"></i>
        {{ casting ? '正在起卦…' : '起出今日一卦' }}
      </button>
    </template>
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
