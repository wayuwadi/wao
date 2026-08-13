<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { fetchCaldavAccounts, fetchCaldavEvents, type CaldavAccount, type CaldavEvent } from '@/api/caldav'
import { fetchAlternateWorkWeek, saveAlternateWorkWeek } from '@/api/workSchedule'
import { fetchHolidayEvents, type HolidayDayType, type HolidayEvent } from '@/api/holiday'
import { toast } from '@/composables/toast'
import { usePreferenceStore } from '@/stores/preferences'
import type { Task } from '@/types'
import {
  defaultAlternateWorkWeek,
  parseAlternateWorkWeek,
  workWeekForDate,
  type AlternateWorkWeekSchedule,
} from '@/utils/workSchedule'
// 配置面板打开才需要，不进日历页首屏包。
const CaldavPanel = defineAsyncComponent(() => import('@/components/calendar/CaldavPanel.vue'))

/** 日历格子里的一条事件（任务截止 / 里程碑 / CalDAV）。 */
interface CalEvt {
  date: string
  title: string
  color: string
  completed?: boolean
  time?: string
  endTime?: string
  taskId?: string
  holidayDayType?: HolidayDayType
  go?: () => void
}

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const preferences = usePreferenceStore()
const router = useRouter()

const now = new Date(`${data.today}T00:00:00`)
const calY = ref(now.getFullYear())
const calM = ref(now.getMonth())
const calD = ref(now.getDate())
const viewMode = ref<'year' | 'month' | 'week' | 'day'>('month')
/** 日历默认只看当前人；切换后才加载另一成员的任务，避免跨成员任务默认混在一起。 */
const calendarMemberId = ref(session.currentUser.id)
watch(() => session.currentUser.id, (memberId) => {
  if (!calendarMemberId.value && memberId) calendarMemberId.value = memberId
}, { immediate: true })

const DOWS = computed(() => preferences.weekStart === '周日' ? ['日', '一', '二', '三', '四', '五', '六'] : ['一', '二', '三', '四', '五', '六', '日'])

// ---------- 大小周：成员级持久化配置 + 确定性日历标识 ----------
const workSchedule = ref<AlternateWorkWeekSchedule>(defaultAlternateWorkWeek(data.today))
const workScheduleLoading = ref(true)
const workScheduleLoadError = ref('')
const showWorkScheduleConfig = ref(false)
const workScheduleSaving = ref(false)
const workScheduleSaveError = ref('')
const workScheduleDraft = reactive<AlternateWorkWeekSchedule>(defaultAlternateWorkWeek(data.today))
const workScheduleDraftKey = computed(() => `wao-work-week-draft:${session.currentUser.id || 'anonymous'}`)

const thisWeekInfo = computed(() => workWeekForDate(data.today, workSchedule.value))
const workScheduleState = computed(() => {
  if (workScheduleLoading.value) return { label: '我的大小周 · 读取中', dot: '#9A9DA9' }
  if (workScheduleLoadError.value) return { label: '我的大小周 · 读取失败', dot: '#D9634C' }
  if (!thisWeekInfo.value) return { label: '设置我的大小周', dot: '#9A9DA9' }
  return {
    label: `本周 · ${thisWeekInfo.value.label} · ${thisWeekInfo.value.restLabel}`,
    dot: thisWeekInfo.value.type === 'big' ? '#D9634C' : '#2C8A5B',
  }
})

async function loadWorkSchedule() {
  workScheduleLoading.value = true
  workScheduleLoadError.value = ''
  try {
    workSchedule.value = await fetchAlternateWorkWeek(data.today)
    if (showWorkScheduleConfig.value && !localStorage.getItem(workScheduleDraftKey.value)) {
      copyWorkSchedule(workScheduleDraft, workSchedule.value)
    }
  } catch (error) {
    workScheduleLoadError.value = error instanceof Error ? error.message : '读取大小周配置失败'
  } finally {
    workScheduleLoading.value = false
  }
}

function copyWorkSchedule(target: AlternateWorkWeekSchedule, source: AlternateWorkWeekSchedule) {
  target.enabled = source.enabled
  target.anchorWeek = source.anchorWeek
  target.anchorType = source.anchorType
}

function openWorkScheduleConfig() {
  workScheduleSaveError.value = ''
  const savedDraft = localStorage.getItem(workScheduleDraftKey.value)
  const restored = savedDraft ? parseAlternateWorkWeek(savedDraft) : null
  copyWorkSchedule(workScheduleDraft, restored ?? workSchedule.value)
  showWorkScheduleConfig.value = true
}

function closeWorkScheduleConfig() {
  if (workScheduleSaving.value) return
  localStorage.removeItem(workScheduleDraftKey.value)
  showWorkScheduleConfig.value = false
}

watch(workScheduleDraft, (value) => {
  if (showWorkScheduleConfig.value) localStorage.setItem(workScheduleDraftKey.value, JSON.stringify(value))
}, { deep: true })

async function retryLoadWorkSchedule() {
  await loadWorkSchedule()
  if (!workScheduleLoadError.value) copyWorkSchedule(workScheduleDraft, workSchedule.value)
}

async function submitWorkSchedule() {
  workScheduleSaving.value = true
  workScheduleSaveError.value = ''
  try {
    const saved = await saveAlternateWorkWeek({ ...workScheduleDraft })
    workSchedule.value = saved
    copyWorkSchedule(workScheduleDraft, saved)
    localStorage.removeItem(workScheduleDraftKey.value)
    showWorkScheduleConfig.value = false
    const info = workWeekForDate(data.today, saved)
    toast(info ? `大小周已保存：本周是${info.label}（${info.restLabel}）` : '大小周已关闭', 'ri-calendar-check-line')
  } catch (error) {
    workScheduleSaveError.value = error instanceof Error ? error.message : '保存大小周配置失败'
  } finally {
    workScheduleSaving.value = false
  }
}

// ---------- 外部 CalDAV + 内置节假日 ----------
const accounts = ref<CaldavAccount[]>([])
const caldavEvents = ref<CaldavEvent[]>([])
const holidayEvents = ref<HolidayEvent[]>([])
const holidayState = ref<'loading' | 'ready' | 'error'>('loading')
let holidayRequestSequence = 0

const caldavState = computed(() => {
  if (!accounts.value.length) return { label: '接入 CalDAV 日历', dot: '#9A9DA9' }
  const bad = accounts.value.some((a) => a.status === 'error')
  return bad
    ? { label: 'CalDAV 同步异常', dot: '#D9634C' }
    : { label: 'CalDAV 已连接', dot: '#2C8A5B' }
})

async function loadCaldav() {
  try {
    accounts.value = await fetchCaldavAccounts()
  } catch {
    accounts.value = []
  }
}

/** 当前视图真实可见的日期窗，年视图覆盖完整自然年。 */
function visibleRange() {
  if (viewMode.value === 'year') {
    return { from: new Date(calY.value, 0, 1), to: new Date(calY.value, 11, 31) }
  }
  if (viewMode.value === 'day') {
    const day = new Date(calY.value, calM.value, calD.value)
    return { from: day, to: day }
  }
  if (viewMode.value === 'week') {
    const pivot = new Date(calY.value, calM.value, calD.value)
    const offset = preferences.weekStart === '周日' ? pivot.getDay() : (pivot.getDay() + 6) % 7
    const from = new Date(pivot)
    from.setDate(pivot.getDate() - offset)
    const to = new Date(from)
    to.setDate(from.getDate() + 6)
    return { from, to }
  }
  const first = new Date(calY.value, calM.value, 1)
  const offset = preferences.weekStart === '周日' ? first.getDay() : (first.getDay() + 6) % 7
  const days = new Date(calY.value, calM.value + 1, 0).getDate()
  const cellCount = Math.ceil((offset + days) / 7) * 7
  const from = new Date(calY.value, calM.value, 1 - offset)
  const to = new Date(from)
  to.setDate(from.getDate() + cellCount - 1)
  return { from, to }
}

/** 拉取当前视图可见日期窗内的 CalDAV 订阅事件。 */
async function loadEvents() {
  const { from, to } = visibleRange()
  try {
    caldavEvents.value = await fetchCaldavEvents(iso(from), iso(to))
  } catch {
    caldavEvents.value = []
  }
}

/** 拉取内置节假日；序号避免快速翻页时旧响应覆盖新日期窗。 */
async function loadHolidays() {
  const requestSequence = ++holidayRequestSequence
  const { from, to } = visibleRange()
  holidayState.value = 'loading'
  try {
    const result = await fetchHolidayEvents(iso(from), iso(to))
    if (requestSequence !== holidayRequestSequence) return
    holidayEvents.value = result
    holidayState.value = 'ready'
  } catch {
    if (requestSequence !== holidayRequestSequence) return
    holidayEvents.value = []
    holidayState.value = 'error'
  }
}

function loadCalendarSources() {
  loadEvents()
  loadHolidays()
}

function iso(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

onMounted(() => {
  loadCaldav()
  loadWorkSchedule()
  loadCalendarSources()
})
watch([calY, calM, calD, viewMode, () => preferences.weekStart], loadCalendarSources)

// ---------- 图例：固定两类 + 每个启用的订阅日历 ----------
const legend = computed(() => {
  const items = [
    { label: '任务截止', color: '#4358D6' },
    { label: '里程碑', color: '#B9821F' },
    { label: '中国节假日', color: '#D9634C' },
    { label: '国际节日', color: '#8A5BD9' },
  ]
  if (holidayEvents.value.some((event) => event.dayType === 'ADJUSTED_WORKDAY')) {
    items.push({ label: '调休上班', color: '#79808F' })
  }
  for (const a of accounts.value) {
    for (const c of a.calendars) {
      if (c.enabled) items.push({ label: c.name, color: c.color })
    }
  }
  return items
})

// ---------- 事件合并 ----------
const events = computed<CalEvt[]>(() => {
  const evs: CalEvt[] = []
  data.tasks
    .filter((t) => t.owner === calendarMemberId.value && (t.due || t.startAt))
    .forEach((t) => evs.push({
      date: t.startAt?.slice(0, 10) || t.due,
      title: t.title,
      color: '#4358D6',
      completed: t.status === '已完成',
      time: t.startAt?.slice(11, 16),
      endTime: t.endAt?.slice(11, 16),
      taskId: t.id,
      go: () => ui.openTask(t.id),
    }))
  data.projects.forEach((p) => {
    p.msDates.forEach((d, i) => {
      const name = p.milestones[i]?.name
      if (d && name) evs.push({ date: d, title: `里程碑 · ${name}`, color: '#B9821F', go: () => router.push(`/projects/${p.id}`) })
    })
  })
  caldavEvents.value.forEach((e) => {
    evs.push({
      date: e.start.slice(0, 10),
      title: e.summary || '（无标题）',
      color: e.color,
      time: e.allDay ? '' : e.start.slice(11, 16),
      endTime: e.allDay ? '' : e.end?.slice(11, 16),
    })
  })
  holidayEvents.value.forEach((holiday) => {
    evs.push({
      date: holiday.date,
      title: holiday.name,
      color: holiday.dayType === 'ADJUSTED_WORKDAY'
        ? '#79808F'
        : holiday.category === 'CHINA' ? '#D9634C' : '#8A5BD9',
      holidayDayType: holiday.dayType,
    })
  })
  return evs
})

const month = computed(() => {
  const y = calY.value
  const m = calM.value
  const first = new Date(y, m, 1)
  const off = preferences.weekStart === '周日' ? first.getDay() : (first.getDay() + 6) % 7
  const dim = new Date(y, m + 1, 0).getDate()
  const prevDim = new Date(y, m, 0).getDate()
  const byDate: Record<string, CalEvt[]> = {}
  events.value.forEach((e) => (byDate[e.date] = byDate[e.date] || []).push(e))
  for (const k of Object.keys(byDate)) {
    byDate[k]!.sort((a, b) => {
      const holidayPriority = Number(!a.holidayDayType) - Number(!b.holidayDayType)
      return holidayPriority || (a.time ?? '').localeCompare(b.time ?? '')
    })
  }
  const total = Math.ceil((off + dim) / 7) * 7
  const cells: {
    iso: string; label: string; isToday: boolean; items: CalEvt[]; more: string
    dayMark: '' | '休' | '班'
    numBg: string; numColor: string; bg: string
    workWeek: ReturnType<typeof workWeekForDate>
  }[] = []
  for (let i = 0; i < total; i++) {
    const dnum = i - off + 1
    const d = new Date(y, m, dnum)
    const dayIso = iso(d)
    let label: number
    let inM: boolean
    if (dnum < 1) {
      label = prevDim + dnum
      inM = false
    } else if (dnum > dim) {
      label = dnum - dim
      inM = false
    } else {
      label = dnum
      inM = true
    }
    const all = byDate[dayIso] || []
    const isToday = dayIso === data.today
    const expanded = expandedDay.value === dayIso
    cells.push({
      iso: dayIso,
      label: String(label),
      isToday,
      items: expanded ? all : all.slice(0, 3),
      more: !expanded && all.length > 3 ? `还有 ${all.length - 3} 项` : '',
      dayMark: all.some((event) => event.holidayDayType === 'ADJUSTED_WORKDAY')
        ? '班'
        : all.some((event) => event.holidayDayType === 'DAY_OFF') ? '休' : '',
      numBg: isToday ? '#4358D6' : 'transparent',
      numColor: isToday ? '#FFFFFF' : inM ? '#3E414D' : '#C4C7D2',
      bg: inM ? '#FFFFFF' : '#FBFBFC',
      workWeek: d.getDay() === 0 ? workWeekForDate(dayIso, workSchedule.value) : null,
    })
  }
  const weeks: (typeof cells)[] = []
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7))
  return { title: `${y} 年 ${m + 1} 月`, weeks }
})

/** 点「还有 N 项」原位展开当天全部事件，再点一次收起。 */
const expandedDay = ref<string | null>(null)
function toggleExpand(dayIso: string) {
  expandedDay.value = expandedDay.value === dayIso ? null : dayIso
}

function shift(d: number) {
  if (viewMode.value === 'year') { calY.value += d; return }
  if (viewMode.value === 'day') {
    const date = new Date(calY.value, calM.value, calD.value + d)
    calY.value = date.getFullYear(); calM.value = date.getMonth(); calD.value = date.getDate()
    return
  }
  if (viewMode.value === 'week') {
    const date = new Date(calY.value, calM.value, calD.value + d * 7)
    calY.value = date.getFullYear(); calM.value = date.getMonth(); calD.value = date.getDate()
    return
  }
  let m = calM.value + d
  let y = calY.value
  if (m < 0) {
    m = 11
    y--
  }
  if (m > 11) {
    m = 0
    y++
  }
  calM.value = m
  calY.value = y
}
function goToday() {
  const d = new Date(`${data.today}T00:00:00`)
  calY.value = d.getFullYear()
  calM.value = d.getMonth()
  calD.value = d.getDate()
}

const weekDays = computed(() => {
  const pivot = new Date(calY.value, calM.value, calD.value)
  const offset = preferences.weekStart === '周日' ? pivot.getDay() : (pivot.getDay() + 6) % 7
  const start = new Date(pivot); start.setDate(pivot.getDate() - offset)
  return Array.from({ length: 7 }, (_, i) => {
    const date = new Date(start); date.setDate(start.getDate() + i)
    const day = iso(date)
    return {
      iso: day,
      label: `${date.getMonth() + 1}月${date.getDate()}日`,
      isToday: day === data.today,
      items: events.value.filter((event) => event.date === day),
      workWeek: date.getDay() === 0 ? workWeekForDate(day, workSchedule.value) : null,
    }
  })
})
const dayView = computed(() => {
  const date = new Date(calY.value, calM.value, calD.value)
  const day = iso(date)
  return [{
    iso: day,
    label: `${date.getMonth() + 1}月${date.getDate()}日`,
    isToday: day === data.today,
    items: events.value.filter((event) => event.date === day),
    workWeek: workWeekForDate(day, workSchedule.value),
  }]
})
const timelineDays = computed(() => viewMode.value === 'day' ? dayView.value : weekDays.value)
const hours = Array.from({ length: 24 }, (_, hour) => hour)
function minutes(value?: string) {
  if (!value) return 0
  const [hour, minute] = value.split(':').map(Number)
  return (hour || 0) * 60 + (minute || 0)
}
function eventStyle(event: CalEvt) {
  const start = minutes(event.time)
  const end = event.endTime ? minutes(event.endTime) : start + 45
  return {
    top: `${start}px`,
    height: `${Math.max(24, end - start)}px`,
    borderColor: event.color,
    background: `color-mix(in srgb, ${event.color} 12%, white)`,
  }
}
const selecting = ref<{ day: string; from: number; to: number } | null>(null)
function startTimeSelection(day: string, hour: number) {
  selecting.value = { day, from: hour, to: hour }
}
function extendTimeSelection(day: string, hour: number) {
  if (selecting.value?.day === day) selecting.value.to = hour
}
function selectionStyle(day: string) {
  const value = selecting.value
  if (!value || value.day !== day) return null
  const from = Math.min(value.from, value.to)
  const to = Math.max(value.from, value.to) + 1
  return { top: `${from * 60}px`, height: `${(to - from) * 60}px` }
}
function finishTimeSelection() {
  const value = selecting.value
  selecting.value = null
  if (!value) return
  const from = Math.min(value.from, value.to)
  const to = Math.max(value.from, value.to) + 1
  const p = (n: number) => String(n).padStart(2, '0')
  ui.openModal('task', '', value.day, `${value.day}T${p(from)}:00`, `${value.day}T${p(to)}:00`)
}
const yearMonths = computed(() => Array.from({ length: 12 }, (_, index) => {
  const prefix = `${calY.value}-${String(index + 1).padStart(2, '0')}`
  return { index, title: `${index + 1} 月`, count: events.value.filter((event) => event.date.startsWith(prefix)).length }
}))
function selectMonth() { viewMode.value = 'month' }

// ---------- CalDAV 配置浮层：账户是个人维度，就近在日历页里配置 ----------
const showCaldavConfig = ref(false)
function onCaldavChanged() {
  loadCaldav()
  loadEvents()
}

/** 双击某天直接建任务，弹窗里的截止日就是这一天。 */
function quickCreate(dayIso: string) {
  ui.openModal('task', '', dayIso)
}

// ---------- 待安排任务：只收未排期与已逾期；按优先级、具体时间排序 ----------
const showBacklog = ref(true)
const backlogFilter = ref<'attention' | 'unplanned' | 'overdue'>('attention')
const unfinishedForMember = computed(() =>
  data.tasks.filter((t) => t.status !== '已完成' && t.owner === calendarMemberId.value))
function isUnplanned(task: Task) {
  return !task.due && !task.startAt
}
function isOverdue(task: Task) {
  return !!task.due && task.due < data.today
}
const backlogCounts = computed(() => ({
  attention: unfinishedForMember.value.filter((t) => isUnplanned(t) || isOverdue(t)).length,
  unplanned: unfinishedForMember.value.filter(isUnplanned).length,
  overdue: unfinishedForMember.value.filter(isOverdue).length,
}))
const backlog = computed(() => {
  const priority = { P0: 0, P1: 1, P2: 2, P3: 3 }
  return unfinishedForMember.value
    .filter((t) => backlogFilter.value === 'unplanned'
      ? isUnplanned(t)
      : backlogFilter.value === 'overdue'
        ? isOverdue(t)
        : isUnplanned(t) || isOverdue(t))
    .map((t) => ({
      id: t.id, title: t.title, pr: t.pr,
      when: t.startAt || t.due || '',
      whenLabel: t.startAt ? t.startAt.replace('T', ' ') : t.due || '未排期',
      projName: data.projectById(t.project)?.name ?? '未关联',
    }))
    .sort((a, b) => priority[a.pr] - priority[b.pr] || (a.when || '9999').localeCompare(b.when || '9999'))
})

const dragTaskId = ref<string | null>(null)
const dragOverDay = ref<string | null>(null)

function onDragStart(taskId: string, ev: DragEvent) {
  dragTaskId.value = taskId
  ev.dataTransfer?.setData('text/plain', taskId)
  if (ev.dataTransfer) ev.dataTransfer.effectAllowed = 'move'
}
function onDragEnd() {
  dragTaskId.value = null
  dragOverDay.value = null
}
function onDropDay(dayIso: string) {
  const id = dragTaskId.value
  dragTaskId.value = null
  dragOverDay.value = null
  if (!id) return
  const t = data.taskById(id)
  if (!t || t.due === dayIso) return
  const hadDue = !!t.due
  const timed = t.startAt ? {
    startAt: `${dayIso}T${t.startAt.slice(11)}`,
    endAt: t.endAt ? `${dayIso}T${t.endAt.slice(11)}` : null,
  } : {}
  data.patchTask(
    id,
    { due: dayIso, ...timed },
    `通过日历${hadDue ? '拖拽调整' : '拖拽排期'}，日期设为 ${dayIso}`,
  )
  toast(`「${t.title}」已排到 ${dayIso}`, 'ri-calendar-check-line')
}
</script>

<template>
  <div class="cal">
    <div class="cal-top">
      <div class="cal-title">日历</div>
      <div class="legend">
        <span v-for="l in legend" :key="l.label" class="leg"><span class="leg-dot" :style="{ background: l.color }"></span>{{ l.label }}</span>
      </div>
      <span v-if="holidayState === 'loading'" class="holiday-status"><i class="ri-loader-4-line spin"></i>节假日加载中</span>
      <button v-else-if="holidayState === 'error'" class="holiday-status error" @click="loadHolidays"><i class="ri-refresh-line"></i>节假日加载失败 · 重试</button>
      <button class="caldav" title="配置 CalDAV 日历账户" @click="showCaldavConfig = true">
        <span class="cd-dot" :style="{ background: caldavState.dot }"></span>{{ caldavState.label }}<i class="ri-settings-3-line"></i>
      </button>
      <button class="work-schedule" title="配置我的大小周" @click="openWorkScheduleConfig">
        <span class="cd-dot" :style="{ background: workScheduleState.dot }"></span>{{ workScheduleState.label }}<i class="ri-settings-3-line"></i>
      </button>
      <label class="member-calendar">
        <i class="ri-user-line"></i>
        <select v-model="calendarMemberId" title="选择要查看的成员日历">
          <option v-for="member in session.members" :key="member.id" :value="member.id">
            {{ member.id === session.currentUser.id ? `我的日历 · ${member.name}` : member.name }}
          </option>
        </select>
      </label>
      <div class="spacer"></div>
      <div class="view-switch"><button :class="{ on: viewMode === 'year' }" @click="viewMode = 'year'">年</button><button :class="{ on: viewMode === 'month' }" @click="viewMode = 'month'">月</button><button :class="{ on: viewMode === 'week' }" @click="viewMode = 'week'">周</button><button :class="{ on: viewMode === 'day' }" @click="viewMode = 'day'">日</button></div>
      <select v-model.number="calY" class="jump-select" title="快速切换年份"><option v-for="y in Array.from({ length: 21 }, (_, i) => now.getFullYear() - 10 + i)" :key="y" :value="y">{{ y }} 年</option></select>
      <select v-if="viewMode !== 'year'" v-model.number="calM" class="jump-select" title="快速切换月份"><option v-for="m in 12" :key="m" :value="m - 1">{{ m }} 月</option></select>
      <button class="nav-btn" @click="shift(-1)"><i class="ri-arrow-left-s-line"></i></button>
      <button class="today-btn" @click="goToday">今天</button>
      <button class="nav-btn" @click="shift(1)"><i class="ri-arrow-right-s-line"></i></button>
    </div>

    <div class="cal-body">
      <div class="cal-card">
        <div class="cal-head">
          <div class="month-title">{{ viewMode === 'year' ? `${calY} 年` : viewMode === 'week' ? `${weekDays[0]?.label} - ${weekDays[6]?.label}` : viewMode === 'day' ? dayView[0]?.label : month.title }}</div>
          <span class="cal-hint">任务、里程碑、节假日与订阅日历合并显示</span>
        </div>
        <template v-if="viewMode === 'month'"><div class="dows">
          <div v-for="d in DOWS" :key="d" class="dow">{{ d }}</div>
        </div>
        <div v-for="(wk, wi) in month.weeks" :key="wi" class="week">
          <div
            v-for="dy in wk"
            :key="dy.iso"
            class="day"
            :class="{ 'drop-target': dragTaskId, 'drop-over': dragOverDay === dy.iso }"
            :style="{ background: dy.bg }"
            title="双击这一天，直接新建以它为截止日的任务"
            @dblclick.self="quickCreate(dy.iso)"
            @dragover.prevent="dragOverDay = dy.iso"
            @dragleave="dragOverDay === dy.iso && (dragOverDay = null)"
            @drop.prevent="onDropDay(dy.iso)"
          >
            <div class="day-heading">
              <div class="day-num" :style="{ background: dy.numBg, color: dy.numColor }" @dblclick="quickCreate(dy.iso)">{{ dy.label }}</div>
              <span v-if="dy.dayMark" class="day-mark" :class="dy.dayMark === '班' ? 'work' : 'rest'">{{ dy.dayMark }}</span>
            </div>
            <div v-if="dy.workWeek" class="work-week-mark" :class="dy.workWeek.type">
              {{ dy.workWeek.label }} · {{ dy.workWeek.restLabel }}
            </div>
            <div class="day-events" @dblclick.self="quickCreate(dy.iso)">
              <button
                v-for="(ev, ei) in dy.items"
                :key="ei"
                class="event"
                :class="{ completed: ev.completed }"
                :title="ev.time ? `${ev.time} ${ev.title}` : ev.title"
                :draggable="!!ev.taskId"
                @click="ev.go?.()"
                @dragstart="ev.taskId && onDragStart(ev.taskId, $event)"
                @dragend="onDragEnd"
              >
                <span class="ev-dot" :style="{ background: ev.color }"></span>
                <span v-if="ev.time" class="ev-time wao-tabular">{{ ev.time }}</span>
                <span class="ev-title">{{ ev.title }}</span>
              </button>
              <button v-if="dy.more" class="day-more" @click="toggleExpand(dy.iso)">{{ dy.more }}</button>
              <button v-else-if="expandedDay === dy.iso && dy.items.length > 3" class="day-more" @click="toggleExpand(dy.iso)">收起</button>
            </div>
          </div>
        </div></template>
        <template v-else-if="viewMode === 'week' || viewMode === 'day'">
          <div class="timeline" :class="{ single: viewMode === 'day' }" @mouseup="finishTimeSelection">
            <div class="time-axis-head"></div>
            <div v-for="(day, index) in timelineDays" :key="`head-${day.iso}`" class="week-date" :class="{ today: day.isToday }">
              {{ viewMode === 'week' ? DOWS[index] : '' }} {{ day.label }}
              <span v-if="day.workWeek" class="timeline-work-week" :class="day.workWeek.type">{{ day.workWeek.label }} · {{ day.workWeek.restLabel }}</span>
            </div>
            <div class="time-axis">
              <span v-for="hour in hours" :key="hour" :style="{ top: `${hour * 60}px` }">{{ String(hour).padStart(2, '0') }}:00</span>
            </div>
            <div
              v-for="day in timelineDays"
              :key="day.iso"
              class="time-day"
            >
              <div
                v-for="hour in hours"
                :key="hour"
                class="time-slot"
                :style="{ top: `${hour * 60}px` }"
                :title="`${day.iso} ${String(hour).padStart(2, '0')}:00，拖拽选择时间段`"
                @mousedown.prevent="startTimeSelection(day.iso, hour)"
                @mouseenter="extendTimeSelection(day.iso, hour)"
              ></div>
              <div v-if="selectionStyle(day.iso)" class="time-selection" :style="selectionStyle(day.iso)!"></div>
              <button
                v-for="(event, eventIndex) in day.items.filter((item) => item.time)"
                :key="eventIndex"
                class="timeline-event"
                :class="{ completed: event.completed }"
                :style="eventStyle(event)"
                @mousedown.stop
                @click="event.go?.()"
              >
                <b>{{ event.time }}<template v-if="event.endTime">–{{ event.endTime }}</template></b>
                <span>{{ event.title }}</span>
              </button>
              <div v-if="day.items.some((item) => !item.time)" class="all-day-strip">
                <button v-for="(event, eventIndex) in day.items.filter((item) => !item.time)" :key="eventIndex" :class="{ completed: event.completed }" @click="event.go?.()">{{ event.title }}</button>
              </div>
            </div>
          </div>
        </template>
        <template v-else><div class="year-view"><button v-for="item in yearMonths" :key="item.index" class="year-month" @click="calM = item.index; selectMonth()"><b>{{ item.title }}</b><span>{{ item.count ? `${item.count} 项日程` : '暂无日程' }}</span></button></div></template>
      </div>

      <!-- 未排期与已逾期任务 -->
      <aside class="backlog" :class="{ folded: !showBacklog }">
        <div class="bl-head">
          <button class="bl-toggle" aria-label="展开或收起待安排任务" @click="showBacklog = !showBacklog">
            <i class="ri-inbox-line"></i>
            <span class="bl-title">待安排任务</span>
            <span class="bl-count wao-tabular">{{ backlog.length }}</span>
            <i class="bl-chev" :class="showBacklog ? 'ri-arrow-down-s-line' : 'ri-arrow-up-s-line'"></i>
          </button>
        </div>
        <template v-if="showBacklog">
          <label class="bl-filter">
            <span>筛选</span>
            <select v-model="backlogFilter">
              <option value="attention">未排期 + 已逾期（{{ backlogCounts.attention }}）</option>
              <option value="unplanned">仅未分配时间（{{ backlogCounts.unplanned }}）</option>
              <option value="overdue">仅逾期未完成（{{ backlogCounts.overdue }}）</option>
            </select>
          </label>
          <div class="bl-hint">按 P0–P3 排序；未排期任务可拖到日期格完成排期</div>
          <div v-if="!backlog.length" class="bl-empty">当前筛选下没有待安排任务</div>
          <div
            v-for="t in backlog"
            :key="t.id"
            class="bl-item"
            :class="{ dragging: dragTaskId === t.id }"
            draggable="true"
            @dragstart="onDragStart(t.id, $event)"
            @dragend="onDragEnd"
            @click="ui.openTask(t.id)"
          >
            <i class="ri-draggable bl-grip"></i>
            <div class="bl-body">
              <div class="bl-name">{{ t.title }}</div>
              <div class="bl-sub">{{ t.pr }} · {{ t.whenLabel }} · {{ t.projName }}</div>
            </div>
          </div>
        </template>
      </aside>
    </div>

    <!-- CalDAV 配置浮层 -->
    <template v-if="showCaldavConfig">
      <div class="cd-mask" @click="showCaldavConfig = false"></div>
      <div class="cd-modal">
        <div class="cd-head">
          <div class="cd-title">日历账户 · CalDAV</div>
          <div class="spacer"></div>
          <button class="cd-close" @click="showCaldavConfig = false"><i class="ri-close-line"></i></button>
        </div>
        <div class="cd-body">
          <CaldavPanel @changed="onCaldavChanged" />
        </div>
      </div>
    </template>

    <!-- 大小周配置浮层 -->
    <template v-if="showWorkScheduleConfig">
      <div class="cd-mask" @click="closeWorkScheduleConfig"></div>
      <div class="work-schedule-modal" role="dialog" aria-modal="true" aria-labelledby="work-schedule-title">
        <div class="cd-head">
          <div id="work-schedule-title" class="cd-title">我的大小周</div>
          <div class="spacer"></div>
          <button class="cd-close" aria-label="关闭大小周配置" :disabled="workScheduleSaving" @click="closeWorkScheduleConfig"><i class="ri-close-line"></i></button>
        </div>
        <div class="work-schedule-body">
          <div v-if="workScheduleLoading" class="work-schedule-state"><i class="ri-loader-4-line spinning"></i>正在读取已保存的大小周配置…</div>
          <div v-else-if="workScheduleLoadError" class="work-schedule-state error">
            <span>读取失败：{{ workScheduleLoadError }}</span>
            <button type="button" @click="retryLoadWorkSchedule">重新读取</button>
          </div>
          <form v-else @submit.prevent="submitWorkSchedule">
            <label class="work-schedule-toggle">
              <span><b>启用大小周</b><small>大周周日上班，小周周六、周日双休</small></span>
              <input v-model="workScheduleDraft.enabled" type="checkbox" />
            </label>
            <fieldset :disabled="!workScheduleDraft.enabled || workScheduleSaving">
              <legend>选择一个已知周作为轮换参照</legend>
              <label class="work-schedule-field">
                <span>参照周内任意一天</span>
                <input v-model="workScheduleDraft.anchorWeek" type="date" required />
              </label>
              <label class="work-schedule-field">
                <span>这一周是</span>
                <select v-model="workScheduleDraft.anchorType">
                  <option value="small">小周（双休）</option>
                  <option value="big">大周（周日上班）</option>
                </select>
              </label>
            </fieldset>
            <p class="work-schedule-help">保存后，日历会按自然周自动向前、向后交替标记；不会创建或修改任何日程。</p>
            <div v-if="workScheduleSaveError" class="work-schedule-state error compact">保存失败：{{ workScheduleSaveError }}。草稿仍在，可直接重试。</div>
            <div class="work-schedule-actions">
              <button type="button" class="work-schedule-cancel" :disabled="workScheduleSaving" @click="closeWorkScheduleConfig">取消</button>
              <button type="submit" class="work-schedule-save" :disabled="workScheduleSaving">
                <i v-if="workScheduleSaving" class="ri-loader-4-line spinning"></i>{{ workScheduleSaving ? '保存中…' : workScheduleSaveError ? '重试保存' : '保存设置' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.cal {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.view-switch { display: inline-flex; padding: 2px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); }
.view-switch button { border: 0; border-radius: 5px; padding: 4px 9px; background: transparent; color: var(--wao-text-3); cursor: pointer; font-size: 12px; }
.view-switch button.on { background: var(--wao-primary); color: #fff; }
.jump-select { height: 30px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); padding: 0 5px; }
.timeline {
  display: grid;
  grid-template-columns: 54px repeat(7, minmax(110px, 1fr));
  min-width: 824px;
  max-height: 680px;
  overflow: auto;
  align-items: start;
}
.timeline.single {
  grid-template-columns: 54px minmax(420px, 1fr);
  min-width: 520px;
}
.time-axis-head, .week-date {
  position: sticky;
  top: 0;
  z-index: 6;
  height: 48px;
  background: var(--wao-bg-sub);
  border-bottom: 1px solid var(--wao-divider);
}
.week-date {
  padding: 10px 6px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-3);
  font-weight: 600;
  border-left: 1px solid var(--wao-divider);
}
.week-date.today { color: var(--wao-primary); }
.time-axis {
  position: relative;
  height: 1440px;
  background: var(--wao-bg-sub);
}
.time-axis span {
  position: absolute;
  left: 4px;
  right: 5px;
  transform: translateY(-7px);
  text-align: right;
  color: var(--wao-text-faint);
  font-size: 10px;
}
.time-day {
  position: relative;
  height: 1440px;
  border-left: 1px solid var(--wao-divider);
  background: var(--wao-bg-card);
}
.time-slot {
  position: absolute;
  left: 0;
  right: 0;
  height: 60px;
  border-top: 1px solid var(--wao-divider-2);
  cursor: crosshair;
}
.time-slot:hover { background: var(--wao-primary-tint); }
.time-selection {
  position: absolute;
  left: 3px;
  right: 3px;
  z-index: 2;
  border: 1px dashed var(--wao-primary);
  border-radius: 6px;
  background: rgba(67, 88, 214, .12);
  pointer-events: none;
}
.timeline-event {
  position: absolute;
  left: 4px;
  right: 4px;
  z-index: 3;
  min-height: 24px;
  overflow: hidden;
  padding: 3px 5px;
  border: 0;
  border-left: 3px solid;
  border-radius: 5px;
  text-align: left;
  color: var(--wao-text-2);
  cursor: pointer;
}
.timeline-event b, .timeline-event span { display: block; font-size: 10.5px; line-height: 1.3; }
.timeline-event span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.timeline-event.completed span {
  color: var(--wao-text-weak);
  text-decoration: line-through;
}
.all-day-strip {
  position: sticky;
  top: 48px;
  z-index: 4;
  display: grid;
  gap: 2px;
  padding: 3px;
}
.all-day-strip button {
  border: 0;
  border-radius: 4px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 10px;
  padding: 2px 4px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.all-day-strip button.completed {
  color: var(--wao-text-weak);
  text-decoration: line-through;
}
.year-view { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; padding: 14px; }.year-month { min-height: 100px; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); color: var(--wao-text); text-align: left; padding: 12px; cursor: pointer; display: flex; flex-direction: column; gap: 8px; }.year-month span { color: var(--wao-text-weak); font-size: 12px; }.year-month:hover { border-color: var(--wao-primary); }
.spacer {
  flex: 1;
}
.cal-top {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.cal-title {
  font-size: 20px;
  font-weight: 700;
}
.legend {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.leg {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--wao-text-3);
}
.leg-dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
}
.holiday-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 0;
  background: transparent;
  color: var(--wao-text-weak);
  font-size: 11px;
}
button.holiday-status { cursor: pointer; }
.holiday-status.error { color: var(--wao-danger); }
.holiday-status .spin { display: inline-block; animation: holiday-spin .9s linear infinite; }
@keyframes holiday-spin { to { transform: rotate(360deg); } }
.caldav, .work-schedule {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12px;
  color: var(--wao-text-2);
  cursor: pointer;
}
.caldav:hover, .work-schedule:hover {
  border-color: #c9cdda;
  background: var(--wao-bg-sub);
}
.cd-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.caldav i, .work-schedule i {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.member-calendar {
  height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 7px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  color: var(--wao-text-3);
  background: var(--wao-bg-card);
}
.member-calendar select {
  max-width: 150px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text-2);
  font-size: 12px;
}
.nav-btn {
  width: 28px;
  height: 28px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.nav-btn:hover {
  background: var(--wao-bg-hover);
}
.nav-btn i {
  font-size: 15px;
}
.today-btn {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-2);
  font-size: 12px;
}
.today-btn:hover {
  background: var(--wao-bg-hover);
}

.cal-body {
  display: flex;
  gap: 14px;
  margin-top: 14px;
  align-items: flex-start;
}
.cal-card {
  flex: 1;
  min-width: 0;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
}
.cal-head {
  display: flex;
  align-items: center;
  padding: 12px 16px;
}
.month-title {
  font-size: 14px;
  font-weight: 600;
}
.cal-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.dows {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-top: 1px solid var(--wao-divider);
  background: var(--wao-bg-sub);
}
.dow {
  padding: 6px 8px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  text-align: center;
}
.week {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-top: 1px solid var(--wao-divider);
}
.day {
  min-height: 104px;
  padding: 6px;
  border-right: 1px solid var(--wao-divider-2);
  overflow: hidden;
}
.day.drop-target {
  outline: 1px dashed #c9cdda;
  outline-offset: -3px;
}
.day.drop-over {
  outline: 2px dashed #4358d6;
  background: var(--wao-primary-tint);
}
.day-num {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}
.day-heading {
  display: flex;
  align-items: center;
  gap: 4px;
}
.day-mark {
  min-width: 17px;
  height: 17px;
  border-radius: 5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 9px;
  font-weight: 700;
}
.day-mark.rest { color: #b73f31; background: #fff0ed; }
.day-mark.work { color: #656b78; background: #eceef2; }
.work-week-mark {
  width: fit-content;
  max-width: 100%;
  margin-top: 3px;
  padding: 1px 5px;
  border-radius: 999px;
  background: #edf8f1;
  color: #25734b;
  font-size: 9.5px;
  line-height: 1.5;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.work-week-mark.big, .timeline-work-week.big { background: #fff1ed; color: #ad4934; }
.timeline-work-week {
  display: block;
  width: fit-content;
  max-width: 100%;
  margin: 2px auto 0;
  padding: 0 4px;
  border-radius: 999px;
  background: #edf8f1;
  color: #25734b;
  font-size: 9px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.day-events {
  margin-top: 3px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.event {
  display: flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: var(--wao-bg-hover);
  border-radius: 5px;
  padding: 2px 6px;
  font-size: 11px;
  color: var(--wao-text-2);
  cursor: pointer;
  width: 100%;
  overflow: hidden;
}
.event:hover {
  background: #edeff5;
}
.event[draggable='true'] {
  cursor: grab;
}
.ev-dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  flex-shrink: 0;
}
.ev-time {
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.ev-title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.event.completed .ev-title {
  color: var(--wao-text-weak);
  text-decoration: line-through;
}
.day-more {
  font-size: 10.5px;
  color: var(--wao-text-weak);
  padding: 0 6px;
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
}
.day-more:hover {
  color: var(--wao-text-2);
}

.backlog {
  width: 240px;
  flex-shrink: 0;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 10px;
  position: sticky;
  top: 16px;
}
.bl-head {
  display: flex;
  align-items: center;
  width: 100%;
}
.bl-toggle {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  border: none;
  background: none;
  cursor: pointer;
  padding: 2px 4px;
  font-size: 13px;
  color: var(--wao-text-1);
}
.bl-toggle i {
  font-size: 15px;
  color: var(--wao-text-3);
}
.bl-filter {
  margin: 7px 4px 2px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--wao-text-weak);
  font-size: 10.5px;
}
.bl-filter select {
  min-width: 0;
  flex: 1;
  height: 30px;
  padding: 0 6px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font-size: 10.5px;
}
.bl-title {
  font-weight: 600;
}
.bl-count {
  font-size: 11.5px;
  background: var(--wao-divider);
  border-radius: 999px;
  padding: 0 7px;
  color: var(--wao-text-3);
}
.bl-chev {
  margin-left: auto;
}
.bl-hint {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  padding: 6px 4px 8px;
  line-height: 1.5;
}
.bl-empty {
  font-size: 12px;
  color: var(--wao-text-weak);
  padding: 12px 4px;
  text-align: center;
}
.bl-item {
  display: flex;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  padding: 7px 8px;
  margin-top: 6px;
  cursor: grab;
  background: var(--wao-bg-card);
}
.bl-item:hover {
  border-color: #c9cdda;
  background: var(--wao-bg-sub);
}
.bl-item.dragging {
  opacity: 0.45;
}
.bl-grip {
  color: var(--wao-text-weak);
  font-size: 14px;
  flex-shrink: 0;
}
.bl-body {
  min-width: 0;
}
.bl-name {
  font-size: 12.5px;
  color: var(--wao-text-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bl-sub {
  font-size: 11px;
  color: var(--wao-text-weak);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (max-width: 900px) {
  .cal-body {
    flex-direction: column-reverse;
  }
  .backlog {
    width: 100%;
    position: static;
  }
}

.cd-mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.cd-modal {
  position: fixed;
  top: 9vh;
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(620px, calc(100vw - 40px));
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  background: var(--wao-bg-page);
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
  overflow: hidden;
}
.work-schedule-modal {
  position: fixed;
  top: 50%;
  left: 50%;
  width: min(480px, calc(100vw - 32px));
  max-height: min(720px, calc(100vh - 32px));
  z-index: 101;
  overflow: auto;
  transform: translate(-50%, -50%);
  border-radius: 14px;
  background: var(--wao-bg-page);
  box-shadow: var(--wao-shadow-modal);
  animation: waoFade 0.15s ease;
}
.work-schedule-body { padding: 4px 20px 20px; }
.work-schedule-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 120px;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.work-schedule-state.error { flex-wrap: wrap; color: #ad4934; }
.work-schedule-state.error button {
  border: 1px solid #e6b7ac;
  border-radius: 7px;
  padding: 5px 9px;
  background: #fff7f5;
  color: #ad4934;
  cursor: pointer;
}
.work-schedule-state.compact { justify-content: flex-start; min-height: 0; margin-top: 12px; }
.work-schedule-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
}
.work-schedule-toggle span, .work-schedule-toggle small { display: block; }
.work-schedule-toggle b { font-size: 14px; }
.work-schedule-toggle small { margin-top: 3px; color: var(--wao-text-weak); font-size: 11.5px; }
.work-schedule-toggle input { width: 18px; height: 18px; accent-color: var(--wao-primary); }
.work-schedule-body fieldset { margin: 16px 0 0; padding: 12px; border: 1px solid var(--wao-line); border-radius: 10px; }
.work-schedule-body fieldset:disabled { opacity: .48; }
.work-schedule-body legend { padding: 0 5px; color: var(--wao-text-3); font-size: 12px; }
.work-schedule-field { display: grid; grid-template-columns: 135px 1fr; align-items: center; gap: 10px; margin-top: 10px; color: var(--wao-text-2); font-size: 12px; }
.work-schedule-field:first-of-type { margin-top: 2px; }
.work-schedule-field input, .work-schedule-field select {
  width: 100%;
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text-1);
}
.work-schedule-help { margin: 12px 2px 0; color: var(--wao-text-weak); font-size: 11.5px; line-height: 1.6; }
.work-schedule-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.work-schedule-actions button { height: 34px; border-radius: 8px; padding: 0 14px; cursor: pointer; }
.work-schedule-actions button:disabled { cursor: not-allowed; opacity: .6; }
.work-schedule-cancel { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); color: var(--wao-text-2); }
.work-schedule-save { border: 0; background: var(--wao-primary); color: #fff; }
.spinning { display: inline-block; animation: waoSpin .8s linear infinite; }
@keyframes waoSpin { to { transform: rotate(360deg); } }
.cd-head {
  display: flex;
  align-items: center;
  padding: 16px 20px 10px;
  flex-shrink: 0;
}
.cd-title {
  font-size: 16px;
  font-weight: 700;
}
.cd-close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.cd-close:hover {
  background: var(--wao-bg-hover-2);
}
.cd-close i {
  font-size: 16px;
}
.cd-body {
  padding: 0 20px 20px;
  overflow-y: auto;
}
</style>
