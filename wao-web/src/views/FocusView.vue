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
import WaoCard from '@/components/ui/WaoCard.vue'
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
    <header class="focus-heading"><div><h1>番茄专注</h1><p>留一段时间，专心推进眼前的事。</p></div><RouterLink to="/todo">回到待办 <i class="ri-arrow-right-line"></i></RouterLink></header>
    <p v-if="focus.error" class="focus-error" role="alert">{{ focus.error }} <WaoButton @click="retry">重新读取</WaoButton></p>
    <p v-if="focus.draftError" class="focus-error" role="alert">{{ focus.draftError }}</p>
    <p v-if="!focus.ready" role="status">正在读取专注记录…</p>
    <div v-else class="focus-columns">
      <WaoCard class="timer-panel">
        <div class="focus-modes" aria-label="计时模式"><WaoButton v-for="option in ([['pomodoro','番茄计时'],['stopwatch','正计时'],['break','短休息']] as const)" :key="option[0]" :variant="(focus.active?.mode ?? mode) === option[0] ? 'primary' : 'ghost'" :disabled="!!focus.active || !!focus.pendingStart" @click="chooseMode(option[0])">{{ option[1] }}</WaoButton></div>
        <FocusDial :time="display" :progress="progress" :label="caption" />
        <div v-if="focus.active" class="current-task"><i class="ri-link" aria-hidden="true"></i><button v-if="focus.active.taskId && data.taskById(focus.active.taskId)" @click="ui.openTask(focus.active.taskId, focus.active.subId || undefined)">{{ focus.active.taskTitle }}</button><span v-else>{{ focus.active.taskTitle || '自由专注' }}</span></div>
        <div v-else class="focus-setup">
          <WaoField v-if="mode !== 'stopwatch'" label="时长（分钟）" v-slot="field"><input :id="field.id" v-model.number="minutes" type="number" min="1" max="180" :disabled="!!focus.pendingStart" /></WaoField>
          <p v-if="habitId">关联习惯：{{habit?.name||(routines.state?'习惯不存在或不可用':'正在读取…')}} <RouterLink to="/focus">取消关联</RouterLink><span v-if="routines.error" role="alert">{{routines.error}}</span></p>
          <WaoField v-else label="关联任务" hint="可以选择你有权查看的主任务或子任务" v-slot="field"><input :id="field.id" v-model="search" placeholder="搜索任务…" :disabled="!!focus.pendingStart" /><select v-model="task" aria-label="选择关联任务" :disabled="!!focus.pendingStart"><option value="">不关联任务 · 自由专注</option><option v-for="item in choices" :key="item.id" :value="item.id">{{ items.refs[item.id]?.subId ? '子任务 · ' : '' }}{{ item.title }}</option></select></WaoField>
          <p v-if="missingTask" role="alert">关联任务已不可用，请重新选择。</p>
        </div>
        <div class="focus-actions">
          <WaoButton v-if="!focus.active" variant="primary" :loading="focus.busy" :disabled="missingTask || (!!habitId&&(!habit||habit.status!=='active'||habit.kind!=='habit')) || !Number.isInteger(minutes) || minutes < 1 || minutes > 180" @click="start">{{ focus.pendingStart ? '重试开始' : '开始' }}</WaoButton>
          <template v-else><WaoButton :disabled="focus.conflict" :loading="focus.busy" @click="focus.act(focus.active.status === 'running' ? 'pause' : 'resume')">{{ focus.active.status === 'running' ? '暂停' : '继续' }}</WaoButton><WaoButton variant="primary" :disabled="focus.conflict" :loading="focus.busy" @click="focus.act('finish')">{{ focus.active.mode === 'break' ? '结束休息' : '结束并保存小记' }}</WaoButton></template>
        </div>
        <p class="timer-hint">{{ focus.active ? '切换页面或刷新，计时仍会保留。' : '倒计时结束自动保存；正计时由你结束。' }}</p>
      </WaoCard>
      <div class="focus-side">
        <WaoCard><WaoField label="专注笔记" hint="开始前或专注中都可填写，结束后保存到小记并带上「番茄钟」标签。" v-slot="field"><textarea :id="field.id" :value="focus.note" maxlength="50000" rows="7" :disabled="focus.busy || (focus.active?.mode ?? mode) === 'break'" placeholder="这次准备做什么？记下想法、进展和下一步…" @input="focus.editNote(($event.target as HTMLTextAreaElement).value)" /></WaoField><div class="note-status"><span>{{ focus.draftError ? '草稿待保护' : focus.dirty ? '本机草稿已保留' : focus.active ? '已同步' : '本机草稿' }}</span><WaoButton v-if="focus.active" variant="ghost" :loading="focus.busy" :disabled="!focus.dirty && !focus.conflict" @click="focus.act('save')">{{ focus.conflict ? '同步本机笔记' : '保存笔记' }}</WaoButton></div></WaoCard>
        <div class="focus-stats"><div><small>今日专注</small><strong>{{ focus.stats.todayCount }} <span>次</span></strong></div><div><small>今日时长</small><strong>{{ Math.floor(focus.stats.todayMs / 60_000) }} <span>分钟</span></strong></div><div><small>累计专注</small><strong>{{ focus.stats.totalCount }} <span>次</span></strong></div><div><small>累计时长</small><strong>{{ Math.floor(focus.stats.totalMs / 60_000) }} <span>分钟</span></strong></div></div>
      </div>
    </div>
    <section class="focus-history"><h2>专注记录 <small>最近 200 条 · 仅自己可见</small></h2><p v-if="focus.ready && !focus.history.length">还没有记录，从第一段专注开始。</p><ol><li v-for="record in focus.history" :key="record.id"><i :class="record.mode === 'break' ? 'ri-cup-line' : 'ri-timer-line'" aria-hidden="true"></i><div><strong>{{ record.mode === 'break' ? '短休息' : record.taskTitle || '自由专注' }}</strong><small>{{ when(record.startedAt) }} — {{ when(record.endedAt) }} · {{ duration(record.elapsedMs) }}</small><p v-if="record.note">{{ record.note }}</p></div><RouterLink v-if="record.noteId" :to="{ path: '/notes', query: { note: record.noteId } }">查看小记</RouterLink></li></ol></section>
  </div>
</template>
<style scoped>
.focus-page { max-width: 1200px; margin: 0 auto; padding: var(--wao-space-3); color: var(--wao-text); }
.focus-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--wao-space-2); margin-bottom: var(--wao-space-3); }
h1 { font-size: var(--wao-font-title); margin: 0; } h2 { font-size: var(--wao-font-section); } h1,h2 { font-weight: var(--wao-weight-heading); line-height: var(--wao-line-heading); } p { line-height: var(--wao-line-body); }
.focus-heading p { margin: var(--wao-space-1) 0 0; font-size: var(--wao-font-help); }
.focus-heading a { font-size: var(--wao-font-help); }
.focus-heading p,.timer-hint,small,.note-status { color: var(--wao-text-3); } small { font-size: var(--wao-font-caption); } a { color: var(--wao-primary-text); text-decoration: none; }
.focus-columns { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: var(--wao-space-3); }
.timer-panel { padding: var(--wao-space-3); } .focus-modes,.focus-actions { display: flex; justify-content: center; gap: var(--wao-space-1); flex-wrap: wrap; }
.focus-setup,.focus-side { display: grid; gap: var(--wao-space-2); align-content: start; } .focus-side>.wao-card { padding: var(--wao-space-3); }
input,select,textarea { box-sizing: border-box; width: 100%; min-width: 0; min-height: 44px; padding: var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-page); color: var(--wao-text); font: inherit; }
textarea { resize: vertical; line-height: var(--wao-line-body); } .focus-actions { margin-top: var(--wao-space-3); } .timer-hint { text-align: center; font-size: var(--wao-font-help); margin: var(--wao-space-1) 0 0; }
.current-task { display: flex; justify-content: center; gap: var(--wao-space-1); overflow-wrap: anywhere; } .current-task button { background: none; border: 0; color: var(--wao-primary); font: inherit; cursor: pointer; }
.note-status { display: flex; justify-content: space-between; align-items: center; gap: var(--wao-space-1); margin-top: var(--wao-space-1); font-size: var(--wao-font-caption); }
.focus-stats { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: var(--wao-space-2); } .focus-stats>div { background: var(--wao-bg-sub); border-radius: var(--wao-radius-card); padding: var(--wao-space-2); display: grid; gap: var(--wao-space-1); min-width: 0; } .focus-stats strong { font-size: var(--wao-font-stat); font-weight: var(--wao-weight-heading); line-height: var(--wao-line-heading); font-variant-numeric: tabular-nums; overflow-wrap: anywhere; } .focus-stats strong span { font-size: var(--wao-font-help); font-weight: 400; color: var(--wao-text-3); } .focus-stats small { font-size: var(--wao-font-help); }
.focus-history { margin-top: var(--wao-space-4); } .focus-history h2 { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--wao-space-1); margin: 0 0 var(--wao-space-2); } h2 small { font-size: var(--wao-font-caption); font-weight: normal; } .focus-history > p { color: var(--wao-text-3); } ol { list-style: none; padding: 0; } li { display: flex; gap: var(--wao-space-2); border-bottom: 1px solid var(--wao-line); padding: var(--wao-space-2) 0; align-items: baseline; } li>div { min-width: 0; flex: 1; overflow-wrap: anywhere; } li small { display: block; margin-top: var(--wao-space-1); } li p { white-space: pre-wrap; max-height: 5em; overflow: auto; margin-bottom: 0; } .focus-error { color: var(--wao-danger); }
@media(max-width: 760px) { .focus-page { padding: var(--wao-space-2); } .focus-columns { grid-template-columns: minmax(0,1fr); } .timer-panel,.focus-side>.wao-card { padding: var(--wao-space-2); } input,select,textarea { font-size: 16px; } .focus-heading { align-items: start; } .focus-heading a { flex-shrink: 0; } li { flex-wrap: wrap; } }
</style>
