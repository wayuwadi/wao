<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { subtaskRows, SUBTASK_DRAG_TYPE, readSubtaskDrag } from '@/utils/subtaskTree'
const props = defineProps<{ taskId: string; table?: boolean; parentId?: string | null }>()
const data = useDataStore()
const ui = useUiStore()
const session = useSessionStore()
const task = computed(() => data.taskById(props.taskId))
const hideDone = ref(false)
const rows = computed(() => {
  const all = subtaskRows(props.taskId, task.value?.subs || [])
  if (!props.parentId) return all
  const parent = all.find(row => row.key === props.parentId)
  if (!parent) return []
  const start = all.indexOf(parent) + 1
  const children = []
  for (let i = start; i < all.length && all[i]!.depth > parent.depth; i++) children.push({ ...all[i]!, depth: all[i]!.depth - parent.depth - 1 })
  return children
})
const visibleRows = computed(() => rows.value.filter(row => !hideDone.value || !row.sub.done))
function drag(event: DragEvent, subId: string) {
  event.dataTransfer?.setData(SUBTASK_DRAG_TYPE, JSON.stringify({ taskId: props.taskId, subId }))
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
async function drop(event: DragEvent, parentId?: string | null) {
  const payload = readSubtaskDrag(event)
  if (!payload) return
  localError.value = ''
  try { await data.moveSubtask(payload.taskId, payload.subId, props.taskId, parentId) }
  catch (error) { localError.value = error instanceof Error ? error.message : '移动失败，请重试' }
}
const draft = ref('')
const localError = ref('')
const key = computed(() => `wao-inline-sub:${session.userId}:${props.taskId}${props.parentId ? `:${props.parentId}` : ''}`)
watch(
  key,
  (value) => {
    try {
      draft.value = localStorage.getItem(value) || ''
    } catch {
      localError.value = '本机草稿无法读取，请保持页面打开'
    }
  },
  { immediate: true },
)
watch(draft, (value) => {
  try {
    if (value) localStorage.setItem(key.value, value)
    else localStorage.removeItem(key.value)
  } catch {
    localError.value = '草稿无法写入本机，请保持页面打开'
  }
})
function add() {
  if (data.addSub(props.taskId, draft.value, props.parentId)) {
    draft.value = ''
    void data.flushTask(props.taskId)
  }
}
</script>
<template>
  <tbody v-if="task && table" class="table-subs" @click.stop @dragstart.stop @dragover.prevent @drop.prevent.stop="drop($event, parentId)" :inert="data.movingSubtasks">
    <tr><td colspan="9"><button type="button" @click="hideDone = !hideDone">{{ hideDone ? '显示已完成' : '隐藏已完成' }}</button><small v-if="localError" class="error" role="alert">{{ localError }}</small></td></tr>
    <tr
      v-for="{ sub, idx: index, key: subId, depth } in visibleRows"
      :key="data.subKey(taskId, sub, index)"
      class="inline-sub-row" draggable="true" @dragstart.stop="drag($event, subId)" @dragover.prevent.stop @drop.prevent.stop="drop($event, subId)"
    >
      <td></td>
      <td class="sub-number">{{ sub.number ?? '…' }}</td>
      <td>
        <div class="sub-title" :style="{ paddingLeft: `${22 + depth * 18}px` }">
          <span class="branch">└</span
          ><input
            type="checkbox"
            :checked="sub.done"
            :aria-label="`完成子任务 ${sub.text}`"
            @change="data.updateSub(taskId, data.subKey(taskId, sub, index), { done: !sub.done })"
          /><button
            :class="{ done: sub.done }"
            :title="sub.text"
            @click="ui.openTask(taskId, data.subKey(taskId, sub, index))"
          >
            {{ sub.text }}
          </button>
        </div>
      </td>
      <td>{{ sub.owner ? session.memberById(sub.owner).name : '未分配' }}</td>
      <td>
        <span class="sub-state" :class="{ completed: sub.done }">{{
          sub.done ? '已完成' : '待办'
        }}</span>
      </td>
      <td>{{ sub.due || '—' }}</td>
      <td>—</td>
      <td>—</td>
      <td>—</td>
    </tr>
    <tr class="sub-add-row">
      <td></td>
      <td></td>
      <td colspan="7">
        <form @submit.prevent="add">
          <input
            v-model="draft"
            :aria-label="`为 ${task.title} 添加子任务`"
            placeholder="＋ 添加子任务，回车保存"
            @keydown.enter="$event.isComposing && $event.preventDefault()"
          /><button :disabled="!draft.trim()">添加</button>
          <small v-if="data.taskSaveState(taskId) === 'saving'" role="status">保存中…</small>
          <small v-else-if="data.taskSaveState(taskId) === 'saved'" role="status">已保存</small>
          <small v-else-if="data.taskSaveState(taskId) === 'error'" class="error" role="alert"
            >保存失败，草稿已保留<button type="button" @click="data.flushTask(taskId)">
              重试保存
            </button></small
          >
        </form>
        <small v-if="localError" class="error" role="alert">{{ localError }}</small>
      </td>
    </tr>
  </tbody>
  <section
    v-else-if="task"
    class="inline-subs"
    aria-label="任务子任务"
    @click.stop
    @dragstart.stop @dragover.prevent @drop.prevent.stop="drop($event, parentId)" :inert="data.movingSubtasks"
  >
    <button type="button" @click="hideDone = !hideDone">{{ hideDone ? '显示已完成' : '隐藏已完成' }}</button>
    <div
      v-for="{ sub, idx: index, key: subId, depth } in visibleRows"
      :key="data.subKey(taskId, sub, index)"
      class="inline-sub" :style="{ paddingLeft: `${depth * 18}px` }" draggable="true" @dragstart.stop="drag($event, subId)" @dragover.prevent.stop @drop.prevent.stop="drop($event, subId)"
    >
      <input
        type="checkbox"
        :checked="sub.done"
        :aria-label="`完成子任务 ${sub.text}`"
        @change="data.updateSub(taskId, data.subKey(taskId, sub, index), { done: !sub.done })"
      />
      <button
        :class="{ done: sub.done }"
        @click="ui.openTask(taskId, data.subKey(taskId, sub, index))"
      >
        <span class="number">{{ sub.number ?? '…' }}</span
        >{{ sub.text }}
      </button>
    </div>
    <form @submit.prevent="add">
      <input
        v-model="draft"
        :aria-label="`为 ${task.title} 添加子任务`"
        placeholder="＋ 添加子任务，回车保存"
        @keydown.enter="$event.isComposing && $event.preventDefault()"
      /><button :disabled="!draft.trim()">添加</button>
    </form>
    <small v-if="data.taskSaveState(taskId) === 'saving'" role="status">保存中…</small>
    <small v-else-if="data.taskSaveState(taskId) === 'saved'" role="status">已保存</small>
    <small v-else-if="data.taskSaveState(taskId) === 'error'" class="error" role="alert"
      >保存失败，草稿已保留<button @click="data.flushTask(taskId)">重试保存</button></small
    >
    <small v-if="localError" class="error" role="alert">{{ localError }}</small>
  </section>
</template>
<style scoped>
.inline-subs {
  margin: 0 12px 12px 55px;
  padding-left: 12px;
  border-left: 2px solid var(--wao-line);
  min-width: 0;
}
.inline-sub,
form {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
}
button {
  border: 0;
  background: transparent;
  color: var(--wao-text-2);
  text-align: left;
  cursor: pointer;
  overflow-wrap: anywhere;
  font: inherit;
  font-size: 12px;
}
.inline-sub button {
  flex: 1;
}
.number {
  color: var(--wao-text-faint);
  margin-right: 6px;
  font-variant-numeric: tabular-nums;
}
.done {
  text-decoration: line-through;
  color: var(--wao-text-faint);
}
form input {
  flex: 1;
  min-width: 0;
  padding: 6px;
  border: 1px solid var(--wao-line-control);
  border-radius: 6px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
form button {
  color: var(--wao-primary);
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
small {
  font-size: 11px;
  color: var(--wao-text-faint);
}
.error {
  color: var(--wao-danger-strong);
}

.table-subs td {
  padding: 4px 9px;
  border-right: 1px solid var(--wao-line);
  border-bottom: 1px solid var(--wao-line);
  height: 32px;
  font-size: 12px;
  color: var(--wao-text-faint);
}
.table-subs td:last-child {
  border-right: 0;
}
.table-subs tr:hover {
  background: var(--wao-bg-hover);
}
.sub-title {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding-left: 22px;
}
.sub-title button {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  padding: 0;
}
.sub-title input {
  flex-shrink: 0;
  margin: 0;
}
.branch {
  color: var(--wao-text-faint);
}
.sub-number {
  font-variant-numeric: tabular-nums;
}
.sub-state {
  background: var(--wao-bg-sub);
  padding: 2px 7px;
  border-radius: 4px;
  color: var(--wao-text-weak);
}
.sub-state.completed {
  color: var(--wao-success);
}
.table-subs form {
  padding: 0 0 0 42px;
  height: 26px;
}
.table-subs form input {
  border-color: transparent;
  padding: 3px 0;
  max-width: 440px;
  background: transparent;
  font-size: 12px;
}
.table-subs form input:focus {
  border-color: var(--wao-line-control);
}
.table-subs .sub-add-row td {
  height: 28px;
}
</style>
