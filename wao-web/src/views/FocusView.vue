<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useFocusStore } from '@/stores/focus'
import { useRoutinesStore } from '@/stores/routines'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { projectWorkItems } from '@/utils/projectWorkItems'
import { focusTime, type FocusMode } from '@/api/focus'
import FocusDial from '@/components/focus/FocusDial.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
const focus = useFocusStore(), data = useDataStore(), ui = useUiStore(), route = useRoute()
const routines=useRoutinesStore()
const habitId=computed(()=>typeof route.query.focusHabit==='string'?route.query.focusHabit:'')
const habit=computed(()=>routines.item(habitId.value))
watch(()=>[habitId.value,routines.identity],()=>{if(habitId.value)void routines.load()},{immediate:true})
const mode = ref<FocusMode>('pomodoro'), minutes = ref(25), task = ref(''), search = ref('')
const items = computed(() => projectWorkItems(data.tasks))
const choices = computed(() => items.value.items.filter(item => item.id === task.value || !search.value || item.title.toLowerCase().includes(search.value.toLowerCase())))
watch(() => [route.query.focusTask, route.query.focusSub, focus.identity, items.value.items.length], () => {
  if (focus.active) return
  const match = Object.entries(items.value.refs).find(([, value]) => value.taskId === route.query.focusTask && (value.subId || '') === (route.query.focusSub || ''))
  if (match) task.value = match[0]
}, { immediate: true })
watch(() => focus.identity, () => { task.value = ''; search.value = '' })
const display = computed(() => focus.active ? focus.display : mode.value === 'stopwatch' ? '00:00' : focusTime(minutes.value * 60_000))
const progress = computed(() => focus.active?.plannedMs ? focus.elapsed / focus.active.plannedMs * 100 : 0)
const caption = computed(() => focus.active?.status === 'paused' ? '已暂停' : (focus.active?.mode ?? mode.value) === 'break' ? '休息一下' : '一次只做一件事')
const taskPickerOpen = ref(false)
const recentHistory = computed(() => focus.history.slice(0, 5))
const taskLabel = computed(() => focus.active?.taskTitle || (habitId.value ? habit.value?.name : items.value.items.find(item => item.id === task.value)?.title) || '自由专注')
watch(() => focus.active?.id, () => { taskPickerOpen.value = false })
function selectTask(id: string) { task.value = id; taskPickerOpen.value = false; search.value = '' }
function leavePicker(event: FocusEvent) { if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) taskPickerOpen.value = false }
const selectedRef = computed(() => items.value.refs[task.value])
const missingTask = computed(() => !!task.value && !selectedRef.value)
function start() { void focus.start({ mode: mode.value, minutes: minutes.value, taskId: habitId.value?'':selectedRef.value?.taskId ?? '', subId: habitId.value?'':selectedRef.value?.subId ?? '', ...(habitId.value?{habitId:habitId.value}:{}) }) }
function chooseMode(value: FocusMode) { mode.value = value; minutes.value = value === 'break' ? 5 : 25 }
function when(value: number) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
const duration = (value: number) => `${Math.floor(value / 60_000)} 分钟`
async function retry() { focus.error = ''; await focus.load() }
</script>
<template>
  <div class="focus-page wao-page-frame">
    <header class="focus-heading"><h1>番茄钟</h1><RouterLink to="/todo">回到待办 <i class="ri-arrow-right-line"></i></RouterLink></header>
    <div class="focus-feedback" aria-live="polite">
      <span v-if="focus.error || focus.draftError" class="focus-error" role="alert">{{ focus.error || focus.draftError }} <button v-if="focus.error" @click="retry">重新读取</button></span>
      <span v-else-if="!focus.ready">正在读取专注记录…</span>
    </div>
    <div class="focus-columns">
      <section class="timer-panel" aria-label="专注计时">
        <div class="focus-modes" aria-label="计时模式"><button v-for="option in ([['pomodoro','番茄计时'],['stopwatch','正计时'],['break','短休息']] as const)" :key="option[0]" :aria-pressed="(focus.active?.mode ?? mode) === option[0]" :disabled="!!focus.active || !!focus.pendingStart" @click="chooseMode(option[0])">{{ option[1] }}</button></div>
        <div class="task-picker" @focusout="leavePicker" @keydown.esc.stop="taskPickerOpen=false">
          <button class="task-trigger" aria-label="选择关联任务" :aria-expanded="taskPickerOpen" :disabled="!focus.ready || !!focus.pendingStart || !!habitId || (!!focus.active && !focus.active.taskId)" @click="focus.active ? ui.openTask(focus.active.taskId, focus.active.subId || undefined) : (taskPickerOpen=!taskPickerOpen)"><i :class="habitId ? 'ri-seedling-line' : 'ri-link'"></i><span>{{ taskLabel }}</span><i v-if="!focus.active && !habitId" class="ri-arrow-down-s-line"></i></button>
          <RouterLink v-if="habitId && !focus.active" class="unlink-habit" to="/focus">取消关联</RouterLink>
          <div v-if="taskPickerOpen" class="task-popover">
            <input v-model="search" aria-label="搜索关联任务" placeholder="搜索任务或子任务…" />
            <div class="task-options" role="group" aria-label="可关联任务">
              <button :aria-pressed="!task" @click="selectTask('')">自由专注 · 不关联任务</button>
              <button v-for="item in choices" :key="item.id" :aria-pressed="task===item.id" @click="selectTask(item.id)"><i :class="items.refs[item.id]?.subId ? 'ri-node-tree' : 'ri-checkbox-blank-circle-line'"></i><span>{{ item.title }}</span><i v-if="task===item.id" class="ri-check-line"></i></button>
              <p v-if="!choices.length">没有匹配的任务</p>
            </div>
            <small>仅显示你有权查看的任务</small>
          </div>
        </div>
        <div class="dial-space"><FocusDial :time="display" :progress="progress" :label="caption" /></div>
        <div class="duration-setting"><label for="focus-minutes">时长</label><input id="focus-minutes" :value="focus.active ? Math.round(focus.active.plannedMs / 60000) : minutes" @input="minutes=Number(($event.target as HTMLInputElement).value)" aria-label="时长（分钟）" type="number" min="1" max="180" :disabled="!!focus.active || !!focus.pendingStart || mode==='stopwatch'" /><span>分钟</span></div>
        <div class="focus-actions">
          <WaoButton class="main-action" variant="primary" :loading="focus.busy" :disabled="!focus.ready || focus.conflict || (!focus.active && (missingTask || (!!habitId&&(!habit||habit.status!=='active'||habit.kind!=='habit')) || !Number.isInteger(minutes) || minutes<1 || minutes>180))" @click="focus.active ? focus.act(focus.active.status==='running'?'pause':'resume') : start()">{{ focus.active ? focus.active.status==='running' ? '暂停' : '继续专注' : focus.pendingStart ? '重试开始' : '开始' }}</WaoButton>
          <WaoButton class="end-action" :class="{ 'action-placeholder': !focus.active }" :disabled="!focus.active || focus.conflict" :loading="focus.busy" @click="focus.act('finish')">{{ focus.active?.mode==='break' ? '结束休息' : '结束专注' }}</WaoButton>
        </div>
        <p class="timer-hint" :class="{ 'focus-error': missingTask }">{{ missingTask ? '关联任务已不可用，请重新选择。' : '切换页面或刷新，计时仍会保留。' }}</p>
      </section>
      <aside class="focus-side" aria-label="专注笔记与概览">
        <section class="note-panel">
          <WaoField label="专注笔记" v-slot="field"><textarea :id="field.id" :value="focus.note" maxlength="50000" :disabled="focus.busy || (focus.active?.mode ?? mode)==='break'" placeholder="记录这次专注的想法…" @input="focus.editNote(($event.target as HTMLTextAreaElement).value)" /></WaoField>
          <div class="note-status"><span>{{ focus.draftError ? '草稿待保护' : focus.dirty ? '本机草稿已保留' : focus.active ? '已同步' : '有内容时保存为小记 · #番茄钟' }}</span><WaoButton variant="ghost" :loading="focus.busy" :disabled="!focus.active || (!focus.dirty && !focus.conflict)" @click="focus.act('save')">{{ focus.conflict ? '同步本机笔记' : '保存笔记' }}</WaoButton></div>
        </section>
        <section class="focus-overview" aria-label="专注概览">
          <h2>概览</h2>
          <div class="focus-stats"><div><small>今日专注</small><strong>{{ focus.stats.todayCount }} <span>次</span></strong></div><div><small>今日时长</small><strong>{{ Math.floor(focus.stats.todayMs / 60_000) }} <span>分钟</span></strong></div><div><small>累计专注</small><strong>{{ focus.stats.totalCount }} <span>次</span></strong></div><div><small>累计时长</small><strong>{{ Math.floor(focus.stats.totalMs / 60_000) }} <span>分钟</span></strong></div></div>
          <section class="focus-history"><h2>专注记录 <small>最近 5 条 · 仅自己可见</small></h2><p v-if="focus.ready && !recentHistory.length" class="history-empty">还没有记录，从第一段专注开始。</p><ol><li v-for="record in recentHistory" :key="record.id"><i :class="record.mode==='break' ? 'ri-cup-line' : 'ri-timer-line'" aria-hidden="true"></i><div><strong :title="record.note || record.taskTitle">{{ record.mode==='break' ? '短休息' : record.taskTitle || '自由专注' }}</strong><small>{{ when(record.startedAt) }} — {{ when(record.endedAt) }} · {{ duration(record.elapsedMs) }}</small></div><RouterLink v-if="record.noteId" :to="{path:'/notes',query:{note:record.noteId}}" aria-label="查看小记" title="查看小记"><i class="ri-file-text-line"></i></RouterLink></li></ol></section>
        </section>
      </aside>
    </div>
  </div>
</template>
<style scoped>
.focus-page { box-sizing: border-box; container: focus-workspace / inline-size; height: 100%; min-height: 0; display: flex; flex-direction: column; padding: var(--wao-space-2); color: var(--wao-text); }
.focus-heading { display: flex; justify-content: space-between; align-items: center; gap: var(--wao-space-2); flex: none; }
h1 { font-size: var(--wao-font-title); } h2 { font-size: var(--wao-font-section); } h1,h2 { margin: 0; font-weight: var(--wao-weight-heading); line-height: var(--wao-line-heading); }
a { color: var(--wao-primary-text); text-decoration: none; } small,.timer-hint,.note-status,.focus-feedback { color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.focus-feedback { height: 28px; flex: none; overflow: auto; } .focus-error { color: var(--wao-danger); }
.focus-columns { display: grid; grid-template-columns: minmax(0,1.25fr) minmax(320px,1fr); min-height: 0; flex: 1; }
.timer-panel { display: flex; flex-direction: column; align-items: center; min-height: 0; padding: var(--wao-space-2) var(--wao-space-3); }
.focus-modes { display: flex; flex: none; padding: 4px; background: var(--wao-bg-sub); border-radius: var(--wao-radius-pill); }
button,input,textarea { font: inherit; } button { cursor: pointer; } button:disabled { cursor: default; }
.focus-modes button { min-height: 32px; padding: 4px var(--wao-space-2); border: 0; border-radius: var(--wao-radius-pill); background: transparent; color: var(--wao-text-3); }
.focus-modes button[aria-pressed=true] { color: var(--wao-primary-text); background: var(--wao-bg-card); }
.task-picker { position: relative; margin-top: var(--wao-space-3); width: min(100%,360px); text-align: center; flex: none; }
.task-trigger { display: inline-flex; align-items: center; justify-content: center; gap: var(--wao-space-1); max-width: 100%; min-height: 36px; padding: 4px var(--wao-space-1); border: 0; background: transparent; color: var(--wao-text-2); }
.task-trigger span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .unlink-habit { font-size: var(--wao-font-caption); }
.task-popover { position: absolute; z-index: 30; inset-inline: 0; top: 100%; text-align: left; padding: var(--wao-space-2); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-card); background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop); }
.task-options { max-height: 240px; overflow: auto; margin-block: var(--wao-space-1); }
.task-options button { display: flex; align-items: center; gap: var(--wao-space-1); width: 100%; min-height: 40px; border: 0; border-radius: var(--wao-radius-control); padding: var(--wao-space-1); text-align: left; background: transparent; color: var(--wao-text); }
.task-options button span { flex: 1; overflow-wrap: anywhere; } .task-options button:hover,.task-options button[aria-pressed=true] { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.dial-space { container-type: size; flex: 1; min-height: 180px; width: 100%; display: grid; place-items: center; padding-block: var(--wao-space-2); }
.dial-space :deep(.focus-dial) { width: min(100%, 340px, calc(100cqh - 20px)); margin: 0; }
.dial-space :deep(svg) { display: block; }
.duration-setting { display: flex; align-items: center; gap: var(--wao-space-1); height: 36px; flex: none; font-size: var(--wao-font-help); color: var(--wao-text-3); }
.duration-setting input { width: 64px; min-height: 32px; text-align: center; padding: 4px; } input,textarea { box-sizing: border-box; min-width: 0; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); padding: var(--wao-space-1); background: var(--wao-bg-sub); color: var(--wao-text); } .task-popover input { width: 100%; min-height: 40px; }
.focus-actions { display: grid; justify-items: center; gap: var(--wao-space-1); margin-top: var(--wao-space-2); flex: none; }
.focus-actions :deep(.wao-button) { width: 170px; border-radius: var(--wao-radius-pill); } .action-placeholder { visibility: hidden; pointer-events: none; }
.timer-hint { margin: var(--wao-space-1) 0 0; min-height: 20px; text-align: center; flex: none; }
.focus-side { min-height: 0; display: flex; flex-direction: column; gap: var(--wao-space-2); padding-left: var(--wao-space-3); border-left: 1px solid var(--wao-line); }
.note-panel { flex: none; } .note-panel textarea { width: 100%; height: clamp(72px,10dvh,110px); resize: none; line-height: var(--wao-line-body); }
.note-status { display: flex; align-items: center; justify-content: space-between; gap: var(--wao-space-1); min-height: 40px; } .note-status span { min-width: 0; }
.focus-overview { min-height: 0; flex: 1; display: flex; flex-direction: column; gap: var(--wao-space-1); }
.focus-stats { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: var(--wao-space-1); flex: none; }
.focus-stats>div { display: flex; flex-direction: column; gap: 4px; padding: var(--wao-space-1) var(--wao-space-2); border-radius: var(--wao-radius-control); background: var(--wao-bg-sub); min-width: 0; }
.focus-stats strong { line-height: 1.4; font-size: var(--wao-font-section); font-variant-numeric: tabular-nums; } .focus-stats strong span { font-size: var(--wao-font-caption); font-weight: normal; }
.focus-history { min-height: 0; flex: 1; display: flex; flex-direction: column; padding-top: var(--wao-space-1); }
.focus-history h2 { display: flex; justify-content: space-between; gap: var(--wao-space-1); flex: none; } .focus-history h2 small { font-weight: normal; }
ol { list-style: none; margin: var(--wao-space-1) 0 0; padding: 0; overflow: auto; min-height: 0; }
li { line-height: 1.35; display: flex; align-items: baseline; gap: var(--wao-space-1); padding: 6px 0; border-bottom: 1px solid var(--wao-line); font-size: var(--wao-font-help); }
li>div { flex: 1; min-width: 0; } li strong,li p { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } li small { display: block; margin-top: 4px; } li p { margin: 4px 0 0; color: var(--wao-text-3); } .history-empty { color: var(--wao-text-3); font-size: var(--wao-font-help); }
button:focus-visible,input:focus-visible,textarea:focus-visible,a:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@container focus-workspace (max-width: 720px) { .focus-columns { grid-template-columns: minmax(0,1fr); overflow: auto; } .timer-panel { min-height: 530px; padding: var(--wao-space-1); } .focus-side { border-left: 0; border-top: 1px solid var(--wao-line); padding: var(--wao-space-2) 0 0; } .focus-history { min-height: 260px; } }
@media (min-width: 821px) and (max-height: 800px) { .focus-stats { grid-template-columns: repeat(4,minmax(0,1fr)); } .focus-stats>div { padding-inline: var(--wao-space-1); } }
@media(max-width:760px) { .focus-page { height: auto; } .focus-columns { flex: none; } .timer-panel { min-height: 530px; } .focus-side { min-height: 540px; } input,textarea { font-size: var(--wao-font-card-title); } }
</style>
