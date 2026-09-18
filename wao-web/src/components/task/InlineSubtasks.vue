<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { subtaskRows, SUBTASK_DRAG_TYPE, readSubtaskDrag } from '@/utils/subtaskTree'
import WorkItemIcon from '@/components/task/WorkItemIcon.vue'
/**
 * 子任务列表：项目表格里渲染成与主任务同列的 `<tbody>` 行；任务 / 子任务详情里渲染成紧凑列表。
 * 两种形态共用同一套数据、编号、拖拽与草稿逻辑，只是外观不同。
 *
 * <p>表格形态的「隐藏已完成」由表格工具条统一控制（`hideDone`），每个任务下不再各放一个按钮。
 */
const props = defineProps<{ taskId: string; table?: boolean; parentId?: string | null; hideDone?: boolean }>()
const data = useDataStore()
const ui = useUiStore()
const session = useSessionStore()
const task = computed(() => data.taskById(props.taskId))
const localHideDone = ref(false)
const hidingDone = computed(() => (props.table ? props.hideDone === true : localHideDone.value))
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
const visibleRows = computed(() => rows.value.filter(row => !hidingDone.value || !row.sub.done))
const doneCount = computed(() => rows.value.filter(row => row.sub.done).length)
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
function toggleDone(subId: string, done: boolean) {
  data.updateSub(props.taskId, subId, { done: !done })
}
function member(id?: string | null) {
  return id ? session.memberById(id) : null
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
    <tr v-if="localError"><td colspan="10"><small class="error" role="alert">{{ localError }}</small></td></tr>
    <tr
      v-for="{ sub, idx: index, key: subId, depth } in visibleRows"
      :key="data.subKey(taskId, sub, index)"
      class="inline-sub-row" :class="{ 'is-done': sub.done }" draggable="true" @dragstart.stop="drag($event, subId)" @dragover.prevent.stop @drop.prevent.stop="drop($event, subId)"
      @click="ui.openTask(taskId, subId)"
    >
      <td></td>
      <td class="sub-number wao-tabular">{{ sub.number ?? '…' }}</td>
      <td>
        <div class="sub-title" :style="{ paddingLeft: `${24 + depth * 20}px` }">
          <WorkItemIcon kind="subtask" /><button
            :class="{ done: sub.done }"
            :title="sub.text"
            @click.stop="ui.openTask(taskId, subId)"
          >
            {{ sub.text }}
          </button>
        </div>
      </td>
      <td></td>
      <td><span class="faint">—</span></td>
      <td>
        <button
          class="sub-state"
          :class="{ completed: sub.done }"
          :title="sub.done ? '点击改回未开始' : '点击标记已完成'"
          :aria-label="`${sub.done ? '取消完成' : '完成'}子任务 ${sub.text}`"
          @click.stop="toggleDone(subId, sub.done)"
        >{{ sub.done ? '已完成' : '未开始' }}</button>
      </td>
      <td>
        <span v-if="member(sub.owner)" class="sub-member"
          ><span class="sub-av" :style="{ background: member(sub.owner)!.color }">{{ member(sub.owner)!.initial }}</span
          >{{ member(sub.owner)!.name }}</span
        ><span v-else class="faint">—</span>
      </td>
      <td><span class="faint">—</span></td>
      <td><span class="faint">—</span></td>
      <td class="wao-tabular">{{ sub.due || '—' }}</td>
    </tr>
    <tr class="sub-add-row">
      <td></td>
      <td></td>
      <td colspan="8">
        <form @submit.prevent="add">
          <input
            v-model="draft"
            :aria-label="`为 ${task.title} 添加子任务`"
            placeholder="＋ 添加子任务，回车保存"
            @keydown.enter="$event.isComposing && $event.preventDefault()"
          /><button :disabled="!draft.trim()">添加</button>
          <small v-if="data.taskSaveState(taskId) === 'saving'" role="status">保存中…</small>
          <small v-else-if="data.taskSaveState(taskId) === 'error'" class="error" role="alert"
            >保存失败，草稿已保留<button type="button" @click="data.flushTask(taskId)">
              重试保存
            </button></small
          >
        </form>
      </td>
    </tr>
  </tbody>
  <section
    v-else-if="task"
    class="inline-subs"
    aria-label="子任务"
    @click.stop
    @dragstart.stop @dragover.prevent @drop.prevent.stop="drop($event, parentId)" :inert="data.movingSubtasks"
  >
    <div class="list-head">
      <span class="list-title">子任务</span>
      <span class="list-count wao-tabular">{{ doneCount }}/{{ rows.length }}</span>
      <span class="list-spacer"></span>
      <button
        v-if="doneCount"
        type="button"
        class="done-switch"
        role="switch"
        :aria-checked="!localHideDone"
        @click="localHideDone = !localHideDone"
        title="是否显示已完成的子任务"
      ><span class="track"><span class="thumb"></span></span>显示已完成</button>
    </div>
    <div
      v-for="{ sub, idx: index, key: subId, depth } in visibleRows"
      :key="data.subKey(taskId, sub, index)"
      class="inline-sub" :class="{ 'is-done': sub.done }" :style="{ paddingLeft: `${8 + depth * 20}px` }" draggable="true" @dragstart.stop="drag($event, subId)" @dragover.prevent.stop @drop.prevent.stop="drop($event, subId)"
    >
      <i class="ri-draggable grip" title="拖到另一条子任务上可调整层级"></i>
      <WorkItemIcon kind="subtask" />
      <span class="number wao-tabular">{{ sub.number ?? '…' }}</span>
      <button class="sub-open" :class="{ done: sub.done }" :title="sub.text" @click="ui.openTask(taskId, subId)">{{ sub.text }}</button>
      <i v-if="sub.desc || sub.images?.length" class="ri-file-text-line flag" title="有描述 / 图片"></i>
      <span v-if="sub.due" class="due wao-tabular">{{ sub.due.slice(5) }}</span>
      <span v-if="member(sub.owner)" class="sub-av" :title="member(sub.owner)!.name" :style="{ background: member(sub.owner)!.color }">{{ member(sub.owner)!.initial }}</span>
      <button
        class="sub-state"
        :class="{ completed: sub.done }"
        :aria-label="`${sub.done ? '取消完成' : '完成'}子任务 ${sub.text}`"
        @click="toggleDone(subId, sub.done)"
      >{{ sub.done ? '已完成' : '未开始' }}</button>
    </div>
    <div v-if="!rows.length" class="empty">还没有子任务</div>
    <form @submit.prevent="add">
      <i class="ri-add-line"></i>
      <input
        v-model="draft"
        :aria-label="`为 ${task.title} 添加子任务`"
        placeholder="添加子任务，回车保存"
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
button {
  border: 0;
  background: transparent;
  color: var(--wao-text-2);
  text-align: left;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
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
.done {
  text-decoration: line-through;
  color: var(--wao-text-faint);
}
.faint {
  color: var(--wao-text-faint);
}

/* 状态胶囊：与主任务表格同一套描边样式 */
.sub-state {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border: 1px solid #338fe5;
  border-radius: 10px;
  color: #338fe5;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  flex-shrink: 0;
}
.sub-state.completed {
  border-color: #3fb96f;
  color: #3fb96f;
}
.sub-state:hover {
  filter: brightness(0.9);
}
.sub-av {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: #fff;
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* ---- 详情里的紧凑列表 ---- */
.inline-subs {
  min-width: 0;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  overflow: hidden;
}
.list-head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 10px;
  background: var(--wao-bg-sub);
  border-bottom: 1px solid var(--wao-line);
}
.list-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text);
}
.list-count {
  font-size: 12px;
  color: var(--wao-text-faint);
}
.list-spacer {
  flex: 1;
}
.done-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.done-switch .track {
  position: relative;
  width: 26px;
  height: 14px;
  border-radius: 7px;
  background: var(--wao-line-control);
  transition: background 0.15s;
}
.done-switch .thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  transition: transform 0.15s;
}
.done-switch[aria-checked='true'] .track {
  background: var(--wao-primary);
}
.done-switch[aria-checked='true'] .thumb {
  transform: translateX(12px);
}
.inline-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  padding-right: 10px;
  border-bottom: 1px solid var(--wao-line);
}
.inline-sub:hover {
  background: var(--wao-bg-hover);
}
.grip {
  color: var(--wao-text-faint);
  font-size: 13px;
  cursor: grab;
  opacity: 0;
}
.inline-sub:hover .grip {
  opacity: 1;
}
.number {
  font-size: 12px;
  color: var(--wao-text-faint);
  min-width: 28px;
}
.sub-open {
  flex: 1;
  min-width: 0;
  padding: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--wao-text);
}
.sub-open:hover {
  color: var(--wao-primary);
}
.sub-open.done {
  color: var(--wao-text-faint);
}
.flag {
  font-size: 12px;
  color: var(--wao-text-faint);
}
.due {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.empty {
  padding: 10px 12px;
  font-size: 12px;
  color: var(--wao-text-faint);
  border-bottom: 1px solid var(--wao-line);
}
.inline-subs form {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  color: var(--wao-text-faint);
}
.inline-subs form input {
  flex: 1;
  min-width: 0;
  height: 28px;
  padding: 0 4px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text);
  font-size: 13px;
}
.inline-subs form input:focus {
  border-color: var(--wao-line-control);
  outline: none;
}
.inline-subs form button {
  color: var(--wao-primary);
  font-size: 12px;
}
.inline-subs > small {
  display: block;
  padding: 0 10px 6px;
}

/* ---- 项目表格里的子任务行 ---- */
.table-subs td {
  padding: 0 10px;
  border-right: 1px solid var(--wao-line);
  border-bottom: 1px solid var(--wao-line);
  height: 34px;
  font-size: 13px;
  color: var(--wao-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.table-subs td:last-child {
  border-right: 0;
}
.table-subs .inline-sub-row {
  cursor: pointer;
}
.table-subs .inline-sub-row:hover {
  background: var(--wao-bg-hover);
}
.table-subs .sub-number {
  color: var(--wao-text-weak);
  font-size: 12px;
}
.sub-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.sub-title button {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
  padding: 0;
  color: var(--wao-text);
}
.sub-title button:hover {
  color: var(--wao-primary);
}
.sub-title button.done {
  color: var(--wao-text-faint);
}
.sub-member {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.table-subs form {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 46px;
  height: 28px;
}
.table-subs form input {
  flex: 1;
  border: 1px solid transparent;
  border-radius: 6px;
  padding: 3px 4px;
  max-width: 440px;
  background: transparent;
  color: var(--wao-text);
  font-size: 12px;
}
.table-subs form input:focus {
  border-color: var(--wao-line-control);
  outline: none;
}
.table-subs form button {
  font-size: 12px;
  color: var(--wao-primary);
}
.table-subs .sub-add-row td {
  height: 30px;
  color: var(--wao-text-faint);
}
</style>
