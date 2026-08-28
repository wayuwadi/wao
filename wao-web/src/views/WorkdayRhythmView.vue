<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchWorkdayRhythmHistory,
  fetchWorkdayRhythm,
  fetchWorkdayRhythmDay,
  saveWorkdayRhythm,
  saveWorkdayRhythmDay,
  sendEnergyNotification,
  sendFocusNotification,
  saveRhythmFocus,
  setRhythmWorkdayStatus,
  type RhythmDashboard,
  type WorkdayRhythmEnergyAction,
  type WorkdayRhythmHistory,
  type WorkdayRhythmHistoryDay,
} from '@/api/workdayRhythm'
import {
  fetchFeishuChats,
  fetchWebhookEndpoints,
  type FeishuChat,
  type FeishuWebhookEndpoint,
} from '@/api/system'
import { toast } from '@/composables/toast'
import { useFavoritesStore } from '@/stores/favorites'
import {
  MAX_FOCUS_COUNT,
  DEFAULT_WORKDAY_RHYTHM,
  blockProgress,
  cloneRhythmConfig,
  currentBlockIndex,
  defaultRhythmDay,
  minutesUntilEnd,
  parseRhythmConfigDraft,
  parseRhythmDay,
  rhythmConfigError,
  shanghaiClock,
  type WorkdayRhythmConfig,
  type WorkdayRhythmDay,
  type WorkdayRhythmEnergyItem,
} from '@/utils/workdayRhythm'
import WorkdayRhythmDailyPanel from '@/components/workday/WorkdayRhythmDailyPanel.vue'
import WorkdayRhythmDailySettings from '@/components/workday/WorkdayRhythmDailySettings.vue'
import WorkdayRhythmHexagramSummary from '@/components/workday/WorkdayRhythmHexagramSummary.vue'

const CONFIG_DRAFT_KEY = 'wao-workday-rhythm-config-draft-v1'
const RHYTHM_FAVORITE_ID = 'workday-rhythm'
const favorites = useFavoritesStore()
const route = useRoute()
const clock = ref(shanghaiClock())
const config = ref<WorkdayRhythmConfig>(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
const day = ref<WorkdayRhythmDay>(defaultRhythmDay(clock.value.date, clock.value.weekday))
const tomorrowDate = computed(() => shiftDate(clock.value.date, 1))
const tomorrowWeekday = computed(() => weekdayOf(tomorrowDate.value))
const tomorrowDay = ref<WorkdayRhythmDay>(
  defaultRhythmDay(tomorrowDate.value, tomorrowWeekday.value),
)
const loading = ref(true)
const loadError = ref('')
const dayState = ref<'idle' | 'dirty' | 'saving' | 'saved' | 'error'>('idle')
const tomorrowState = ref<'loading' | 'idle' | 'dirty' | 'saving' | 'saved' | 'error'>('loading')
const tomorrowError = ref('')
const editorOpen = ref(false)
const editorBusy = ref(false)
const editorError = ref('')
const editorDraft = ref<WorkdayRhythmConfig>(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
const notificationChats = ref<FeishuChat[]>([])
const notificationChatsLoading = ref(false)
const notificationChatsError = ref('')
const notificationWebhooks = ref<FeishuWebhookEndpoint[]>([])
const notificationWebhooksLoading = ref(false)
const notificationWebhooksError = ref('')
const energyNotificationState = ref<'idle' | 'sending' | 'sent' | 'error'>('idle')
const focusNotificationState = ref<'idle' | 'sending' | 'sent' | 'error'>('idle')
const pendingFocusNotification = ref<string | null>(null)
const pendingEnergyNotification = ref<{ action: WorkdayRhythmEnergyAction; date: string } | null>(
  null,
)
const historyOpen = ref(false)
const historyMonth = ref(clock.value.date.slice(0, 7))
const historyLoading = ref(false)
const historyError = ref('')
const history = ref<WorkdayRhythmHistory | null>(null)
const dailyPanelKey = ref(0)
const tomorrowOpen = ref(false)
const tianrenEnabled = ref(true)
let clockTimer: ReturnType<typeof setInterval> | null = null

const dayDraftKey = computed(() => `wao-workday-rhythm-day-draft-${clock.value.date}`)
const tomorrowDraftKey = computed(() => `wao-workday-rhythm-day-draft-${tomorrowDate.value}`)
const blockIndex = computed(() => currentBlockIndex(config.value.blocks, clock.value))
const currentBlock = computed(() => config.value.blocks[blockIndex.value] ?? null)
const nextBlock = computed(() => {
  if (blockIndex.value < 0) return config.value.blocks[0] ?? null
  return config.value.blocks[(blockIndex.value + 1) % config.value.blocks.length] ?? null
})
const currentProgress = computed(() =>
  currentBlock.value ? blockProgress(currentBlock.value, clock.value) : 0,
)
const remainingCopy = computed(() => {
  if (!currentBlock.value) return '等待下一个时间块'
  const remaining = minutesUntilEnd(currentBlock.value, clock.value)
  if (remaining >= 60) return `还剩 ${Math.floor(remaining / 60)} 小时 ${remaining % 60} 分钟`
  return `还剩 ${remaining} 分钟`
})
const weekdayLabel = computed(
  () => ['一', '二', '三', '四', '五', '六', '日'][clock.value.weekday - 1],
)
const energyActionBusy = computed(
  () =>
    dayState.value === 'saving' ||
    energyNotificationState.value === 'sending' ||
    energyNotificationState.value === 'error',
)
const focusReady = computed(() => day.value.focus.filter((item) => !!item.trim()).length >= 3)
const activeFocusIndex = computed(() => {
  if (!focusReady.value) return -1
  return day.value.focus.findIndex(
    (item, index) => !!item.trim() && day.value.focusCompleted[index] !== true,
  )
})
const configuredChatMissing = computed(
  () =>
    !!editorDraft.value.energyNotification.chatId &&
    !notificationChats.value.some(
      (chat) => chat.chatId === editorDraft.value.energyNotification.chatId,
    ),
)
const configuredWebhookMissing = computed(
  () =>
    !!editorDraft.value.energyNotification.webhookId &&
    !notificationWebhooks.value.some(
      (endpoint) =>
        endpoint.id === editorDraft.value.energyNotification.webhookId && endpoint.enabled,
    ),
)
const rhythmFavorite = computed(() => favorites.isFav('module', RHYTHM_FAVORITE_ID))
let energyItemSequence = 0

function toggleRhythmFavorite() {
  void favorites.toggle('module', RHYTHM_FAVORITE_ID, '今日节奏')
}

function handleDailyPanelLoaded(dashboard: RhythmDashboard) {
  day.value.workdayActive = dashboard.workday
  tianrenEnabled.value = dashboard.settings.tianrenEnabled
}

function shiftDate(value: string, days: number) {
  const [year, month, date] = value.split('-').map(Number)
  const shifted = new Date(Date.UTC(year!, month! - 1, date! + days))
  return shifted.toISOString().slice(0, 10)
}

function weekdayOf(value: string) {
  const [year, month, date] = value.split('-').map(Number)
  const weekday = new Date(Date.UTC(year!, month! - 1, date!)).getUTCDay()
  return weekday === 0 ? 7 : weekday
}

function loadLocalDayDraft(validIds: string[], validEnergyIds: string[]): WorkdayRhythmDay | null {
  const raw = localStorage.getItem(dayDraftKey.value)
  if (!raw) return null
  const parsed = parseRhythmDay(
    raw,
    clock.value.date,
    clock.value.weekday,
    validIds,
    validEnergyIds,
  )
  return parsed.date === clock.value.date ? parsed : null
}

function loadLocalTomorrowDraft(
  validIds: string[],
  validEnergyIds: string[],
): WorkdayRhythmDay | null {
  const raw = localStorage.getItem(tomorrowDraftKey.value)
  if (!raw) return null
  const parsed = parseRhythmDay(
    raw,
    tomorrowDate.value,
    tomorrowWeekday.value,
    validIds,
    validEnergyIds,
  )
  return parsed.date === tomorrowDate.value ? parsed : null
}

async function loadTomorrow(validIds: string[], validEnergyIds: string[]) {
  tomorrowState.value = 'loading'
  tomorrowError.value = ''
  try {
    const loaded = await fetchWorkdayRhythmDay(
      tomorrowDate.value,
      tomorrowWeekday.value,
      validIds,
      validEnergyIds,
    )
    tomorrowDay.value = loadLocalTomorrowDraft(validIds, validEnergyIds) ?? loaded
    tomorrowState.value = localStorage.getItem(tomorrowDraftKey.value) ? 'error' : 'idle'
  } catch (error) {
    tomorrowState.value = 'error'
    tomorrowError.value = error instanceof Error ? error.message : '明日计划暂时无法读取'
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const loadedConfig = await fetchWorkdayRhythm()
    const validIds = loadedConfig.blocks.map((block) => block.id)
    const validEnergyIds = loadedConfig.energyItems.map((item) => item.id)
    const loadedDay = await fetchWorkdayRhythmDay(
      clock.value.date,
      clock.value.weekday,
      validIds,
      validEnergyIds,
    )
    config.value = loadedConfig
    day.value = loadLocalDayDraft(validIds, validEnergyIds) ?? loadedDay
    dayState.value = localStorage.getItem(dayDraftKey.value) ? 'error' : 'idle'
    await loadTomorrow(validIds, validEnergyIds)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '今日节奏暂时无法读取'
  } finally {
    loading.value = false
    openPetSettingsIfRequested()
  }
}

function markDayDirty() {
  if (dayState.value !== 'saving') dayState.value = 'dirty'
  localStorage.setItem(dayDraftKey.value, JSON.stringify(day.value))
}

async function persistDay(successMessage = ''): Promise<boolean> {
  if (dayState.value === 'saving') return false
  dayState.value = 'saving'
  day.value.energySnapshot = {
    ...day.value.energySnapshot,
    ...Object.fromEntries(
      config.value.energyItems.map((item) => [item.id, { title: item.title, mode: item.mode }]),
    ),
  }
  localStorage.setItem(dayDraftKey.value, JSON.stringify(day.value))
  try {
    await saveWorkdayRhythmDay(day.value)
    localStorage.removeItem(dayDraftKey.value)
    dayState.value = 'saved'
    if (successMessage) toast(successMessage, 'ri-check-line')
    return true
  } catch (error) {
    dayState.value = 'error'
    toast(
      error instanceof Error ? `${error.message}，草稿仍在本机` : '保存失败，草稿仍在本机',
      'ri-error-warning-line',
    )
    return false
  }
}

async function dispatchEnergyNotification(action: WorkdayRhythmEnergyAction, date: string) {
  const notification = config.value.energyNotification
  const selected = config.value.energyItems.some((item) => item.id === action && item.notify)
  if (!notification.enabled || !selected) return
  pendingEnergyNotification.value = { action, date }
  energyNotificationState.value = 'sending'
  try {
    const result = await sendEnergyNotification(action, date)
    if (!result.sent) {
      energyNotificationState.value = 'idle'
      pendingEnergyNotification.value = null
      return
    }
    energyNotificationState.value = 'sent'
    pendingEnergyNotification.value = null
    toast(`已通知「${result.chatName}」`, 'ri-send-plane-fill')
  } catch (error) {
    energyNotificationState.value = 'error'
    toast(
      error instanceof Error
        ? `记录已保存，但飞书通知失败：${error.message}`
        : '记录已保存，但飞书通知失败',
      'ri-error-warning-line',
    )
  }
}

async function retryEnergyNotification() {
  if (!pendingEnergyNotification.value) return
  await dispatchEnergyNotification(
    pendingEnergyNotification.value.action,
    pendingEnergyNotification.value.date,
  )
}

function energyCount(item: WorkdayRhythmEnergyItem): number {
  return day.value.energyRecords[item.id] ?? 0
}

function syncLegacyEnergyFields() {
  day.value.waterCount = day.value.energyRecords.WATER ?? 0
  day.value.meditated = (day.value.energyRecords.MEDITATION ?? 0) > 0
}

async function recordEnergy(item: WorkdayRhythmEnergyItem) {
  const current = energyCount(item)
  if (energyActionBusy.value || current >= 99 || (item.mode === 'DAILY_ONCE' && current > 0)) return
  day.value.energyRecords = {
    ...day.value.energyRecords,
    [item.id]: item.mode === 'DAILY_ONCE' ? 1 : current + 1,
  }
  syncLegacyEnergyFields()
  markDayDirty()
  if (await persistDay(`${item.title}已记录`)) {
    await dispatchEnergyNotification(item.id, day.value.date)
  }
}

async function toggleWorkday() {
  if (dayState.value === 'saving') return
  const next = !day.value.workdayActive
  try {
    const dashboard = await setRhythmWorkdayStatus(clock.value.date, next)
    day.value.workdayActive = dashboard.workday
    dailyPanelKey.value += 1
  } catch (error) {
    toast(
      error instanceof Error ? error.message : '今天的工作状态保存失败',
      'ri-error-warning-line',
    )
    return
  }
  markDayDirty()
  void persistDay(day.value.workdayActive ? '今天已按工作日节奏启用' : '今天已切换为休息日')
}

function focusInput(index: number) {
  day.value.focus = day.value.focus.map((item) => item.slice(0, 100))
  day.value.focusCompleted = day.value.focusCompleted.map(
    (completed, completedIndex) => completedIndex < index && completed,
  )
  focusNotificationState.value = 'idle'
  pendingFocusNotification.value = null
  markDayDirty()
}

function addFocus() {
  if (day.value.focus.length >= MAX_FOCUS_COUNT) return
  day.value.focus.push('')
  day.value.focusCompleted.push(false)
  markDayDirty()
}

function removeFocus(index: number) {
  if (day.value.focus.length <= 3) return
  day.value.focus.splice(index, 1)
  day.value.focusCompleted.splice(index, 1)
  day.value.focusCompleted = day.value.focusCompleted.map(
    (completed, completedIndex) => completedIndex < index && completed,
  )
  focusNotificationState.value = 'idle'
  pendingFocusNotification.value = null
  markDayDirty()
}

function tomorrowFocusInput() {
  tomorrowDay.value.focus = tomorrowDay.value.focus.map((item) => item.slice(0, 100))
  if (tomorrowState.value !== 'saving') tomorrowState.value = 'dirty'
  localStorage.setItem(tomorrowDraftKey.value, JSON.stringify(tomorrowDay.value))
}

function addTomorrowFocus() {
  if (tomorrowDay.value.focus.length >= MAX_FOCUS_COUNT) return
  tomorrowDay.value.focus.push('')
  tomorrowDay.value.focusCompleted.push(false)
  tomorrowFocusInput()
}

function removeTomorrowFocus(index: number) {
  if (tomorrowDay.value.focus.length <= 3) return
  tomorrowDay.value.focus.splice(index, 1)
  tomorrowDay.value.focusCompleted.splice(index, 1)
  tomorrowFocusInput()
}

async function saveFocus() {
  day.value.focus = day.value.focus.map((item) => item.trim())
  markDayDirty()
  if (!(await persistDay('今日重点已保存'))) return
  try {
    await saveRhythmFocus(day.value.date, day.value.focus)
    dailyPanelKey.value += 1
    if (focusReady.value) await dispatchFocusNotification(day.value.date)
  } catch (error) {
    toast(
      error instanceof Error ? error.message : '今日计划联动失败，请重试',
      'ri-error-warning-line',
    )
  }
}

async function dispatchFocusNotification(date: string) {
  const notification = config.value.energyNotification
  if (!notification.enabled || !notification.notifyFocus) return
  pendingFocusNotification.value = date
  focusNotificationState.value = 'sending'
  try {
    const result = await sendFocusNotification(date)
    if (!result.sent) {
      focusNotificationState.value = 'idle'
      pendingFocusNotification.value = null
      return
    }
    focusNotificationState.value = 'sent'
    pendingFocusNotification.value = null
    toast(`今日重点已发到「${result.chatName}」`, 'ri-send-plane-fill')
  } catch (error) {
    focusNotificationState.value = 'error'
    toast(
      error instanceof Error
        ? `重点已保存，但飞书通知失败：${error.message}`
        : '重点已保存，但飞书通知失败',
      'ri-error-warning-line',
    )
  }
}

async function retryFocusNotification() {
  if (pendingFocusNotification.value)
    await dispatchFocusNotification(pendingFocusNotification.value)
}

async function completeFocus(index: number) {
  if (index !== activeFocusIndex.value || dayState.value === 'saving') return
  day.value.focusCompleted[index] = true
  markDayDirty()
  const nextIndex = day.value.focus.findIndex(
    (item, itemIndex) => itemIndex > index && !!item.trim() && !day.value.focusCompleted[itemIndex],
  )
  const hasNext = nextIndex >= 0
  await persistDay(
    hasNext ? `重点 ${index + 1} 已完成，现在只推进重点 ${nextIndex + 1}` : '今日重点已全部完成',
  )
}

async function saveTomorrowFocus() {
  if (tomorrowState.value === 'saving' || tomorrowState.value === 'loading') return
  tomorrowDay.value.focus = tomorrowDay.value.focus.map((item) => item.trim())
  tomorrowState.value = 'saving'
  localStorage.setItem(tomorrowDraftKey.value, JSON.stringify(tomorrowDay.value))
  try {
    await saveRhythmFocus(tomorrowDate.value, tomorrowDay.value.focus)
    localStorage.removeItem(tomorrowDraftKey.value)
    tomorrowState.value = 'saved'
    tomorrowError.value = ''
    toast('明日计划已保存，明天会直接成为今日重点', 'ri-check-line')
  } catch (error) {
    tomorrowState.value = 'error'
    tomorrowError.value = error instanceof Error ? error.message : '明日计划保存失败'
    toast(`${tomorrowError.value}，草稿仍在本机`, 'ri-error-warning-line')
  }
}

function openEditor() {
  const savedDraft = parseRhythmConfigDraft(localStorage.getItem(CONFIG_DRAFT_KEY) ?? '')
  editorDraft.value = savedDraft ?? cloneRhythmConfig(config.value)
  editorError.value = ''
  editorOpen.value = true
  if (editorDraft.value.energyNotification.enabled) loadNotificationTargets()
}

function openPetSettingsIfRequested() {
  if (route.query.settings !== 'pet' || loading.value || loadError.value || editorOpen.value) return
  openEditor()
  void nextTick(() => document.querySelector('.energy-items-editor')?.scrollIntoView?.())
}

function monthRange(month: string) {
  const match = /^(\d{4})-(\d{2})$/.exec(month)
  if (!match) return null
  const year = Number(match[1])
  const monthNumber = Number(match[2])
  if (year < 2000 || year > 2100 || monthNumber < 1 || monthNumber > 12) return null
  const lastDay = new Date(Date.UTC(year, monthNumber, 0)).getUTCDate()
  return {
    from: `${match[1]}-${match[2]}-01`,
    to: `${match[1]}-${match[2]}-${String(lastDay).padStart(2, '0')}`,
  }
}

async function loadHistory() {
  const range = monthRange(historyMonth.value)
  if (!range || historyLoading.value) return
  historyLoading.value = true
  historyError.value = ''
  history.value = null
  try {
    history.value = await fetchWorkdayRhythmHistory(range.from, range.to)
  } catch (error) {
    historyError.value = error instanceof Error ? error.message : '历史记录读取失败，请稍后重试'
  } finally {
    historyLoading.value = false
  }
}

function openHistory() {
  historyMonth.value = clock.value.date.slice(0, 7)
  historyOpen.value = true
  void loadHistory()
}

function historyDateLabel(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return value
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])))
  const weekday = ['日', '一', '二', '三', '四', '五', '六'][date.getUTCDay()]
  return `${Number(match[2])} 月 ${Number(match[3])} 日 · 星期${weekday}`
}

function energyResult(item: WorkdayRhythmHistoryDay['energyItems'][number]) {
  return item.mode === 'DAILY_ONCE' ? (item.count > 0 ? '已完成' : '未执行') : `${item.count} 次`
}

async function loadNotificationChats() {
  if (notificationChatsLoading.value) return
  notificationChatsLoading.value = true
  notificationChatsError.value = ''
  try {
    notificationChats.value = await fetchFeishuChats()
    if (!notificationChats.value.length)
      notificationChatsError.value = '机器人当前不在任何可选群聊中'
  } catch (error) {
    notificationChatsError.value =
      error instanceof Error ? error.message : '可用飞书群读取失败，请稍后重试'
  } finally {
    notificationChatsLoading.value = false
  }
}

async function loadNotificationWebhooks() {
  if (notificationWebhooksLoading.value) return
  notificationWebhooksLoading.value = true
  notificationWebhooksError.value = ''
  try {
    notificationWebhooks.value = await fetchWebhookEndpoints()
    if (!notificationWebhooks.value.some((endpoint) => endpoint.enabled))
      notificationWebhooksError.value = '系统设置中没有已启用的 Webhook 配置'
  } catch (error) {
    notificationWebhooksError.value =
      error instanceof Error ? error.message : 'Webhook 配置读取失败，请稍后重试'
  } finally {
    notificationWebhooksLoading.value = false
  }
}

function loadNotificationTargets() {
  if (editorDraft.value.energyNotification.channel === 'WEBHOOK') void loadNotificationWebhooks()
  else void loadNotificationChats()
}

function selectNotificationChat() {
  const selected = notificationChats.value.find(
    (chat) => chat.chatId === editorDraft.value.energyNotification.chatId,
  )
  editorDraft.value.energyNotification.chatName = selected?.name ?? ''
}

function selectNotificationWebhook() {
  const selected = notificationWebhooks.value.find(
    (endpoint) => endpoint.id === editorDraft.value.energyNotification.webhookId,
  )
  editorDraft.value.energyNotification.webhookName = selected?.name ?? ''
}

function resetEditor() {
  if (
    !window.confirm(
      '恢复任务中给出的原始工作日模板？当前编辑草稿会被替换，保存前不会影响线上配置。',
    )
  )
    return
  editorDraft.value = {
    ...cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM),
    energyNotification: { ...editorDraft.value.energyNotification },
  }
  editorError.value = ''
}

function addEnergyItem() {
  if (editorDraft.value.energyItems.length >= 8) return
  energyItemSequence += 1
  editorDraft.value.energyItems.push({
    id: `energy-${Date.now().toString(36)}-${energyItemSequence}`,
    title: '',
    guidance: '',
    mode: 'COUNT',
    notify: true,
  })
}

function removeEnergyItem(index: number) {
  if (editorDraft.value.energyItems.length <= 1) return
  editorDraft.value.energyItems.splice(index, 1)
}

async function submitEditor() {
  const error = rhythmConfigError(editorDraft.value)
  if (error) {
    editorError.value = error
    return
  }
  editorBusy.value = true
  editorError.value = ''
  try {
    config.value = await saveWorkdayRhythm(editorDraft.value)
    day.value.completed = day.value.completed.filter((id) =>
      config.value.blocks.some((block) => block.id === id),
    )
    day.value.energyRecords = {
      ...day.value.energyRecords,
      ...Object.fromEntries(
        config.value.energyItems.map((item) => [item.id, day.value.energyRecords[item.id] ?? 0]),
      ),
    }
    syncLegacyEnergyFields()
    localStorage.removeItem(CONFIG_DRAFT_KEY)
    const pendingAction = pendingEnergyNotification.value?.action
    if (
      !config.value.energyNotification.enabled ||
      !config.value.energyItems.some((item) => item.id === pendingAction && item.notify)
    ) {
      pendingEnergyNotification.value = null
      energyNotificationState.value = 'idle'
    }
    if (!config.value.energyNotification.enabled || !config.value.energyNotification.notifyFocus) {
      pendingFocusNotification.value = null
      focusNotificationState.value = 'idle'
    }
    editorOpen.value = false
    toast('工作日节奏已保存并跨设备生效', 'ri-check-line')
  } catch (error) {
    editorError.value =
      error instanceof Error ? `${error.message}，草稿仍在本机` : '模板保存失败，草稿仍在本机'
  } finally {
    editorBusy.value = false
  }
}

watch(() => route.query.settings, openPetSettingsIfRequested)

watch(
  editorDraft,
  (value) => {
    if (editorOpen.value) localStorage.setItem(CONFIG_DRAFT_KEY, JSON.stringify(value))
  },
  { deep: true },
)

watch(
  () => editorDraft.value.energyNotification.enabled,
  (enabled) => {
    if (enabled && editorOpen.value) loadNotificationTargets()
  },
)

watch(
  () => editorDraft.value.energyNotification.channel,
  () => {
    if (editorDraft.value.energyNotification.enabled && editorOpen.value) loadNotificationTargets()
  },
)

watch(
  () => clock.value.date,
  (date, previous) => {
    if (date !== previous) {
      pendingEnergyNotification.value = null
      energyNotificationState.value = 'idle'
      pendingFocusNotification.value = null
      focusNotificationState.value = 'idle'
      void load()
    }
  },
)

onMounted(() => {
  void favorites.load()
  void load()
  clockTimer = setInterval(() => {
    clock.value = shanghaiClock()
  }, 30_000)
})
onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <main class="rhythm-page">
    <header class="page-head">
      <div>
        <div class="eyebrow">WORKDAY RHYTHM · 上海时间</div>
        <div class="title-line">
          <h1>今日节奏</h1>
          <p>守住精力边界，把每一段时间用在它真正该做的事上。</p>
        </div>
      </div>
      <div class="head-actions">
        <button
          class="page-action"
          :class="{ active: rhythmFavorite }"
          type="button"
          @click="toggleRhythmFavorite"
        >
          <i :class="rhythmFavorite ? 'ri-star-fill' : 'ri-star-line'"></i
          >{{ rhythmFavorite ? '已收藏' : '收藏' }}
        </button>
        <button class="history-button" type="button" @click="openHistory">
          <i class="ri-history-line"></i>历史记录
        </button>
        <button class="settings-button" type="button" @click="openEditor">
          <i class="ri-settings-4-line"></i>调整节奏
        </button>
      </div>
    </header>

    <div v-if="loading" class="state-card">
      <i class="ri-loader-4-line spin"></i>正在读取你的工作日节奏…
    </div>
    <div v-else-if="loadError" class="state-card error" role="alert">
      <i class="ri-error-warning-line"></i><b>今日节奏暂时没读出来</b><span>{{ loadError }}</span>
      <button type="button" @click="load">重新加载</button>
    </div>

    <template v-else>
      <section
        class="hero"
        :class="{ resting: !day.workdayActive, 'without-hexagram': !tianrenEnabled }"
      >
        <div class="hero-copy">
          <div class="date-line">
            <span>{{ clock.date }}</span
            ><span>星期{{ weekdayLabel }}</span
            ><button type="button" @click="toggleWorkday">
              {{ day.workdayActive ? '今天休息' : '按工作日执行' }}
            </button>
          </div>
          <template v-if="day.workdayActive && currentBlock">
            <small>此刻该做</small>
            <h2>{{ currentBlock.title }}</h2>
            <p>{{ currentBlock.guidance }}</p>
            <div class="time-meta">
              <b>{{ currentBlock.start }}—{{ currentBlock.end }}</b
              ><span>{{ remainingCopy }}</span>
            </div>
            <div
              class="time-progress"
              role="progressbar"
              :aria-valuenow="currentProgress"
              aria-valuemin="0"
              aria-valuemax="100"
            >
              <span :style="{ width: `${currentProgress}%` }"></span>
            </div>
            <div v-if="nextBlock" class="next-up">
              <i class="ri-skip-right-line"></i>接下来 {{ nextBlock.start }} · {{ nextBlock.title }}
            </div>
          </template>
          <template v-else>
            <small>恢复模式</small>
            <h2>今天不追赶进度</h2>
            <p>休息日不会自动记为失败。需要加班或调休时，再手动启用今天的工作日节奏。</p>
          </template>
        </div>
        <div class="hero-flow" aria-label="完整的一天指导列表">
          <div class="hero-flow-head"><small>完整的一天</small><span>仅作当下指导</span></div>
          <ol>
            <li
              v-for="(block, index) in config.blocks"
              :key="block.id"
              :class="{ current: day.workdayActive && index === blockIndex }"
            >
              <time>{{ block.start }}</time
              ><i></i><span>{{ block.title }}</span>
            </li>
          </ol>
        </div>
        <WorkdayRhythmHexagramSummary v-if="tianrenEnabled" :date="clock.date" />
      </section>

      <div v-if="dayState === 'error'" class="save-banner error" role="alert">
        <i class="ri-cloud-off-line"></i><span>上次保存失败，未提交内容仍在本机。</span
        ><button type="button" @click="persistDay('今日记录已重新保存')">重试保存</button>
      </div>
      <div v-else-if="dayState === 'saving'" class="save-banner">
        <i class="ri-loader-4-line spin"></i><span>保存中…</span>
      </div>
      <div v-else-if="dayState === 'saved'" class="save-banner saved">
        <i class="ri-cloud-line"></i><span>已保存到你的 WAO 账号</span>
      </div>

      <section class="overview-grid">
        <WorkdayRhythmDailyPanel
          :date="clock.date"
          :reload-key="dailyPanelKey"
          @loaded="handleDailyPanelLoaded"
        />

        <section class="dashboard-grid">
          <div class="focus-stack">
            <article class="focus-card">
              <header>
                <div>
                  <small>今日执行</small>
                  <h2>今日重点</h2>
                </div>
                <button
                  class="tomorrow-toggle"
                  type="button"
                  :aria-label="tomorrowOpen ? '收起明日计划' : '展开明日计划'"
                  :title="tomorrowOpen ? '收起明日计划' : '展开明日计划'"
                  @click="tomorrowOpen = !tomorrowOpen"
                >
                  <i :class="tomorrowOpen ? 'ri-calendar-close-line' : 'ri-calendar-todo-line'"></i>
                </button>
              </header>
              <div
                v-for="(_, index) in day.focus"
                :key="index"
                class="focus-row"
                :class="{ active: index === activeFocusIndex, done: day.focusCompleted[index] }"
              >
                <label
                  ><span>{{ index + 1 }}</span
                  ><input
                    v-model="day.focus[index]"
                    :disabled="dayState === 'saving'"
                    maxlength="100"
                    :placeholder="index === 0 ? '今天最重要、最难的一件事' : '另一个可观察的结果'"
                    @input="focusInput(index)"
                /></label>
                <button
                  v-if="focusReady && index === activeFocusIndex"
                  class="focus-complete"
                  type="button"
                  :disabled="dayState === 'saving'"
                  @click="completeFocus(index)"
                >
                  <i class="ri-check-line"></i
                  >{{
                    day.focus.some(
                      (item, itemIndex) =>
                        itemIndex > index && !!item.trim() && !day.focusCompleted[itemIndex],
                    )
                      ? '完成并开始下一项'
                      : '完成今日重点'
                  }}
                </button>
                <small v-else-if="day.focusCompleted[index]" class="focus-done"
                  ><i class="ri-check-line"></i>已完成</small
                >
                <small v-else-if="focusReady">等待当前项完成</small>
                <button
                  v-if="day.focus.length > 3"
                  class="focus-remove"
                  type="button"
                  :aria-label="`删除重点 ${index + 1}`"
                  :disabled="dayState === 'saving'"
                  @click="removeFocus(index)"
                >
                  <i class="ri-delete-bin-line"></i>
                </button>
              </div>
              <button
                class="focus-add"
                type="button"
                aria-label="添加一个今日重点"
                title="添加一个今日重点"
                :disabled="day.focus.length >= MAX_FOCUS_COUNT || dayState === 'saving'"
                @click="addFocus"
              >
                <i class="ri-add-line"></i>
              </button>
              <div
                v-if="focusNotificationState !== 'idle'"
                class="focus-notice"
                :class="focusNotificationState"
              >
                <span v-if="focusNotificationState === 'sending'"
                  ><i class="ri-loader-4-line spin"></i>重点已保存，正在发送群通知…</span
                >
                <span v-else-if="focusNotificationState === 'sent'"
                  ><i class="ri-send-plane-fill"></i>今日承诺已发到群里</span
                >
                <template v-else
                  ><span><i class="ri-cloud-off-line"></i>重点已保存，群通知未发出</span
                  ><button type="button" @click="retryFocusNotification">重试通知</button></template
                >
              </div>
              <footer>
                <small v-if="dayState === 'dirty'"><i class="ri-draft-line"></i>待保存</small>
                <span></span
                ><button type="button" :disabled="dayState === 'saving'" @click="saveFocus">
                  {{ dayState === 'error' ? '重试保存' : '保存今日重点' }}
                </button>
              </footer>
            </article>

            <article v-if="tomorrowOpen" class="focus-card tomorrow-focus">
              <header>
                <div>
                  <small>{{ tomorrowDate }} · 提前准备</small>
                  <h2>明日重点</h2>
                </div>
              </header>
              <div v-if="tomorrowState === 'loading'" class="tomorrow-state">
                <i class="ri-loader-4-line spin"></i>正在读取明日计划…
              </div>
              <div
                v-else-if="tomorrowState === 'error' && !tomorrowDay.focus.some(Boolean)"
                class="tomorrow-state error"
                role="alert"
              >
                <span>{{ tomorrowError || '上次保存失败，草稿仍在本机' }}</span>
                <button
                  type="button"
                  @click="
                    loadTomorrow(
                      config.blocks.map((item) => item.id),
                      config.energyItems.map((item) => item.id),
                    )
                  "
                >
                  重新读取
                </button>
              </div>
              <template v-else>
                <div v-for="(_, index) in tomorrowDay.focus" :key="index" class="tomorrow-row">
                  <label>
                    <span>{{ index + 1 }}</span>
                    <input
                      v-model="tomorrowDay.focus[index]"
                      :disabled="tomorrowState === 'saving'"
                      maxlength="100"
                      :placeholder="
                        index === 0 ? '明天最重要、最难的一件事' : '另一件值得提前准备的事'
                      "
                      @input="tomorrowFocusInput"
                    />
                  </label>
                  <button
                    v-if="tomorrowDay.focus.length > 3"
                    class="focus-remove"
                    type="button"
                    :aria-label="`删除明日重点 ${index + 1}`"
                    :disabled="tomorrowState === 'saving'"
                    @click="removeTomorrowFocus(index)"
                  >
                    <i class="ri-delete-bin-line"></i>
                  </button>
                </div>
                <button
                  class="focus-add"
                  type="button"
                  aria-label="添加一个明日重点"
                  title="添加一个明日重点"
                  :disabled="
                    tomorrowDay.focus.length >= MAX_FOCUS_COUNT || tomorrowState === 'saving'
                  "
                  @click="addTomorrowFocus"
                >
                  <i class="ri-add-line"></i>
                </button>
                <footer>
                  <small v-if="tomorrowState === 'dirty'"
                    ><i class="ri-draft-line"></i>待保存</small
                  >
                  <small v-else-if="tomorrowState === 'error'" class="focus-error"
                    >{{ tomorrowError }}，草稿仍在本机</small
                  >
                  <small v-else-if="tomorrowState === 'saved'" class="focus-saved"
                    >已保存，明天自动接续</small
                  >
                  <span></span>
                  <button
                    type="button"
                    :disabled="tomorrowState === 'saving'"
                    @click="saveTomorrowFocus"
                  >
                    {{
                      tomorrowState === 'error'
                        ? '重试保存'
                        : tomorrowState === 'saving'
                          ? '保存中…'
                          : '保存明日计划'
                    }}
                  </button>
                </footer>
              </template>
            </article>
          </div>

          <aside class="energy-card">
            <header>
              <div>
                <small>ENERGY CHECK</small>
                <h2>精力补给</h2>
              </div>
            </header>
            <button
              v-for="(item, index) in config.energyItems"
              :key="item.id"
              class="energy-action"
              :class="{
                water: item.id === 'WATER',
                meditate: item.id === 'MEDITATION',
                once: item.mode === 'DAILY_ONCE',
                done: item.mode === 'DAILY_ONCE' && energyCount(item) > 0,
              }"
              type="button"
              :disabled="
                energyActionBusy ||
                energyCount(item) >= 99 ||
                (item.mode === 'DAILY_ONCE' && energyCount(item) > 0)
              "
              @click="recordEnergy(item)"
            >
              <i
                :class="
                  item.mode === 'DAILY_ONCE' && energyCount(item) > 0
                    ? 'ri-checkbox-circle-fill'
                    : item.id === 'WATER'
                      ? 'ri-cup-line'
                      : item.id === 'MEDITATION'
                        ? 'ri-mental-health-line'
                        : index % 2 === 0
                          ? 'ri-flashlight-line'
                          : 'ri-leaf-line'
                "
              ></i
              ><span
                ><b>{{ item.title }}{{ item.mode === 'COUNT' ? ' +1' : '' }}</b
                ><small v-if="item.mode === 'COUNT'"
                  >{{ item.guidance }} {{ energyCount(item) }} 次</small
                ><small v-else>{{
                  energyCount(item) > 0 ? '今天已完成' : item.guidance
                }}</small></span
              >
              <strong v-if="item.mode === 'COUNT'">{{ energyCount(item) }}</strong>
            </button>
            <div v-if="energyNotificationState === 'sending'" class="energy-notice sending">
              <i class="ri-loader-4-line spin"></i><span>记录已保存，正在发送飞书通知…</span>
            </div>
            <div
              v-else-if="energyNotificationState === 'error'"
              class="energy-notice error"
              role="alert"
            >
              <i class="ri-cloud-off-line"></i><span>记录已保存，飞书通知未发出。</span>
              <button type="button" @click="retryEnergyNotification">重试通知</button>
            </div>
            <div v-else-if="energyNotificationState === 'sent'" class="energy-notice sent">
              <i class="ri-send-plane-fill"></i><span>飞书通知已发出</span>
            </div>
          </aside>
        </section>
      </section>
    </template>

    <template v-if="editorOpen">
      <div class="mask" @click="!editorBusy && (editorOpen = false)"></div>
      <section class="editor" role="dialog" aria-modal="true" aria-labelledby="rhythm-editor-title">
        <header>
          <div>
            <small>个人工作日配置</small>
            <h2 id="rhythm-editor-title">调整每日节奏</h2>
          </div>
          <button type="button" aria-label="关闭" @click="editorOpen = false">
            <i class="ri-close-line"></i>
          </button>
        </header>
        <div class="editor-body">
          <p class="editor-intro">
            时间线与通知目标都按你的账号保存；编辑草稿会先留在本机，保存后才跨设备生效。
          </p>
          <WorkdayRhythmDailySettings @saved="dailyPanelKey += 1" />
          <section class="energy-editor" aria-labelledby="energy-editor-title">
            <header>
              <div>
                <small>ACTION NOTIFICATION</small>
                <h3 id="energy-editor-title">今日行动飞书通知</h3>
              </div>
              <label class="toggle-field">
                <input v-model="editorDraft.energyNotification.enabled" type="checkbox" />
                <span>{{ editorDraft.energyNotification.enabled ? '已启用' : '未启用' }}</span>
              </label>
            </header>
            <p>今日重点或补给行动成功保存后，可使用系统 Webhook 或飞书应用通知指定群聊。</p>
            <div v-if="editorDraft.energyNotification.enabled" class="energy-editor-fields">
              <label class="energy-notify-field">
                <input v-model="editorDraft.energyNotification.notifyFocus" type="checkbox" />
                <span>今日重点保存后发群</span>
              </label>
              <label class="channel-field">
                <span>发送方式</span>
                <select v-model="editorDraft.energyNotification.channel">
                  <option value="WEBHOOK">系统 Webhook</option>
                  <option value="CHAT">飞书应用群聊</option>
                </select>
              </label>
              <label v-if="editorDraft.energyNotification.channel === 'WEBHOOK'" class="chat-field">
                <span>Webhook 配置</span>
                <select
                  v-model="editorDraft.energyNotification.webhookId"
                  :disabled="notificationWebhooksLoading"
                  @change="selectNotificationWebhook"
                >
                  <option value="">请选择系统设置中的 Webhook</option>
                  <option
                    v-if="configuredWebhookMissing"
                    :value="editorDraft.energyNotification.webhookId"
                    disabled
                  >
                    已保存 · {{ editorDraft.energyNotification.webhookName || '配置不可用' }}
                  </option>
                  <option
                    v-for="endpoint in notificationWebhooks.filter((item) => item.enabled)"
                    :key="endpoint.id"
                    :value="endpoint.id"
                  >
                    {{ endpoint.name }}{{ endpoint.systemDefault ? ' · 系统默认' : '' }}
                  </option>
                </select>
              </label>
              <label v-else class="chat-field">
                <span>通知群聊</span>
                <select
                  v-model="editorDraft.energyNotification.chatId"
                  :disabled="notificationChatsLoading"
                  @change="selectNotificationChat"
                >
                  <option value="">请选择机器人所在的群</option>
                  <option
                    v-if="configuredChatMissing"
                    :value="editorDraft.energyNotification.chatId"
                  >
                    已保存 · {{ editorDraft.energyNotification.chatName || '未知群聊' }}
                  </option>
                  <option v-for="chat in notificationChats" :key="chat.chatId" :value="chat.chatId">
                    {{ chat.name }}
                  </option>
                </select>
              </label>
              <button
                class="refresh-chats"
                type="button"
                :disabled="notificationChatsLoading || notificationWebhooksLoading"
                @click="loadNotificationTargets"
              >
                <i
                  :class="
                    notificationChatsLoading || notificationWebhooksLoading
                      ? 'ri-loader-4-line spin'
                      : 'ri-refresh-line'
                  "
                ></i>
                {{
                  notificationChatsLoading || notificationWebhooksLoading ? '读取中' : '刷新选项'
                }}
              </button>
            </div>
            <p
              v-if="
                editorDraft.energyNotification.channel === 'WEBHOOK' && notificationWebhooksError
              "
              class="chat-load-error"
              role="alert"
            >
              <i class="ri-error-warning-line"></i
              >{{ notificationWebhooksError }}；可前往系统设置的“Webhook配置”维护。
            </p>
            <p
              v-else-if="
                editorDraft.energyNotification.channel === 'CHAT' && notificationChatsError
              "
              class="chat-load-error"
              role="alert"
            >
              <i class="ri-error-warning-line"></i
              >{{ notificationChatsError }}；请确认自动化中的飞书应用凭据可用。
            </p>
          </section>
          <section class="energy-items-editor" aria-labelledby="energy-items-title">
            <header>
              <div>
                <small>ENERGY ITEMS</small>
                <h3 id="energy-items-title">精力补给项目</h3>
                <p>每项选择累计次数或每天只能完成一次，保存后会按顺序展示。</p>
              </div>
              <button
                type="button"
                :disabled="editorDraft.energyItems.length >= 8"
                @click="addEnergyItem"
              >
                <i class="ri-add-line"></i>添加项目
              </button>
            </header>
            <div
              v-for="(item, index) in editorDraft.energyItems"
              :key="item.id"
              class="energy-item-editor"
            >
              <span class="energy-item-number">{{ index + 1 }}</span>
              <label class="energy-name-field"
                ><span>名称</span
                ><input v-model="item.title" maxlength="20" placeholder="例如：起身走动"
              /></label>
              <label class="energy-guidance-field"
                ><span>未完成时提示</span
                ><input
                  v-model="item.guidance"
                  maxlength="60"
                  placeholder="例如：离开座位活动几分钟"
              /></label>
              <label class="energy-mode-field"
                ><span>点击机制</span
                ><select v-model="item.mode">
                  <option value="COUNT">累计次数</option>
                  <option value="DAILY_ONCE">每天一次</option>
                </select></label
              >
              <label class="energy-notify-field">
                <input v-model="item.notify" type="checkbox" />
                <span>飞书通知</span>
              </label>
              <button
                class="remove-energy-item"
                type="button"
                aria-label="删除补给项目"
                :disabled="editorDraft.energyItems.length <= 1"
                @click="removeEnergyItem(index)"
              >
                <i class="ri-delete-bin-line"></i>
              </button>
            </div>
            <small class="energy-item-limit">{{ editorDraft.energyItems.length }} / 8 个项目</small>
          </section>
          <div class="timeline-editor-title">
            <small>DAILY FLOW</small>
            <h3>时间线与行动提示</h3>
            <p>时间块必须首尾相接并完整覆盖一天。</p>
          </div>
          <div v-for="(block, index) in editorDraft.blocks" :key="block.id" class="block-editor">
            <div class="block-number">{{ index + 1 }}</div>
            <div class="time-fields">
              <label><span>开始</span><input v-model="block.start" type="time" /></label
              ><label><span>结束</span><input v-model="block.end" type="time" /></label>
            </div>
            <label class="title-field"
              ><span>名称</span><input v-model="block.title" maxlength="30"
            /></label>
            <label class="guidance-field"
              ><span>行动提示</span
              ><textarea v-model="block.guidance" maxlength="120" rows="2"></textarea>
            </label>
          </div>
          <p v-if="editorError" class="editor-error" role="alert">
            <i class="ri-error-warning-line"></i>{{ editorError }}
          </p>
        </div>
        <footer>
          <button class="reset" type="button" :disabled="editorBusy" @click="resetEditor">
            恢复原始模板</button
          ><span></span
          ><button type="button" :disabled="editorBusy" @click="editorOpen = false">稍后再说</button
          ><button class="primary" type="button" :disabled="editorBusy" @click="submitEditor">
            {{ editorBusy ? '保存中…' : '保存节奏' }}
          </button>
        </footer>
      </section>
    </template>

    <template v-if="historyOpen">
      <div class="mask" @click="historyOpen = false"></div>
      <section
        class="history-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="rhythm-history-title"
      >
        <header>
          <div>
            <small>HISTORY · 真实保存记录</small>
            <h2 id="rhythm-history-title">今日节奏历史</h2>
            <p>按天回顾每日目标和各精力补给项目的执行次数。</p>
          </div>
          <button type="button" aria-label="关闭历史记录" @click="historyOpen = false">
            <i class="ri-close-line"></i>
          </button>
        </header>
        <div class="history-toolbar">
          <label>
            <span>查看月份</span>
            <input
              v-model="historyMonth"
              type="month"
              min="2000-01"
              max="2100-12"
              :disabled="historyLoading"
              @change="loadHistory"
            />
          </label>
          <button type="button" :disabled="historyLoading" @click="loadHistory">
            <i :class="historyLoading ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>刷新
          </button>
        </div>
        <div v-if="historyLoading && !history" class="history-state">
          <i class="ri-loader-4-line spin"></i>正在读取你的真实历史记录…
        </div>
        <div v-else-if="historyError" class="history-state error" role="alert">
          <i class="ri-error-warning-line"></i><span>{{ historyError }}</span>
          <button type="button" @click="loadHistory">重试</button>
        </div>
        <div
          v-else-if="!history?.items.length"
          class="history-state empty"
          :class="{ corrupt: history?.skippedCount }"
        >
          <template v-if="history?.skippedCount">
            <i class="ri-error-warning-line"></i>
            <b>{{ history.skippedCount }} 条已保存记录暂时无法读取</b>
            <span>损坏记录没有被伪装成空白日期；请保留数据并联系维护人员处理。</span>
          </template>
          <template v-else>
            <i class="ri-calendar-check-line"></i>
            <b>这个月还没有已保存记录</b>
            <span>完成一次打卡、补给记录或保存每日目标后，这里会出现真实历史。</span>
          </template>
        </div>
        <div v-else class="history-list">
          <p v-if="history.skippedCount" class="history-warning">
            <i class="ri-error-warning-line"></i
            >{{ history.skippedCount }} 条损坏记录未展示，未用空数据替代。
          </p>
          <article v-for="record in history.items" :key="record.date" class="history-day">
            <header>
              <div>
                <b>{{ historyDateLabel(record.date) }}</b
                ><small>{{ record.date }}</small>
              </div>
              <span :class="{ resting: !record.workdayActive }">
                {{ record.workdayActive ? '工作日节奏' : '休息日' }}
              </span>
            </header>
            <section>
              <h3><i class="ri-focus-3-line"></i>每日目标</h3>
              <ol v-if="record.focus.length">
                <li v-for="(item, index) in record.focus" :key="`${index}-${item}`">{{ item }}</li>
              </ol>
              <p v-else>当天没有填写目标</p>
            </section>
            <section>
              <h3><i class="ri-battery-charge-line"></i>项目执行</h3>
              <div class="history-energy-grid">
                <div v-for="item in record.energyItems" :key="item.id" :title="item.id">
                  <span>{{ item.title }}</span
                  ><b>{{ energyResult(item) }}</b>
                </div>
              </div>
            </section>
            <section v-if="record.checklist?.length">
              <h3><i class="ri-checkbox-multiple-line"></i>每日必做</h3>
              <div class="history-energy-grid">
                <div v-for="(item, index) in record.checklist" :key="`${index}-${item.title}`">
                  <span>{{ item.title }}</span>
                  <b :class="{ late: item.completedAt && item.completedOnTime === false }">
                    {{
                      item.completedAt ? (item.completedOnTime ? '准时完成' : '逾期完成') : '未完成'
                    }}
                  </b>
                </div>
              </div>
            </section>
            <section v-if="record.documentId && record.documentKbId">
              <h3><i class="ri-file-text-line"></i>每日文档</h3>
              <RouterLink
                :to="{ path: `/kb/${record.documentKbId}`, query: { doc: record.documentId } }"
              >
                {{ record.documentTitle || '打开当天工作记录'
                }}<i class="ri-arrow-right-up-line"></i>
              </RouterLink>
            </section>
          </article>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.rhythm-page {
  min-height: calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h) - 44px);
  box-sizing: border-box;
  max-width: 1180px;
  margin: 0 auto;
  padding: 16px 28px 24px;
  color: var(--wao-text);
  display: flex;
  flex-direction: column;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow,
article header small,
aside header small,
.editor header small {
  color: #267c65;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1.7px;
}
h1 {
  margin: 5px 0 1px;
  font-size: 25px;
}
.title-line {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.page-head p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 13px;
}
button,
input,
textarea {
  font: inherit;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.page-action,
.settings-button,
.history-button {
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 0 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.page-action.active {
  border-color: #b9d8cc;
  background: var(--wao-success-bg);
  color: #267c65;
}
.history-button {
  border-color: #b9d8cc;
  background: var(--wao-success-bg);
  color: #267c65;
}
.state-card {
  margin-top: 20px;
  min-height: 300px;
  border: 1px solid var(--wao-line);
  border-radius: 18px;
  background: var(--wao-bg-card);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: var(--wao-text-weak);
}
.state-card.error {
  flex-direction: column;
}
.state-card.error b {
  color: var(--wao-text-2);
}
.state-card.error button {
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  padding: 7px 14px;
  cursor: pointer;
}
.hero {
  position: relative;
  min-height: 205px;
  margin-top: 14px;
  padding: 18px 26px;
  border-radius: 20px;
  overflow: hidden;
  background: linear-gradient(128deg, #174f43, #267c65 62%, #3f9677);
  color: #fff;
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(190px, 1fr) minmax(190px, 1fr);
  align-items: center;
  gap: 20px;
  box-shadow: 0 18px 40px rgba(32, 99, 78, 0.16);
}
.hero::after {
  content: '';
  position: absolute;
  width: 340px;
  height: 340px;
  right: -125px;
  top: -145px;
  border: 1px solid rgba(255, 255, 255, 0.13);
  border-radius: 50%;
  box-shadow:
    0 0 0 48px rgba(255, 255, 255, 0.035),
    0 0 0 96px rgba(255, 255, 255, 0.025);
}
.hero.resting {
  background: linear-gradient(128deg, #344956, #526d75);
}
.hero.without-hexagram {
  grid-template-columns: minmax(0, 1.15fr) minmax(250px, 0.72fr);
}
.hero-copy,
.hero-flow {
  position: relative;
  z-index: 1;
}
.hero-flow {
  align-self: stretch;
  padding: 12px 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 14px;
  background: rgba(11, 58, 47, 0.22);
  backdrop-filter: blur(4px);
}
.hero-flow-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 7px;
}
.hero-flow-head small {
  color: #cce9df;
  font-size: 10px;
  font-weight: 700;
}
.hero-flow-head span {
  color: rgba(255, 255, 255, 0.48);
  font-size: 8.5px;
}
.hero-flow ol {
  margin: 0;
  padding: 0;
  list-style: none;
}
.hero-flow li {
  min-height: 21px;
  display: grid;
  grid-template-columns: 37px 9px minmax(0, 1fr);
  align-items: center;
  gap: 6px;
  border-radius: 7px;
  padding: 0 6px;
  color: rgba(255, 255, 255, 0.64);
  font-size: 9px;
}
.hero-flow li time {
  font-variant-numeric: tabular-nums;
}
.hero-flow li > i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.35);
}
.hero-flow li.current {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  font-weight: 700;
}
.hero-flow li.current > i {
  background: #9ce3c8;
  box-shadow: 0 0 0 3px rgba(156, 227, 200, 0.16);
}
.date-line {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.68);
  font-size: 11px;
}
.date-line span + span::before {
  content: '·';
  margin-right: 8px;
}
.date-line button {
  margin-left: 5px;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.85);
  padding: 3px 9px;
  cursor: pointer;
  font-size: 10px;
}
.hero-copy > small {
  display: block;
  margin-top: 14px;
  color: #b9e4d4;
  font-size: 11px;
  letter-spacing: 1px;
}
.hero h2 {
  margin: 3px 0 7px;
  font-size: clamp(28px, 4vw, 38px);
}
.hero-copy > p {
  max-width: 650px;
  margin: 0;
  color: rgba(255, 255, 255, 0.82);
  font-size: 13px;
  line-height: 1.7;
}
.time-meta {
  max-width: 560px;
  margin-top: 13px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
}
.time-meta b {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.time-meta span {
  color: #cce9df;
}
.time-progress {
  width: min(560px, 100%);
  height: 5px;
  margin-top: 7px;
  border-radius: 99px;
  background: rgba(255, 255, 255, 0.15);
  overflow: hidden;
}
.time-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #9ce3c8;
  transition: width 0.3s;
}
.next-up {
  margin-top: 11px;
  color: rgba(255, 255, 255, 0.64);
  font-size: 10.5px;
}
.next-up i {
  margin-right: 5px;
}
.save-banner {
  min-height: 32px;
  margin: 9px 2px -4px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--wao-text-faint);
  font-size: 10.5px;
}
.save-banner.saved {
  color: #267c65;
}
.save-banner.error {
  color: #a84545;
}
.save-banner button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.overview-grid {
  min-height: 0;
  flex: 1;
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-template-areas:
    'focus mustdo energy'
    'tomorrow tomorrow tomorrow';
  gap: 14px;
  align-items: stretch;
}
.dashboard-grid,
.focus-stack {
  display: contents;
}
.focus-stack > .focus-card:first-child {
  grid-area: focus;
}
.focus-card,
.energy-card {
  height: 100%;
  min-height: 0;
  box-sizing: border-box;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  overflow-y: auto;
}
.focus-card {
  padding: 18px;
  display: flex;
  flex-direction: column;
}
.tomorrow-focus {
  grid-area: tomorrow;
}
.focus-card header {
  display: flex;
  align-items: center;
}
.focus-card h2,
.energy-card h2 {
  margin: 2px 0 0;
  font-size: 16px;
}
.focus-card header .tomorrow-toggle {
  width: 32px;
  height: 32px;
  margin-left: auto;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-page);
  color: var(--wao-text-2);
  display: grid;
  place-items: center;
  cursor: pointer;
}
.focus-card label {
  min-height: 42px;
  margin-top: 7px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-page);
  padding: 0 10px;
  display: flex;
  align-items: center;
  gap: 9px;
}
.focus-row {
  flex: 1 0 42px;
  max-height: 70px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
}
.focus-row label {
  margin: 0;
}
.focus-row > small {
  color: var(--wao-text-weak);
  font-size: 9px;
}
.focus-row.active label {
  border-color: color-mix(in srgb, var(--wao-primary) 55%, var(--wao-line));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--wao-primary) 8%, transparent);
}
.focus-row.done label {
  opacity: 0.62;
}
.focus-row.done input {
  text-decoration: line-through;
}
.focus-complete {
  border: 0;
  border-radius: 9px;
  background: var(--wao-primary);
  color: #fff;
  padding: 9px 10px;
  font-size: 9.5px;
  white-space: nowrap;
  cursor: pointer;
}
.focus-complete:disabled {
  opacity: 0.55;
  cursor: wait;
}
.focus-done {
  color: #267c65 !important;
}
.focus-add {
  width: 32px;
  height: 32px;
  margin-top: 9px;
  border: 1px dashed var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-page);
  color: var(--wao-primary);
  display: grid;
  place-items: center;
  font-size: 15px;
  cursor: pointer;
}
.focus-add:disabled {
  opacity: 0.55;
  cursor: default;
}
.focus-remove {
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-faint);
  cursor: pointer;
}
.focus-remove:hover {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.tomorrow-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
}
.tomorrow-row label {
  margin-top: 7px;
}
.focus-notice {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 9px;
  font-size: 9.5px;
}
.focus-notice.sending {
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
}
.focus-notice.sent {
  background: color-mix(in srgb, #2f9674 10%, var(--wao-bg-card));
  color: #267c65;
}
.focus-notice.error {
  background: color-mix(in srgb, var(--wao-danger) 8%, var(--wao-bg-card));
  color: var(--wao-danger-strong);
}
.focus-notice button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.focus-card label:focus-within {
  border-color: #71ad98;
  box-shadow: 0 0 0 3px rgba(62, 139, 112, 0.08);
}
.focus-card label span {
  width: 23px;
  height: 23px;
  border-radius: 7px;
  background: #e0f0e9;
  color: #267c65;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
}
.focus-card input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text);
  font-size: 12.5px;
}
.focus-card footer {
  min-height: 35px;
  margin-top: 8px;
  display: flex;
  align-items: center;
}
.focus-card footer small {
  color: #9a741e;
}
.focus-card footer .focus-error {
  color: var(--wao-danger-strong);
}
.focus-card footer .focus-saved {
  color: #267c65;
}
.focus-card footer span {
  flex: 1;
}
.focus-card footer button {
  height: 31px;
  border: 0;
  border-radius: 8px;
  background: #267c65;
  color: #fff;
  padding: 0 12px;
  cursor: pointer;
  font-size: 11px;
}
.focus-card footer button:disabled {
  opacity: 0.55;
}
.tomorrow-focus {
  border-color: color-mix(in srgb, var(--wao-primary) 26%, var(--wao-line));
}
.tomorrow-state {
  min-height: 96px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.tomorrow-state.error {
  flex-direction: column;
  color: var(--wao-danger-strong);
}
.tomorrow-state button {
  border: 0;
  background: none;
  color: var(--wao-primary);
  cursor: pointer;
}
.energy-card {
  grid-area: energy;
  padding: 18px;
  display: flex;
  flex-direction: column;
}
.energy-card > header {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.energy-action {
  flex: 1 0 63px;
  width: 100%;
  min-height: 63px;
  margin-top: 8px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-page);
  color: var(--wao-text);
  padding: 9px 11px;
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
  cursor: pointer;
}
.energy-action > i {
  width: 33px;
  height: 33px;
  border-radius: 10px;
  background: #dcecf2;
  color: #37738a;
  display: grid;
  place-items: center;
  font-size: 17px;
}
.energy-action > span {
  flex: 1;
  display: grid;
}
.energy-action b {
  font-size: 12.5px;
}
.energy-action small {
  margin-top: 2px;
  color: var(--wao-text-faint);
  font-size: 10px;
}
.energy-action strong {
  color: #37738a;
  font-size: 21px;
}
.energy-action:disabled {
  cursor: default;
}
.energy-action:disabled:not(.done) {
  opacity: 0.62;
}
.energy-action.meditate > i {
  background: #efe9f6;
  color: #775b92;
}
.energy-action.once.done {
  border-color: #9ac7b7;
  background: #eff8f4;
}
.energy-action.once.done > i {
  background: #d9eee5;
  color: #267c65;
}
.energy-notice {
  min-height: 34px;
  margin-top: 10px;
  border-radius: 8px;
  padding: 7px 9px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--wao-text-weak);
  background: var(--wao-bg-page);
  font-size: 10px;
}
.energy-notice.error {
  color: #9b4141;
  background: var(--wao-danger-bg);
}
.energy-notice.sent {
  color: #267c65;
  background: #eff8f4;
}
.energy-notice button {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-weight: 700;
}
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(18, 29, 26, 0.44);
}
.history-dialog {
  position: fixed;
  z-index: 101;
  top: 4vh;
  left: 50%;
  transform: translateX(-50%);
  width: min(720px, calc(100vw - 28px));
  max-height: 92vh;
  border-radius: 17px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.history-dialog > header {
  min-height: 82px;
  padding: 15px 19px;
  border-bottom: 1px solid var(--wao-divider);
  display: flex;
  align-items: center;
  gap: 12px;
}
.history-dialog > header > div {
  min-width: 0;
  flex: 1;
}
.history-dialog > header small {
  color: #267c65;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 1.5px;
}
.history-dialog > header h2 {
  margin: 3px 0 2px;
  font-size: 18px;
}
.history-dialog > header p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 10.5px;
}
.history-dialog > header button {
  width: 31px;
  height: 31px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-2);
  cursor: pointer;
}
.history-toolbar {
  padding: 10px 19px;
  border-bottom: 1px solid var(--wao-divider);
  background: var(--wao-bg-page);
  display: flex;
  align-items: flex-end;
  gap: 8px;
}
.history-toolbar label {
  flex: 1;
  display: grid;
  gap: 4px;
}
.history-toolbar label span {
  color: var(--wao-text-faint);
  font-size: 9px;
}
.history-toolbar input,
.history-toolbar button,
.history-state button {
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  padding: 0 10px;
}
.history-toolbar button,
.history-state button {
  cursor: pointer;
}
.history-toolbar button:disabled {
  opacity: 0.55;
  cursor: wait;
}
.history-state {
  min-height: 260px;
  padding: 24px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 11px;
}
.history-state.error {
  color: #9b4141;
}
.history-state.empty {
  flex-direction: column;
  text-align: center;
}
.history-state.empty > i {
  color: #6cad95;
  font-size: 28px;
}
.history-state.empty.corrupt > i {
  color: #a66b22;
}
.history-state.empty b {
  color: var(--wao-text-2);
  font-size: 13px;
}
.history-list {
  padding: 12px 19px 20px;
  overflow-y: auto;
}
.history-warning {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--wao-warning-bg);
  color: #876714;
  font-size: 10px;
}
.history-warning i {
  margin-right: 5px;
}
.history-day {
  border: 1px solid var(--wao-line);
  border-radius: 13px;
  background: var(--wao-bg-card);
  overflow: hidden;
}
.history-day + .history-day {
  margin-top: 10px;
}
.history-day > header {
  padding: 11px 13px;
  border-bottom: 1px solid var(--wao-divider);
  background: var(--wao-success-bg);
  display: flex;
  align-items: center;
  gap: 10px;
}
.history-day > header > div {
  flex: 1;
  display: grid;
  gap: 2px;
}
.history-day > header b {
  font-size: 12px;
}
.history-day > header small {
  color: var(--wao-text-faint);
  font-size: 8.5px;
}
.history-day > header > span {
  padding: 3px 7px;
  border-radius: 999px;
  background: #dceee6;
  color: #267c65;
  font-size: 9px;
}
.history-day > header > span.resting {
  background: #e8ecef;
  color: #65737b;
}
.history-day > section {
  padding: 10px 13px;
}
.history-day > section + section {
  border-top: 1px solid var(--wao-divider);
}
.history-day h3 {
  margin: 0 0 7px;
  color: var(--wao-text-2);
  font-size: 10.5px;
}
.history-day h3 i {
  margin-right: 5px;
  color: #3f8b71;
}
.history-day ol {
  margin: 0;
  padding-left: 20px;
  color: var(--wao-text-2);
  font-size: 10.5px;
  line-height: 1.65;
}
.history-day section > p {
  margin: 0;
  color: var(--wao-text-faint);
  font-size: 10px;
}
.history-energy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(135px, 1fr));
  gap: 7px;
}
.history-energy-grid > div {
  min-width: 0;
  padding: 8px 9px;
  border-radius: 8px;
  background: var(--wao-bg-page);
  display: flex;
  align-items: center;
  gap: 8px;
}
.history-energy-grid span {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: var(--wao-text-weak);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-energy-grid b {
  color: #267c65;
  font-size: 11px;
  white-space: nowrap;
}
.editor {
  position: fixed;
  z-index: 101;
  top: 4vh;
  left: 50%;
  transform: translateX(-50%);
  width: min(760px, calc(100vw - 28px));
  max-height: 92vh;
  border-radius: 17px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.editor > header {
  min-height: 68px;
  padding: 13px 19px;
  border-bottom: 1px solid var(--wao-divider);
  display: flex;
  align-items: center;
}
.editor > header h2 {
  margin: 2px 0 0;
  font-size: 17px;
}
.editor > header button {
  margin-left: auto;
  width: 31px;
  height: 31px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
}
.editor-body {
  padding: 15px 19px 22px;
  overflow-y: auto;
}
.editor-intro {
  margin: 0 0 11px;
  color: var(--wao-text-faint);
  font-size: 10.5px;
}
.energy-editor {
  margin-bottom: 16px;
  border: 1px solid #b9d8cc;
  border-radius: 13px;
  padding: 13px;
  background: var(--wao-success-bg);
}
.energy-editor > header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.energy-editor h3,
.timeline-editor-title h3 {
  margin: 2px 0 0;
  color: var(--wao-text);
  font-size: 14px;
}
.energy-editor > p {
  margin: 8px 0 0;
  color: var(--wao-text-weak);
  font-size: 10.5px;
  line-height: 1.5;
}
.toggle-field {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #267c65;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
}
.toggle-field input,
.action-field input {
  accent-color: #267c65;
}
.energy-editor-fields {
  margin-top: 11px;
  display: grid;
  grid-template-columns: minmax(120px, 0.45fr) minmax(0, 1fr) auto;
  align-items: end;
  gap: 8px;
}
.chat-field,
.channel-field {
  display: grid;
  gap: 4px;
}
.chat-field > span,
.channel-field > span {
  color: var(--wao-text-faint);
  font-size: 9.5px;
}
.chat-field select,
.channel-field select {
  width: 100%;
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 0 8px;
  font-size: 11px;
}
.refresh-chats {
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  padding: 0 9px;
  cursor: pointer;
  font-size: 10px;
}
.action-field {
  min-height: 36px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  padding: 5px 7px;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.action-field > span {
  display: grid;
}
.action-field b {
  font-size: 10.5px;
}
.action-field small {
  color: var(--wao-text-faint);
  font-size: 8.5px;
}
.energy-editor > .chat-load-error {
  color: #9b4141;
}
.chat-load-error i {
  margin-right: 4px;
}
.energy-items-editor {
  margin-bottom: 16px;
}
.energy-items-editor > header {
  margin: 0 2px 9px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
}
.energy-items-editor h3 {
  margin: 2px 0 0;
  font-size: 14px;
}
.energy-items-editor header p {
  margin: 4px 0 0;
  color: var(--wao-text-faint);
  font-size: 10px;
}
.energy-items-editor > header button {
  min-height: 32px;
  border: 1px solid #9fcaba;
  border-radius: 8px;
  background: #eff8f4;
  color: #267c65;
  padding: 0 10px;
  cursor: pointer;
  font-size: 10.5px;
  font-weight: 700;
}
.energy-items-editor > header button:disabled {
  opacity: 0.55;
  cursor: default;
}
.energy-item-editor {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-page);
  display: grid;
  grid-template-columns: 25px minmax(110px, 0.7fr) minmax(180px, 1.3fr) 120px 72px 32px;
  align-items: end;
  gap: 8px;
}
.energy-item-number {
  width: 25px;
  height: 25px;
  margin-bottom: 6px;
  border-radius: 8px;
  background: #dceee6;
  color: #267c65;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 800;
}
.energy-item-editor label:not(.energy-notify-field) {
  display: grid;
  gap: 4px;
}
.energy-item-editor label > span {
  color: var(--wao-text-faint);
  font-size: 9px;
}
.energy-item-editor input[type='text'],
.energy-item-editor input:not([type]),
.energy-item-editor select {
  width: 100%;
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 0 8px;
  font: inherit;
  font-size: 10.5px;
}
.energy-notify-field {
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  padding: 0 8px;
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
}
.energy-notify-field input {
  accent-color: #267c65;
}
.remove-energy-item {
  width: 32px;
  height: 34px;
  border: 1px solid #e2c5c1;
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: #a04f45;
  cursor: pointer;
}
.remove-energy-item:disabled {
  opacity: 0.35;
  cursor: default;
}
.energy-item-limit {
  display: block;
  margin: 7px 2px 0;
  color: var(--wao-text-faint);
  text-align: right;
  font-size: 9px;
}
.timeline-editor-title {
  margin: 2px 2px 9px;
}
.timeline-editor-title p {
  margin: 4px 0 0;
  color: var(--wao-text-faint);
  font-size: 10px;
}
.block-editor {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-page);
  display: grid;
  grid-template-columns: 28px 190px minmax(130px, 0.7fr) minmax(180px, 1.3fr);
  align-items: end;
  gap: 8px;
}
.block-number {
  width: 25px;
  height: 25px;
  margin-bottom: 6px;
  border-radius: 8px;
  background: #dceee6;
  color: #267c65;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
}
.time-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.block-editor label {
  display: grid;
  gap: 4px;
}
.block-editor label span {
  color: var(--wao-text-faint);
  font-size: 9.5px;
}
.block-editor input,
.block-editor textarea {
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 7px 8px;
  outline: none;
  font-size: 11.5px;
}
.block-editor textarea {
  min-height: 52px;
  resize: vertical;
}
.block-editor input:focus,
.block-editor textarea:focus {
  border-color: #71ad98;
  box-shadow: 0 0 0 3px rgba(62, 139, 112, 0.08);
}
.editor-error {
  margin: 12px 2px 0;
  color: #aa4242;
  font-size: 11px;
}
.editor-error i {
  margin-right: 5px;
}
.editor > footer {
  min-height: 62px;
  padding: 10px 19px;
  border-top: 1px solid var(--wao-divider);
  display: flex;
  align-items: center;
  gap: 8px;
}
.editor > footer span {
  flex: 1;
}
.editor > footer button {
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  padding: 0 13px;
  cursor: pointer;
}
.editor > footer .reset {
  border: 0;
  color: var(--wao-text-faint);
}
.editor > footer .primary {
  border: 0;
  background: #267c65;
  color: #fff;
}
.editor > footer button:disabled {
  opacity: 0.55;
  cursor: wait;
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 760px) {
  .rhythm-page {
    min-height: 0;
    padding: 16px 12px 96px;
    display: block;
  }
  .page-head {
    align-items: center;
  }
  .page-head p {
    display: none;
  }
  .title-line {
    display: block;
  }
  .head-actions {
    gap: 5px;
  }
  .head-actions button {
    width: 34px;
    padding: 0;
    justify-content: center;
    font-size: 0;
  }
  .head-actions button i {
    font-size: 15px;
  }
  .settings-button,
  .history-button {
    padding: 0 10px;
  }
  .history-button {
    width: 36px;
    padding: 0;
    font-size: 0;
  }
  .history-button i {
    font-size: 15px;
  }
  .hero {
    min-height: 0;
    padding: 22px 18px;
    grid-template-columns: 1fr;
    gap: 20px;
  }
  .hero.without-hexagram {
    grid-template-columns: 1fr;
  }
  .hero-flow {
    order: 3;
  }
  .hero > :last-child {
    order: 2;
  }
  .hero h2 {
    font-size: 30px;
  }
  .overview-grid {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas: 'focus' 'mustdo' 'energy' 'tomorrow';
  }
  .focus-card,
  .energy-card {
    height: auto;
    overflow: visible;
    padding: 14px;
  }
  .focus-row {
    grid-template-columns: 1fr;
  }
  .focus-row > small,
  .focus-complete {
    justify-self: end;
  }
  .focus-notice {
    align-items: flex-start;
  }
  .editor {
    top: 10px;
    width: calc(100vw - 20px);
    max-height: calc(100dvh - 20px);
  }
  .history-dialog {
    top: 10px;
    width: calc(100vw - 20px);
    max-height: calc(100dvh - 20px);
  }
  .history-dialog > header p {
    display: none;
  }
  .history-list {
    padding: 10px;
  }
  .history-energy-grid {
    grid-template-columns: 1fr;
  }
  .editor-body {
    padding: 12px;
  }
  .energy-editor-fields {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .channel-field {
    grid-column: 1 / -1;
  }
  .energy-item-editor {
    grid-template-columns: 25px minmax(0, 1fr) 32px;
  }
  .energy-name-field,
  .energy-guidance-field,
  .energy-mode-field,
  .energy-notify-field {
    grid-column: 2 / 3;
  }
  .remove-energy-item {
    grid-column: 3 / 4;
    grid-row: 1;
  }
  .block-editor {
    grid-template-columns: 25px 1fr 1fr;
  }
  .time-fields {
    grid-column: 2 / -1;
  }
  .title-field {
    grid-column: 2 / -1;
  }
  .guidance-field {
    grid-column: 2 / -1;
  }
  .editor > footer .reset {
    display: none;
  }
}
</style>
