<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  clearRequestMetricHistory,
  fetchRequestMetricOverview,
  type RequestMetricOverview,
} from '@/api/requestMetrics'
import { toast } from '@/composables/toast'
import { apiRunJob } from '@/api/board'
import HttpPerformanceConfigModal from '@/components/overlays/HttpPerformanceConfigModal.vue'
import HttpInspectionHistoryModal from '@/components/overlays/HttpInspectionHistoryModal.vue'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import {
  pauseRequestMetricReporter,
  resumeRequestMetricReporter,
} from '@/utils/requestMetricReporter'

const overview = ref<RequestMetricOverview | null>(null)
const loading = ref(true)
const error = ref('')
const hours = ref(24)
const clearing = ref(false)
const startingInspection = ref(false)
const configOpen = ref(false)
const historyOpen = ref(false)
const TABLE_PAGE_SIZE = 20
const endpointPage = ref(1)
const recentPage = ref(1)
type EndpointSort = 'failedCount' | 'averageDurationMs' | 'p95DurationMs'
const endpointSort = ref<EndpointSort>('failedCount')
const endpointSection = ref<HTMLElement | null>(null)
let loadVersion = 0
const attention = computed(() => {
  const value = overview.value
  if (!value || !value.totalRequests) return null
  if (value.failedRequests > 0) return { title: '先关注未成功的请求', detail: `有 ${value.failedRequests} 次请求未成功，可能对应加载或保存失败。先查看失败较多的接口，再结合当时的操作判断影响。`, sort: 'failedCount' as const, action: '查看失败较多的接口' }
  if (value.slowRequests > 0) return { title: '有等待较久的请求', detail: `有 ${value.slowRequests} 次请求等待至少 1 秒，可能影响操作流畅度。可先查看平均耗时较长的接口。`, sort: 'averageDurationMs' as const, action: '查看耗时较长的接口' }
  return { title: '当前样本未发现失败或慢请求', detail: '可以继续正常使用。这里只反映当前账号在这一时间段采集到的请求，不代表全站或所有成员的状态。', sort: null, action: '' }
})
async function inspectEndpoints(field: EndpointSort) {
  sortEndpoints(field)
  await nextTick()
  endpointSection.value?.focus()
  endpointSection.value?.scrollIntoView({ block: 'start' })
}

const cards = computed(() => {
  const value = overview.value
  return [
    { label: '请求总数', value: value?.totalRequests ?? 0, unit: '次', tone: 'normal' },
    { label: '平均耗时', value: value?.averageDurationMs ?? 0, unit: 'ms', tone: 'normal' },
    { label: 'P95 耗时', value: value?.p95DurationMs ?? 0, unit: 'ms', tone: 'warning' },
    { label: '慢请求', value: value?.slowRequests ?? 0, unit: '次', tone: 'warning' },
    { label: '失败请求', value: value?.failedRequests ?? 0, unit: '次', tone: 'danger' },
  ]
})
const endpointPageCount = computed(() =>
  Math.max(1, Math.ceil((overview.value?.endpoints.length ?? 0) / TABLE_PAGE_SIZE)),
)
const recentPageCount = computed(() =>
  Math.max(1, Math.ceil((overview.value?.recentRequests.length ?? 0) / TABLE_PAGE_SIZE)),
)
const sortedEndpoints = computed(() =>
  [...(overview.value?.endpoints ?? [])].sort((left, right) => {
    const selected = right[endpointSort.value] - left[endpointSort.value]
    if (selected !== 0) return selected
    const byFailures = right.failedCount - left.failedCount
    if (byFailures !== 0) return byFailures
    const byAverage = right.averageDurationMs - left.averageDurationMs
    if (byAverage !== 0) return byAverage
    const byP95 = right.p95DurationMs - left.p95DurationMs
    return byP95 !== 0 ? byP95 : left.path.localeCompare(right.path)
  }),
)
const visibleEndpoints = computed(() =>
  sortedEndpoints.value.slice(
    (endpointPage.value - 1) * TABLE_PAGE_SIZE,
    endpointPage.value * TABLE_PAGE_SIZE,
  ),
)
const visibleRecentRequests = computed(() =>
  (overview.value?.recentRequests ?? []).slice(
    (recentPage.value - 1) * TABLE_PAGE_SIZE,
    recentPage.value * TABLE_PAGE_SIZE,
  ),
)

async function load() {
  const version = ++loadVersion
  const requestedHours = hours.value
  if (overview.value?.windowHours !== requestedHours) overview.value = null
  loading.value = true
  error.value = ''
  try {
    const result = await fetchRequestMetricOverview(requestedHours)
    if (version !== loadVersion) return
    overview.value = result
    endpointPage.value = 1
    recentPage.value = 1
  } catch {
    if (version === loadVersion) error.value = '请求监控加载失败，请检查网络后重试'
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function startInspection() {
  if (startingInspection.value) return
  startingInspection.value = true
  try {
    const job = await apiRunJob('job-http-performance-ai-run')
    const result = job.runs[0]?.result ?? job.last
    if (result.includes('失败') || result.includes('跳过')) {
      toast(result, 'ri-error-warning-line')
    } else {
      toast(result || 'HTTP 性能 AI 巡检已启动', 'ri-rocket-line')
    }
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '巡检启动失败，请重试', 'ri-error-warning-line')
  } finally {
    startingInspection.value = false
  }
}

function sortEndpoints(field: EndpointSort) {
  endpointSort.value = field
  endpointPage.value = 1
}

async function clearHistory() {
  if (!window.confirm('清空当前账号的全部 HTTP 请求历史？此操作不可恢复。')) return
  clearing.value = true
  try {
    await pauseRequestMetricReporter()
    const result = await clearRequestMetricHistory()
    overview.value = null
    await load()
    toast(`已清空 ${result.cleared} 条 HTTP 请求记录`, 'ri-delete-bin-line')
  } catch {
    error.value = '历史记录清空失败，请检查网络后重试'
  } finally {
    resumeRequestMetricReporter()
    clearing.value = false
  }
}

function durationClass(duration: number) {
  if (duration >= 3_000) return 'danger'
  if (duration >= 1_000) return 'warning'
  return ''
}

function statusText(status: number | null) {
  return status === null ? '网络错误' : String(status)
}

function formatTime(raw: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(raw))
}

onMounted(load)
</script>

<template>
  <main class="monitor-page wao-page-frame">
    <header class="hero">
      <div>
        <span>NETWORK EXPERIENCE</span>
        <h1>HTTP 请求监控</h1>
        <p>浏览器端到端实测，包含当前网络、网关与服务端的完整等待时间。</p>
      </div>
      <div class="actions">
        <button class="secondary-button history-button" @click="historyOpen = true">
          <i class="ri-history-line"></i>巡检历史
        </button>
        <select v-model.number="hours" :disabled="clearing" aria-label="统计时间范围" @change="load">
          <option :value="1">最近 1 小时</option>
          <option :value="6">最近 6 小时</option>
          <option :value="24">最近 24 小时</option>
          <option :value="72">最近 3 天</option>
          <option :value="168">最近 7 天</option>
        </select>
        <button :disabled="loading || clearing" @click="load">
          <i :class="loading ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>
          {{ loading ? '刷新中' : '刷新' }}
        </button>
        <button class="clear-button" :disabled="loading || clearing" @click="clearHistory">
          <i :class="clearing ? 'ri-loader-4-line spin' : 'ri-delete-bin-line'"></i>
          {{ clearing ? '清空中' : '清空历史' }}
        </button>
      </div>
    </header>

    <section v-if="loading && !overview" class="state-card">
      <i class="ri-loader-4-line spin"></i><span>正在汇总真实请求记录…</span>
    </section>
    <section v-else-if="error && !overview" class="state-card error-state">
      <i class="ri-wifi-off-line"></i><strong>监控数据暂时不可用</strong><span>{{ error }}</span>
      <button @click="load">重新加载</button>
    </section>
    <template v-else-if="overview">
      <div v-if="error" class="inline-error">
        <i class="ri-error-warning-line"></i><span>{{ error }}，已保留上一次结果。</span
        ><button @click="load">重试</button>
      </div>
      <section class="attention-summary" aria-label="请求体验摘要" :aria-busy="loading">
        <p class="sample-context">当前账号 · 最近 {{ overview.windowHours }} 小时 · 统计于 {{ formatTime(overview.generatedAt) }}</p>
        <p v-if="loading" role="status">正在刷新；下方暂时显示上一次统计结果。</p>
        <template v-if="attention">
          <h2>{{ attention.title }}</h2>
          <p>{{ attention.detail }}</p>
          <div class="summary-actions">
            <button v-if="attention.sort" type="button" @click="inspectEndpoints(attention.sort)">{{ attention.action }}</button>
            <button type="button" @click="historyOpen = true">查看巡检启动记录</button>
          </div>
        </template>
        <p v-else>暂时没有可判断的样本。使用 WAO 后再刷新，或扩大统计时间范围。</p>
        <details><summary>这些数字代表什么？</summary><p>慢请求指等待至少 1 秒；失败包括网络错误及 HTTP 4xx / 5xx。慢和失败可能是同一次请求，不能直接相加。P95 表示约 95% 的请求在该耗时内结束；这些数字不能单独判断故障原因。</p></details>
      </section>
      <section class="metric-grid">
        <article v-for="card in cards" :key="card.label" :class="card.tone">
          <span>{{ card.label }}</span
          ><strong
            >{{ card.value }}<small>{{ card.unit }}</small></strong
          >
        </article>
      </section>

      <section v-if="overview.totalRequests === 0" class="state-card empty-state">
        <i class="ri-pulse-line"></i><strong>这个时间段还没有请求记录</strong>
        <span>继续使用项目或密码本后再刷新；这里不会填充演示数据。</span>
      </section>
      <template v-else>
        <div class="request-tables">
          <section ref="endpointSection" class="table-card" tabindex="-1" aria-label="优先关注的接口">
            <header>
              <div>
                <span>ENDPOINTS</span>
                <h2>优先关注的接口</h2>
              </div>
              <small>按所选指标倒序 · 慢请求 ≥ 1000 ms</small>
            </header>
            <p class="endpoint-scope">接口列表最多展示 50 项；排序仅针对当前列表，失败总数以统计卡片为准。</p>
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>接口</th>
                    <th>次数</th>
                    <th>
                      <button
                        type="button"
                        class="sort-button"
                        :class="{ active: endpointSort === 'averageDurationMs' }"
                        @click="sortEndpoints('averageDurationMs')"
                      >
                        平均<i class="ri-arrow-down-line"></i>
                      </button>
                    </th>
                    <th>
                      <button
                        type="button"
                        class="sort-button"
                        :class="{ active: endpointSort === 'p95DurationMs' }"
                        @click="sortEndpoints('p95DurationMs')"
                      >
                        P95<i class="ri-arrow-down-line"></i>
                      </button>
                    </th>
                    <th>慢请求</th>
                    <th>
                      <button
                        type="button"
                        class="sort-button"
                        :class="{ active: endpointSort === 'failedCount' }"
                        @click="sortEndpoints('failedCount')"
                      >
                        失败<i class="ri-arrow-down-line"></i>
                      </button>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in visibleEndpoints" :key="`${item.method}:${item.path}`">
                    <td>
                      <b>{{ item.method }}</b
                      ><code>{{ item.path }}</code>
                    </td>
                    <td>{{ item.requestCount }}</td>
                    <td :class="durationClass(item.averageDurationMs)">
                      {{ item.averageDurationMs }} ms
                    </td>
                    <td :class="durationClass(item.p95DurationMs)">{{ item.p95DurationMs }} ms</td>
                    <td>{{ item.slowCount }}</td>
                    <td :class="{ danger: item.failedCount }">{{ item.failedCount }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <WaoPagination
              v-if="overview.endpoints.length > TABLE_PAGE_SIZE"
              v-model:page="endpointPage"
              class="pagination"
              :page-count="endpointPageCount"
              :total="overview.endpoints.length"
              aria-label="监控接口分页"
            />
          </section>

          <section class="table-card">
            <header>
              <div>
                <span>RECENT REQUESTS</span>
                <h2>最近请求明细</h2>
              </div>
              <small>仅保存去除查询参数后的路径</small>
            </header>
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>接口</th>
                    <th>状态</th>
                    <th>耗时</th>
                    <th>网络</th>
                    <th>尝试</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in visibleRecentRequests" :key="item.id">
                    <td>{{ formatTime(item.startedAt) }}</td>
                    <td>
                      <b>{{ item.method }}</b
                      ><code>{{ item.path }}</code>
                    </td>
                    <td :class="{ danger: item.statusCode === null || item.statusCode >= 400 }">
                      {{ statusText(item.statusCode) }}
                    </td>
                    <td :class="durationClass(item.durationMs)">{{ item.durationMs }} ms</td>
                    <td>{{ item.networkType || '未知' }}</td>
                    <td>{{ item.retryAttempt ? `重试 ${item.retryAttempt}` : '首次' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <WaoPagination
              v-if="overview.recentRequests.length > TABLE_PAGE_SIZE"
              v-model:page="recentPage"
              class="pagination"
              :page-count="recentPageCount"
              :total="overview.recentRequests.length"
              aria-label="最近请求分页"
            />
          </section>
        </div>
      </template>
    </template>
    <HttpInspectionHistoryModal v-if="historyOpen" @close="historyOpen = false" />
  </main>
</template>

<style scoped>
.attention-summary{margin-top:18px;padding:18px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card);line-height:1.7;overflow-wrap:anywhere}.attention-summary h2{font-size:var(--wao-font-section);margin:8px 0}.attention-summary p{margin:8px 0;font-size:var(--wao-font-help)}.sample-context,.attention-summary details{color:var(--wao-text-3);font-size:var(--wao-font-help)}.summary-actions{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}.summary-actions button{padding:8px 12px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-sub);color:var(--wao-primary-text);cursor:pointer}.attention-summary summary{cursor:pointer}.table-card:focus-visible{outline:2px solid var(--wao-primary-text);outline-offset:3px}
.endpoint-scope{padding:0 16px;color:var(--wao-text-3);font-size:var(--wao-font-help);line-height:1.6}
.monitor-page {
  max-width: none;
  margin: 0 auto;
  padding: 24px 28px 60px;
  color: var(--wao-text);
}
.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
}
.hero span,
.table-card header span {
  color: #56736b;
  font-size: var(--wao-font-body);
  font-weight: 800;
  letter-spacing: 1.4px;
}
.hero h1 {
  margin: 5px 0 3px;
  font-size: 27px;
}
.hero p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: var(--wao-font-caption);
}
.actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}
.actions select,
.actions button,
.state-card button {
  min-height: 36px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  padding: 0 11px;
  cursor: pointer;
}
.actions button {
  background: #4f7167;
  color: white;
  border-color: #4f7167;
}
.actions .inspection-button {
  background: var(--wao-bg-card);
  color: var(--wao-danger-strong);
  border-color: #dfaaa4;
}
.actions .secondary-button {
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  border-color: var(--wao-line-control);
}
.actions .clear-button {
  border-color: #d9b7b3;
  background: var(--wao-bg-card);
  color: var(--wao-danger-strong);
}
.actions button:disabled {
  opacity: 0.55;
  cursor: default;
}
.metric-grid {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 9px;
}
.metric-grid article {
  padding: 15px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-card);
}
.metric-grid span {
  display: block;
  color: var(--wao-text-weak);
  font-size: var(--wao-font-caption);
}
.metric-grid strong {
  display: block;
  margin-top: 6px;
  font-size: 24px;
}
.metric-grid small {
  margin-left: 4px;
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
}
.metric-grid .warning strong,
.warning {
  color: #b36b12;
}
.metric-grid .danger strong,
.danger {
  color: var(--wao-danger-strong);
}
.table-card {
  margin-top: 12px;
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.request-tables {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}
.request-tables .table-card {
  min-width: 0;
}
.table-card header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 12px;
}
.table-card h2 {
  margin: 3px 0 0;
  font-size: var(--wao-font-body);
}
.table-card header small {
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
}
.table-scroll {
  margin-top: 12px;
  overflow-x: auto;
}
table {
  width: 100%;
  min-width: 650px;
  border-collapse: collapse;
}
.pagination {
  min-height: 38px;
  font-size: var(--wao-font-caption);
}
th,
td {
  padding: 9px 10px;
  border-top: 1px solid var(--wao-line);
  text-align: right;
  white-space: nowrap;
  font-size: var(--wao-font-caption);
}
th {
  border-top: 0;
  color: var(--wao-text-weak);
  font-size: var(--wao-font-caption);
}
.sort-button {
  height: 25px;
  padding: 0 4px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
  font: inherit;
  font-weight: 650;
}
.sort-button i {
  margin-left: 2px;
  opacity: 0.35;
}
.sort-button.active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.sort-button.active i {
  opacity: 1;
}
th:first-child,
td:first-child,
td:nth-child(2) {
  text-align: left;
}
td:first-child {
  max-width: 520px;
}
td b {
  display: inline-block;
  min-width: 45px;
  color: #4f7167;
  font-size: var(--wao-font-body);
}
td code {
  color: var(--wao-text-2);
  white-space: normal;
  overflow-wrap: anywhere;
}
.state-card {
  min-height: 250px;
  margin-top: 18px;
  border: 1px dashed var(--wao-line);
  border-radius: 12px;
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 8px;
  color: var(--wao-text-weak);
  text-align: center;
}
.state-card > i {
  font-size: 28px;
  color: #5d8177;
}
.state-card strong {
  color: var(--wao-text);
}
.state-card span {
  font-size: var(--wao-font-caption);
}
.inline-error {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  display: flex;
  gap: 8px;
  font-size: var(--wao-font-caption);
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
@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: repeat(3, 1fr);
  }
  .request-tables {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  .monitor-page {
    padding: 16px 14px 46px;
  }
  .hero {
    align-items: flex-start;
    flex-direction: column;
  }
  .actions {
    width: 100%;
    flex-wrap: wrap;
  }
  .actions select {
    flex: 1;
  }
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .table-card {
    padding: 13px;
  }
  .table-card header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
