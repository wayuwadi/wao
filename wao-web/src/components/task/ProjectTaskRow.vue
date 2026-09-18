<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useDeco } from '@/composables/deco'
import { toast } from '@/composables/toast'
import { selectableTaskStatuses } from '@/config/features'
import WorkItemIcon from '@/components/task/WorkItemIcon.vue'
import type { Priority, TaskGroup, TaskStatus } from '@/types'

type TaskRowView = ReturnType<ReturnType<typeof useDeco>['decoTask']>

/**
 * 项目表格里的一行主任务，版式参照 ONES：编号、类型图标 + 标题、分组、实心优先级、描边状态胶囊、成员头像。
 *
 * <p>状态与分组都能在行内直接改；状态保存失败时保留本地修改，点胶囊即可重试。
 */
const props = defineProps<{
  task: TaskRowView
  groups: TaskGroup[]
  groupId: string
  selected: boolean
  expanded: boolean
  dragging: boolean
}>()
const emit = defineEmits<{ select: []; 'toggle-subs': []; open: []; completed: [] }>()
const data = useDataStore()
const session = useSessionStore()

/** 描边状态色：未开始蓝、进行中橙、已完成绿，与 ONES 的状态胶囊一致。 */
const STATUS_COLOR: Record<TaskStatus, string> = {
  待办: '#338fe5',
  收集中: '#d172a8',
  进行中: '#f08c00',
  'AI 进行中': '#8a5cd6',
  待验证: '#d9a13b',
  已完成: '#3fb96f',
}
const PRIORITY_COLOR: Record<Priority, string> = { P0: '#e5484d', P1: '#f08c00', P2: '#338fe5', P3: '#9aa0ab' }
const statusColor = (status: TaskStatus) => STATUS_COLOR[status] ?? '#9aa0ab'

const menuOpen = ref(false)
const saving = ref(false)
const failed = ref(false)
const statusOptions = computed(() => selectableTaskStatuses(props.task.status))
const doneSubs = computed(() => props.task.subs.filter((sub) => sub.done).length)
const creator = computed(() => (props.task.createdBy ? session.memberById(props.task.createdBy) : null))

function toggleMenu() {
  if (!saving.value) menuOpen.value = !menuOpen.value
}
async function changeStatus(status: TaskStatus) {
  menuOpen.value = false
  if (saving.value || (status === props.task.status && !failed.value)) return
  saving.value = true
  if (status !== props.task.status) {
    data.patchTask(props.task.id, { status }, `将状态改为「${status}」`)
    if (status === '已完成') emit('completed')
  }
  await data.flushTask(props.task.id)
  failed.value = data.taskSaveState(props.task.id) === 'error'
  toast(
    failed.value ? '状态保存失败，本地修改已保留；点击状态可重试' : `状态已改为「${status}」`,
    failed.value ? 'ri-error-warning-line' : 'ri-check-line',
  )
  saving.value = false
}
function changeGroup(event: Event) {
  data.moveTaskToGroup(props.task.id, (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <tr class="task-row" :class="{ done: task.status === '已完成', dragging, selected }" draggable="true" @click="emit('open')">
    <td class="cell-select">
      <button
        class="select-dot"
        :class="{ on: selected }"
        :aria-label="`选择任务 ${task.title}`"
        :aria-pressed="selected"
        @click.stop="emit('select')"
      >
        <i :class="selected ? 'ri-checkbox-fill' : 'ri-checkbox-blank-line'"></i>
      </button>
    </td>
    <td class="table-number wao-tabular">{{ task.number ?? '…' }}</td>
    <td>
      <div class="title-cell">
        <button
          v-if="task.subs.length"
          class="tree-toggle"
          :aria-label="`展开或收起 ${task.title} 的子任务`"
          :aria-expanded="expanded"
          @click.stop="emit('toggle-subs')"
        >
          <i :class="expanded ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
        </button>
        <span v-else class="tree-spacer"></span>
        <WorkItemIcon kind="task" />
        <button class="trow-title" :class="{ done: task.status === '已完成' }" :title="task.title" @click.stop="emit('open')">{{ task.title }}</button>
        <span v-if="task.waitBadge" class="wait-badge">{{ task.waitBadge }}</span>
        <span v-if="task.subs.length" class="sub-count wao-tabular" :title="`子任务 已完成 ${doneSubs} / 共 ${task.subs.length}`"
          ><i class="ri-node-tree"></i>{{ doneSubs }}/{{ task.subs.length }}</span
        >
      </div>
    </td>
    <td @click.stop>
      <select class="group-select" :value="groupId" :aria-label="`${task.title} 所在分组`" title="修改分组" @change="changeGroup">
        <option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option>
      </select>
    </td>
    <td>
      <span class="priority" :style="{ background: PRIORITY_COLOR[task.pr] ?? '#9aa0ab' }">{{ task.pr }}</span>
    </td>
    <td class="cell-status">
      <div class="task-status" @click.stop>
        <button
          class="status-pill task-status-trigger"
          :class="{ saving, error: failed }"
          :style="{ color: statusColor(task.status), borderColor: statusColor(task.status) }"
          :title="failed ? '状态保存失败，点击重试' : '点击修改状态'"
          aria-haspopup="menu"
          :aria-expanded="menuOpen"
          @click="failed ? changeStatus(task.status) : toggleMenu()"
        >
          <i v-if="saving" class="ri-loader-4-line spin"></i>
          <i v-else-if="failed" class="ri-error-warning-line"></i>
          {{ task.status === '待办' ? '未开始' : task.status }}
        </button>
        <div v-if="menuOpen" class="menu-mask" @click="menuOpen = false"></div>
        <div v-if="menuOpen" class="task-status-menu" role="menu">
          <button
            v-for="status in statusOptions"
            :key="status"
            role="menuitemradio"
            :aria-checked="task.status === status"
            :class="{ on: task.status === status }"
            @click="changeStatus(status)"
          >
            <span class="status-pill sm" :style="{ color: statusColor(status), borderColor: statusColor(status) }">{{ status === '待办' ? '未开始' : status }}</span>
            <i v-if="task.status === status" class="ri-check-line"></i>
          </button>
        </div>
      </div>
    </td>
    <td>
      <span class="table-member"><span class="member-av" :style="{ background: task.ownerColor }">{{ task.ownerInitial }}</span>{{ task.ownerName }}</span>
    </td>
    <td>
      <span v-if="creator" class="table-member"><span class="member-av" :style="{ background: creator.color }">{{ creator.initial }}</span>{{ creator.name }}</span>
      <span v-else class="faint">—</span>
    </td>
    <td class="wao-tabular">{{ task.start || '—' }}</td>
    <td class="wao-tabular" :style="{ color: task.dueColor }">{{ task.due || '—' }}</td>
  </tr>
</template>

<style scoped>
.task-row {
  cursor: pointer;
}
.task-row td {
  height: 36px;
  padding: 0 10px;
  border-right: 1px solid var(--wao-line);
  border-bottom: 1px solid var(--wao-line);
  font-size: 13px;
  color: var(--wao-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  background: var(--wao-bg-card);
}
.task-row td.cell-status {
  overflow: visible;
}
.task-row td:last-child {
  border-right: 0;
}
.task-row:hover td,
.task-row.selected td {
  background: var(--wao-bg-hover);
}
.task-row.dragging {
  opacity: 0.45;
}
.cell-select {
  text-align: center;
  padding: 0 !important;
}
.select-dot {
  border: none;
  background: none;
  padding: 0;
  color: var(--wao-text-faint);
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  opacity: 0;
}
.task-row:hover .select-dot,
.select-dot.on,
:global(.selecting-tasks) .select-dot {
  opacity: 1;
}
.select-dot.on {
  color: var(--wao-primary);
}
.table-number {
  color: var(--wao-text-weak);
  font-size: 12px;
}
.title-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.tree-toggle {
  width: 16px;
  height: 16px;
  border: none;
  background: none;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--wao-text-weak);
  cursor: pointer;
  flex-shrink: 0;
  font-size: 16px;
}
.tree-spacer {
  width: 16px;
  flex-shrink: 0;
}
.trow-title {
  flex: 1;
  min-width: 0;
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  font-size: 13px;
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trow-title:hover {
  color: var(--wao-primary);
}
.trow-title.done {
  color: var(--wao-text-faint);
}
.wait-badge {
  font-size: 11px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 4px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.sub-count {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
  height: 18px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 11px;
}
.group-select {
  width: 100%;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--wao-text-2);
  font: inherit;
  font-size: 13px;
  padding: 2px 0;
  cursor: pointer;
}
.group-select:hover,
.group-select:focus {
  border-color: var(--wao-line-control);
  outline: none;
}
.priority {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 18px;
  padding: 0 4px;
  border-radius: 3px;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
}
.task-status {
  position: relative;
}
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  padding: 0 8px;
  border: 1px solid;
  border-radius: 10px;
  background: none;
  font: inherit;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
}
.status-pill.sm {
  cursor: inherit;
}
.status-pill.error {
  border-style: dashed;
}
.menu-mask {
  position: fixed;
  inset: 0;
  z-index: 20;
}
.task-status-menu {
  position: absolute;
  z-index: 21;
  top: 24px;
  left: 0;
  min-width: 140px;
  padding: 4px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(16, 18, 28, 0.14);
}
.task-status-menu button {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: none;
  background: none;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-primary);
}
.task-status-menu button:hover,
.task-status-menu button.on {
  background: var(--wao-bg-hover);
}
.table-member {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.member-av {
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
.faint {
  color: var(--wao-text-faint);
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
