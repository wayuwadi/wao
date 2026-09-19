<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useDeco } from '@/composables/deco'
import { useCompletionSettle } from '@/composables/completion'
import { TASK_STATUSES, TASK_STATUS_DOTS } from '@/utils/domain'
import type { Task, TaskStatus } from '@/types'
import TaskBatchCopy from '@/components/task/TaskBatchCopy.vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import { readTodoTaskView, type TodoTaskView } from '@/utils/taskQuadrants'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const { decoTask } = useDeco()

const taskFilter = ref<'全部' | '我负责' | '我参与' | '等待他人'>('全部')
const taskView = ref<TodoTaskView>(readTodoTaskView())
watch(taskView, (view) => {
  try { localStorage.setItem('wao-todo-view-v1', view) } catch { /* 存储不可用不阻断视图 */ }
})

const myList = computed(() => {
  const me = session.currentUser.id
  // TODO 只收纳我负责或参与的任务；项目协作可见不等于该任务属于我。
  let list = data.tasks.filter((t) => t.owner === me || t.parts.includes(me))
  if (taskFilter.value === '我负责') list = list.filter((t) => t.owner === me)
  if (taskFilter.value === '我参与') list = list.filter((t) => t.parts.includes(me))
  if (taskFilter.value === '等待他人') list = list.filter((t) => t.waiting)
  return list
})

// 已完成默认收起，点分组标题展开；勾完成后先原地停留 3 秒再归到「已完成」里。
const showDone = ref(false)
const { toggle: toggleTask, isSettling } = useCompletionSettle()

/** 本周日（含）为界：今天之后、本周日之前算「本周」，再往后是「稍后」。 */
const weekEnd = computed(() => {
  const d = new Date(`${data.today}T00:00:00`)
  d.setDate(d.getDate() + (7 - (d.getDay() || 7)))
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
})

/** 已完成任务倒序：最新完成的排最前；老数据没有完成时间时按 id（含创建时间戳）倒序兜底。 */
const doneDesc = (a: Task, b: Task) =>
  (b.completedAt ?? '').localeCompare(a.completedAt ?? '') || b.id.localeCompare(a.id)

const taskGroups = computed(() => {
  const list = myList.value
  const open = list.filter((t) => t.status !== '已完成' || isSettling(t.id))
  const done = list.filter((t) => t.status === '已完成' && !isSettling(t.id)).sort(doneDesc)
  const buckets: [string, (due: string) => boolean][] = [
    ['今天', (due) => !!due && due <= data.today],
    ['本周', (due) => !!due && due > data.today && due <= weekEnd.value],
    ['稍后', (due) => !due || due > weekEnd.value],
  ]
  const groups: { label: string; items: ReturnType<typeof decoTask>[]; collapsible?: boolean }[] = buckets
    .map(([label, fn]) => ({ label, items: open.filter((t) => fn(t.due)).map(decoTask) }))
    .filter((g) => g.items.length)
  if (done.length) groups.push({ label: '已完成', items: done.map(decoTask), collapsible: true })
  return groups
})

const boardCols = computed(() =>
  TASK_STATUSES.map((st) => {
    const items = myList.value.filter((t) => t.status === st)
    if (st === '已完成') items.sort(doneDesc)
    return { label: st, dot: TASK_STATUS_DOTS[st], collapsible: st === '已完成', items: items.map(decoTask) }
  }),
)

const tasksEmpty = computed(() => taskGroups.value.length === 0)
const selectedTaskIds = ref<string[]>([])
const quadrantSelectionMode = ref(false)
const selectionMode = computed(() => taskView.value !== 'quadrant' || quadrantSelectionMode.value)
function toggleQuadrantSelection() {
  quadrantSelectionMode.value = !quadrantSelectionMode.value
  if (!quadrantSelectionMode.value) selectedTaskIds.value = []
}
const visibleTaskIds = computed(() => myList.value.filter((task) => task.status !== '已完成'
  || showDone.value || (taskView.value === 'list' && isSettling(task.id))).map((task) => task.id))
const selectedCopyTasks = computed(() => myList.value.filter((task) => selectedTaskIds.value.includes(task.id)))
watch(visibleTaskIds, (ids) => { selectedTaskIds.value = selectedTaskIds.value.filter((id) => ids.includes(id)) })
function toggleSelection(id: string) {
  selectedTaskIds.value = selectedTaskIds.value.includes(id)
    ? selectedTaskIds.value.filter((value) => value !== id) : [...selectedTaskIds.value, id]
}

function openTask(id: string) {
  ui.openTask(id)
}

// 看板拖拽改状态
const dragId = ref<string | null>(null)
function onDragStart(id: string) {
  dragId.value = id
}
function onDropStatus(status: TaskStatus) {
  const id = dragId.value
  dragId.value = null
  if (!id) return
  const t = data.taskById(id)
  if (t && t.status !== status) data.patchTask(id, { status }, `将状态改为「${status}」`)
}
</script>

<template>
  <div class="todo" :class="{ 'todo-quadrant': taskView === 'quadrant' }">
    <div class="ptop">
      <div class="ptitle">TODO</div>
      <span class="ptip">跨项目任务汇总 · 任务归属于各自项目</span>
      <div class="spacer"></div>
      <button class="btn-primary" @click="ui.openModal('task')">
        <i class="ri-add-line"></i>新建任务
      </button>
    </div>

    <div class="mt-toolbar">
      <button
        v-for="c in (['全部', '我负责', '我参与', '等待他人'] as const)"
        :key="c"
        class="chip"
        :class="{ on: taskFilter === c }"
        @click="taskFilter = c"
      >
        {{ c }}
      </button>
      <div class="spacer"></div>
      <button v-if="taskView === 'quadrant'" class="chip" :aria-pressed="quadrantSelectionMode" @click="toggleQuadrantSelection">{{ quadrantSelectionMode ? '退出批量选择' : '批量选择' }}</button>
      <div class="seg">
        <button
          v-for="v in ([['列表', 'ri-list-check-2', 'list'], ['看板', 'ri-layout-column-line', 'board'], ['四象限', 'ri-layout-grid-line', 'quadrant']] as const)"
          :key="v[2]"
          class="seg-btn icon"
          :class="{ on: taskView === v[2] }"
          @click="taskView = v[2]"
        >
          <i :class="v[1]"></i>{{ v[0] }}
        </button>
      </div>
    </div>

    <div v-if="data.taskLoadState === 'loaded' && selectionMode" class="copy-selection-actions">
      <button class="chip" :disabled="!visibleTaskIds.length" @click="selectedTaskIds = [...visibleTaskIds]">全选当前可见 · {{ visibleTaskIds.length }}</button>
      <button v-if="selectedTaskIds.length" class="chip" @click="selectedTaskIds = []">清空选择</button>
    </div>
    <TaskBatchCopy v-if="data.taskLoadState === 'loaded' && selectionMode" :tasks="selectedCopyTasks" />

    <div v-if="data.taskLoadState === 'loading'" class="task-state" role="status" aria-live="polite">
      <i class="ri-loader-4-line spinning"></i>
      <b>正在加载中</b>
      <span>正在读取你的真实任务…</span>
    </div>

    <div v-else-if="data.taskLoadState === 'error'" class="task-state error" role="alert">
      <i class="ri-wifi-off-line"></i>
      <b>任务暂时没有加载出来</b>
      <span>{{ data.taskLoadError }}</span>
      <button class="btn-primary" @click="data.hydrate()"><i class="ri-refresh-line"></i>重新加载</button>
    </div>

    <template v-else-if="taskView === 'list'">
      <div v-for="tg in taskGroups" :key="tg.label" class="tgroup">
        <div class="tgroup-head" :class="{ clickable: tg.collapsible }" @click="tg.collapsible && (showDone = !showDone)">
          <i v-if="tg.collapsible" class="tgroup-chev" :class="showDone ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
          <span class="tgroup-label">{{ tg.label }}</span>
          <span class="tgroup-count wao-tabular">{{ tg.items.length }}</span>
          <span v-if="tg.collapsible" class="tgroup-hint">{{ showDone ? '点击收起' : '点击展开' }}</span>
        </div>
        <div v-if="tg.collapsible && !showDone" class="tgroup-collapsed">已收起 {{ tg.items.length }} 项</div>
        <div v-else class="tlist">
          <div v-for="t in tg.items" :key="t.id" class="trow" :class="{ settling: isSettling(t.id) }" @click="openTask(t.id)">
            <button class="task-select" :aria-label="`选择任务 ${t.title}`" :aria-pressed="selectedTaskIds.includes(t.id)" @click.stop="toggleSelection(t.id)"><i :class="selectedTaskIds.includes(t.id) ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i></button>
            <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" title="完成 / 重新打开" @click.stop="toggleTask(t.id)">
              <i class="ri-check-line" :style="{ opacity: t.checkOp }"></i>
            </button>
            <span class="trow-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</span>
            <span v-if="t.waitBadge" class="wait-badge">{{ t.waitBadge }}</span>
            <span class="trow-proj"><span class="proj-dot" :style="{ background: t.projColor }"></span><span class="ellip">{{ t.projName }}</span></span>
            <span class="pr" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
            <span class="owner-av" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
            <span class="trow-due" :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
          </div>
        </div>
      </div>
      <div v-if="tasksEmpty" class="empty simple">这个筛选下没有任务</div>
    </template>

    <TaskQuadrantView v-else-if="taskView === 'quadrant'" v-model:show-done="showDone" :tasks="myList" :selected-ids="quadrantSelectionMode ? selectedTaskIds : []" :selection-mode="quadrantSelectionMode" @select="toggleSelection" />

    <div v-else class="board">
      <div
        v-for="col in boardCols"
        :key="col.label"
        class="board-col"
        :class="{ 'drop-hint': dragId }"
        @dragover.prevent
        @drop="onDropStatus(col.label as TaskStatus)"
      >
        <div class="board-col-head" :class="{ clickable: col.collapsible }" @click="col.collapsible && (showDone = !showDone)">
          <span class="board-dot" :style="{ background: col.dot }"></span>
          <span class="board-col-label">{{ col.label }}</span>
          <span class="board-col-count wao-tabular">{{ col.items.length }}</span>
          <i v-if="col.collapsible" class="board-col-chev" :class="showDone ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
        </div>
        <div v-if="col.collapsible && !showDone" class="board-collapsed">已收起 {{ col.items.length }} 项 · 点标题展开</div>
        <div v-else class="board-cards">
          <div
            v-for="t in col.items"
            :key="t.id"
            class="board-card"
            :class="{ dragging: dragId === t.id }"
            draggable="true"
            @click="openTask(t.id)"
            @dragstart="onDragStart(t.id)"
          >
            <button class="task-select" :aria-label="`选择任务 ${t.title}`" :aria-pressed="selectedTaskIds.includes(t.id)" @click.stop="toggleSelection(t.id)"><i :class="selectedTaskIds.includes(t.id) ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'"></i></button>
            <div class="bc-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</div>
            <div class="bc-proj"><span class="proj-dot" :style="{ background: t.projColor }"></span><span class="ellip">{{ t.projName }}</span></div>
            <div class="bc-foot">
              <span class="pr" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
              <span class="bc-due" :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
              <span class="spacer"></span>
              <span class="owner-av small" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<style scoped>
.copy-selection-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0 8px; }
.task-select { flex-shrink: 0; width: 28px; height: 28px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-weak); cursor: pointer; }
.task-select[aria-pressed='true'] { color: var(--wao-primary-text); background: var(--wao-primary-soft); }
.todo {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}
.todo.todo-quadrant { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; max-width: none; margin: 0; padding: 16px 20px 12px; }
.todo-quadrant > :not(.quadrant-view) { flex-shrink: 0; }
.todo-quadrant :deep(.copy-preview) { max-height: 25vh; overflow: auto; }
.ellip {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ptop {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.ptitle {
  font-size: 20px;
  font-weight: 700;
}
.ptip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.seg {
  display: flex;
  background: var(--wao-divider);
  border-radius: 9px;
  padding: 3px;
  gap: 2px;
}
.seg-btn {
  height: 26px;
  padding: 0 12px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-3);
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.seg-btn.icon i {
  font-size: 13px;
}
.seg-btn.on {
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(16, 18, 28, 0.1);
}
.btn-primary {
  height: 33px;
  padding: 0 14px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary i {
  font-size: 15px;
}

.chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  border-radius: 999px;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.chip.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: var(--wao-primary-line);
  font-weight: 600;
}

.empty {
  margin-top: 14px;
  padding: 44px;
  text-align: center;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.empty.simple {
  margin-top: 24px;
}

.mt-toolbar {
  margin-top: 14px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.task-state {
  margin-top: 18px;
  min-height: 180px;
  padding: 36px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  color: var(--wao-text-weak);
}
.task-state > i {
  font-size: 24px;
  color: var(--wao-primary-text);
}
.task-state b {
  color: var(--wao-text-2);
  font-size: 14px;
}
.task-state span {
  font-size: 12.5px;
}
.task-state.error > i {
  color: var(--wao-danger);
}
.task-state .btn-primary {
  margin-top: 6px;
}
.spinning {
  animation: task-spin 0.8s linear infinite;
}
@keyframes task-spin {
  to { transform: rotate(360deg); }
}
.tgroup {
  margin-top: 18px;
}
.tgroup-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 2px 8px;
}
.tgroup-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text-2);
}
.tgroup-count {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.tgroup-head.clickable {
  cursor: pointer;
  border-radius: 7px;
  padding: 4px 6px 8px;
  margin: 0 -6px;
}
.tgroup-head.clickable:hover .tgroup-label {
  color: var(--wao-text);
}
.tgroup-chev {
  font-size: 15px;
  color: var(--wao-text-faint-2);
}
.tgroup-hint {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.tgroup-collapsed {
  background: var(--wao-bg-card);
  border: 1px dashed var(--wao-line-strong);
  border-radius: 12px;
  padding: 14px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-faint);
}
.tlist {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 5px 8px;
}
.trow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 8px;
  cursor: pointer;
}
.trow:hover {
  background: var(--wao-bg-hover);
}
/* 勾完成后的 3 秒缓冲：淡出但不移位 */
.trow.settling {
  opacity: 0.55;
  transition: opacity 0.4s ease;
}
.check {
  width: 17px;
  height: 17px;
  border-radius: 5px;
  border: 1.5px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
}
.check i {
  font-size: 12px;
  color: #fff;
}
.trow-title {
  flex: 1;
  min-width: 0;
  font-size: 13.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wait-badge {
  font-size: 11px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 5px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.trow-proj {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
  max-width: 150px;
}
.proj-dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  flex-shrink: 0;
}
.pr {
  font-size: 11px;
  border-radius: 5px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.owner-av {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  color: #fff;
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.owner-av.small {
  width: 19px;
  height: 19px;
  font-size: 9.5px;
}
.trow-due {
  font-size: 12px;
  width: 62px;
  text-align: right;
  flex-shrink: 0;
}

/* board */
.board {
  margin-top: 14px;
  display: flex;
  gap: 12px;
  overflow-x: auto;
  align-items: flex-start;
  padding-bottom: 8px;
}
.board-col {
  /* 六个状态列平分宽度，宽屏一屏放下；窄到放不下（min-width 兜底）再横向滚动 */
  flex: 1 1 0;
  min-width: 172px;
  max-width: 262px;
  background: var(--wao-bg-hover-2);
  border-radius: 12px;
  padding: 10px;
  transition: background 0.12s, box-shadow 0.12s;
}
.board-col.drop-hint {
  background: var(--wao-primary-soft);
  box-shadow: inset 0 0 0 1.5px var(--wao-primary-line);
}
.board-col-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 2px 4px 8px;
}
.board-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.board-col-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text-2);
}
.board-col-count {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.board-col-head.clickable {
  cursor: pointer;
  border-radius: 7px;
}
.board-col-head.clickable:hover {
  background: var(--wao-bg-hover);
}
.board-col-chev {
  margin-left: auto;
  font-size: 15px;
  color: var(--wao-text-faint-2);
}
.board-collapsed {
  border: 1px dashed var(--wao-line-strong);
  border-radius: 10px;
  padding: 14px 10px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-faint);
}
.board-cards {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
}
.board-card {
  box-sizing: border-box;
  min-width: 0;
  max-width: 100%;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 11px 12px;
  cursor: grab;
}
.board-card.dragging {
  opacity: 0.5;
}
.board-card:hover {
  border-color: var(--wao-line-control);
  box-shadow: 0 2px 8px rgba(16, 18, 28, 0.06);
}
.bc-title {
  min-width: 0;
  font-size: 13px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.bc-proj {
  min-width: 0;
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  gap: 6px;
}
.bc-proj .ellip {
  min-width: 0;
}
.bc-foot {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.bc-due {
  font-size: 11.5px;
}

@media (max-width: 760px) {
  .todo { padding: 16px 12px 28px; }
  .todo.todo-quadrant { padding: 10px 8px 8px; }
  .todo-quadrant .ptip { display: none; }
  .seg { flex-wrap: wrap; }
  .seg-btn { min-height: 36px; }
  .btn-primary { min-height: 36px; }
  .trow { align-items: flex-start; flex-wrap: wrap; }
  .trow-title { flex-basis: calc(100% - 30px); white-space: normal; }
  .trow-proj { max-width: calc(100% - 80px); margin-left: 27px; }
  .trow-due { margin-left: auto; }
}
</style>
