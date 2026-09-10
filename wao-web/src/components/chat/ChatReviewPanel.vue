<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  createSharedChatReview,
  fetchChatReview,
  fetchChatReviews,
  fetchChatReviewAutomation,
  rerunChatReview,
  retryChatReview,
  saveChatReviewAutomation,
  type ChatReviewRunDetail,
  type ChatReviewRunSummary,
  type ChatReviewStage,
} from '@/api/chatReviews'
import { fetchAiCapabilities, type AiCapability } from '@/api/assistant'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ open: boolean; initialRunId?: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const STORAGE_KEY = 'wao-shared-chat-review-draft-v1'
const ACTIVE: ChatReviewStage[] = ['WAITING', 'SYNCING', 'VERIFYING', 'ANALYZING']
const stageLabel: Record<ChatReviewStage, string> = {
  WAITING: '等待执行', SYNCING: '同步聊天', VERIFYING: '核对资料',
  ANALYZING: 'AI 分析', SUCCEEDED: '报告已保存', FAILED: '运行失败',
}

const saved = (() => {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') as { rangeKind?: 'MESSAGES' | 'HOURS'; rangeValue?: number; focus?: string; aiCapabilityId?: string } }
  catch { return {} }
})()
const rangeKind = ref<'MESSAGES' | 'HOURS'>(saved.rangeKind === 'HOURS' ? 'HOURS' : 'MESSAGES')
const rangeValue = ref(saved.rangeValue || 20)
const focus = ref(saved.focus || '')
const aiCapabilityId = ref(saved.aiCapabilityId || 'local-codex')
const capabilities = ref<AiCapability[]>([])
const autoEnabled = ref(false)
const autoEvery = ref(6)
const autoUnit = ref<'MINUTES' | 'HOURS' | 'DAYS'>('HOURS')
const autoRangeKind = ref<'MESSAGES' | 'HOURS'>('MESSAGES')
const autoRangeValue = ref(20)
const autoFocus = ref('')
const autoCapabilityId = ref('local-codex')
const autoNextFireAt = ref<string | null>(null)
const autoSaving = ref(false)
const autoFeedback = ref('')
const list = ref<{ activeRun: ChatReviewRunSummary | null; history: ChatReviewRunSummary[] }>({ activeRun: null, history: [] })
const detail = ref<ChatReviewRunDetail | null>(null)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | null = null
let settingsLoaded = false

const items = computed(() => [list.value.activeRun, ...list.value.history].filter(Boolean) as ChatReviewRunSummary[])
const valueHint = computed(() => rangeKind.value === 'MESSAGES' ? '5～100 条' : '1～168 小时')
const validRange = computed(() => rangeKind.value === 'MESSAGES'
  ? rangeValue.value >= 5 && rangeValue.value <= 100
  : rangeValue.value >= 1 && rangeValue.value <= 168)
const autoIntervalSeconds = computed(() => autoEvery.value * (
  autoUnit.value === 'MINUTES' ? 60 : autoUnit.value === 'HOURS' ? 3600 : 86400))
const validAuto = computed(() => autoEvery.value > 0 && autoIntervalSeconds.value >= 300
  && autoIntervalSeconds.value <= 604800 && (autoRangeKind.value === 'MESSAGES'
    ? autoRangeValue.value >= 5 && autoRangeValue.value <= 100
    : autoRangeValue.value >= 1 && autoRangeValue.value <= 168))

function persistDraft() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ rangeKind: rangeKind.value, rangeValue: rangeValue.value, focus: focus.value, aiCapabilityId: aiCapabilityId.value })) }
  catch { /* 私密模式下仍允许当前会话提交 */ }
}

function rangeText(run: ChatReviewRunSummary) {
  if (run.sourceKind === 'SHARED_CHAT') return run.rangeKind === 'HOURS'
    ? `最近 ${run.rangeValue} 小时` : `最近 ${run.rangeValue} 条消息`
  return `${run.startDate} 至 ${run.endDate}`
}

function timeText(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

async function load(preferredId?: string) {
  loading.value = true
  try {
    list.value = await fetchChatReviews()
    if (!settingsLoaded) {
      const [nextCapabilities, automation] = await Promise.all([
        fetchAiCapabilities(), fetchChatReviewAutomation(),
      ])
      capabilities.value = nextCapabilities
      if (!capabilities.value.some((item) => item.id === aiCapabilityId.value)) aiCapabilityId.value = capabilities.value[0]?.id || 'local-codex'
      autoEnabled.value = automation.enabled
      autoRangeKind.value = automation.rangeKind
      autoRangeValue.value = automation.rangeValue
      autoFocus.value = automation.focus || ''
      autoCapabilityId.value = automation.aiCapabilityId
      autoNextFireAt.value = automation.nextFireAt
      if (automation.intervalSeconds % 86400 === 0) { autoUnit.value = 'DAYS'; autoEvery.value = automation.intervalSeconds / 86400 }
      else if (automation.intervalSeconds % 3600 === 0) { autoUnit.value = 'HOURS'; autoEvery.value = automation.intervalSeconds / 3600 }
      else { autoUnit.value = 'MINUTES'; autoEvery.value = automation.intervalSeconds / 60 }
      settingsLoaded = true
    }
    const id = preferredId || detail.value?.id || list.value.activeRun?.id || list.value.history[0]?.id
    detail.value = id ? await fetchChatReview(id) : null
    error.value = ''
    schedule()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '聊天复盘读取失败，请重试'
  } finally { loading.value = false }
}

function schedule() {
  if (timer) clearTimeout(timer)
  if (!props.open || !list.value.activeRun) return
  timer = setTimeout(() => void load(list.value.activeRun?.id), 2_000)
}

async function select(run: ChatReviewRunSummary) {
  try { detail.value = await fetchChatReview(run.id); error.value = '' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '复盘详情读取失败' }
}

async function submit() {
  if (!validRange.value || submitting.value) return
  submitting.value = true
  error.value = ''
  persistDraft()
  try {
    const created = await createSharedChatReview({ rangeKind: rangeKind.value, rangeValue: rangeValue.value,
      focus: focus.value.trim() || undefined, aiCapabilityId: aiCapabilityId.value })
    focus.value = ''
    persistDraft()
    await load(created.runId)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '复盘创建失败，草稿已保留' }
  finally { submitting.value = false }
}

async function saveAutomation() {
  if (!validAuto.value || autoSaving.value) return
  autoSaving.value = true
  autoFeedback.value = ''
  try {
    const savedConfig = await saveChatReviewAutomation({ enabled: autoEnabled.value,
      intervalSeconds: autoIntervalSeconds.value, rangeKind: autoRangeKind.value,
      rangeValue: autoRangeValue.value, focus: autoFocus.value.trim() || null,
      aiCapabilityId: autoCapabilityId.value })
    autoNextFireAt.value = savedConfig.nextFireAt
    autoFeedback.value = '自动复盘设置已保存'
  } catch (cause) {
    autoFeedback.value = cause instanceof Error ? cause.message : '自动复盘设置保存失败，请重试'
  } finally { autoSaving.value = false }
}

async function retry() {
  if (!detail.value || submitting.value) return
  submitting.value = true
  try { const created = await retryChatReview(detail.value.id); await load(created.runId) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '复盘重试失败' }
  finally { submitting.value = false }
}

async function rerun() {
  if (!detail.value || submitting.value) return
  submitting.value = true
  try { const created = await rerunChatReview(detail.value.id); await load(created.runId) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '复盘再次运行失败' }
  finally { submitting.value = false }
}

watch([rangeKind, rangeValue, focus, aiCapabilityId], persistDraft)
watch(() => props.open, (open) => {
  if (open) void load(props.initialRunId)
  else if (timer) clearTimeout(timer)
}, { immediate: true })
watch(() => props.initialRunId, (id) => { if (props.open && id) void load(id) })
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="review-mask" @click.self="emit('update:open', false)">
      <section class="review-panel" role="dialog" aria-modal="true" aria-labelledby="chat-review-title">
        <header class="panel-head">
          <div><span><i class="ri-chat-history-line"></i></span><div><h2 id="chat-review-title">聊天复盘</h2><p>选择当前共享聊天的真实消息，报告自动托管并沉淀知识库。</p></div></div>
          <button type="button" aria-label="关闭" @click="emit('update:open', false)"><i class="ri-close-line"></i></button>
        </header>

        <div class="panel-body">
          <aside class="review-create">
            <h3>发起 AI 复盘</h3>
            <div class="range-tabs">
              <button type="button" :class="{ active: rangeKind === 'MESSAGES' }" @click="rangeKind = 'MESSAGES'; rangeValue = 20">最近几条</button>
              <button type="button" :class="{ active: rangeKind === 'HOURS' }" @click="rangeKind = 'HOURS'; rangeValue = 6">最近几小时</button>
            </div>
            <label class="range-input"><span>{{ rangeKind === 'MESSAGES' ? '消息条数' : '时间范围' }}</span><input v-model.number="rangeValue" type="number" :min="rangeKind === 'MESSAGES' ? 5 : 1" :max="rangeKind === 'MESSAGES' ? 100 : 168" /><small>{{ valueHint }}</small></label>
            <label class="ai-picker"><span>本次 AI 能力</span><select v-model="aiCapabilityId"><option v-for="capability in capabilities" :key="capability.id" :value="capability.id">{{ capability.label }}{{ capability.model ? ` · ${capability.model}` : '' }}{{ capability.available ? '' : ' · 当前不可用' }}</option></select></label>
            <label class="focus-input"><span>本次重点（可选）</span><textarea v-model="focus" maxlength="1000" rows="4" placeholder="例如：关注已经明确的结论和还没解决的问题"></textarea><small>{{ focus.length }}/1000 · 草稿已保存在本机</small></label>
            <button class="create-button" type="button" :disabled="!validRange || submitting || !!list.activeRun" @click="submit">
              <i :class="submitting ? 'ri-loader-4-line spin' : 'ri-sparkling-2-line'"></i>{{ list.activeRun ? '已有复盘正在执行' : submitting ? '正在创建…' : '生成复盘' }}
            </button>
            <p v-if="error" class="review-error">{{ error }} <button type="button" @click="load(detail?.id)">重试读取</button></p>
            <details class="automation-settings">
              <summary><span><i class="ri-timer-flash-line"></i>自动复盘</span><em>{{ autoEnabled ? '已开启' : '未开启' }}</em></summary>
              <div class="auto-body">
                <label class="auto-switch"><span><b>自动启动复盘</b><small>到期时使用已落库的真实消息</small></span><input v-model="autoEnabled" type="checkbox" /></label>
                <label class="auto-row"><span>每隔</span><input v-model.number="autoEvery" type="number" min="1" /><select v-model="autoUnit"><option value="MINUTES">分钟</option><option value="HOURS">小时</option><option value="DAYS">天</option></select></label>
                <div class="range-tabs"><button type="button" :class="{ active: autoRangeKind === 'MESSAGES' }" @click="autoRangeKind = 'MESSAGES'; autoRangeValue = 20">最近几条</button><button type="button" :class="{ active: autoRangeKind === 'HOURS' }" @click="autoRangeKind = 'HOURS'; autoRangeValue = 6">最近几小时</button></div>
                <label class="auto-row"><span>覆盖范围</span><input v-model.number="autoRangeValue" type="number" :min="autoRangeKind === 'MESSAGES' ? 5 : 1" :max="autoRangeKind === 'MESSAGES' ? 100 : 168" /><em>{{ autoRangeKind === 'MESSAGES' ? '条' : '小时' }}</em></label>
                <label class="ai-picker"><span>自动使用</span><select v-model="autoCapabilityId"><option v-for="capability in capabilities" :key="capability.id" :value="capability.id">{{ capability.label }}{{ capability.model ? ` · ${capability.model}` : '' }}</option></select></label>
                <label class="focus-input"><span>固定关注点（可选）</span><textarea v-model="autoFocus" maxlength="1000" rows="2"></textarea></label>
                <small v-if="autoNextFireAt && autoEnabled">下次触发：{{ timeText(autoNextFireAt) }}</small>
                <button class="save-auto" type="button" :disabled="!validAuto || autoSaving" @click="saveAutomation">{{ autoSaving ? '保存中…' : '保存自动复盘' }}</button>
                <p v-if="autoFeedback" class="auto-feedback">{{ autoFeedback }}</p>
              </div>
            </details>
            <div class="history-list">
              <b>AI 复盘记录</b>
              <button v-for="run in items" :key="run.id" type="button" :class="{ selected: detail?.id === run.id }" @click="select(run)">
                <span><em>{{ run.triggerKind === 'AUTOMATIC' ? '自动复盘' : '聊天复盘' }}</em><strong>{{ rangeText(run) }}</strong><small>{{ run.messageCount }} 条消息 · {{ timeText(run.createdAt) }}</small></span>
                <i :class="ACTIVE.includes(run.stage) ? 'ri-loader-4-line spin' : run.stage === 'SUCCEEDED' ? 'ri-check-line' : 'ri-error-warning-line'"></i>
              </button>
              <p v-if="!loading && !items.length">还没有复盘记录。</p>
            </div>
          </aside>

          <main class="review-result">
            <div v-if="loading && !detail" class="result-state"><i class="ri-loader-4-line spin"></i>正在读取复盘…</div>
            <div v-else-if="!detail" class="result-state"><i class="ri-chat-history-line"></i><b>选择范围开始第一次复盘</b><span>只会读取已落库的真实聊天消息。</span></div>
            <template v-else>
              <header class="result-head"><div><em>聊天复盘</em><h3>{{ rangeText(detail) }}</h3><p>{{ detail.createdByName }} 发起 · {{ detail.messageCount }} 条真实消息</p></div><span :class="detail.stage.toLowerCase()">{{ stageLabel[detail.stage] }}</span></header>
              <div v-if="ACTIVE.includes(detail.stage)" class="progress-card"><i class="ri-loader-4-line spin"></i><div><b>{{ stageLabel[detail.stage] }}</b><span>刷新页面或关闭弹窗都不会中断，完成后会自动出现报告。</span></div></div>
              <div v-else-if="detail.stage === 'FAILED'" class="failed-card"><i class="ri-error-warning-line"></i><div><b>这次没有保存残缺报告</b><p>{{ detail.error }}</p><button type="button" :disabled="submitting" @click="retry">按原消息重试</button></div></div>
              <template v-else-if="detail.report">
                <div class="artifact-links">
                  <a v-if="detail.reportUrl" :href="detail.reportUrl" target="_blank" rel="noopener"><i class="ri-global-line"></i><span><b>公开托管报告</b><small>可直接打开与分享</small></span><i class="ri-arrow-right-up-line"></i></a>
                  <a v-if="detail.knowledgeUrl" :href="detail.knowledgeUrl"><i class="ri-book-2-line"></i><span><b>聊天复盘知识库</b><small>结论已长期沉淀</small></span><i class="ri-arrow-right-line"></i></a>
                </div>
                <button class="rerun-button" type="button" :disabled="submitting" @click="rerun">
                  <i class="ri-refresh-line"></i>{{ submitting ? '正在创建…' : '按原消息再次运行' }}
                </button>
                <article><h4>讨论过程</h4><div v-html="renderMarkdown(detail.report.discussionProcess)"></div></article>
                <article><h4>讨论方向</h4><div v-html="renderMarkdown(detail.report.discussionDirections)"></div></article>
                <article><h4>讨论结论</h4><div v-html="renderMarkdown(detail.report.conclusions)"></div></article>
                <article><h4>后续建议</h4><div v-html="renderMarkdown(detail.report.recommendations)"></div></article>
              </template>
            </template>
          </main>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.review-mask{position:fixed;inset:0;z-index:1102;background:rgba(17,24,39,.46);backdrop-filter:blur(3px);display:grid;place-items:center;padding:24px}.review-panel{width:min(1080px,100%);height:min(760px,calc(100dvh - 48px));overflow:hidden;border:1px solid var(--wao-line);border-radius:20px;background:var(--wao-bg-card);box-shadow:0 24px 70px rgba(15,23,42,.24);display:flex;flex-direction:column}.panel-head{min-height:74px;padding:0 20px;border-bottom:1px solid var(--wao-divider);display:flex;align-items:center;justify-content:space-between}.panel-head>div{display:flex;align-items:center;gap:12px}.panel-head>div>span{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:19px}.panel-head h2,.panel-head p{margin:0}.panel-head h2{font-size:18px}.panel-head p{font-size:10px;color:var(--wao-text-weak)}.panel-head>button{border:0;background:transparent;color:var(--wao-text-weak);font-size:20px;cursor:pointer}.panel-body{min-height:0;flex:1;display:grid;grid-template-columns:310px 1fr}.review-create{min-height:0;overflow:auto;padding:18px;border-right:1px solid var(--wao-divider);background:var(--wao-bg-sub)}.review-create h3{margin:0 0 12px;font-size:14px}.range-tabs{display:grid;grid-template-columns:1fr 1fr;gap:5px;padding:4px;border:1px solid var(--wao-line);border-radius:10px;background:var(--wao-bg-card)}.range-tabs button{border:0;border-radius:7px;padding:7px;background:transparent;color:var(--wao-text-weak);cursor:pointer}.range-tabs button.active{background:var(--wao-primary-soft);color:var(--wao-primary-text);font-weight:650}.range-input,.focus-input{display:grid;gap:5px;margin-top:12px;color:var(--wao-text-2);font-size:10px}.range-input{grid-template-columns:1fr 82px;align-items:center}.range-input small{grid-column:1/-1}.range-input input,.focus-input textarea{border:1px solid var(--wao-line-control);border-radius:9px;background:var(--wao-bg-card);color:var(--wao-text);padding:8px;font:inherit}.focus-input textarea{resize:vertical;line-height:1.5}.range-input small,.focus-input small{color:var(--wao-text-faint)}.create-button{width:100%;height:38px;margin-top:12px;border:0;border-radius:10px;background:var(--wao-primary);color:#fff;cursor:pointer}.create-button:disabled{opacity:.48;cursor:not-allowed}.review-error{padding:8px;border-radius:8px;background:var(--wao-danger-tint);color:var(--wao-danger);font-size:10px}.review-error button{border:0;background:transparent;color:inherit;text-decoration:underline}.history-list{display:grid;gap:6px;margin-top:20px}.history-list>b{font-size:11px;color:var(--wao-text-weak)}.history-list>button{width:100%;border:1px solid transparent;border-radius:10px;background:transparent;padding:8px;text-align:left;display:flex;align-items:center;gap:8px;color:var(--wao-text-2);cursor:pointer}.history-list>button.selected{border-color:var(--wao-line);background:var(--wao-bg-card)}.history-list>button>span{min-width:0;flex:1;display:grid}.history-list em,.result-head em{width:max-content;padding:2px 6px;border-radius:999px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:8px;font-style:normal}.history-list strong{font-size:10px}.history-list small{color:var(--wao-text-faint);font-size:8px}.history-list p{color:var(--wao-text-faint);font-size:10px}.review-result{min-width:0;overflow:auto;padding:22px}.result-state{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;color:var(--wao-text-weak)}.result-head{display:flex;justify-content:space-between;align-items:flex-start}.result-head h3{margin:7px 0 2px;font-size:20px}.result-head p{margin:0;color:var(--wao-text-weak);font-size:10px}.result-head>span{padding:5px 9px;border-radius:999px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:9px}.result-head>span.succeeded{background:var(--wao-success-bg);color:var(--wao-success-strong)}.result-head>span.failed{background:var(--wao-danger-tint);color:var(--wao-danger)}.progress-card,.failed-card{margin-top:18px;padding:18px;border:1px solid var(--wao-line);border-radius:14px;display:flex;gap:12px}.progress-card div,.failed-card div{display:grid;gap:4px}.progress-card span,.failed-card p{margin:0;color:var(--wao-text-weak);font-size:10px}.failed-card{border-color:color-mix(in srgb,var(--wao-danger) 35%,var(--wao-line));color:var(--wao-danger)}.failed-card button{width:max-content;border:0;border-radius:8px;padding:7px 10px;background:var(--wao-danger);color:#fff}.artifact-links{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:18px 0 10px}.artifact-links a{border:1px solid var(--wao-line);border-radius:12px;padding:12px;display:flex;align-items:center;gap:10px;color:var(--wao-text-2);text-decoration:none}.artifact-links a>span{flex:1;display:grid}.artifact-links small{color:var(--wao-text-weak);font-size:9px}.rerun-button{margin:0 0 12px;border:1px solid var(--wao-line-control);border-radius:9px;padding:7px 10px;background:var(--wao-bg-card);color:var(--wao-primary-text);cursor:pointer}.rerun-button:disabled{opacity:.5}.review-result article{padding:16px 0;border-top:1px solid var(--wao-divider)}.review-result article h4{margin:0 0 8px;color:var(--wao-primary-text)}.review-result article div{font-size:12px;line-height:1.75}.spin{display:inline-block;animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.ai-picker{display:grid;gap:5px;margin-top:12px;color:var(--wao-text-2);font-size:10px}.ai-picker select,.auto-row input,.auto-row select{min-width:0;border:1px solid var(--wao-line-control);border-radius:9px;background:var(--wao-bg-card);color:var(--wao-text);padding:8px;font:inherit}.automation-settings{margin-top:16px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-card)}.automation-settings summary{padding:10px;cursor:pointer;display:flex;align-items:center;justify-content:space-between;font-size:11px}.automation-settings summary span{display:flex;align-items:center;gap:5px;font-weight:650}.automation-settings summary em{color:var(--wao-primary-text);font-size:9px;font-style:normal}.auto-body{padding:0 10px 10px;display:grid;gap:9px}.auto-switch{display:flex;align-items:center;justify-content:space-between}.auto-switch span{display:grid}.auto-switch small,.auto-body>small{color:var(--wao-text-faint);font-size:9px}.auto-row{display:grid;grid-template-columns:auto minmax(0,1fr) 72px;align-items:center;gap:6px;color:var(--wao-text-weak);font-size:10px}.auto-row em{font-style:normal}.auto-body .ai-picker,.auto-body .focus-input{margin-top:0}.save-auto{height:32px;border:0;border-radius:8px;background:var(--wao-primary-soft);color:var(--wao-primary-text);cursor:pointer}.save-auto:disabled{opacity:.5}.auto-feedback{margin:0;color:var(--wao-text-weak);font-size:9px}
@media(max-width:720px){.review-mask{padding:0}.review-panel{height:100dvh;border:0;border-radius:0}.panel-body{grid-template-columns:1fr}.review-create{max-height:45vh;border-right:0;border-bottom:1px solid var(--wao-divider)}.artifact-links{grid-template-columns:1fr}.review-result{padding:16px}}
</style>
