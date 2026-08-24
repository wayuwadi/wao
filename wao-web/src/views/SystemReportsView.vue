<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  fetchSystemReport,
  generateSystemReport,
  type SystemPeriodicReport,
  type SystemReportPeriod,
} from '@/api/systemReports'

const PERIODS: Array<{ id: SystemReportPeriod; label: string; hint: string }> = [
  { id: 'DAY', label: '日报', hint: '每日 00:00 产出' },
  { id: 'WEEK', label: '周报', hint: '周日结束后产出' },
  { id: 'MONTH', label: '月报', hint: '每月 1 日产出' },
]

const period = ref<SystemReportPeriod>('DAY')
const report = ref<SystemPeriodicReport | null>(null)
const loading = ref(true)
const generating = ref(false)
const error = ref('')

const createdTasks = computed(
  () => report.value?.tasks.filter((task) => task.events.includes('CREATED')) ?? [],
)
const completedTasks = computed(
  () => report.value?.tasks.filter((task) => task.events.includes('COMPLETED')) ?? [],
)
const canGoNext = computed(() => {
  if (!report.value) return false
  const next = new Date(`${report.value.endDate}T12:00:00+08:00`)
  if (period.value === 'DAY') next.setDate(next.getDate() + 1)
  else if (period.value === 'WEEK') next.setDate(next.getDate() + 7)
  else {
    next.setDate(1)
    next.setMonth(next.getMonth() + 2, 0)
  }
  const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
  return next.toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }) < today
})

async function load(anchorDate?: string) {
  loading.value = true
  error.value = ''
  try {
    report.value = await fetchSystemReport(period.value, anchorDate)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '系统报告读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function selectPeriod(value: SystemReportPeriod) {
  if (period.value === value && report.value) return
  period.value = value
  await load()
}

async function move(direction: -1 | 1) {
  if (!report.value || (direction > 0 && !canGoNext.value)) return
  const base = new Date(
    `${direction < 0 ? report.value.startDate : report.value.endDate}T12:00:00+08:00`,
  )
  base.setDate(base.getDate() + direction)
  await load(base.toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }))
}

async function generate() {
  if (!report.value || generating.value) return
  generating.value = true
  error.value = ''
  try {
    report.value = await generateSystemReport(period.value, report.value.startDate)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '报告补齐失败，已有快照没有被删除'
  } finally {
    generating.value = false
  }
}

function taskUrl(projectId: string | null, taskId: string) {
  return projectId ? `/projects/${projectId}?task=${taskId}` : '/todo'
}

function formatTime(value: string | null) {
  if (!value) return '未结束'
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

function formatTokens(value: number) {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function runStatus(value: string) {
  return (
    (
      {
        succeeded: '成功',
        failed: '失败',
        cancelled: '已取消',
        queued: '排队中',
        claimed: '已领取',
        running: '运行中',
      } as Record<string, string>
    )[value] ?? value
  )
}

onMounted(() => load())
</script>

<template>
  <main class="reports-page">
    <header class="hero">
      <div>
        <span><i class="ri-bar-chart-box-line"></i> SYSTEM DELIVERY</span>
        <h1>系统报告</h1>
        <p>从真实任务和 AI Run 自动形成 WAO 建设日报、周报与月报。</p>
      </div>
      <button v-if="report?.generatedAt" :disabled="generating" @click="generate">
        <i :class="generating ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i
        >{{ generating ? '正在补齐…' : '重新核对' }}
      </button>
    </header>

    <nav class="period-tabs" aria-label="系统报告周期">
      <button
        v-for="item in PERIODS"
        :key="item.id"
        :class="{ active: period === item.id }"
        @click="selectPeriod(item.id)"
      >
        <b>{{ item.label }}</b
        ><small>{{ item.hint }}</small>
      </button>
    </nav>

    <section v-if="report" class="scope-bar">
      <button :disabled="loading" aria-label="上一个周期" @click="move(-1)">
        <i class="ri-arrow-left-s-line"></i>
      </button>
      <div>
        <b>{{ report.periodLabel }}</b
        ><small>{{ report.coverageMessage }}</small>
      </div>
      <button :disabled="loading || !canGoNext" aria-label="下一个周期" @click="move(1)">
        <i class="ri-arrow-right-s-line"></i>
      </button>
    </section>

    <section v-if="loading" class="state">
      <i class="ri-loader-4-line spin"></i><b>正在读取系统真实记录…</b>
    </section>
    <section v-else-if="error && !report" class="state error">
      <i class="ri-error-warning-line"></i><b>{{ error }}</b
      ><button @click="load()">重新读取</button>
    </section>

    <template v-else-if="report">
      <section v-if="!report.generatedAt" class="state empty">
        <i class="ri-file-warning-line"></i>
        <h2>这个周期尚未产出</h2>
        <p>可以立即用任务与 AI Run 的真实持久化数据补齐，不会生成演示内容。</p>
        <button :disabled="generating" @click="generate">
          {{ generating ? '正在补齐…' : '立即补齐报告' }}
        </button>
      </section>

      <div v-if="error" class="inline-error">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span
        ><button @click="report.generatedAt ? generate() : load(report.startDate)">重试</button>
      </div>

      <template v-if="report.generatedAt">
        <section class="inventory">
          <article>
            <span>项目存量</span><strong>{{ report.projectTotal }}</strong>
          </article>
          <article>
            <span>任务存量</span><strong>{{ report.taskTotal }}</strong>
          </article>
          <article>
            <span>未完成任务</span><strong>{{ report.openTaskTotal }}</strong>
          </article>
          <article>
            <span>AI Run 存量</span><strong>{{ report.aiRunTotal }}</strong>
          </article>
        </section>

        <section class="period-stats">
          <article>
            <i class="ri-add-circle-line"></i>
            <div>
              <strong>{{ report.taskCreatedCount }}</strong
              ><span>新建任务</span>
            </div>
          </article>
          <article>
            <i class="ri-checkbox-circle-line"></i>
            <div>
              <strong>{{ report.taskCompletedCount }}</strong
              ><span>完成任务</span>
            </div>
          </article>
          <article>
            <i class="ri-robot-2-line"></i>
            <div>
              <strong>{{ report.aiRunCount }}</strong
              ><span>AI Run</span>
            </div>
          </article>
          <article>
            <i class="ri-check-double-line"></i>
            <div>
              <strong>{{ report.aiRunSuccessCount }}</strong
              ><span>运行成功</span>
            </div>
          </article>
          <article>
            <i class="ri-error-warning-line"></i>
            <div>
              <strong>{{ report.aiRunFailureCount }}</strong
              ><span>运行失败</span>
            </div>
          </article>
          <article>
            <i class="ri-coins-line"></i>
            <div>
              <strong>{{ formatTokens(report.totalTokens) }}</strong
              ><span>Token · {{ report.aiRunMeteredCount }}/{{ report.aiRunCount }} 有用量</span>
            </div>
          </article>
        </section>

        <section class="activity-card">
          <header>
            <div>
              <span>TASK ACTIVITY</span>
              <h2>任务记录</h2>
            </div>
            <em>{{ report.tasks.length }} 个相关任务</em>
          </header>
          <div v-if="report.tasks.length" class="task-columns">
            <div>
              <h3>新建 · {{ createdTasks.length }}</h3>
              <a
                v-for="task in createdTasks"
                :key="`created:${task.id}`"
                :href="taskUrl(task.projectId, task.id)"
                ><span
                  ><b>{{ task.title }}</b
                  ><small
                    >{{ task.projectName || '未归属项目' }} ·
                    {{ formatTime(task.createdAt) }}</small
                  ></span
                ><i class="ri-arrow-right-up-line"></i
              ></a>
            </div>
            <div>
              <h3>完成 · {{ completedTasks.length }}</h3>
              <a
                v-for="task in completedTasks"
                :key="`done:${task.id}`"
                :href="taskUrl(task.projectId, task.id)"
                ><span
                  ><b>{{ task.title }}</b
                  ><small
                    >{{ task.projectName || '未归属项目' }} ·
                    {{ formatTime(task.completedAt) }}</small
                  ></span
                ><i class="ri-arrow-right-up-line"></i
              ></a>
            </div>
          </div>
          <p v-else class="empty-text">这个周期没有新建或完成的任务。</p>
        </section>

        <section class="activity-card">
          <header>
            <div>
              <span>AI RUN ACTIVITY</span>
              <h2>运行记录</h2>
            </div>
            <em>生成于 {{ formatTime(report.generatedAt) }}</em>
          </header>
          <div v-if="report.runs.length" class="run-list">
            <a v-for="run in report.runs" :key="run.id" :href="taskUrl(run.projectId, run.taskId)">
              <div class="run-head">
                <b>{{ run.taskTitle || `任务 ${run.taskId}` }}</b
                ><span :class="`status ${run.status}`">{{ runStatus(run.status) }}</span>
              </div>
              <small
                >{{ run.projectName || run.projectId }} · {{ run.engine }} ·
                {{ formatTime(run.createdAt) }} ·
                {{
                  run.totalTokens == null ? '未回传用量' : `${formatTokens(run.totalTokens)} Token`
                }}</small
              >
              <p v-if="run.resultSummary">{{ run.resultSummary }}</p>
            </a>
          </div>
          <p v-else class="empty-text">这个周期没有启动过 AI Run。</p>
        </section>
      </template>
    </template>
  </main>
</template>

<style scoped>
.reports-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px 28px 60px;
  color: var(--wao-text);
}
.hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
}
.hero span,
.activity-card header span {
  color: #356c78;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1.5px;
}
.hero h1 {
  margin: 5px 0 2px;
  font-size: 27px;
}
.hero p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.hero button,
.state button {
  border: 0;
  border-radius: 9px;
  padding: 10px 14px;
  background: #356c78;
  color: #fff;
  font-weight: 700;
  cursor: pointer;
}
.period-tabs {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.period-tabs button {
  padding: 11px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  text-align: left;
  cursor: pointer;
}
.period-tabs b,
.period-tabs small {
  display: block;
}
.period-tabs small {
  margin-top: 3px;
  color: var(--wao-text-weak);
  font-size: 9px;
}
.period-tabs .active {
  border-color: #4f8792;
  background: color-mix(in srgb, #356c78 8%, var(--wao-bg-card));
  color: #356c78;
}
.scope-bar {
  margin-top: 12px;
  display: grid;
  grid-template-columns: 34px 1fr 34px;
  align-items: center;
  gap: 8px;
  text-align: center;
}
.scope-bar button {
  width: 34px;
  height: 34px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-card);
  cursor: pointer;
}
.scope-bar button:disabled {
  opacity: 0.35;
  cursor: default;
}
.scope-bar b,
.scope-bar small {
  display: block;
}
.scope-bar small {
  margin-top: 2px;
  color: var(--wao-text-weak);
  font-size: 9px;
}
.state {
  min-height: 240px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  text-align: center;
  color: var(--wao-text-weak);
}
.state > i {
  font-size: 28px;
}
.state h2 {
  margin: 0;
  color: var(--wao-text);
  font-size: 16px;
}
.state p {
  margin: 0 0 5px;
  font-size: 10px;
}
.inventory,
.period-stats {
  margin-top: 14px;
  display: grid;
  gap: 8px;
}
.inventory {
  grid-template-columns: repeat(4, 1fr);
}
.inventory article,
.period-stats article,
.activity-card {
  border: 1px solid var(--wao-line);
  background: var(--wao-bg-card);
  border-radius: 11px;
}
.inventory article {
  padding: 11px;
}
.inventory span,
.inventory strong {
  display: block;
}
.inventory span {
  color: var(--wao-text-weak);
  font-size: 9px;
}
.inventory strong {
  margin-top: 3px;
  font-size: 18px;
}
.period-stats {
  grid-template-columns: repeat(6, 1fr);
}
.period-stats article {
  padding: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.period-stats i {
  color: #356c78;
  font-size: 19px;
}
.period-stats strong,
.period-stats span {
  display: block;
}
.period-stats strong {
  font-size: 17px;
}
.period-stats span {
  color: var(--wao-text-weak);
  font-size: 8.5px;
}
.activity-card {
  margin-top: 12px;
  padding: 18px;
}
.activity-card header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.activity-card h2 {
  margin: 3px 0 0;
  font-size: 15px;
}
.activity-card em {
  font-style: normal;
  color: var(--wao-text-weak);
  font-size: 9px;
}
.task-columns {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.task-columns h3 {
  margin: 0 0 6px;
  font-size: 11px;
}
.task-columns a,
.run-list > a {
  padding: 9px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: inherit;
  text-decoration: none;
}
.task-columns a {
  margin-top: 5px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.task-columns a > span {
  min-width: 0;
  flex: 1;
}
.task-columns b,
.task-columns small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-columns b {
  font-size: 10.5px;
}
.task-columns small,
.run-list small {
  margin-top: 2px;
  color: var(--wao-text-weak);
  font-size: 8.5px;
}
.run-list {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 7px;
}
.run-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.run-head b {
  font-size: 10.5px;
}
.status {
  flex: none;
  padding: 2px 6px;
  border-radius: 99px;
  background: var(--wao-bg-card);
  font-size: 8px;
}
.status.succeeded {
  color: #26734d;
}
.status.failed {
  color: var(--wao-danger-strong);
}
.run-list p {
  margin: 6px 0 0;
  color: var(--wao-text-3);
  font-size: 9px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.empty-text {
  margin: 16px 0 2px;
  color: var(--wao-text-weak);
  font-size: 10px;
}
.inline-error {
  margin-top: 10px;
  padding: 10px;
  border-radius: 9px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  display: flex;
  gap: 8px;
  font-size: 10px;
}
.inline-error span {
  flex: 1;
}
.inline-error button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 760px) {
  .reports-page {
    padding: 16px 14px 46px;
  }
  .hero {
    align-items: flex-start;
  }
  .hero p {
    max-width: 250px;
  }
  .hero button {
    font-size: 0;
  }
  .hero button i {
    font-size: 14px;
  }
  .inventory {
    grid-template-columns: repeat(2, 1fr);
  }
  .period-stats {
    grid-template-columns: repeat(2, 1fr);
  }
  .task-columns,
  .run-list {
    grid-template-columns: 1fr;
  }
  .scope-bar small {
    padding: 0 5px;
  }
  .activity-card header {
    align-items: flex-start;
  }
}
</style>
