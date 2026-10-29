<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import CellMenu from './CellMenu.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { deadlineInfo, shiftDay } from '@/utils/taskDeadline'

const props = defineProps<{ due?: string | null; today:string; completed?:boolean; disabled?:boolean; missingCount?:number; bulkBusy?:boolean }>()
const emit = defineEmits<{ change:[date:string]; fillMissing:[] }>()
const anchor = ref<HTMLElement | null>(null), calendar = ref<HTMLElement | null>(null)
const open = ref(false), draft = ref(''), month = ref('')
const info = computed(() => deadlineInfo(props.due, props.today, props.completed))
const days = computed(() => {
  if (!month.value) return []
  const first = `${month.value}-01`
  const start = shiftDay(first, -new Date(`${first}T00:00:00Z`).getUTCDay())
  return Array.from({ length:42 }, (_, index) => shiftDay(start, index))
})
async function focusDay() {
  await nextTick()
  calendar.value?.querySelector<HTMLButtonElement>(`[data-day="${draft.value}"]`)?.focus()
}
function show() {
  if (open.value) { close(); return }
  draft.value = props.due || props.today
  month.value = draft.value.slice(0,7)
  open.value = true
  void focusDay()
}
function close() { open.value = false; anchor.value?.focus() }
function moveMonth(offset:number) {
  const date = new Date(`${month.value}-01T00:00:00Z`)
  date.setUTCMonth(date.getUTCMonth() + offset)
  month.value = date.toISOString().slice(0,7)
}
function choose(day:string) { draft.value = day; month.value = day.slice(0,7) }
function navigate(event:KeyboardEvent, day:string) {
  const offset = { ArrowLeft:-1, ArrowRight:1, ArrowUp:-7, ArrowDown:7 }[event.key]
  if (offset === undefined) return
  event.preventDefault(); choose(shiftDay(day, offset)); void focusDay()
}
function save() { emit('change', draft.value); close() }
</script>

<template>
  <button ref="anchor" class="due-trigger" :class="{ overdue:info.overdue > 0 }" aria-label="选择截止日期" aria-haspopup="dialog" :aria-expanded="open" :disabled="disabled" @click="show">
    <i class="ri-calendar-line" aria-hidden="true"></i><span>{{ info.label }}</span><span v-if="info.overdue" class="overdue-days">已延期 {{ info.overdue }} 天</span>
  </button>
  <CellMenu v-if="open" :anchor="anchor" :min-width="280" custom-content style="max-height:calc(100dvh - 32px)" @close="close" @ready="focusDay">
    <section ref="calendar" class="deadline-calendar" role="dialog" aria-label="选择截止日期">
      <div class="date-shortcuts"><WaoButton variant="ghost" @click="choose(today)"><i class="ri-sun-line"></i>今天</WaoButton><WaoButton variant="ghost" @click="choose(shiftDay(today, 1))">明天</WaoButton><WaoButton variant="ghost" @click="choose(shiftDay(today, 7))">一周后</WaoButton></div>
      <div class="month-nav"><strong>{{ month.slice(0,4) }}年{{ Number(month.slice(5,7)) }}月</strong><WaoButton variant="ghost" aria-label="上个月" @click="moveMonth(-1)"><i class="ri-arrow-left-s-line"></i></WaoButton><WaoButton variant="ghost" aria-label="下个月" @click="moveMonth(1)"><i class="ri-arrow-right-s-line"></i></WaoButton></div>
      <div class="calendar-grid"><span v-for="day in ['日','一','二','三','四','五','六']" :key="day" class="weekday">{{ day }}</span>
        <button v-for="day in days" :key="day" :data-day="day" :aria-label="day" :aria-pressed="draft === day" :aria-current="day === today ? 'date' : undefined" :tabindex="day === draft || (!draft.startsWith(month) && day === month + '-01') ? 0 : -1" :class="{ selected:draft === day, outside:!day.startsWith(month), today:day === today }" @click="choose(day)" @keydown="navigate($event, day)">{{ Number(day.slice(8,10)) }}</button>
      </div>
      <div class="date-footer"><WaoButton variant="primary" @click="save">确定</WaoButton><WaoButton variant="ghost" @click="close">取消</WaoButton></div>
      <WaoButton v-if="missingCount" class="fill-defaults" variant="ghost" :disabled="bulkBusy" @click="emit('fillMissing'); close()">为所有项目补齐默认日期（{{ missingCount }}）</WaoButton>
    </section>
  </CellMenu>
</template>

<style scoped>
.due-trigger { display:inline-flex; align-items:center; flex-wrap:wrap; gap:var(--wao-space-1); padding:var(--wao-space-1) 0; border:0; background:transparent; color:var(--wao-primary-text); font:inherit; cursor:pointer; text-align:left; }
.due-trigger:hover { text-decoration:underline; }
.due-trigger.overdue { color:var(--wao-danger-strong); }
.overdue-days { font-size:var(--wao-font-caption); }
.deadline-calendar { width:280px; max-width:calc(100vw - 32px); padding:var(--wao-space-1); box-sizing:border-box; color:var(--wao-text); font-size:var(--wao-font-body); }
.date-shortcuts,.month-nav,.date-footer { display:flex; align-items:center; gap:var(--wao-space-1); }
.date-shortcuts > *,.date-footer > * { flex:1; }
.date-shortcuts .wao-button { padding:var(--wao-space-1); white-space:nowrap; }
.month-nav { margin:var(--wao-space-1) 0; }
.month-nav strong { margin-right:auto; font-weight:500; }
.month-nav .wao-button { padding:var(--wao-space-1); }
.calendar-grid { display:grid; grid-template-columns:repeat(7,minmax(0,1fr)); gap:0; text-align:center; }
.weekday { color:var(--wao-text-weak); padding:var(--wao-space-1) 0; }
.calendar-grid button { min-height:36px; padding:0; border:0; border-radius:var(--wao-radius-pill); background:transparent; color:var(--wao-text); font:inherit; cursor:pointer; }
.calendar-grid button.outside { color:var(--wao-text-weak); }
.calendar-grid button.today { outline:1px solid var(--wao-primary); outline-offset:-2px; }
.calendar-grid button:hover { background:var(--wao-primary-soft); }
.calendar-grid button.selected { background:var(--wao-primary); color:var(--wao-on-primary); }
.fill-defaults { width:100%; margin-top:var(--wao-space-1); white-space:normal; font-size:var(--wao-font-caption); }
.date-footer { margin-top:var(--wao-space-2); }
button:focus-visible { outline:2px solid var(--wao-primary); outline-offset:1px; }
@media (pointer:coarse) { .calendar-grid button { min-height:44px; } }
</style>
