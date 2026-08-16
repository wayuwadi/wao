<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchAssistantStatus, type AssistantStatus } from '@/api/assistant'
import {
  fetchLifeReview,
  fetchLifeReviewHistory,
  generateLifeReview,
  type LifeReview,
  type LifeReviewHistory,
  type LifeReviewPeriod,
} from '@/api/lifeReview'
import { copyText } from '@/utils/clipboard'
import { renderMarkdown } from '@/utils/markdown'

const PERIODS: Array<{ id: LifeReviewPeriod; label: string; hint: string }> = [
  { id: 'WEEK', label: '周回顾', hint: '最近一个完整自然周' },
  { id: 'MONTH', label: '月回顾', hint: '最近一个完整自然月' },
  { id: 'YEAR', label: '年回顾', hint: '最近一个完整自然年' },
  { id: 'LIFE', label: '至今回顾', hint: '从最早真实记录到今天' },
]

const SECTIONS = [
  { key: 'pastActions' as const, title: '过去做了什么', icon: 'ri-footprint-line' },
  { key: 'meaningfulTopics' as const, title: '有意义的话题', icon: 'ri-chat-heart-line' },
  { key: 'trajectory' as const, title: '轨迹趋势假设', icon: 'ri-route-line' },
  { key: 'nextFocus' as const, title: '下次回顾时问自己', icon: 'ri-compass-discover-line' },
]

const period = ref<LifeReviewPeriod>('WEEK')
const anchorDate = ref('')
const review = ref<LifeReview | null>(null)
const history = ref<LifeReviewHistory[]>([])
const loading = ref(true)
const generating = ref(false)
const error = ref('')
const assistant = ref<AssistantStatus>({ configured: false, message: '正在检查本地 Codex…' })

const hasEvidence = computed(() => (review.value?.sources.length ?? 0) > 0)
const canGenerate = computed(() => hasEvidence.value && assistant.value.configured && !generating.value)
const canGoNext = computed(() => {
  if (!review.value || period.value === 'LIFE') return false
  return review.value.endDate < new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
})

async function load(includeHistory = false) {
  loading.value = true
  error.value = ''
  try {
    const requests: [Promise<LifeReview>, Promise<LifeReviewHistory[]> | null] = [
      fetchLifeReview(period.value, anchorDate.value || undefined),
      includeHistory ? fetchLifeReviewHistory() : null,
    ]
    const loaded = await requests[0]
    review.value = loaded
    anchorDate.value = loaded.startDate
    if (requests[1]) history.value = await requests[1]
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '回顾读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function selectPeriod(value: LifeReviewPeriod) {
  if (period.value === value && review.value) return
  period.value = value
  anchorDate.value = ''
  await load()
}

async function move(direction: -1 | 1) {
  if (!review.value || period.value === 'LIFE' || (direction === 1 && !canGoNext.value)) return
  const base = new Date(`${direction < 0 ? review.value.startDate : review.value.endDate}T12:00:00+08:00`)
  base.setUTCDate(base.getUTCDate() + direction)
  anchorDate.value = base.toISOString().slice(0, 10)
  await load()
}

async function generate() {
  if (!canGenerate.value || !review.value) return
  generating.value = true
  error.value = ''
  try {
    review.value = await generateLifeReview(period.value, review.value.startDate)
    history.value = await fetchLifeReviewHistory()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '回顾生成失败，现有记录没有被修改'
  } finally {
    generating.value = false
  }
}

async function openHistory(item: LifeReviewHistory) {
  period.value = item.period
  anchorDate.value = item.startDate
  await load()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function fullText() {
  if (!review.value?.report) return ''
  return SECTIONS.map((section) =>
    `## ${section.title}\n\n${review.value!.report![section.key]}`,
  ).join('\n\n')
}

function formatTime(value: string | null) {
  if (!value) return ''
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

onMounted(async () => {
  const [status] = await Promise.allSettled([fetchAssistantStatus(), load(true)])
  assistant.value = status.status === 'fulfilled'
    ? status.value
    : { configured: false, message: 'AI 状态读取失败，请刷新重试' }
})
</script>

<template>
  <main class="review-page">
    <header class="hero">
      <div>
        <span class="eyebrow"><i class="ri-time-line"></i> PERSONAL REVIEW</span>
        <h1>人生回顾</h1>
        <p>从真实任务与对话里回看走过的路，再用条件性的方式观察可能趋势。</p>
      </div>
      <button v-if="review?.report" class="copy-button" @click="copyText(fullText(), '回顾全文已复制')">
        <i class="ri-file-copy-line"></i>复制全文
      </button>
    </header>

    <section class="guardrail">
      <i class="ri-shield-check-line"></i>
      <div><b>证据优先，不算命</b><span>轨迹只是假设：它必须引用现有记录、说明成立条件和可能改变方向的变量。</span></div>
    </section>

    <nav class="period-tabs" aria-label="回顾周期">
      <button v-for="item in PERIODS" :key="item.id" :class="{ active: period === item.id }" @click="selectPeriod(item.id)">
        <b>{{ item.label }}</b><small>{{ item.hint }}</small>
      </button>
    </nav>

    <section v-if="review" class="scope-bar">
      <button :disabled="period === 'LIFE' || loading" title="上一个周期" @click="move(-1)"><i class="ri-arrow-left-s-line"></i></button>
      <div><b>{{ review.periodLabel }}</b><small>{{ review.coverageMessage }}</small></div>
      <button :disabled="!canGoNext || loading" title="下一个周期" @click="move(1)"><i class="ri-arrow-right-s-line"></i></button>
    </section>

    <div v-if="loading" class="state-card"><i class="ri-loader-4-line spin"></i><b>正在核对这段时间的真实记录…</b></div>
    <div v-else-if="error && !review" class="state-card error"><i class="ri-error-warning-line"></i><b>{{ error }}</b><button @click="load(true)">重新读取</button></div>

    <template v-else-if="review">
      <section class="coverage-grid">
        <article><i class="ri-checkbox-circle-line"></i><div><strong>{{ review.taskCount }}</strong><span>已完成任务</span></div></article>
        <article><i class="ri-message-3-line"></i><div><strong>{{ review.assistantConversationCount }}</strong><span>WAO AI 对话</span></div></article>
        <article><i class="ri-chat-3-line"></i><div><strong>{{ review.chatDayCount }}</strong><span>飞书聊天日</span></div></article>
      </section>

      <section v-if="review.report" class="report-card">
        <header><div><span>已生成存档</span><h2>{{ review.periodLabel }}</h2></div><time>{{ formatTime(review.generatedAt) }}</time></header>
        <div class="report-sections">
          <article v-for="section in SECTIONS" :key="section.key">
            <h3><i :class="section.icon"></i>{{ section.title }}</h3>
            <div class="markdown" v-html="renderMarkdown(review.report[section.key])"></div>
          </article>
        </div>
        <button class="generate-button secondary" :disabled="!canGenerate" @click="generate">
          <i :class="generating ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>{{ generating ? '正在重新核对证据…' : '按当前记录重新生成' }}
        </button>
      </section>

      <section v-else class="empty-report">
        <span><i class="ri-route-line"></i></span>
        <h2>{{ hasEvidence ? '真实资料已就绪' : '这个周期还没有可回顾的记录' }}</h2>
        <p>{{ hasEvidence ? '生成后会保存为这个自然周期的长期档案，重新打开仍可阅读。' : '完成任务、与 WAO AI 对话或同步飞书聊天后再来，不会用演示内容填空。' }}</p>
        <button class="generate-button" :disabled="!canGenerate" @click="generate">
          <i :class="generating ? 'ri-loader-4-line spin' : 'ri-sparkling-2-line'"></i>{{ generating ? '正在阅读记录并形成回顾…' : '生成这段时间的回顾' }}
        </button>
        <small v-if="!assistant.configured && hasEvidence" class="assistant-state">{{ assistant.message }}</small>
      </section>

      <div v-if="error" class="inline-error"><i class="ri-error-warning-line"></i><span>{{ error }}</span><button @click="generate">保留记录并重试</button></div>

      <section class="sources-card">
        <header><div><span>证据清单</span><h2>报告引用了什么</h2></div><em>{{ review.sources.length }} 条</em></header>
        <div v-if="review.sources.length" class="source-list">
          <component :is="source.url ? 'a' : 'div'" v-for="source in review.sources" :key="source.id" :href="source.url || undefined" class="source-row">
            <b>{{ source.id }}</b><span><strong>{{ source.title }}</strong><small>{{ source.type }}{{ source.date ? ` · ${source.date}` : '' }}</small></span><i v-if="source.url" class="ri-arrow-right-up-line"></i>
          </component>
        </div>
        <p v-else>当前周期没有真实来源。</p>
      </section>

      <section v-if="history.length" class="history-card">
        <header><div><span>回顾档案</span><h2>过去生成的周期</h2></div></header>
        <div class="history-list">
          <button v-for="item in history" :key="`${item.period}:${item.periodKey}`" @click="openHistory(item)">
            <span><b>{{ PERIODS.find((periodItem) => periodItem.id === item.period)?.label }}</b><time>{{ item.periodLabel }}</time></span>
            <p>{{ item.excerpt }}</p><i class="ri-arrow-right-s-line"></i>
          </button>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.review-page { max-width: 1160px; margin: 0 auto; padding: 24px 28px 60px; color: var(--wao-text); }
.hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; }.eyebrow { color: #6d5d9c; font-size: 10px; font-weight: 750; letter-spacing: 1.6px; }.hero h1 { margin: 5px 0 2px; font-size: 27px; }.hero p { margin: 0; color: var(--wao-text-weak); font-size: 12.5px; }.copy-button,.generate-button { border: 0; border-radius: 9px; background: #65558f; color: white; cursor: pointer; font-weight: 680; }.copy-button { padding: 9px 13px; }.guardrail { margin-top: 18px; padding: 12px 14px; border: 1px solid color-mix(in srgb,#65558f 22%,var(--wao-line)); border-radius: 11px; background: color-mix(in srgb,#65558f 5%,var(--wao-bg-card)); display: flex; gap: 10px; }.guardrail > i { color: #65558f; font-size: 19px; }.guardrail b,.guardrail span { display: block; }.guardrail b { font-size: 11.5px; }.guardrail span { margin-top: 2px; color: var(--wao-text-weak); font-size: 10.5px; }.period-tabs { margin-top: 18px; display: grid; grid-template-columns: repeat(4,1fr); gap: 8px; }.period-tabs button { min-width: 0; padding: 11px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); color: var(--wao-text-3); cursor: pointer; text-align: left; }.period-tabs b,.period-tabs small { display: block; }.period-tabs small { margin-top: 3px; color: var(--wao-text-weak); font-size: 9px; }.period-tabs button.active { border-color: #8e80b2; background: color-mix(in srgb,#65558f 8%,var(--wao-bg-card)); color: #65558f; }.scope-bar { margin-top: 12px; display: grid; grid-template-columns: 34px 1fr 34px; align-items: center; gap: 8px; text-align: center; }.scope-bar button { width: 34px; height: 34px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); cursor: pointer; }.scope-bar button:disabled { opacity: .35; cursor: default; }.scope-bar b,.scope-bar small { display: block; }.scope-bar b { font-size: 13px; }.scope-bar small { margin-top: 2px; color: var(--wao-text-weak); font-size: 9.5px; }.state-card { min-height: 220px; display: grid; place-items: center; align-content: center; gap: 10px; color: var(--wao-text-weak); }.state-card i { font-size: 25px; }.coverage-grid { margin-top: 16px; display: grid; grid-template-columns: repeat(3,1fr); gap: 9px; }.coverage-grid article { padding: 13px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); display: flex; align-items: center; gap: 10px; }.coverage-grid i { color: #65558f; font-size: 21px; }.coverage-grid strong,.coverage-grid span { display: block; }.coverage-grid strong { font-size: 19px; }.coverage-grid span { color: var(--wao-text-weak); font-size: 9px; }.report-card,.empty-report,.sources-card,.history-card { margin-top: 12px; border: 1px solid var(--wao-line); border-radius: 13px; background: var(--wao-bg-card); }.report-card,.sources-card,.history-card { padding: 18px; }.report-card > header,.sources-card > header,.history-card > header { display: flex; justify-content: space-between; gap: 12px; }.report-card header span,.sources-card header span,.history-card header span { color: #65558f; font-size: 9px; font-weight: 750; letter-spacing: 1px; }.report-card h2,.sources-card h2,.history-card h2 { margin: 3px 0 0; font-size: 15px; }.report-card time { color: var(--wao-text-weak); font-size: 9px; }.report-sections { margin-top: 14px; display: grid; grid-template-columns: repeat(2,1fr); gap: 10px; }.report-sections article { padding: 14px; border-radius: 10px; background: var(--wao-bg-sub); overflow-wrap: anywhere; }.report-sections h3 { margin: 0 0 8px; font-size: 12px; }.report-sections h3 i { margin-right: 6px; color: #65558f; }.markdown { color: var(--wao-text-2); font-size: 11px; line-height: 1.7; }.markdown :deep(p),.markdown :deep(ul) { margin: 0 0 7px; }.markdown :deep(ul) { padding-left: 18px; }.generate-button { min-height: 40px; padding: 0 17px; }.generate-button:disabled { opacity: .45; cursor: default; }.generate-button.secondary { margin-top: 14px; background: color-mix(in srgb,#65558f 12%,var(--wao-bg-sub)); color: #65558f; }.empty-report { min-height: 235px; padding: 30px; display: grid; justify-items: center; align-content: center; text-align: center; }.empty-report > span { width: 44px; height: 44px; border-radius: 13px; display: grid; place-items: center; background: color-mix(in srgb,#65558f 11%,var(--wao-bg-sub)); color: #65558f; font-size: 23px; }.empty-report h2 { margin: 11px 0 4px; font-size: 15px; }.empty-report p { max-width: 500px; margin: 0 0 14px; color: var(--wao-text-weak); font-size: 10.5px; }.assistant-state { margin-top: 8px; color: var(--wao-text-weak); }.inline-error { margin-top: 10px; padding: 10px 12px; border-radius: 9px; background: var(--wao-danger-bg); color: var(--wao-danger-strong); display: flex; align-items: center; gap: 8px; font-size: 10px; }.inline-error span { flex: 1; }.inline-error button { border: 0; background: transparent; color: inherit; cursor: pointer; }.sources-card em { font-style: normal; color: var(--wao-text-weak); font-size: 10px; }.source-list { margin-top: 12px; display: grid; grid-template-columns: repeat(2,1fr); gap: 7px; }.source-row { min-width: 0; padding: 9px; border: 1px solid var(--wao-line); border-radius: 8px; display: flex; align-items: center; gap: 8px; color: inherit; text-decoration: none; }.source-row > b { color: #65558f; font-size: 9px; }.source-row span { min-width: 0; flex: 1; }.source-row strong,.source-row small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.source-row strong { font-size: 10.5px; }.source-row small { margin-top: 2px; color: var(--wao-text-weak); font-size: 8.5px; }.history-list { margin-top: 12px; display: grid; gap: 6px; }.history-list button { padding: 10px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-sub); display: grid; grid-template-columns: 145px 1fr 20px; align-items: center; gap: 9px; color: inherit; cursor: pointer; text-align: left; }.history-list b,.history-list time { display: block; }.history-list b { font-size: 10px; color: #65558f; }.history-list time { margin-top: 2px; color: var(--wao-text-weak); font-size: 8.5px; }.history-list p { margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wao-text-3); font-size: 9.5px; }.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 720px) { .review-page { padding: 16px 14px 46px; }.hero { align-items: flex-start; }.hero p { max-width: 260px; }.period-tabs { grid-template-columns: repeat(2,1fr); }.coverage-grid { grid-template-columns: 1fr; }.report-sections,.source-list { grid-template-columns: 1fr; }.history-list button { grid-template-columns: 105px 1fr 16px; }.scope-bar small { padding: 0 4px; }.copy-button { font-size: 0; }.copy-button i { font-size: 14px; } }
</style>
