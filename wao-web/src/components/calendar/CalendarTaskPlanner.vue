<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { Task, Project } from '@/types'
import { scheduleSnapshot, sameSchedule, type TaskSchedule } from '@/utils/taskSchedule'
import { scheduleSummary, validateSchedule } from '@/utils/calendarPlanning'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
const props = defineProps<{
  tasks: Task[]; projects: Project[]; identity: string; date: string; loading: boolean; loadError: boolean
  save: (id: string, after: TaskSchedule, expectedVersion: string | undefined, before: TaskSchedule) => Promise<boolean>
}>()
const emit = defineEmits<{ drag: [id: string, event: DragEvent]; dragend: []; open: [id: string]; retry: []; close: [] }>()
const query = ref(''), scope = ref<'unplanned' | 'all'>('unplanned'), project = ref('*'), count = ref(50)
const id = ref(''), mode = ref<'day' | 'time'>('day'), day = ref(''), start = ref(''), end = ref('')
const before = ref<TaskSchedule>({}), version = ref<string>(), busy = ref(false), error = ref(''), draftError = ref(''), restored = ref(false)
const editor = ref<HTMLElement>()
let identityEpoch = 0
const selected = computed(() => props.tasks.find(task => task.id === id.value))
const changedElsewhere = computed(() => !!selected.value && (selected.value.editVersion !== version.value || !sameSchedule(selected.value, before.value)))
const candidates = computed(() => props.tasks.filter(task => (scope.value === 'all' || (!task.start && !task.startAt))
  && (project.value === '*' || task.project === project.value)
  && (!query.value.trim() || `${task.title} ${props.projects.find(p => p.id === task.project)?.name || ''}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase()))))
const visible = computed(() => candidates.value.slice(0, count.value))
const draftKey = computed(() => `wao-calendar-plan:${props.identity}:${id.value}`)
const proposal = computed<TaskSchedule>(() => mode.value === 'time'
  ? { start: start.value.slice(0, 10), startAt: start.value || null, endAt: end.value || null, schedulePeriod: null }
  : { start: day.value, startAt: null, endAt: null, schedulePeriod: null })
watch([query, scope, project], () => { count.value = 50 })
watch(() => props.identity, () => { identityEpoch++; id.value = ''; query.value = ''; project.value = '*'; error.value = ''; busy.value = false })
function remember() {
  if (!id.value) return
  try { localStorage.setItem(draftKey.value, JSON.stringify({ mode: mode.value, day: day.value, start: start.value, end: end.value, before: before.value, version: version.value })); draftError.value = '' }
  catch { draftError.value = '本机草稿未能保存，请保存后再离开页面。' }
}
async function editTask(taskId: string, restore = true) {
  if (busy.value) return
  const task = props.tasks.find(task => task.id === taskId)
  if (!task) return
  id.value = taskId; before.value = scheduleSnapshot(task); version.value = task.editVersion
  mode.value = task.startAt ? 'time' : 'day'; day.value = task.start || props.date
  start.value = task.startAt?.slice(0, 16) || `${props.date}T09:00`; end.value = task.endAt?.slice(0, 16) || `${props.date}T10:00`
  error.value = ''; draftError.value = ''; restored.value = false
  if (!restore) try { localStorage.removeItem(draftKey.value) } catch { draftError.value = '本机旧草稿未能清除，请保存后再离开页面。' }
  if (restore) try {
    const saved = JSON.parse(localStorage.getItem(draftKey.value) || 'null')
    if (saved && ['day', 'time'].includes(saved.mode) && typeof saved.day === 'string' && typeof saved.start === 'string' && typeof saved.end === 'string' && saved.before) {
      mode.value = saved.mode; day.value = saved.day; start.value = saved.start; end.value = saved.end; before.value = saved.before; version.value = saved.version; restored.value = true
    }
  } catch { draftError.value = '旧草稿无法读取，当前显示服务器记录。' }
  await nextTick(); editor.value?.scrollIntoView?.({ block: 'nearest' }); editor.value?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true })
}
function closeEditor() { if (!busy.value) { remember(); id.value = '' } }
async function submit(clear = false) {
  if (!selected.value || busy.value) return
  const after = clear ? { start: '', startAt: null, endAt: null, schedulePeriod: null } : proposal.value
  if (!clear && !after.start) { error.value = '请选择执行日期'; return }
  error.value = validateSchedule(after)
  if (error.value) return
  if (changedElsewhere.value) { error.value = '任务已有后续修改，请先重新读取，再应用本次安排。'; return }
  remember(); busy.value = true
  const identity = props.identity, epoch = identityEpoch, taskId = id.value, key = draftKey.value
  try {
    const saved = await props.save(taskId, after, version.value, before.value)
    if (props.identity !== identity || identityEpoch !== epoch || id.value !== taskId) return
    if (!saved) { error.value = '执行安排尚未保存。请在页面上方重试，或打开任务处理保存冲突。'; return }
    try { localStorage.removeItem(key) } catch { /* Confirmed server save remains authoritative. */ }
    id.value = ''; restored.value = false
  } catch (e) { if (props.identity === identity && identityEpoch === epoch) error.value = e instanceof Error ? e.message : '安排未保存，请重试。' }
  finally { if (props.identity === identity && identityEpoch === epoch) busy.value = false }
}
defineExpose({ editTask })
</script>
<template>
  <aside class="calendar-task-planner" aria-label="任务排期">
    <header><strong>任务排期</strong><WaoButton variant="ghost" aria-label="收起任务排期" @click="emit('close')"><i class="ri-close-line" /></WaoButton></header>
    <p class="hint">拖到日历安排时间，或点“安排”精确设置。截止日期保持不变。</p>
    <div class="planner-filters"><label>范围<select v-model="scope"><option value="unplanned">未安排执行时间</option><option value="all">全部未完成任务</option></select></label><label>清单<select v-model="project"><option value="*">所有清单</option><option value="">收集箱</option><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option></select></label><label class="planner-query">搜索<input v-model="query" type="search" placeholder="任务或清单名称" /></label></div>
    <p v-if="loadError" role="alert">任务读取失败，结果可能不完整。<WaoButton @click="emit('retry')">重试</WaoButton></p>
    <p v-else-if="loading" role="status">正在读取任务…</p>
    <div v-if="id" ref="editor" class="planner-editor" @input="remember" @change="remember">
      <template v-if="selected"><strong>{{ selected.title }}</strong><small>上海时间 · {{ selected.due ? `截止 ${selected.due}` : '无截止日期' }}</small>
      <label>安排方式<select v-model="mode" :disabled="busy"><option value="day">全天</option><option value="time">时间段</option></select></label>
      <WaoField v-if="mode==='day'" label="执行日期" v-slot="field"><input :id="field.id" v-model="day" type="date" :disabled="busy" /></WaoField>
      <template v-else><WaoField label="开始时间" v-slot="field"><input :id="field.id" v-model="start" type="datetime-local" :disabled="busy" /></WaoField><WaoField label="结束时间" v-slot="field"><input :id="field.id" v-model="end" type="datetime-local" :disabled="busy" /></WaoField></template>
      <small v-if="restored">已恢复本机未提交安排</small>
      <p v-if="changedElsewhere" role="alert">任务已被其他操作修改。<WaoButton :disabled="busy" @click="editTask(id,false)">重新读取</WaoButton></p>
      <p v-if="error || draftError" role="alert">{{ error || draftError }}</p>
      <div class="planner-actions"><WaoButton :disabled="busy || changedElsewhere" @click="submit()">{{ busy ? '保存中…' : '保存安排' }}</WaoButton><WaoButton :disabled="busy || changedElsewhere || (!selected.start && !selected.startAt)" @click="submit(true)">取消排期</WaoButton><WaoButton :disabled="busy" @click="closeEditor">收起</WaoButton></div></template>
      <p v-else role="status">任务已完成或不在当前成员范围内。<WaoButton @click="closeEditor">收起</WaoButton></p>
    </div>
    <div class="planner-list"><p class="hint">{{ candidates.length }} 项</p><p v-if="!loading && !loadError && !candidates.length">{{ query || project!=='*' ? '没有符合筛选的任务' : '没有待安排的任务' }}</p>
      <article v-for="task in visible" :key="task.id" class="planner-task" :data-planner-task="task.id" :draggable="!busy" @dragstart="emit('drag',task.id,$event)" @dragend="emit('dragend')"><button class="task-title" @click="emit('open',task.id)">{{ task.title }}</button><small>{{ projects.find(p=>p.id===task.project)?.name || '收集箱' }} · {{ task.pr }}</small><small>{{ scheduleSummary(task) }}<template v-if="task.due"> · 截止 {{ task.due }}</template></small><WaoButton :disabled="busy" @click="editTask(task.id)">安排</WaoButton></article>
      <WaoButton v-if="visible.length<candidates.length" @click="count+=50">继续显示 {{ Math.min(50,candidates.length-visible.length) }} 项</WaoButton>
    </div>
  </aside>
</template>
<style scoped>
.calendar-task-planner { flex:0 0 300px; width:300px; min-width:0; max-height:100%; overflow:auto; box-sizing:border-box; border:1px solid var(--wao-line); border-radius:var(--wao-radius-card); padding:var(--wao-space-2); color:var(--wao-text); background:var(--wao-bg-card); font:400 var(--wao-font-body)/1.5 var(--wao-font); }
header,.planner-actions { display:flex; align-items:center; justify-content:space-between; gap:var(--wao-space-1); flex-wrap:wrap; } header strong { font-size:var(--wao-font-section); }
.hint,small { color:var(--wao-text-3); font-size:var(--wao-font-caption); } small { display:block; } p { margin-block:var(--wao-space-1); }
.planner-filters { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:var(--wao-space-1); } .planner-query { grid-column:1/-1; }
label { display:grid; min-width:0; gap:4px; font-size:var(--wao-font-help); } input,select { width:100%; min-width:0; box-sizing:border-box; padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); color:var(--wao-text); background:var(--wao-bg-card); font:inherit; }
.planner-editor { display:grid; gap:var(--wao-space-1); padding-block:var(--wao-space-2); border-block:1px solid var(--wao-line); margin-top:var(--wao-space-2); } .planner-editor strong { overflow-wrap:anywhere; }
.planner-task { padding-block:var(--wao-space-1); border-bottom:1px solid var(--wao-line); } .planner-task>button:last-child { margin-top:4px; }
.task-title { display:block; width:100%; padding:0; text-align:left; background:transparent; border:0; color:var(--wao-text); font:inherit; overflow-wrap:anywhere; cursor:pointer; } [role=alert] { color:var(--wao-danger-strong); }
button:focus-visible,input:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(max-width:900px) { .calendar-task-planner { width:auto; flex:none; max-height:55dvh; } }
@container app-main (max-width:900px) { .calendar-task-planner { width:auto; flex:none; max-height:55dvh; } }
@media(pointer:coarse) { input,select,.task-title { min-height:44px; } }
</style>
