<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useDeco } from '@/composables/deco'
import { TASK_QUADRANTS } from '@/utils/taskQuadrants'
import SaveHint from '@/components/ui/SaveHint.vue'
import type { Priority, Task } from '@/types'

const props = withDefaults(defineProps<{
  tasks: Task[]
  selectedIds: string[]
  showDone: boolean
  showProject?: boolean
}>(), { showProject: true })
const emit = defineEmits<{ select: [id: string]; 'update:showDone': [value: boolean] }>()
const data = useDataStore()
const ui = useUiStore()
const { decoTask } = useDeco()
const dragId = ref<string | null>(null)
const savingIds = ref<string[]>([])
const doneCount = computed(() => props.tasks.filter((task) => task.status === '已完成').length)
const sections = computed(() => TASK_QUADRANTS.map((quadrant) => ({
  ...quadrant,
  tasks: props.tasks.filter((task) => task.pr === quadrant.priority && (props.showDone || task.status !== '已完成'))
    .map(decoTask),
})))
async function setPriority(id: string, priority: Priority) {
  const task = data.taskById(id)
  if (!task || savingIds.value.includes(id)) return
  savingIds.value.push(id)
  try {
    if (task.pr !== priority) data.patchTask(id, { pr: priority }, `将优先级改为「${priority}」`)
    await data.flushTask(id)
  } finally { savingIds.value = savingIds.value.filter((value) => value !== id) }
}
function drop(priority: Priority) {
  const id = dragId.value
  dragId.value = null
  if (id && props.tasks.some((task) => task.id === id)) void setPriority(id, priority)
}
</script>

<template>
  <div class="quadrant-view">
    <div class="quadrant-toolbar">
      <span>按 P0–P3 归类；拖动卡片或选择象限可调整优先级。</span>
      <button v-if="doneCount" :aria-expanded="showDone" @click="emit('update:showDone', !showDone)">{{ showDone ? '收起' : '展开' }}已完成 · {{ doneCount }}</button>
    </div>
    <div class="quadrants">
      <section v-for="section in sections" :key="section.priority" class="quadrant" :aria-label="section.label" :style="{ '--quadrant-color': section.color }" @dragover.prevent @drop.prevent="drop(section.priority)">
        <header><h2>{{ section.label }}</h2><span>{{ section.priority }} · {{ section.tasks.length }} 项</span></header>
        <p class="quadrant-hint">{{ section.hint }}</p>
        <article v-for="task in section.tasks" :key="task.id" class="quadrant-task" :class="{ done: task.status === '已完成' }" :draggable="!savingIds.includes(task.id)" @dragstart="dragId = task.id" @dragend="dragId = null">
          <div class="quadrant-task-head">
            <button class="quadrant-select" :aria-label="`选择任务 ${task.title}`" :aria-pressed="selectedIds.includes(task.id)" @click="emit('select', task.id)"><i :class="selectedIds.includes(task.id) ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i></button>
            <button class="quadrant-title" @click="ui.openTask(task.id)">{{ task.title }}</button>
          </div>
          <div class="quadrant-meta"><span v-if="showProject">{{ task.projName }}</span><span>{{ task.status }}</span><span>{{ task.dueStr }}</span></div>
          <select :value="task.pr" :aria-label="`调整象限 ${task.title}`" :disabled="savingIds.includes(task.id)" @change="setPriority(task.id, ($event.target as HTMLSelectElement).value as Priority)">
            <option v-for="option in TASK_QUADRANTS" :key="option.priority" :value="option.priority">{{ option.priority }} · {{ option.label }}</option>
          </select>
          <div class="quadrant-save" aria-live="polite">
            <SaveHint :saving="savingIds.includes(task.id) || data.taskSaveState(task.id) === 'saving'" :failed="data.taskSaveState(task.id) === 'error'" :saved-at="data.taskSavedAt(task.id)" />
            <button v-if="data.taskSaveState(task.id) === 'error'" :disabled="savingIds.includes(task.id)" @click="setPriority(task.id, task.pr)">重试保存</button>
          </div>
        </article>
        <p v-if="!section.tasks.length" class="quadrant-empty">这个象限暂无{{ showDone ? '' : '未完成' }}任务</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.quadrant-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin: 14px 0 10px; font-size: 12px; color: var(--wao-text-weak); }
button, select { font: inherit; color: var(--wao-text); }
.quadrant-toolbar button, .quadrant-save button { min-height: 32px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); padding: 5px 10px; cursor: pointer; }
.quadrants { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; align-items: start; }
.quadrant { min-width: 0; padding: 14px; border: 1px solid var(--wao-line); border-top: 3px solid var(--quadrant-color); border-radius: 12px; background: var(--wao-bg-hover-2); }
header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 6px; }
h2 { margin: 0; font-size: 15px; color: var(--quadrant-color); }
header span, .quadrant-hint { font-size: 12px; color: var(--wao-text-weak); }
.quadrant-hint { margin: 6px 0 12px; }
.quadrant-task { min-width: 0; margin-top: 8px; border: 1px solid var(--wao-line); border-radius: 10px; padding: 12px; background: var(--wao-bg-card); }
.quadrant-task-head { display: flex; align-items: flex-start; gap: 8px; }
.quadrant-select { flex: 0 0 28px; height: 28px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); cursor: pointer; }
.quadrant-select[aria-pressed='true'] { color: var(--wao-primary); background: var(--wao-primary-soft); }
.quadrant-title { min-width: 0; flex: 1; padding: 3px 0; border: 0; background: none; text-align: left; overflow-wrap: anywhere; line-height: 1.6; cursor: pointer; font-size: 13px; }
.quadrant-title:hover { color: var(--wao-primary-text); }
.done .quadrant-title { text-decoration: line-through; color: var(--wao-text-weak); }
.quadrant-meta { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0; font-size: 12px; color: var(--wao-text-weak); overflow-wrap: anywhere; }
select { width: 100%; min-width: 0; min-height: 34px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); padding: 4px 6px; font-size: 12px; }
.quadrant-save { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 6px; }
.quadrant-empty { padding: 24px 8px; color: var(--wao-text-weak); text-align: center; font-size: 13px; }
@media (max-width: 760px) { .quadrants { grid-template-columns: minmax(0, 1fr); } .quadrant-select { flex-basis: 36px; height: 36px; } select, .quadrant-toolbar button, .quadrant-save button { min-height: 40px; } }
</style>
