<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchCodexMemberships } from '@/api/codexMemberships'
import type { CodexQuotaAccount } from '@/api/ai'
import { emptyCodexBenefits, type CodexBenefitsSnapshot } from '@/api/codexBenefits'
import CodexBenefitsPanel from '@/components/ai/CodexBenefitsPanel.vue'
import HomeTianrenGlance from './HomeTianrenGlance.vue'
import { createVisibilityPoller } from '@/composables/visibilityPolling'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const region = ref<HTMLElement>()
const closeButton = ref<HTMLButtonElement>()
const open = ref(false)
const tianren = ref<InstanceType<typeof HomeTianrenGlance>>()
const accounts = ref<CodexQuotaAccount[]>([])
const loading = ref(false)
const error = ref('')
const attention = ref('')
const benefits = ref<CodexBenefitsSnapshot>({ saved: emptyCodexBenefits(), loading: true, loaded: false, error: '', daysLeft: null, expiryLabel: '' })
const now = ref(Date.now())
let version = 0
let opener: HTMLElement | null = null

// 使用通用 Codex 周窗口；模型专属窗口不能冒充通用额度。
const weekly = computed(() => accounts.value.flatMap(account => {
  const window = account.limits.find(limit => limit.id === 'codex')?.windows.find(item => item.windowDurationMinutes === 10_080)
  if (!window || !Number.isFinite(window.usedPercent)) return []
  return [{ account, window, remaining: Math.max(0, Math.min(100, Math.round(100 - window.usedPercent))),
    current: fresh(account) && (window.resetsAt === null || window.resetsAt * 1000 > now.value) }]
}))
const resetAccounts = computed(() => accounts.value.filter(account => account.resetCredits))
function fresh(account: CodexQuotaAccount) {
  const synced = Date.parse(/(?:Z|[+-]\d{2}:\d{2})$/.test(account.lastSyncedAt) ? account.lastSyncedAt : `${account.lastSyncedAt}+08:00`)
  return !error.value && account.fresh && Number.isFinite(synced) && now.value - synced < 15 * 60_000
}
const quotaValue = computed(() => weekly.value.length > 1 ? `${weekly.value.length} 个账号`
  : weekly.value[0] ? `${weekly.value[0].remaining}%` : loading.value ? '读取中…' : error.value ? '读取失败' : '暂无快照')
const quotaHint = computed(() => weekly.value.some(item => !item.current) ? '历史快照'
  : weekly.value.length > 1 ? '分别查看' : weekly.value.length ? '最近同步' : '点击查看')
const expiryValue = computed(() => benefits.value.loaded
  ? benefits.value.saved.proExpiresOn?.replace(/-/g, '/') ?? '未设置'
  : benefits.value.loading ? '读取中…' : benefits.value.error ? '读取失败' : '未读取')
const cardsValue = computed(() => resetAccounts.value.length > 1 ? `${resetAccounts.value.length} 个账号`
  : resetAccounts.value[0] ? `${resetAccounts.value[0].resetCredits!.availableCount} 张`
    : benefits.value.loaded && benefits.value.saved.resetCards !== null ? `${benefits.value.saved.resetCards} 张`
      : loading.value || benefits.value.loading ? '读取中…' : error.value || benefits.value.error ? '读取失败' : '未设置')
const cardsHint = computed(() => resetAccounts.value.length
  ? resetAccounts.value.some(account => !fresh(account)) ? '历史快照' : '官方同步'
  : benefits.value.loaded && benefits.value.saved.resetCards !== null ? '手工记录' : '点击查看')
const displayAccounts = computed(() => accounts.value.map(account => ({ ...account, fresh: fresh(account) })))

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
const poller = createVisibilityPoller(load, () => 5 * 60_000)
watch(() => session.userId, () => {
  version++
  accounts.value = []
  loading.value = false
  error.value = ''
  open.value = false
  void load()
}, { immediate: true })
async function showDetails(event: MouseEvent) {
  tianren.value?.close()
  if (open.value) { close(); return }
  opener = event.currentTarget as HTMLElement
  open.value = true
  await nextTick()
  closeButton.value?.focus()
}
function close(restoreFocus = true) {
  open.value = false
  if (restoreFocus) opener?.focus()
}
function onPointer(event: PointerEvent) {
  if (open.value && event.target instanceof Node && !region.value?.contains(event.target)) close(false)
}
function onKey(event: KeyboardEvent) {
  if (open.value && event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close() }
}
function resetTime(seconds: number | null) {
  return seconds === null ? '重置时间未知' : `${new Date(seconds * 1000).toLocaleString('zh-CN', { hour12: false })} 重置`
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
  poller.stop()
  clearInterval(clock)
  document.removeEventListener('pointerdown', onPointer)
  document.removeEventListener('keydown', onKey, true)
})
</script>

<template>
  <div ref="region" class="home-overview">
    <section class="overview-card" aria-label="今日卦象与 Codex 统计">
      <HomeTianrenGlance :key="session.userId" ref="tianren" integrated @preview="close(false)" />
      <button class="overview-metric" type="button" :aria-expanded="open" aria-controls="home-codex-details" @click="showDetails">
        <span class="metric-label"><i class="ri-pie-chart-2-line" aria-hidden="true"></i>Codex 周剩余</span>
        <span class="metric-value" :class="{ attention: error || weekly.some(item => item.remaining < 20) }">{{ quotaValue }}</span>
        <small>{{ quotaHint }}</small>
      </button>
      <button class="overview-metric" type="button" :aria-expanded="open" aria-controls="home-codex-details" @click="showDetails">
        <span class="metric-label"><i class="ri-calendar-line" aria-hidden="true"></i>Pro 到期</span>
        <span class="metric-value expiry" :class="{ attention: benefits.daysLeft !== null && benefits.daysLeft <= 7 }">{{ expiryValue }}</span>
        <small>{{ benefits.loaded ? `${benefits.expiryLabel} · 手工` : '点击查看' }}</small>
      </button>
      <button class="overview-metric" type="button" :aria-expanded="open" aria-controls="home-codex-details" @click="showDetails">
        <span class="metric-label"><i class="ri-ticket-2-line" aria-hidden="true"></i>重置卡</span>
        <span class="metric-value">{{ cardsValue }}</span>
        <small>{{ cardsHint }}</small>
      </button>
    </section>
    <p v-if="attention || error" class="overview-attention" role="status">{{ attention || '额度读取失败，请打开详情重试' }}</p>
    <section v-show="open" id="home-codex-details" class="overview-details" aria-label="Codex 额度与权益详情">
      <header><strong>Codex 额度与权益</strong><button ref="closeButton" type="button" aria-label="关闭额度详情" @click="close()"><i class="ri-close-line" aria-hidden="true"></i></button></header>
      <p v-if="error" class="attention" role="alert">{{ error }}；已有快照仅供参考。</p>
      <button type="button" :disabled="loading" @click="load">{{ loading ? '读取中…' : error ? '重试读取额度' : '刷新额度快照' }}</button>
      <p v-if="!weekly.length && !loading">暂无可用的 Codex 周额度快照。</p>
      <article v-for="item in weekly" :key="item.account.key" class="quota-account">
        <strong>{{ item.account.accountEmail || item.account.key }} · 周剩余 {{ item.remaining }}%</strong>
        <p>{{ item.current ? '最近同步' : '历史快照' }} · {{ item.account.lastSyncedAt }}</p>
        <p>{{ resetTime(item.window.resetsAt) }}</p>
        <p v-if="item.remaining < 20" class="attention">周额度偏低，长任务可能受限。</p>
      </article>
      <CodexBenefitsPanel neutral :accounts="displayAccounts" @snapshot="benefits = $event" @attention="attention = $event" />
    </section>
  </div>
</template>

<style scoped>
.home-overview { position: relative; width: 600px; max-width: 100%; min-width: 0; }
.overview-card { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--wao-line-control); border-radius: 10px; background: var(--wao-bg-card); padding: 9px 0; }
.overview-metric { display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 5px; min-width: 0; padding: 1px 14px; border: 0; background: transparent; color: var(--wao-text); font: inherit; text-align: left; cursor: pointer; }
.overview-card > * + * { border-left: 1px solid var(--wao-line); }
.overview-metric:hover, .overview-metric[aria-expanded="true"] { background: var(--wao-primary-soft); }
.overview-metric:focus-visible, .overview-details button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.metric-label { display: flex; flex-wrap: wrap; gap: 5px; align-items: center; color: var(--wao-text-3); font-size: 11px; }
.metric-value { font-size: 16px; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.metric-value.expiry { font-size: 14px; }
small { font-size: 11px; color: var(--wao-text-3); overflow-wrap: anywhere; }
.attention, .overview-attention { color: var(--wao-warning-text, var(--wao-primary-text)); }
.overview-attention { font-size: 11px; margin: 5px 0 0; }
.overview-details { position: absolute; top: calc(100% + 8px); left: 0; width: 100%; box-sizing: border-box; max-height: min(60dvh, 520px); overflow: auto; z-index: 80; border: 1px solid var(--wao-line-control); border-radius: 12px; padding: 16px; background: var(--wao-bg-card); color: var(--wao-text); box-shadow: 0 8px 30px #0002; overflow-wrap: anywhere; }
.overview-details header { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 12px; }
.overview-details button { border: 1px solid var(--wao-line-control); border-radius: 6px; padding: 5px 8px; background: var(--wao-bg-card); color: var(--wao-primary-text); font: inherit; font-size: 12px; cursor: pointer; }
.overview-details button:disabled { opacity: .6; cursor: default; }
.overview-details p { font-size: 12px; line-height: 1.6; }
.quota-account { border-top: 1px solid var(--wao-line); margin-top: 12px; padding-top: 12px; font-size: 13px; }
.quota-account p { color: var(--wao-text-3); margin: 5px 0; }
@media (max-width: 600px) {
  .overview-card { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 0; }
  .overview-card > :nth-child(3) { border-left: 0; }
  .overview-card > :nth-child(n+3) { border-top: 1px solid var(--wao-line); }
  .overview-metric { padding: 12px 14px; }
}
</style>
