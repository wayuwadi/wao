<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchAutomationJob } from '@/api/board'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import type { AutomationJob, JobRun } from '@/types'

const emit = defineEmits<{ close: [] }>()

const JOB_ID = 'job-http-performance-ai-run'
const PAGE_SIZE = 10
const job = ref<AutomationJob | null>(null)
const loading = ref(true)
const error = ref('')
const page = ref(1)

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

async function load() {
  loading.value = true
  error.value = ''
  try {
    job.value = await fetchAutomationJob(JOB_ID)
    page.value = 1
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '巡检历史读取失败，请重试'
  } finally {
    loading.value = false
  }
}

function duration(run: JobRun) {
  if (run.durationMs == null) return '未记录'
  if (run.durationMs < 1_000) return `${run.durationMs} ms`
  return `${Math.max(1, Math.round(run.durationMs / 1_000))} 秒`
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
      <template v-else>
        <div class="run-list">
          <article
            v-for="(run, index) in visibleRuns"
            :key="`${run.runAt ?? run.time}-${index}`"
            :class="tone(run.result)"
          >
            <div>
              <time>{{ run.time }}</time
              ><span>{{ run.executor }}</span
              ><span>{{ duration(run) }}</span>
            </div>
            <p>{{ run.result }}</p>
          </article>
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
.run-list article {
  padding: 11px 12px;
  border: 1px solid var(--wao-line);
  border-left: 3px solid #4f8a70;
  border-radius: 9px;
  background: var(--wao-bg-sub);
}
.run-list article.failed {
  border-left-color: var(--wao-danger-strong);
}
.run-list article.skipped {
  border-left-color: #b36b12;
}
.run-list article > div {
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
</style>
