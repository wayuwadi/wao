<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { toast } from '@/composables/toast'

interface CalEvt {
  date: string
  title: string
  color: string
  go?: () => void
}

const data = useDataStore()
const ui = useUiStore()
const router = useRouter()

const calY = ref(2026)
const calM = ref(6) // 0-based：7 月

const LEGEND = [
  { label: '日程', color: '#2C8A5B' },
  { label: '任务截止', color: '#4358D6' },
  { label: '里程碑', color: '#B9821F' },
  { label: '家庭日历 · CalDAV', color: '#0E8C8C' },
  { label: '工作日历 · CalDAV', color: '#C05D8E' },
]
const DOWS = ['一', '二', '三', '四', '五', '六', '日']

const events = computed<CalEvt[]>(() => {
  const evs: CalEvt[] = []
  data.schedule.forEach((x) => evs.push({ date: data.today, title: `${x.time} ${x.title}`, color: '#2C8A5B' }))
  data.tasks
    .filter((t) => t.status !== '已完成' && t.due)
    .forEach((t) => evs.push({ date: t.due, title: t.title, color: '#4358D6', go: () => ui.openTask(t.id) }))
  const p = data.projectById('p1')
  if (p) p.msDates.forEach((d, i) => evs.push({ date: d, title: `里程碑 · ${p.milestones[i]?.name ?? ''}`, color: '#B9821F', go: () => router.push('/projects/p1') }))
  data.calEvents.forEach((e) => evs.push({ date: e.date, title: e.title, color: e.color }))
  return evs
})

const month = computed(() => {
  const y = calY.value
  const m = calM.value
  const first = new Date(y, m, 1)
  const off = (first.getDay() + 6) % 7
  const dim = new Date(y, m + 1, 0).getDate()
  const prevDim = new Date(y, m, 0).getDate()
  const byDate: Record<string, CalEvt[]> = {}
  events.value.forEach((e) => (byDate[e.date] = byDate[e.date] || []).push(e))
  const total = Math.ceil((off + dim) / 7) * 7
  const cells: {
    label: string; isToday: boolean; items: CalEvt[]; more: string; numBg: string; numColor: string; bg: string
  }[] = []
  for (let i = 0; i < total; i++) {
    const dnum = i - off + 1
    let iso = ''
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
      iso = `${y}-${String(m + 1).padStart(2, '0')}-${String(dnum).padStart(2, '0')}`
    }
    const all = (iso && byDate[iso]) || []
    const isToday = iso === data.today
    cells.push({
      label: String(label),
      isToday,
      items: all.slice(0, 3),
      more: all.length > 3 ? `还有 ${all.length - 3} 项` : '',
      numBg: isToday ? '#4358D6' : 'transparent',
      numColor: isToday ? '#FFFFFF' : inM ? '#3E414D' : '#C4C7D2',
      bg: inM ? '#FFFFFF' : '#FBFBFC',
    })
  }
  const weeks: (typeof cells)[] = []
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7))
  return { title: `${y} 年 ${m + 1} 月`, weeks }
})

function shift(d: number) {
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
  calY.value = 2026
  calM.value = 6
}
</script>

<template>
  <div class="cal">
    <div class="cal-top">
      <div class="cal-title">日历</div>
      <div class="legend">
        <span v-for="l in LEGEND" :key="l.label" class="leg"><span class="leg-dot" :style="{ background: l.color }"></span>{{ l.label }}</span>
      </div>
      <button class="caldav" title="在设置中配置 CalDAV 账户" @click="router.push('/settings')">
        <span class="cd-dot"></span>CalDAV 已连接<i class="ri-settings-3-line"></i>
      </button>
      <div class="spacer"></div>
      <button class="nav-btn" @click="shift(-1)"><i class="ri-arrow-left-s-line"></i></button>
      <button class="today-btn" @click="goToday">今天</button>
      <button class="nav-btn" @click="shift(1)"><i class="ri-arrow-right-s-line"></i></button>
    </div>

    <div class="cal-card">
      <div class="cal-head">
        <div class="month-title">{{ month.title }}</div>
        <span class="cal-hint">日程、任务截止与项目里程碑在同一张日历上</span>
      </div>
      <div class="dows">
        <div v-for="d in DOWS" :key="d" class="dow">{{ d }}</div>
      </div>
      <div v-for="(wk, wi) in month.weeks" :key="wi" class="week">
        <div v-for="(dy, di) in wk" :key="di" class="day" :style="{ background: dy.bg }">
          <div class="day-num" :style="{ background: dy.numBg, color: dy.numColor }">{{ dy.label }}</div>
          <div class="day-events">
            <button
              v-for="(ev, ei) in dy.items"
              :key="ei"
              class="event"
              :title="ev.title"
              @click="ev.go ? ev.go() : toast('CalDAV / 家庭日历事件（演示）')"
            >
              <span class="ev-dot" :style="{ background: ev.color }"></span>
              <span class="ev-title">{{ ev.title }}</span>
            </button>
            <div v-if="dy.more" class="day-more">{{ dy.more }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cal {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
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
  background: var(--wao-success);
}
.caldav i {
  font-size: 12px;
  color: var(--wao-text-weak);
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

.cal-card {
  margin-top: 14px;
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
.ev-dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
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
}
</style>
