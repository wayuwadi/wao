<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import {
  fetchAiRunTrend,
  fetchAiQuotaHistory,
  fetchAiUsageInsights,
  fetchDashboardSummary,
  fetchEngineUsage,
  fetchTaskTrend,
  type AiRunTrendItem,
  type AiQuotaHistory,
  type AiUsageInsights,
  type DashboardSummary,
  type EngineUsageItem,
  type TaskTrendItem,
} from '@/api/dashboard'
import { formatTokens } from '@/composables/aiUsage'
import { formatDuration } from '@/composables/aiRunDuration'
import { createVisibilityPoller } from '@/composables/visibilityPolling'
import { usePageVisibility } from '@/composables/pageVisibility'
import CodexMembershipPanel from '@/components/dashboard/CodexMembershipPanel.vue'

echarts.use([
  CanvasRenderer,
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
])

const router = useRouter()
const summary = ref<DashboardSummary | null>(null)
const aiTrend = ref<AiRunTrendItem[]>([])
const taskTrend = ref<TaskTrendItem[]>([])
const engines = ref<EngineUsageItem[]>([])
const quotaHistory = ref<AiQuotaHistory>({ recordedSamples: 0, series: [], refreshes: [], officialResets: [] })
const usageInsights = ref<AiUsageInsights | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const loadError = ref('')
const lastLoadedAt = ref<string | null>(null)

const recentRuns = computed(() => aiTrend.value.reduce((sum, day) => sum + day.total, 0))
const recordedRuns = computed(() => aiTrend.value.reduce((sum, day) => sum + day.recordedRuns, 0))
const recentTokens = computed(() => aiTrend.value.reduce((sum, day) => sum + day.totalTokens, 0))
const officialResetMorningCount = computed(() => quotaHistory.value.officialResets
  .filter((event) => resetTimeMinutes(event.resetAt) < 12 * 60).length)
const officialResetAfternoonCount = computed(() =>
  quotaHistory.value.officialResets.length - officialResetMorningCount.value)

const aiChartEl = ref<HTMLElement | null>(null)
const taskChartEl = ref<HTMLElement | null>(null)
const engineChartEl = ref<HTMLElement | null>(null)
const tokenChartEl = ref<HTMLElement | null>(null)
const quotaChartEl = ref<HTMLElement | null>(null)
const officialResetChartEl = ref<HTMLElement | null>(null)
let aiChart: echarts.ECharts | null = null
let taskChart: echarts.ECharts | null = null
let engineChart: echarts.ECharts | null = null
let tokenChart: echarts.ECharts | null = null
let quotaChart: echarts.ECharts | null = null
let officialResetChart: echarts.ECharts | null = null
let disposed = false
let chartFingerprint = ''
const poller = createVisibilityPoller(load, () => 30_000)
const pageVisible = usePageVisibility()
watch(pageVisible, visible => visible ? poller.start() : poller.stop())
let loadInFlight = false

function cssVar(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

function themeColors() {
  const isDark = document.documentElement.dataset.theme === 'dark'
  return {
    text: cssVar('--wao-text', isDark ? '#e6e8ef' : '#1f2430'),
    text2: cssVar('--wao-text-2', isDark ? '#c2c6d2' : '#4a4f5d'),
    textWeak: cssVar('--wao-text-weak', isDark ? '#8a8f9d' : '#8a8f9d'),
    line: cssVar('--wao-line', isDark ? '#2e3340' : '#e9ebf0'),
    bgCard: cssVar('--wao-bg-card', isDark ? '#1a1e27' : '#ffffff'),
    primary: cssVar('--wao-primary', '#2b3b8d'),
    success: cssVar('--wao-success', '#237a4b'),
    danger: cssVar('--wao-danger', '#b33a3a'),
    warning: cssVar('--wao-warning', '#8a5b0d'),
    brand2: cssVar('--wao-brand-2', '#7a5cc6'),
    brand3: cssVar('--wao-brand-3', '#4aa3c2'),
  }
}

function formatInteger(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function axisTokens(value: number): string {
  return formatTokens(value).replace('K', '千').replace('M', '百万')
}

function formatTime(value: string | null): string {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

function resetTimeMinutes(value: string): number {
  const match = value.match(/T(\d{2}):(\d{2})/)
  if (!match) return 0
  return Number(match[1]) * 60 + Number(match[2])
}

function clockTime(value: number): string {
  const hours = Math.floor(value / 60).toString().padStart(2, '0')
  const minutes = Math.round(value % 60).toString().padStart(2, '0')
  return `${hours}:${minutes}`
}

function formatOfficialResetTime(value: string): string {
  const normalized = value.replace('T', ' ')
  return normalized.length >= 16 ? normalized.slice(0, 16) : normalized
}

function adviceIcon(category: string): string {
  return ({
    OUTCOME: 'ri-checkbox-circle-line', QUALITY: 'ri-shield-check-line',
    ROUTING: 'ri-route-line', RETRY: 'ri-loop-left-line', ACCOUNTING: 'ri-file-list-3-line',
  } as Record<string, string>)[category] ?? 'ri-lightbulb-line'
}

function renderAiChart() {
  if (!aiChartEl.value) return
  if (!aiChart) {
    aiChart = echarts.init(aiChartEl.value)
  }
  const c = themeColors()
  const hasData = aiTrend.value.some((d) => d.total > 0)
  if (!hasData) {
    aiChart.clear()
    aiChart.setOption({
      title: { text: '暂无 AI 运行记录', left: 'center', top: 'center',
        textStyle: { color: c.textWeak, fontSize: 14, fontWeight: 400 } },
    })
    return
  }
  aiChart.setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.bgCard,
      borderColor: c.line,
      textStyle: { color: c.text, fontSize: 12 },
    },
    legend: {
      data: ['运行总数', '成功', '失败', '取消'],
      textStyle: { color: c.text2, fontSize: 12 },
      top: 4,
    },
    grid: { left: 40, right: 16, top: 40, bottom: 28 },
    xAxis: {
      type: 'category',
      data: aiTrend.value.map((d) => d.date.slice(5)),
      axisLine: { lineStyle: { color: c.line } },
      axisLabel: { color: c.textWeak, fontSize: 11 },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: c.line, type: 'dashed' } },
      axisLabel: { color: c.textWeak, fontSize: 11 },
    },
    series: [
      { name: '运行总数', type: 'line', smooth: true, data: aiTrend.value.map((d) => d.total),
        lineStyle: { color: c.primary }, itemStyle: { color: c.primary } },
      { name: '成功', type: 'line', smooth: true, data: aiTrend.value.map((d) => d.succeeded),
        lineStyle: { color: c.success }, itemStyle: { color: c.success } },
      { name: '失败', type: 'line', smooth: true, data: aiTrend.value.map((d) => d.failed),
        lineStyle: { color: c.danger }, itemStyle: { color: c.danger } },
      { name: '取消', type: 'line', smooth: true, data: aiTrend.value.map((d) => d.cancelled),
        lineStyle: { color: c.textWeak }, itemStyle: { color: c.textWeak } },
    ],
  }, true)
}

function renderTaskChart() {
  if (!taskChartEl.value) return
  if (!taskChart) {
    taskChart = echarts.init(taskChartEl.value)
  }
  const c = themeColors()
  const hasData = taskTrend.value.some((d) => d.completed > 0 || d.created > 0)
  if (!hasData) {
    taskChart.clear()
    taskChart.setOption({
      title: { text: '暂无任务活动', left: 'center', top: 'center',
        textStyle: { color: c.textWeak, fontSize: 14, fontWeight: 400 } },
    })
    return
  }
  taskChart.setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.bgCard,
      borderColor: c.line,
      textStyle: { color: c.text, fontSize: 12 },
    },
    legend: {
      data: ['已完成', '已创建'],
      textStyle: { color: c.text2, fontSize: 12 },
      top: 4,
    },
    grid: { left: 40, right: 16, top: 40, bottom: 28 },
    xAxis: {
      type: 'category',
      data: taskTrend.value.map((d) => d.date.slice(5)),
      axisLine: { lineStyle: { color: c.line } },
      axisLabel: { color: c.textWeak, fontSize: 11 },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: c.line, type: 'dashed' } },
      axisLabel: { color: c.textWeak, fontSize: 11 },
    },
    series: [
      { name: '已完成', type: 'bar', data: taskTrend.value.map((d) => d.completed),
        itemStyle: { color: c.success, borderRadius: [3, 3, 0, 0] }, barMaxWidth: 28 },
      { name: '已创建', type: 'bar', data: taskTrend.value.map((d) => d.created),
        itemStyle: { color: c.brand3, borderRadius: [3, 3, 0, 0] }, barMaxWidth: 28 },
    ],
  }, true)
}

function renderTokenChart() {
  if (!tokenChartEl.value) return
  if (!tokenChart) {
    tokenChart = echarts.init(tokenChartEl.value)
  }
  const c = themeColors()
  const hasRuns = aiTrend.value.some((d) => d.total > 0)
  const hasRecordedUsage = aiTrend.value.some((d) => d.recordedRuns > 0)
  if (!hasRecordedUsage) {
    tokenChart.clear()
    tokenChart.setOption({
      title: {
        text: hasRuns ? '这些运行尚未回传 Token 用量' : '暂无 AI Token 用量',
        left: 'center',
        top: 'center',
        textStyle: { color: c.textWeak, fontSize: 14, fontWeight: 400 },
      },
    })
    return
  }
  tokenChart.setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.bgCard,
      borderColor: c.line,
      textStyle: { color: c.text, fontSize: 12 },
      formatter: (params: Array<{ axisValue: string; marker: string; seriesName: string; value: number }>) => {
        const [first] = params
        return [first?.axisValue ?? '', ...params.map((p) =>
          `${p.marker}${p.seriesName}：${formatInteger(p.value)} Token`)].join('<br/>')
      },
    },
    legend: {
      data: ['输入', '输出', '缓存', '总 Token'],
      textStyle: { color: c.text2, fontSize: 12 },
      top: 4,
    },
    grid: { left: 56, right: 18, top: 40, bottom: 28 },
    xAxis: {
      type: 'category',
      data: aiTrend.value.map((d) => d.date.slice(5)),
      axisLine: { lineStyle: { color: c.line } },
      axisLabel: { color: c.textWeak, fontSize: 11 },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: c.line, type: 'dashed' } },
      axisLabel: { color: c.textWeak, fontSize: 11, formatter: axisTokens },
    },
    series: [
      { name: '输入', type: 'bar', stack: 'token', data: aiTrend.value.map((d) => d.inputTokens),
        itemStyle: { color: c.brand3 }, barMaxWidth: 24 },
      { name: '输出', type: 'bar', stack: 'token', data: aiTrend.value.map((d) => d.outputTokens),
        itemStyle: { color: c.brand2 }, barMaxWidth: 24 },
      { name: '缓存', type: 'bar', stack: 'token', data: aiTrend.value.map((d) => d.cachedInputTokens),
        itemStyle: { color: c.warning }, barMaxWidth: 24 },
      { name: '总 Token', type: 'line', smooth: true, data: aiTrend.value.map((d) => d.totalTokens),
        lineStyle: { color: c.primary, width: 2 }, itemStyle: { color: c.primary } },
    ],
  }, true)
}

function renderQuotaChart() {
  if (!quotaChartEl.value) return
  if (!quotaChart) quotaChart = echarts.init(quotaChartEl.value)
  const c = themeColors()
  if (!quotaHistory.value.series.length) {
    quotaChart.clear()
    quotaChart.setOption({
      title: {
        text: '等待首次真实额度采样', left: 'center', top: 'center',
        textStyle: { color: c.textWeak, fontSize: 14, fontWeight: 400 },
      },
    })
    return
  }
  const dates = [...new Set(quotaHistory.value.series.flatMap((series) =>
    series.points.map((point) => point.date)))].sort()
  const palette = [c.primary, c.brand2, c.brand3, c.success, c.warning]
  quotaChart.setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.bgCard,
      borderColor: c.line,
      textStyle: { color: c.text, fontSize: 12 },
      formatter: (params: Array<{
        axisValue: string; marker: string; seriesName: string
        data?: { value: number | null; decreasedPercent?: number }
      }>) => [params[0]?.axisValue ?? '', ...params.filter((item) => item.data?.value !== null).map((item) =>
        `${item.marker}${item.seriesName}：剩余 ${item.data?.value}%` +
        (item.data?.decreasedPercent ? `（当日下降 ${item.data.decreasedPercent}%）` : ''))].join('<br/>'),
    },
    legend: {
      type: 'scroll', top: 4, textStyle: { color: c.text2, fontSize: 11 },
    },
    grid: { left: 44, right: 18, top: 46, bottom: 28 },
    xAxis: {
      type: 'category', data: dates.map((date) => date.slice(5)),
      axisLine: { lineStyle: { color: c.line } },
      axisLabel: { color: c.textWeak, fontSize: 11 }, axisTick: { show: false },
    },
    yAxis: {
      type: 'value', min: 0, max: 100,
      axisLabel: { color: c.textWeak, fontSize: 11, formatter: '{value}%' },
      splitLine: { lineStyle: { color: c.line, type: 'dashed' } },
    },
    series: quotaHistory.value.series.map((series, index) => {
      const points = new Map(series.points.map((point) => [point.date, point]))
      return {
        name: `${series.accountLabel} · ${series.windowLabel}`,
        type: 'line', smooth: true, connectNulls: true, showSymbol: true,
        data: dates.map((date) => {
          const point = points.get(date)
          return { value: point?.remainingPercent ?? null, decreasedPercent: point?.decreasedPercent ?? 0 }
        }),
        lineStyle: { color: palette[index % palette.length], width: 2 },
        itemStyle: { color: palette[index % palette.length] },
      }
    }),
  }, true)
}

function renderOfficialResetChart() {
  if (!officialResetChartEl.value) return
  if (!officialResetChart) officialResetChart = echarts.init(officialResetChartEl.value)
  const c = themeColors()
  const events = [...quotaHistory.value.officialResets]
    .sort((left, right) => left.resetAt.localeCompare(right.resetAt))
  if (!events.length) {
    officialResetChart.clear()
    officialResetChart.setOption({
      title: {
        text: '等待首次 Codex 七天额度跨周期样本', left: 'center', top: 'center',
        textStyle: { color: c.textWeak, fontSize: 14, fontWeight: 400 },
      },
    })
    return
  }
  officialResetChart.setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: c.bgCard,
      borderColor: c.line,
      textStyle: { color: c.text, fontSize: 12 },
      formatter: (params: Array<{ dataIndex: number; marker: string }>) => {
        const event = events[params[0]?.dataIndex ?? 0]
        return event ? `${params[0]?.marker ?? ''}${event.accountLabel} · ${event.windowLabel}<br/>` +
          `官方重置：${formatOfficialResetTime(event.resetAt)}<br/>` +
          `重置前后：${event.beforeRemainingPercent}% → ${event.afterRemainingPercent}%` : ''
      },
    },
    grid: { left: 54, right: 18, top: 24, bottom: 34 },
    xAxis: {
      type: 'category',
      data: events.map((event) => event.resetAt.slice(5, 10)),
      axisLine: { lineStyle: { color: c.line } },
      axisLabel: { color: c.textWeak, fontSize: 11 },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value', min: 0, max: 24 * 60 - 1, interval: 360,
      axisLabel: { color: c.textWeak, fontSize: 11, formatter: clockTime },
      splitLine: { lineStyle: { color: c.line, type: 'dashed' } },
    },
    series: [{
      name: '官方重置时间', type: 'line', showSymbol: true,
      data: events.map((event) => resetTimeMinutes(event.resetAt)),
      lineStyle: { color: c.primary, width: 2 },
      itemStyle: { color: c.primary },
    }],
  }, true)
}

function renderEngineChart() {
  if (!engineChartEl.value) return
  if (!engineChart) {
    engineChart = echarts.init(engineChartEl.value)
  }
  const c = themeColors()
  const total = engines.value.reduce((s, e) => s + e.count, 0)
  if (!total) {
    engineChart.clear()
    engineChart.setOption({
      title: { text: '暂无引擎使用记录', left: 'center', top: 'center',
        textStyle: { color: c.textWeak, fontSize: 14, fontWeight: 400 } },
    })
    return
  }
  const palette = [c.primary, c.brand2, c.brand3]
  engineChart.setOption({
    tooltip: {
      backgroundColor: c.bgCard,
      borderColor: c.line,
      textStyle: { color: c.text, fontSize: 12 },
      formatter: (p: { name: string; value: number; percent: number }) =>
        `${p.name}：${p.value} 次（${p.percent.toFixed(1)}%）`,
    },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '55%'],
        avoidLabelOverlap: true,
        label: {
          color: c.text2,
          fontSize: 12,
          formatter: '{b}\n{d}%',
        },
        labelLine: { lineStyle: { color: c.line } },
        data: engines.value.map((e, i) => ({
          value: e.count,
          name: e.label,
          itemStyle: { color: palette[i % palette.length] },
        })),
      },
    ],
  }, true)
}

function renderAll() {
  renderAiChart()
  renderTokenChart()
  renderTaskChart()
  renderEngineChart()
  renderQuotaChart()
  renderOfficialResetChart()
}

function onResize() {
  aiChart?.resize()
  taskChart?.resize()
  engineChart?.resize()
  tokenChart?.resize()
  quotaChart?.resize()
  officialResetChart?.resize()
}

async function load() {
  if (loadInFlight || disposed) return
  loadInFlight = true
  let chartsChanged = false
  const initial = summary.value === null
  if (initial) loading.value = true
  else refreshing.value = true
  try {
    const [s, ai, t, eng, quota, insights] = await Promise.all([
      fetchDashboardSummary(),
      fetchAiRunTrend(30),
      fetchTaskTrend(7),
      fetchEngineUsage(),
      fetchAiQuotaHistory(30),
      fetchAiUsageInsights(30),
    ])
    if (disposed) return
    const nextFingerprint = JSON.stringify([ai, t, eng, quota])
    chartsChanged = nextFingerprint !== chartFingerprint
    if (chartsChanged) {
      aiTrend.value = ai
      taskTrend.value = t
      engines.value = eng
      quotaHistory.value = quota
      chartFingerprint = nextFingerprint
    }
    if (JSON.stringify(summary.value) !== JSON.stringify(s)) summary.value = s
    if (JSON.stringify(usageInsights.value) !== JSON.stringify(insights)) usageInsights.value = insights
    lastLoadedAt.value = new Date().toISOString()
    loadError.value = ''
  } catch (error) {
    if (!disposed) loadError.value = error instanceof Error ? error.message : '仪表盘数据加载失败'
  } finally {
    loading.value = false
    refreshing.value = false
    loadInFlight = false
    if (chartsChanged) {
      await nextTick()
      if (!disposed) renderAll()
    }
  }
}

/** 主题切换时重绘图表（颜色跟随 CSS 变量）。 */
const observer = new MutationObserver(() => renderAll())

onMounted(() => {
  if (pageVisible.value) poller.start()
  window.addEventListener('resize', onResize)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  disposed = true
  poller.stop()
  observer.disconnect()
  aiChart?.dispose()
  taskChart?.dispose()
  engineChart?.dispose()
  tokenChart?.dispose()
  quotaChart?.dispose()
  officialResetChart?.dispose()
})

function goProjects() { router.push('/projects') }
function goHome() { router.push('/') }
function goRuns() { router.push({ path: '/', query: { console: 'runs' } }) }
</script>

<template>
  <div class="dashboard">
    <header class="dash-head">
      <div>
        <div class="eyebrow">DATA INSIGHTS</div>
        <h1>使用统计</h1>
        <p>每天的 AI 使用频次、真实 Token、额度变化与有证据的使用建议。</p>
      </div>
      <div class="dash-actions">
        <button class="ghost-btn" :disabled="loading || refreshing" @click="load">
          <i class="ri-refresh-line" :class="{ spin: refreshing }"></i>{{ refreshing ? '刷新中' : '刷新' }}
        </button>
        <button class="ghost-btn" @click="goHome">
          <i class="ri-arrow-left-line"></i>返回控制台
        </button>
      </div>
    </header>

    <section v-if="summary" class="usage-guidance" aria-label="统计解读与下一步">
      <h2>先查看运行，再判断消耗</h2>
      <p>本页读取时间：{{ formatTime(lastLoadedAt) }}。这是系统汇总，不是个人效率评分；各图表的时间范围不同。</p>
      <div v-if="loadError" class="refresh-error" role="alert"><strong>刷新失败，以下统计仍是上一次结果。</strong><span>{{ loadError }}</span><button class="ghost-btn" :disabled="refreshing" @click="load">重试刷新</button></div>
      <p v-else-if="refreshing" role="status">正在刷新，暂时保留上一次统计。</p>
      <ul>
        <li v-if="summary.activeRuns">当前有 {{ summary.activeRuns }} 次运行排队或执行中，可先查看进度。</li>
        <li v-if="summary.failedThisWeek">本周创建的运行中，有 {{ summary.failedThisWeek }} 次失败；查看失败原因与已有产物后再决定是否重试。</li>
        <li v-if="recentRuns > recordedRuns">近 30 天有 {{ recentRuns - recordedRuns }} 次运行尚未回传用量；当前 Token 总量并不完整，不代表这些运行没有消耗。</li>
        <li v-if="!summary.totalRuns">还没有 AI 运行记录，可先从首页表达一个目标。</li>
      </ul>
      <div class="guidance-actions"><button class="ghost-btn" @click="goRuns">查看 AI 运行</button><button class="ghost-btn" @click="goHome">打开首页</button></div>
      <p>下方建议附有证据；样本不足时先补齐观察，不据此直接调整模型或比较效率。</p>
    </section>

    <CodexMembershipPanel />

    <section v-if="loading" class="loading">
      <i class="ri-loader-4-line spin"></i>正在读取统计数据…
    </section>

    <section v-else-if="loadError && !summary" class="load-error">
      <i class="ri-error-warning-line"></i>
      <strong>统计数据加载失败</strong>
      <span>{{ loadError }}</span>
      <button class="ghost-btn" @click="load"><i class="ri-refresh-line"></i>重试</button>
    </section>

    <template v-else>
      <section class="kpi-grid">
        <div class="kpi">
          <i class="ri-pulse-line"></i>
          <div class="kpi-num">{{ summary?.totalRuns ?? 0 }}</div>
          <div class="kpi-label">AI 运行总数</div>
          <div class="kpi-sub">进行中 <b>{{ summary?.activeRuns ?? 0 }}</b></div>
        </div>
        <div class="kpi">
          <i class="ri-computer-line"></i>
          <div class="kpi-num">{{ summary?.onlineRunners ?? 0 }}</div>
          <div class="kpi-label">在线执行电脑</div>
          <div class="kpi-sub">仅表示近期在线，运行前仍需检查配置</div>
        </div>
        <div class="kpi">
          <i class="ri-checkbox-circle-line"></i>
          <div class="kpi-num">{{ summary?.completedThisWeek ?? 0 }}</div>
          <div class="kpi-label">本周完成任务</div>
          <div class="kpi-sub">本周创建的 AI 运行：成功 {{ summary?.succeededThisWeek ?? 0 }} · 失败 {{ summary?.failedThisWeek ?? 0 }}</div>
        </div>
        <div class="kpi">
          <i class="ri-cpu-line"></i>
          <div class="kpi-num">{{ formatTokens(recentTokens) }}</div>
          <div class="kpi-label">近 30 天 Token</div>
          <div class="kpi-sub">已记账 <b>{{ recordedRuns }}</b> / {{ recentRuns }} 次运行</div>
        </div>
      </section>

      <section class="chart-card insight-card">
        <div class="chart-head">
          <h2><i class="ri-route-line"></i>AI 交付效能</h2>
          <span class="chart-hint">近 {{ usageInsights?.windowDays ?? 30 }} 天 · 基于真实运行</span>
        </div>
        <div v-if="usageInsights" class="outcome-metrics">
          <article>
            <strong>{{ usageInsights.deliveredTasks }}</strong>
            <span>有效交付任务</span>
            <small>{{ usageInsights.deliveredRuns }} 次运行完成部署与线上验收</small>
          </article>
          <article>
            <strong>{{ usageInsights.tokensPerDeliveredTask === null ? (usageInsights.deliveredTasks ? '记账不全' : '等待交付') : formatTokens(usageInsights.tokensPerDeliveredTask) }}</strong>
            <span>每个有效交付 Token</span>
            <small>窗口内全部已结束运行消耗 ÷ 有效交付任务</small>
          </article>
          <article>
            <strong>{{ usageInsights.verifiedTasks }} / {{ usageInsights.deliveredTasks }}</strong>
            <span>成员已验收</span>
            <small>AI 成功不会代替成员最终验收</small>
          </article>
          <article>
            <strong>{{ usageInsights.medianAiExecutionSeconds === null ? '未回传' : formatDuration(usageInsights.medianAiExecutionSeconds * 1000) }}</strong>
            <span>AI 实际执行中位</span>
            <small>{{ usageInsights.timedRuns }}/{{ usageInsights.completedRuns }} 次已回传 · 不含 Git、部署和验收等待</small>
          </article>
          <article>
            <strong>{{ formatTokens(usageInsights.failedTokens) }}</strong>
            <span>已记录未交付消耗</span>
            <small>已记录重试 {{ usageInsights.retryRuns }} 次 · {{ formatTokens(usageInsights.retryTokens) }} Token</small>
          </article>
        </div>
        <div class="advice-grid">
          <article v-for="item in usageInsights?.advice ?? []" :key="item.category"
            class="advice-item" :class="{ insufficient: !item.sufficientData }">
            <i :class="adviceIcon(item.category)"></i>
            <div>
              <strong>{{ item.title }}</strong>
              <p>{{ item.recommendation }}</p>
              <small>{{ item.evidence }}</small>
            </div>
          </article>
        </div>
        <p class="insight-disclaimer">有效交付必须同时通过部署与线上验收；模型路由只在同一任务类型、每个配置至少 3 次且真实 Token 与 AI 执行时长完整时比较，端到端等待时间不会被当成模型速度。</p>
      </section>

      <section class="chart-row quota-row">
        <div class="chart-card wide">
          <div class="chart-head">
            <h2><i class="ri-time-line"></i>Codex 官方重置时间趋势</h2>
            <span class="chart-hint">近 30 天 · 上午 {{ officialResetMorningCount }} 次 · 下午 {{ officialResetAfternoonCount }} 次</span>
          </div>
          <div ref="officialResetChartEl" class="chart"></div>
          <p class="chart-note">只认七天窗口由供应商返回的重置时间跨周期推进；纵轴为上海时间，不把刷新卡造成的额度上涨混入。</p>
        </div>
        <div class="chart-card">
          <div class="chart-head">
            <h2><i class="ri-calendar-check-line"></i>官方重置时间点</h2>
            <span class="chart-hint">最近 {{ Math.min(5, quotaHistory.officialResets.length) }} 条</span>
          </div>
          <div v-if="quotaHistory.officialResets.length" class="refresh-list">
            <article v-for="item in quotaHistory.officialResets.slice(0, 5)"
              :key="`${item.resetAt}-${item.accountLabel}-${item.windowLabel}`" class="refresh-item">
              <div class="refresh-title">
                <strong>{{ item.accountLabel }}</strong>
                <span>{{ formatOfficialResetTime(item.resetAt).slice(11) }}</span>
              </div>
              <p>{{ item.windowLabel }}：{{ item.beforeRemainingPercent }}% → {{ item.afterRemainingPercent }}%</p>
              <small>{{ formatOfficialResetTime(item.resetAt) }} 官方重置 · {{ formatTime(item.confirmedAt) }} 确认</small>
            </article>
          </div>
          <div v-else class="empty-panel">
            <i class="ri-time-line"></i>
            <strong>还没有可确认的官方重置</strong>
            <span>系统会等待同一 Codex 七天窗口跨周期，再记录供应商给出的准确重置时间。</span>
          </div>
        </div>
      </section>

      <section class="chart-row quota-row">
        <div class="chart-card wide">
          <div class="chart-head">
            <h2><i class="ri-battery-charge-line"></i>额度剩余与每日下降</h2>
            <span class="chart-hint">近 30 天 · {{ quotaHistory.recordedSamples }} 次真实采样</span>
          </div>
          <div ref="quotaChartEl" class="chart"></div>
          <p class="chart-note">每天取最后一次真实采样；额度上升记为刷新，不会算成负消耗。</p>
        </div>
        <div class="chart-card">
          <div class="chart-head">
            <h2><i class="ri-history-line"></i>全部额度上涨记录</h2>
            <span class="chart-hint">最近 {{ Math.min(5, quotaHistory.refreshes.length) }} 条</span>
          </div>
          <div v-if="quotaHistory.refreshes.length" class="refresh-list">
            <article v-for="item in quotaHistory.refreshes.slice(0, 5)"
              :key="`${item.observedAt}-${item.accountLabel}-${item.windowLabel}`" class="refresh-item">
              <div class="refresh-title">
                <strong>{{ item.accountLabel }}</strong>
                <span>+{{ item.refreshedPercent }}%</span>
              </div>
              <p>{{ item.windowLabel }}：{{ item.beforeRemainingPercent }}% → {{ item.afterRemainingPercent }}%</p>
              <small>{{ formatTime(item.observedAt) }} 记录 · 下次重置 {{ formatTime(item.resetsAt) }}</small>
            </article>
          </div>
          <div v-else class="empty-panel">
            <i class="ri-history-line"></i>
            <strong>还没有额度刷新记录</strong>
            <span>系统会从后续真实采样开始记录；这里包含自动重置和手动刷新卡造成的真实上涨。</span>
          </div>
        </div>
      </section>


      <section class="chart-row">
        <div class="chart-card wide">
          <div class="chart-head">
            <h2><i class="ri-line-chart-line"></i>每日 AI 使用频次</h2>
            <span class="chart-hint">近 30 天</span>
          </div>
          <div ref="aiChartEl" class="chart"></div>
        </div>
        <div class="chart-card">
          <div class="chart-head">
            <h2><i class="ri-pie-chart-line"></i>引擎占比</h2>
            <span class="chart-hint">历史累计</span>
          </div>
          <div ref="engineChartEl" class="chart small"></div>
        </div>
      </section>

      <section class="chart-row">
        <div class="chart-card wide">
          <div class="chart-head">
            <h2><i class="ri-cpu-line"></i>每日 Token 消耗</h2>
            <span class="chart-hint">近 30 天 · 输入 / 输出 / 缓存</span>
          </div>
          <div ref="tokenChartEl" class="chart"></div>
        </div>
        <div class="chart-card">
          <div class="chart-head">
            <h2><i class="ri-file-chart-line"></i>用量概览</h2>
          </div>
          <div class="usage-overview">
            <strong>{{ formatInteger(recentTokens) }}</strong>
            <span>近 30 天总 Token</span>
            <dl>
              <div><dt>运行频次</dt><dd>{{ recentRuns }} 次</dd></div>
              <div><dt>已记账</dt><dd>{{ recordedRuns }} 次</dd></div>
              <div><dt>未回传</dt><dd>{{ Math.max(0, recentRuns - recordedRuns) }} 次</dd></div>
            </dl>
            <p>仅汇总 AI CLI 结束事件真实回传的用量；未回传不会被伪装成零消耗。</p>
          </div>
        </div>
      </section>

      <section class="chart-row">
        <div class="chart-card wide">
          <div class="chart-head">
            <h2><i class="ri-bar-chart-2-line"></i>任务活动</h2>
            <span class="chart-hint">近 7 天</span>
          </div>
          <div ref="taskChartEl" class="chart"></div>
        </div>
        <div class="chart-card">
          <div class="chart-head">
            <h2><i class="ri-lightbulb-flash-line"></i>小提示</h2>
          </div>
          <div class="tips">
            <p>通过下方入口查看原始记录，结合任务结果判断统计含义。</p>
            <button class="link-btn" @click="goProjects">
              <i class="ri-stack-line"></i>打开项目列表
            </button>
            <button class="link-btn" @click="goHome">
              <i class="ri-pulse-line"></i>查看 AI 控制台
            </button>
            <p class="muted">数据每 30 秒自动刷新一次（手动点击「刷新」可立即获取最新数据）。</p>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.usage-guidance{padding:18px;margin-bottom:20px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card);color:var(--wao-text)}.usage-guidance h2{font-size:17px;margin:0 0 10px}.usage-guidance p,.usage-guidance li{font-size:13px;line-height:1.7;color:var(--wao-text-2)}.usage-guidance ul{padding-left:20px}.guidance-actions{display:flex;gap:8px;flex-wrap:wrap}.refresh-error{display:flex;align-items:flex-start;flex-direction:column;gap:8px;font-size:13px;color:var(--wao-danger)}.guidance-actions button:focus-visible{outline:2px solid var(--wao-primary);outline-offset:3px}
.dashboard {
  padding: 28px 32px 48px;
  max-width: none;
  margin: 0 auto;
}
.dash-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 24px;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--wao-text-weak);
  text-transform: uppercase;
  margin-bottom: 4px;
}
.dash-head h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--wao-text);
}
.dash-head p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--wao-text-2);
}
.dash-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.ghost-btn {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.ghost-btn:hover {
  background: var(--wao-bg-hover-2);
}
.ghost-btn:disabled {
  cursor: wait;
  opacity: 0.65;
}
.ghost-btn i { font-size: 15px; }

.loading {
  padding: 60px 0;
  text-align: center;
  color: var(--wao-text-2);
  font-size: 14px;
}
.loading i {
  font-size: 20px;
  margin-right: 8px;
  animation: spin 1s linear infinite;
  vertical-align: middle;
}
.load-error {
  display: flex;
  min-height: 280px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
  color: var(--wao-text-2);
  text-align: center;
}
.load-error > i { color: var(--wao-danger); font-size: 28px; }
.load-error > strong { color: var(--wao-text); font-size: 15px; }
.load-error > span { max-width: 560px; color: var(--wao-text-weak); font-size: 12px; }
.load-error .ghost-btn { margin-top: 6px; }
@keyframes spin { to { transform: rotate(360deg); } }

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.kpi {
  padding: 18px 20px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  position: relative;
}
.kpi > i {
  position: absolute;
  top: 16px;
  right: 18px;
  font-size: 22px;
  color: var(--wao-primary-text);
  opacity: 0.25;
}
.kpi-num {
  font-size: 28px;
  font-weight: 600;
  color: var(--wao-text);
  letter-spacing: -0.01em;
}
.kpi-label {
  font-size: 13px;
  color: var(--wao-text-2);
}
.kpi-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.kpi-sub b { color: var(--wao-text-2); font-weight: 500; }

.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}
.chart-card {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
}
.chart-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.chart-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--wao-text);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.chart-head h2 i {
  font-size: 16px;
  color: var(--wao-primary-text);
}
.chart-hint {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.chart {
  width: 100%;
  height: 280px;
}
.chart.small { height: 260px; }
.chart-note,
.insight-disclaimer {
  margin: -4px 0 0;
  color: var(--wao-text-weak);
  font-size: 11px;
  line-height: 1.6;
}
.quota-row .chart-card { min-width: 0; }
.refresh-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 306px;
  overflow: auto;
  padding-right: 2px;
}
.refresh-item {
  padding: 10px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-hover-2);
}
.refresh-title {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--wao-text);
  font-size: 12px;
}
.refresh-title span { color: var(--wao-success); font-weight: 600; }
.refresh-item p { margin: 5px 0 3px; color: var(--wao-text-2); font-size: 12px; }
.refresh-item small { color: var(--wao-text-weak); font-size: 10px; }
.empty-panel {
  min-height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 6px;
  text-align: center;
  color: var(--wao-text-weak);
}
.empty-panel i { font-size: 24px; }
.empty-panel strong { color: var(--wao-text-2); font-size: 13px; }
.empty-panel span { max-width: 260px; font-size: 11px; line-height: 1.6; }
.insight-card { margin-bottom: 16px; }
.outcome-metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin: 2px 0 14px;
}
.outcome-metrics article {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 11px 12px;
  border-radius: 9px;
  background: var(--wao-bg-hover-2);
}
.outcome-metrics strong {
  color: var(--wao-text);
  font-size: 18px;
  line-height: 1.25;
}
.outcome-metrics span {
  margin-top: 3px;
  color: var(--wao-text-2);
  font-size: 12px;
}
.outcome-metrics small {
  margin-top: 5px;
  color: var(--wao-text-weak);
  font-size: 11px;
  line-height: 1.45;
}
.advice-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.advice-item {
  display: flex;
  gap: 9px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-hover-2);
}
.advice-item > i { color: var(--wao-primary-text); font-size: 17px; flex: 0 0 auto; }
.advice-item strong { color: var(--wao-text); font-size: 12px; }
.advice-item p { margin: 5px 0; color: var(--wao-text-2); font-size: 12px; line-height: 1.5; }
.advice-item small { color: var(--wao-text-weak); font-size: 10px; line-height: 1.5; }
.advice-item.insufficient { border-style: dashed; }
.advice-item.insufficient > i { color: var(--wao-text-weak); }

.usage-overview {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  min-height: 260px;
  color: var(--wao-text-2);
}
.usage-overview > strong {
  color: var(--wao-text);
  font-size: 30px;
  font-weight: 600;
  letter-spacing: -0.02em;
}
.usage-overview > span {
  margin-top: 2px;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.usage-overview dl {
  display: grid;
  gap: 7px;
  margin: 20px 0 14px;
}
.usage-overview dl > div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 7px;
  border-bottom: 1px solid var(--wao-line);
}
.usage-overview dt,
.usage-overview dd { margin: 0; font-size: 12px; }
.usage-overview dd { color: var(--wao-text); font-weight: 600; }
.usage-overview p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 11px;
  line-height: 1.6;
}

.tips {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 0;
  font-size: 13px;
  color: var(--wao-text-2);
}
.tips p { margin: 0; }
.tips .muted { color: var(--wao-text-weak); font-size: 12px; }
.link-btn {
  align-self: flex-start;
  padding: 6px 10px;
  border: 1px solid var(--wao-line);
  background: transparent;
  color: var(--wao-primary-text);
  border-radius: 7px;
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.link-btn:hover { background: var(--wao-bg-hover-2); }
.link-btn i { font-size: 15px; }

@media (max-width: 900px) {
  .kpi-grid { grid-template-columns: 1fr 1fr; }
  .chart-row { grid-template-columns: 1fr; }
  .outcome-metrics { grid-template-columns: 1fr 1fr; }
  .advice-grid { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 600px) {
  .dashboard { padding: 16px; }
  .kpi-grid { grid-template-columns: 1fr; }
  .dash-head { flex-direction: column; }
  .outcome-metrics { grid-template-columns: 1fr; }
  .advice-grid { grid-template-columns: 1fr; }
}
</style>
