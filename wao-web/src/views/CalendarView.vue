<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { fetchCaldavAccounts, fetchCaldavEvents, type CaldavAccount, type CaldavEvent } from '@/api/caldav'
import { fetchAlternateWorkWeek, saveAlternateWorkWeek } from '@/api/workSchedule'
import {
  fetchHolidayEvents,
  fetchHolidaySettings,
  saveHolidayReminder,
  saveHolidaySettings,
  type HolidayDayType,
  type HolidayEvent,
  type HolidaySettings,
  type HolidayYearSource,
} from '@/api/holiday'
import {
  createFamilyImportantDate,
  convertCalendarDate,
  deleteFamilyImportantDate,
  fetchFamilyImportantDates,
  updateFamilyImportantDate,
  type FamilyImportantDate,
  type FamilyImportantDateEvent,
  type SaveFamilyImportantDate,
} from '@/api/importantDates'
import { fetchFeishuChats, type FeishuChat } from '@/api/system'
import { toast } from '@/composables/toast'
import { usePreferenceStore } from '@/stores/preferences'
import type { Task } from '@/types'
import AttendancePanel from '@/components/calendar/AttendancePanel.vue'
import type { AttendanceDay } from '@/api/attendance'
import {
  defaultAlternateWorkWeek,
  parseAlternateWorkWeek,
  workWeekForDate,
  type AlternateWorkWeekSchedule,
} from '@/utils/workSchedule'
// 配置面板打开才需要，不进日历页首屏包。
const CaldavPanel = defineAsyncComponent(() => import('@/components/calendar/CaldavPanel.vue'))

/** 日历格子里的一条事件（任务截止 / 里程碑 / CalDAV / 考勤）。 */
interface CalEvt {
  date: string
  title: string
  color: string
  completed?: boolean
  time?: string
  endTime?: string
  taskId?: string
  holidayDayType?: HolidayDayType
  holiday?: HolidayEvent
  importantDate?: FamilyImportantDateEvent
  kind?: string
  sourceLabel?: string
  sortOrder?: number
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
const attendanceMode = ref(localStorage.getItem(`wao-attendance-mode:${session.currentUser.id}`) === 'true')
const attendanceDays = ref<AttendanceDay[]>([])
const selectedAttendanceDate = ref<string | null>(null)
/** 日历默认只看当前人；切换后才加载另一成员的任务，避免跨成员任务默认混在一起。 */
const calendarMemberId = ref(session.currentUser.id)
watch(() => session.currentUser.id, (memberId) => {
  if (!calendarMemberId.value && memberId) calendarMemberId.value = memberId
  attendanceMode.value = localStorage.getItem(`wao-attendance-mode:${memberId}`) === 'true'
}, { immediate: true })
watch(attendanceMode, (enabled) => {
  localStorage.setItem(`wao-attendance-mode:${session.currentUser.id}`, String(enabled))
  if (!enabled) { attendanceDays.value = []; selectedAttendanceDate.value = null }
})
watch(calendarMemberId, (memberId) => {
  if (memberId !== session.currentUser.id) {
    attendanceMode.value = false
    attendanceDays.value = []
  }
})

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
const showWorkWeek = ref(localStorage.getItem(`wao-calendar-work-week-visible:${session.currentUser.id}`) !== 'false')
watch(showWorkWeek, (visible) => localStorage.setItem(`wao-calendar-work-week-visible:${session.currentUser.id}`, String(visible)))
watch(() => session.currentUser.id, (memberId) => {
  showWorkWeek.value = localStorage.getItem(`wao-calendar-work-week-visible:${memberId}`) !== 'false'
})

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
const holidayYears = ref<HolidayYearSource[]>([])
const holidayState = ref<'loading' | 'ready' | 'error'>('loading')
let holidayRequestSequence = 0
const importantDateItems = ref<FamilyImportantDate[]>([])
const importantDateEvents = ref<FamilyImportantDateEvent[]>([])
const importantDateState = ref<'loading' | 'ready' | 'error'>('loading')
const importantDateLoadError = ref('')
const importantDateChannelAvailable = ref(false)
const importantDateChannelHint = ref('正在读取飞书提醒通道状态…')
const importantDateGroupChannelAvailable = ref(false)
const importantDateGroupChannelHint = ref('正在读取飞书群提醒通道状态…')
let importantDateRequestSequence = 0

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

const attendanceRange = computed(() => {
  if (viewMode.value === 'month') {
    return { from: iso(new Date(calY.value, calM.value, 1)), to: iso(new Date(calY.value, calM.value + 1, 0)) }
  }
  const { from, to } = visibleRange()
  return { from: iso(from), to: iso(to) }
})

function toggleAttendanceMode() {
  if (calendarMemberId.value !== session.currentUser.id) {
    toast('考勤是私密数据，请先切回“我的日历”', 'ri-lock-line')
    return
  }
  attendanceMode.value = !attendanceMode.value
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
    holidayEvents.value = result.events
    holidayYears.value = result.years
    holidayState.value = 'ready'
  } catch {
    if (requestSequence !== holidayRequestSequence) return
    holidayEvents.value = []
    holidayYears.value = []
    holidayState.value = 'error'
  }
}

/** 拉取当前成员维护的家庭重要日；旧日期窗响应不能覆盖快速翻页后的结果。 */
async function loadImportantDates() {
  const requestSequence = ++importantDateRequestSequence
  const { from, to } = visibleRange()
  importantDateState.value = 'loading'
  importantDateLoadError.value = ''
  try {
    const result = await fetchFamilyImportantDates(iso(from), iso(to))
    if (requestSequence !== importantDateRequestSequence) return
    importantDateItems.value = result.items
    importantDateEvents.value = result.events
    importantDateChannelAvailable.value = result.reminderChannelAvailable
    importantDateChannelHint.value = result.reminderChannelHint
    importantDateGroupChannelAvailable.value = Boolean(result.groupChannelAvailable)
    importantDateGroupChannelHint.value = result.groupChannelHint || '飞书群提醒通道暂不可用'
    importantDateState.value = 'ready'
  } catch (error) {
    if (requestSequence !== importantDateRequestSequence) return
    importantDateItems.value = []
    importantDateEvents.value = []
    importantDateLoadError.value = error instanceof Error ? error.message : '读取家庭重要日失败'
    importantDateState.value = 'error'
  }
}

function loadCalendarSources() {
  loadEvents()
  loadHolidays()
  loadImportantDates()
}

function iso(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

onMounted(() => {
  loadCaldav()
  loadWorkSchedule()
  loadHolidaySettings()
  loadCalendarSources()
})
watch([calY, calM, calD, viewMode, () => preferences.weekStart], loadCalendarSources)

// ---------- 图例：固定来源 + 每个启用的订阅日历 ----------
const legend = computed(() => {
  const items = [
    { label: '任务截止', color: '#4358D6' },
    { label: '里程碑', color: '#B9821F' },
    { label: '中国节假日', color: '#D9634C' },
  ]
  if ((holidaySettings.value?.selectedInternationalCategories.length ?? 0) > 0
      || holidayEvents.value.some((event) => event.category === 'INTERNATIONAL')) {
    items.push({ label: '国际节日', color: '#8A5BD9' })
  }
  if (holidayEvents.value.some((event) => event.dayType === 'ADJUSTED_WORKDAY')) {
    items.push({ label: '调休上班', color: '#79808F' })
  }
  if (importantDateItems.value.length || importantDateEvents.value.length) {
    items.push({ label: '提醒事项', color: '#D0527F' })
  }
  if (attendanceMode.value) items.push({ label: '我的考勤', color: '#2C8A5B' })
  for (const a of accounts.value) {
    for (const c of a.calendars) {
      if (c.enabled) items.push({ label: c.name, color: c.color })
    }
  }
  return items
})

// ---------- 事件合并、已完成显隐与搜索 ----------
const showCompletedTasks = ref(true)
const events = computed<CalEvt[]>(() => {
  const evs: CalEvt[] = []
  data.tasks
    .filter((t) => t.owner === calendarMemberId.value && (t.due || t.startAt)
      && (showCompletedTasks.value || t.status !== '已完成'))
    .forEach((t) => evs.push({
      date: t.startAt?.slice(0, 10) || t.due,
      title: t.title,
      color: '#4358D6',
      completed: t.status === '已完成',
      time: t.startAt?.slice(11, 16),
      endTime: t.endAt?.slice(11, 16),
      taskId: t.id,
      kind: '任务',
      sourceLabel: data.projectById(t.project)?.name ?? '个人事项',
      go: () => ui.openTask(t.id),
    }))
  data.projects.forEach((p) => {
    p.msDates.forEach((d, i) => {
      const name = p.milestones[i]?.name
      if (d && name) evs.push({ date: d, title: `里程碑 · ${name}`, color: '#B9821F', kind: '里程碑', sourceLabel: p.name, go: () => router.push(`/projects/${p.id}`) })
    })
  })
  caldavEvents.value.forEach((e) => {
    evs.push({
      date: e.start.slice(0, 10),
      title: e.summary || '（无标题）',
      color: e.color,
      time: e.allDay ? '' : e.start.slice(11, 16),
      endTime: e.allDay ? '' : e.end?.slice(11, 16),
      kind: 'CalDAV',
      sourceLabel: e.calendarName,
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
      holiday,
      kind: holiday.name.startsWith('节气 · ') ? '二十四节气' : '节假日',
      sourceLabel: holiday.sourceName,
      go: () => openHolidayDetail(holiday),
    })
  })
  importantDateEvents.value.forEach((importantDate) => {
    evs.push({
      date: importantDate.date,
      title: importantDate.name,
      color: '#D0527F',
      time: importantDate.reminderEnabled && importantDate.reminderDaysBefore === 0
        ? importantDate.reminderTime ?? undefined : undefined,
      importantDate,
      kind: '提醒事项',
      sourceLabel: importantDate.calendarType === 'LUNAR' ? importantDate.lunarDateLabel : importantDate.solarDateLabel,
      go: () => openImportantDateEvent(importantDate),
    })
  })
  if (attendanceMode.value) attendanceDays.value.filter((day) => (day.expectedWorkday && day.date <= data.today) || day.clockInAt || day.clockOutAt || day.dayType === 'LEAVE' || day.manualOverride).forEach((day) => {
    const clockIn = day.clockInAt?.slice(11, 16)
    const clockOut = day.clockOutAt?.slice(11, 16)
    const sourceLabel = day.manualOverride ? '手工模拟' : 'Moka'
    const manualLabel = day.manualOverride ? ' · 模拟' : ''
    const openDay = () => { selectedAttendanceDate.value = day.date }
    const pushAttendance = (title: string, sortOrder: number, color: string, time?: string) => evs.push({
      date: day.date, title, color, time, sortOrder,
      kind: '考勤',
      sourceLabel,
      go: openDay,
    })
    if (day.dayType === 'LEAVE') {
      pushAttendance(`请假 · 不计日均${manualLabel}`, 0, '#B9821F')
      return
    }
    if (day.dayType === 'REST' && !clockIn && !clockOut) {
      pushAttendance(`休息日${manualLabel}`, 0, '#79808F')
      return
    }
    pushAttendance(`${clockIn ? '上班打卡' : '上班 · 缺卡'}${clockIn && day.anomaly ? ` · ${day.anomaly}` : ''}${manualLabel}`, 0, clockIn ? (day.anomaly ? '#D9634C' : '#2C8A5B') : '#D9634C', clockIn)
    pushAttendance(`${clockOut ? '下班打卡' : '下班 · 缺卡'}${manualLabel}`, 1, clockOut ? '#2C8A5B' : '#D9634C', clockOut)
    if (day.workHours !== null) pushAttendance(`当日工时 · ${day.workHours.toFixed(2)} h${manualLabel}`, 2, '#4358D6')
  })
  return evs
})

const calendarSearch = ref('')
const searchPreview = ref<CalEvt | null>(null)
const searchResults = computed(() => {
  const keyword = calendarSearch.value.trim().toLocaleLowerCase()
  if (!keyword) return []
  return events.value.filter((event) => [event.title, event.kind, event.sourceLabel, event.date]
    .some((value) => value?.toLocaleLowerCase().includes(keyword))).slice(0, 30)
})

async function selectSearchResult(event: CalEvt) {
  const date = new Date(`${event.date}T00:00:00`)
  calY.value = date.getFullYear()
  calM.value = date.getMonth()
  calD.value = date.getDate()
  viewMode.value = 'day'
  calendarSearch.value = ''
  await nextTick()
  if (event.go) event.go()
  else searchPreview.value = event
}

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
      const attendancePriority = Number(a.kind !== '考勤') - Number(b.kind !== '考勤')
      const attendanceOrder = (a.sortOrder ?? 99) - (b.sortOrder ?? 99)
      const holidayPriority = Number(!a.holidayDayType) - Number(!b.holidayDayType)
      return attendancePriority || attendanceOrder || holidayPriority || (a.time ?? '').localeCompare(b.time ?? '')
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
      workWeek: showWorkWeek.value && d.getDay() === 0 ? workWeekForDate(dayIso, workSchedule.value) : null,
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
      workWeek: showWorkWeek.value && date.getDay() === 0 ? workWeekForDate(day, workSchedule.value) : null,
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
    workWeek: showWorkWeek.value ? workWeekForDate(day, workSchedule.value) : null,
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
  openCreateChoice(value.day, `${value.day}T${p(from)}:00`, `${value.day}T${p(to)}:00`)
}
const yearMonths = computed(() => Array.from({ length: 12 }, (_, index) => {
  const prefix = `${calY.value}-${String(index + 1).padStart(2, '0')}`
  return { index, title: `${index + 1} 月`, count: events.value.filter((event) => event.date.startsWith(prefix)).length }
}))
function selectMonth() { viewMode.value = 'month' }

// ---------- 国际节日范围与节日详情提醒 ----------
const holidaySettings = ref<HolidaySettings | null>(null)
const holidaySettingsLoading = ref(false)
const holidaySettingsLoadError = ref('')
const showHolidaySettings = ref(false)
const holidaySettingsSaving = ref(false)
const holidaySettingsSaveError = ref('')
const holidayCategoryDraft = ref<string[]>([])
const holidaySettingsDraftKey = computed(() => `wao-holiday-settings-draft:${session.currentUser.id || 'anonymous'}`)
const missingHolidayYears = computed(() => holidayYears.value.filter((year) => !year.officialScheduleAvailable))

async function loadHolidaySettings() {
  holidaySettingsLoading.value = true
  holidaySettingsLoadError.value = ''
  try {
    holidaySettings.value = await fetchHolidaySettings()
  } catch (error) {
    holidaySettingsLoadError.value = error instanceof Error ? error.message : '读取节日设置失败'
  } finally {
    holidaySettingsLoading.value = false
  }
}

function openHolidaySettings() {
  holidaySettingsSaveError.value = ''
  const stored = localStorage.getItem(holidaySettingsDraftKey.value)
  try {
    const parsed = stored ? JSON.parse(stored) : null
    holidayCategoryDraft.value = Array.isArray(parsed)
      ? parsed.filter((item): item is string => typeof item === 'string')
      : [...(holidaySettings.value?.selectedInternationalCategories ?? [])]
  } catch {
    holidayCategoryDraft.value = [...(holidaySettings.value?.selectedInternationalCategories ?? [])]
  }
  showHolidaySettings.value = true
}

function closeHolidaySettings() {
  if (holidaySettingsSaving.value) return
  localStorage.removeItem(holidaySettingsDraftKey.value)
  showHolidaySettings.value = false
}

watch(holidayCategoryDraft, (value) => {
  if (showHolidaySettings.value) localStorage.setItem(holidaySettingsDraftKey.value, JSON.stringify(value))
}, { deep: true })

async function submitHolidaySettings() {
  holidaySettingsSaving.value = true
  holidaySettingsSaveError.value = ''
  try {
    holidaySettings.value = await saveHolidaySettings(holidayCategoryDraft.value)
    localStorage.removeItem(holidaySettingsDraftKey.value)
    showHolidaySettings.value = false
    await loadHolidays()
    toast(holidayCategoryDraft.value.length
      ? `已订阅 ${holidayCategoryDraft.value.length} 类国际节日`
      : '已关闭国际节日，只保留中国节假日', 'ri-calendar-check-line')
  } catch (error) {
    holidaySettingsSaveError.value = error instanceof Error ? error.message : '保存节日设置失败'
  } finally {
    holidaySettingsSaving.value = false
  }
}

const activeHoliday = ref<HolidayEvent | null>(null)
const holidayReminderEnabled = ref(false)
const holidayReminderTime = ref('09:00')
const holidayReminderSaving = ref(false)
const holidayReminderSaveError = ref('')
const holidayReminderDraftKey = computed(() => activeHoliday.value
  ? `wao-holiday-reminder-draft:${session.currentUser.id || 'anonymous'}:${activeHoliday.value.code}`
  : '')

function openHolidayDetail(holiday: HolidayEvent) {
  activeHoliday.value = holiday
  holidayReminderSaveError.value = ''
  const key = `wao-holiday-reminder-draft:${session.currentUser.id || 'anonymous'}:${holiday.code}`
  try {
    const parsed = JSON.parse(localStorage.getItem(key) || 'null') as { enabled?: unknown; time?: unknown } | null
    holidayReminderEnabled.value = typeof parsed?.enabled === 'boolean' ? parsed.enabled : holiday.reminderEnabled
    holidayReminderTime.value = typeof parsed?.time === 'string' ? parsed.time : holiday.reminderTime ?? '09:00'
  } catch {
    holidayReminderEnabled.value = holiday.reminderEnabled
    holidayReminderTime.value = holiday.reminderTime ?? '09:00'
  }
}

function closeHolidayDetail() {
  if (holidayReminderSaving.value) return
  if (holidayReminderDraftKey.value) localStorage.removeItem(holidayReminderDraftKey.value)
  activeHoliday.value = null
}

watch([holidayReminderEnabled, holidayReminderTime], ([enabled, time]) => {
  if (activeHoliday.value && holidayReminderDraftKey.value) {
    localStorage.setItem(holidayReminderDraftKey.value, JSON.stringify({ enabled, time }))
  }
})

async function submitHolidayReminder() {
  const holiday = activeHoliday.value
  if (!holiday) return
  if (holidayReminderEnabled.value && !holidayReminderTime.value) {
    holidayReminderSaveError.value = '开启提醒后请选择提醒时间'
    return
  }
  holidayReminderSaving.value = true
  holidayReminderSaveError.value = ''
  try {
    const saved = await saveHolidayReminder(holiday.code, {
      enabled: holidayReminderEnabled.value,
      reminderTime: holidayReminderEnabled.value ? holidayReminderTime.value : null,
    })
    localStorage.removeItem(holidayReminderDraftKey.value)
    holidayEvents.value = holidayEvents.value.map((event) => event.code === holiday.code
      ? { ...event, reminderEnabled: saved.enabled, reminderTime: saved.reminderTime }
      : event)
    activeHoliday.value = null
    toast(saved.enabled ? `${holiday.name}提醒已设为 ${saved.reminderTime}` : `${holiday.name}提醒已关闭`,
      saved.enabled ? 'ri-notification-3-line' : 'ri-notification-off-line')
  } catch (error) {
    holidayReminderSaveError.value = error instanceof Error ? error.message : '保存提醒失败'
  } finally {
    holidayReminderSaving.value = false
  }
}

// ---------- 家庭重要日：成员级维护、年度日历事件与飞书提醒 ----------
const showImportantDates = ref(false)
const importantDateEditorOpen = ref(false)
const editingImportantDateId = ref<string | null>(null)
const importantDateSaving = ref(false)
const importantDateSaveError = ref('')
const importantDateDeletingId = ref<string | null>(null)
const importantDateDraft = reactive<SaveFamilyImportantDate>({
  name: '',
  eventDate: data.today,
  note: null,
  reminderEnabled: false,
  reminderDaysBefore: 0,
  reminderTime: '09:00',
  calendarType: 'SOLAR',
  lunarMonth: null,
  lunarDay: null,
  lunarLeapMonth: false,
  notificationType: 'DIRECT',
  chatId: null,
  chatName: null,
})
const lunarYear = ref(new Date(`${data.today}T00:00:00`).getFullYear())
const dateConversionLabel = ref('')
const dateConversionState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const dateConversionError = ref('')
let dateConversionSequence = 0
const feishuChats = ref<FeishuChat[]>([])
const feishuChatsState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const feishuChatsError = ref('')
const importantDateDraftKey = computed(() =>
  `wao-family-important-date-draft:${session.currentUser.id || 'anonymous'}:${editingImportantDateId.value || 'new'}`)

function copyImportantDateDraft(value?: FamilyImportantDate) {
  importantDateDraft.name = value?.name ?? ''
  importantDateDraft.eventDate = value?.eventDate ?? data.today
  importantDateDraft.note = value?.note ?? null
  importantDateDraft.reminderEnabled = value?.reminderEnabled ?? false
  importantDateDraft.reminderDaysBefore = value?.reminderDaysBefore ?? 0
  importantDateDraft.reminderTime = value?.reminderTime ?? '09:00'
  importantDateDraft.calendarType = value?.calendarType ?? 'SOLAR'
  importantDateDraft.lunarMonth = value?.lunarMonth ?? null
  importantDateDraft.lunarDay = value?.lunarDay ?? null
  importantDateDraft.lunarLeapMonth = value?.lunarLeapMonth ?? false
  importantDateDraft.notificationType = value?.notificationType ?? 'DIRECT'
  importantDateDraft.chatId = value?.chatId ?? null
  importantDateDraft.chatName = value?.chatName ?? null
  lunarYear.value = new Date(`${value?.eventDate ?? data.today}T00:00:00`).getFullYear()
}

function restoreImportantDateDraft(value?: FamilyImportantDate) {
  copyImportantDateDraft(value)
  try {
    const parsed = JSON.parse(localStorage.getItem(importantDateDraftKey.value) || 'null') as (Partial<SaveFamilyImportantDate> & { lunarYear?: number }) | null
    if (!parsed) return
    if (typeof parsed.name === 'string') importantDateDraft.name = parsed.name
    if (typeof parsed.eventDate === 'string') importantDateDraft.eventDate = parsed.eventDate
    if (typeof parsed.note === 'string' || parsed.note === null) importantDateDraft.note = parsed.note
    if (typeof parsed.reminderEnabled === 'boolean') importantDateDraft.reminderEnabled = parsed.reminderEnabled
    if (typeof parsed.reminderDaysBefore === 'number') importantDateDraft.reminderDaysBefore = parsed.reminderDaysBefore
    if (typeof parsed.reminderTime === 'string') importantDateDraft.reminderTime = parsed.reminderTime
    if (parsed.calendarType === 'SOLAR' || parsed.calendarType === 'LUNAR') importantDateDraft.calendarType = parsed.calendarType
    if (typeof parsed.lunarMonth === 'number') importantDateDraft.lunarMonth = parsed.lunarMonth
    if (typeof parsed.lunarDay === 'number') importantDateDraft.lunarDay = parsed.lunarDay
    if (typeof parsed.lunarLeapMonth === 'boolean') importantDateDraft.lunarLeapMonth = parsed.lunarLeapMonth
    if (parsed.notificationType === 'DIRECT' || parsed.notificationType === 'GROUP') importantDateDraft.notificationType = parsed.notificationType
    if (typeof parsed.chatId === 'string' || parsed.chatId === null) importantDateDraft.chatId = parsed.chatId
    if (typeof parsed.chatName === 'string' || parsed.chatName === null) importantDateDraft.chatName = parsed.chatName
    if (typeof parsed.lunarYear === 'number') lunarYear.value = parsed.lunarYear
  } catch {
    copyImportantDateDraft(value)
  }
}

function openImportantDateManager() {
  editingImportantDateId.value = null
  importantDateEditorOpen.value = false
  importantDateSaveError.value = ''
  showImportantDates.value = true
  loadFeishuChats()
}

function closeImportantDateManager() {
  if (importantDateSaving.value || importantDateDeletingId.value) return
  showImportantDates.value = false
  editingImportantDateId.value = null
  importantDateEditorOpen.value = false
  importantDateSaveError.value = ''
}

function startNewImportantDate(presetDate?: string, presetTime?: string) {
  editingImportantDateId.value = null
  importantDateEditorOpen.value = true
  importantDateSaveError.value = ''
  restoreImportantDateDraft()
  if (typeof presetDate === 'string') importantDateDraft.eventDate = presetDate
  if (presetTime) importantDateDraft.reminderTime = presetTime
  void refreshDateConversion()
}

function editImportantDate(value: FamilyImportantDate) {
  editingImportantDateId.value = value.id
  importantDateEditorOpen.value = true
  importantDateSaveError.value = ''
  restoreImportantDateDraft(value)
  void refreshDateConversion()
}

async function loadFeishuChats() {
  if (!importantDateGroupChannelAvailable.value || feishuChatsState.value === 'loading') return
  feishuChatsState.value = 'loading'
  feishuChatsError.value = ''
  try {
    feishuChats.value = await fetchFeishuChats()
    feishuChatsState.value = 'ready'
  } catch (error) {
    feishuChats.value = []
    feishuChatsError.value = error instanceof Error ? error.message : '读取飞书群失败'
    feishuChatsState.value = 'error'
  }
}

function selectReminderChat() {
  const chat = feishuChats.value.find((item) => item.chatId === importantDateDraft.chatId)
  importantDateDraft.chatName = chat?.name ?? null
}

async function refreshDateConversion() {
  const sequence = ++dateConversionSequence
  dateConversionState.value = 'loading'
  dateConversionError.value = ''
  try {
    const converted = importantDateDraft.calendarType === 'LUNAR'
      ? await convertCalendarDate({
          calendarType: 'LUNAR', lunarYear: lunarYear.value,
          lunarMonth: importantDateDraft.lunarMonth ?? undefined,
          lunarDay: importantDateDraft.lunarDay ?? undefined,
          lunarLeapMonth: importantDateDraft.lunarLeapMonth,
        })
      : await convertCalendarDate({ calendarType: 'SOLAR', solarDate: importantDateDraft.eventDate })
    if (sequence !== dateConversionSequence) return
    importantDateDraft.eventDate = converted.solarDate
    importantDateDraft.lunarMonth = converted.lunarMonth
    importantDateDraft.lunarDay = converted.lunarDay
    importantDateDraft.lunarLeapMonth = converted.lunarLeapMonth
    lunarYear.value = converted.lunarYear
    dateConversionLabel.value = importantDateDraft.calendarType === 'LUNAR'
      ? `对应公历：${converted.solarLabel}` : `对应农历：${converted.lunarLabel}`
    dateConversionState.value = 'ready'
  } catch (error) {
    if (sequence !== dateConversionSequence) return
    dateConversionLabel.value = ''
    dateConversionError.value = error instanceof Error ? error.message : '日期转换失败'
    dateConversionState.value = 'error'
  }
}

function switchCalendarType(type: 'SOLAR' | 'LUNAR') {
  importantDateDraft.calendarType = type
  void refreshDateConversion()
}

function openImportantDateEvent(event: FamilyImportantDateEvent) {
  showImportantDates.value = true
  const item = importantDateItems.value.find((value) => value.id === event.importantDateId)
  if (item) editImportantDate(item)
}

function backToImportantDateList() {
  if (importantDateSaving.value) return
  editingImportantDateId.value = null
  importantDateEditorOpen.value = false
  importantDateSaveError.value = ''
}

watch(importantDateDraft, (value) => {
  if (showImportantDates.value && importantDateEditorOpen.value
      && (editingImportantDateId.value || value.name || value.note)) {
    localStorage.setItem(importantDateDraftKey.value, JSON.stringify({ ...value, lunarYear: lunarYear.value }))
  }
}, { deep: true })
watch(lunarYear, () => {
  if (showImportantDates.value && importantDateEditorOpen.value) {
    localStorage.setItem(importantDateDraftKey.value,
      JSON.stringify({ ...importantDateDraft, lunarYear: lunarYear.value }))
  }
})

async function submitImportantDate() {
  if (!importantDateDraft.name.trim()) {
    importantDateSaveError.value = '请填写重要日名称'
    return
  }
  if (!importantDateDraft.eventDate) {
    importantDateSaveError.value = '请选择日期'
    return
  }
  if (dateConversionState.value === 'loading' || dateConversionState.value === 'error') {
    importantDateSaveError.value = dateConversionState.value === 'loading' ? '请等待日期转换完成' : dateConversionError.value
    return
  }
  if (importantDateDraft.reminderEnabled && !importantDateDraft.reminderTime) {
    importantDateSaveError.value = '开启飞书提醒后请选择时间'
    return
  }
  if (importantDateDraft.reminderEnabled && importantDateDraft.notificationType === 'GROUP'
      && (!importantDateDraft.chatId || !importantDateDraft.chatName)) {
    importantDateSaveError.value = '请选择要发送提醒的飞书群'
    return
  }
  importantDateSaving.value = true
  importantDateSaveError.value = ''
  const draftKey = importantDateDraftKey.value
  const payload: SaveFamilyImportantDate = {
    ...importantDateDraft,
    name: importantDateDraft.name.trim(),
    note: importantDateDraft.note?.trim() || null,
    reminderTime: importantDateDraft.reminderEnabled ? importantDateDraft.reminderTime : null,
  }
  try {
    if (editingImportantDateId.value) {
      await updateFamilyImportantDate(editingImportantDateId.value, payload)
    } else {
      await createFamilyImportantDate(payload)
    }
    localStorage.removeItem(draftKey)
    const name = payload.name
    editingImportantDateId.value = null
    importantDateEditorOpen.value = false
    await loadImportantDates()
    toast(`${name}已保存，并会每年显示在日历中`, 'ri-heart-3-line')
  } catch (error) {
    importantDateSaveError.value = error instanceof Error ? error.message : '保存家庭重要日失败'
  } finally {
    importantDateSaving.value = false
  }
}

async function removeImportantDate(value: FamilyImportantDate) {
  if (!window.confirm(`删除“${value.name}”？日历事件和飞书提醒也会一并删除。`)) return
  importantDateDeletingId.value = value.id
  importantDateSaveError.value = ''
  try {
    await deleteFamilyImportantDate(value.id)
    localStorage.removeItem(`wao-family-important-date-draft:${session.currentUser.id || 'anonymous'}:${value.id}`)
    if (editingImportantDateId.value === value.id) {
      editingImportantDateId.value = null
      importantDateEditorOpen.value = false
    }
    await loadImportantDates()
    toast(`${value.name}已删除`, 'ri-delete-bin-line')
  } catch (error) {
    importantDateSaveError.value = error instanceof Error ? error.message : '删除家庭重要日失败'
  } finally {
    importantDateDeletingId.value = null
  }
}

function importantDateMonthDay(value: string) {
  const [, month, day] = value.split('-')
  return `${Number(month)} 月 ${Number(day)} 日`
}

function importantDateReminderLabel(value: FamilyImportantDate) {
  if (!value.reminderEnabled) return '仅在日历显示'
  const before = value.reminderDaysBefore ? `提前 ${value.reminderDaysBefore} 天` : '当天'
  const target = value.notificationType === 'GROUP' ? `群聊「${value.chatName}」` : '飞书私聊'
  return `${before} ${value.reminderTime} ${target}提醒`
}

// ---------- CalDAV 配置浮层：账户是个人维度，就近在日历页里配置 ----------
const showCaldavConfig = ref(false)
function onCaldavChanged() {
  loadCaldav()
  loadEvents()
}

const showCreateChoice = ref(false)
const createPreset = reactive({ day: data.today, startAt: '', endAt: '' })

/** 日历创建先选任务或提醒事项，避免把没有行动责任的纪念日塞进个人任务。 */
function openCreateChoice(dayIso: string, startAt = '', endAt = '') {
  createPreset.day = dayIso
  createPreset.startAt = startAt
  createPreset.endAt = endAt
  showCreateChoice.value = true
}

function quickCreate(dayIso: string) {
  openCreateChoice(dayIso)
}

function continueCreateTask() {
  showCreateChoice.value = false
  ui.openModal('task', '', createPreset.day, createPreset.startAt, createPreset.endAt)
}

function continueCreateReminder() {
  showCreateChoice.value = false
  openImportantDateManager()
  startNewImportantDate(createPreset.day, createPreset.startAt?.slice(11, 16) || '09:00')
}

// ---------- 待安排任务：只收未排期与已逾期；按优先级、具体时间排序 ----------
const showBacklog = ref(localStorage.getItem(`wao-calendar-backlog-visible:${session.currentUser.id}`) !== 'false')
watch(showBacklog, (visible) => localStorage.setItem(`wao-calendar-backlog-visible:${session.currentUser.id}`, String(visible)))
watch(() => session.currentUser.id, (memberId) => {
  showBacklog.value = localStorage.getItem(`wao-calendar-backlog-visible:${memberId}`) !== 'false'
})
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
      <div class="cal-heading-row">
        <div class="cal-title">日历</div>
        <div class="legend">
          <span v-for="l in legend" :key="l.label" class="leg"><span class="leg-dot" :style="{ background: l.color }"></span>{{ l.label }}</span>
        </div>
        <span v-if="holidayState === 'loading'" class="holiday-status"><i class="ri-loader-4-line spin"></i>节假日加载中</span>
        <button v-else-if="holidayState === 'error'" class="holiday-status error" @click="loadHolidays"><i class="ri-refresh-line"></i>节假日加载失败 · 重试</button>
        <div class="spacer"></div>
        <div class="view-switch"><button :class="{ on: viewMode === 'year' }" @click="viewMode = 'year'">年</button><button :class="{ on: viewMode === 'month' }" @click="viewMode = 'month'">月</button><button :class="{ on: viewMode === 'week' }" @click="viewMode = 'week'">周</button><button :class="{ on: viewMode === 'day' }" @click="viewMode = 'day'">日</button></div>
        <select v-model.number="calY" class="jump-select" title="快速切换年份"><option v-for="y in Array.from({ length: 21 }, (_, i) => now.getFullYear() - 10 + i)" :key="y" :value="y">{{ y }} 年</option></select>
        <select v-if="viewMode !== 'year'" v-model.number="calM" class="jump-select" title="快速切换月份"><option v-for="m in 12" :key="m" :value="m - 1">{{ m }} 月</option></select>
        <button class="nav-btn" @click="shift(-1)"><i class="ri-arrow-left-s-line"></i></button>
        <button class="today-btn" @click="goToday">今天</button>
        <button class="nav-btn" @click="shift(1)"><i class="ri-arrow-right-s-line"></i></button>
      </div>
      <div class="cal-control-row">
        <label class="member-calendar"><i class="ri-user-line"></i><select v-model="calendarMemberId" title="选择要查看的成员日历"><option v-for="member in session.members" :key="member.id" :value="member.id">{{ member.id === session.currentUser.id ? `我的日历 · ${member.name}` : member.name }}</option></select></label>
        <div class="calendar-search"><i class="ri-search-line"></i><input v-model="calendarSearch" type="search" aria-label="搜索日历事项" placeholder="搜索任务、考勤、提醒、节日…" @keydown.esc="calendarSearch = ''" /><div v-if="calendarSearch.trim()" class="calendar-search-results"><button v-for="(result, index) in searchResults" :key="`${result.date}-${result.title}-${index}`" type="button" @click="selectSearchResult(result)"><span class="search-result-date">{{ result.date }}</span><span><b>{{ result.title }}</b><small>{{ result.kind }}<template v-if="result.sourceLabel"> · {{ result.sourceLabel }}</template></small></span></button><div v-if="!searchResults.length" class="calendar-search-empty">没有找到匹配事项</div></div></div>
        <div class="spacer"></div>
        <details class="calendar-menu calendar-display-menu"><summary><i class="ri-eye-line"></i>显示</summary><div class="calendar-menu-popover">
          <button class="work-week-visibility" :class="{ on: showWorkWeek }" type="button" @click="showWorkWeek = !showWorkWeek"><i :class="showWorkWeek ? 'ri-eye-line' : 'ri-eye-off-line'"></i><span><b>大小周标记</b><small>只控制日历显示，不关闭配置</small></span><em>{{ showWorkWeek ? '显示' : '隐藏' }}</em></button>
          <button class="attendance-toggle" :class="{ on: attendanceMode }" type="button" @click="toggleAttendanceMode"><i :class="attendanceMode ? 'ri-eye-line' : 'ri-eye-off-line'"></i><span><b>我的考勤</b><small>上下班、工时与异常</small></span><em>{{ attendanceMode ? '显示' : '隐藏' }}</em></button>
          <button class="completed-toggle" :class="{ on: showCompletedTasks }" type="button" @click="showCompletedTasks = !showCompletedTasks"><i :class="showCompletedTasks ? 'ri-eye-line' : 'ri-eye-off-line'"></i><span><b>{{ showCompletedTasks ? '隐藏已完成任务' : '显示已完成任务' }}</b><small>保留或隐藏历史完成项</small></span><em>{{ showCompletedTasks ? '显示中' : '已隐藏' }}</em></button>
        </div></details>
        <details class="calendar-menu calendar-settings-menu"><summary><i class="ri-settings-3-line"></i>日历设置</summary><div class="calendar-menu-popover">
          <button class="holiday-settings-entry" type="button" @click="openHolidaySettings"><i class="ri-earth-line"></i><span><b>节日设置</b><small>{{ holidaySettings?.selectedInternationalCategories.length ?? 0 }} 类国际节日</small></span></button>
          <button class="important-date-entry" type="button" @click="openImportantDateManager"><i :class="importantDateState === 'loading' ? 'ri-loader-4-line spinning' : importantDateState === 'error' ? 'ri-error-warning-line' : 'ri-heart-3-line'"></i><span><b>提醒事项</b><small>{{ importantDateState === 'error' ? '读取失败' : `${importantDateItems.length} 个` }}</small></span></button>
          <button class="caldav" type="button" @click="showCaldavConfig = true"><span class="cd-dot" :style="{ background: caldavState.dot }"></span><span><b>CalDAV</b><small>{{ caldavState.label }}</small></span></button>
          <button class="work-schedule" type="button" @click="openWorkScheduleConfig"><span class="cd-dot" :style="{ background: workScheduleState.dot }"></span><span><b>大小周配置</b><small>{{ workScheduleState.label }}</small></span></button>
        </div></details>
      </div>
    </div>

    <div class="cal-body">
      <div class="cal-card">
        <div class="cal-head">
          <div class="month-title">{{ viewMode === 'year' ? `${calY} 年` : viewMode === 'week' ? `${weekDays[0]?.label} - ${weekDays[6]?.label}` : viewMode === 'day' ? dayView[0]?.label : month.title }}</div>
          <span class="cal-hint">任务、提醒事项、二十四节气、节假日与订阅日历合并显示</span>
        </div>
        <AttendancePanel
          :active="attendanceMode"
          :from="attendanceRange.from"
          :to="attendanceRange.to"
          :selected-date="selectedAttendanceDate"
          @loaded="attendanceDays = $event"
          @close-day="selectedAttendanceDate = null"
        />
        <div v-if="missingHolidayYears.length" class="holiday-source-warning">
          <i class="ri-information-line"></i>
          <span>{{ missingHolidayYears.map((year) => `${year.year} 年`).join('、') }}法定放假调休安排尚未收录；当前只显示可由历法确定的节日本日，不推测调休。</span>
          <a :href="missingHolidayYears[0]!.sourceUrl" target="_blank" rel="noopener">查看历法来源</a>
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
            title="双击这一天，选择新建任务或提醒事项"
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
          <button class="bl-toggle" type="button" :aria-expanded="showBacklog" :aria-label="showBacklog ? '收起待安排任务' : `展开待安排任务，共 ${backlog.length} 项`" :title="showBacklog ? '收起待安排任务' : `展开待安排任务 · ${backlog.length} 项`" @click="showBacklog = !showBacklog">
            <i class="ri-inbox-line"></i>
            <span class="bl-title">待安排任务</span>
            <span class="bl-count wao-tabular">{{ backlog.length }}</span>
            <i class="bl-chev" :class="showBacklog ? 'ri-arrow-down-s-line' : 'ri-arrow-up-s-line'"></i>
          </button>
        </div>
        <template v-if="showBacklog">
          <label class="bl-filter">
            <span class="compactible-control-label">筛选</span>
            <select v-model="backlogFilter" aria-label="筛选待安排任务" title="筛选待安排任务">
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

    <template v-if="showCreateChoice">
      <div class="cd-mask" @click="showCreateChoice = false"></div>
      <div class="create-choice-modal" role="dialog" aria-modal="true" aria-labelledby="create-choice-title">
        <div class="cd-head">
          <div id="create-choice-title" class="cd-title">在 {{ createPreset.day }} 创建</div>
          <div class="spacer"></div>
          <button class="cd-close" aria-label="关闭创建类型选择" @click="showCreateChoice = false"><i class="ri-close-line"></i></button>
        </div>
        <div class="create-choice-grid">
          <button type="button" @click="continueCreateTask"><i class="ri-checkbox-circle-line"></i><b>任务</b><span>有负责人、项目和完成状态，继续进入个人事项。</span></button>
          <button type="button" @click="continueCreateReminder"><i class="ri-notification-3-line"></i><b>提醒事项</b><span>独立保存在日历提醒中，不进入个人任务列表。</span></button>
        </div>
      </div>
    </template>

    <template v-if="searchPreview">
      <div class="cd-mask" @click="searchPreview = null"></div>
      <div class="search-preview-modal" role="dialog" aria-modal="true" aria-labelledby="search-preview-title">
        <div class="cd-head"><div id="search-preview-title" class="cd-title">{{ searchPreview.title }}</div><div class="spacer"></div><button class="cd-close" aria-label="关闭事项预览" @click="searchPreview = null"><i class="ri-close-line"></i></button></div>
        <dl class="holiday-facts">
          <div><dt>日期</dt><dd>{{ searchPreview.date }}</dd></div>
          <div><dt>类型</dt><dd>{{ searchPreview.kind || '日历事项' }}</dd></div>
          <div v-if="searchPreview.time"><dt>时间</dt><dd>{{ searchPreview.time }}<template v-if="searchPreview.endTime">–{{ searchPreview.endTime }}</template></dd></div>
          <div v-if="searchPreview.sourceLabel"><dt>来源</dt><dd>{{ searchPreview.sourceLabel }}</dd></div>
        </dl>
      </div>
    </template>

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

    <!-- 日历提醒事项维护浮层 -->
    <template v-if="showImportantDates">
      <div class="cd-mask" @click="closeImportantDateManager"></div>
      <div class="important-date-modal" role="dialog" aria-modal="true" aria-labelledby="important-date-title">
        <div class="cd-head">
          <button v-if="importantDateEditorOpen" class="important-date-back" type="button" :disabled="importantDateSaving" @click="backToImportantDateList">
            <i class="ri-arrow-left-line"></i><span>返回</span>
          </button>
          <div id="important-date-title" class="cd-title">{{ importantDateEditorOpen ? (editingImportantDateId ? '编辑提醒事项' : '新增提醒事项') : '日历提醒事项' }}</div>
          <div class="spacer"></div>
          <button class="cd-close" aria-label="关闭日历提醒事项" :disabled="importantDateSaving || !!importantDateDeletingId" @click="closeImportantDateManager"><i class="ri-close-line"></i></button>
        </div>
        <div class="important-date-body">
          <form v-if="importantDateEditorOpen" class="important-date-form" @submit.prevent="submitImportantDate">
            <p class="important-date-intro">事项独立保存在日历中，不进入个人任务；按所选公历或农历日期每年显示，并可提前通知。</p>
            <label class="important-date-field">
              <span>名称</span>
              <input v-model="importantDateDraft.name" type="text" maxlength="64" required placeholder="例如：妈妈生日、合同续费日" :disabled="importantDateSaving" />
            </label>
            <div class="calendar-type-switch" role="group" aria-label="录入历法">
              <button type="button" :class="{ on: importantDateDraft.calendarType === 'SOLAR' }" @click="switchCalendarType('SOLAR')">公历录入</button>
              <button type="button" :class="{ on: importantDateDraft.calendarType === 'LUNAR' }" @click="switchCalendarType('LUNAR')">农历录入</button>
            </div>
            <label v-if="importantDateDraft.calendarType === 'SOLAR'" class="important-date-field">
              <span>公历日期</span>
              <input v-model="importantDateDraft.eventDate" type="date" required :disabled="importantDateSaving" @change="refreshDateConversion" />
              <small>从所选年份起按公历月日每年重复；2 月 29 日只在闰年显示。</small>
            </label>
            <div v-else class="lunar-date-fields">
              <label class="important-date-field"><span>农历年</span><input v-model.number="lunarYear" type="number" min="1900" max="2100" required @change="refreshDateConversion" /></label>
              <label class="important-date-field"><span>月</span><select v-model.number="importantDateDraft.lunarMonth" required @change="refreshDateConversion"><option :value="null" disabled>请选择</option><option v-for="monthNumber in 12" :key="monthNumber" :value="monthNumber">{{ monthNumber }} 月</option></select></label>
              <label class="important-date-field"><span>日</span><select v-model.number="importantDateDraft.lunarDay" required @change="refreshDateConversion"><option :value="null" disabled>请选择</option><option v-for="dayNumber in 30" :key="dayNumber" :value="dayNumber">{{ dayNumber }} 日</option></select></label>
              <label class="lunar-leap-toggle"><input v-model="importantDateDraft.lunarLeapMonth" type="checkbox" @change="refreshDateConversion" />闰月</label>
            </div>
            <div class="date-conversion" :class="dateConversionState">
              <i :class="dateConversionState === 'loading' ? 'ri-loader-4-line spinning' : dateConversionState === 'error' ? 'ri-error-warning-line' : 'ri-swap-line'"></i>
              {{ dateConversionState === 'loading' ? '正在换算另一种历法…' : dateConversionState === 'error' ? dateConversionError : dateConversionLabel }}
            </div>
            <label class="important-date-field">
              <span>备注（可选）</span>
              <textarea v-model="importantDateDraft.note" maxlength="200" rows="3" placeholder="例如：喜欢百合花，记得提前订餐" :disabled="importantDateSaving"></textarea>
            </label>
            <label class="important-date-reminder-toggle">
              <span><b>飞书通知</b><small>{{ importantDateDraft.notificationType === 'GROUP' ? importantDateGroupChannelHint : importantDateChannelHint }}</small></span>
              <input v-model="importantDateDraft.reminderEnabled" type="checkbox" :disabled="importantDateSaving || (!(importantDateDraft.notificationType === 'GROUP' ? importantDateGroupChannelAvailable : importantDateChannelAvailable) && !importantDateDraft.reminderEnabled)" />
            </label>
            <div class="calendar-type-switch notification-type" role="group" aria-label="通知方式">
              <button type="button" :class="{ on: importantDateDraft.notificationType === 'DIRECT' }" @click="importantDateDraft.notificationType = 'DIRECT'">私聊我</button>
              <button type="button" :class="{ on: importantDateDraft.notificationType === 'GROUP' }" @click="importantDateDraft.notificationType = 'GROUP'; loadFeishuChats()">群聊并 @ 我</button>
            </div>
            <label v-if="importantDateDraft.notificationType === 'GROUP'" class="important-date-field">
              <span>目标飞书群</span>
              <select v-model="importantDateDraft.chatId" :disabled="feishuChatsState === 'loading' || !importantDateGroupChannelAvailable" required @change="selectReminderChat">
                <option :value="null" disabled>{{ feishuChatsState === 'loading' ? '正在读取机器人所在群…' : '请选择群聊' }}</option>
                <option v-for="chat in feishuChats" :key="chat.chatId" :value="chat.chatId">{{ chat.name }}</option>
              </select>
              <small v-if="feishuChatsState === 'error'" class="field-error">读取失败：{{ feishuChatsError }}。<button type="button" @click="loadFeishuChats">重试</button></small>
              <small v-else>群列表实时来自飞书；发送时会通过 Union ID 重新确认并只 @ 配置人。</small>
            </label>
            <div class="important-date-reminder-fields">
              <label class="important-date-field">
                <span>提前天数</span>
                <input v-model.number="importantDateDraft.reminderDaysBefore" type="number" min="0" max="30" required :disabled="importantDateSaving || !importantDateDraft.reminderEnabled" />
                <small>0 表示当天提醒，最多提前 30 天。</small>
              </label>
              <label class="important-date-field">
                <span>提醒时间</span>
                <input v-model="importantDateDraft.reminderTime" type="time" required :disabled="importantDateSaving || !importantDateDraft.reminderEnabled" />
                <small>按上海时区发送。</small>
              </label>
            </div>
            <div v-if="importantDateDraft.reminderEnabled && !(importantDateDraft.notificationType === 'GROUP' ? importantDateGroupChannelAvailable : importantDateChannelAvailable)" class="work-schedule-state error compact">{{ importantDateDraft.notificationType === 'GROUP' ? importantDateGroupChannelHint : importantDateChannelHint }}</div>
            <div v-if="importantDateSaveError" class="work-schedule-state error compact">保存失败：{{ importantDateSaveError }}。草稿已保留，可直接重试。</div>
            <div class="work-schedule-actions">
              <button type="button" class="work-schedule-cancel" :disabled="importantDateSaving" @click="backToImportantDateList">返回列表</button>
              <button type="submit" class="work-schedule-save" :disabled="importantDateSaving || dateConversionState === 'loading' || (importantDateDraft.reminderEnabled && !(importantDateDraft.notificationType === 'GROUP' ? importantDateGroupChannelAvailable : importantDateChannelAvailable))">
                <i v-if="importantDateSaving" class="ri-loader-4-line spinning"></i>{{ importantDateSaving ? '保存中…' : importantDateSaveError ? '重试保存' : '保存提醒事项' }}
              </button>
            </div>
          </form>
          <template v-else>
            <div class="important-date-list-head">
              <div>
                <b>单独管理无需完成状态的日期提醒</b>
                <span>这里只维护当前账号的提醒事项，任务仍在个人事项中。</span>
              </div>
              <button type="button" @click="startNewImportantDate()"><i class="ri-add-line"></i>新增提醒事项</button>
            </div>
            <div v-if="importantDateState === 'loading'" class="work-schedule-state"><i class="ri-loader-4-line spinning"></i>正在读取已保存的重要日…</div>
            <div v-else-if="importantDateState === 'error'" class="work-schedule-state error">
              <span>读取失败：{{ importantDateLoadError }}</span>
              <button type="button" @click="loadImportantDates">重新读取</button>
            </div>
            <div v-else-if="!importantDateItems.length" class="important-date-empty">
              <i class="ri-heart-add-line"></i>
              <b>还没有提醒事项</b>
              <span>新增后会按所选公历或农历日期每年显示在日历里。</span>
              <button type="button" @click="startNewImportantDate()">新增第一个提醒事项</button>
            </div>
            <div v-else class="important-date-list">
              <article v-for="item in importantDateItems" :key="item.id" class="important-date-item">
                <button class="important-date-main" type="button" @click="editImportantDate(item)">
                  <span class="important-date-icon"><i class="ri-heart-3-line"></i></span>
                  <span class="important-date-copy">
                    <b>{{ item.name }}</b>
                    <small>{{ item.calendarType === 'LUNAR' ? item.lunarDateLabel : importantDateMonthDay(item.eventDate) }} · {{ importantDateReminderLabel(item) }}</small>
                    <em v-if="item.note">{{ item.note }}</em>
                  </span>
                  <i class="ri-arrow-right-s-line"></i>
                </button>
                <button class="important-date-delete" type="button" :disabled="!!importantDateDeletingId" :aria-label="`删除${item.name}`" @click="removeImportantDate(item)">
                  <i :class="importantDateDeletingId === item.id ? 'ri-loader-4-line spinning' : 'ri-delete-bin-line'"></i>
                </button>
              </article>
            </div>
            <div v-if="importantDateSaveError" class="work-schedule-state error compact">操作失败：{{ importantDateSaveError }}，请重试。</div>
          </template>
        </div>
      </div>
    </template>

    <!-- 国际节日范围配置 -->
    <template v-if="showHolidaySettings">
      <div class="cd-mask" @click="closeHolidaySettings"></div>
      <div class="holiday-modal" role="dialog" aria-modal="true" aria-labelledby="holiday-settings-title">
        <div class="cd-head">
          <div id="holiday-settings-title" class="cd-title">我的国际节日</div>
          <div class="spacer"></div>
          <button class="cd-close" aria-label="关闭节日设置" :disabled="holidaySettingsSaving" @click="closeHolidaySettings"><i class="ri-close-line"></i></button>
        </div>
        <div class="holiday-modal-body">
          <div v-if="holidaySettingsLoading" class="work-schedule-state"><i class="ri-loader-4-line spinning"></i>正在读取节日设置…</div>
          <div v-else-if="holidaySettingsLoadError || !holidaySettings" class="work-schedule-state error">
            <span>读取失败：{{ holidaySettingsLoadError || '没有拿到节日设置' }}</span>
            <button type="button" @click="loadHolidaySettings">重新读取</button>
          </div>
          <form v-else @submit.prevent="submitHolidaySettings">
            <p class="holiday-setting-intro">中国节假日始终显示；国际节日默认不添加，请只选择你真正关心的范围。</p>
            <div class="holiday-category-list">
              <label v-for="category in holidaySettings.availableInternationalCategories" :key="category.code" class="holiday-category">
                <input v-model="holidayCategoryDraft" type="checkbox" :value="category.code" :disabled="holidaySettingsSaving" />
                <span><b>{{ category.name }}</b><small>{{ category.description }}</small></span>
              </label>
            </div>
            <p class="holiday-setting-note">关闭类别会同时关闭该类别下已经设置的提醒，避免隐藏的节日仍然发送通知。</p>
            <div v-if="holidaySettingsSaveError" class="work-schedule-state error compact">保存失败：{{ holidaySettingsSaveError }}。选择草稿仍在，可直接重试。</div>
            <div class="work-schedule-actions">
              <button type="button" class="work-schedule-cancel" :disabled="holidaySettingsSaving" @click="closeHolidaySettings">取消</button>
              <button type="submit" class="work-schedule-save" :disabled="holidaySettingsSaving">
                <i v-if="holidaySettingsSaving" class="ri-loader-4-line spinning"></i>{{ holidaySettingsSaving ? '保存中…' : holidaySettingsSaveError ? '重试保存' : '保存范围' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </template>

    <!-- 节日详情与个人提醒 -->
    <template v-if="activeHoliday">
      <div class="cd-mask" @click="closeHolidayDetail"></div>
      <div class="holiday-modal holiday-detail-modal" role="dialog" aria-modal="true" aria-labelledby="holiday-detail-title">
        <div class="cd-head">
          <div id="holiday-detail-title" class="cd-title">{{ activeHoliday.name }}</div>
          <div class="spacer"></div>
          <button class="cd-close" aria-label="关闭节日详情" :disabled="holidayReminderSaving" @click="closeHolidayDetail"><i class="ri-close-line"></i></button>
        </div>
        <div class="holiday-modal-body">
          <dl class="holiday-facts">
            <div><dt>日期</dt><dd>{{ activeHoliday.date }}</dd></div>
            <div><dt>性质</dt><dd>{{ activeHoliday.dayType === 'DAY_OFF' ? '法定休息日' : activeHoliday.dayType === 'ADJUSTED_WORKDAY' ? '调休上班日' : '普通纪念日' }}</dd></div>
            <div><dt>年份</dt><dd>{{ activeHoliday.year }} 年数据</dd></div>
            <div><dt>来源</dt><dd><a v-if="activeHoliday.sourceUrl" :href="activeHoliday.sourceUrl" target="_blank" rel="noopener">{{ activeHoliday.sourceName }}</a><span v-else>{{ activeHoliday.sourceName }}</span></dd></div>
          </dl>
          <form v-if="activeHoliday.remindable" class="holiday-reminder-form" @submit.prevent="submitHolidayReminder">
            <label class="work-schedule-toggle">
              <span><b>每年提醒我</b><small>{{ holidaySettings?.reminderChannelHint || '提醒将通过当前账号的飞书身份发送' }}</small></span>
              <input v-model="holidayReminderEnabled" type="checkbox" :disabled="holidayReminderSaving || (!holidaySettings?.reminderChannelAvailable && !activeHoliday.reminderEnabled)" />
            </label>
            <label class="work-schedule-field">
              <span>节日当天提醒时间</span>
              <input v-model="holidayReminderTime" type="time" required :disabled="!holidayReminderEnabled || holidayReminderSaving" />
            </label>
            <div v-if="holidayReminderEnabled && !holidaySettings?.reminderChannelAvailable" class="work-schedule-state error compact">{{ holidaySettings?.reminderChannelHint || '提醒通道暂不可用' }}</div>
            <div v-if="holidayReminderSaveError" class="work-schedule-state error compact">保存失败：{{ holidayReminderSaveError }}。草稿仍在，可直接重试。</div>
            <div class="work-schedule-actions">
              <button type="button" class="work-schedule-cancel" :disabled="holidayReminderSaving" @click="closeHolidayDetail">取消</button>
              <button type="submit" class="work-schedule-save" :disabled="holidayReminderSaving || (holidayReminderEnabled && !holidaySettings?.reminderChannelAvailable)">
                <i v-if="holidayReminderSaving" class="ri-loader-4-line spinning"></i>{{ holidayReminderSaving ? '保存中…' : holidayReminderSaveError ? '重试保存' : holidayReminderEnabled ? '保存提醒' : activeHoliday.reminderEnabled ? '关闭提醒' : '保存' }}
              </button>
            </div>
          </form>
          <div v-else class="holiday-reminder-unavailable"><i class="ri-information-line"></i>{{ activeHoliday.name.startsWith('节气 · ') ? '这是历法数据中的二十四节气节点；如需通知，可在日历上创建独立提醒事项并选择提前天数与目标群。' : '这是调休上班或假期延展日；提醒请在对应节日本日设置，避免同一假期重复通知。' }}</div>
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
  display: grid;
  gap: 9px;
  padding: 10px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.cal-heading-row, .cal-control-row { display: flex; align-items: center; gap: 9px; min-width: 0; }
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
.cd-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.calendar-menu { position: relative; }
.calendar-menu summary { display: inline-flex; height: 30px; align-items: center; gap: 6px; padding: 0 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); font-size: 11.5px; cursor: pointer; list-style: none; }
.calendar-menu summary::-webkit-details-marker { display: none; }
.calendar-menu[open] summary { border-color: var(--wao-primary); color: var(--wao-primary); background: var(--wao-primary-soft); }
.calendar-menu-popover { position: absolute; z-index: 30; top: 36px; right: 0; display: grid; width: min(310px, calc(100vw - 32px)); padding: 6px; border: 1px solid var(--wao-line); border-radius: 11px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-modal); }
.calendar-menu-popover button { display: grid; grid-template-columns: 20px minmax(0,1fr) auto; align-items: center; gap: 8px; width: 100%; padding: 9px; border: 0; border-radius: 8px; background: transparent; color: var(--wao-text-2); text-align: left; cursor: pointer; }
.calendar-menu-popover button:hover { background: var(--wao-bg-hover); }
.calendar-menu-popover button > i { color: var(--wao-text-weak); }
.calendar-menu-popover button > span { min-width: 0; }
.calendar-menu-popover button b, .calendar-menu-popover button small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.calendar-menu-popover button b { font-size: 12px; }
.calendar-menu-popover button small { margin-top: 2px; color: var(--wao-text-weak); font-size: 10.5px; }
.calendar-menu-popover button em { color: var(--wao-text-weak); font-size: 10.5px; font-style: normal; }
.calendar-menu-popover button.on em { color: var(--wao-primary); }
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
.day-mark.rest { color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
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
.work-week-mark.big, .timeline-work-week.big { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
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
  transition: width .16s ease, padding .16s ease;
}
.backlog.folded {
  width: 42px;
  padding: 6px;
}
.backlog.folded .bl-toggle {
  height: 30px;
  justify-content: center;
  padding: 0;
}
.backlog.folded .bl-title,
.backlog.folded .bl-count,
.backlog.folded .bl-chev {
  display: none;
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
  .backlog.folded {
    width: 42px;
    align-self: flex-end;
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
  background: var(--wao-danger-bg);
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
.holiday-source-warning {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 0 14px 12px;
  padding: 9px 11px;
  border: 1px solid #ead7ad;
  border-radius: 8px;
  background: var(--wao-warning-bg);
  color: #7b642f;
  font-size: 11.5px;
  line-height: 1.5;
}
.holiday-source-warning span { flex: 1; }
.holiday-source-warning a { flex-shrink: 0; color: #8a6717; }
.holiday-modal {
  position: fixed;
  top: 50%;
  left: 50%;
  width: min(520px, calc(100vw - 32px));
  max-height: min(760px, calc(100vh - 32px));
  z-index: 101;
  overflow: auto;
  transform: translate(-50%, -50%);
  border-radius: 14px;
  background: var(--wao-bg-page);
  box-shadow: var(--wao-shadow-modal);
  animation: waoFade 0.15s ease;
}
.holiday-modal-body { padding: 4px 20px 20px; }
.holiday-setting-intro, .holiday-setting-note {
  margin: 2px 0 12px;
  color: var(--wao-text-weak);
  font-size: 12px;
  line-height: 1.6;
}
.holiday-setting-note { margin: 12px 2px 0; font-size: 11.5px; }
.holiday-category-list { display: grid; gap: 8px; }
.holiday-category {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  cursor: pointer;
}
.holiday-category:has(input:checked) { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.holiday-category input { width: 17px; height: 17px; accent-color: var(--wao-primary); }
.holiday-category span, .holiday-category small { display: block; }
.holiday-category b { font-size: 13px; color: var(--wao-text-1); }
.holiday-category small { margin-top: 2px; color: var(--wao-text-weak); font-size: 11.5px; }
.holiday-facts { margin: 0; display: grid; gap: 1px; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 10px; }
.holiday-facts div { display: grid; grid-template-columns: 76px 1fr; gap: 8px; padding: 9px 11px; background: var(--wao-bg-card); }
.holiday-facts dt { color: var(--wao-text-weak); font-size: 12px; }
.holiday-facts dd { margin: 0; color: var(--wao-text-2); font-size: 12px; }
.holiday-facts a { color: var(--wao-primary); }
.holiday-reminder-form { margin-top: 14px; }
.holiday-reminder-form .work-schedule-field { margin-top: 12px; }
.holiday-reminder-unavailable {
  display: flex;
  gap: 7px;
  margin-top: 14px;
  padding: 10px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 12px;
  line-height: 1.6;
}
.important-date-modal {
  position: fixed;
  top: 50%;
  left: 50%;
  width: min(600px, calc(100vw - 32px));
  max-height: min(780px, calc(100dvh - 32px));
  z-index: 101;
  overflow: auto;
  transform: translate(-50%, -50%);
  border-radius: 14px;
  background: var(--wao-bg-page);
  box-shadow: var(--wao-shadow-modal);
  animation: waoFade 0.15s ease;
}
.important-date-body { padding: 4px 20px 20px; }
.important-date-back {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-right: 8px;
  padding: 4px 6px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.important-date-back:hover { background: var(--wao-bg-hover); }
.important-date-intro { margin: 2px 0 14px; color: var(--wao-text-weak); font-size: 12px; line-height: 1.6; }
.important-date-form { display: grid; gap: 12px; }
.important-date-field { display: grid; gap: 6px; color: var(--wao-text-2); font-size: 12px; }
.important-date-field input, .important-date-field textarea, .important-date-field select {
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text-1);
  font: inherit;
}
.important-date-field input, .important-date-field select { height: 36px; }
.important-date-field textarea { resize: vertical; line-height: 1.5; }
.important-date-field small { color: var(--wao-text-weak); font-size: 11px; line-height: 1.5; }
.important-date-reminder-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
}
.important-date-reminder-toggle span, .important-date-reminder-toggle small { display: block; }
.important-date-reminder-toggle b { font-size: 13px; }
.important-date-reminder-toggle small { margin-top: 3px; color: var(--wao-text-weak); font-size: 11px; line-height: 1.45; }
.important-date-reminder-toggle input { width: 18px; height: 18px; accent-color: #d0527f; flex-shrink: 0; }
.important-date-reminder-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.important-date-list-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin: 2px 0 14px; }
.important-date-list-head div, .important-date-list-head span { display: block; }
.important-date-list-head b { color: var(--wao-text-1); font-size: 13px; }
.important-date-list-head span { margin-top: 3px; color: var(--wao-text-weak); font-size: 11.5px; }
.important-date-list-head button, .important-date-empty button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex-shrink: 0;
  min-height: 34px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: #d0527f;
  color: #fff;
  cursor: pointer;
}
.important-date-list { display: grid; gap: 8px; }
.important-date-item { display: flex; align-items: stretch; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); overflow: hidden; }
.important-date-item:hover { border-color: #df9db5; }
.important-date-main { display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1; padding: 11px 12px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.important-date-icon { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; flex-shrink: 0; border-radius: 9px; background: var(--wao-primary-soft); color: var(--wao-area-growth); }
.important-date-copy { display: block; min-width: 0; flex: 1; }
.important-date-copy b, .important-date-copy small, .important-date-copy em { display: block; }
.important-date-copy b { color: var(--wao-text-1); font-size: 13px; }
.important-date-copy small { margin-top: 3px; color: var(--wao-text-weak); font-size: 11.5px; }
.important-date-copy em { margin-top: 4px; overflow: hidden; color: var(--wao-text-3); font-size: 11px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.important-date-delete { width: 42px; flex-shrink: 0; border: 0; border-left: 1px solid var(--wao-divider); background: transparent; color: var(--wao-text-weak); cursor: pointer; }
.important-date-delete:hover { background: var(--wao-danger-bg); color: var(--wao-danger); }
.important-date-delete:disabled { cursor: not-allowed; opacity: .6; }
.important-date-empty { display: flex; min-height: 240px; align-items: center; justify-content: center; flex-direction: column; gap: 7px; color: var(--wao-text-weak); text-align: center; }
.important-date-empty > i { font-size: 34px; color: #d0527f; }
.important-date-empty b { color: var(--wao-text-2); font-size: 14px; }
.important-date-empty span { font-size: 12px; }
.important-date-empty button { margin-top: 8px; }
.completed-toggle {
  height: 30px; display: inline-flex; align-items: center; gap: 5px; padding: 0 9px;
  border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card);
  color: var(--wao-text-3); font-size: 11.5px; cursor: pointer;
}
.completed-toggle.on { color: var(--wao-primary); border-color: color-mix(in srgb, var(--wao-primary) 45%, var(--wao-line-control)); }
.calendar-search { position: relative; width: min(240px, 100%); height: 30px; }
.calendar-search > i { position: absolute; z-index: 1; left: 9px; top: 7px; color: var(--wao-text-weak); }
.calendar-search > input { width: 100%; height: 30px; padding: 0 9px 0 28px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-1); }
.calendar-search-results { position: absolute; z-index: 20; top: 35px; right: 0; width: min(420px, calc(100vw - 28px)); max-height: 360px; overflow: auto; padding: 5px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-modal); }
.calendar-search-results button { display: grid; grid-template-columns: 84px 1fr; gap: 8px; width: 100%; padding: 8px; border: 0; border-radius: 7px; background: transparent; color: var(--wao-text-2); text-align: left; cursor: pointer; }
.calendar-search-results button:hover { background: var(--wao-bg-hover); }
.calendar-search-results b, .calendar-search-results small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.calendar-search-results b { font-size: 12px; }.calendar-search-results small, .search-result-date { color: var(--wao-text-weak); font-size: 10.5px; }
.calendar-search-empty { padding: 16px; color: var(--wao-text-weak); font-size: 12px; text-align: center; }
.create-choice-modal, .search-preview-modal { position: fixed; z-index: 101; top: 50%; left: 50%; width: min(520px, calc(100vw - 32px)); transform: translate(-50%, -50%); border-radius: 14px; background: var(--wao-bg-page); box-shadow: var(--wao-shadow-modal); animation: waoFade .15s ease; }
.create-choice-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 8px 20px 20px; }
.create-choice-grid button { display: grid; justify-items: start; gap: 7px; padding: 16px; border: 1px solid var(--wao-line); border-radius: 11px; background: var(--wao-bg-card); color: var(--wao-text-2); text-align: left; cursor: pointer; }
.create-choice-grid button:hover { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.create-choice-grid i { font-size: 24px; color: var(--wao-primary); }.create-choice-grid b { font-size: 14px; }.create-choice-grid span { color: var(--wao-text-weak); font-size: 11.5px; line-height: 1.55; }
.search-preview-modal .holiday-facts { margin: 4px 20px 20px; }
.calendar-type-switch { display: inline-flex; width: fit-content; padding: 2px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); }
.calendar-type-switch button { min-width: 90px; height: 30px; border: 0; border-radius: 6px; background: transparent; color: var(--wao-text-3); cursor: pointer; }
.calendar-type-switch button.on { background: var(--wao-primary); color: #fff; }
.notification-type { opacity: 1; }.notification-type button { min-width: 110px; }
.lunar-date-fields { display: grid; grid-template-columns: 1.2fr 1fr 1fr auto; align-items: end; gap: 8px; }
.lunar-leap-toggle { display: inline-flex; height: 36px; align-items: center; gap: 5px; color: var(--wao-text-2); font-size: 12px; }
.date-conversion { display: flex; align-items: center; gap: 6px; min-height: 34px; padding: 7px 10px; border-radius: 8px; background: var(--wao-primary-soft); color: var(--wao-primary-text); font-size: 11.5px; }
.date-conversion.error { background: var(--wao-danger-bg); color: var(--wao-danger); }
.field-error { color: var(--wao-danger) !important; }.field-error button { padding: 0; border: 0; background: transparent; color: var(--wao-primary); cursor: pointer; }
@media (max-width: 640px) {
  .cal { padding-inline: 12px; }
  .holiday-source-warning { flex-wrap: wrap; }
  .holiday-source-warning a { margin-left: 22px; }
  .holiday-modal-body { padding-inline: 14px; }
  .holiday-facts div { grid-template-columns: 58px 1fr; }
  .holiday-reminder-form .work-schedule-field { grid-template-columns: 1fr; }
  .important-date-body { padding-inline: 14px; }
  .cal-heading-row, .cal-control-row { flex-wrap: wrap; }
  .cal-control-row .spacer { display: none; }
  .important-date-reminder-fields { grid-template-columns: 1fr; }
  .important-date-list-head { align-items: flex-start; }
  .important-date-list-head span { max-width: 190px; }
  .create-choice-grid { grid-template-columns: 1fr; }
  .lunar-date-fields { grid-template-columns: 1fr 1fr; }
  .calendar-search { width: 100%; }
}
</style>
