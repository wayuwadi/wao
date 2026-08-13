<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchSharedTianrenReading, type SharedTianrenReading } from '@/api/tianren'
import SiteFooter from '@/components/SiteFooter.vue'

const route = useRoute()
const token = computed(() => String(route.params.token ?? ''))
const reading = ref<SharedTianrenReading | null>(null)
const loading = ref(true)
const error = ref('')

const displayLines = computed(() => (reading.value?.lines ?? []).map((value, index) => ({
  value,
  position: index + 1,
  positionLabel: ['初', '二', '三', '四', '五', '上'][index],
  yang: value % 2 === 1,
  changing: value === 6 || value === 9,
  label: ({ 6: '老阴', 7: '少阳', 8: '少阴', 9: '老阳' } as Record<number, string>)[value],
})).reverse())

async function load() {
  loading.value = true
  error.value = ''
  try {
    reading.value = await fetchSharedTianrenReading(token.value)
  } catch {
    reading.value = null
    error.value = '这个分享链接无效，或者对应的卦象已不可访问。'
  } finally {
    loading.value = false
  }
}

function formatDate(value: string) {
  return new Date(`${value}T12:00:00`).toLocaleDateString('zh-CN', {
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
  })
}

onMounted(load)
watch(token, load)
</script>

<template>
  <div class="share-page">
    <main class="share-card">
      <div v-if="loading" class="state"><span class="spinner"></span><strong>正在读取分享结果</strong></div>
      <div v-else-if="error" class="state error"><i class="ri-link-unlink-m"></i><strong>{{ error }}</strong></div>

      <template v-else-if="reading">
        <header>
          <div class="brand"><i class="ri-compass-3-line"></i> WAO · 天人一卦</div>
          <span>{{ formatDate(reading.date) }}</span>
          <h1>{{ reading.primary.symbol }} 第 {{ reading.primary.number }} 卦 · {{ reading.primary.name }}</h1>
          <p>上{{ reading.primary.upper.name }}（{{ reading.primary.upper.nature }}）下{{ reading.primary.lower.name }}（{{ reading.primary.lower.nature }}）</p>
        </header>

        <section class="cast-layout">
          <div class="line-stack" aria-label="六爻，从上爻到初爻展示">
            <div v-for="line in displayLines" :key="line.position" class="yao-row" :class="{ changing: line.changing }">
              <span>{{ line.positionLabel }}</span>
              <span class="yao-shape" :class="{ yin: !line.yang }"><i></i><i v-if="!line.yang"></i></span>
              <span>{{ line.value }} · {{ line.label }}</span>
              <b v-if="line.changing">变</b>
            </div>
          </div>

          <div class="hexagram-flow">
            <div class="hex-card primary"><span>本卦</span><strong>{{ reading.primary.symbol }}</strong><b>{{ reading.primary.name }}</b><small>第 {{ reading.primary.number }} 卦</small></div>
            <div class="flow-arrow"><i :class="reading.relating ? 'ri-arrow-right-line' : 'ri-subtract-line'"></i><span>{{ reading.changingLines.length ? `${reading.changingLines.length} 个变爻` : '无变爻' }}</span></div>
            <div class="hex-card" :class="{ muted: !reading.relating }"><span>之卦</span><strong>{{ reading.relating?.symbol ?? '—' }}</strong><b>{{ reading.relating?.name ?? '守其本卦' }}</b><small>{{ reading.relating ? `第 ${reading.relating.number} 卦` : '不另生之卦' }}</small></div>
          </div>
        </section>

        <section class="boundary">
          <i class="ri-heart-pulse-line"></i>
          <div><strong>文化反思，不是医疗建议</strong><p>这是一份由分享者主动公开的卦象结果，不含其私有 AI 对话。它不预测吉凶，也不替代现实判断或医疗建议。</p></div>
        </section>
      </template>
    </main>
    <SiteFooter />
  </div>
</template>

<style scoped>
.share-page { min-height: 100vh; padding: 42px 18px 20px; color: var(--wao-text); background: radial-gradient(circle at 10% 0%, color-mix(in srgb, #b68745 13%, transparent), transparent 32%), radial-gradient(circle at 90% 8%, color-mix(in srgb, #3b887b 11%, transparent), transparent 28%), var(--wao-bg-page); }
.share-card { max-width: 820px; min-height: 420px; margin: 0 auto; padding: clamp(22px, 4vw, 38px); border: 1px solid var(--wao-line); border-radius: 18px; background: color-mix(in srgb, var(--wao-bg-card) 97%, #f2e5cf); box-shadow: var(--wao-shadow-pop); }
.state { min-height: 340px; display: grid; place-items: center; align-content: center; gap: 12px; color: var(--wao-text-weak); text-align: center; }.state strong { color: var(--wao-text-2); font-size: 14px; }.state > i { color: var(--wao-danger); font-size: 38px; }.spinner { width: 29px; height: 29px; border: 3px solid color-mix(in srgb, #3d756b 20%, transparent); border-top-color: #3d756b; border-radius: 50%; animation: spin .8s linear infinite; }
header { padding-bottom: 22px; border-bottom: 1px solid var(--wao-divider); text-align: center; }.brand { margin-bottom: 18px; color: #8e6534; font-size: 11px; font-weight: 650; letter-spacing: .08em; }.brand i { margin-right: 5px; }header > span { color: var(--wao-text-weak); font-size: 11px; }header h1 { margin: 7px 0 5px; font-family: 'STKaiti', 'KaiTi', serif; font-size: clamp(27px, 5vw, 38px); letter-spacing: .04em; }header p { margin: 0; color: var(--wao-text-weak); font-size: 11px; }
.cast-layout { margin-top: 26px; display: grid; grid-template-columns: minmax(260px, .9fr) minmax(280px, 1fr); gap: clamp(22px, 4vw, 42px); align-items: center; }.line-stack { padding: 16px; display: flex; flex-direction: column; gap: 12px; border-radius: 14px; background: color-mix(in srgb, #3d756b 5%, var(--wao-bg-sub)); }.yao-row { min-height: 26px; display: grid; grid-template-columns: 24px minmax(120px, 1fr) 68px 24px; align-items: center; gap: 9px; color: var(--wao-text-weak); font-size: 10px; }.yao-shape { height: 11px; display: flex; gap: 15%; }.yao-shape i { display: block; flex: 1; border-radius: 2px; background: #283f3b; }.yao-row.changing .yao-shape i { background: #b36b42; box-shadow: 0 0 0 3px color-mix(in srgb, #b36b42 10%, transparent); }.yao-row > b { width: 21px; height: 21px; display: grid; place-items: center; border-radius: 50%; background: color-mix(in srgb, #b36b42 12%, transparent); color: #a85d36; font-size: 9px; }
.hexagram-flow { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 12px; }.hex-card { min-width: 0; padding: 19px 10px; border: 1px solid color-mix(in srgb, #3d756b 24%, var(--wao-line)); border-radius: 14px; display: grid; justify-items: center; gap: 4px; background: color-mix(in srgb, #3d756b 4%, var(--wao-bg-card)); text-align: center; }.hex-card.primary { border-color: color-mix(in srgb, #ae7d40 35%, var(--wao-line)); }.hex-card > span,.hex-card > small { color: var(--wao-text-weak); font-size: 9px; }.hex-card > strong { color: #3d756b; font-size: 51px; line-height: 1; }.hex-card.primary > strong { color: #8e6534; }.hex-card > b { font-family: 'STKaiti', 'KaiTi', serif; font-size: 19px; }.hex-card.muted { opacity: .55; }.flow-arrow { display: grid; justify-items: center; gap: 3px; color: #a65f38; }.flow-arrow i { font-size: 20px; }.flow-arrow span { font-size: 8.5px; white-space: nowrap; }
.boundary { margin-top: 28px; padding: 14px 16px; display: flex; align-items: flex-start; gap: 10px; border-radius: 12px; background: var(--wao-warning-bg); color: var(--wao-warning-strong); }.boundary > i { font-size: 20px; }.boundary strong { font-size: 11px; }.boundary p { margin: 4px 0 0; color: var(--wao-text-3); font-size: 10px; line-height: 1.65; }
@keyframes spin { to { transform: rotate(360deg); } }@media (max-width: 680px) { .share-page { padding: 14px 10px; }.share-card { padding: 21px 14px; border-radius: 14px; }.cast-layout { grid-template-columns: 1fr; }.yao-row { grid-template-columns: 21px minmax(100px, 1fr) 62px 21px; gap: 6px; }.boundary { margin-top: 20px; } }@media (prefers-reduced-motion: reduce) { .spinner { animation: none; } }
</style>
