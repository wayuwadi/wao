<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchCodexMemberships } from '@/api/codexMemberships'
import { fetchAgyQuotas, type AgyQuotaAccount } from '@/api/agyQuota'
import type { CodexQuotaAccount } from '@/api/ai'
import CodexBenefitsPanel from '@/components/ai/CodexBenefitsPanel.vue'
import HomeTianrenGlance from './HomeTianrenGlance.vue'
import { createVisibilityPoller } from '@/composables/visibilityPolling'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const region = ref<HTMLElement>()
const closeButton = ref<HTMLButtonElement>()
const agyCloseButton = ref<HTMLButtonElement>()
const open = ref(false)
const agyOpen = ref(false)
const tianren = ref<InstanceType<typeof HomeTianrenGlance>>()
const accounts = ref<CodexQuotaAccount[]>([])
const loading = ref(false)
const error = ref('')
const attention = ref('')
const agyAccounts = ref<AgyQuotaAccount[]>([])
const agyLoading = ref(false)
const agyError = ref('')
const now = ref(Date.now())
let version = 0
let agyVersion = 0
let opener: HTMLElement | null = null
let agyOpener: HTMLElement | null = null

function isRecentSync(lastSyncedAt: string, isFresh: boolean, hasError: boolean) {
  const synced = Date.parse(/(?:Z|[+-]\d{2}:\d{2})$/.test(lastSyncedAt) ? lastSyncedAt : `${lastSyncedAt}+08:00`)
  return !hasError && isFresh && Number.isFinite(synced) && now.value - synced < 15 * 60_000
}
function fresh(account: CodexQuotaAccount) {
  return isRecentSync(account.lastSyncedAt, account.fresh, Boolean(error.value))
}
function freshAgy(account: AgyQuotaAccount) {
  return isRecentSync(account.lastSyncedAt, account.fresh, Boolean(agyError.value))
}
function agyGroupLabel(name: string) {
  const lower = name.trim().toLowerCase()
  if (lower === 'gemini models') return 'Gemini'
  if (lower.includes('claude') && lower.includes('gpt')) return 'Claude / GPT'
  return name.trim() || 'Agy'
}
function agyWindowLabel(window: string | null, name: string | null) {
  if (window === 'weekly') return '周额度'
  if (window === '5h') return '5 小时'
  return name?.trim() || window?.trim() || '额度'
}

// 使用通用 Codex 周窗口；模型专属窗口不能冒充通用额度。
const weekly = computed(() => accounts.value.flatMap(account => {
  const window = account.limits.find(limit => limit.id === 'codex')?.windows.find(item => item.windowDurationMinutes === 10_080)
  if (!window || !Number.isFinite(window.usedPercent)) return []
  return [{ account, window, remaining: Math.max(0, Math.min(100, Math.round(100 - window.usedPercent))),
    current: fresh(account) && (window.resetsAt === null || window.resetsAt * 1000 > now.value) }]
}))
const resetAccounts = computed(() => accounts.value.filter(account => account.resetCredits))
const quotaValue = computed(() => weekly.value.length > 1 ? `${weekly.value.length} 个账号`
  : weekly.value[0] ? `${weekly.value[0].remaining}%` : loading.value ? '读取中…' : error.value ? '读取失败' : '暂无快照')
const quotaHint = computed(() => weekly.value.some(item => !item.current) ? '历史快照'
  : weekly.value.length > 1 ? '分别查看' : weekly.value.length ? '最近同步' : '点击查看')
const resetValue = computed(() => weekly.value.length > 1 ? '分别查看'
  : weekly.value[0]?.window.resetsAt ? new Date(weekly.value[0].window.resetsAt * 1000).toLocaleString('zh-CN',
    { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
    : loading.value ? '读取中…' : error.value ? '读取失败' : '未同步')
const cardsValue = computed(() => resetAccounts.value.length > 1 ? `${resetAccounts.value.length} 个账号`
  : resetAccounts.value[0] ? `${resetAccounts.value[0].resetCredits!.availableCount} 张`
    : loading.value ? '读取中…' : error.value ? '读取失败' : '未同步')
const cardsHint = computed(() => resetAccounts.value.length
  ? resetAccounts.value.some(account => !fresh(account)) ? '历史快照' : '官方同步'
  : '等待官方数据')
const quotaDetails = computed(() => accounts.value.map(account => ({ account, current: fresh(account),
  windows: account.limits.flatMap(limit => limit.windows.map(window => ({ ...window,
    label: `${limit.id === 'codex' ? 'Codex' : limit.label} · ${window.windowDurationMinutes === 10080 ? '周额度' : window.windowDurationMinutes === 300 ? '5 小时' : window.windowDurationMinutes ? `${window.windowDurationMinutes} 分钟` : '额度'}`,
    remaining: Math.max(0, Math.min(100, Math.round(100 - window.usedPercent))),
    current: fresh(account) && (window.resetsAt === null || window.resetsAt * 1000 > now.value),
  }))),
})))

const agyWeekly = computed(() => agyAccounts.value.flatMap(account => {
  const geminiGroup = account.groups.find(group =>
    group.name.toLowerCase().includes('gemini')
    || group.buckets.some(bucket => bucket.id?.toLowerCase().startsWith('gemini-')))
  const bucket = geminiGroup?.buckets.find(item => item.window === 'weekly')
    ?? account.groups.flatMap(group => group.buckets).find(item => item.window === 'weekly')
  if (!bucket || !Number.isFinite(bucket.remainingPercent)) return []
  const remaining = Math.max(0, Math.min(100, Math.round(bucket.remainingPercent)))
  return [{
    account,
    bucket,
    remaining,
    current: freshAgy(account) && (bucket.resetsAt === null || bucket.resetsAt * 1000 > now.value),
  }]
}))
const agyQuotaValue = computed(() => agyWeekly.value.length > 1 ? `${agyWeekly.value.length} 个账号`
  : agyWeekly.value[0] ? `${agyWeekly.value[0].remaining}%` : agyLoading.value ? '读取中…' : agyError.value ? '读取失败' : '暂无快照')
const agyQuotaHint = computed(() => {
  if (agyWeekly.value.some(item => !item.current)) return '历史快照'
  if (agyWeekly.value.length > 1) return '分别查看'
  if (agyWeekly.value[0]?.bucket.resetsAt) {
    return `${new Date(agyWeekly.value[0].bucket.resetsAt * 1000).toLocaleString('zh-CN',
      { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })} 重置`
  }
  return agyWeekly.value.length ? '最近同步' : '点击查看'
})
const agyDetails = computed(() => agyAccounts.value.map(account => {
  const currentAccount = freshAgy(account)
  return {
    account,
    current: currentAccount,
    buckets: account.groups.flatMap(group => group.buckets.map(bucket => {
      const remaining = Math.max(0, Math.min(100, Math.round(bucket.remainingPercent)))
      return {
        label: `${agyGroupLabel(group.name)} · ${agyWindowLabel(bucket.window, bucket.name)}`,
        remaining,
        resetsAt: bucket.resetsAt,
        current: currentAccount && (bucket.resetsAt === null || bucket.resetsAt * 1000 > now.value),
      }
    })),
  }
}))

async function load() {
  if (!session.userId || loading.value) return
  const request = version
  loading.value = true
  error.value = ''
  try {
    const result = await fetchCodexMemberships()
    if (request === version) accounts.value = result.map(entry => entry.account)
  } catch (cause) {
    if (request === version) error.value = cause instanceof Error ? cause.message : '额度读取失败，请重试'
  } finally {
    if (request === version) loading.value = false
  }
}
async function loadAgy() {
  if (!session.userId || agyLoading.value) return
  const request = agyVersion
  agyLoading.value = true
  agyError.value = ''
  try {
    const result = await fetchAgyQuotas()
    if (request === agyVersion) agyAccounts.value = result
  } catch (cause) {
    if (request === agyVersion) agyError.value = cause instanceof Error ? cause.message : 'Agy 额度读取失败，请重试'
  } finally {
    if (request === agyVersion) agyLoading.value = false
  }
}
function loadAll() {
  return Promise.all([load(), loadAgy()])
}
const poller = createVisibilityPoller(loadAll, () => 5 * 60_000)
watch(() => session.userId, () => {
  version++
  agyVersion++
  accounts.value = []
  agyAccounts.value = []
  loading.value = false
  agyLoading.value = false
  error.value = ''
  agyError.value = ''
  open.value = false
  agyOpen.value = false
  void load()
  void loadAgy()
}, { immediate: true })
async function showDetails(event: MouseEvent) {
  tianren.value?.close()
  agyOpen.value = false
  if (open.value) { close(); return }
  opener = event.currentTarget as HTMLElement
  open.value = true
  await nextTick()
  closeButton.value?.focus()
}
async function showAgyDetails(event: MouseEvent) {
  tianren.value?.close()
  open.value = false
  if (agyOpen.value) { closeAgy(); return }
  agyOpener = event.currentTarget as HTMLElement
  agyOpen.value = true
  await nextTick()
  agyCloseButton.value?.focus()
}
function close(restoreFocus = true) {
  open.value = false
  if (restoreFocus) opener?.focus()
}
function closeAgy(restoreFocus = true) {
  agyOpen.value = false
  if (restoreFocus) agyOpener?.focus()
}
function closeAll(restoreFocus = true) {
  if (open.value) close(restoreFocus)
  if (agyOpen.value) closeAgy(restoreFocus)
}
function onPointer(event: PointerEvent) {
  if ((open.value || agyOpen.value) && event.target instanceof Node && !region.value?.contains(event.target)) closeAll(false)
}
function onKey(event: KeyboardEvent) {
  if ((open.value || agyOpen.value) && event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    closeAll(true)
  }
}
function resetTime(seconds: number | null, remaining?: number) {
  if (seconds === null) return remaining === 100 ? '满额待用' : '重置时间未知'
  return `${new Date(seconds * 1000).toLocaleString('zh-CN', { hour12: false })} 重置`
}
let clock: ReturnType<typeof setInterval>
onMounted(() => {
  poller.start(false)
  clock = setInterval(() => { now.value = Date.now() }, 60_000)
  document.addEventListener('pointerdown', onPointer)
  document.addEventListener('keydown', onKey, true)
})
onBeforeUnmount(() => {
  version++
  agyVersion++
  poller.stop()
  clearInterval(clock)
  document.removeEventListener('pointerdown', onPointer)
  document.removeEventListener('keydown', onKey, true)
})
</script>

<template>
  <div ref="region" class="home-overview">
    <section class="overview-card" aria-label="今日卦象与 AI 额度统计">
      <HomeTianrenGlance :key="session.userId" ref="tianren" integrated @preview="closeAll(false)" />
      <button class="overview-metric" type="button" :aria-expanded="open" aria-controls="home-codex-details" @click="showDetails">
        <span class="metric-label"><i class="ri-pie-chart-2-line" aria-hidden="true"></i>Codex 周剩余</span>
        <span class="metric-value" :class="{ attention: error || weekly.some(item => item.remaining < 20) }">{{ quotaValue }}</span>
        <small>{{ quotaHint }}</small>
      </button>
      <button class="overview-metric" type="button" :aria-expanded="open" aria-controls="home-codex-details" @click="showDetails">
        <span class="metric-label"><i class="ri-time-line" aria-hidden="true"></i>周重置</span>
        <span class="metric-value expiry">{{ resetValue }}</span>
        <small>{{ weekly.some(item => !item.current) ? '历史快照' : weekly.length ? '当地时间 · 官方同步' : '等待官方数据' }}</small>
      </button>
      <button class="overview-metric" type="button" :aria-expanded="open" aria-controls="home-codex-details" @click="showDetails">
        <span class="metric-label"><i class="ri-ticket-2-line" aria-hidden="true"></i>重置卡</span>
        <span class="metric-value">{{ cardsValue }}</span>
        <small>{{ cardsHint }}</small>
      </button>
      <button class="overview-metric" type="button" :aria-expanded="agyOpen" aria-controls="home-agy-details" @click="showAgyDetails">
        <span class="metric-label"><i class="ri-sparkling-2-line" aria-hidden="true"></i>Agy 周剩余</span>
        <span class="metric-value" :class="{ attention: agyError || agyWeekly.some(item => item.remaining < 20) }">{{ agyQuotaValue }}</span>
        <small>{{ agyQuotaHint }}</small>
      </button>
    </section>
    <p v-if="attention || error || agyError" class="overview-attention" role="status">{{ attention || (error && agyError ? 'Codex 与 Agy 额度读取失败，请打开详情重试' : error ? 'Codex 额度读取失败，请打开详情重试' : 'Agy 额度读取失败，请打开详情重试') }}</p>
    <section v-show="open" id="home-codex-details" class="overview-details" aria-label="Codex 额度与权益详情">
      <header><strong>Codex 额度与权益</strong><button ref="closeButton" type="button" aria-label="关闭额度详情" @click="close()"><i class="ri-close-line" aria-hidden="true"></i></button></header>
      <p v-if="error" class="attention" role="alert">{{ error }}；已有快照仅供参考。</p>
      <button type="button" :disabled="loading" @click="load">{{ loading ? '读取中…' : error ? '重试读取额度' : '刷新额度快照' }}</button>
      <p v-if="!accounts.length && !loading">暂无可用的 Codex 额度快照。</p>
      <article v-for="item in quotaDetails" :key="item.account.key" class="quota-account">
        <strong>{{ item.account.accountEmail || item.account.key }} · {{ item.account.plan }}</strong>
        <p>{{ item.current ? '最近同步' : '历史快照' }} · {{ item.account.lastSyncedAt }}</p>
        <p v-if="item.account.runnerNames.length">采集来源：{{ item.account.runnerNames.join('、') }} · 同账号取最新</p>
        <div v-for="(window, index) in item.windows" :key="index" class="quota-window">
          <span>{{ window.label }} · 剩余 {{ window.remaining }}% <small v-if="!window.current">（历史快照）</small></span>
          <p>{{ resetTime(window.resetsAt) }}</p>
        </div>
        <p>重置卡：{{ item.account.resetCredits ? `${item.account.resetCredits.availableCount} 张` : '官方尚未返回' }}{{ item.current ? '' : '（历史快照）' }}</p>
      </article>
      <details class="manual-benefits"><summary>手工补充记录（不参与上方统计）</summary>
        <CodexBenefitsPanel neutral @attention="attention = $event" />
      </details>
    </section>
    <section v-show="agyOpen" id="home-agy-details" class="overview-details" aria-label="Agy 额度详情">
      <header><strong>Agy 额度</strong><button ref="agyCloseButton" type="button" aria-label="关闭 Agy 额度详情" @click="closeAgy()"><i class="ri-close-line" aria-hidden="true"></i></button></header>
      <p v-if="agyError" class="attention" role="alert">{{ agyError }}；已有快照仅供参考。</p>
      <button type="button" :disabled="agyLoading" @click="loadAgy">{{ agyLoading ? '读取中…' : agyError ? '重试读取额度' : '刷新 Agy 快照' }}</button>
      <p v-if="!agyAccounts.length && !agyLoading">暂无可用的 Agy 额度快照。</p>
      <article v-for="item in agyDetails" :key="item.account.key" class="agy-quota-account">
        <strong>{{ item.account.accountLabel }}</strong>
        <p>{{ item.current ? '最近同步' : '历史快照' }} · {{ item.account.lastSyncedAt }}</p>
        <p v-if="item.account.sources.length">采集来源：{{ item.account.sources.join('、') }} · 同账号取最新</p>
        <div v-for="(bucket, index) in item.buckets" :key="index" class="quota-window">
          <span>{{ bucket.label }} · 剩余 {{ bucket.remaining }}% <small v-if="!bucket.current">（历史快照）</small></span>
          <p>{{ resetTime(bucket.resetsAt, bucket.remaining) }}</p>
        </div>
      </article>
    </section>
  </div>
</template>

<style scoped>
.home-overview { position: relative; width: 760px; max-width: 100%; min-width: 0; }
.overview-card { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border: 1px solid var(--wao-line-control); border-radius: 10px; background: var(--wao-bg-card); padding: 9px 0; }
.overview-metric { display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 5px; min-width: 0; padding: 1px 14px; border: 0; background: transparent; color: var(--wao-text); font: inherit; text-align: left; cursor: pointer; }
.overview-card > * + * { border-left: 1px solid var(--wao-line); }
.overview-metric:hover, .overview-metric[aria-expanded="true"] { background: var(--wao-primary-soft); }
.overview-metric:focus-visible, .overview-details button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.metric-label { display: flex; flex-wrap: wrap; gap: 5px; align-items: center; color: var(--wao-text-3); font-size: 11px; letter-spacing: .01em; }
.metric-value { font-size: 17px; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.metric-value.expiry { font-size: 14px; }
small { font-size: 10px; line-height: 1.4; color: var(--wao-text-3); overflow-wrap: anywhere; }
.attention, .overview-attention { color: var(--wao-warning-text, var(--wao-primary-text)); }
.overview-attention { font-size: 11px; margin: 5px 0 0; }
.overview-details { position: absolute; top: calc(100% + 8px); left: 0; width: 100%; box-sizing: border-box; max-height: min(60dvh, 520px); overflow: auto; z-index: 80; border: 1px solid var(--wao-line-control); border-radius: 12px; padding: 16px; background: var(--wao-bg-card); color: var(--wao-text); box-shadow: 0 8px 30px #0002; overflow-wrap: anywhere; }
.overview-details header { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 12px; }
.overview-details button { border: 1px solid var(--wao-line-control); border-radius: 6px; padding: 5px 8px; background: var(--wao-bg-card); color: var(--wao-primary-text); font: inherit; font-size: 12px; cursor: pointer; }
.overview-details button:disabled { opacity: .6; cursor: default; }
.overview-details p { font-size: 12px; line-height: 1.6; }
.quota-account, .agy-quota-account { border-top: 1px solid var(--wao-line); margin-top: 12px; padding-top: 12px; font-size: 13px; }
.quota-account p, .agy-quota-account p { color: var(--wao-text-3); margin: 5px 0; }
.quota-window { padding-top: 8px; }
.manual-benefits { margin-top: 12px; font-size: 12px; }
.manual-benefits summary { cursor: pointer; color: var(--wao-text-3); }
@media (max-width: 600px) {
  .overview-card { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 0; }
  .overview-card > :nth-child(3) { border-left: 0; }
  .overview-card > :nth-child(5) { grid-column: 1 / -1; border-left: 0; }
  .overview-card > :nth-child(n+3) { border-top: 1px solid var(--wao-line); }
  .overview-metric { padding: 12px 14px; }
  .overview-metric small { font-size: 11px; }
}
</style>
