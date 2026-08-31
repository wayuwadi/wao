<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { fetchAiRun } from '@/api/ai'
import { fetchAutomationJob } from '@/api/board'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import type { AiRun, AutomationJob, JobRun } from '@/types'

const emit = defineEmits<{ close: [] }>()

const JOB_ID = 'job-http-performance-ai-run'
const PAGE_SIZE = 10
const job = ref<AutomationJob | null>(null)
const loading = ref(true)
const error = ref('')
const page = ref(1)
const dialogElement = ref<HTMLElement | null>(null)
const selected = ref<JobRun | null>(null)
const linkedAiRun = ref<AiRun | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

const runs = computed(() =>
  (job.value?.runs ?? [])
    .map((run, order) => ({ run, order }))
    .sort((left, right) => {
      const byTime = Date.parse(right.run.runAt ?? '') - Date.parse(left.run.runAt ?? '')
      return Number.isNaN(byTime) || byTime === 0 ? left.order - right.order : byTime
    })
    .map(({ run }) => run),
)
const pageCount = computed(() => Math.max(1, Math.ceil(runs.value.length / PAGE_SIZE)))
const visibleRuns = computed(() =>
  runs.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE),
)
const statistics = computed(() => {
  const values = runs.value
  const measured = values.filter((run) => run.durationMs != null).map((run) => run.durationMs!)
  return {
    total: values.length,
    created: values.filter((run) => tone(run.result) === 'success').length,
    failed: values.filter((run) => tone(run.result) === 'failed').length,
    skipped: values.filter((run) => tone(run.result) === 'skipped').length,
    averageMs: measured.length
      ? Math.round(measured.reduce((sum, value) => sum + value, 0) / measured.length)
      : null,
  }
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    job.value = await fetchAutomationJob(JOB_ID)
    page.value = 1
    selected.value = null
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '巡检历史读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function openDetail(run: JobRun) {
  selected.value = run
  linkedAiRun.value = null
  detailError.value = ''
  await nextTick()
  dialogElement.value?.scrollTo?.({ top: 0 })
  if (!run.aiRunId) return
  await loadLinkedAiRun(run.aiRunId)
}

async function loadLinkedAiRun(aiRunId: string) {
  detailLoading.value = true
  detailError.value = ''
  try {
    linkedAiRun.value = await fetchAiRun(aiRunId)
  } catch (cause) {
    detailError.value = cause instanceof Error ? cause.message : 'AI Run 详情读取失败，请重试'
  } finally {
    detailLoading.value = false
  }
}

function aiRunDuration(run: AiRun) {
  if (!run.startedAt || !run.finishedAt) return '未结束'
  const milliseconds = Date.parse(run.finishedAt) - Date.parse(run.startedAt)
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '未记录'
  return formatDurationMs(milliseconds)
}

function aiRunStatus(run: AiRun) {
  if (run.status === 'succeeded') return '运行成功'
  if (run.status === 'failed') return '运行失败'
  if (run.status === 'cancelled') return '已取消'
  return '仍在运行'
}

function duration(run: JobRun) {
  return formatDurationMs(run.durationMs)
}

function formatDurationMs(milliseconds: number | null | undefined) {
  if (milliseconds == null) return '未记录'
  if (milliseconds < 1_000) return `${milliseconds} ms`
  return `${Math.max(1, Math.round(milliseconds / 1_000))} 秒`
}

function tone(result: string) {
  if (result.includes('失败')) return 'failed'
  if (result.includes('跳过')) return 'skipped'
  return 'success'
}

onMounted(load)
</script>

<template>
  <div class="mask" @click.self="emit('close')">
    <section
      ref="dialogElement"
      class="dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="inspection-history-title"
    >
      <header>
        <div>
          <span>REAL AUTOMATION RUNS</span>
          <h2 id="inspection-history-title">AI 巡检历史</h2>
          <p>这里展示自动与手动巡检的真实运行记录，不填充演示结果。</p>
        </div>
        <button type="button" aria-label="关闭" @click="emit('close')">
          <i class="ri-close-line"></i>
        </button>
      </header>

      <div v-if="loading" class="state"><i class="ri-loader-4-line spin"></i>正在读取巡检历史…</div>
      <div v-else-if="error" class="state error" role="alert">
        <i class="ri-error-warning-line"></i><b>巡检历史暂时不可用</b><span>{{ error }}</span>
        <button type="button" @click="load">重新读取</button>
      </div>
      <div v-else-if="!runs.length" class="state empty">
        <i class="ri-history-line"></i><b>还没有巡检记录</b>
        <span>首次自动巡检到期或手动启动后，真实结果会出现在这里。</span>
      </div>
      <template v-else-if="selected">
        <button type="button" class="back-button" @click="selected = null">
          <i class="ri-arrow-left-line"></i>返回巡检历史
        </button>
        <section class="run-detail" :class="tone(selected.result)">
          <header>
            <div><span>触发时间</span><b>{{ selected.time }}</b></div>
            <strong>{{ tone(selected.result) === 'success' ? '已创建 AI Run' : tone(selected.result) === 'failed' ? '启动失败' : '已跳过' }}</strong>
          </header>
          <dl>
            <div><dt>执行方式</dt><dd>{{ selected.executor }}</dd></div>
            <div><dt>启动耗时</dt><dd>{{ duration(selected) }}</dd></div>
            <div><dt>记录编号</dt><dd>{{ selected.id || '旧记录未提供' }}</dd></div>
            <div><dt>AI Run</dt><dd>{{ selected.aiRunId || '本次未创建' }}</dd></div>
          </dl>
          <section class="result-block"><b>自动化结果</b><p>{{ selected.result }}</p></section>
        </section>

        <div v-if="detailLoading" class="detail-state"><i class="ri-loader-4-line spin"></i>正在读取 AI Run 详情…</div>
        <div v-else-if="detailError" class="detail-state error" role="alert">
          <span>{{ detailError }}</span>
          <button type="button" @click="loadLinkedAiRun(selected.aiRunId!)">重新读取</button>
        </div>
        <section v-else-if="linkedAiRun" class="ai-run-detail">
          <header><div><small>真实 AI RUN</small><h3>{{ linkedAiRun.taskTitle }}</h3></div><strong>{{ aiRunStatus(linkedAiRun) }}</strong></header>
          <dl>
            <div><dt>项目</dt><dd>{{ linkedAiRun.projectName }}</dd></div>
            <div><dt>AI 工具</dt><dd>{{ linkedAiRun.engine }}</dd></div>
            <div><dt>执行电脑</dt><dd>{{ linkedAiRun.runnerName || '尚未分配' }}</dd></div>
            <div><dt>运行耗时</dt><dd>{{ aiRunDuration(linkedAiRun) }}</dd></div>
            <div><dt>Token</dt><dd>{{ linkedAiRun.usage?.recorded ? linkedAiRun.usage.totalTokens.toLocaleString() : '未记录' }}</dd></div>
            <div><dt>退出码</dt><dd>{{ linkedAiRun.exitCode ?? '未结束' }}</dd></div>
          </dl>
          <section class="result-block"><b>{{ linkedAiRun.status === 'failed' ? '失败原因' : '运行结果' }}</b><p>{{ linkedAiRun.resultSummary || 'AI 尚未回传最终摘要。' }}</p></section>
          <a v-if="linkedAiRun.reportUrl" :href="linkedAiRun.reportUrl" target="_blank" rel="noopener">打开验收报告<i class="ri-external-link-line"></i></a>
        </section>
        <div v-else class="detail-state muted"><i class="ri-information-line"></i>本次巡检未创建 AI Run，以上自动化结果就是完整事实。</div>
      </template>
      <template v-else>
        <div class="statistics" aria-label="AI 巡检统计">
          <div><span>总次数</span><b>{{ statistics.total }}</b></div>
          <div><span>已创建</span><b>{{ statistics.created }}</b></div>
          <div><span>启动失败</span><b>{{ statistics.failed }}</b></div>
          <div><span>已跳过</span><b>{{ statistics.skipped }}</b></div>
          <div><span>平均启动</span><b>{{ formatDurationMs(statistics.averageMs) }}</b></div>
        </div>
        <div class="run-list">
          <button
            v-for="(run, index) in visibleRuns"
            :key="run.id || `${run.runAt ?? run.time}-${index}`"
            type="button"
            :class="tone(run.result)"
            @click="openDetail(run)"
          >
            <div>
              <time>{{ run.time }}</time
              ><span>{{ run.executor }}</span
              ><span>{{ duration(run) }}</span>
            </div>
            <p>{{ run.result }}</p>
            <i class="ri-arrow-right-s-line"></i>
          </button>
        </div>
        <WaoPagination
          v-if="runs.length > PAGE_SIZE"
          v-model:page="page"
          class="history-pagination"
          :page-count="pageCount"
          :total="runs.length"
          aria-label="AI 巡检历史分页"
        />
      </template>

      <footer><button type="button" @click="emit('close')">关闭</button></footer>
    </section>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 18px;
  background: rgba(18, 20, 30, 0.38);
}
.dialog {
  width: min(720px, 100%);
  max-height: calc(100vh - 36px);
  overflow: auto;
  padding: 20px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
}
.dialog > header {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}
.dialog > header > div {
  flex: 1;
}
.dialog header span {
  color: var(--wao-primary-text);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 1.3px;
}
.dialog h2 {
  margin: 4px 0 0;
  font-size: 19px;
}
.dialog header p {
  margin: 5px 0 0;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.dialog > header > button {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.state {
  min-height: 250px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  color: var(--wao-text-weak);
  text-align: center;
  font-size: 11px;
}
.state > i {
  font-size: 26px;
}
.state.error {
  color: var(--wao-danger-strong);
}
.state button,
.dialog > footer button {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
}
.run-list {
  margin-top: 18px;
  display: grid;
  gap: 8px;
}
.statistics {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}
.statistics > div {
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-sub);
}
.statistics span, .statistics b { display: block; }
.statistics span { color: var(--wao-text-weak); font-size: 9px; }
.statistics b { margin-top: 4px; color: var(--wao-text); font-size: 15px; overflow-wrap: anywhere; }
.run-list > button {
  position: relative;
  width: 100%;
  padding: 11px 38px 11px 12px;
  text-align: left;
  border: 1px solid var(--wao-line);
  border-left: 3px solid #4f8a70;
  border-radius: 9px;
  background: var(--wao-bg-sub);
  cursor: pointer;
}
.run-list > button:hover { border-color: var(--wao-primary-line); }
.run-list > button.failed {
  border-left-color: var(--wao-danger-strong);
}
.run-list > button.skipped {
  border-left-color: #b36b12;
}
.run-list > button > div {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  color: var(--wao-text-weak);
  font-size: 10px;
}
.run-list time {
  color: var(--wao-text-2);
  font-weight: 650;
}
.run-list p {
  margin: 7px 0 0;
  color: var(--wao-text);
  font-size: 11px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.run-list > button > i { position: absolute; right: 12px; top: 50%; transform: translateY(-50%); color: var(--wao-text-weak); }
.back-button {
  margin-top: 16px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--wao-primary-text);
  cursor: pointer;
}
.run-detail, .ai-run-detail { margin-top: 14px; padding: 14px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-sub); }
.run-detail { border-left: 3px solid #4f8a70; }
.run-detail.failed { border-left-color: var(--wao-danger-strong); }
.run-detail.skipped { border-left-color: #b36b12; }
.run-detail > header, .ai-run-detail > header { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }
.run-detail header span, .ai-run-detail small { color: var(--wao-text-weak); font-size: 9px; }
.run-detail header b, .ai-run-detail h3 { display: block; margin: 3px 0 0; color: var(--wao-text); font-size: 14px; }
.run-detail header strong, .ai-run-detail header strong { color: var(--wao-primary-text); font-size: 11px; }
.run-detail dl, .ai-run-detail dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 14px 0 0; }
.run-detail dl > div, .ai-run-detail dl > div { min-width: 0; }
.run-detail dt, .ai-run-detail dt { color: var(--wao-text-weak); font-size: 9px; }
.run-detail dd, .ai-run-detail dd { margin: 3px 0 0; color: var(--wao-text); font-size: 11px; overflow-wrap: anywhere; }
.result-block { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--wao-line); }
.result-block b { font-size: 10px; }
.result-block p { margin: 5px 0 0; color: var(--wao-text-2); font-size: 11px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.ai-run-detail > a { display: inline-flex; gap: 5px; margin-top: 12px; color: var(--wao-primary-text); font-size: 11px; text-decoration: none; }
.detail-state { min-height: 100px; display: flex; align-items: center; justify-content: center; gap: 8px; color: var(--wao-text-weak); font-size: 11px; text-align: center; }
.detail-state.error { color: var(--wao-danger-strong); }
.detail-state button { height: 30px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.dialog > footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 640px) {
  .statistics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .run-detail dl, .ai-run-detail dl { grid-template-columns: 1fr; }
}
</style>
