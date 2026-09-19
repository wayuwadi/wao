<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  fetchDataCenterOverview,
  type DataCenterOverview,
  type DataCenterSource,
  type DataCenterStatus,
} from '@/api/dataCenter'

const overview = ref<DataCenterOverview | null>(null)
const loading = ref(true)
const error = ref('')
const selectedCategory = ref('全部')
const selectedStatus = ref<DataCenterStatus | 'ALL'>('ALL')

const categories = computed(() => [
  '全部',
  ...new Set((overview.value?.sources ?? []).map((source) => source.category)),
])
const visibleSources = computed(() =>
  (overview.value?.sources ?? []).filter(source =>
    (selectedCategory.value === '全部' || source.category === selectedCategory.value)
    && (selectedStatus.value === 'ALL' || source.status === selectedStatus.value)),
)

const statusMeta: Record<DataCenterStatus, { label: string; icon: string }> = {
  READY: { label: '有记录和写入时间', icon: 'ri-checkbox-circle-line' },
  TIME_UNKNOWN: { label: '时间未知', icon: 'ri-time-line' },
  EMPTY: { label: '暂无数据', icon: 'ri-inbox-2-line' },
  REGISTERED_ONLY: { label: '仅登记', icon: 'ri-link' },
}
const statusCounts = computed(() => Object.entries(statusMeta).map(([status, meta]) => ({
  status: status as DataCenterStatus, label: meta.label,
  count: (overview.value?.sources ?? []).filter(source => source.status === status).length,
})))
const sourceDestinations: Record<string, { path: string; label: string }> = {
  projects: { path: '/projects', label: '查看项目' },
  tasks: { path: '/todo', label: '查看待办' },
  'ai-runs': { path: '/ai-sessions', label: '查看 AI 会话' },
  'knowledge-documents': { path: '/knowledge', label: '查看知识库' },
  'memory-revisions': { path: '/memory', label: '查看记忆' },
  'shared-chat-messages': { path: '/chat', label: '查看共享聊天' },
  'http-request-metrics': { path: '/request-monitoring', label: '查看请求监控' },
  'product-updates': { path: '/updates', label: '查看更新日记' },
}
function guidance(source: DataCenterSource) {
  if (source.status === 'EMPTY') return '当前没有记录，不等于功能故障。先在对应功能产生数据，再刷新目录。'
  if (source.coverage === 'REGISTRY_ONLY') return '只有来源登记信息，不能据此分析来源正文或完整历史。需要采集内容时应先确认接入范围。'
  if (source.status === 'TIME_UNKNOWN') return '已有记录，但无法判断最近何时更新；请到对应功能核对内容，不据此判断更新频率。'
  if (source.status === 'READY') return '请到对应功能确认内容是否覆盖所需时间和主题；最近写入时间不保证持续同步或数据完整。'
  return '当前状态尚无法解释，请刷新后再确认覆盖范围。'
}
function resetFilters() { selectedCategory.value = '全部'; selectedStatus.value = 'ALL' }

function formatCount(source: DataCenterSource) {
  return `${new Intl.NumberFormat('zh-CN').format(source.recordCount)} ${source.recordUnit}`
}

function formatTime(value: string | null) {
  if (!value) return '没有可靠写入时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await fetchDataCenterOverview()
    if (!categories.value.includes(selectedCategory.value)) selectedCategory.value = '全部'
  } catch {
    error.value = '数据目录读取失败，请检查网络后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="data-center-page">
    <header class="hero">
      <div>
        <span>WAO DATA CENTER</span>
        <h1>数据中心</h1>
        <p>查看 WAO 保存了哪些数据，核对范围后再用于报告或分析。</p>
      </div>

      <button type="button" :disabled="loading" @click="load">
        <i :class="loading ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>
        {{ loading ? '读取中…' : '刷新目录' }}
      </button>
    </header>

    <section v-if="loading && !overview" class="state">
      <i class="ri-loader-4-line spin"></i><b>正在盘点 WAO 的真实持久化数据…</b>
    </section>
    <section v-else-if="error && !overview" class="state error">
      <i class="ri-error-warning-line"></i><b>{{ error }}</b>
      <button type="button" @click="load">重新读取</button>
    </section>

    <template v-else-if="overview">
      <div v-if="error" class="inline-error">
        <span>{{ error }} 已保留上一次成功结果。</span
        ><button type="button" @click="load">重试</button>
      </div>

      <section class="data-guidance" aria-label="数据使用前检查">
        <h2>这些数据能用来做什么？</h2>
        <p>先看是否有记录，再核对覆盖边界和时间。此处是系统目录汇总，不是个人完成量，也不代表实时同步状态。</p>
        <p>统计于 {{ formatTime(overview.generatedAt) }}<span v-if="loading"> · 正在刷新，下方暂为上一次结果</span></p>
        <div class="status-filters" role="group" aria-label="按数据状态查看">
          <button type="button" :aria-pressed="selectedStatus === 'ALL'" @click="selectedStatus = 'ALL'">全部状态</button>
          <button v-for="item in statusCounts" :key="item.status" type="button" :aria-pressed="selectedStatus === item.status" @click="selectedStatus = item.status">{{ item.label }} · {{ item.count }}</button>
        </div>
        <p>空数据和仅登记不一定需要修复；有记录也需要核对内容。“目录内记录总量”混合了不同单位，不能作为完成量或质量评分。</p>
      </section>

      <section class="summary">
        <article>
          <span>已登记数据源</span><strong>{{ overview.sourceCount }}</strong>
        </article>
        <article>
          <span>已有真实记录</span><strong>{{ overview.populatedSourceCount }}</strong>
        </article>
        <article>
          <span>目录内记录总量</span>
          <strong>{{ new Intl.NumberFormat('zh-CN').format(overview.totalRecords) }}</strong>
        </article>
      </section>

      <section class="git-boundary">
        <i class="ri-git-repository-line"></i>
        <div>
          <b>Git 只采集成功交付所需的最小更新字段</b>
          <p>
            Runner 会把成功运行中的提交 SHA、scope、标题和时间写入更新日记；仓库凭据仍只在 Runner
            本机，WAO 不镜像作者、diff、分支历史，也不会生成贡献排行等伪统计。
          </p>
        </div>
      </section>

      <section v-if="overview.populatedSourceCount === 0" class="empty-banner">
        <i class="ri-inbox-2-line"></i>
        <div><b>还没有可统计记录</b><span>目录保持真实空值，不会填充演示数字。</span></div>
      </section>

      <nav class="filters" aria-label="数据源分类">
        <button
          v-for="category in categories"
          :key="category"
          type="button"
          :class="{ active: selectedCategory === category }"
          :aria-pressed="selectedCategory === category"
          @click="selectedCategory = category"
        >
          {{ category }}
        </button>
      </nav>

      <div v-if="visibleSources.length === 0" class="state">
        <b>{{ overview.sources.length ? '当前分类和状态下没有数据源' : '暂时没有已登记的数据源' }}</b>
        <button v-if="overview.sources.length" type="button" @click="resetFilters">清除筛选</button>
      </div>

      <section class="source-grid">
        <article v-for="source in visibleSources" :key="source.id" class="source-card">
          <header>
            <div>
              <small>{{ source.category }}</small>
              <h2>{{ source.label }}</h2>
            </div>
            <span :class="`status ${source.status.toLowerCase()}`">
              <i :class="statusMeta[source.status]?.icon ?? 'ri-question-line'"></i>{{ statusMeta[source.status]?.label ?? '状态待确认' }}
            </span>
          </header>
          <strong class="count">{{ formatCount(source) }}</strong>
          <p>{{ source.description }}</p>
          <dl>
            <div>
              <dt>最近写入</dt>
              <dd>{{ formatTime(source.latestAt) }}</dd>
            </div>
            <div>
              <dt>覆盖边界</dt>
              <dd>{{ source.boundary }}</dd>
            </div>
          </dl>
          <div class="source-next"><p>{{ guidance(source) }}</p><router-link v-if="sourceDestinations[source.id]" :to="sourceDestinations[source.id]!.path">{{ sourceDestinations[source.id]!.label }} →</router-link></div>
        </article>
      </section>
    </template>
  </main>
</template>

<style scoped>
.data-guidance{margin-top:18px;padding:18px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.data-guidance h2{font-size:17px;margin:0 0 10px}.data-guidance p,.source-next p{font-size:13px;line-height:1.7;color:var(--wao-text-3)}.status-filters{display:flex;gap:8px;flex-wrap:wrap;margin:14px 0}.status-filters button{font:inherit;font-size:12px;padding:8px 10px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);cursor:pointer}.status-filters button[aria-pressed=true]{background:var(--wao-primary-soft);color:var(--wao-primary-text)}.source-next{border-top:1px solid var(--wao-line);margin-top:14px;padding-top:10px}.source-next a{font-size:13px;color:var(--wao-primary-text);display:inline-block;padding:6px 0}.status-filters button:focus-visible,.source-next a:focus-visible{outline:2px solid var(--wao-primary-text);outline-offset:3px}
.data-center-page {
  max-width: none;
  margin: 0 auto;
  padding: 24px 28px 60px;
  color: var(--wao-text);
}
.hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 18px;
}
.hero span {
  color: #356c78;
  font-size: 10px;
  font-weight: 800;
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
.hero button:disabled {
  opacity: 0.6;
  cursor: default;
}
.summary {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 9px;
}
.summary article,
.source-card {
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.summary article {
  padding: 14px;
}
.summary span,
.summary strong {
  display: block;
}
.summary span {
  color: var(--wao-text-weak);
  font-size: 10px;
}
.summary strong {
  margin-top: 4px;
  font-size: 23px;
}
.git-boundary,
.empty-banner {
  margin-top: 12px;
  padding: 14px 16px;
  border-radius: 11px;
  display: flex;
  align-items: flex-start;
  gap: 11px;
}
.git-boundary {
  border: 1px solid color-mix(in srgb, #b78125 35%, var(--wao-line));
  background: color-mix(in srgb, #f3b84b 8%, var(--wao-bg-card));
}
.git-boundary > i,
.empty-banner > i {
  margin-top: 1px;
  font-size: 20px;
}
.git-boundary b,
.empty-banner b,
.empty-banner span {
  display: block;
}
.git-boundary p {
  margin: 4px 0 0;
  color: var(--wao-text-3);
  font-size: 10px;
  line-height: 1.6;
}
.empty-banner {
  border: 1px dashed var(--wao-line);
  background: var(--wao-bg-sub);
}
.empty-banner span {
  margin-top: 3px;
  color: var(--wao-text-weak);
  font-size: 10px;
}
.filters {
  margin: 18px 0 10px;
  display: flex;
  gap: 7px;
  overflow-x: auto;
  scrollbar-width: thin;
}
.filters button {
  flex: none;
  padding: 7px 11px;
  border: 1px solid var(--wao-line);
  border-radius: 99px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font-size: 10px;
  cursor: pointer;
}
.filters button.active {
  border-color: #4f8792;
  background: color-mix(in srgb, #356c78 9%, var(--wao-bg-card));
  color: #356c78;
  font-weight: 700;
}
.source-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.source-card {
  min-width: 0;
  padding: 17px;
}
.source-card header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.source-card small {
  color: #356c78;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 1px;
}
.source-card h2 {
  margin: 4px 0 0;
  font-size: 15px;
}
.status {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 7px;
  border-radius: 99px;
  background: var(--wao-bg-sub);
  font-size: 8.5px;
}
.status.ready {
  color: #26734d;
}
.status.time_unknown,
.status.registered_only {
  color: #9a6513;
}
.status.empty {
  color: var(--wao-text-weak);
}
.count {
  display: block;
  margin-top: 14px;
  font-size: 24px;
}
.source-card > p {
  min-height: 34px;
  margin: 6px 0 12px;
  color: var(--wao-text-3);
  font-size: 10px;
  line-height: 1.6;
}
.source-card dl {
  margin: 0;
  display: grid;
  gap: 6px;
}
.source-card dl > div {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 7px;
  padding-top: 7px;
  border-top: 1px solid var(--wao-line);
}
.source-card dt,
.source-card dd {
  margin: 0;
  font-size: 9px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.source-card dt {
  color: var(--wao-text-weak);
}
.source-card dd {
  color: var(--wao-text-3);
}
.state {
  min-height: 260px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  color: var(--wao-text-weak);
  text-align: center;
}
.state > i {
  font-size: 28px;
}
.inline-error {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 10px;
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
  .data-center-page {
    padding: 16px 14px 46px;
  }
  .hero {
    align-items: flex-start;
  }
  .hero button {
    font-size: 0;
    padding: 10px;
  }
  .hero button i {
    font-size: 15px;
  }
  .summary {
    grid-template-columns: 1fr 1fr;
  }
  .summary article:last-child {
    grid-column: 1 / -1;
  }
  .source-grid {
    grid-template-columns: 1fr;
  }
}
</style>
