<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useDayWorkspaceStore } from '@/stores/dayWorkspace'
import { isTaskFinished } from '@/utils/projectCompletion'
import { legacyProjection } from '@/utils/richDoc'
import { PreviousTodoWriteRecovered, saveTodoWithReceipt, writeDayNote, type DayNoteWrite, type DayTask, type NoteSubmission } from '@/api/dayWorkspace'
import InlineSubtasks from '@/components/task/InlineSubtasks.vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import TaskSchedulePopover from './TaskSchedulePopover.vue'
import DailyRoutineSection from './DailyRoutineSection.vue'

const props = defineProps<{ date: string }>()
const emit = defineEmits<{ 'update:date': [date: string]; all: [] }>()
const data = useDataStore(), session = useSessionStore(), ui = useUiStore(), days = useDayWorkspaceStore(), router = useRouter()
const day = computed(() => days.days[props.date])
const past = computed(() => props.date < data.today), future = computed(() => props.date > data.today)
const heading = computed(() => new Date(`${props.date}T12:00:00+08:00`).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Shanghai' }))
const noteFor = ref<string | null>(null), expanded = ref<Record<string, boolean>>({}), title = ref(''), createError = ref(''), creating = ref(false)
const createProject = ref('')
const identity = () => `${session.account?.accountId ?? ''}:${session.userId}`
const newKey = () => `wao-day-task-draft-v1:${identity()}:${props.date}`
let createId = ''
watch(() => [props.date, identity()], () => {
  noteFor.value = null; title.value = ''; createError.value = ''; createId = ''; createProject.value = ''
  try { const draft = JSON.parse(localStorage.getItem(newKey()) || 'null') as { id: string; title: string; project?: string } | null; if (draft) { title.value = draft.title; createId = draft.id; createProject.value = draft.project || '' } } catch { /* Preserve corrupted draft without overwriting it. */ }
  void days.load(props.date)
}, { immediate: true })
watch([title, createProject], ([value]) => {
  if (!value.trim()) return
  createId ||= crypto.randomUUID().replace(/-/g, '')
  try { localStorage.setItem(newKey(), JSON.stringify({ id: createId, title: value, project: createProject.value })) }
  catch { createError.value = '无法保护本机标题草稿，请保持页面并保存' }
})
const current = (entry: DayTask) => data.taskById(entry.task.id) ?? entry.task
const shown = (entry: DayTask) => past.value ? { ...entry.task, ...entry.snapshot } : current(entry)
function move(offset: number) { emit('update:date', new Date(Date.parse(`${props.date}T00:00:00Z`) + offset * 86400000).toISOString().slice(0, 10)) }
async function refresh(date = props.date) { days.invalidate(); await days.load(date, true) }
async function createTask() {
  if (creating.value || !title.value.trim()) return
  const date = props.date, key = newKey(), account = identity(), submitted = title.value.trim(), project = createProject.value
  createId ||= crypto.randomUUID().replace(/-/g, '')
  creating.value = true; createError.value = ''
  try {
    if (!data.apiLive) throw new Error('服务尚未连接，标题草稿已保留')
    if (!project || !data.projects.some(item => item.id === project)) throw new Error('请选择任务所属项目，标题草稿已保留')
    const existing = data.taskById(createId)
    if (existing) {
      if (existing.title !== submitted || existing.project !== project) data.patchTask(createId, { title: submitted, project })
      await data.flushTask(createId)
      if (data.taskSaveState(createId) !== 'saved') throw new Error(data.taskSaveError(createId) || '任务尚未保存，请重试')
      if (data.taskById(createId)?.title !== submitted) {
        data.patchTask(createId, { title: submitted })
        await data.flushTask(createId)
        if (data.taskSaveState(createId) !== 'saved') throw new Error(data.taskSaveError(createId) || '新标题尚未保存，请重试')
      }
    }
    else {
      const result = await data.addTask({ title: submitted, project, pr: 'P2', owner: session.userId, due: '', start: date, desc: '' }, createId)
      if (!result.persisted) throw new Error(data.taskSaveError(createId) || '任务尚未保存，原地重试不会重复创建')
    }
    if (identity() === account && props.date === date && title.value.trim() === submitted) {
      localStorage.removeItem(key); title.value = ''; createId = ''
    }
    await refresh(date)
  } catch (cause) { if (identity() === account && props.date === date) createError.value = cause instanceof Error ? cause.message : '任务保存失败，标题已保留' }
  finally { creating.value = false }
}
async function toggle(entry: DayTask) {
  if (past.value || future.value) return
  const date = props.date
  data.toggleComplete(entry.task.id)
  await data.flushTask(entry.task.id)
  if (data.taskSaveState(entry.task.id) === 'saved') await refresh(date)
}
async function submitNote(request: NoteSubmission, taskId?: string) {
  const date = props.date, account = identity()
  const perform = async (version?: string) => {
    const payload = { ...request, blocks: legacyProjection(request.content), businessDate: date, ...(taskId ? { taskId, expectedTaskEditVersion: version } : {}),
      ...(data.docById(request.docId)?.rev ? { expectedDocRev: data.docById(request.docId)!.rev } : {}) }
    try {
      return await saveTodoWithReceipt<DayNoteWrite>(`note:${request.docId}`, account, payload,
        (operation, body) => writeDayNote(operation, body as typeof payload), identity)
    } catch (cause) {
      if (cause instanceof PreviousTodoWriteRecovered) {
        const recovered = cause.result as DayNoteWrite
        data.applyRemoteDoc(recovered.doc)
      }
      throw cause
    }
  }
  const result = taskId ? await data.runTaskOperation(taskId, base => perform(base.editVersion)) : await perform()
  if (identity() !== account) throw new Error('成员已切换，原保存结果仍保留')
  data.applyRemoteDoc(result.doc)
  void data.refreshKnowledge()
  await refresh(date)
  return result.doc
}
let refreshTimer: ReturnType<typeof setTimeout>
const unsubscribe = data.$subscribe((_mutation, state) => {
  if (days.states[props.date] === 'loaded' && state.tasks.some(task => task.editVersion !== day.value?.tasks.find(item => item.task.id === task.id)?.task.editVersion
    && day.value?.tasks.some(item => item.task.id === task.id))) {
    clearTimeout(refreshTimer); refreshTimer = setTimeout(() => void days.load(props.date, true), 700)
  }
})
onBeforeUnmount(() => { unsubscribe(); clearTimeout(refreshTimer) })
</script>

<template>
  <section class="day-workspace" aria-label="待办按天视图">
    <header class="day-heading"><div><h2>{{ heading }}</h2><p>{{ past ? '回看这一天留下的真实记录' : future ? '先安排，实际进展留给当天' : '先安排要做的事，实际进展留给今天' }}</p></div><nav aria-label="切换日期"><button type="button" aria-label="前一天" @click="move(-1)">‹</button><button type="button" @click="emit('update:date', data.today)">今天</button><button type="button" aria-label="后一天" @click="move(1)">›</button><input :value="date" type="date" aria-label="选择待办日期" @change="emit('update:date', ($event.target as HTMLInputElement).value || date)" /></nav></header>
    <div v-if="days.states[date] === 'loading' && !day" class="day-state" role="status">正在读取当天记录…</div>
    <div v-else-if="days.states[date] === 'error'" class="day-state" role="alert"><b>当天记录暂时没有加载出来</b><p>{{ days.errors[date] }}</p><button type="button" @click="days.load(date, true)">重新加载</button><button type="button" @click="emit('all')">查看全部待办</button></div>
    <template v-else-if="day">
      <p v-if="day.historyCoverage === 'BEFORE_BASELINE'" class="coverage" role="status">这一天早于可靠记录起点。可查看有依据的小记，不根据当前状态补造任务历史。</p>
      <div class="day-overview"><span>{{ day.summary.plannedCount }} 件安排</span><span>{{ day.summary.finishedCount }} 件完成记录</span><span>{{ day.summary.stepDoneCount }} 项步骤完成</span><span>{{ day.summary.noteCount }} 条记录</span><span v-if="days.states[date] === 'loading'" role="status">正在刷新…</span></div>
      <DailyRoutineSection :date="date" />
      <div v-if="!day.tasks.length" class="day-empty">{{ past ? '这一天还没有可靠的任务记录' : '这一天还没有安排，写下一件想做的事' }}</div>
      <article v-for="(entry, index) in day.tasks" :key="entry.task.id" class="day-task">
        <span class="task-index">{{ String(index + 1).padStart(2, '0') }}</span><div class="day-task-main">
          <div class="task-heading"><button v-if="!past && !future" type="button" class="task-check" :aria-label="`完成或重新打开：${shown(entry).title}`" :aria-pressed="isTaskFinished(current(entry))" @click="toggle(entry)">{{ isTaskFinished(current(entry)) ? '✓' : '○' }}</button><button class="task-title" type="button" @click="ui.openTask(entry.task.id)">{{ shown(entry).title }}</button><span class="task-status">{{ shown(entry).status }}</span></div>
          <p v-if="!entry.planned" class="task-trace">当天留下了进展，未安排在这一天</p>
          <TaskSchedulePopover v-if="!past" :key="`${identity()}:${entry.task.id}`" :task="current(entry)" :day="date" @saved="refresh()" />
          <p v-else class="past-schedule">当日安排：{{ entry.snapshot.startAt ? entry.snapshot.startAt.slice(11, 16) : entry.snapshot.schedulePeriod === 'MORNING' ? '早晨' : entry.snapshot.schedulePeriod === 'AFTERNOON' ? '下午' : entry.snapshot.schedulePeriod === 'EVENING' ? '晚上' : entry.snapshot.start ? '不定时' : '未排期' }}</p>
          <div class="task-actions"><button v-if="shown(entry).subs?.length" type="button" :aria-expanded="!!expanded[entry.task.id]" @click="expanded[entry.task.id] = !expanded[entry.task.id]">{{ expanded[entry.task.id] ? '收起子事项' : `${shown(entry).subs.length} 项子事项` }}</button><button v-if="!future" type="button" @click="noteFor = noteFor === entry.task.id ? null : entry.task.id">{{ noteFor === entry.task.id ? '收起记录' : past ? '补记进展' : '记进展' }}</button><button v-if="!past" type="button" @click="ui.openTask(entry.task.id)">拆分与详情</button></div>
          <InlineSubtasks v-if="expanded[entry.task.id] && !past && !future && data.taskById(entry.task.id)" :task-id="entry.task.id" />
          <ul v-else-if="expanded[entry.task.id]" class="historical-subs"><li v-for="(sub, subIndex) in shown(entry).subs" :key="sub.id || subIndex">{{ sub.done ? '✓' : '○' }} {{ sub.text }}</li></ul>
          <p v-if="data.taskSaveState(entry.task.id) === 'saving'" role="status">正在保存任务…</p><p v-if="data.taskSaveState(entry.task.id) === 'error'" class="task-error" role="alert">{{ data.taskSaveError(entry.task.id) }} <button type="button" @click="ui.openTask(entry.task.id)">处理保存</button></p>
          <NoteComposer v-if="noteFor === entry.task.id" :key="`${identity()}:${date}:${entry.task.id}`" compact :draft-scope="`day:${identity()}:${date}:${entry.task.id}`" :submit-note="request => submitNote(request, entry.task.id)" />
          <div v-if="entry.events.length" class="day-events"><span v-for="event in entry.events" :key="event.id">{{ event.summary }}<small>{{ new Date(event.occurredAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai' }) }}{{ event.late ? ' · 后来补记' : '' }}</small></span></div>
        </div>
      </article>
      <form v-if="!past" class="day-create" @submit.prevent="createTask"><input v-model="title" aria-label="当天要做的事" placeholder="＋ 写下当天要做的一件事" :disabled="creating" maxlength="500" /><select v-model="createProject" aria-label="任务所属项目" :disabled="creating"><option value="">选择项目</option><option v-for="project in data.projects" :key="project.id" :value="project.id">{{ project.name }}</option></select><button type="submit" :disabled="creating || !title.trim()">{{ creating ? '保存中…' : '添加' }}</button></form><p v-if="createError" class="task-error" role="alert">{{ createError }}</p>
      <section class="day-notes"><header><h3>{{ past ? '当天记录与补记' : '随手记' }}</h3><button v-if="!future" type="button" @click="noteFor = noteFor === '' ? null : ''">{{ noteFor === '' ? '收起输入' : past ? '补记这一天' : '写一条' }}</button></header><NoteComposer v-if="noteFor === '' && !future" :key="`${identity()}:${date}:free`" compact :draft-scope="`day:${identity()}:${date}:free`" :submit-note="request => submitNote(request)" />
        <button v-for="note in day.notes" :key="note.docId" class="day-note-link" type="button" @click="router.push({ path: '/notes', query: { note: note.docId } })"><i class="ri-sticky-note-line"></i><span>{{ data.docById(note.docId)?.title || note.title }}</span><small>{{ note.late ? '补记 · ' : '' }}{{ note.updatedAt !== note.createdAt ? '后来有修改' : '已保存' }}</small></button><p v-if="!day.notes.length" class="day-empty">{{ future ? '实际记录留给当天' : '还没有记录，想到什么就记下来' }}</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.day-workspace{max-width:1000px;margin:20px auto;min-width:0}.day-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;padding-bottom:18px;border-bottom:1px solid var(--wao-line-control)}h2{margin:0;font-size:24px;font-weight:700}.day-heading p{margin:8px 0 0;color:var(--wao-text-3);font-size:12px}nav{display:flex;gap:5px;flex-wrap:wrap}button,input,select{font:inherit}.day-create select{max-width:180px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text-1);min-height:40px}nav button,nav input,.day-create button,.day-state button{border:1px solid var(--wao-line-control);border-radius:7px;padding:7px 10px;background:var(--wao-bg-card);color:var(--wao-primary)}nav input{width:140px;min-width:0}.day-overview{display:flex;gap:15px;flex-wrap:wrap;color:var(--wao-text-3);font-size:12px;padding:18px 0}.day-task{display:flex;gap:16px;padding:21px 0;border-bottom:1px solid var(--wao-line-control)}.task-index{font-size:12px;color:var(--wao-primary);font-weight:700;padding-top:6px}.day-task-main{min-width:0;flex:1}.task-heading{display:flex;align-items:flex-start;gap:8px;margin-bottom:8px}.task-title{border:0;background:none;font-weight:650;color:var(--wao-text-1);text-align:left;overflow-wrap:anywhere;padding:3px 0;flex:1;min-width:0}.task-check{border:0;background:none;color:var(--wao-primary);font-size:19px;padding:0;min-width:24px}.task-status{font-size:11px;color:var(--wao-text-3);white-space:nowrap;padding-top:5px}.task-actions{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0}.task-actions button,.day-notes header button{border:0;background:none;padding:4px 0;font-size:12px;color:var(--wao-primary)}.task-trace,.past-schedule{font-size:12px;color:var(--wao-text-3)}.day-create{display:flex;flex-wrap:wrap;gap:8px;margin:18px 0}.day-create input{min-width:0;flex:1;padding:10px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text-1)}.day-events{display:flex;gap:8px 15px;flex-wrap:wrap;font-size:11px;color:var(--wao-text-3);margin-top:12px}.day-events small{margin-left:5px}.day-notes{margin-top:30px}.day-notes header{display:flex;justify-content:space-between;align-items:center}h3{font-size:14px}.day-note-link{display:flex;align-items:center;gap:8px;width:100%;border:0;border-bottom:1px solid var(--wao-line-control);background:none;text-align:left;padding:13px 0;color:var(--wao-text-1)}.day-note-link span{flex:1;min-width:0;overflow-wrap:anywhere}.day-note-link small{font-size:11px;color:var(--wao-text-3)}.coverage{font-size:12px;padding:12px;border-radius:8px;background:var(--wao-bg-hover);color:var(--wao-text-2)}.day-empty,.day-state{font-size:13px;color:var(--wao-text-3);padding:15px 0}.day-state button{margin-right:8px}.task-error{font-size:12px;color:var(--wao-danger,#ce4c65)}.historical-subs{padding-left:18px;line-height:1.8;overflow-wrap:anywhere}button{cursor:pointer}button:focus-visible,input:focus-visible{outline:2px solid var(--wao-primary);outline-offset:2px}button:disabled{opacity:.55;cursor:default}@media(max-width:600px){.day-heading h2{font-size:21px}.day-heading nav{width:100%}.day-heading nav button,.day-heading nav input{min-height:44px;box-sizing:border-box}.day-task{gap:9px}.task-actions button,.task-check,.day-notes header button,.day-create button{min-height:44px}.day-note-link{flex-wrap:wrap}.day-note-link small{padding-left:25px}.task-status{max-width:65px;white-space:normal}.day-overview{gap:8px 12px}}
</style>
