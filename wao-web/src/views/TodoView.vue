<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { TODO_DUE_GROUPS, todoDueGroup } from '@/utils/todoDueGroups'
import { isTaskFinished } from '@/utils/projectCompletion'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useDeco } from '@/composables/deco'
import { useCompletionSettle } from '@/composables/completion'
import { TASK_STATUSES, TASK_STATUS_DOTS } from '@/utils/domain'
import type { Task, TaskStatus } from '@/types'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { copyText } from '@/utils/clipboard'
import { shareUrl } from '@/utils/share'
import { readTodoTaskView, type TodoTaskView } from '@/utils/taskQuadrants'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const contextMenu = ref<InstanceType<typeof ContextActionMenu> | null>(null)
const { decoTask } = useDeco()

const taskFilter = ref<'全部' | '我负责' | '我参与' | '等待他人'>('全部')
const projectFilter = ref('all')
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
  if (projectFilter.value === 'unassigned') list = list.filter((t) => !t.project)
  else if (projectFilter.value !== 'all') list = list.filter((t) => t.project === projectFilter.value)
  return list
})

// 已完成默认收起，点分组标题展开；勾完成后先原地停留 3 秒再归到「已完成」里。
const showDone = ref(false)
const { toggle: toggleTask, isSettling } = useCompletionSettle()

/** 已完成任务倒序：最新完成的排最前；老数据没有完成时间时按 id（含创建时间戳）倒序兜底。 */
const doneDesc = (a: Task, b: Task) =>
  (b.completedAt ?? '').localeCompare(a.completedAt ?? '') || b.id.localeCompare(a.id)

const taskGroups = computed(() => {
  const list = myList.value
  const open = list.filter((t) => !isTaskFinished(t) || isSettling(t.id))
  const done = list.filter((t) => isTaskFinished(t) && !isSettling(t.id)).sort(doneDesc)
  const groups: { label: string; items: ReturnType<typeof decoTask>[]; collapsible?: boolean }[] = TODO_DUE_GROUPS
    .map(label => ({ label, items: open.filter(t => todoDueGroup(t.due, data.today) === label).map(decoTask) }))
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
function openTask(id: string) {
  ui.openTask(id)
}
function openTaskMenu(event: MouseEvent | KeyboardEvent, id: string) {
  const task = data.taskById(id)
  if (!task) return
  if (event instanceof KeyboardEvent) {
    if (!(event.shiftKey && event.key === 'F10') && event.key !== 'ContextMenu') return
    event.preventDefault()
  } else {
    if (event.target instanceof Element && event.target.closest('input, textarea, a, img, [contenteditable="true"]')) return
    const selection = window.getSelection()
    if (selection && !selection.isCollapsed && selection.anchorNode && event.currentTarget instanceof Node && event.currentTarget.contains(selection.anchorNode)) return
  }
  contextMenu.value?.openAt(event, `任务：${task.title}`, [
    { id: 'open', label: '打开任务', run: () => openTask(id) },
    { id: 'link', label: '复制分享链接', run: () => copyText(shareUrl(id), '任务分享链接已复制') },
    { id: 'id', label: '复制任务编号', run: () => copyText(id, '任务编号已复制') },
    { id: 'complete', label: task.status === '已完成' ? '重新打开任务' : '完成任务', separatorBefore: true, run: () => toggleTask(id) },
  ])
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
      <button class="btn-primary" @click="ui.openNewTask()">
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
      <select v-model="projectFilter" class="project-filter" aria-label="按项目筛选任务">
        <option value="all">全部项目</option>
        <option v-for="project in data.projects" :key="project.id" :value="project.id">{{ project.name }}</option>
        <option value="unassigned">未关联项目</option>
      </select>
      <div class="spacer"></div>
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
          <div v-for="t in tg.items" :key="t.id" class="trow" :class="{ settling: isSettling(t.id) }" tabindex="0" :aria-label="`任务：${t.title}`" @click="openTask(t.id)" @keydown.enter.self.prevent="openTask(t.id)" @keydown="openTaskMenu($event, t.id)" @contextmenu="openTaskMenu($event, t.id)">
            <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" title="完成 / 重新打开" @click.stop="toggleTask(t.id)">
              <i class="ri-check-line" :style="{ opacity: t.checkOp }"></i>
            </button>
            <div class="trow-main">
              <span class="trow-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</span>
              <div v-if="t.unfinishedSubCount || t.waitBadge || t.executionLabel" class="trow-context">
                <span v-if="t.unfinishedSubCount" class="wait-badge">仍有 {{ t.unfinishedSubCount }} 项子项未收尾</span>
                <span v-if="t.waitBadge" class="wait-badge">{{ t.waitBadge }}</span>
                <span v-if="t.executionLabel" class="execution-label">{{ t.executionLabel }}</span>
              </div>
            </div>
            <span class="trow-proj"><span class="proj-dot" :style="{ background: t.projColor }"></span><span class="ellip">{{ t.projName }}</span></span>
            <span class="pr" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
            <span class="owner-av" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
            <span class="trow-due" :style="{ color: t.dueColor }">{{ t.due ? (t.due < data.today ? t.dueStr : `${t.dueStr}截止`) : '无截止日期' }}</span>
            <button type="button" class="task-more" :aria-label="`更多任务操作：${t.title}`" aria-haspopup="menu" @click.stop="openTaskMenu($event, t.id)"><i class="ri-more-line"></i></button>
          </div>
        </div>
      </div>
      <div v-if="tasksEmpty" class="empty simple">这个筛选下没有任务</div>
    </template>

    <TaskQuadrantView v-else-if="taskView === 'quadrant'" v-model:show-done="showDone" :tasks="myList" :selected-ids="[]" />

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
        <div v-if="col.collapsible && !showDone" class="board-collapsed">已收起 {{ col.items.filter(isTaskFinished).length }} 项已收尾任务 · 点标题展开</div>
        <div v-if="!col.collapsible || showDone || col.items.some(t => !isTaskFinished(t) || isSettling(t.id))" class="board-cards">
          <div
            v-for="t in col.items.filter(t => !col.collapsible || showDone || !isTaskFinished(t) || isSettling(t.id))"
            :key="t.id"
            class="board-card"
            :aria-label="`任务：${t.title}`"
            :class="{ dragging: dragId === t.id }"
            draggable="true"
            @click="openTask(t.id)"
            @keydown.enter.self.prevent="openTask(t.id)"
            @contextmenu="openTaskMenu($event, t.id)"
            @keydown="openTaskMenu($event, t.id)"
            @dragstart="onDragStart(t.id)"
            tabindex="0"
          >
            <div class="bc-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</div>
            <span v-if="t.unfinishedSubCount" class="wait-badge">仍有 {{ t.unfinishedSubCount }} 项子项未收尾</span>
            <div class="bc-proj"><span class="proj-dot" :style="{ background: t.projColor }"></span><span class="ellip">{{ t.projName }}</span></div>
            <span v-if="t.executionLabel" class="execution-label">{{ t.executionLabel }}</span>
            <div class="bc-foot">
              <span class="pr" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
              <span class="bc-due" :style="{ color: t.dueColor }">{{ t.due ? (t.due < data.today ? t.dueStr : `${t.dueStr}截止`) : '无截止日期' }}</span>
              <span class="spacer"></span>
              <span class="owner-av small" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
              <button type="button" class="task-more" :aria-label="`更多任务操作：${t.title}`" aria-haspopup="menu" @click.stop="openTaskMenu($event, t.id)"><i class="ri-more-line"></i></button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <ContextActionMenu ref="contextMenu" />
  </div>
</template>

<style scoped>
.execution-label { color: var(--wao-primary-text); font-size: 11px; overflow-wrap: anywhere; }
.task-more { flex: none; border: 0; border-radius: 6px; background: transparent; color: var(--wao-text-2); cursor: pointer; }
.task-more:hover, .task-more:focus-visible { background: var(--wao-bg-hover); }
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
.project-filter {
  max-width: min(220px, 100%);
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 999px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font-size: 12.5px;
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
  min-width: 72px;
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

.trow-main { flex: 1; min-width: 0; }
.trow-main .trow-title { display: block; white-space: normal; overflow-wrap: anywhere; line-height: 1.55; }
.trow-context { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 7px; margin-top: 4px; }
.trow-context .wait-badge { max-width: 100%; overflow-wrap: anywhere; }
.trow-context .execution-label { padding: 1px 6px; border-radius: 5px; background: var(--wao-bg-hover); }
.task-more { min-width: 32px; min-height: 32px; }
.trow:focus-visible, .task-more:focus-visible, .check:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@media (max-width: 600px) {
  .trow-main { flex-basis: calc(100% - 40px); }
  .task-more { min-width: 40px; min-height: 40px; }
  .trow .check { margin-top: 4px; }
}
</style>
