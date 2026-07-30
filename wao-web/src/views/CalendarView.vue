<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { fetchCaldavAccounts, fetchCaldavEvents, type CaldavAccount, type CaldavEvent } from '@/api/caldav'
import { toast } from '@/composables/toast'
import { usePreferenceStore } from '@/stores/preferences'
// 配置面板打开才需要，不进日历页首屏包。
const CaldavPanel = defineAsyncComponent(() => import('@/components/calendar/CaldavPanel.vue'))

/** 日历格子里的一条事件（任务截止 / 里程碑 / CalDAV）。 */
interface CalEvt {
  date: string
  title: string
  color: string
  time?: string
  endTime?: string
  taskId?: string
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

// ---------- CalDAV：账户状态 + 当月事件 ----------
const accounts = ref<CaldavAccount[]>([])
const caldavEvents = ref<CaldavEvent[]>([])

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

/** 拉取覆盖当前月视图（含前后补位周）的订阅事件。 */
async function loadEvents() {
  const first = new Date(calY.value, calM.value, 1)
  const off = preferences.weekStart === '周日' ? first.getDay() : (first.getDay() + 6) % 7
  const from = new Date(calY.value, calM.value, 1 - off)
  const to = new Date(calY.value, calM.value + 1, 7)
  try {
    caldavEvents.value = await fetchCaldavEvents(iso(from), iso(to))
  } catch {
    caldavEvents.value = []
  }
}

function iso(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

onMounted(() => {
  loadCaldav()
  loadEvents()
})
watch([calY, calM], loadEvents)

// ---------- 图例：固定两类 + 每个启用的订阅日历 ----------
const legend = computed(() => {
  const items = [
    { label: '任务截止', color: '#4358D6' },
    { label: '里程碑', color: '#B9821F' },
  ]
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
    .filter((t) => t.status !== '已完成' && t.owner === calendarMemberId.value && (t.due || t.startAt))
    .forEach((t) => evs.push({
      date: t.startAt?.slice(0, 10) || t.due,
      title: t.title,
      color: '#4358D6',
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
    byDate[k]!.sort((a, b) => (a.time ?? '').localeCompare(b.time ?? ''))
  }
  const total = Math.ceil((off + dim) / 7) * 7
  const cells: {
    iso: string; label: string; isToday: boolean; items: CalEvt[]; more: string
    numBg: string; numColor: string; bg: string
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
      numBg: isToday ? '#4358D6' : 'transparent',
      numColor: isToday ? '#FFFFFF' : inM ? '#3E414D' : '#C4C7D2',
      bg: inM ? '#FFFFFF' : '#FBFBFC',
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
    return { iso: day, label: `${date.getMonth() + 1}月${date.getDate()}日`, isToday: day === data.today, items: events.value.filter((event) => event.date === day) }
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

// ---------- 全部未完成任务：按优先级、具体时间排序；拖上日历可调整日期 ----------
const showBacklog = ref(true)
const backlog = computed(() => {
  const priority = { P0: 0, P1: 1, P2: 2, P3: 3 }
  return data.tasks
    .filter((t) => t.status !== '已完成' && t.owner === calendarMemberId.value)
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
      <button class="caldav" title="配置 CalDAV 日历账户" @click="showCaldavConfig = true">
        <span class="cd-dot" :style="{ background: caldavState.dot }"></span>{{ caldavState.label }}<i class="ri-settings-3-line"></i>
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
          <span class="cal-hint">任务截止、项目里程碑与订阅日历在同一张日历上</span>
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
            <div class="day-num" :style="{ background: dy.numBg, color: dy.numColor }" @dblclick="quickCreate(dy.iso)">{{ dy.label }}</div>
            <div class="day-events" @dblclick.self="quickCreate(dy.iso)">
              <button
                v-for="(ev, ei) in dy.items"
                :key="ei"
                class="event"
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
                :style="eventStyle(event)"
                @mousedown.stop
                @click="event.go?.()"
              >
                <b>{{ event.time }}<template v-if="event.endTime">–{{ event.endTime }}</template></b>
                <span>{{ event.title }}</span>
              </button>
              <div v-if="day.items.some((item) => !item.time)" class="all-day-strip">
                <button v-for="(event, eventIndex) in day.items.filter((item) => !item.time)" :key="eventIndex" @click="event.go?.()">{{ event.title }}</button>
              </div>
            </div>
          </div>
        </template>
        <template v-else><div class="year-view"><button v-for="item in yearMonths" :key="item.index" class="year-month" @click="calM = item.index; selectMonth()"><b>{{ item.title }}</b><span>{{ item.count ? `${item.count} 项日程` : '暂无日程' }}</span></button></div></template>
      </div>

      <!-- 全部未完成任务 -->
      <aside class="backlog" :class="{ folded: !showBacklog }">
        <button class="bl-head" @click="showBacklog = !showBacklog">
          <i class="ri-inbox-line"></i>
          <span class="bl-title">未完成任务</span>
          <span class="bl-count wao-tabular">{{ backlog.length }}</span>
          <i class="bl-chev" :class="showBacklog ? 'ri-arrow-down-s-line' : 'ri-arrow-up-s-line'"></i>
        </button>
        <template v-if="showBacklog">
          <div class="bl-hint">按 P0–P3 和具体时间排序；拖到日期格可新增或调整排期</div>
          <div v-if="!backlog.length" class="bl-empty">当前成员没有未完成任务</div>
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
  height: 38px;
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
  background: #fff;
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
.all-day-strip {
  position: sticky;
  top: 38px;
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
.caldav {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--wao-line-control);
  background: #fff;
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12px;
  color: var(--wao-text-2);
  cursor: pointer;
}
.caldav:hover {
  border-color: #c9cdda;
  background: var(--wao-bg-sub);
}
.cd-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.caldav i {
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
  background: #fff;
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
  background: #fff;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.nav-btn:hover {
  background: #f4f5f8;
}
.nav-btn i {
  font-size: 15px;
}
.today-btn {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: #fff;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-2);
  font-size: 12px;
}
.today-btn:hover {
  background: #f4f5f8;
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
  background: #fff;
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
  background: #f2f4fd !important;
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
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 10px;
  position: sticky;
  top: 16px;
}
.bl-head {
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
.bl-head i {
  font-size: 15px;
  color: var(--wao-text-3);
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
  background: #fff;
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
