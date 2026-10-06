<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { PERIOD_LABELS, sameSchedule } from '@/utils/taskSchedule'
import { PreviousTodoWriteRecovered, saveTodoWithReceipt, writeSchedule, type DailySchedule, type ScheduleWrite } from '@/api/dayWorkspace'
import type { Task } from '@/types'

const props = defineProps<{ task: Task; day: string }>()
const emit = defineEmits<{ saved: [] }>()
const data = useDataStore(), session = useSessionStore()
const open = ref(false), exact = ref(false), saving = ref(false), error = ref('')
const anchor = ref<HTMLButtonElement>(), panel = ref<HTMLElement>()
const style = ref<Record<string, string>>({})
const day = ref(props.task.start || props.day)
const starts = ref(props.task.startAt?.slice(0, 16) || `${day.value}T09:00`)
const ends = ref(props.task.endAt?.slice(0, 16) || `${day.value}T10:00`)
const receipt = ref<ScheduleWrite | null>(null)
const identity = () => `${session.account?.accountId ?? ''}:${session.userId}`
const draftKey = `wao-schedule-draft-v1:${identity()}:${props.task.id}`
try {
  const draft = JSON.parse(localStorage.getItem(draftKey) || 'null') as { day?: string; starts?: string; ends?: string } | null
  if (draft?.day) day.value = draft.day
  if (draft?.starts) starts.value = draft.starts
  if (draft?.ends) ends.value = draft.ends
} catch { /* Inputs remain usable when a prior draft is unavailable. */ }
watch([day, starts, ends], () => {
  try { localStorage.setItem(draftKey, JSON.stringify({ day: day.value, starts: starts.value, ends: ends.value })) }
  catch { error.value = '时间草稿无法保存在本机，请保留页面并完成保存' }
})
const label = computed(() => props.task.startAt ? `${props.task.startAt.slice(5, 10)} ${props.task.startAt.slice(11, 16)}${props.task.endAt ? '–' + (props.task.endAt.slice(0, 10) !== props.task.startAt.slice(0, 10) ? props.task.endAt.slice(5, 10) + ' ' : '') + props.task.endAt.slice(11, 16) : ''}`
  : props.task.start ? `${props.task.start === props.day ? '' : props.task.start.slice(5) + ' · '}${props.task.schedulePeriod ? PERIOD_LABELS[props.task.schedulePeriod] : '当天做，暂不定时'}` : '安排时间')
const canUndo = computed(() => receipt.value && props.task.editVersion === receipt.value.task.editVersion && sameSchedule(props.task, receipt.value.task))
function close() { open.value = false; void nextTick(() => anchor.value?.focus()) }
async function show() {
  if (open.value) { close(); return }
  style.value = { visibility: 'hidden' }
  open.value = true
  await nextTick()
  positionPanel()
  panel.value?.querySelector<HTMLElement>('button')?.focus()
}
function positionPanel() {
  if (!open.value || !panel.value || !anchor.value) return
  const viewport = window.visualViewport
  const leftEdge = (viewport?.offsetLeft ?? 0) + 12
  const topEdge = (viewport?.offsetTop ?? 0) + 12
  const width = Math.max(1, Math.min(340, (viewport?.width ?? window.innerWidth) - 24))
  const availableHeight = Math.max(1, (viewport?.height ?? window.innerHeight) - 24)
  // Measure at the final width before revealing the first frame.
  panel.value.style.width = `${width}px`
  const height = Math.min(panel.value.scrollHeight, availableHeight)
  const box = anchor.value.getBoundingClientRect()
  const bottomEdge = topEdge + availableHeight
  const preferredTop = box.bottom + height + 6 <= bottomEdge ? box.bottom + 6 : box.top - height - 6
  style.value = { left: `${Math.max(leftEdge, Math.min(box.left, leftEdge + (viewport?.width ?? window.innerWidth) - 24 - width))}px`, top: `${Math.max(topEdge, Math.min(preferredTop, bottomEdge - height))}px`, width: `${width}px`, maxHeight: `${availableHeight}px` }
}
useOverlayDismiss(() => open.value, close, event => {
  if (!panel.value?.contains(event.target as Node) && !anchor.value?.contains(event.target as Node)) close()
})
const viewportChanged = () => { if (open.value) void nextTick(positionPanel) }
watch(exact, viewportChanged)
window.addEventListener('resize', viewportChanged)
const visualViewport = window.visualViewport
visualViewport?.addEventListener('resize', viewportChanged)
visualViewport?.addEventListener('scroll', viewportChanged)
onBeforeUnmount(() => {
  window.removeEventListener('resize', viewportChanged)
  visualViewport?.removeEventListener('resize', viewportChanged)
  visualViewport?.removeEventListener('scroll', viewportChanged)
})
async function save(schedule: DailySchedule, undo = false) {
  if (saving.value) return
  saving.value = true; error.value = ''
  try {
    const result = await data.runTaskOperation(props.task.id, async base => {
      if (undo && (!receipt.value || base.editVersion !== receipt.value.task.editVersion || !sameSchedule(base, receipt.value.task))) throw new Error('任务已有后续修改，不能撤销覆盖；可重新安排')
      return saveTodoWithReceipt<ScheduleWrite>(`schedule:${base.id}`, identity(), { ...schedule, expectedEditVersion: base.editVersion! },
        (operation, request) => writeSchedule(base.id, operation, request as DailySchedule & { expectedEditVersion: string }), identity)
    })
    receipt.value = undo ? null : result
    try { localStorage.removeItem(draftKey) } catch { /* A retained draft is harmless. */ }
    emit('saved'); close()
  } catch (cause) {
    if (cause instanceof PreviousTodoWriteRecovered) emit('saved')
    error.value = cause instanceof Error ? cause.message : '安排保存失败，输入仍保留'
  } finally { saving.value = false }
}
function choose(period: DailySchedule['schedulePeriod']) { void save({ start: day.value, startAt: null, endAt: null, schedulePeriod: period }) }
function saveExact() {
  if (!starts.value || !ends.value || starts.value >= ends.value) { error.value = '请选择开始与结束时间；结束可以在次日'; return }
  void save({ start: starts.value.slice(0, 10), startAt: starts.value, endAt: ends.value, schedulePeriod: null })
}
</script>

<template>
  <span class="schedule-control">
    <button ref="anchor" type="button" class="time-chip" :disabled="saving" :aria-expanded="open" aria-haspopup="dialog" @click="show"><i class="ri-time-line"></i>{{ saving ? '正在保存…' : label }}</button>
    <span v-if="receipt && !open" class="schedule-feedback" role="status">已保存 <button type="button" :disabled="!canUndo || saving" :title="canUndo ? '恢复原安排' : '已有后续修改，无法撤销'" @click="save(receipt.previousSchedule, true)">撤销</button></span>
    <span v-if="error && !open" class="schedule-error" role="alert">{{ error }} <button type="button" @click="show">重试</button></span>
  </span>
  <Teleport to="body"><section v-if="open" ref="panel" class="schedule-popover" :style="style" role="dialog" aria-label="安排执行时间" @keydown.esc.prevent.stop="close">
    <header><b>什么时候做</b><button type="button" aria-label="收起时间安排" @click="close">×</button></header>
    <label>执行日期<input v-model="day" type="date" aria-label="任务执行日期" :disabled="saving" /></label>
    <div class="period-options"><button type="button" :disabled="saving" @click="choose(null)">暂不定时</button><button v-for="(name, period) in PERIOD_LABELS" :key="period" type="button" :disabled="saving" @click="choose(period)">{{ name }}</button></div>
    <button type="button" class="secondary" :aria-expanded="exact" @click="exact = !exact">{{ exact ? '收起精确时间' : '指定精确时间' }}</button>
    <div v-if="exact" class="exact-fields"><label>开始<input v-model="starts" type="datetime-local" aria-label="开始时间" :disabled="saving" /></label><label>结束<input v-model="ends" type="datetime-local" aria-label="结束时间" :disabled="saving" /></label><button type="button" :disabled="saving" @click="saveExact">保存时段</button></div>
    <button type="button" class="secondary" :disabled="saving" @click="save({ start: null, startAt: null, endAt: null, schedulePeriod: null })">取消执行安排</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <small>执行安排与截止日期分别保存</small>
  </section></Teleport>
</template>

<style scoped>
.schedule-control{display:flex;gap:8px;align-items:center;flex-wrap:wrap;min-width:0}.time-chip{border:0;border-radius:7px;padding:5px 8px;background:var(--wao-bg-hover);color:var(--wao-primary);font-size:12px;white-space:normal;text-align:left;overflow-wrap:anywhere}.time-chip i{margin-right:5px}.schedule-feedback{font-size:11px;color:var(--wao-text-3)}.schedule-feedback button,.schedule-error button{color:var(--wao-primary);border:0;background:none}.schedule-error{font-size:12px;color:var(--wao-danger,#ce4c65)}.schedule-popover{position:fixed;z-index:1300;box-sizing:border-box;overflow:auto;border:1px solid var(--wao-line-control);border-radius:14px;background:var(--wao-bg-card);box-shadow:0 12px 40px #18234222;padding:14px;color:var(--wao-text-1)}header{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}button{cursor:pointer}header button{border:0;background:none;font-size:22px}.schedule-popover label{display:grid;gap:5px;font-size:12px;color:var(--wao-text-2);margin-bottom:10px}input{width:100%;min-width:0;box-sizing:border-box;border:1px solid var(--wao-line-control);border-radius:7px;padding:8px;background:var(--wao-bg-card);color:var(--wao-text-1)}.period-options{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:5px;margin-bottom:8px}.period-options button,.exact-fields button{min-height:38px;border:1px solid var(--wao-line-control);border-radius:7px;background:var(--wao-bg-hover);color:var(--wao-primary);padding:5px}.secondary{display:block;border:0;background:none;color:var(--wao-text-2);padding:10px 0}.schedule-popover small{font-size:11px;color:var(--wao-text-3)}.schedule-popover p{font-size:12px;color:var(--wao-danger,#ce4c65)}@media(max-width:600px){.time-chip,.period-options button,.secondary,header button{min-height:44px}}
@media (max-width: 600px) { .schedule-popover input, .exact-fields button { min-height: 44px; font-size: 16px; } header button { min-width: 44px; } }
</style>
