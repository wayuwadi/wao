<script setup lang="ts">
import { useProjectItemActions } from '@/composables/projectItemActions'
import { withProjectAncestors, type ProjectItemRefs } from '@/utils/projectWorkItems'
import { taskTreeRows } from '@/utils/taskHierarchy'
import TaskOrderStatus from './TaskOrderStatus.vue'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { taskDropAfter } from '@/utils/taskDrop'
import { computed, ref, watch } from 'vue'
import { useMemberPreference } from '@/composables/memberPreference'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { defaultQuadrantRules, decodeQuadrantRules, type QuadrantRule } from '@/utils/taskQuadrantRules'
import { matchesTaskWorkspaceFilter } from '@/utils/taskWorkspaceFilter'
import TaskFilterSelect from './TaskFilterSelect.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useDeco } from '@/composables/deco'
import { useSettle } from '@/composables/completion'
import { TASK_QUADRANTS } from '@/utils/taskQuadrants'
import SaveHint from '@/components/ui/SaveHint.vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { copyText } from '@/utils/clipboard'
import { shareUrl } from '@/utils/share'
import type { Priority, Task } from '@/types'
import { isTaskFinished } from '@/utils/projectCompletion'

const props = defineProps<{ tasks: Task[]; selectedIds: string[]; showDone: boolean; selectionMode?: boolean; projectId?: string; itemRefs?: ProjectItemRefs; contextTasks?: Task[]; tagOptions?: string[] }>()
const emit = defineEmits<{ select: [id: string]; 'update:showDone': [value: boolean] }>()
const data = useDataStore()
const order = useTaskOrderStore()
const orderScope = computed(() => props.projectId ? `project:${props.projectId}` : 'all')
const orderingOver = ref<{ id: string; after: boolean } | null>(null)
const ui = useUiStore()
const itemActions = useProjectItemActions(() => props.itemRefs)
const sourceId = itemActions.sourceId
function openItem(id: string) { itemActions.open(id) }
function locateParent(id: string) {
  const key = props.itemRefs?.[id]?.parentKey
  if (!key) return
  const parent = props.tasks.find(t => t.id === key)
  if (parent) {
    expanded.value = null
    requestAnimationFrame(() => {
      const element = document.getElementById(`quadrant-item-${key}`)
      if (element) { element.scrollIntoView({ block: 'nearest' }); element.focus() }
      else openItem(key)
    })
  }
  else openItem(key)
}
const contextMenu = ref<InstanceType<typeof ContextActionMenu> | null>(null)
const { decoTask } = useDeco()
const { begin, cancel, isSettling } = useSettle()
const dragId = ref<string | null>(null)
const dropTarget = ref<Priority | null>(null)
const expanded = ref<Priority | null>(null)
const savingIds = ref<string[]>([])
const touchedIds = ref<string[]>([])
const finished = isTaskFinished
const doneCount = computed(() => props.tasks.filter(finished).length)
const busy = (id: string) => savingIds.value.includes(id) || itemActions.busy(id)
// 完成请求未落库或失败时仍留在原象限，始终提供重试入口。
const treeRows = computed(() => {
  const matches = props.tasks.filter(task => props.showDone || !finished(task) || isSettling(task.id)
    || busy(task.id) || data.taskSaveState(sourceId(task.id)) === 'error')
  const visible = props.itemRefs ? withProjectAncestors(matches, props.contextTasks ?? props.tasks) : matches
  const sorted = order.sort(orderScope.value, visible, t => t.id)
  if (!props.itemRefs) return sorted.map(task => ({ ...decoTask(task), depth: 0, quadrantPriority: task.pr }))
  let priority: Priority = 'P2'
  return taskTreeRows(sorted, []).map(({ task, depth }) => {
    if (!depth) priority = task.pr
    return { ...decoTask(task), depth, quadrantPriority: priority }
  })
})
const labels = useTaskLabelsStore()
watch(() => labels.identity, () => { if (!labels.ready && !labels.loading) void labels.load() }, {immediate:true})
const rules = useMemberPreference(computed(()=>`task-quadrants-v1:${props.projectId ?? 'personal'}`),decodeQuadrantRules,JSON.stringify)
const editingRule = ref<number|null>(null)
const draftRule = ref<QuadrantRule>(defaultQuadrantRules()[0]!)
watch([()=>labels.identity,()=>props.projectId],()=>{editingRule.value=null;draftRule.value=defaultQuadrantRules()[0]!})
const customized = computed(()=>rules.value.value.some(r=>r.custom))
function editRule(index:number) { draftRule.value=JSON.parse(JSON.stringify(rules.value.value[index]!)); editingRule.value=index }
function saveRule() { if(editingRule.value===null)return; void rules.set(rules.value.value.map((r,i)=>i===editingRule.value?{...draftRule.value,custom:true}:r));editingRule.value=null }
function resetRule() { if(editingRule.value===null)return; void rules.set(rules.value.value.map((r,i)=>i===editingRule.value?defaultQuadrantRules()[i]!:r));editingRule.value=null }
const sections = computed(() => TASK_QUADRANTS.map((quadrant,index) => {
 const rule=rules.value.value[index]!
 const candidates=new Set(props.tasks.map(t=>t.id))
 const matching=rule.custom?treeRows.value.filter(task=>{ if(!candidates.has(task.id))return false; const ref=props.itemRefs?.[task.id]; return matchesTaskWorkspaceFilter(task,labels.get(ref?.taskId??task.id,ref?.subId).tags,rule,data.today) }):treeRows.value.filter(task=>task.quadrantPriority===quadrant.priority)
 const visible=props.itemRefs?withProjectAncestors(matching,treeRows.value):matching
 const ids=new Set(matching.map(t=>t.id))
 return {...quadrant,label:rule.name,tasks:visible.map(t=>({...t,contextOnly:!ids.has(t.id)})),count:rule.custom?matching.length:matching.filter(t=>!t.depth).length}
}))
const isChild = (id: string) => !!props.itemRefs?.[id]?.parentKey
async function save(id: string, change?: () => void) {
  if (!data.taskById(sourceId(id)) || busy(id)) return
  savingIds.value.push(id)
  if (!touchedIds.value.includes(id)) touchedIds.value.push(id)
  try {
    change?.()
    await data.flushTask(sourceId(id))
  } finally { savingIds.value = savingIds.value.filter((value) => value !== id) }
}
async function setPriority(id: string, priority: Priority) {
  if (isChild(id) || customized.value) return
  await save(id, () => {
    const ref = itemActions.source(id)
    if (ref.subId) data.updateSub(ref.taskId, ref.subId, { pr: priority })
    else if (data.taskById(id)?.pr !== priority) data.patchTask(id, { pr: priority }, `将优先级改为「${priority}」`)
  })
}
async function toggleComplete(id: string) {
  await save(id, () => {
    const ref = itemActions.source(id)
    if (ref.subId) data.setSubState(ref.taskId, ref.subId, data.subById(ref.taskId, ref.subId)?.done ? '未开始' : '已完成')
    else data.toggleComplete(id)
    if (ref.subId ? data.subById(ref.taskId, ref.subId)?.done : data.taskById(id)?.status === '已完成') begin(id)
    else cancel(id)
  })
}
function startDrag(event: DragEvent, id: string) {
  if (busy(id) || isChild(id)) { event.preventDefault(); return }
  dragId.value = id
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', id)
  }
}
function endDrag() { dragId.value = null; dropTarget.value = null; orderingOver.value = null }
function drop(priority: Priority) {
  const id = dragId.value
  endDrag()
  if (id && props.tasks.some((task) => task.id === id)) void setPriority(id, priority)
}
async function dropOnTask(event: DragEvent, target: Task) {
  const id = dragId.value, after = taskDropAfter(event)
  endDrag()
  const source = props.tasks.find(t => t.id === id)
  if (!source || source.id === target.id || busy(source.id) || isChild(source.id) || isChild(target.id)) return
  if (source.pr !== target.pr) await setPriority(source.id, target.pr)
  if (itemActions.isSub(source.id) || itemActions.isSub(target.id)) return
  await order.move(orderScope.value, props.tasks.filter(t => !itemActions.isSub(t.id)).map(t => t.id), source.id, target.id, after)
}
function createTask(priority: Priority) {
  ui.openModal('task', props.projectId ?? '')
  ui.modalPriority = priority
}
function openTaskMenu(event: MouseEvent | KeyboardEvent, id: string) {
  const ref = itemActions.source(id)
  const task = props.tasks.find(t => t.id === id)
  if (!task) return
  if (event instanceof KeyboardEvent) {
    if (!(event.shiftKey && event.key === 'F10') && event.key !== 'ContextMenu') return
    event.preventDefault()
  } else {
    if (event.target instanceof Element && event.target.closest('select, input, textarea, a, img, [contenteditable="true"]')) return
    const selection = window.getSelection()
    if (selection && !selection.isCollapsed && selection.anchorNode && event.currentTarget instanceof Node && event.currentTarget.contains(selection.anchorNode)) return
  }
  contextMenu.value?.openAt(event, `任务：${task.title}`, [
    { id: 'open', label: '打开任务', run: () => openItem(id) },
    { id: 'link', label: '复制分享链接', run: () => copyText(shareUrl(ref.taskId, ref.subId), '任务分享链接已复制') },
    { id: 'id', label: '复制任务编号', run: () => copyText(String(task.number ?? ref.subId ?? ref.taskId), '任务编号已复制') },
    { id: 'complete', label: task.status === '已完成' ? '重新打开任务' : '完成任务', separatorBefore: true, disabled: busy(id), run: () => toggleComplete(id) },
  ])
}
</script>

<template>
  <div class="quadrant-view">
    <div class="quadrant-toolbar">
      <TaskOrderStatus :scope="orderScope" /><span v-if="customized">自定义筛选 · 任务可出现在多个象限</span>
      <button v-if="doneCount && !itemRefs" :aria-expanded="showDone" @click="emit('update:showDone', !showDone)">{{ showDone ? '收起' : '展开' }}已完成 · {{ doneCount }}</button>
    </div>
    <div v-if="rules.error.value || labels.error" role="alert">{{ rules.error.value || labels.error }}<button @click="rules.retry(); labels.load()">重试</button></div><div class="quadrants" :class="{ expanded }">
      <section v-for="section in sections" v-show="!expanded || expanded === section.priority" :key="section.priority"
        class="quadrant" :class="{ 'drop-target': dragId && dropTarget === section.priority }"
        :aria-label="section.label" :style="{ '--quadrant-color': section.color }"
        @dragover.prevent="dropTarget = section.priority" @drop.prevent="drop(section.priority)">
        <header>
          <span class="quadrant-dot"></span>
          <h2 :title="`${section.priority} · ${section.hint}`">{{ section.label }}</h2>
          <span class="quadrant-count">{{ section.count }}</span>
          <div class="quadrant-actions"><button :aria-label="`编辑象限 ${section.label}`" @click="editRule(TASK_QUADRANTS.findIndex(q=>q.priority===section.priority))"><i class="ri-more-line"></i></button>
            <button :aria-label="`在${section.label}新建任务`" title="新建任务" @click="createTask(section.priority)"><i class="ri-add-line"></i></button>
            <button :aria-label="`${expanded ? '还原' : '放大'}${section.label}`" :aria-expanded="expanded === section.priority"
              :title="expanded ? '还原四象限' : '放大象限'" @click="expanded = expanded ? null : section.priority"><i :class="expanded ? 'ri-contract-left-right-line' : 'ri-expand-diagonal-line'"></i></button>
          </div>
        </header>
        <div class="quadrant-list" tabindex="0" :aria-label="`${section.label}任务列表`">
          <article v-for="task in section.tasks" :key="task.id" :id="`quadrant-item-${task.id}`" class="quadrant-task" :style="{ marginInlineStart: `min(${Math.min(task.depth, 6) * 18}px, 30%)` }"
            tabindex="0" :aria-label="`任务：${task.title}`" @keydown.enter.self.prevent="openItem(task.id)" @keydown="openTaskMenu($event, task.id)" @contextmenu="openTaskMenu($event, task.id)"
            :class="{ 'context-only': task.contextOnly, done: task.status === '已完成', dragging: dragId === task.id, selected: selectedIds.includes(task.id), 'order-before': orderingOver?.id === task.id && !orderingOver.after, 'order-after': orderingOver?.id === task.id && orderingOver.after }"
            :draggable="!customized && !isChild(task.id) && !busy(task.id) && !selectionMode" @dragstart="startDrag($event, task.id)" @dragend="endDrag"
            @dragover.prevent.stop="orderingOver = dragId && dragId !== task.id ? { id: task.id, after: taskDropAfter($event) } : null"
            @dragleave="orderingOver = null" @drop.prevent.stop="dropOnTask($event, task)">
            <button v-if="selectionMode && !itemActions.isSub(task.id)" class="quadrant-select" :aria-label="`选择任务 ${task.title}`" :aria-pressed="selectedIds.includes(task.id)"
              @click="emit('select', task.id)"><i :class="selectedIds.includes(task.id) ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i></button>
            <button v-else class="quadrant-complete" :aria-label="`${task.status === '已完成' ? '重新打开' : '完成任务'} ${task.title}`"
              :aria-pressed="task.status === '已完成'" :disabled="busy(task.id)" @click="toggleComplete(task.id)"><i :class="task.status === '已完成' ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i></button>
            <div class="quadrant-task-body" :class="{ 'has-error': data.taskSaveState(sourceId(task.id)) === 'error' }">
              <button class="quadrant-title" :title="task.title" :draggable="!customized && !isChild(task.id) && !busy(task.id) && !selectionMode" @click="openItem(task.id)"><span v-if="itemRefs?.[task.id]?.parentKey" aria-label="子任务">↳ </span>{{ task.title }}</button>
              <span v-if="task.unfinishedSubCount" class="save-hint" :title="`仍有 ${task.unfinishedSubCount} 项子项未收尾`">{{ task.unfinishedSubCount }} 项未收尾</span>
              <span v-if="task.executionLabel" class="quadrant-execution" :title="task.executionLabel">{{ task.executionLabel }}</span>
              <div class="quadrant-meta">
                <span v-if="task.due" class="quadrant-due" :style="{ color: task.dueColor }">{{ task.dueStr }}</span>
                <span v-if="task.status !== '待办' && task.status !== '已完成'" class="quadrant-status">{{ task.status }}</span>
                <button v-if="itemRefs?.[task.id]?.parentKey" class="quadrant-parent" :title="`定位父任务：${itemRefs[task.id]!.parentTitle}`" @click="locateParent(task.id)">↳ {{ itemRefs[task.id]!.parentTitle }}</button>
                <span v-else class="quadrant-project" :title="task.projName"><i class="ri-inbox-2-line"></i>{{ task.projName }}</span>
              </div>
              <div v-if="busy(task.id) || data.taskSaveState(sourceId(task.id)) === 'error' || touchedIds.includes(task.id)" class="quadrant-save" aria-live="polite">
                <SaveHint :saving="busy(task.id)" :failed="data.taskSaveState(sourceId(task.id)) === 'error'" :saved-at="data.taskSavedAt(sourceId(task.id))" />
                <button v-if="data.taskSaveState(sourceId(task.id)) === 'error'" :disabled="busy(task.id)" @click="save(task.id)">重试保存</button>
              </div>
            </div>
            <label v-if="!customized && !isChild(task.id)" class="quadrant-move" title="移动到其他象限">
              <i class="ri-arrow-left-right-line"></i>
              <select :value="task.pr" :aria-label="`调整象限 ${task.title}`" :disabled="busy(task.id)"
                @change="setPriority(task.id, ($event.target as HTMLSelectElement).value as Priority)">
                <option v-for="option in TASK_QUADRANTS" :key="option.priority" :value="option.priority">{{ option.label }}</option>
              </select>
            </label>
            <button type="button" class="quadrant-more" :aria-label="`更多任务操作：${task.title}`" aria-haspopup="menu" @click="openTaskMenu($event, task.id)"><i class="ri-more-line"></i></button>
          </article>
          <div v-if="!section.tasks.length" class="quadrant-empty">
            <i class="ri-checkbox-circle-line"></i>
            <span>暂无{{ showDone ? '' : '未完成' }}任务</span>
            <button @click="createTask(section.priority)">添加任务</button>
          </div>
        </div>
      </section>
    </div>
    <SettingsDialogShell v-if="editingRule!==null" title="编辑象限" width="560px" height="auto" @close="editingRule=null">
 <form class="rule-form" @submit.prevent="saveRule">
 <label>名称<input v-model="draftRule.name" maxlength="40" required /></label>
 <div class="rule-field"><span>清单</span><TaskFilterSelect v-model="draftRule.projects" label="象限项目" :options="[{value:'',label:'收集箱'},...data.projects.map(p=>({value:p.id,label:p.name}))]" /></div>
 <div class="rule-field"><span>标签</span><TaskFilterSelect v-model="draftRule.tags" label="象限标签" :options="(tagOptions ?? labels.catalog).map(tag=>({value:tag,label:tag}))" /></div>
 <label>标签匹配<select v-model="draftRule.tagMode"><option value="any">包含任一</option><option value="all">包含全部</option><option value="none">无标签</option></select></label>
 <label>日期<select v-model="draftRule.date"><option value="all">所有</option><option value="today">今天</option><option value="tomorrow">明天</option><option value="week">最近 7 天</option><option value="overdue">已逾期</option><option value="undated">无日期</option></select></label>
 <fieldset><legend>优先级</legend><label v-for="q in TASK_QUADRANTS" :key="q.priority"><input v-model="draftRule.priorities" type="checkbox" :value="q.priority" />{{ q.priority }}</label></fieldset>
 <p>清单、标签、优先级留空表示所有；日期匹配执行安排或截止日期。</p>
 <footer><button type="button" @click="resetRule">恢复默认</button><button type="button" @click="editingRule=null">取消</button><button type="submit">保存</button></footer>
 </form></SettingsDialogShell><ContextActionMenu ref="contextMenu" />
  </div>
</template>

<style scoped>
.context-only { opacity:.65; }
.rule-form { padding:var(--wao-space-3); display:grid; gap:var(--wao-space-2); }
.rule-form>label,.rule-field { display:grid; grid-template-columns:80px minmax(0,1fr); align-items:center; gap:var(--wao-space-2); }
.rule-form input:not([type=checkbox]),.rule-form select { min-width:0; padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; }
.rule-form fieldset { border:0; display:flex; gap:var(--wao-space-2); }
.rule-form footer { display:flex; justify-content:flex-end; gap:var(--wao-space-2); }
.rule-form footer button { padding:var(--wao-space-1) var(--wao-space-2); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-bg-card); }

.quadrant-parent { min-width: 0; max-width: 100%; border: 0; padding: 0; background: transparent; color: var(--wao-text-3); font-size: inherit; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.quadrant-task:focus { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
.quadrant-more { flex: none; width: 26px; height: 26px; padding: 0; border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-text-2); }
.quadrant-more:hover, .quadrant-more:focus-visible { background: var(--wao-bg-hover); }
.quadrant-task.order-before { box-shadow: inset 0 2px var(--wao-primary); }
.quadrant-task.order-after { box-shadow: inset 0 -2px var(--wao-primary); }
.quadrant-view { display: flex; flex-direction: column; flex: 1; min-height: 0; min-width: 0; }
.quadrant-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 32px; margin: 4px 0 6px; font-size: 12px; color: var(--wao-text-weak); }
.quadrant-toolbar > span { display: inline-flex; align-items: center; gap: 6px; }
button, select { font: inherit; color: var(--wao-text); }
button { cursor: pointer; }
button:disabled { opacity: .5; cursor: wait; }
button:focus-visible, .quadrant-move:focus-within { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.quadrant-toolbar button, .quadrant-save button, .quadrant-empty button { border: 0; border-radius: 5px; background: transparent; padding: 5px 6px; color: var(--wao-text-weak); }
.quadrant-toolbar button:hover, .quadrant-empty button:hover { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
.quadrants { min-height:calc(100dvh - 330px); display: grid; flex: 1; min-height: 0; grid-template-columns: repeat(2, minmax(0, 1fr)); grid-template-rows: repeat(2, minmax(0, 1fr)); gap: 10px; }
.quadrants.expanded { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr); }
.quadrant { display: flex; flex-direction: column; min-width: 0; min-height: 0; border: 1px solid var(--wao-line); border-radius: 9px; background: var(--wao-bg-card); overflow: hidden; transition: border-color .15s, box-shadow .15s; }
.quadrant.drop-target { border-color: var(--quadrant-color); box-shadow: inset 0 0 0 2px var(--quadrant-color); }
header { display: flex; align-items: center; gap: 7px; flex-shrink: 0; min-height: 44px; padding: 4px 12px; background: color-mix(in srgb, var(--quadrant-color) 5%, var(--wao-bg-card)); }
.quadrant-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--quadrant-color); flex-shrink: 0; }
h2 { margin: 0; font-size: 14px; font-weight: 600; color: var(--quadrant-color); }
.quadrant-count { font-size: 12px; color: var(--wao-text-weak); }
.quadrant-actions { display: flex; gap: 2px; margin-left: auto; }
.quadrant-actions button { display: grid; place-items: center; width: 28px; height: 28px; padding: 0; border: 0; border-radius: 5px; background: transparent; color: var(--wao-text-weak); font-size: 16px; }
.quadrant-actions button:hover { background: var(--wao-bg-hover); color: var(--quadrant-color); }
.quadrant-list { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 4px 7px; scrollbar-width: thin; }
.quadrant-task { display: flex; align-items: center; gap: 6px; min-width: 0; padding: 4px 3px; border-bottom: 1px solid var(--wao-line); border-radius: var(--wao-radius-control); }
.quadrant-task:last-child { border-bottom-color: transparent; }
.quadrant-task:hover { background: var(--wao-bg-hover); }
.quadrant-task.selected { background: var(--wao-primary-soft); }
.quadrant-task.dragging { opacity: .4; }
.quadrant-complete, .quadrant-select { display: grid; place-items: center; flex: 0 0 26px; height: 26px; padding: 0; border: 0; background: transparent; color: var(--quadrant-color); font-size: 19px; }
.quadrant-select { color: var(--wao-primary-text); }
.quadrant-task-body { display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0; }
.quadrant-task-body.has-error { flex-wrap: wrap; }
.quadrant-title { display: block; flex: 1 1 80px; min-width: 0; padding: 3px 0; border: 0; background: none; text-align: left; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; line-height: 20px; font-size: 13px; }
.quadrant-title:hover { color: var(--wao-primary-text); }
.done .quadrant-title { text-decoration: line-through; color: var(--wao-text-weak); }
.quadrant-meta { display: flex; align-items: center; gap: 4px; min-width: 0; max-width: 40%; font-size: 11px; line-height: 18px; color: var(--wao-text-3); }
.quadrant-execution, .quadrant-task-body > .save-hint { max-width: 20%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 11px; color: var(--wao-primary-text); }
.quadrant-due, .quadrant-status { flex-shrink: 0; }
.quadrant-project { margin-left: auto; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.quadrant-project i { margin-right: 3px; }
.quadrant-move { position: relative; flex: 0 0 26px; height: 26px; display: grid; place-items: center; color: var(--wao-text-weak); border-radius: 5px; opacity: .4; }
.quadrant-move select { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }
.quadrant-task:hover .quadrant-move, .quadrant-move:focus-within { opacity: 1; background: var(--wao-bg-hover-2); }
.quadrant-save { display: flex; align-items: center; gap: 6px; flex: none; }
.quadrant-save :deep(.hint.ok) { font-size: 0; gap: 0; }
.quadrant-save :deep(.hint.ok i) { font-size: 13px; }
.has-error .quadrant-save { flex-basis: 100%; flex-wrap: wrap; }
.has-error .quadrant-save :deep(.hint) { white-space: normal; overflow-wrap: anywhere; }
.quadrant-save button { color: var(--wao-danger-strong); font-size: 12px; }
.quadrant-empty { display: flex; min-height: 100%; flex-direction: column; align-items: center; justify-content: center; gap: 5px; box-sizing: border-box; padding: 12px 0; color: var(--wao-text-faint); font-size: 12px; }
.quadrant-empty > i { color: color-mix(in srgb, var(--quadrant-color) 35%, var(--wao-bg-card)); font-size: 26px; }
@media (max-width: 760px) {
  .quadrant-view { min-height: 300px; }
  .quadrants { gap: 7px; }
  header { gap: 4px; padding: 5px 7px; flex-wrap: wrap; }
  h2 { font-size: 12px; }
  .quadrant-actions { width: 100%; justify-content: flex-end; }
  .quadrant-actions button { width: 30px; height: 28px; }
  .quadrant-list { padding: 3px; }
  .quadrant-task { gap: 2px; }
  .quadrant-complete, .quadrant-select { flex-basis: 24px; min-height: 32px; }
  .quadrant-title { font-size: 12px; line-height: 24px; }
  .quadrant-meta { max-width: 28%; }
  .quadrant-project { margin-left: 0; }
  .quadrant-due, .quadrant-status { display: none; }
  .quadrants.expanded .quadrant-meta { max-width: 40%; }
  .quadrants.expanded .quadrant-due, .quadrants.expanded .quadrant-status { display: inline; }
  .quadrant-task-body { gap: 3px; }
  .quadrant-task { min-height: 44px; }
  .quadrant-more { width: 24px; min-height: 32px; }
  .quadrant-move { flex-basis: 22px; min-height: 32px; opacity: .7; }
  .quadrant-toolbar { font-size: 11px; }
  .quadrant-toolbar button { min-height: 34px; }
  .quadrants.expanded header { flex-wrap: nowrap; }
  .quadrants.expanded .quadrant-actions { width: auto; }
}
</style>
