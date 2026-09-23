<script setup lang="ts">
import TaskOrderStatus from './TaskOrderStatus.vue'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { taskDropAfter } from '@/utils/taskDrop'
import { computed, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useDeco } from '@/composables/deco'
import { useSettle } from '@/composables/completion'
import { TASK_QUADRANTS } from '@/utils/taskQuadrants'
import SaveHint from '@/components/ui/SaveHint.vue'
import type { Priority, Task } from '@/types'

const props = defineProps<{ tasks: Task[]; selectedIds: string[]; showDone: boolean; selectionMode?: boolean; projectId?: string }>()
const emit = defineEmits<{ select: [id: string]; 'update:showDone': [value: boolean] }>()
const data = useDataStore()
const order = useTaskOrderStore()
const orderScope = computed(() => props.projectId ? `project:${props.projectId}` : 'all')
const orderingOver = ref<{ id: string; after: boolean } | null>(null)
const ui = useUiStore()
const { decoTask } = useDeco()
const { begin, cancel, isSettling } = useSettle()
const dragId = ref<string | null>(null)
const dropTarget = ref<Priority | null>(null)
const expanded = ref<Priority | null>(null)
const savingIds = ref<string[]>([])
const touchedIds = ref<string[]>([])
const doneCount = computed(() => props.tasks.filter((task) => task.status === '已完成').length)
const busy = (id: string) => savingIds.value.includes(id) || data.taskSaveState(id) === 'saving'
// 完成请求未落库或失败时仍留在原象限，始终提供重试入口。
const sections = computed(() => TASK_QUADRANTS.map((quadrant) => ({
  ...quadrant,
  tasks: order.sort(orderScope.value, props.tasks, t => t.id).filter((task) => task.pr === quadrant.priority && (props.showDone
    || task.status !== '已完成' || isSettling(task.id) || busy(task.id) || data.taskSaveState(task.id) === 'error'))
    .map(decoTask),
})))
async function save(id: string, change?: () => void) {
  if (!data.taskById(id) || busy(id)) return
  savingIds.value.push(id)
  if (!touchedIds.value.includes(id)) touchedIds.value.push(id)
  try {
    change?.()
    await data.flushTask(id)
  } finally { savingIds.value = savingIds.value.filter((value) => value !== id) }
}
async function setPriority(id: string, priority: Priority) {
  await save(id, () => {
    if (data.taskById(id)?.pr !== priority) data.patchTask(id, { pr: priority }, `将优先级改为「${priority}」`)
  })
}
async function toggleComplete(id: string) {
  await save(id, () => {
    data.toggleComplete(id)
    if (data.taskById(id)?.status === '已完成') begin(id)
    else cancel(id)
  })
}
function startDrag(event: DragEvent, id: string) {
  if (busy(id)) { event.preventDefault(); return }
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
  if (!source || source.id === target.id || busy(source.id)) return
  if (source.pr !== target.pr) await setPriority(source.id, target.pr)
  await order.move(orderScope.value, props.tasks.map(t => t.id), source.id, target.id, after)
}
function createTask(priority: Priority) {
  ui.openModal('task', props.projectId ?? '')
  ui.modalPriority = priority
}
</script>

<template>
  <div class="quadrant-view">
    <div class="quadrant-toolbar">
      <TaskOrderStatus :scope="orderScope" />
      <button v-if="doneCount" :aria-expanded="showDone" @click="emit('update:showDone', !showDone)">{{ showDone ? '收起' : '展开' }}已完成 · {{ doneCount }}</button>
    </div>
    <div class="quadrants" :class="{ expanded }">
      <section v-for="section in sections" v-show="!expanded || expanded === section.priority" :key="section.priority"
        class="quadrant" :class="{ 'drop-target': dragId && dropTarget === section.priority }"
        :aria-label="section.label" :style="{ '--quadrant-color': section.color }"
        @dragover.prevent="dropTarget = section.priority" @drop.prevent="drop(section.priority)">
        <header>
          <span class="quadrant-dot"></span>
          <h2 :title="`${section.priority} · ${section.hint}`">{{ section.label }}</h2>
          <span class="quadrant-count">{{ section.tasks.length }}</span>
          <div class="quadrant-actions">
            <button :aria-label="`在${section.label}新建任务`" title="新建任务" @click="createTask(section.priority)"><i class="ri-add-line"></i></button>
            <button :aria-label="`${expanded ? '还原' : '放大'}${section.label}`" :aria-expanded="expanded === section.priority"
              :title="expanded ? '还原四象限' : '放大象限'" @click="expanded = expanded ? null : section.priority"><i :class="expanded ? 'ri-contract-left-right-line' : 'ri-expand-diagonal-line'"></i></button>
          </div>
        </header>
        <div class="quadrant-list" tabindex="0" :aria-label="`${section.label}任务列表`">
          <article v-for="task in section.tasks" :key="task.id" class="quadrant-task"
            :class="{ done: task.status === '已完成', dragging: dragId === task.id, selected: selectedIds.includes(task.id), 'order-before': orderingOver?.id === task.id && !orderingOver.after, 'order-after': orderingOver?.id === task.id && orderingOver.after }"
            :draggable="!busy(task.id) && !selectionMode" @dragstart="startDrag($event, task.id)" @dragend="endDrag"
            @dragover.prevent.stop="orderingOver = dragId && dragId !== task.id ? { id: task.id, after: taskDropAfter($event) } : null"
            @dragleave="orderingOver = null" @drop.prevent.stop="dropOnTask($event, task)">
            <button v-if="selectionMode" class="quadrant-select" :aria-label="`选择任务 ${task.title}`" :aria-pressed="selectedIds.includes(task.id)"
              @click="emit('select', task.id)"><i :class="selectedIds.includes(task.id) ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i></button>
            <button v-else class="quadrant-complete" :aria-label="`${task.status === '已完成' ? '重新打开' : '完成任务'} ${task.title}`"
              :aria-pressed="task.status === '已完成'" :disabled="busy(task.id)" @click="toggleComplete(task.id)"><i :class="task.status === '已完成' ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i></button>
            <div class="quadrant-task-body">
              <button class="quadrant-title" :title="task.title" :draggable="!busy(task.id) && !selectionMode" @click="ui.openTask(task.id)">{{ task.title }}</button>
              <div class="quadrant-meta">
                <span v-if="task.due" class="quadrant-due" :style="{ color: task.dueColor }">{{ task.dueStr }}</span>
                <span v-if="task.status !== '待办' && task.status !== '已完成'" class="quadrant-status">{{ task.status }}</span>
                <span class="quadrant-project" :title="task.projName"><i class="ri-inbox-2-line"></i>{{ task.projName }}</span>
              </div>
              <div v-if="busy(task.id) || data.taskSaveState(task.id) === 'error' || touchedIds.includes(task.id)" class="quadrant-save" aria-live="polite">
                <SaveHint :saving="busy(task.id)" :failed="data.taskSaveState(task.id) === 'error'" :saved-at="data.taskSavedAt(task.id)" />
                <button v-if="data.taskSaveState(task.id) === 'error'" :disabled="busy(task.id)" @click="save(task.id)">重试保存</button>
              </div>
            </div>
            <label class="quadrant-move" title="移动到其他象限">
              <i class="ri-arrow-left-right-line"></i>
              <select :value="task.pr" :aria-label="`调整象限 ${task.title}`" :disabled="busy(task.id)"
                @change="setPriority(task.id, ($event.target as HTMLSelectElement).value as Priority)">
                <option v-for="option in TASK_QUADRANTS" :key="option.priority" :value="option.priority">{{ option.label }}</option>
              </select>
            </label>
          </article>
          <div v-if="!section.tasks.length" class="quadrant-empty">
            <i class="ri-checkbox-circle-line"></i>
            <span>暂无{{ showDone ? '' : '未完成' }}任务</span>
            <button @click="createTask(section.priority)">添加任务</button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
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
.quadrants { display: grid; flex: 1; min-height: 0; grid-template-columns: repeat(2, minmax(0, 1fr)); grid-template-rows: repeat(2, minmax(0, 1fr)); gap: 10px; }
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
.quadrant-task { display: flex; align-items: flex-start; gap: 6px; min-width: 0; padding: 6px 3px; border-bottom: 1px solid var(--wao-line); border-radius: 4px; }
.quadrant-task:last-child { border-bottom-color: transparent; }
.quadrant-task:hover { background: var(--wao-bg-hover); }
.quadrant-task.selected { background: var(--wao-primary-soft); }
.quadrant-task.dragging { opacity: .4; }
.quadrant-complete, .quadrant-select { display: grid; place-items: center; flex: 0 0 26px; height: 26px; padding: 0; border: 0; background: transparent; color: var(--quadrant-color); font-size: 19px; }
.quadrant-select { color: var(--wao-primary-text); }
.quadrant-task-body { flex: 1; min-width: 0; }
.quadrant-title { display: block; width: 100%; padding: 3px 0; border: 0; background: none; text-align: left; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; line-height: 20px; font-size: 13px; }
.quadrant-title:hover { color: var(--wao-primary-text); }
.done .quadrant-title { text-decoration: line-through; color: var(--wao-text-weak); }
.quadrant-meta { display: flex; align-items: center; gap: 8px; min-width: 0; font-size: 11px; line-height: 18px; color: var(--wao-text-weak); }
.quadrant-due, .quadrant-status { flex-shrink: 0; }
.quadrant-project { margin-left: auto; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.quadrant-project i { margin-right: 3px; }
.quadrant-move { position: relative; flex: 0 0 26px; height: 26px; display: grid; place-items: center; color: var(--wao-text-weak); border-radius: 5px; opacity: .4; }
.quadrant-move select { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }
.quadrant-task:hover .quadrant-move, .quadrant-move:focus-within { opacity: 1; background: var(--wao-bg-hover-2); }
.quadrant-save { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.quadrant-save :deep(.hint) { white-space: normal; overflow-wrap: anywhere; }
.quadrant-save button { color: var(--wao-danger-strong); font-size: 12px; }
.quadrant-empty { display: flex; min-height: 100%; flex-direction: column; align-items: center; justify-content: center; gap: 5px; box-sizing: border-box; padding: 12px 0; color: var(--wao-text-faint); font-size: 12px; }
.quadrant-empty > i { color: color-mix(in srgb, var(--quadrant-color) 35%, var(--wao-bg-card)); font-size: 26px; }
@media (max-width: 760px) {
  .quadrants { gap: 7px; }
  header { gap: 4px; padding: 5px 7px; flex-wrap: wrap; }
  h2 { font-size: 12px; }
  .quadrant-actions { width: 100%; justify-content: flex-end; }
  .quadrant-actions button { width: 30px; height: 28px; }
  .quadrant-list { padding: 3px; }
  .quadrant-task { gap: 2px; }
  .quadrant-complete, .quadrant-select { flex-basis: 24px; min-height: 32px; }
  .quadrant-title { font-size: 12px; line-height: 24px; }
  .quadrant-meta { flex-wrap: wrap; gap: 0 5px; }
  .quadrant-project { width: 100%; margin-left: 0; }
  .quadrant-move { flex-basis: 22px; min-height: 32px; opacity: .7; }
  .quadrant-toolbar { font-size: 11px; }
  .quadrant-toolbar button { min-height: 34px; }
  .quadrants.expanded header { flex-wrap: nowrap; }
  .quadrants.expanded .quadrant-actions { width: auto; }
}
</style>
