<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
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

const aiChartEl = ref<HTMLElement | null>(null)
const taskChartEl = ref<HTMLElement | null>(null)
const engineChartEl = ref<HTMLElement | null>(null)
let aiChart: echarts.ECharts | null = null
let taskChart: echarts.ECharts | null = null
let engineChart: echarts.ECharts | null = null

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
  renderTaskChart()
  renderEngineChart()
}

function onResize() {
  aiChart?.resize()
  taskChart?.resize()
  engineChart?.resize()
}

async function load() {
  loading.value = true
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
    renderAll()
  } catch (error) {
    toast(error instanceof Error ? error.message : '仪表盘数据加载失败', 'ri-error-warning-line')
  } finally {
    loading.value = false
  }
}

/** 主题切换时重绘图表（颜色跟随 CSS 变量）。 */
const observer = new MutationObserver(() => renderAll())

onMounted(() => {
  void load()
  window.addEventListener('resize', onResize)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  observer.disconnect()
  aiChart?.dispose()
  taskChart?.dispose()
  engineChart?.dispose()
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
        <p>AI 运行与任务完成的实时统计，图表颜色跟随当前主题。</p>
      </div>
      <div class="dash-actions">
        <button class="ghost-btn" @click="load">
          <i class="ri-refresh-line"></i>刷新
        </button>
        <button class="ghost-btn" @click="goHome">
          <i class="ri-arrow-left-line"></i>返回控制台
        </button>
      </div>
    </header>

    <section v-if="loading" class="loading">
      <i class="ri-loader-4-line spin"></i>正在读取统计数据…
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
      </section>

      <section class="chart-row">
        <div class="chart-card wide">
          <div class="chart-head">
            <h2><i class="ri-line-chart-line"></i>AI 运行趋势</h2>
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
            <p class="muted">数据每 30 秒刷新一次（手动点击「刷新」可立即获取最新数据）。</p>
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
@keyframes spin { to { transform: rotate(360deg); } }

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
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
