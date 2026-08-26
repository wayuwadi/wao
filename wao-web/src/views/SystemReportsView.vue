<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  fetchSystemReportSettings,
  fetchSystemReport,
  generateSystemReport,
  saveSystemReportSettings,
  type SystemPeriodicReport,
  type SystemReportAggregation,
  type SystemReportPeriod,
  type SystemReportSettings,
} from '@/api/systemReports'

const PERIODS: Array<{ id: SystemReportPeriod; label: string; hint: string }> = [
  { id: 'DAY', label: '日报', hint: '最近完整自然日' },
  { id: 'WEEK', label: '周报', hint: '最近完整自然周' },
  { id: 'MONTH', label: '月报', hint: '最近完整自然月' },
]
const AGGREGATIONS: Array<{ id: SystemReportAggregation; label: string }> = [
  { id: 'TASK_CREATED', label: '新建任务' },
  { id: 'TASK_COMPLETED', label: '完成任务' },
  { id: 'AI_RUN', label: 'AI Run 与 Token' },
]
const PAGE_SIZE = 20

const period = ref<SystemReportPeriod>('DAY')
const report = ref<SystemPeriodicReport | null>(null)
const loading = ref(true)
const generatingPeriod = ref<SystemReportPeriod | null>(null)
const error = ref('')
const createdTaskPage = ref(1)
const completedTaskPage = ref(1)
const runPage = ref(1)
const settingsOpen = ref(false)
const settingsLoading = ref(true)
const settingsSaving = ref(false)
const settingsError = ref('')
const settingsSaved = ref(false)
const reportSettings = ref<SystemReportSettings | null>(null)
const settingsDraft = ref<SystemReportSettings | null>(null)

function cloneSettings(value: SystemReportSettings): SystemReportSettings {
  return {
    ...value,
    periods: [...value.periods],
    aggregationOptions: [...value.aggregationOptions],
  }
}

const createdTasks = computed(
  () =>
    report.value?.tasks
      .filter((task) => task.events.includes('CREATED'))
      .sort((a, b) => Date.parse(b.createdAt ?? '') - Date.parse(a.createdAt ?? '')) ?? [],
)
const completedTasks = computed(
  () =>
    report.value?.tasks
      .filter((task) => task.events.includes('COMPLETED'))
      .sort((a, b) => Date.parse(b.completedAt ?? '') - Date.parse(a.completedAt ?? '')) ?? [],
)
const createdTaskPages = computed(() =>
  Math.max(1, Math.ceil(createdTasks.value.length / PAGE_SIZE)),
)
const completedTaskPages = computed(() =>
  Math.max(1, Math.ceil(completedTasks.value.length / PAGE_SIZE)),
)
const runPages = computed(() =>
  Math.max(1, Math.ceil((report.value?.runs.length ?? 0) / PAGE_SIZE)),
)
const visibleCreatedTasks = computed(() =>
  createdTasks.value.slice(
    (createdTaskPage.value - 1) * PAGE_SIZE,
    createdTaskPage.value * PAGE_SIZE,
  ),
)
const visibleCompletedTasks = computed(() =>
  completedTasks.value.slice(
    (completedTaskPage.value - 1) * PAGE_SIZE,
    completedTaskPage.value * PAGE_SIZE,
  ),
)
const visibleRuns = computed(() =>
  (report.value?.runs ?? []).slice((runPage.value - 1) * PAGE_SIZE, runPage.value * PAGE_SIZE),
)
const includesCreated = computed(() => report.value?.aggregationOptions.includes('TASK_CREATED'))
const includesCompleted = computed(() =>
  report.value?.aggregationOptions.includes('TASK_COMPLETED'),
)
const includesRuns = computed(() => report.value?.aggregationOptions.includes('AI_RUN'))
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
    resetPagination()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '系统报告读取失败，请重试'
  } finally {
    loading.value = false
  }
}

function resetPagination() {
  createdTaskPage.value = 1
  completedTaskPage.value = 1
  runPage.value = 1
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

async function generate(value = period.value) {
  if (generatingPeriod.value) return
  generatingPeriod.value = value
  error.value = ''
  try {
    const anchorDate = value === period.value ? report.value?.startDate : undefined
    report.value = await generateSystemReport(value, anchorDate)
    period.value = value
    resetPagination()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '报告重新统计失败，已有快照没有被删除'
  } finally {
    generatingPeriod.value = null
  }
}

async function loadSettings() {
  settingsLoading.value = true
  settingsError.value = ''
  try {
    reportSettings.value = await fetchSystemReportSettings()
    settingsDraft.value = cloneSettings(reportSettings.value)
  } catch (cause) {
    settingsError.value = cause instanceof Error ? cause.message : '报告设置读取失败，请重试'
  } finally {
    settingsLoading.value = false
  }
}

function toggleSetting<T extends string>(key: 'periods' | 'aggregationOptions', value: T) {
  if (!settingsDraft.value) return
  const values = settingsDraft.value[key] as string[]
  settingsSaved.value = false
  settingsDraft.value = {
    ...settingsDraft.value,
    [key]: values.includes(value) ? values.filter((item) => item !== value) : [...values, value],
  }
}

async function saveSettings() {
  if (!settingsDraft.value || settingsSaving.value) return
  settingsSaving.value = true
  settingsError.value = ''
  settingsSaved.value = false
  try {
    reportSettings.value = await saveSystemReportSettings(settingsDraft.value)
    settingsDraft.value = cloneSettings(reportSettings.value)
    settingsSaved.value = true
  } catch (cause) {
    settingsError.value = cause instanceof Error ? cause.message : '报告设置保存失败，请重试'
  } finally {
    settingsSaving.value = false
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

onMounted(() => Promise.all([load(), loadSettings()]))
</script>

<template>
  <main class="reports-page">
    <header class="hero">
      <div>
        <span><i class="ri-bar-chart-box-line"></i> SYSTEM DELIVERY</span>
        <h1>系统报告</h1>
        <p>从真实任务和 AI Run 自动形成 WAO 建设日报、周报与月报。</p>
      </div>
      <button type="button" @click="settingsOpen = !settingsOpen">
        <i class="ri-settings-3-line"></i>{{ settingsOpen ? '收起设置' : '报告设置' }}
      </button>
    </header>

    <nav class="period-tabs" aria-label="系统报告周期">
      <div v-for="item in PERIODS" :key="item.id" :class="{ active: period === item.id }">
        <button class="period-select" type="button" @click="selectPeriod(item.id)">
          <b>{{ item.label }}</b
          ><small>{{ item.hint }}</small>
        </button>
        <button
          class="period-refresh"
          type="button"
          :aria-label="`重新统计${item.label}`"
          :title="`重新统计${item.label}`"
          :disabled="Boolean(generatingPeriod)"
          @click="generate(item.id)"
        >
          <i
            :class="generatingPeriod === item.id ? 'ri-loader-4-line spin' : 'ri-refresh-line'"
          ></i>
        </button>
      </div>
    </nav>

    <section v-if="settingsOpen" class="settings-card" aria-label="系统报告设置">
      <div v-if="settingsLoading" class="settings-state">
        <i class="ri-loader-4-line spin"></i>正在读取团队共享设置…
      </div>
      <div v-else-if="!settingsDraft" class="settings-state error">
        <span>{{ settingsError || '没有读取到报告设置' }}</span>
        <button type="button" @click="loadSettings">重新读取</button>
      </div>
      <form v-else @submit.prevent="saveSettings">
        <header>
          <div>
            <b>自动刷新</b>
            <small>设置由团队共享，主节点按上海时间读取；手动重新统计不受开关限制。</small>
          </div>
          <label class="switch-row">
            <input
              v-model="settingsDraft.automaticEnabled"
              type="checkbox"
              @change="settingsSaved = false"
            />
            <span>{{ settingsDraft.automaticEnabled ? '已开启' : '已关闭' }}</span>
          </label>
        </header>
        <div class="settings-grid">
          <label>
            <span>起始刷新时间</span>
            <input v-model="settingsDraft.refreshTime" type="time" @input="settingsSaved = false" />
          </label>
          <label>
            <span>刷新频率</span>
            <select
              v-model.number="settingsDraft.refreshFrequencyHours"
              @change="settingsSaved = false"
            >
              <option :value="1">每 1 小时</option>
              <option :value="3">每 3 小时</option>
              <option :value="6">每 6 小时</option>
              <option :value="12">每 12 小时</option>
              <option :value="24">每 24 小时</option>
            </select>
          </label>
        </div>
        <fieldset>
          <legend>自动刷新的报告</legend>
          <label v-for="item in PERIODS" :key="item.id">
            <input
              type="checkbox"
              :checked="settingsDraft.periods.includes(item.id)"
              @change="toggleSetting('periods', item.id)"
            />{{ item.label }}
          </label>
        </fieldset>
        <fieldset>
          <legend>报告汇总项</legend>
          <label v-for="item in AGGREGATIONS" :key="item.id">
            <input
              type="checkbox"
              :checked="settingsDraft.aggregationOptions.includes(item.id)"
              @change="toggleSetting('aggregationOptions', item.id)"
            />{{ item.label }}
          </label>
        </fieldset>
        <footer>
          <span :class="{ error: settingsError }">
            {{
              settingsError ||
              (settingsSaved
                ? '设置已保存'
                : '保存后用于后续自动刷新和手动重新统计；已有快照保持不变。')
            }}
          </span>
          <button type="submit" :disabled="settingsSaving">
            <i v-if="settingsSaving" class="ri-loader-4-line spin"></i
            >{{ settingsSaving ? '保存中…' : settingsError ? '重试保存' : '保存设置' }}
          </button>
        </footer>
      </form>
    </section>

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
        <button :disabled="Boolean(generatingPeriod)" @click="generate()">
          {{ generatingPeriod ? '正在重新统计…' : '立即补齐报告' }}
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
          <article v-if="includesCreated">
            <i class="ri-add-circle-line"></i>
            <div>
              <strong>{{ report.taskCreatedCount }}</strong
              ><span>新建任务</span>
            </div>
          </article>
          <article v-if="includesCompleted">
            <i class="ri-checkbox-circle-line"></i>
            <div>
              <strong>{{ report.taskCompletedCount }}</strong
              ><span>完成任务</span>
            </div>
          </article>
          <article v-if="includesRuns">
            <i class="ri-robot-2-line"></i>
            <div>
              <strong>{{ report.aiRunCount }}</strong
              ><span>AI Run</span>
            </div>
          </article>
          <article v-if="includesRuns">
            <i class="ri-check-double-line"></i>
            <div>
              <strong>{{ report.aiRunSuccessCount }}</strong
              ><span>运行成功</span>
            </div>
          </article>
          <article v-if="includesRuns">
            <i class="ri-error-warning-line"></i>
            <div>
              <strong>{{ report.aiRunFailureCount }}</strong
              ><span>运行失败</span>
            </div>
          </article>
          <article v-if="includesRuns">
            <i class="ri-coins-line"></i>
            <div>
              <strong>{{ formatTokens(report.totalTokens) }}</strong
              ><span>Token · {{ report.aiRunMeteredCount }}/{{ report.aiRunCount }} 有用量</span>
            </div>
          </article>
        </section>

        <section v-if="includesCreated || includesCompleted" class="activity-card">
          <header>
            <div>
              <span>TASK ACTIVITY</span>
              <h2>任务记录</h2>
            </div>
            <em>每页最多 {{ PAGE_SIZE }} 条</em>
          </header>
          <div v-if="report.tasks.length" class="task-columns">
            <div v-if="includesCreated">
              <h3>新建 · {{ createdTasks.length }}</h3>
              <a
                v-for="task in visibleCreatedTasks"
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
              <div v-if="createdTaskPages > 1" class="pagination">
                <button :disabled="createdTaskPage <= 1" @click="createdTaskPage--">上一页</button>
                <span>{{ createdTaskPage }} / {{ createdTaskPages }}</span>
                <button :disabled="createdTaskPage >= createdTaskPages" @click="createdTaskPage++">
                  下一页
                </button>
              </div>
            </div>
            <div v-if="includesCompleted">
              <h3>完成 · {{ completedTasks.length }}</h3>
              <a
                v-for="task in visibleCompletedTasks"
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
              <div v-if="completedTaskPages > 1" class="pagination">
                <button :disabled="completedTaskPage <= 1" @click="completedTaskPage--">
                  上一页
                </button>
                <span>{{ completedTaskPage }} / {{ completedTaskPages }}</span>
                <button
                  :disabled="completedTaskPage >= completedTaskPages"
                  @click="completedTaskPage++"
                >
                  下一页
                </button>
              </div>
            </div>
          </div>
          <p v-else class="empty-text">这个周期没有新建或完成的任务。</p>
        </section>

        <section v-if="includesRuns" class="activity-card">
          <header>
            <div>
              <span>AI RUN ACTIVITY</span>
              <h2>运行记录</h2>
            </div>
            <em>共 {{ report.runs.length }} 条 · 每页最多 {{ PAGE_SIZE }} 条</em>
          </header>
          <div v-if="report.runs.length" class="run-list">
            <a v-for="run in visibleRuns" :key="run.id" :href="taskUrl(run.projectId, run.taskId)">
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
          <div v-if="runPages > 1" class="pagination run-pagination">
            <button :disabled="runPage <= 1" @click="runPage--">上一页</button>
            <span>第 {{ runPage }} / {{ runPages }} 页</span>
            <button :disabled="runPage >= runPages" @click="runPage++">下一页</button>
          </div>
          <p v-if="!report.runs.length" class="empty-text">这个周期没有启动过 AI Run。</p>
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
.period-tabs > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 38px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  overflow: hidden;
}
.period-tabs button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.period-select {
  min-width: 0;
  padding: 11px;
  text-align: left;
}
.period-refresh {
  border-left: 1px solid var(--wao-line) !important;
  font-size: 15px;
}
.period-refresh:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.period-select b,
.period-select small {
  display: block;
}
.period-select small {
  margin-top: 3px;
  color: var(--wao-text-weak);
  font-size: 9px;
}
.period-tabs > .active {
  border-color: #4f8792;
  background: color-mix(in srgb, #356c78 8%, var(--wao-bg-card));
  color: #356c78;
}
.settings-card {
  margin-top: 12px;
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-card);
}
.settings-card form > header,
.settings-card footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
}
.settings-card header b,
.settings-card header small {
  display: block;
}
.settings-card header small,
.settings-card footer span {
  margin-top: 3px;
  color: var(--wao-text-weak);
  font-size: 9.5px;
}
.switch-row,
.settings-card fieldset label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
}
.settings-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.settings-grid label > span {
  display: block;
  margin-bottom: 5px;
  color: var(--wao-text-weak);
  font-size: 9.5px;
}
.settings-grid input,
.settings-grid select {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.settings-card fieldset {
  margin: 13px 0 0;
  padding: 10px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.settings-card legend {
  padding: 0 5px;
  color: var(--wao-text-weak);
  font-size: 9.5px;
}
.settings-card footer {
  margin-top: 13px;
}
.settings-card footer span {
  flex: 1;
}
.settings-card footer span.error,
.settings-state.error {
  color: var(--wao-danger-strong);
}
.settings-card footer button,
.settings-state button {
  padding: 8px 12px;
  border: 0;
  border-radius: 8px;
  background: #356c78;
  color: #fff;
  cursor: pointer;
}
.settings-card footer button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.settings-state {
  min-height: 90px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wao-text-weak);
  font-size: 10.5px;
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
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
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
  min-width: 0;
  overflow: hidden;
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
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.task-columns > div {
  min-width: 0;
  overflow: hidden;
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
.run-list small {
  display: block;
  overflow-wrap: anywhere;
}
.run-list {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}
.run-list > a {
  min-width: 0;
  overflow: hidden;
}
.run-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.run-head b {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.pagination {
  margin-top: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wao-text-weak);
  font-size: 9px;
}
.pagination button {
  padding: 5px 8px;
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  cursor: pointer;
}
.pagination button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.run-pagination {
  margin-top: 12px;
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
  .period-tabs {
    grid-template-columns: 1fr;
  }
  .settings-grid {
    grid-template-columns: 1fr;
  }
  .settings-card form > header,
  .settings-card footer {
    align-items: flex-start;
    flex-direction: column;
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
