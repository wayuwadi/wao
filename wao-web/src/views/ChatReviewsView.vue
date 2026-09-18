<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createChatReview,
  fetchChatReview,
  fetchChatReviewConfig,
  fetchChatReviews,
  rerunChatReview,
  retryChatReview,
  saveChatReviewConfig,
  type ChatReviewConfig,
  type ChatReviewReport,
  type ChatReviewRunDetail,
  type ChatReviewRunSummary,
  type ChatReviewStage,
} from '@/api/chatReviews'
import { toast } from '@/composables/toast'
import { useSessionStore } from '@/stores/session'
import { copyText } from '@/utils/clipboard'
import {
  chatReviewRangeError,
  defaultChatReviewRange,
  inclusiveDays,
  MIN_CHAT_REVIEW_DATE,
  parseShanghaiDateTime,
  shanghaiDate,
} from '@/utils/chatReviews'
import { renderChatReviewMarkdown } from '@/utils/markdown'

type DraftState = 'idle' | 'saving' | 'saved' | 'error'

interface StoredDraft {
  startDate: string
  endDate: string
  focus: string
  updatedAt: string
}

const POLL_MS = 5_000
const ACTIVE_STAGES: ChatReviewStage[] = ['WAITING', 'SYNCING', 'VERIFYING', 'ANALYZING']
const STEPS: Array<{ stage: Exclude<ChatReviewStage, 'FAILED'>; label: string; hint: string }> = [
  { stage: 'WAITING', label: '等待执行', hint: '已入队，等待后台编排' },
  { stage: 'SYNCING', label: '同步聊天', hint: '按天幂等更新群聊归档' },
  { stage: 'VERIFYING', label: '核对资料', hint: '只保留两位指定发言人' },
  { stage: 'ANALYZING', label: 'AI 分析', hint: '核验四段报告与真实引用' },
  { stage: 'SUCCEEDED', label: '报告已保存', hint: '双方可查看并回到原文' },
]
const REPORT_SECTIONS: Array<{
  key: keyof ChatReviewReport
  title: string
  icon: string
}> = [
  { key: 'discussionProcess', title: '讨论过程', icon: 'ri-chat-1-line' },
  { key: 'discussionDirections', title: '讨论方向', icon: 'ri-compass-3-line' },
  { key: 'conclusions', title: '讨论结论', icon: 'ri-checkbox-circle-line' },
  { key: 'recommendations', title: '后续建议', icon: 'ri-lightbulb-flash-line' },
]

const session = useSessionStore()
const initialRange = defaultChatReviewRange()
const form = reactive({
  startDate: initialRange.startDate,
  endDate: initialRange.endDate,
  focus: '',
})
const today = shanghaiDate()

const config = ref<ChatReviewConfig | null>(null)
const configLoading = ref(true)
const configError = ref('')
const configEditing = ref(false)
const configSaving = ref(false)
const speakerOne = ref('')
const speakerTwo = ref('')

const activeRun = ref<ChatReviewRunSummary | null>(null)
const history = ref<ChatReviewRunSummary[]>([])
const listLoading = ref(true)
const listError = ref('')
const selectedId = ref('')
const selectedDetail = ref<ChatReviewRunDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const newResultId = ref('')
const newResultStage = ref<'SUCCEEDED' | 'FAILED' | ''>('')
const autoOpenRunId = ref('')

const submitting = ref(false)
const retryingId = ref('')
const submitError = ref('')
const draftState = ref<DraftState>('idle')
const draftSavedAt = ref('')
let draftReady = false
let draftTimer: ReturnType<typeof setTimeout> | null = null
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollInFlight = false
let detailToken = 0
let alive = true

const storageKey = computed(() => `wao-chat-review-draft-v1:${session.userId || 'unknown'}`)
const rangeError = computed(() => chatReviewRangeError(form.startDate, form.endDate, today))
const focusLength = computed(() => Array.from(form.focus).length)
const focusError = computed(() => (focusLength.value > 1000 ? '本次重点最多填写 1000 个字' : ''))
const formError = computed(() => rangeError.value || focusError.value)
const rangeDays = computed(() => inclusiveDays(form.startDate, form.endDate))
const runActive = computed(() => !!activeRun.value && ACTIVE_STAGES.includes(activeRun.value.stage))
const canSubmit = computed(
  () => !!config.value?.configured && !runActive.value && !submitting.value && !formError.value,
)
const configSelectionValid = computed(
  () => !!speakerOne.value && !!speakerTwo.value && speakerOne.value !== speakerTwo.value,
)
const selectedReport = computed(() => {
  const report = selectedDetail.value?.report
  if (!report) return null
  return REPORT_SECTIONS.every(({ key }) => typeof report[key] === 'string' && report[key].trim())
    ? report
    : null
})

function messageOf(cause: unknown, fallback: string) {
  return cause instanceof Error && cause.message ? cause.message : fallback
}

function isActive(stage: ChatReviewStage) {
  return ACTIVE_STAGES.includes(stage)
}

function readDraft() {
  try {
    const raw = localStorage.getItem(storageKey.value)
    if (!raw) return
    const stored = JSON.parse(raw) as Partial<StoredDraft>
    if (typeof stored.startDate === 'string') form.startDate = stored.startDate
    if (typeof stored.endDate === 'string') form.endDate = stored.endDate
    if (typeof stored.focus === 'string') form.focus = stored.focus
    draftSavedAt.value = typeof stored.updatedAt === 'string' ? stored.updatedAt : ''
    draftState.value = 'saved'
  } catch {
    draftState.value = 'error'
  }
}

function persistDraft() {
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = null
  try {
    const updatedAt = new Date().toISOString()
    const stored: StoredDraft = {
      startDate: form.startDate,
      endDate: form.endDate,
      focus: form.focus,
      updatedAt,
    }
    localStorage.setItem(storageKey.value, JSON.stringify(stored))
    draftSavedAt.value = updatedAt
    draftState.value = 'saved'
  } catch {
    draftState.value = 'error'
  }
}

function scheduleDraft() {
  if (!draftReady) return
  draftState.value = 'saving'
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(persistDraft, 450)
}

watch(() => [form.startDate, form.endDate, form.focus], scheduleDraft)

function formatClock(value: string) {
  if (!value) return ''
  const date = parseShanghaiDateTime(value)
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleTimeString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
      })
}

function formatTime(value: string | null) {
  if (!value) return ''
  const date = parseShanghaiDateTime(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

function stageLabel(stage: ChatReviewStage) {
  return stage === 'FAILED'
    ? '运行失败'
    : (STEPS.find((step) => step.stage === stage)?.label ?? stage)
}

function failedStage(run: ChatReviewRunSummary) {
  return run.failedStage && ACTIVE_STAGES.includes(run.failedStage) ? run.failedStage : 'WAITING'
}

function stepState(run: ChatReviewRunSummary, stage: Exclude<ChatReviewStage, 'FAILED'>) {
  const index = STEPS.findIndex((step) => step.stage === stage)
  if (run.stage === 'SUCCEEDED') return 'done'
  if (run.stage === 'FAILED') {
    const failedIndex = STEPS.findIndex((step) => step.stage === failedStage(run))
    if (index < failedIndex) return 'done'
    return index === failedIndex ? 'failed' : 'pending'
  }
  const currentIndex = STEPS.findIndex((step) => step.stage === run.stage)
  if (index < currentIndex) return 'done'
  return index === currentIndex ? 'active' : 'pending'
}

function progressText(run: ChatReviewRunSummary) {
  if (run.stage === 'SYNCING' && run.progressTotal > 0) {
    const date = run.currentDate ? ` · ${run.currentDate}` : ''
    return `已处理 ${run.progressCurrent} / ${run.progressTotal} 天${date}`
  }
  if (run.stage === 'VERIFYING') return `正在核对有效消息 · ${run.messageCount} 条`
  if (run.stage === 'ANALYZING') return `已选出 ${run.messageCount} 条消息，正在生成与验证报告`
  if (run.stage === 'WAITING') return '运行已保存，刷新或换设备也不会丢失'
  if (run.stage === 'FAILED') return run.error || '本次运行失败'
  return `报告已保存 · ${run.messageCount} 条有效消息`
}

function setConfigSelection(value: ChatReviewConfig) {
  speakerOne.value = value.selectedSpeakers[0]?.id ?? value.availableSpeakers[0]?.id ?? ''
  speakerTwo.value =
    value.selectedSpeakers[1]?.id ??
    value.availableSpeakers.find((speaker) => speaker.id !== speakerOne.value)?.id ??
    ''
}

async function loadConfig() {
  configLoading.value = true
  configError.value = ''
  try {
    const loaded = await fetchChatReviewConfig()
    if (!alive) return
    config.value = loaded
    setConfigSelection(loaded)
    configEditing.value = !loaded.configured
  } catch (cause) {
    if (alive) configError.value = messageOf(cause, '聊天复盘配置读取失败')
  } finally {
    if (alive) configLoading.value = false
  }
}

async function saveConfig() {
  if (!configSelectionValid.value || configSaving.value) return
  configSaving.value = true
  configError.value = ''
  try {
    const saved = await saveChatReviewConfig([speakerOne.value, speakerTwo.value])
    if (!alive) return
    config.value = saved
    setConfigSelection(saved)
    configEditing.value = false
    toast('两位分析对象已保存', 'ri-check-line')
  } catch (cause) {
    if (alive) configError.value = messageOf(cause, '分析对象保存失败')
  } finally {
    if (alive) configSaving.value = false
  }
}

function editConfig() {
  if (!config.value || runActive.value) return
  setConfigSelection(config.value)
  configEditing.value = true
}

function cancelConfigEdit() {
  if (!config.value?.configured) return
  setConfigSelection(config.value)
  configEditing.value = false
  configError.value = ''
}

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function schedulePoll(delay = POLL_MS) {
  stopPolling()
  if (!alive || !runActive.value || document.hidden) return
  pollTimer = setTimeout(() => void pollActiveRun(), delay)
}

async function loadOverview(selectDefault = false) {
  if (selectDefault) listLoading.value = true
  listError.value = ''
  try {
    const loaded = await fetchChatReviews()
    if (!alive) return
    activeRun.value = loaded.activeRun
    history.value = loaded.history
    if (selectDefault && !selectedId.value) {
      const first = loaded.history.find((run) => run.stage === 'SUCCEEDED') ?? loaded.history[0]
      if (first) await selectRun(first.id)
      else if (loaded.activeRun) autoOpenRunId.value = loaded.activeRun.id
    }
    if (loaded.activeRun && isActive(loaded.activeRun.stage)) schedulePoll()
    else stopPolling()
  } catch (cause) {
    if (alive) listError.value = messageOf(cause, '运行与历史读取失败')
  } finally {
    if (alive) listLoading.value = false
  }
}

async function selectRun(id: string) {
  const token = ++detailToken
  selectedId.value = id
  detailLoading.value = true
  detailError.value = ''
  try {
    const detail = await fetchChatReview(id)
    if (!alive || token !== detailToken || selectedId.value !== id) return
    selectedDetail.value = detail
    if (newResultId.value === id) {
      newResultId.value = ''
      newResultStage.value = ''
    }
  } catch (cause) {
    if (alive && token === detailToken) {
      detailError.value = messageOf(cause, '复盘详情读取失败')
    }
  } finally {
    if (alive && token === detailToken) detailLoading.value = false
  }
}

async function acceptRun(runId: string) {
  try {
    const detail = await fetchChatReview(runId)
    if (!alive) return
    activeRun.value = detail
  } catch {
    await loadOverview(false)
  }
  if (runActive.value) schedulePoll()
}

async function enqueue(payload: { startDate: string; endDate: string; focus?: string }) {
  if (submitting.value || runActive.value) return
  submitting.value = true
  submitError.value = ''
  try {
    const result = await createChatReview(payload)
    autoOpenRunId.value = selectedDetail.value ? '' : result.runId
    await acceptRun(result.runId)
    toast('复盘已入队，页面会持续更新', 'ri-time-line')
  } catch (cause) {
    submitError.value = messageOf(cause, '复盘提交失败')
    // 可能是另一台设备抢先创建了运行，立即重读服务端状态。
    await loadOverview(false)
  } finally {
    submitting.value = false
  }
}

async function submitReview() {
  if (!canSubmit.value) return
  const focus = form.focus.trim()
  await enqueue({
    startDate: form.startDate,
    endDate: form.endDate,
    ...(focus ? { focus } : {}),
  })
}

async function runAgain(run: ChatReviewRunSummary) {
  if (submitting.value || runActive.value || run.stage !== 'SUCCEEDED') return
  submitting.value = true
  submitError.value = ''
  try {
    const result = await rerunChatReview(run.id)
    autoOpenRunId.value = selectedDetail.value ? '' : result.runId
    await acceptRun(result.runId)
    toast('已按报告快照重新入队', 'ri-refresh-line')
  } catch (cause) {
    submitError.value = messageOf(cause, '重新运行失败')
    await loadOverview(false)
  } finally {
    submitting.value = false
  }
}

async function retry(run: ChatReviewRunSummary) {
  if (retryingId.value || runActive.value) return
  retryingId.value = run.id
  submitError.value = ''
  try {
    const result = await retryChatReview(run.id)
    await acceptRun(result.runId)
    toast('已重新同步整个日期范围', 'ri-refresh-line')
  } catch (cause) {
    submitError.value = messageOf(cause, '重试失败')
    await loadOverview(false)
  } finally {
    retryingId.value = ''
  }
}

async function pollActiveRun() {
  if (!alive || pollInFlight || !activeRun.value || document.hidden) return
  const id = activeRun.value.id
  pollInFlight = true
  try {
    const detail = await fetchChatReview(id)
    if (!alive || activeRun.value?.id !== id) return
    activeRun.value = detail
    if (isActive(detail.stage)) {
      schedulePoll()
      return
    }

    stopPolling()
    await loadOverview(false)
    if (detail.stage === 'SUCCEEDED') {
      if (autoOpenRunId.value === id || !selectedDetail.value) await selectRun(id)
      else {
        newResultId.value = id
        newResultStage.value = 'SUCCEEDED'
      }
      toast('聊天复盘已生成并保存', 'ri-check-line')
    } else {
      if (!selectedDetail.value) await selectRun(id)
      else {
        newResultId.value = id
        newResultStage.value = 'FAILED'
      }
      toast('聊天复盘运行失败，可查看原因并重试', 'ri-error-warning-line')
    }
    autoOpenRunId.value = ''
  } catch {
    // 保留上一帧，网络恢复后下一轮继续。
    schedulePoll()
  } finally {
    pollInFlight = false
  }
}

function onVisibilityChange() {
  if (!document.hidden && runActive.value) {
    stopPolling()
    void pollActiveRun()
  }
}

function reportMarkdown(detail: ChatReviewRunDetail) {
  if (!detail.report) return ''
  const sections = REPORT_SECTIONS.map(({ key, title }) => `## ${title}\n\n${detail.report![key]}`)
  const sources = detail.sources.length
    ? detail.sources
        .map(
          (source) =>
            `- [${source.id}] ${source.date} · ${source.messageCount} 条消息${source.url ? ` · [打开原文](${source.url})` : ''}`,
        )
        .join('\n')
    : '- 无'
  return [
    `# 聊天复盘（${detail.startDate} 至 ${detail.endDate}）`,
    ...sections,
    `## 证据来源\n\n${sources}`,
  ].join('\n\n')
}

function copyReport() {
  if (selectedDetail.value && selectedReport.value) {
    void copyText(reportMarkdown(selectedDetail.value), '复盘 Markdown 已复制')
  }
}

onMounted(async () => {
  readDraft()
  // 先让草稿恢复引发的 watcher 走完，避免刚打开就把“已恢复”闪成“保存中”。
  await nextTick()
  draftReady = true
  document.addEventListener('visibilitychange', onVisibilityChange)
  await Promise.allSettled([loadConfig(), loadOverview(true)])
})

onBeforeUnmount(() => {
  alive = false
  draftReady = false
  stopPolling()
  if (draftTimer) persistDraft()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <main class="chat-review-page">
    <header class="hero">
      <div>
        <span class="eyebrow"><i class="ri-chat-history-line"></i> SHARED CHAT REVIEW</span>
        <h1>娃与娃聊天复盘</h1>
        <p>重新同步真实飞书聊天，只分析两位指定发言人，报告与来源双方共享。</p>
      </div>
      <button v-if="selectedReport" class="copy-button" type="button" @click="copyReport">
        <i class="ri-file-copy-line"></i><span>复制 Markdown</span>
      </button>
    </header>

    <section class="guardrail">
      <i class="ri-shield-check-line"></i>
      <div>
        <b>证据优先</b>
        <span>图片、语音、视频和文件只按归档占位显示，不声称已做视觉识别或语音转写。</span>
      </div>
    </section>

    <section class="config-card">
      <div v-if="configLoading" class="state-inline">
        <i class="ri-loader-4-line spin"></i>正在读取群聊与发言人…
      </div>
      <div v-else-if="!config" class="state-inline error">
        <i class="ri-error-warning-line"></i><span>{{ configError || '配置读取失败' }}</span>
        <button type="button" @click="loadConfig">重试</button>
      </div>
      <template v-else>
        <header class="section-head">
          <div>
            <span>01 · 分析范围</span>
            <h2>{{ config.configured ? '已固定两位分析对象' : '先选择两位分析对象' }}</h2>
          </div>
          <button
            v-if="config.configured && !configEditing"
            class="secondary-button"
            type="button"
            :disabled="runActive"
            @click="editConfig"
          >
            <i class="ri-user-settings-line"></i>修改分析对象
          </button>
        </header>

        <div class="chat-chip" :class="{ missing: !config.chatConfigured }">
          <i class="ri-group-2-line"></i>
          <span
            ><b>{{ config.chatName || '尚未配置群聊' }}</b
            ><small>{{ config.chatId || config.message }}</small></span
          >
        </div>

        <div v-if="configEditing" class="speaker-editor">
          <label>
            <span>分析对象一</span>
            <select v-model="speakerOne" :disabled="!config.chatConfigured || configSaving">
              <option value="">请选择</option>
              <option
                v-for="speaker in config.availableSpeakers"
                :key="speaker.id"
                :value="speaker.id"
                :disabled="speaker.id === speakerTwo"
              >
                {{ speaker.name }}
              </option>
            </select>
          </label>
          <label>
            <span>分析对象二</span>
            <select v-model="speakerTwo" :disabled="!config.chatConfigured || configSaving">
              <option value="">请选择</option>
              <option
                v-for="speaker in config.availableSpeakers"
                :key="speaker.id"
                :value="speaker.id"
                :disabled="speaker.id === speakerOne"
              >
                {{ speaker.name }}
              </option>
            </select>
          </label>
          <div class="config-actions">
            <button
              v-if="config.configured"
              class="secondary-button"
              type="button"
              @click="cancelConfigEdit"
            >
              取消
            </button>
            <button
              class="primary-button compact"
              type="button"
              :disabled="!config.chatConfigured || !configSelectionValid || configSaving"
              @click="saveConfig"
            >
              <i :class="configSaving ? 'ri-loader-4-line spin' : 'ri-lock-line'"></i>
              {{ configSaving ? '保存中…' : '固定这两位发言人' }}
            </button>
          </div>
        </div>
        <div v-else class="selected-speakers">
          <span v-for="speaker in config.selectedSpeakers" :key="speaker.id">
            <i class="ri-user-voice-line"></i>{{ speaker.name }}
          </span>
        </div>

        <p v-if="config.message" class="config-message">{{ config.message }}</p>
        <p v-if="configError" class="inline-error">
          <i class="ri-error-warning-line"></i>{{ configError }}
        </p>
        <RouterLink v-if="!config.chatConfigured" class="automation-link" to="/automation">
          前往“自动化”配置唯一飞书群聊<i class="ri-arrow-right-line"></i>
        </RouterLink>
      </template>
    </section>

    <section class="request-card">
      <header class="section-head request-head">
        <div>
          <span>02 · 发起复盘</span>
          <h2>选择上海时区的自然日</h2>
        </div>
        <div class="draft-pill" :class="draftState">
          <i
            :class="
              draftState === 'saving'
                ? 'ri-loader-4-line spin'
                : draftState === 'error'
                  ? 'ri-error-warning-line'
                  : 'ri-save-3-line'
            "
          ></i>
          <button v-if="draftState === 'error'" type="button" @click="persistDraft">
            草稿保存失败 · 重试
          </button>
          <span v-else-if="draftState === 'saving'">草稿保存中…</span>
          <span v-else-if="draftState === 'saved'"
            >草稿已保存{{
              formatClock(draftSavedAt) ? ` · ${formatClock(draftSavedAt)}` : ''
            }}</span
          >
          <span v-else>修改后自动保存在本机</span>
        </div>
      </header>

      <div class="date-grid">
        <label
          ><span>开始日期</span
          ><input v-model="form.startDate" type="date" :min="MIN_CHAT_REVIEW_DATE" :max="form.endDate || today"
        /></label>
        <label
          ><span>结束日期</span
          ><input v-model="form.endDate" type="date" :min="form.startDate || MIN_CHAT_REVIEW_DATE" :max="today"
        /></label>
      </div>
      <label class="focus-field">
        <span
          ><b>本次重点</b><em>{{ focusLength }} / 1000</em></span
        >
        <textarea
          v-model="form.focus"
          maxlength="1000"
          placeholder="可选：例如“重点梳理关于 WAO 上线节奏的分歧与结论”"
        ></textarea>
        <small>重点只影响分析关注点，不会改变四段报告和证据约束。</small>
      </label>
      <div class="request-actions">
        <span :class="{ error: !!formError }">
          <i :class="formError ? 'ri-error-warning-line' : 'ri-calendar-check-line'"></i>
          {{ formError || `共 ${rangeDays} 天，单次最多 31 天` }}
        </span>
        <button class="primary-button" type="button" :disabled="!canSubmit" @click="submitReview">
          <i :class="submitting ? 'ri-loader-4-line spin' : 'ri-sparkling-2-line'"></i>
          {{ submitting ? '正在提交…' : runActive ? '当前已有运行' : '同步并生成复盘' }}
        </button>
      </div>
      <p v-if="!config?.configured" class="inline-error">
        <i class="ri-information-line"></i>先在上方固定两位分析对象，才能开始复盘。
      </p>
      <p v-if="submitError" class="inline-error">
        <i class="ri-error-warning-line"></i>{{ submitError }}
      </p>
    </section>

    <section v-if="activeRun" class="run-card">
      <header class="section-head">
        <div>
          <span>03 · 实时运行</span>
          <h2>{{ activeRun.startDate }} 至 {{ activeRun.endDate }}</h2>
        </div>
        <em>{{ stageLabel(activeRun.stage) }}</em>
      </header>
      <p v-if="activeRun.focus" class="run-focus">
        <i class="ri-focus-3-line"></i>{{ activeRun.focus }}
      </p>
      <ol class="stage-list" aria-label="复盘进度" aria-live="polite">
        <li v-for="step in STEPS" :key="step.stage" :class="stepState(activeRun, step.stage)">
          <span class="stage-dot">
            <i
              :class="
                stepState(activeRun, step.stage) === 'done'
                  ? 'ri-check-line'
                  : stepState(activeRun, step.stage) === 'failed'
                    ? 'ri-close-line'
                    : 'ri-sparkling-2-line'
              "
            ></i>
          </span>
          <div>
            <b>{{ step.label }}</b
            ><small>{{ step.hint }}</small>
          </div>
        </li>
      </ol>
      <div class="run-progress" :class="{ failed: activeRun.stage === 'FAILED' }">
        <i :class="activeRun.stage === 'FAILED' ? 'ri-error-warning-line' : 'ri-pulse-line'"></i>
        <span>{{ progressText(activeRun) }}</span>
        <b v-if="activeRun.failedDate">失败日期：{{ activeRun.failedDate }}</b>
      </div>
      <button
        v-if="activeRun.stage === 'FAILED'"
        class="primary-button retry-button"
        type="button"
        :disabled="!!retryingId"
        @click="retry(activeRun)"
      >
        <i :class="retryingId ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>
        {{ retryingId ? '重新入队中…' : '重试整个日期范围' }}
      </button>
    </section>

    <div
      v-if="newResultId"
      class="new-result"
      :class="{ failed: newResultStage === 'FAILED' }"
      role="status"
    >
      <i
        :class="newResultStage === 'FAILED' ? 'ri-error-warning-line' : 'ri-checkbox-circle-line'"
      ></i>
      <span>{{
        newResultStage === 'FAILED'
          ? '新运行已失败，你正在阅读的报告没有被切走。'
          : '新复盘已保存，你正在阅读的旧报告没有被切走。'
      }}</span>
      <button type="button" @click="selectRun(newResultId)">
        {{ newResultStage === 'FAILED' ? '查看失败原因' : '查看新报告' }}
      </button>
    </div>

    <div class="archive-layout">
      <section class="report-panel">
        <div v-if="detailLoading" class="detail-state">
          <i class="ri-loader-4-line spin"></i>正在读取复盘详情…
        </div>
        <div v-else-if="detailError" class="detail-state error">
          <i class="ri-error-warning-line"></i><span>{{ detailError }}</span>
          <button type="button" @click="selectRun(selectedId)">重试</button>
        </div>
        <div v-else-if="!selectedDetail" class="detail-state empty">
          <i class="ri-chat-history-line"></i><b>还没有历史复盘</b
          ><span>第一份报告完成后会在这里长期保留。</span>
        </div>
        <template v-else>
          <header class="report-head">
            <div>
              <span>{{ selectedDetail.stage === 'SUCCEEDED' ? '已保存复盘' : '失败运行' }}</span>
              <h2>{{ selectedDetail.startDate }} 至 {{ selectedDetail.endDate }}</h2>
              <p>
                {{ selectedDetail.chatName }} ·
                {{
                  selectedDetail.speakers.map((speaker) => speaker.name).join(' 与 ') ||
                  '分析对象未记录'
                }}
              </p>
            </div>
            <div class="report-actions">
              <button v-if="selectedReport" type="button" @click="copyReport">
                <i class="ri-file-copy-line"></i>复制 Markdown
              </button>
              <button
                v-if="selectedDetail.stage === 'SUCCEEDED'"
                type="button"
                :disabled="runActive || submitting"
                @click="runAgain(selectedDetail)"
              >
                <i class="ri-refresh-line"></i>按原参数再次运行
              </button>
            </div>
          </header>

          <dl class="report-meta">
            <div>
              <dt>创建人</dt>
              <dd>{{ selectedDetail.createdByName }}</dd>
            </div>
            <div>
              <dt>生成时间</dt>
              <dd>{{ formatTime(selectedDetail.finishedAt) || '未完成' }}</dd>
            </div>
            <div>
              <dt>有效消息</dt>
              <dd>{{ selectedDetail.messageCount }} 条</dd>
            </div>
            <div>
              <dt>证据日期</dt>
              <dd>{{ selectedDetail.sources.length }} 天</dd>
            </div>
          </dl>
          <p v-if="selectedDetail.focus" class="report-focus">
            <b>本次重点</b>{{ selectedDetail.focus }}
          </p>

          <div v-if="selectedDetail.stage === 'FAILED'" class="failed-detail">
            <i class="ri-error-warning-line"></i>
            <div>
              <b>{{
                selectedDetail.failedDate ? `${selectedDetail.failedDate} 处理失败` : '本次复盘失败'
              }}</b>
              <p>{{ selectedDetail.error || '服务端没有返回更多错误信息' }}</p>
            </div>
            <button
              type="button"
              :disabled="runActive || !!retryingId"
              @click="retry(selectedDetail)"
            >
              {{ retryingId === selectedDetail.id ? '重试中…' : '重试' }}
            </button>
          </div>

          <div v-else-if="selectedReport" class="report-sections">
            <article v-for="section in REPORT_SECTIONS" :key="section.key">
              <h3><i :class="section.icon"></i>{{ section.title }}</h3>
              <div
                class="markdown"
                v-html="renderChatReviewMarkdown(selectedReport[section.key])"
              ></div>
            </article>
          </div>
          <div v-else class="invalid-report">
            <i class="ri-shield-cross-line"></i>报告字段不完整，本页不会把残缺结果当成成功报告展示。
          </div>

          <section v-if="selectedDetail.sources.length" class="sources-card">
            <header>
              <div>
                <span>证据来源</span>
                <h3>回到每日原始聊天</h3>
              </div>
              <em>{{ selectedDetail.sources.length }} 天</em>
            </header>
            <div class="source-list">
              <a
                v-for="source in selectedDetail.sources"
                :key="source.id"
                :href="source.url"
                target="_blank"
                rel="noopener noreferrer"
              >
                <b>{{ source.id }}</b>
                <span
                  ><strong>{{ source.date }}</strong
                  ><small>{{ source.messageCount }} 条有效消息</small></span
                >
                <i class="ri-arrow-right-up-line"></i>
              </a>
            </div>
          </section>
        </template>
      </section>

      <aside class="history-panel">
        <header class="section-head">
          <div>
            <span>04 · 共享历史</span>
            <h2>过往复盘</h2>
          </div>
          <em>{{ history.length }} 条</em>
        </header>
        <div v-if="listLoading" class="history-state">
          <i class="ri-loader-4-line spin"></i>读取中…
        </div>
        <div v-else-if="listError" class="history-state error">
          <i class="ri-error-warning-line"></i><span>{{ listError }}</span
          ><button type="button" @click="loadOverview(true)">重试</button>
        </div>
        <div v-else-if="!history.length" class="history-state">
          <i class="ri-inbox-2-line"></i>还没有已结束运行
        </div>
        <div v-else class="history-list">
          <button
            v-for="run in history"
            :key="run.id"
            type="button"
            :class="{ active: selectedId === run.id, failed: run.stage === 'FAILED' }"
            @click="selectRun(run.id)"
          >
            <span class="history-status"
              ><i
                :class="
                  run.stage === 'SUCCEEDED' ? 'ri-checkbox-circle-line' : 'ri-error-warning-line'
                "
              ></i
              >{{ stageLabel(run.stage) }}</span
            >
            <b>{{ run.startDate }} 至 {{ run.endDate }}</b>
            <p>{{ run.focus || '未填写本次重点' }}</p>
            <small
              >{{ run.createdByName }} · {{ formatTime(run.finishedAt || run.createdAt) }}</small
            >
          </button>
        </div>
      </aside>
    </div>
  </main>
</template>

<style scoped>
.chat-review-page {
  max-width: none;
  margin: 0 auto;
  padding: 24px 28px 58px;
  color: var(--wao-text);
}
.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
}
.eyebrow {
  color: #436b91;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1.5px;
}
.eyebrow i {
  margin-right: 5px;
}
.hero h1 {
  margin: 5px 0 3px;
  font-size: 27px;
}
.hero p {
  max-width: 720px;
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 12.5px;
  line-height: 1.65;
}
button {
  font: inherit;
}
.copy-button,
.primary-button {
  min-height: 38px;
  padding: 0 15px;
  border: 0;
  border-radius: 9px;
  background: #345f85;
  color: #fff;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 680;
}
.copy-button {
  flex: none;
}
.primary-button:hover:not(:disabled),
.copy-button:hover {
  background: #294f70;
}
.primary-button:disabled,
.secondary-button:disabled {
  opacity: 0.45;
  cursor: default;
}
.primary-button.compact {
  min-height: 34px;
}
.guardrail {
  margin-top: 17px;
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, #436b91 23%, var(--wao-line));
  border-radius: 11px;
  background: color-mix(in srgb, #436b91 5%, var(--wao-bg-card));
  display: flex;
  gap: 10px;
}
.guardrail > i {
  color: #436b91;
  font-size: 19px;
}
.guardrail b,
.guardrail span {
  display: block;
}
.guardrail b {
  font-size: 11.5px;
}
.guardrail span {
  margin-top: 2px;
  color: var(--wao-text-weak);
  font-size: 10.5px;
  line-height: 1.55;
}
.config-card,
.request-card,
.run-card,
.report-panel,
.history-panel {
  margin-top: 13px;
  border: 1px solid var(--wao-line);
  border-radius: 13px;
  background: var(--wao-bg-card);
}
.config-card,
.request-card,
.run-card,
.report-panel,
.history-panel {
  padding: 18px;
}
.section-head,
.report-head,
.sources-card > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.section-head span,
.report-head > div > span,
.sources-card header span {
  color: #436b91;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 1px;
}
.section-head h2,
.report-head h2 {
  margin: 3px 0 0;
  font-size: 15px;
}
.section-head > em,
.history-panel .section-head > em {
  color: var(--wao-text-weak);
  font-size: 10px;
  font-style: normal;
}
.secondary-button,
.report-actions button,
.failed-detail button {
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
}
.secondary-button:hover:not(:disabled),
.report-actions button:hover:not(:disabled),
.failed-detail button:hover:not(:disabled) {
  background: var(--wao-bg-hover-2);
}
.chat-chip {
  margin-top: 13px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--wao-bg-sub);
  display: flex;
  align-items: center;
  gap: 9px;
}
.chat-chip > i {
  color: #436b91;
  font-size: 20px;
}
.chat-chip b,
.chat-chip small {
  display: block;
}
.chat-chip b {
  font-size: 12px;
}
.chat-chip small {
  margin-top: 2px;
  color: var(--wao-text-weak);
  font-size: 9px;
  overflow-wrap: anywhere;
}
.chat-chip.missing {
  background: var(--wao-warning-bg);
}
.chat-chip.missing > i {
  color: var(--wao-warning-strong);
}
.speaker-editor {
  margin-top: 12px;
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 10px;
  align-items: end;
}
.speaker-editor label,
.date-grid label,
.focus-field {
  display: grid;
  gap: 5px;
}
.speaker-editor label > span,
.date-grid label > span,
.focus-field > span {
  color: var(--wao-text-weak);
  font-size: 10.5px;
}
.speaker-editor select,
.date-grid input,
.focus-field textarea {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
  font-size: 12px;
}
.speaker-editor select,
.date-grid input {
  height: 36px;
  padding: 0 9px;
}
.speaker-editor select:focus,
.date-grid input:focus,
.focus-field textarea:focus {
  border-color: #5e86aa;
  box-shadow: 0 0 0 3px color-mix(in srgb, #436b91 12%, transparent);
}
.config-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}
.selected-speakers {
  margin-top: 11px;
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}
.selected-speakers span {
  padding: 7px 10px;
  border-radius: 999px;
  background: color-mix(in srgb, #436b91 9%, var(--wao-bg-sub));
  color: #436b91;
  font-size: 11px;
  font-weight: 650;
}
.selected-speakers i {
  margin-right: 5px;
}
.config-message {
  margin: 9px 0 0;
  color: var(--wao-text-weak);
  font-size: 10.5px;
}
.automation-link {
  margin-top: 10px;
  color: #436b91;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  text-decoration: none;
}
.state-inline {
  min-height: 70px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.state-inline.error {
  color: var(--wao-danger-strong);
}
.state-inline button,
.detail-state button,
.history-state button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-decoration: underline;
}
.request-head {
  align-items: center;
}
.draft-pill {
  min-height: 28px;
  padding: 0 9px;
  border-radius: 999px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 9.5px;
}
.draft-pill.error {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.draft-pill button {
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.date-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.focus-field {
  margin-top: 11px;
}
.focus-field > span {
  display: flex;
  justify-content: space-between;
}
.focus-field > span b {
  color: var(--wao-text-2);
  font-weight: 600;
}
.focus-field > span em {
  font-style: normal;
}
.focus-field textarea {
  min-height: 92px;
  padding: 9px 10px;
  resize: vertical;
  line-height: 1.6;
}
.focus-field > small {
  color: var(--wao-text-weak);
  font-size: 9.5px;
}
.request-actions {
  margin-top: 13px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.request-actions > span {
  color: var(--wao-text-weak);
  font-size: 10px;
}
.request-actions > span i {
  margin-right: 5px;
}
.request-actions > span.error {
  color: var(--wao-danger-strong);
}
.inline-error {
  margin: 10px 0 0;
  padding: 9px 10px;
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: 10.5px;
  line-height: 1.5;
}
.inline-error i {
  margin-right: 6px;
}
.run-card .section-head > em {
  padding: 5px 8px;
  border-radius: 999px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary);
}
.run-focus,
.report-focus {
  margin: 12px 0 0;
  padding: 9px 10px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  font-size: 10.5px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.run-focus i {
  margin-right: 6px;
  color: #436b91;
}
.stage-list {
  margin: 17px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
}
.stage-list li {
  position: relative;
  min-width: 0;
  display: grid;
  justify-items: center;
  text-align: center;
  gap: 7px;
}
.stage-list li:not(:last-child)::after {
  content: '';
  position: absolute;
  left: calc(50% + 14px);
  right: calc(-50% + 14px);
  top: 12px;
  height: 2px;
  background: var(--wao-line);
}
.stage-list li.done:not(:last-child)::after {
  background: #4d9472;
}
.stage-dot {
  position: relative;
  z-index: 1;
  width: 25px;
  height: 25px;
  border-radius: 50%;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  display: grid;
  place-items: center;
  font-size: 11px;
}
.stage-list li.done .stage-dot {
  background: var(--wao-success-bg);
  color: var(--wao-success);
}
.stage-list li.active .stage-dot {
  background: var(--wao-primary-soft);
  color: var(--wao-primary);
  animation: pulse 1.5s infinite;
}
.stage-list li.failed .stage-dot {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.stage-list b,
.stage-list small {
  display: block;
  overflow-wrap: anywhere;
}
.stage-list b {
  font-size: 10.5px;
}
.stage-list small {
  margin-top: 2px;
  padding: 0 4px;
  color: var(--wao-text-weak);
  font-size: 8.5px;
  line-height: 1.45;
}
.run-progress {
  margin-top: 15px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--wao-bg-sub);
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--wao-text-3);
  font-size: 10.5px;
}
.run-progress i {
  color: var(--wao-primary);
}
.run-progress span {
  flex: 1;
}
.run-progress b {
  color: var(--wao-danger-strong);
  font-size: 10px;
}
.run-progress.failed {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.retry-button {
  margin-top: 10px;
}
.new-result {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid var(--wao-success-line);
  border-radius: 10px;
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 10.5px;
}
.new-result span {
  flex: 1;
}
.new-result button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-weight: 700;
}
.new-result.failed {
  border-color: var(--wao-danger-line);
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.archive-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 13px;
  align-items: start;
}
.report-panel,
.history-panel {
  min-width: 0;
}
.detail-state {
  min-height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wao-text-weak);
  font-size: 12px;
  text-align: center;
}
.detail-state.empty {
  flex-direction: column;
}
.detail-state.empty > i {
  color: #436b91;
  font-size: 28px;
}
.detail-state.empty b {
  color: var(--wao-text-3);
}
.detail-state.error {
  color: var(--wao-danger-strong);
}
.report-head p {
  margin: 4px 0 0;
  color: var(--wao-text-weak);
  font-size: 9.5px;
}
.report-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: wrap;
}
.report-meta {
  margin: 13px 0 0;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 7px;
}
.report-meta > div {
  min-width: 0;
  padding: 9px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
}
.report-meta dt {
  color: var(--wao-text-weak);
  font-size: 8.5px;
}
.report-meta dd {
  margin: 3px 0 0;
  font-size: 10px;
  overflow-wrap: anywhere;
}
.report-focus b {
  display: block;
  margin-bottom: 3px;
  color: #436b91;
  font-size: 9px;
}
.report-sections {
  margin-top: 13px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}
.report-sections article {
  min-width: 0;
  padding: 14px;
  border-radius: 10px;
  background: var(--wao-bg-sub);
  overflow-wrap: anywhere;
}
.report-sections h3 {
  margin: 0 0 8px;
  font-size: 12px;
}
.report-sections h3 i {
  margin-right: 6px;
  color: #436b91;
}
.markdown {
  color: var(--wao-text-2);
  font-size: 10.5px;
  line-height: 1.75;
}
.markdown :deep(p),
.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 0 0 7px;
}
.markdown :deep(ul),
.markdown :deep(ol) {
  padding-left: 18px;
}
.failed-detail,
.invalid-report {
  margin-top: 13px;
  padding: 13px;
  border-radius: 10px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  display: flex;
  align-items: flex-start;
  gap: 9px;
}
.failed-detail > div {
  flex: 1;
}
.failed-detail b {
  font-size: 11px;
}
.failed-detail p {
  margin: 3px 0 0;
  font-size: 10px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.invalid-report {
  font-size: 10.5px;
  line-height: 1.6;
}
.sources-card {
  margin-top: 13px;
  padding-top: 14px;
  border-top: 1px solid var(--wao-divider);
}
.sources-card h3 {
  margin: 3px 0 0;
  font-size: 13px;
}
.sources-card header em {
  color: var(--wao-text-weak);
  font-size: 10px;
  font-style: normal;
}
.source-list {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}
.source-list a {
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  color: inherit;
  text-decoration: none;
  display: flex;
  align-items: center;
  gap: 8px;
}
.source-list a:hover {
  background: var(--wao-bg-hover-2);
}
.source-list a > b {
  color: #436b91;
  font-size: 9px;
}
.source-list a span {
  min-width: 0;
  flex: 1;
}
.source-list strong,
.source-list small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-list strong {
  font-size: 10.5px;
}
.source-list small {
  margin-top: 2px;
  color: var(--wao-text-weak);
  font-size: 8.5px;
}
.history-panel {
  position: sticky;
  top: 12px;
}
.history-state {
  min-height: 130px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--wao-text-weak);
  font-size: 10.5px;
  text-align: center;
}
.history-state.error {
  color: var(--wao-danger-strong);
}
.history-list {
  margin-top: 11px;
  max-height: 680px;
  overflow-y: auto;
  display: grid;
  gap: 6px;
}
.history-list > button {
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-sub);
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.history-list > button:hover,
.history-list > button.active {
  border-color: color-mix(in srgb, #436b91 45%, var(--wao-line));
  background: color-mix(in srgb, #436b91 7%, var(--wao-bg-sub));
}
.history-list > button.failed {
  border-left: 3px solid var(--wao-danger);
}
.history-status {
  color: var(--wao-success);
  font-size: 8.5px;
}
.history-list button.failed .history-status {
  color: var(--wao-danger-strong);
}
.history-status i {
  margin-right: 4px;
}
.history-list b,
.history-list p,
.history-list small {
  display: block;
}
.history-list b {
  margin-top: 4px;
  font-size: 10.5px;
}
.history-list p {
  margin: 4px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--wao-text-3);
  font-size: 9.5px;
}
.history-list small {
  color: var(--wao-text-weak);
  font-size: 8.5px;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes pulse {
  50% {
    opacity: 0.4;
    transform: scale(0.88);
  }
}

@media (max-width: 900px) {
  .archive-layout {
    grid-template-columns: 1fr;
  }
  .history-panel {
    position: static;
  }
  .history-list {
    max-height: none;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .speaker-editor {
    grid-template-columns: 1fr 1fr;
  }
  .config-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }
}
@media (max-width: 760px) {
  .chat-review-page {
    padding: 16px 13px 44px;
  }
  .hero {
    align-items: flex-start;
  }
  .hero h1 {
    font-size: 23px;
  }
  .hero p {
    max-width: 290px;
  }
  .copy-button {
    width: 36px;
    min-height: 36px;
    padding: 0;
  }
  .copy-button span {
    display: none;
  }
  .config-card,
  .request-card,
  .run-card,
  .report-panel,
  .history-panel {
    padding: 14px;
  }
  .section-head,
  .report-head {
    align-items: flex-start;
  }
  .secondary-button {
    flex: none;
  }
  .speaker-editor,
  .date-grid,
  .report-sections,
  .source-list,
  .history-list,
  .report-meta {
    grid-template-columns: 1fr;
  }
  .config-actions {
    grid-column: auto;
    justify-content: stretch;
  }
  .config-actions > button {
    flex: 1;
  }
  .request-head {
    display: grid;
  }
  .draft-pill {
    justify-self: start;
  }
  .request-actions {
    align-items: stretch;
    flex-direction: column;
  }
  .request-actions .primary-button {
    width: 100%;
    min-height: 42px;
  }
  .stage-list {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .stage-list li {
    min-height: 51px;
    padding-left: 38px;
    justify-items: start;
    align-content: start;
    text-align: left;
    gap: 0;
  }
  .stage-list li:not(:last-child)::after {
    left: 12px;
    right: auto;
    top: 25px;
    bottom: 0;
    width: 2px;
    height: auto;
  }
  .stage-dot {
    position: absolute;
    left: 0;
    top: 0;
  }
  .stage-list small {
    padding: 0;
  }
  .run-progress {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .run-progress b {
    width: 100%;
    padding-left: 21px;
  }
  .report-head {
    display: grid;
  }
  .report-actions {
    justify-content: stretch;
  }
  .report-actions button {
    flex: 1;
    justify-content: center;
  }
  .failed-detail {
    flex-wrap: wrap;
  }
  .failed-detail button {
    margin-left: 25px;
  }
  .new-result {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .new-result button {
    width: 100%;
    text-align: right;
  }
}
</style>
