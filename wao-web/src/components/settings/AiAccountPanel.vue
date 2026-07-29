<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { toast } from '@/composables/toast'
import {
  fetchAiAccounts, createAiAccount, updateAiAccount, deleteAiAccount, syncAiAccount,
  startAiAccountLogin, completeAiAccountLogin, fetchAiAccountLoginStatus,
  type AiAccount, type AiAccountForm, type AiAccountLoginStart,
} from '@/api/aiAccount'

type OAuthProvider = 'claude' | 'codex'
type FormMode = 'credentials' | 'custom'
type ProviderFilter = 'all' | AiAccount['provider'] | 'custom'
type StatusFilter = 'all' | 'enabled' | 'disabled' | 'problem'
type QuotaWindowDetail = { key: string; label: string; percent: number; resetsAt: string | null }

const providerLabels: Record<AiAccount['provider'], string> = {
  codex: 'Codex', claude: 'Claude Code', qoder: 'Qoder',
}
const providerIcons: Record<AiAccount['provider'], string> = {
  codex: 'ri-openai-fill', claude: 'ri-claude-fill', qoder: 'ri-terminal-box-fill',
}
const statusLabels: Record<string, string> = {
  UNKNOWN: '等待检测', NO_API: '无额度接口', SYNCED: '状态正常', FAILED: '连接异常',
}
const providerTabs: Array<{ value: ProviderFilter; label: string }> = [
  { value: 'all', label: '全部' }, { value: 'codex', label: 'Codex' },
  { value: 'claude', label: 'Claude' }, { value: 'qoder', label: 'Qoder' },
  { value: 'custom', label: '自定义 API' },
]
const draftKey = 'wao:ai-account-form-draft'

const accounts = ref<AiAccount[]>([])
const loaded = ref(false)
const busy = ref(false)
const saving = ref(false)
const syncingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const pendingDelete = ref<AiAccount | null>(null)
const editingId = ref<string | null>(null)
const showForm = ref(false)
const formMode = ref<FormMode>('credentials')
const query = ref('')
const providerFilter = ref<ProviderFilter>('all')
const statusFilter = ref<StatusFilter>('all')
const expandedAccountIds = ref<Set<string>>(new Set())

const form = reactive<AiAccountForm>({
  provider: 'claude', label: '', email: '', credentials: '', enabled: true,
  baseUrl: '', model: '', apiProtocol: 'responses', apiKey: '',
})

const hasForm = computed(() => showForm.value || editingId.value !== null)
const isEditing = computed(() => editingId.value !== null)
const enabledCount = computed(() => accounts.value.filter((account) => account.enabled).length)
const problemCount = computed(() => accounts.value.filter((account) => account.quotaStatus === 'FAILED').length)
const filteredAccounts = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return accounts.value.filter((account) => {
    const providerOk = providerFilter.value === 'all'
      || (providerFilter.value === 'custom' ? account.authMethod === 'api_key' : account.provider === providerFilter.value)
    const statusOk = statusFilter.value === 'all'
      || (statusFilter.value === 'enabled' && account.enabled)
      || (statusFilter.value === 'disabled' && !account.enabled)
      || (statusFilter.value === 'problem' && account.quotaStatus === 'FAILED')
    const searchOk = !needle || [account.label, account.email, account.baseUrl, account.model, providerLabels[account.provider]]
      .some((value) => value?.toLowerCase().includes(needle))
    return providerOk && statusOk && searchOk
  })
})

const login = ref<AiAccountLoginStart | null>(null)
const loginProvider = ref<OAuthProvider | null>(null)
const loginCode = ref('')
const loginStarting = ref<OAuthProvider | null>(null)
const loginFinishing = ref(false)
const loginPolling = ref(false)
const loginStatusMessage = ref('')
let loginPollTimer: ReturnType<typeof setTimeout> | null = null

const windowLabels: Record<string, string> = {
  session: '五小时会话额度', five_hour: '五小时会话额度',
  weekly_all: '七天额度', seven_day: '七天额度',
  weekly_opus: '七天 Opus 额度', seven_day_opus: '七天 Opus 额度',
}

function windowLabel(name: string | null) { return name ? windowLabels[name] ?? name : '额度' }
function quotaTone(percent: number) { return percent >= 90 ? 'danger' : percent >= 70 ? 'warn' : 'ok' }
function formatTime(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '' }
function loginName(provider: OAuthProvider | null) { return provider === 'codex' ? 'Codex' : 'Claude' }
function planLabel(plan: string | null) {
  if (!plan) return '未获取'
  return ({ pro: 'Pro', max: 'Max', plus: 'Plus', team: 'Team' } as Record<string, string>)[plan.toLowerCase()] ?? plan
}
function authMethodLabel(method: AiAccount['authMethod']) {
  return method === 'oauth' ? 'OAuth 登录' : method === 'api_key' ? 'API Key' : '凭据文件'
}
function detailsTitle(account: AiAccount) { return account.authMethod === 'api_key' ? '连接详情' : '额度详情' }
function viewActionLabel(account: AiAccount) {
  if (expandedAccountIds.value.has(account.id)) return '收起详情'
  return account.authMethod === 'api_key' ? '查看状态' : '查看额度'
}
function toggleDetails(id: string) {
  const next = new Set(expandedAccountIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedAccountIds.value = next
}
function recordOf(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}
function rawResetTime(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (typeof value !== 'number') return null
  const milliseconds = value > 10_000_000_000 ? value : value * 1000
  return new Date(milliseconds).toISOString()
}
function quotaWindows(account: AiAccount): QuotaWindowDetail[] {
  if (!account.quotaRawJson) return account.quotaPercent === null ? [] : [{
    key: account.quotaWindow ?? 'quota', label: windowLabel(account.quotaWindow),
    percent: account.quotaPercent, resetsAt: account.quotaResetsAt,
  }]
  try {
    const root = recordOf(JSON.parse(account.quotaRawJson) as unknown)
    if (!root) return []
    const limits = Array.isArray(root.limits) ? root.limits : []
    const parsed = limits.flatMap((value, index) => {
      const limit = recordOf(value)
      const percent = typeof limit?.percent === 'number' ? Math.round(limit.percent) : null
      if (percent === null) return []
      const key = typeof limit?.kind === 'string' ? limit.kind : `limit-${index + 1}`
      return [{
        key, label: windowLabel(key), percent,
        resetsAt: typeof limit?.resets_at === 'string' ? limit.resets_at : null,
      }]
    })
    if (parsed.length) return parsed
    const legacy = ['five_hour', 'seven_day'].flatMap((key) => {
      const window = recordOf(root[key])
      const percent = typeof window?.utilization === 'number' ? Math.round(window.utilization) : null
      if (percent === null) return []
      return [{
        key, label: windowLabel(key), percent,
        resetsAt: typeof window?.resets_at === 'string' ? window.resets_at : null,
      }]
    })
    if (legacy.length) return legacy
    const rateLimits = [recordOf(root.rate_limit), ...(
      Array.isArray(root.additional_rate_limits) ? root.additional_rate_limits : []
    ).map((item) => recordOf(recordOf(item)?.rate_limit))].filter((item) => item !== null)
    return rateLimits.flatMap((rateLimit, rateIndex) =>
      ['primary_window', 'secondary_window'].flatMap((field, windowIndex) => {
        const window = recordOf(rateLimit[field])
        const percent = typeof window?.used_percent === 'number' ? Math.round(window.used_percent) : null
        if (percent === null) return []
        const seconds = typeof window?.limit_window_seconds === 'number' ? window.limit_window_seconds : 0
        const kind = seconds === 0
          ? (windowIndex === 0 ? 'five_hour' : 'weekly_all')
          : Math.abs(seconds - 18_000) <= Math.abs(seconds - 604_800) ? 'five_hour' : 'weekly_all'
        return [{
          key: `${kind}-${rateIndex}-${windowIndex}`, label: windowLabel(kind), percent,
          resetsAt: rawResetTime(window?.reset_at),
        }]
      }))
  } catch {
    return account.quotaPercent === null ? [] : [{
      key: account.quotaWindow ?? 'quota', label: windowLabel(account.quotaWindow),
      percent: account.quotaPercent, resetsAt: account.quotaResetsAt,
    }]
  }
}

async function beginLogin(provider: OAuthProvider) {
  loginStarting.value = provider
  try {
    login.value = await startAiAccountLogin(provider)
    loginProvider.value = provider
    loginCode.value = ''
    loginStatusMessage.value = login.value.flow === 'device_code'
      ? '等待你在 OpenAI 页面完成登录与确认…'
      : ''
    window.open(login.value.authorizeUrl, '_blank', 'noopener')
    scheduleLoginPoll()
  } catch (error) {
    toast(error instanceof Error ? error.message : '发起登录失败')
  } finally {
    loginStarting.value = null
  }
}

function cancelLogin() {
  clearLoginPoll()
  login.value = null
  loginProvider.value = null
  loginCode.value = ''
  loginStatusMessage.value = ''
}

function clearLoginPoll() {
  if (loginPollTimer !== null) clearTimeout(loginPollTimer)
  loginPollTimer = null
}

function scheduleLoginPoll() {
  clearLoginPoll()
  if (login.value?.flow !== 'device_code') return
  const delay = Math.max(1, login.value.pollIntervalSeconds || 5) * 1000
  loginPollTimer = setTimeout(() => void pollCodexLogin(), delay)
}

async function pollCodexLogin() {
  const current = login.value
  if (!current || current.flow !== 'device_code' || loginPolling.value) return
  clearLoginPoll()
  loginPolling.value = true
  try {
    const result = await fetchAiAccountLoginStatus(current.state)
    if (login.value?.state !== current.state) return
    loginStatusMessage.value = result.message
    if (result.status === 'completed' && result.account) {
      toast(`已登录 ${result.account.email || result.account.label}`, 'ri-check-line')
      cancelLogin()
      await load()
      return
    }
  } catch (error) {
    if (login.value?.state !== current.state) return
    loginStatusMessage.value = error instanceof Error ? error.message : '暂时无法查询登录状态，正在重试…'
  } finally {
    loginPolling.value = false
  }
  scheduleLoginPoll()
}

async function copyDeviceCode() {
  const code = login.value?.userCode
  if (!code) return
  try {
    await navigator.clipboard.writeText(code)
    toast('一次性短码已复制', 'ri-check-line')
  } catch {
    toast(`请复制短码：${code}`)
  }
}

async function finishLogin() {
  const code = loginCode.value.trim()
  if (!code) return toast('请粘贴授权码')
  loginFinishing.value = true
  try {
    const account = await completeAiAccountLogin(login.value!.state, code)
    toast(`已登录 ${account.email || account.label}`, 'ri-check-line')
    cancelLogin()
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '登录失败')
  } finally {
    loginFinishing.value = false
  }
}

onBeforeUnmount(clearLoginPoll)

async function load() {
  busy.value = true
  try {
    accounts.value = await fetchAiAccounts()
    loaded.value = true
  } catch (error) {
    toast(error instanceof Error ? error.message : '读取 AI 账号失败')
  } finally {
    busy.value = false
  }
}

function clearForm(removeDraft = true) {
  Object.assign(form, {
    provider: 'claude', label: '', email: '', credentials: '', enabled: true,
    baseUrl: '', model: '', apiProtocol: 'responses', apiKey: '',
  })
  editingId.value = null
  showForm.value = false
  if (removeDraft) sessionStorage.removeItem(draftKey)
}

function openAdd(mode: FormMode) {
  clearForm(false)
  formMode.value = mode
  if (mode === 'custom') form.provider = 'codex'
  showForm.value = true
  const saved = sessionStorage.getItem(draftKey)
  if (saved) {
    try {
      const draft = JSON.parse(saved)
      if (draft.mode === mode) Object.assign(form, draft.form)
    } catch { /* 损坏草稿直接忽略。 */ }
  }
}

function openEdit(account: AiAccount) {
  clearForm(false)
  editingId.value = account.id
  formMode.value = account.authMethod === 'api_key' ? 'custom' : 'credentials'
  Object.assign(form, {
    provider: account.provider, label: account.label, email: account.email ?? '', credentials: '',
    enabled: account.enabled, baseUrl: account.baseUrl ?? '', model: account.model ?? '',
    apiProtocol: 'responses', apiKey: '',
  })
  showForm.value = true
}

async function save() {
  const label = form.label.trim()
  if (!label) return toast('请输入展示名称')
  if (formMode.value === 'custom' && (!form.baseUrl?.trim() || !form.model?.trim())) {
    return toast('请填写 Base URL 和默认模型')
  }
  if (!isEditing.value && formMode.value === 'custom' && !form.apiKey?.trim()) return toast('请输入 API Key')
  if (!isEditing.value && formMode.value === 'credentials' && !form.credentials?.trim()) return toast('请粘贴凭据 JSON')
  const payload: AiAccountForm = formMode.value === 'custom' ? {
    provider: 'codex', label, email: form.email?.trim() || undefined, enabled: form.enabled,
    baseUrl: form.baseUrl?.trim(), model: form.model?.trim(), apiProtocol: 'responses',
    apiKey: form.apiKey?.trim() || undefined,
  } : {
    provider: form.provider, label, email: form.email?.trim() || undefined,
    credentials: form.credentials?.trim() || undefined, enabled: form.enabled,
  }
  saving.value = true
  try {
    if (editingId.value) await updateAiAccount(editingId.value, payload)
    else await createAiAccount(payload)
    toast(isEditing.value ? 'AI 账号已更新' : 'AI 账号已创建', 'ri-check-line')
    clearForm()
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(id: string) {
  deletingId.value = id
  try {
    await deleteAiAccount(id)
    toast('已删除', 'ri-check-line')
    pendingDelete.value = null
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '删除失败')
  } finally { deletingId.value = null }
}

async function sync(account: AiAccount) {
  syncingId.value = account.id
  try {
    await syncAiAccount(account.id)
    toast(account.authMethod === 'api_key' ? '连接状态已刷新' : '额度已刷新', 'ri-check-line')
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : account.authMethod === 'api_key' ? '连接检测失败' : '额度刷新失败')
  } finally { syncingId.value = null }
}

async function toggle(account: AiAccount) {
  try {
    await updateAiAccount(account.id, {
      provider: account.provider, label: account.label, email: account.email ?? undefined,
      enabled: !account.enabled, baseUrl: account.baseUrl ?? undefined,
      model: account.model ?? undefined, apiProtocol: account.apiProtocol ?? undefined,
    })
    toast(account.enabled ? '已停用' : '已启用', 'ri-check-line')
    await load()
  } catch (error) { toast(error instanceof Error ? error.message : '切换失败') }
}

watch(form, () => {
  if (showForm.value && !isEditing.value) {
    sessionStorage.setItem(draftKey, JSON.stringify({ mode: formMode.value, form: { ...form } }))
  }
}, { deep: true })
watch(() => true, load, { immediate: true })
</script>

<template>
  <section class="account-page">
    <header class="page-head">
      <div>
        <div class="eyebrow">AI ACCESS</div>
        <h1>AI 账号与 API</h1>
        <p>集中管理 OAuth 登录、CLI 凭据和 CLIProxyAPI 自定义端点，运行任务时再选择要用的账号。</p>
      </div>
      <button class="refresh-btn" :disabled="busy" @click="load">
        <i :class="busy ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>{{ busy ? '读取中' : '刷新列表' }}
      </button>
    </header>

    <div class="oauth-grid">
      <article class="oauth-card codex">
        <div class="provider-mark"><i class="ri-openai-fill"></i></div>
        <div class="oauth-copy">
          <div class="oauth-title"><b>Codex OAuth</b><span>推荐</span></div>
          <p>使用 ChatGPT / Codex 账号登录，WAO 自动生成可下发给 Runner 的官方认证文件。</p>
        </div>
        <button :disabled="loginStarting !== null || login !== null" @click="beginLogin('codex')">
          <i :class="loginStarting === 'codex' ? 'ri-loader-4-line spin' : 'ri-login-circle-line'"></i>
          {{ loginStarting === 'codex' ? '正在打开…' : '使用 Codex 账号登录' }}
        </button>
      </article>
      <article class="oauth-card claude">
        <div class="provider-mark"><i class="ri-claude-fill"></i></div>
        <div class="oauth-copy">
          <div class="oauth-title"><b>Claude OAuth</b><span>支持额度</span></div>
          <p>使用 Anthropic 账号登录，自动续期凭据并展示当前最紧张的额度窗口。</p>
        </div>
        <button :disabled="loginStarting !== null || login !== null" @click="beginLogin('claude')">
          <i :class="loginStarting === 'claude' ? 'ri-loader-4-line spin' : 'ri-login-circle-line'"></i>
          {{ loginStarting === 'claude' ? '正在打开…' : '使用 Claude 账号登录' }}
        </button>
      </article>
    </div>

    <div v-if="login" class="login-panel">
      <div class="login-icon"><i :class="loginProvider === 'codex' ? 'ri-openai-fill' : 'ri-claude-fill'"></i></div>
      <div class="login-content">
        <b>正在登录 {{ loginName(loginProvider) }} 账号</b>
        <p>{{ login.hint }}</p>
        <a :href="login.authorizeUrl" target="_blank" rel="noopener">没有打开授权页？重新打开</a>
        <div v-if="login.flow === 'device_code'" class="device-login">
          <div class="device-code">
            <div><small>OpenAI 一次性短码</small><strong>{{ login.userCode }}</strong></div>
            <button class="ghost" @click="copyDeviceCode"><i class="ri-file-copy-line"></i>复制短码</button>
          </div>
          <div class="device-status">
            <i :class="loginPolling ? 'ri-loader-4-line spin' : 'ri-time-line'"></i>
            <span>{{ loginStatusMessage }}</span>
          </div>
          <small class="security-hint">只在你刚刚打开的 OpenAI 官方页面输入此短码；15 分钟后自动失效。</small>
        </div>
        <div v-if="login.flow === 'authorization_code'" class="login-actions">
          <input v-model.trim="loginCode" placeholder="粘贴授权码" @keyup.enter="finishLogin" />
          <button class="primary" :disabled="loginFinishing" @click="finishLogin">{{ loginFinishing ? '登录中…' : '完成登录' }}</button>
          <button class="ghost" :disabled="loginFinishing" @click="cancelLogin">取消</button>
        </div>
        <div v-else class="login-actions">
          <button class="primary" :disabled="loginPolling" @click="pollCodexLogin">
            <i :class="loginPolling ? 'ri-loader-4-line spin' : 'ri-check-double-line'"></i>
            {{ loginPolling ? '检查中…' : '我已完成，立即检查' }}
          </button>
          <button class="ghost" :disabled="loginPolling" @click="cancelLogin">取消</button>
        </div>
      </div>
    </div>

    <section class="manager-card">
      <div class="manager-head">
        <div><h2>认证与端点</h2><span>{{ accounts.length }}</span></div>
        <div class="manager-actions">
          <button class="ghost" :disabled="hasForm" @click="openAdd('credentials')"><i class="ri-file-upload-line"></i>导入凭据</button>
          <button class="primary" :disabled="hasForm" @click="openAdd('custom')"><i class="ri-add-line"></i>添加自定义 API</button>
        </div>
      </div>

      <div class="filters">
        <label class="search"><i class="ri-search-line"></i><input v-model="query" placeholder="搜索名称、邮箱、Base URL 或模型" /></label>
        <div class="filter-tabs">
          <button v-for="item in providerTabs" :key="item.value" :class="{ on: providerFilter === item.value }" @click="providerFilter = item.value">{{ item.label }}</button>
        </div>
        <select v-model="statusFilter">
          <option value="all">全部状态</option><option value="enabled">已启用</option>
          <option value="disabled">已停用</option><option value="problem">有问题</option>
        </select>
      </div>

      <div class="summary-row">
        <span><i class="ri-checkbox-circle-line"></i>{{ enabledCount }} 个启用</span>
        <span v-if="problemCount" class="problem"><i class="ri-error-warning-line"></i>{{ problemCount }} 个异常</span>
        <span class="secure"><i class="ri-shield-keyhole-line"></i>凭据由 AES-256-GCM 加密</span>
      </div>

      <div v-if="!loaded && busy" class="empty-state"><i class="ri-loader-4-line spin"></i><b>正在读取账号…</b></div>
      <div v-else-if="accounts.length === 0" class="empty-state">
        <i class="ri-shield-user-line"></i><b>还没有 AI 账号</b><p>从上方 OAuth 登录，或添加 CLIProxyAPI 自定义端点。</p>
      </div>
      <div v-else-if="filteredAccounts.length === 0" class="empty-state"><i class="ri-search-eye-line"></i><b>没有符合筛选条件的账号</b></div>
      <div v-else class="account-grid">
        <article
          v-for="account in filteredAccounts"
          :key="account.id"
          class="account-card"
          :class="{
            disabled: !account.enabled,
            failed: account.quotaStatus === 'FAILED',
            expanded: expandedAccountIds.has(account.id),
          }"
        >
          <div class="account-top">
            <div class="account-icon" :class="account.provider"><i :class="providerIcons[account.provider]"></i></div>
            <div class="account-title">
              <div class="tags"><span>{{ providerLabels[account.provider] }}</span><em>{{ account.authMethod === 'api_key' ? '自定义 API' : account.authMethod === 'oauth' ? 'OAuth' : '凭据文件' }}</em></div>
              <b :title="account.label">{{ account.label }}</b>
            </div>
            <span class="state-pill" :class="{ on: account.enabled, bad: account.quotaStatus === 'FAILED' }">{{ account.quotaStatus === 'FAILED' ? '异常' : account.enabled ? '启用' : '停用' }}</span>
          </div>

          <div class="account-facts">
            <span><i class="ri-user-line"></i>{{ account.email || account.baseUrl || '未记录账号标识' }}</span>
            <span v-if="account.authMethod !== 'api_key'"><i class="ri-vip-crown-2-line"></i>套餐 {{ planLabel(account.plan) }}</span>
            <span><i class="ri-time-line"></i>{{ account.lastSyncedAt ? `${formatTime(account.lastSyncedAt)} 刷新` : '尚未刷新' }}</span>
          </div>

          <div v-if="account.authMethod === 'api_key'" class="endpoint-info">
            <div><span>模型</span><b>{{ account.model || '未配置' }}</b></div>
            <div><span>协议</span><b>OpenAI Responses</b></div>
          </div>
          <div v-else class="quota-overview">
            <div class="health-label">
              <span>额度状态</span>
              <em :class="account.quotaStatus?.toLowerCase()">{{ statusLabels[account.quotaStatus ?? 'UNKNOWN'] }}</em>
            </div>
            <div v-if="account.quotaPercent !== null" class="quota">
              <div class="quota-head"><span>{{ windowLabel(account.quotaWindow) }}</span><strong :class="quotaTone(account.quotaPercent)">已用 {{ account.quotaPercent }}%</strong></div>
              <div class="quota-bar"><span class="quota-fill" :class="quotaTone(account.quotaPercent)" :style="{ width: Math.min(100, Math.max(0, account.quotaPercent)) + '%' }"></span></div>
              <small v-if="account.quotaResetsAt">{{ formatTime(account.quotaResetsAt) }} 重置</small>
            </div>
            <div v-else class="health-line" :class="account.quotaStatus?.toLowerCase()">
              <i :class="account.quotaStatus === 'FAILED' ? 'ri-error-warning-line' : account.quotaStatus === 'SYNCED' ? 'ri-checkbox-circle-line' : 'ri-information-line'"></i>
              <span>{{ account.quotaMessage || statusLabels[account.quotaStatus ?? 'UNKNOWN'] }}</span>
            </div>
          </div>

          <section v-if="expandedAccountIds.has(account.id)" class="quota-details">
            <div class="quota-details-head">
              <div><i :class="account.authMethod === 'api_key' ? 'ri-pulse-line' : 'ri-pie-chart-line'"></i><b>{{ detailsTitle(account) }}</b></div>
              <small>{{ account.lastSyncedAt ? `更新于 ${formatTime(account.lastSyncedAt)}` : '等待首次刷新' }}</small>
            </div>
            <div class="details-grid">
              <div><span>认证方式</span><b>{{ authMethodLabel(account.authMethod) }}</b></div>
              <div v-if="account.authMethod !== 'api_key'"><span>套餐</span><b>{{ planLabel(account.plan) }}</b></div>
              <div v-if="account.tokenExpiresAt"><span>凭据有效期</span><b>{{ formatTime(account.tokenExpiresAt) }}</b></div>
              <div v-if="account.authMethod === 'api_key'"><span>默认模型</span><b>{{ account.model || '未配置' }}</b></div>
            </div>
            <template v-if="quotaWindows(account).length">
              <div v-for="window in quotaWindows(account)" :key="window.key" class="quota-window">
                <div><span>{{ window.label }}</span><strong :class="quotaTone(window.percent)">已用 {{ window.percent }}%</strong></div>
                <div class="quota-bar"><span class="quota-fill" :class="quotaTone(window.percent)" :style="{ width: Math.min(100, Math.max(0, window.percent)) + '%' }"></span></div>
                <small>{{ window.resetsAt ? `${formatTime(window.resetsAt)} 重置` : '未返回重置时间' }}</small>
              </div>
            </template>
            <div v-else class="quota-empty">
              <i :class="account.quotaStatus === 'FAILED' ? 'ri-error-warning-line' : 'ri-information-line'"></i>
              <span>{{ account.quotaMessage || (account.authMethod === 'api_key' ? '刷新后可查看连接检测结果' : '供应商未返回可展示的额度') }}</span>
            </div>
          </section>

          <div class="account-foot">
            <div class="card-buttons">
              <button class="text-action" :aria-expanded="expandedAccountIds.has(account.id)" @click="toggleDetails(account.id)">
                <i :class="expandedAccountIds.has(account.id) ? 'ri-eye-off-line' : 'ri-eye-line'"></i>{{ viewActionLabel(account) }}
              </button>
              <button class="text-action refresh-quota" :disabled="syncingId === account.id" @click="sync(account)">
                <i :class="syncingId === account.id ? 'ri-loader-4-line spin' : account.authMethod === 'api_key' ? 'ri-pulse-line' : 'ri-refresh-line'"></i>
                {{ syncingId === account.id ? '刷新中…' : account.authMethod === 'api_key' ? '测试连接' : '刷新额度' }}
              </button>
              <button title="编辑" @click="openEdit(account)"><i class="ri-settings-3-line"></i></button>
              <button class="delete" title="删除" :disabled="deletingId === account.id" @click="pendingDelete = account"><i :class="deletingId === account.id ? 'ri-loader-4-line spin' : 'ri-delete-bin-6-line'"></i></button>
            </div>
            <label class="switch-label"><span>{{ account.enabled ? '启用' : '停用' }}</span><button class="toggle" :class="{ on: account.enabled }" @click="toggle(account)"><span></span></button></label>
          </div>
        </article>
      </div>
    </section>

    <section v-if="hasForm" class="editor-card">
      <div class="editor-head"><div><span>{{ formMode === 'custom' ? 'CUSTOM ENDPOINT' : 'CREDENTIAL FILE' }}</span><h2>{{ isEditing ? '编辑配置' : formMode === 'custom' ? '添加自定义 API' : '导入凭据' }}</h2></div><button class="close" @click="clearForm()"><i class="ri-close-line"></i></button></div>
      <form class="account-form" @submit.prevent="save">
        <label v-if="formMode === 'credentials'"><span>AI 工具</span><select v-model="form.provider"><option value="claude">Claude Code</option><option value="codex">Codex</option><option value="qoder">Qoder</option></select></label>
        <label><span>展示名称</span><input v-model.trim="form.label" maxlength="128" placeholder="例如 娃哥-Codex" required /></label>
        <label v-if="formMode === 'credentials'"><span>账号邮箱 / 标识</span><input v-model.trim="form.email" maxlength="255" placeholder="可选，方便识别" /></label>
        <template v-if="formMode === 'custom'">
          <label class="wide"><span>Base URL</span><input v-model.trim="form.baseUrl" maxlength="512" placeholder="https://proxy.example.com/v1" required /><small>未写 /v1 时服务端会自动补齐</small></label>
          <label><span>默认模型</span><input v-model.trim="form.model" maxlength="128" placeholder="gpt-5.4" required /></label>
          <label><span>协议</span><select v-model="form.apiProtocol"><option value="responses">OpenAI Responses</option></select></label>
          <label class="wide"><span>API Key</span><input v-model.trim="form.apiKey" type="password" autocomplete="new-password" :placeholder="isEditing ? '留空表示保留原 API Key' : '只会加密保存，不会在页面回显'" /></label>
        </template>
        <label v-else class="wide"><span>凭据 JSON</span><textarea v-model.trim="form.credentials" rows="8" :placeholder="isEditing ? '留空表示不修改已存凭据' : '粘贴完整 auth.json 或 CLI 凭据 JSON'"></textarea></label>
        <label class="enable-row"><span>创建后启用</span><button type="button" class="toggle" :class="{ on: form.enabled }" @click="form.enabled = !form.enabled"><span></span></button></label>
        <div class="form-actions"><button type="button" class="ghost" :disabled="saving" @click="clearForm()">取消</button><button class="primary" :disabled="saving"><i :class="saving ? 'ri-loader-4-line spin' : 'ri-save-line'"></i>{{ saving ? '保存中…' : '保存配置' }}</button></div>
      </form>
    </section>

    <div v-if="pendingDelete" class="confirm-mask" @click.self="pendingDelete = null">
      <section class="confirm-card" role="alertdialog" aria-labelledby="delete-account-title">
        <div class="confirm-icon"><i class="ri-delete-bin-6-line"></i></div>
        <h2 id="delete-account-title">删除这个账号？</h2>
        <p>“{{ pendingDelete.label }}”的集中凭据和配置会被删除，Runner 本地原有登录态不受影响。</p>
        <div><button class="ghost" :disabled="deletingId !== null" @click="pendingDelete = null">取消</button><button class="danger-btn" :disabled="deletingId !== null" @click="remove(pendingDelete.id)">{{ deletingId ? '删除中…' : '确认删除' }}</button></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.account-page { display: flex; flex-direction: column; gap: 18px; color: var(--wao-text-1); }
.page-head { display: flex; align-items: flex-start; gap: 24px; }
.page-head > div { flex: 1; }
.eyebrow, .editor-head span { color: #7080cf; font-size: 10px; font-weight: 800; letter-spacing: 1.4px; }
.page-head h1 { margin: 4px 0 6px; font-size: 25px; letter-spacing: -.5px; }
.page-head p { margin: 0; color: var(--wao-text-weak); font-size: 12px; line-height: 1.7; }
button { font: inherit; }
.refresh-btn, .ghost, .primary { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 34px; padding: 0 13px; border-radius: 9px; cursor: pointer; font-size: 12px; font-weight: 650; }
.refresh-btn, .ghost { border: 1px solid var(--wao-line); background: #fff; color: var(--wao-text-2); }
.primary { border: 1px solid #475bd8; background: #475bd8; color: #fff; box-shadow: 0 7px 18px rgba(71,91,216,.18); }
button:disabled { opacity: .55; cursor: not-allowed; }
.confirm-mask { position: fixed; inset: 0; z-index: 90; display: grid; place-items: center; padding: 18px; background: rgba(25,29,46,.32); backdrop-filter: blur(3px); }
.confirm-card { width: min(390px, 100%); padding: 24px; border: 1px solid #eceef5; border-radius: 16px; background: #fff; box-shadow: 0 24px 70px rgba(27,32,61,.24); text-align: center; }
.confirm-icon { display: grid; place-items: center; width: 46px; height: 46px; margin: 0 auto 13px; border-radius: 14px; background: #fff0f0; color: #d34c4c; font-size: 21px; }
.confirm-card h2 { margin: 0; font-size: 17px; }
.confirm-card p { margin: 9px 0 20px; color: var(--wao-text-weak); font-size: 12px; line-height: 1.65; }
.confirm-card > div:last-child { display: flex; justify-content: center; gap: 9px; }
.danger-btn { min-height: 34px; padding: 0 14px; border: 1px solid #d94c4c; border-radius: 9px; background: #d94c4c; color: #fff; cursor: pointer; font-size: 12px; font-weight: 700; }
.oauth-grid { display: grid; grid-template-columns: 1fr; gap: 13px; }
.oauth-card { position: relative; display: grid; grid-template-columns: 44px 1fr auto; align-items: center; gap: 14px; min-height: 112px; padding: 17px 18px; overflow: hidden; border: 1px solid #d9deef; border-radius: 15px; background: linear-gradient(120deg,#fff 25%,#f2f5ff); box-shadow: 0 8px 26px rgba(41,52,104,.07); }
.oauth-card.claude { background: linear-gradient(120deg,#fff 25%,#fff6f1); border-color: #eadfd8; }
.provider-mark, .account-icon, .login-icon { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 12px; background: #202638; color: #fff; font-size: 20px; }
.claude .provider-mark, .account-icon.claude { background: #f1e4db; color: #bd6232; }
.oauth-title { display: flex; align-items: center; gap: 8px; }
.oauth-title b { font-size: 14px; }
.oauth-title span { padding: 3px 7px; border-radius: 10px; background: #e7ebff; color: #4d60cf; font-size: 9px; font-weight: 700; }
.claude .oauth-title span { background: #ffeadf; color: #a95731; }
.oauth-copy p { margin: 7px 0 0; color: var(--wao-text-weak); font-size: 11px; line-height: 1.55; }
.oauth-card > button { min-height: 36px; padding: 0 13px; border: 0; border-radius: 9px; background: #8f8d88; color: #fff; cursor: pointer; font-size: 11px; font-weight: 700; }
.oauth-card.codex > button { background: #4659d5; }
.login-panel { display: flex; gap: 13px; padding: 15px; border: 1px solid #cbd3ff; border-radius: 13px; background: #f5f7ff; }
.login-icon { flex: 0 0 auto; width: 36px; height: 36px; font-size: 16px; }
.login-content { flex: 1; min-width: 0; }
.login-content b { font-size: 13px; }
.login-content p { margin: 4px 0; color: var(--wao-text-weak); font-size: 11px; }
.login-content a { color: #485dd3; font-size: 11px; }
.device-login { display: grid; gap: 8px; margin-top: 11px; }
.device-code { display: flex; align-items: center; gap: 12px; padding: 11px 12px; border: 1px solid #d8def8; border-radius: 11px; background: #fff; }
.device-code > div { flex: 1; min-width: 0; }
.device-code small, .device-code strong { display: block; }
.device-code small { color: var(--wao-text-weak); font-size: 9px; }
.device-code strong { margin-top: 3px; color: #3346bd; font-family: ui-monospace,SFMono-Regular,Consolas,monospace; font-size: 20px; letter-spacing: 2px; }
.device-status { display: flex; align-items: center; gap: 6px; color: #5968bf; font-size: 10px; }
.security-hint { color: var(--wao-text-weak); font-size: 8.5px; line-height: 1.5; }
.login-actions { display: flex; gap: 8px; margin-top: 10px; }
.login-actions input { flex: 1; min-width: 0; }
input, select, textarea { border: 1px solid var(--wao-line); border-radius: 9px; background: #fff; color: var(--wao-text-1); outline: none; font: inherit; }
input:focus, select:focus, textarea:focus { border-color: #7e8ce0; box-shadow: 0 0 0 3px rgba(79,98,211,.09); }
.login-actions input { height: 34px; padding: 0 10px; font-size: 11px; }
.manager-card, .editor-card { padding: 18px; border: 1px solid var(--wao-line); border-radius: 15px; background: #fff; box-shadow: 0 8px 28px rgba(35,43,84,.055); }
.manager-head, .editor-head { display: flex; align-items: center; gap: 16px; }
.manager-head > div:first-child { display: flex; align-items: center; gap: 7px; flex: 1; }
.manager-head h2, .editor-head h2 { margin: 0; font-size: 16px; }
.manager-head > div:first-child > span { display: grid; place-items: center; min-width: 22px; height: 22px; border-radius: 11px; background: #eff1f7; color: var(--wao-text-weak); font-size: 10px; font-weight: 700; }
.manager-actions { display: flex; gap: 8px; }
.filters { display: grid; grid-template-columns: minmax(220px,1fr) auto 122px; gap: 10px; margin-top: 15px; }
.search { position: relative; }
.search i { position: absolute; left: 11px; top: 10px; color: var(--wao-text-weak); }
.search input { width: 100%; height: 36px; padding: 0 10px 0 32px; font-size: 11px; box-sizing: border-box; }
.filter-tabs { display: flex; padding: 3px; border: 1px solid var(--wao-line); border-radius: 10px; background: #f7f8fa; }
.filter-tabs button { padding: 0 9px; border: 0; border-radius: 7px; background: transparent; color: var(--wao-text-weak); cursor: pointer; font-size: 10px; }
.filter-tabs button.on { background: #fff; color: var(--wao-text-1); box-shadow: 0 2px 6px rgba(25,31,61,.09); font-weight: 700; }
.filters select { height: 36px; padding: 0 9px; font-size: 11px; }
.summary-row { display: flex; gap: 14px; margin: 11px 0 0; color: var(--wao-text-weak); font-size: 10px; }
.summary-row i { margin-right: 4px; }
.summary-row .problem { color: #c65449; }
.summary-row .secure { margin-left: auto; }
.account-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); align-items: start; gap: 13px; margin-top: 15px; }
.account-card { display: flex; flex-direction: column; min-height: 268px; padding: 16px; border: 1px solid #dfe2ec; border-radius: 13px; background: #fff; transition: .18s ease; }
.account-card:hover { border-color: #c6cceb; box-shadow: 0 10px 24px rgba(43,52,99,.08); transform: translateY(-1px); }
.account-card.expanded { border-color: #bfc7eb; box-shadow: 0 12px 28px rgba(43,52,99,.09); }
.account-card.disabled { opacity: .68; background: #fafafa; }
.account-card.failed { border-color: #efc9c4; }
.account-top { display: flex; align-items: flex-start; gap: 10px; }
.account-icon { flex: 0 0 auto; width: 36px; height: 36px; border-radius: 10px; font-size: 16px; }
.account-icon.codex { background: #edf0ff; color: #5264d8; }
.account-icon.qoder { background: #e8f7ef; color: #2f9061; }
.account-title { flex: 1; min-width: 0; }
.tags { display: flex; gap: 5px; margin-bottom: 4px; }
.tags span, .tags em { padding: 2px 6px; border-radius: 7px; background: #eef0ff; color: #5666ce; font-size: 8px; font-style: normal; font-weight: 700; }
.tags em { background: #f2f3f6; color: var(--wao-text-weak); }
.account-title b { display: block; overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.state-pill { padding: 3px 7px; border-radius: 9px; background: #eff0f3; color: #7c7f89; font-size: 8px; font-weight: 700; }
.state-pill.on { background: #dff8ec; color: #20885d; }
.state-pill.bad { background: #fde8e5; color: #bd4c40; }
.account-facts { display: flex; flex-wrap: wrap; gap: 6px 12px; margin-top: 13px; color: var(--wao-text-weak); font-size: 9px; line-height: 1.45; }
.account-facts span { display: inline-flex; align-items: center; min-width: 0; }
.account-facts span:first-child { flex-basis: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-facts i { margin-right: 4px; color: #9aa1b6; }
.endpoint-info { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 15px; }
.endpoint-info div { padding: 8px 9px; border-radius: 9px; background: #f7f8fb; }
.endpoint-info span, .endpoint-info b { display: block; }
.endpoint-info span { color: var(--wao-text-weak); font-size: 8px; }
.endpoint-info b { margin-top: 3px; overflow: hidden; text-overflow: ellipsis; color: var(--wao-text-2); font-size: 10px; white-space: nowrap; }
.quota-overview { margin-top: 15px; }
.health-label { display: flex; align-items: center; margin-bottom: 8px; color: var(--wao-text-weak); font-size: 9px; }
.health-label > span { flex: 1; }
.health-label em { padding: 2px 6px; border-radius: 7px; background: #f0f1f4; color: #737783; font-size: 8px; font-style: normal; font-weight: 700; }
.health-label em.synced { background: #e4f8ee; color: #25865f; }
.health-label em.failed { background: #fde9e6; color: #bd4e43; }
.health-label em.no_api { background: #fff3dc; color: #9c6d20; }
.quota { margin-top: 0; padding: 10px 11px; border-radius: 10px; background: #f8f9fb; }
.quota-head { display: flex; align-items: center; font-size: 10px; }
.quota-head span { flex: 1; color: var(--wao-text-weak); }
.quota-head strong.ok { color: #258b61; }.quota-head strong.warn { color: #c28323; }.quota-head strong.danger { color: #c54d43; }
.quota-bar { height: 6px; margin-top: 7px; overflow: hidden; border-radius: 3px; background: #eef0f3; }
.quota-fill { display: block; height: 100%; border-radius: inherit; }.quota-fill.ok { background: #38b77d; }.quota-fill.warn { background: #e2a139; }.quota-fill.danger { background: #d85e53; }
.quota small { display: block; margin-top: 6px; color: var(--wao-text-weak); font-size: 8px; }
.health-line { display: flex; align-items: flex-start; gap: 6px; min-height: 38px; padding: 9px 10px; border-radius: 9px; background: #f7f8fb; color: var(--wao-text-weak); font-size: 9px; line-height: 1.45; }
.health-line.failed { background: #fff0ed; color: #b85046; }
.health-line.synced { background: #edf9f3; color: #277d5b; }
.quota-details { margin-top: 13px; padding: 12px; border: 1px solid #e1e5f1; border-radius: 11px; background: #fafbfe; animation: details-in .16s ease-out; }
.quota-details-head { display: flex; align-items: center; gap: 10px; }
.quota-details-head > div { display: flex; align-items: center; gap: 6px; flex: 1; color: var(--wao-text-2); font-size: 10px; }
.quota-details-head > div i { color: #5264d8; }
.quota-details-head small { color: var(--wao-text-weak); font-size: 8px; }
.details-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 7px; margin-top: 10px; }
.details-grid > div { padding: 8px 9px; border-radius: 8px; background: #fff; }
.details-grid span, .details-grid b { display: block; }
.details-grid span { color: var(--wao-text-weak); font-size: 8px; }
.details-grid b { margin-top: 3px; overflow: hidden; color: var(--wao-text-2); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }
.quota-window { margin-top: 9px; padding: 9px 10px; border: 1px solid #eceef4; border-radius: 9px; background: #fff; }
.quota-window > div:first-child { display: flex; align-items: center; font-size: 9px; }
.quota-window > div:first-child span { flex: 1; color: var(--wao-text-2); font-weight: 650; }
.quota-window strong { font-size: 9px; }.quota-window strong.ok { color: #258b61; }.quota-window strong.warn { color: #c28323; }.quota-window strong.danger { color: #c54d43; }
.quota-window small { display: block; margin-top: 5px; color: var(--wao-text-weak); font-size: 8px; }
.quota-empty { display: flex; align-items: flex-start; gap: 6px; margin-top: 10px; padding: 9px; border-radius: 9px; background: #fff; color: var(--wao-text-weak); font-size: 9px; line-height: 1.5; }
.account-foot { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: auto; padding-top: 12px; border-top: 1px solid #eef0f3; }
.card-buttons { display: flex; flex-wrap: wrap; gap: 5px; }
.card-buttons button, .close { display: grid; place-items: center; width: 29px; height: 29px; border: 1px solid var(--wao-line); border-radius: 8px; background: #f8f8f8; color: var(--wao-text-2); cursor: pointer; }
.card-buttons .text-action { display: inline-flex; width: auto; padding: 0 8px; gap: 5px; font-size: 9px; font-weight: 650; }
.card-buttons .text-action.refresh-quota { border-color: #cbd2f1; background: #f4f6ff; color: #4c5fcf; }
.card-buttons .delete { border-color: #f1d1cd; background: #fff1ef; color: #c65046; }
.switch-label { display: flex; align-items: center; gap: 6px; color: var(--wao-text-weak); font-size: 9px; }
.toggle { position: relative; width: 34px; height: 19px; padding: 0; border: 0; border-radius: 10px; background: #d9dce2; cursor: pointer; }
.toggle span { position: absolute; top: 3px; left: 3px; width: 13px; height: 13px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.18); transition: .18s; }
.toggle.on { background: #5d6fd7; }.toggle.on span { transform: translateX(15px); }
.empty-state { display: grid; place-items: center; min-height: 180px; color: var(--wao-text-weak); text-align: center; }
.empty-state > i { font-size: 26px; }.empty-state b { margin-top: 6px; font-size: 12px; }.empty-state p { margin: 4px 0; font-size: 10px; }
.editor-head { align-items: flex-start; }
.editor-head > div { flex: 1; }.editor-head h2 { margin-top: 3px; }
.account-form { display: grid; grid-template-columns: 1fr 1fr; gap: 13px; margin-top: 16px; }
.account-form label { display: flex; flex-direction: column; gap: 6px; color: var(--wao-text-2); font-size: 10px; font-weight: 650; }
.account-form label > input, .account-form label > select { height: 36px; padding: 0 10px; font-size: 11px; }
.account-form textarea { padding: 10px; resize: vertical; font-family: ui-monospace,SFMono-Regular,Consolas,monospace; font-size: 10px; line-height: 1.55; }
.account-form .wide, .form-actions { grid-column: 1 / -1; }
.account-form small { color: var(--wao-text-weak); font-size: 8.5px; font-weight: 400; }
.account-form .enable-row { flex-direction: row; align-items: center; }
.form-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 5px; }
.spin { display: inline-block; animation: spin .9s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@keyframes details-in { from { opacity: 0; transform: translateY(-3px); } }
@media (max-width: 920px) { .oauth-grid, .account-grid { grid-template-columns: 1fr; }.filters { grid-template-columns: 1fr; }.filter-tabs { overflow-x: auto; }.summary-row .secure { margin-left: 0; }.summary-row { flex-wrap: wrap; } }
@media (max-width: 620px) { .page-head { flex-direction: column; }.refresh-btn { align-self: stretch; }.oauth-card { grid-template-columns: 38px 1fr; }.oauth-card > button { grid-column: 1 / -1; }.manager-head { align-items: stretch; flex-direction: column; }.manager-actions { display: grid; grid-template-columns: 1fr 1fr; }.login-actions { flex-wrap: wrap; }.login-actions input { flex-basis: 100%; }.account-form { grid-template-columns: 1fr; }.account-form .wide, .form-actions { grid-column: 1; }.account-foot { align-items: flex-end; }.switch-label { flex-direction: column; }.details-grid { grid-template-columns: 1fr; } }
</style>
