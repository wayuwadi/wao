<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
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
  fetchDashboardSummary,
  fetchEngineUsage,
  fetchTaskTrend,
  type AiRunTrendItem,
  type DashboardSummary,
  type EngineUsageItem,
  type TaskTrendItem,
} from '@/api/dashboard'
import { toast } from '@/composables/toast'
import { formatTokens } from '@/composables/aiUsage'

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
const loading = ref(true)
const refreshing = ref(false)
const loadError = ref('')

const recentRuns = computed(() => aiTrend.value.reduce((sum, day) => sum + day.total, 0))
const recordedRuns = computed(() => aiTrend.value.reduce((sum, day) => sum + day.recordedRuns, 0))
const recentTokens = computed(() => aiTrend.value.reduce((sum, day) => sum + day.totalTokens, 0))

const aiChartEl = ref<HTMLElement | null>(null)
const taskChartEl = ref<HTMLElement | null>(null)
const engineChartEl = ref<HTMLElement | null>(null)
const tokenChartEl = ref<HTMLElement | null>(null)
let aiChart: echarts.ECharts | null = null
let taskChart: echarts.ECharts | null = null
let engineChart: echarts.ECharts | null = null
let tokenChart: echarts.ECharts | null = null
let refreshTimer: ReturnType<typeof setInterval> | null = null
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
}

function onResize() {
  aiChart?.resize()
  taskChart?.resize()
  engineChart?.resize()
  tokenChart?.resize()
}

async function load() {
  if (loadInFlight) return
  loadInFlight = true
  const initial = summary.value === null
  if (initial) loading.value = true
  else refreshing.value = true
  try {
    const [s, ai, t, eng] = await Promise.all([
      fetchDashboardSummary(),
      fetchAiRunTrend(30),
      fetchTaskTrend(7),
      fetchEngineUsage(),
    ])
    summary.value = s
    aiTrend.value = ai
    taskTrend.value = t
    engines.value = eng
    loadError.value = ''
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '仪表盘数据加载失败'
    toast(loadError.value, 'ri-error-warning-line')
  } finally {
    loading.value = false
    refreshing.value = false
    loadInFlight = false
    await nextTick()
    renderAll()
  }
}

/** 主题切换时重绘图表（颜色跟随 CSS 变量）。 */
const observer = new MutationObserver(() => renderAll())

onMounted(() => {
  void load()
  window.addEventListener('resize', onResize)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  refreshTimer = setInterval(() => void load(), 30_000)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (refreshTimer) clearInterval(refreshTimer)
  observer.disconnect()
  aiChart?.dispose()
  taskChart?.dispose()
  engineChart?.dispose()
  tokenChart?.dispose()
})

function goProjects() { router.push('/projects') }
function goHome() { router.push('/') }
</script>

<template>
  <div class="dashboard">
    <header class="dash-head">
      <div>
        <div class="eyebrow">DATA INSIGHTS</div>
        <h1>数据仪表盘</h1>
        <p>每天的 AI 使用频次、真实 Token 消耗与任务完成情况。</p>
      </div>
      <div class="dash-actions">
        <button class="ghost-btn" :disabled="refreshing" @click="load">
          <i class="ri-refresh-line" :class="{ spin: refreshing }"></i>{{ refreshing ? '刷新中' : '刷新' }}
        </button>
        <button class="ghost-btn" @click="goHome">
          <i class="ri-arrow-left-line"></i>返回控制台
        </button>
      </div>
    </header>

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
          <div class="kpi-sub">全部接入即可开始运行</div>
        </div>
        <div class="kpi">
          <i class="ri-checkbox-circle-line"></i>
          <div class="kpi-num">{{ summary?.completedThisWeek ?? 0 }}</div>
          <div class="kpi-label">本周完成任务</div>
          <div class="kpi-sub">成功 {{ summary?.succeededThisWeek ?? 0 }} · 失败 {{ summary?.failedThisWeek ?? 0 }}</div>
        </div>
        <div class="kpi">
          <i class="ri-cpu-line"></i>
          <div class="kpi-num">{{ formatTokens(recentTokens) }}</div>
          <div class="kpi-label">近 30 天 Token</div>
          <div class="kpi-sub">已记账 <b>{{ recordedRuns }}</b> / {{ recentRuns }} 次运行</div>
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
            <p>点击任意指标卡片，可以跳转到相关页面查看详情。</p>
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
.dashboard {
  padding: 28px 32px 48px;
  max-width: 1280px;
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
  color: var(--wao-primary);
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
  color: var(--wao-primary);
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
  color: var(--wao-primary);
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
}
@media (max-width: 600px) {
  .dashboard { padding: 16px; }
  .kpi-grid { grid-template-columns: 1fr; }
  .dash-head { flex-direction: column; }
}
</style>
