<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { AssistantStatus } from '@/api/assistant'
import {
  fetchWorkdayRhythm,
  fetchWorkdayRhythmDay,
  saveWorkdayRhythmDay,
  sendEnergyNotification,
} from '@/api/workdayRhythm'
import PetSprite from './PetSprite.vue'
import { useFloatingDrag } from '@/composables/floatingDrag'
import { useIsMobile } from '@/composables/viewport'
import { toast } from '@/composables/toast'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useWebPetStore, type WebPetTriggerAction } from '@/stores/webPet'
import type { Task, TaskStatus } from '@/types'
import {
  parseRhythmDay,
  shanghaiClock,
  type WorkdayRhythmConfig,
  type WorkdayRhythmDay,
  type WorkdayRhythmEnergyItem,
} from '@/utils/workdayRhythm'

interface PetNotice {
  id: string
  icon: string
  label: string
  detail: string
  taskId?: string
  tone: 'review' | 'running' | 'todo' | 'neutral' | 'error'
}

interface PetRhythmDraft {
  stage: 'saving' | 'notifying'
  itemId: string
  day: WorkdayRhythmDay
}

const props = defineProps<{ assistantStatus: AssistantStatus }>()
const router = useRouter()
const data = useDataStore()
const ui = useUiStore()
const webPet = useWebPetStore()
const isMobile = useIsMobile()
const mobileExpanded = ref(readMobileExpanded())
const noticeIndex = ref(0)
const quickPointerActive = ref(false)
const quickFocusActive = ref(false)
const quickPinned = ref(false)
const settingsPointerActive = ref(false)
const settingsPinned = ref(false)
const noticeSnoozeOpen = ref(false)
const noticeHiddenUntil = ref(readDeadline('wao-web-pet-notice-hidden-until'))
const petHiddenUntil = ref(readDeadline('wao-web-pet-hidden-until'))
const clockTick = ref(Date.now())
const petActivity = ref<'stretch' | 'hop' | 'step' | null>(null)
const petSpriteState = computed(() => petActivity.value === 'hop' ? 'jumping' : petActivity.value ? 'waving' : 'idle')
const rhythmConfig = ref<WorkdayRhythmConfig | null>(null)
const rhythmDay = ref<WorkdayRhythmDay | null>(null)
const rhythmDate = ref(shanghaiClock().date)
const rhythmWeekday = ref(shanghaiClock().weekday)
const rhythmState = ref<'idle' | 'loading' | 'saving' | 'notifying' | 'saved' | 'error'>('idle')
const rhythmError = ref('')
const pendingRhythmItemId = ref<string | null>(null)
const pendingRhythmStage = ref<PetRhythmDraft['stage'] | null>(null)
const lastSavedRhythmItemId = ref<string | null>(null)
const rhythmPageDraftConflict = ref(false)
let rhythmLoadedAt = 0
let noticeTimer: ReturnType<typeof setInterval> | null = null
let rhythmSavedTimer: ReturnType<typeof setTimeout> | null = null
let activityTimer: ReturnType<typeof setInterval> | null = null
let deadlineTimer: ReturnType<typeof setInterval> | null = null
let petLongPressTimer: ReturnType<typeof setTimeout> | null = null
let petLongPressStart: { pointerId: number; x: number; y: number } | null = null
let suppressPetClickUntil = 0
let suppressPetContextUntil = 0
let activitySequence = 0
const PET_LONG_PRESS_MS = 520
const PET_LONG_PRESS_MOVE_TOLERANCE = 8
const {
  elementRef: petRef,
  style: petStyle,
  dragging: petDragging,
  onPointerDown: startPetDrag,
  consumeClick,
} = useFloatingDrag('wao-assistant-fab-position-v1')

const priorityOrder = { P0: 0, P1: 1, P2: 2, P3: 3 }

function tasksWithStatus(status: TaskStatus): Task[] {
  return data.tasks
    .filter((task) => task.status === status)
    .sort((left, right) => {
      const byPriority = priorityOrder[left.pr] - priorityOrder[right.pr]
      if (byPriority !== 0) return byPriority
      return (left.due || '9999-12-31').localeCompare(right.due || '9999-12-31')
    })
}

function taskNotice(
  id: string,
  tasks: Task[],
  icon: string,
  label: string,
  suffix: string,
  tone: PetNotice['tone'],
): PetNotice | null {
  const first = tasks[0]
  if (!first) return null
  return {
    id,
    icon,
    label: `${label} · ${tasks.length} ${suffix}`,
    detail: first.title,
    taskId: first.id,
    tone,
  }
}

const notices = computed<PetNotice[]>(() => {
  if (data.taskLoadState === 'loading') {
    return [
      {
        id: 'loading',
        icon: 'ri-loader-4-line',
        label: '正在读取任务动态',
        detail: '问答入口仍可使用',
        tone: 'neutral',
      },
    ]
  }
  if (data.taskLoadState === 'error') {
    return [
      {
        id: 'error',
        icon: 'ri-error-warning-line',
        label: '任务动态暂时不可用',
        detail: data.taskLoadError || '请稍后重试',
        tone: 'error',
      },
    ]
  }

  const result = [
    taskNotice(
      'review',
      tasksWithStatus('待验证'),
      'ri-checkbox-circle-line',
      'AI 已完成',
      '项等你验收',
      'review',
    ),
    taskNotice(
      'running',
      tasksWithStatus('AI 进行中'),
      'ri-sparkling-2-line',
      'AI 正在工作',
      '项执行中',
      'running',
    ),
    taskNotice(
      'todo',
      tasksWithStatus('待办'),
      'ri-calendar-check-line',
      '待办提醒',
      '项待办',
      'todo',
    ),
  ].filter((notice): notice is PetNotice => notice !== null)

  if (result.length) return result
  return [
    {
      id: 'assistant',
      icon: props.assistantStatus.configured ? 'ri-chat-smile-2-line' : 'ri-cloud-off-line',
      label: props.assistantStatus.configured ? '今天的任务动态已清空' : 'WAO AI 暂时离线',
      detail: props.assistantStatus.configured ? '有问题随时问我' : props.assistantStatus.message,
      tone: props.assistantStatus.configured ? 'neutral' : 'error',
    },
  ]
})

const currentNotice = computed(() => notices.value[noticeIndex.value % notices.value.length]!)
const quickMenuActive = computed(
  () => quickPointerActive.value || quickFocusActive.value || quickPinned.value,
)
const settingsMenuActive = computed(() => settingsPointerActive.value || settingsPinned.value)
const noticeVisible = computed(() =>
  webPet.settings.showTaskNotices
  && noticeHiddenUntil.value <= clockTick.value
  && !quickMenuActive.value
  && !settingsMenuActive.value,
)
const petHidden = computed(() => petHiddenUntil.value > clockTick.value || (isMobile.value && !mobileExpanded.value))
const rhythmActions = computed(() => rhythmConfig.value?.energyItems.slice(0, 3) ?? [])
const actionableCount = computed(
  () => data.tasks.filter((task) => ['AI 进行中', '待验证', '待办'].includes(task.status)).length,
)

function readDeadline(key: string) {
  const value = Number(localStorage.getItem(key))
  return Number.isFinite(value) ? value : 0
}

function readMobileExpanded() {
  try { return sessionStorage.getItem('wao-web-pet-mobile-expanded-v1') === 'true' }
  catch { return false }
}

function keepDeadline(key: string, minutes: number) {
  const until = Date.now() + Math.max(1, minutes) * 60_000
  localStorage.setItem(key, String(until))
  clockTick.value = Date.now()
  return until
}

function snoozeNotice(minutes: number) {
  noticeHiddenUntil.value = keepDeadline('wao-web-pet-notice-hidden-until', minutes)
  noticeSnoozeOpen.value = false
  toast(`宠物气泡已静音 ${minutes} 分钟`, 'ri-notification-off-line')
}

function hidePet(minutes: number) {
  petHiddenUntil.value = keepDeadline('wao-web-pet-hidden-until', minutes)
  settingsPinned.value = false
  quickPinned.value = false
  toast(`宠物已进入 ${minutes} 分钟静默状态`, 'ri-moon-clear-line')
}

function restorePet() {
  if (isMobile.value) {
    mobileExpanded.value = true
    try { sessionStorage.setItem('wao-web-pet-mobile-expanded-v1', 'true') } catch { /* 当前页面仍可恢复。 */ }
  }
  localStorage.removeItem('wao-web-pet-hidden-until')
  petHiddenUntil.value = 0
  clockTick.value = Date.now()
}

function petRhythmDraftKey(date = rhythmDate.value) {
  return `wao-pet-rhythm-day-draft-${date}`
}

function workdayRhythmDraftKey(date = rhythmDate.value) {
  return `wao-workday-rhythm-day-draft-${date}`
}

function refreshRhythmPageDraftConflict() {
  rhythmPageDraftConflict.value = !!localStorage.getItem(workdayRhythmDraftKey())
}

function restorePetRhythmDraft(config: WorkdayRhythmConfig): PetRhythmDraft | null {
  const raw = localStorage.getItem(petRhythmDraftKey())
  if (!raw) return null
  try {
    const draft = JSON.parse(raw) as PetRhythmDraft
    if (
      (draft.stage !== 'saving' && draft.stage !== 'notifying') ||
      !config.energyItems.some((item) => item.id === draft.itemId) ||
      draft.day?.date !== rhythmDate.value
    ) {
      localStorage.removeItem(petRhythmDraftKey())
      return null
    }
    return {
      ...draft,
      day: parseRhythmDay(
        JSON.stringify(draft.day),
        rhythmDate.value,
        rhythmWeekday.value,
        config.blocks.map((block) => block.id),
        config.energyItems.map((item) => item.id),
      ),
    }
  } catch {
    localStorage.removeItem(petRhythmDraftKey())
    return null
  }
}

function keepPetRhythmDraft(stage: PetRhythmDraft['stage'], itemId: string) {
  if (!rhythmDay.value) return
  localStorage.setItem(
    petRhythmDraftKey(),
    JSON.stringify({ stage, itemId, day: rhythmDay.value } satisfies PetRhythmDraft),
  )
}

async function loadRhythmActions(force = false) {
  if (['loading', 'saving', 'notifying'].includes(rhythmState.value)) return
  if (pendingRhythmItemId.value) return
  if (rhythmState.value === 'error' && !force) return

  const clock = shanghaiClock()
  if (clock.date !== rhythmDate.value) {
    rhythmDate.value = clock.date
    rhythmWeekday.value = clock.weekday
    rhythmConfig.value = null
    rhythmDay.value = null
    rhythmLoadedAt = 0
  }
  refreshRhythmPageDraftConflict()
  if (!force && rhythmConfig.value && rhythmDay.value && Date.now() - rhythmLoadedAt < 30_000)
    return

  rhythmState.value = 'loading'
  rhythmError.value = ''
  try {
    const config = await fetchWorkdayRhythm()
    const loadedDay = await fetchWorkdayRhythmDay(
      rhythmDate.value,
      rhythmWeekday.value,
      config.blocks.map((block) => block.id),
      config.energyItems.map((item) => item.id),
    )
    const draft = restorePetRhythmDraft(config)
    rhythmConfig.value = config
    rhythmDay.value = draft?.stage === 'saving' ? draft.day : loadedDay
    rhythmLoadedAt = Date.now()
    if (draft) {
      pendingRhythmItemId.value = draft.itemId
      pendingRhythmStage.value = draft.stage
      rhythmState.value = 'error'
      rhythmError.value =
        draft.stage === 'saving'
          ? '上次记录未保存，点击原动作重试'
          : '记录已保存，通知未发出，点击原动作重试'
    } else {
      rhythmState.value = 'idle'
    }
  } catch (error) {
    rhythmState.value = 'error'
    rhythmError.value = error instanceof Error ? error.message : '今日节奏暂时无法读取'
  }
}

function rhythmCount(item: WorkdayRhythmEnergyItem) {
  return rhythmDay.value?.energyRecords[item.id] ?? 0
}

function rhythmActionStatus(item: WorkdayRhythmEnergyItem) {
  if (pendingRhythmItemId.value === item.id) {
    if (rhythmState.value === 'saving') return '保存中…'
    if (rhythmState.value === 'notifying') return '已记录，通知中…'
    if (pendingRhythmStage.value === 'saving') return `保存失败 · 点击重试：${rhythmError.value}`
    if (pendingRhythmStage.value === 'notifying')
      return `已记录 · 通知失败，点击重试：${rhythmError.value}`
  }
  if (lastSavedRhythmItemId.value === item.id && rhythmState.value === 'saved') return '已保存'
  const count = rhythmCount(item)
  return item.mode === 'DAILY_ONCE'
    ? count > 0
      ? '今天已完成'
      : item.guidance
    : `今天 ${count} 次`
}

function rhythmActionDisabled(item: WorkdayRhythmEnergyItem) {
  if (rhythmPageDraftConflict.value && pendingRhythmStage.value !== 'notifying') return true
  if (
    rhythmState.value === 'loading' ||
    rhythmState.value === 'saving' ||
    rhythmState.value === 'notifying'
  )
    return true
  if (pendingRhythmItemId.value) return pendingRhythmItemId.value !== item.id
  const count = rhythmCount(item)
  return count >= 99 || (item.mode === 'DAILY_ONCE' && count > 0)
}

function syncLegacyRhythmFields() {
  if (!rhythmDay.value) return
  rhythmDay.value.waterCount = rhythmDay.value.energyRecords.WATER ?? 0
  rhythmDay.value.meditated = (rhythmDay.value.energyRecords.MEDITATION ?? 0) > 0
}

function finishRhythmAction(itemId: string) {
  pendingRhythmItemId.value = null
  pendingRhythmStage.value = null
  lastSavedRhythmItemId.value = itemId
  rhythmState.value = 'saved'
  rhythmError.value = ''
  localStorage.removeItem(petRhythmDraftKey())
  localStorage.setItem(
    'wao-pet-rhythm-last-saved',
    JSON.stringify({ date: rhythmDate.value, at: Date.now() }),
  )
  if (rhythmSavedTimer) clearTimeout(rhythmSavedTimer)
  rhythmSavedTimer = setTimeout(() => {
    rhythmState.value = 'idle'
    lastSavedRhythmItemId.value = null
  }, 2_500)
}

async function notifyRhythmAction(item: WorkdayRhythmEnergyItem) {
  const notification = rhythmConfig.value?.energyNotification
  if (!notification?.enabled || notification.notifyEnergy === false || !item.notify) {
    finishRhythmAction(item.id)
    return
  }
  pendingRhythmItemId.value = item.id
  pendingRhythmStage.value = 'notifying'
  rhythmState.value = 'notifying'
  keepPetRhythmDraft('notifying', item.id)
  try {
    const result = await sendEnergyNotification(item.id, rhythmDate.value)
    finishRhythmAction(item.id)
    toast(
      result.sent ? `${item.title}已记录，并已通知「${result.chatName}」` : `${item.title}已记录`,
      result.sent ? 'ri-send-plane-fill' : 'ri-check-line',
    )
  } catch (error) {
    rhythmState.value = 'error'
    rhythmError.value =
      error instanceof Error ? `记录已保存，但通知失败：${error.message}` : '记录已保存，但通知失败'
    keepPetRhythmDraft('notifying', item.id)
  }
}

async function saveRhythmAction(item: WorkdayRhythmEnergyItem) {
  if (!rhythmDay.value || !rhythmConfig.value) return
  pendingRhythmItemId.value = item.id
  pendingRhythmStage.value = 'saving'
  rhythmState.value = 'saving'
  rhythmDay.value.energySnapshot = {
    ...rhythmDay.value.energySnapshot,
    [item.id]: { title: item.title, mode: item.mode },
  }
  keepPetRhythmDraft('saving', item.id)
  try {
    await saveWorkdayRhythmDay(rhythmDay.value)
    await notifyRhythmAction(item)
  } catch (error) {
    rhythmState.value = 'error'
    rhythmError.value = error instanceof Error ? error.message : '记录保存失败，请重试'
    keepPetRhythmDraft('saving', item.id)
  }
}

async function recordRhythmAction(item: WorkdayRhythmEnergyItem) {
  refreshRhythmPageDraftConflict()
  if (rhythmPageDraftConflict.value && pendingRhythmStage.value !== 'notifying') return
  if (pendingRhythmItemId.value === item.id) {
    if (pendingRhythmStage.value === 'saving') await saveRhythmAction(item)
    else if (pendingRhythmStage.value === 'notifying') await notifyRhythmAction(item)
    return
  }
  if (rhythmActionDisabled(item) || !rhythmDay.value) return
  const current = rhythmCount(item)
  rhythmDay.value.energyRecords = {
    ...rhythmDay.value.energyRecords,
    [item.id]: item.mode === 'DAILY_ONCE' ? 1 : current + 1,
  }
  syncLegacyRhythmFields()
  await saveRhythmAction(item)
}

function activateQuickPointer() {
  if (webPet.settings.hoverAction !== 'QUICK_ACTIONS') return
  quickPointerActive.value = true
  void loadRhythmActions()
}

function activateQuickFocus() {
  quickFocusActive.value = true
  void loadRhythmActions()
}

function applyTrigger(action: WebPetTriggerAction) {
  quickPointerActive.value = false
  quickFocusActive.value = false
  settingsPointerActive.value = false
  quickPinned.value = action === 'QUICK_ACTIONS' ? !quickPinned.value : false
  settingsPinned.value = action === 'SETTINGS' ? !settingsPinned.value : false
  if (action === 'QUICK_ACTIONS') void loadRhythmActions()
}

function onPetPointerEnter() {
  const action = webPet.settings.hoverAction
  if (action === 'QUICK_ACTIONS') activateQuickPointer()
  if (action === 'SETTINGS') settingsPointerActive.value = true
}

function onPetPointerLeave() {
  quickPointerActive.value = false
  settingsPointerActive.value = false
}

function leaveQuickFocus(event: FocusEvent) {
  const container = event.currentTarget as HTMLElement
  if (!(event.relatedTarget instanceof Node) || !container.contains(event.relatedTarget)) {
    quickFocusActive.value = false
  }
}

function restartNoticeTimer() {
  if (noticeTimer) clearInterval(noticeTimer)
  noticeTimer = null
  if (notices.value.length > 1) {
    noticeTimer = setInterval(() => {
      noticeIndex.value = (noticeIndex.value + 1) % notices.value.length
    }, webPet.settings.noticeRotationSeconds * 1_000)
  }
}

function playPetActivity() {
  const activities = ['stretch', 'hop', 'step'] as const
  petActivity.value = activities[activitySequence % activities.length]!
  activitySequence += 1
}

function interactWithPet(event: MouseEvent) {
  if (Date.now() < suppressPetClickUntil) {
    event.preventDefault()
    event.stopPropagation()
    return
  }
  if (consumeClick(event)) return
  applyTrigger(webPet.settings.leftClickAction)
  if (webPet.settings.leftClickAction !== 'NONE') playPetActivity()
}

function cancelPetLongPress() {
  if (petLongPressTimer) clearTimeout(petLongPressTimer)
  petLongPressTimer = null
  petLongPressStart = null
}

function startPetLongPress(event: PointerEvent) {
  if (event.pointerType === 'mouse' || event.button !== 0) return
  cancelPetLongPress()
  petLongPressStart = { pointerId: event.pointerId, x: event.clientX, y: event.clientY }
  petLongPressTimer = setTimeout(() => {
    if (petLongPressStart?.pointerId !== event.pointerId) return
    petLongPressTimer = null
    suppressPetClickUntil = Date.now() + 1_000
    suppressPetContextUntil = suppressPetClickUntil
    applyTrigger(webPet.settings.rightClickAction)
    if (webPet.settings.rightClickAction !== 'NONE') {
      playPetActivity()
      navigator.vibrate?.(18)
    }
  }, PET_LONG_PRESS_MS)
}

function movePetLongPress(event: PointerEvent) {
  const start = petLongPressStart
  if (!start || start.pointerId !== event.pointerId) return
  if (
    Math.hypot(event.clientX - start.x, event.clientY - start.y) > PET_LONG_PRESS_MOVE_TOLERANCE
  ) {
    cancelPetLongPress()
  }
}

function endPetLongPress(event: PointerEvent) {
  if (petLongPressStart?.pointerId === event.pointerId) cancelPetLongPress()
}

function openPetSettings() {
  settingsPinned.value = false
  void router.push({ path: '/settings', query: { tab: 'pet' } })
}

function openPetContext(event: MouseEvent) {
  if (Date.now() < suppressPetContextUntil) return
  if (consumeClick(event)) return
  applyTrigger(webPet.settings.rightClickAction)
}

function openNotice(event: MouseEvent) {
  if (consumeClick(event)) return
  if (currentNotice.value.taskId) ui.openTask(currentNotice.value.taskId)
  else interactWithPet(event)
}

watch(
  () => notices.value.map((notice) => notice.id).join('|'),
  () => {
    noticeIndex.value = 0
    restartNoticeTimer()
  },
)

watch(
  () => webPet.settings.noticeRotationSeconds,
  restartNoticeTimer,
)

onMounted(() => {
  void webPet.load()
  restartNoticeTimer()
  activityTimer = setInterval(() => {
    if (webPet.settings.idleAnimation && !petDragging.value && !quickMenuActive.value
        && !settingsMenuActive.value && !petHidden.value) playPetActivity()
  }, 16_000)
  deadlineTimer = setInterval(() => { clockTick.value = Date.now() }, 1_000)
})
onBeforeUnmount(() => {
  if (noticeTimer) clearInterval(noticeTimer)
  if (rhythmSavedTimer) clearTimeout(rhythmSavedTimer)
  if (activityTimer) clearInterval(activityTimer)
  if (deadlineTimer) clearInterval(deadlineTimer)
  cancelPetLongPress()
})
</script>

<template>
  <button
    v-if="petHidden"
    class="pet-silent-dock"
    type="button"
    title="宠物静默中，点击立即恢复"
    aria-label="宠物静默中，点击立即恢复"
    @click="restorePet"
  >
    <PetSprite paused /><i class="ri-moon-clear-line"></i>
  </button>
  <section
    v-else
    ref="petRef"
    class="wao-web-pet"
    :class="{ dragging: petDragging }"
    :style="petStyle"
    aria-label="WAO 网页宠物"
    @pointerdown="startPetDrag"
  >
    <Transition name="pet-notice" mode="out-in">
      <div
        v-if="noticeVisible"
        :key="currentNotice.id"
        class="pet-notice"
        :class="`tone-${currentNotice.tone}`"
        @click="openNotice"
      >
        <button
          class="pet-notice-main"
          type="button"
          aria-live="polite"
          :title="currentNotice.taskId ? `打开任务：${currentNotice.detail}` : '和宠物互动'"
        >
          <i :class="[currentNotice.icon, { spin: currentNotice.id === 'loading' }]"></i>
          <span><b>{{ currentNotice.label }}</b><small>{{ currentNotice.detail }}</small></span>
          <i :class="currentNotice.taskId ? 'ri-arrow-right-s-line' : 'ri-chat-3-line'"></i>
        </button>
        <button
          class="pet-notice-close"
          type="button"
          title="临时关闭气泡"
          aria-label="临时关闭宠物气泡"
          @pointerdown.stop
          @click.stop="noticeSnoozeOpen = !noticeSnoozeOpen"
        ><i class="ri-close-line"></i></button>
        <div v-if="noticeSnoozeOpen" class="notice-snooze-menu" @pointerdown.stop>
          <span>气泡暂不显示</span>
          <button type="button" @click.stop="snoozeNotice(5)">5 分钟</button>
          <button type="button" @click.stop="snoozeNotice(10)">10 分钟</button>
          <button type="button" @click.stop="snoozeNotice(webPet.settings.noticeSnoozeMinutes)">
            自选 {{ webPet.settings.noticeSnoozeMinutes }} 分钟
          </button>
        </div>
      </div>
    </Transition>

    <div
      class="pet-control"
      @pointerenter="onPetPointerEnter"
      @pointerleave="onPetPointerLeave"
      @focusin="activateQuickFocus"
      @focusout="leaveQuickFocus"
    >
      <button
        class="pet-silence-close"
        type="button"
        title="让宠物静默"
        aria-label="让宠物静默"
        @pointerdown.stop
        @click.stop="hidePet(webPet.settings.petSnoozeMinutes)"
      ><i class="ri-close-line"></i></button>
      <button
        class="pet-avatar"
        :class="petActivity ? `activity-${petActivity}` : ''"
        type="button"
        title="点击打开快捷操作，拖动调整位置；手机长按执行右键动作"
        aria-label="点击打开快捷操作，拖动调整宠物位置；手机长按执行右键动作"
        :aria-expanded="quickMenuActive || settingsMenuActive"
        aria-controls="pet-quick-actions pet-settings-actions"
        @click="interactWithPet"
        @contextmenu.prevent="openPetContext"
        @pointerdown="startPetLongPress"
        @pointermove="movePetLongPress"
        @pointerup="endPetLongPress"
        @pointercancel="endPetLongPress"
      >
        <span
          v-if="actionableCount"
          class="pet-badge"
          :aria-label="`${actionableCount} 项任务动态`"
        >
          {{ actionableCount > 99 ? '99+' : actionableCount }}
        </span>
        <PetSprite :state="petSpriteState" :paused="petDragging || (!webPet.settings.idleAnimation && !petActivity)"
          @finished="petActivity = null" />
        <span
          class="pet-status"
          :class="{ online: assistantStatus.configured }"
          aria-hidden="true"
        ></span>
      </button>

      <nav
        id="pet-quick-actions"
        class="pet-quick-actions"
        :class="{ open: quickMenuActive }"
        aria-label="宠物快捷操作"
        @pointerdown.stop
      >
        <span class="quick-actions-label">快捷操作</span>
        <button type="button" class="pet-settings-link pet-quick-silence" @click="hidePet(webPet.settings.petSnoozeMinutes)">
          <i class="ri-moon-clear-line"></i><span><b>宠物静默</b><small>收起 {{ webPet.settings.petSnoozeMinutes }} 分钟，可随时恢复</small></span>
        </button>
        <a
          v-if="rhythmPageDraftConflict && pendingRhythmStage !== 'notifying'"
          class="pet-rhythm-state conflict"
          href="/workday-rhythm"
        >
          <i class="ri-draft-line"></i>
          <span><b>先保存页面草稿</b><small>打开今日节奏继续</small></span>
        </a>
        <button
          v-else-if="rhythmState === 'loading' || (!rhythmConfig && rhythmState === 'idle')"
          class="pet-rhythm-state"
          type="button"
          disabled
        >
          <i class="ri-loader-4-line spin"></i>
          <span><b>正在读取今日节奏</b><small>从你的 WAO 账号加载</small></span>
        </button>
        <button
          v-else-if="rhythmState === 'error' && !rhythmActions.length"
          class="pet-rhythm-state error"
          type="button"
          @click="loadRhythmActions(true)"
        >
          <i class="ri-refresh-line"></i>
          <span
            ><b>读取失败 · 点击重试</b><small>{{ rhythmError }}</small></span
          >
        </button>
        <template v-else>
          <button
            v-for="item in rhythmActions"
            :key="item.id"
            class="pet-rhythm-action"
            :class="{
              done: item.mode === 'DAILY_ONCE' && rhythmCount(item) > 0,
              error: pendingRhythmItemId === item.id && rhythmState === 'error',
            }"
            type="button"
            :disabled="rhythmActionDisabled(item)"
            :title="`${item.title}：${rhythmActionStatus(item)}`"
            @click="recordRhythmAction(item)"
          >
            <i
              :class="
                pendingRhythmItemId === item.id && ['saving', 'notifying'].includes(rhythmState)
                  ? 'ri-loader-4-line spin'
                  : item.mode === 'DAILY_ONCE' && rhythmCount(item) > 0
                    ? 'ri-check-line'
                    : item.mode === 'COUNT'
                      ? 'ri-add-line'
                      : 'ri-checkbox-blank-circle-line'
              "
            ></i>
            <span
              ><b>{{ item.title }}</b
              ><small>{{ rhythmActionStatus(item) }}</small></span
            >
            <strong v-if="item.mode === 'COUNT'">{{ rhythmCount(item) }}</strong>
          </button>
        </template>
        <button class="pet-settings-link" type="button" @click="openPetSettings">
          <i class="ri-settings-4-line"></i>
          <span><b>宠物设置</b><small>配置气泡、触发方式与静默</small></span>
          <i class="ri-arrow-right-s-line"></i>
        </button>
      </nav>

      <nav
        id="pet-settings-actions"
        class="pet-settings-actions"
        :class="{ open: settingsMenuActive }"
        aria-label="宠物功能气泡"
        @pointerdown.stop
      >
        <span class="quick-actions-label">宠物功能</span>
        <button type="button" @click="openPetSettings">
          <i class="ri-settings-4-line"></i><span><b>宠物设置</b><small>打开专用配置页面</small></span>
          <i class="ri-arrow-right-s-line"></i>
        </button>
        <div class="pet-menu-group">
          <span>气泡暂不显示</span>
          <button type="button" @click="snoozeNotice(5)">5 分钟</button>
          <button type="button" @click="snoozeNotice(10)">10 分钟</button>
          <button type="button" @click="snoozeNotice(webPet.settings.noticeSnoozeMinutes)">自选 {{ webPet.settings.noticeSnoozeMinutes }} 分钟</button>
        </div>
        <div class="pet-menu-group">
          <span>宠物进入静默</span>
          <button type="button" @click="hidePet(5)">5 分钟</button>
          <button type="button" @click="hidePet(10)">10 分钟</button>
          <button type="button" @click="hidePet(webPet.settings.petSnoozeMinutes)">自选 {{ webPet.settings.petSnoozeMinutes }} 分钟</button>
        </div>
      </nav>
    </div>
  </section>
</template>

<style scoped>
.wao-web-pet {
  position: fixed;
  right: 78px;
  bottom: 12px;
  z-index: 70;
  width: min(280px, calc(100vw - 24px));
  min-height: 160px;
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: flex-end;
}
.wao-web-pet.dragging {
  cursor: grabbing;
}
.pet-notice {
  position: relative;
  width: 230px;
  min-height: 58px;
  margin: 0 54px -11px 0;
  padding: 0;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 20%, var(--wao-line));
  border-radius: 14px 14px 4px 14px;
  background: color-mix(in srgb, var(--wao-bg-card) 96%, transparent);
  color: var(--wao-text-2);
  box-shadow: 0 12px 32px rgba(29, 37, 70, 0.16);
  backdrop-filter: blur(10px);
  display: block;
}
.pet-notice::before {
  content: '';
  position: absolute;
  right: 30px;
  bottom: -7px;
  width: 12px;
  height: 12px;
  border-right: 1px solid color-mix(in srgb, var(--wao-primary) 20%, var(--wao-line));
  border-bottom: 1px solid color-mix(in srgb, var(--wao-primary) 20%, var(--wao-line));
  background: color-mix(in srgb, var(--wao-bg-card) 96%, transparent);
  transform: rotate(45deg);
}
.pet-notice:hover {
  border-color: var(--wao-primary-line);
}
.pet-notice-main {
  width: 100%; min-height: 58px; padding: 9px 28px 9px 10px; border: 0; border-radius: inherit;
  background: transparent; color: inherit; cursor: pointer; display: grid;
  grid-template-columns: 24px minmax(0, 1fr) 16px; align-items: center; gap: 7px;
}
.pet-notice-main > i:first-child {
  color: var(--wao-primary);
  font-size: 19px;
}
.pet-notice-main > i:last-child {
  color: var(--wao-text-weak);
  font-size: 16px;
}
.pet-notice-main span,
.pet-notice-main b,
.pet-notice-main small {
  min-width: 0;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}
.pet-notice-main b {
  color: var(--wao-text);
  font-size: 12px;
  font-weight: 700;
}
.pet-notice-main small {
  margin-top: 3px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.pet-notice.tone-review {
  border-color: color-mix(in srgb, #d89614 34%, var(--wao-line));
}
.pet-notice.tone-review .pet-notice-main > i:first-child {
  color: #d89614;
}
.pet-notice.tone-running .pet-notice-main > i:first-child {
  animation: pet-spark 1.7s ease-in-out infinite;
}
.pet-notice.tone-error {
  border-color: color-mix(in srgb, #e5484d 36%, var(--wao-line));
}
.pet-notice.tone-error .pet-notice-main > i:first-child {
  color: #d94b50;
}
.pet-control {
  position: relative;
  width: 102px;
  height: 100px;
}
.pet-silence-close {
  position: absolute;
  z-index: 2;
  top: -8px;
  right: -2px;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid var(--wao-line);
  border-radius: 50%;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  box-shadow: var(--wao-shadow-pop);
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
}
.pet-control:hover .pet-silence-close,
.pet-control:focus-within .pet-silence-close {
  opacity: 1;
  pointer-events: auto;
}
@media (hover: none) {
  .pet-silence-close { opacity: 1; pointer-events: auto; width: 36px; height: 36px; }
}
.pet-notice-close { position: absolute; z-index: 1; top: 5px; right: 5px; width: 23px; height: 23px; padding: 0; border: 0; border-radius: 7px; background: transparent; color: var(--wao-text-faint); cursor: pointer; }
.pet-notice-close:hover { background: var(--wao-bg-hover-2); color: var(--wao-text-2); }
.notice-snooze-menu { position: absolute; z-index: 3; right: 4px; top: 36px; width: 148px; padding: 6px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop); display: grid; gap: 3px; }
.notice-snooze-menu span { padding: 4px 6px; color: var(--wao-text-faint); font-size: 9px; }
.notice-snooze-menu button { min-height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--wao-text-2); text-align: left; cursor: pointer; font-size: 11px; }
.notice-snooze-menu button:hover { background: var(--wao-bg-hover-2); }
.pet-avatar {
  position: relative;
  width: 102px;
  height: 100px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: grab;
  filter: drop-shadow(0 10px 11px rgba(55, 39, 79, 0.24));
}
.pet-quick-actions,
.pet-settings-actions {
  position: absolute;
  right: 82px;
  bottom: 9px;
  width: 174px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  opacity: 0;
  transform: translateX(8px) scale(0.98);
  transform-origin: right bottom;
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
  pointer-events: none;
}
.pet-quick-actions.open,
.pet-settings-actions.open {
  opacity: 1;
  transform: translateX(0) scale(1);
  pointer-events: auto;
}
.pet-settings-actions { width: 210px; right: 82px; }
.pet-settings-actions > button { width: 202px; min-height: 42px; padding: 7px 9px; border: 1px solid color-mix(in srgb, var(--wao-primary) 22%, var(--wao-line)); border-radius: 18px 18px 4px 18px; background: color-mix(in srgb, var(--wao-bg-card) 96%, transparent); color: var(--wao-text-2); box-shadow: 0 9px 22px rgba(29,37,70,.14); display: grid; grid-template-columns: 22px minmax(0,1fr) auto; align-items: center; gap: 6px; text-align: left; cursor: pointer; }
.pet-settings-actions > button i { color: var(--wao-primary); font-size: 16px; }.pet-settings-actions > button i:last-child { color: var(--wao-text-weak); }
.pet-settings-actions > button span,.pet-settings-actions > button b,.pet-settings-actions > button small { min-width: 0; display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pet-settings-actions > button b { color: var(--wao-text); font-size: 11px; }.pet-settings-actions > button small { margin-top: 2px; color: var(--wao-text-weak); font-size: 9px; }
.pet-menu-group { width: 202px; box-sizing: border-box; padding: 8px; border: 1px solid var(--wao-line); border-radius: 12px 12px 4px 12px; background: var(--wao-bg-card); box-shadow: 0 9px 22px rgba(29,37,70,.12); display: grid; grid-template-columns: repeat(3,1fr); gap: 4px; }
.pet-menu-group > span { grid-column: 1 / -1; color: var(--wao-text-weak); font-size: 9px; }
.pet-menu-group button { min-height: 27px; padding: 0 4px; border: 0; border-radius: 6px; background: var(--wao-bg-hover); color: var(--wao-text-2); cursor: pointer; font-size: 9px; white-space: nowrap; }
.pet-menu-group button:hover { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.quick-actions-label {
  align-self: flex-end;
  margin: 0 8px -2px 0;
  color: var(--wao-text-weak);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.12em;
}
.pet-rhythm-action,
.pet-rhythm-state,
.pet-settings-link {
  width: 166px;
  min-height: 40px;
  padding: 6px 9px;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 22%, var(--wao-line));
  border-radius: 18px 18px 4px 18px;
  background: color-mix(in srgb, var(--wao-bg-card) 96%, transparent);
  color: var(--wao-text-2);
  box-shadow: 0 9px 22px rgba(29, 37, 70, 0.14);
  backdrop-filter: blur(10px);
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
  text-align: left;
  text-decoration: none;
}
.pet-rhythm-action {
  cursor: pointer;
}
.pet-rhythm-action:hover:not(:disabled),
.pet-rhythm-state:hover:not(:disabled),
.pet-settings-link:hover {
  border-color: var(--wao-primary-line);
  transform: translateX(-2px);
}
.pet-rhythm-action > i,
.pet-rhythm-state > i,
.pet-settings-link > i {
  color: var(--wao-primary);
  font-size: 17px;
  text-align: center;
}
.pet-rhythm-action span,
.pet-rhythm-action b,
.pet-rhythm-action small,
.pet-rhythm-state span,
.pet-rhythm-state b,
.pet-rhythm-state small,
.pet-settings-link span,
.pet-settings-link b,
.pet-settings-link small {
  min-width: 0;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pet-settings-link {
  cursor: pointer;
}
.pet-settings-link > i:last-child {
  color: var(--wao-text-weak);
}
.pet-rhythm-action b,
.pet-rhythm-state b,
.pet-settings-link b {
  color: var(--wao-text);
  font-size: 11px;
  font-weight: 700;
}
.pet-rhythm-action small,
.pet-rhythm-state small,
.pet-settings-link small {
  margin-top: 1px;
  color: var(--wao-text-weak);
  font-size: 9px;
}
.pet-rhythm-action strong {
  color: var(--wao-primary);
  font-size: 12px;
}
.pet-rhythm-action.done > i {
  color: #2f9e6f;
}
.pet-rhythm-action.error,
.pet-rhythm-state.error {
  border-color: color-mix(in srgb, #e5484d 42%, var(--wao-line));
}
.pet-rhythm-action.error > i,
.pet-rhythm-state.error > i {
  color: #d94b50;
}
.pet-rhythm-state.conflict {
  border-color: color-mix(in srgb, #d89614 36%, var(--wao-line));
}
.pet-rhythm-state.conflict > i {
  color: #c27c00;
}
.pet-rhythm-action:disabled {
  cursor: not-allowed;
  opacity: 0.68;
}
.pet-rhythm-action.done:disabled {
  opacity: 0.84;
}
.dragging .pet-avatar {
  cursor: grabbing;
  animation: none;
}
.pet-avatar:hover {
  filter: drop-shadow(0 13px 14px rgba(68, 51, 100, 0.3));
}
.pet-avatar img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  object-position: center bottom;
  pointer-events: none;
}
.pet-avatar :deep(.pet-sprite) { width: 92.307%; }
.pet-silent-dock :deep(.pet-sprite) { width: 34px; height: 37px; }
.pet-status {
  position: absolute;
  right: 8px;
  bottom: 13px;
  width: 10px;
  height: 10px;
  border: 2px solid var(--wao-bg-card);
  border-radius: 50%;
  background: #f0a33a;
  box-shadow: 0 0 0 3px rgba(240, 163, 58, 0.18);
}
.pet-status.online {
  background: #4fc58b;
  box-shadow: 0 0 0 3px rgba(79, 197, 139, 0.2);
}
.pet-badge {
  position: absolute;
  z-index: 1;
  right: 4px;
  top: 5px;
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  border: 2px solid var(--wao-bg-card);
  border-radius: 999px;
  background: #e5484d;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}
.pet-silent-dock { position: fixed; z-index: 70; right: 0; bottom: 92px; width: 42px; height: 48px; padding: 2px 0 2px 4px; border: 1px solid var(--wao-line); border-right: 0; border-radius: 16px 0 0 16px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop); cursor: pointer; overflow: hidden; }
.pet-silent-dock img { width: 34px; height: 34px; object-fit: contain; }.pet-silent-dock i { position: absolute; right: 3px; bottom: 3px; padding: 2px; border-radius: 50%; background: var(--wao-bg-card); color: var(--wao-text-weak); font-size: 10px; }
.spin {
  animation: pet-spin 1s linear infinite;
}
.pet-notice-enter-active,
.pet-notice-leave-active {
  transition: opacity 0.18s ease;
}
.pet-notice-enter-from,
.pet-notice-leave-to {
  opacity: 0;
}
@keyframes pet-breathe {
  0%,
  100% {
    transform: translateY(0) rotate(-1deg);
  }
  50% {
    transform: translateY(-4px) rotate(1deg);
  }
}
@keyframes pet-spark {
  0%,
  100% {
    transform: scale(0.92) rotate(-6deg);
    opacity: 0.72;
  }
  50% {
    transform: scale(1.12) rotate(5deg);
    opacity: 1;
  }
}
@keyframes pet-spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes pet-stretch {
  0%,
  100% {
    transform: scale(1) rotate(0);
  }
  35% {
    transform: scaleX(1.08) scaleY(0.92) rotate(-4deg);
  }
  70% {
    transform: scaleX(0.96) scaleY(1.06) rotate(3deg);
  }
}
@keyframes pet-hop {
  0%,
  100% {
    transform: translateY(0) rotate(0);
  }
  35% {
    transform: translateY(-13px) rotate(-5deg);
  }
  58% {
    transform: translateY(1px) rotate(4deg);
  }
  76% {
    transform: translateY(-5px) rotate(-2deg);
  }
}
@keyframes pet-step {
  0%,
  100% {
    transform: translateX(0) rotate(0);
  }
  25% {
    transform: translateX(-12px) rotate(-6deg);
  }
  50% {
    transform: translateX(8px) rotate(5deg);
  }
  75% {
    transform: translateX(-4px) rotate(-3deg);
  }
}
.pet-avatar.activity-stretch img {
  animation: pet-stretch 0.8s ease-in-out;
}
.pet-avatar.activity-hop img {
  animation: pet-hop 0.9s ease-in-out;
}
.pet-avatar.activity-step img {
  animation: pet-step 1s ease-in-out;
}

@media (max-width: 760px) {
  .wao-web-pet {
    right: 94px;
    bottom: calc(71px + env(safe-area-inset-bottom));
    width: min(238px, calc(100vw - 92px));
    min-height: 144px;
  }
  .pet-notice {
    width: 190px;
    min-height: 52px;
    margin-right: 46px;
    padding: 8px;
  }
  .pet-avatar {
    width: 82px;
    height: 82px;
  }
  .pet-control {
    width: 82px;
    height: 82px;
  }
  .pet-quick-actions {
    right: 0;
    bottom: 86px;
    width: min(210px, calc(100vw - 24px));
  }
  .pet-settings-actions {
    right: 0;
    bottom: 86px;
    width: min(210px, calc(100vw - 24px));
  }
  .pet-rhythm-action,
  .pet-rhythm-state,
  .pet-settings-link {
    width: 100%;
    min-height: 38px;
    padding: 5px 8px;
    box-sizing: border-box;
  }
  .pet-settings-actions > button,
  .pet-menu-group {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pet-avatar,
  .pet-avatar img,
  .pet-notice.tone-running .pet-notice-main > i:first-child,
  .spin {
    animation: none;
  }
  .pet-notice-enter-active,
  .pet-notice-leave-active,
  .pet-quick-actions {
    transition: none;
  }
}
</style>
