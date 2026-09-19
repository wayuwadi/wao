<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useDeco } from '@/composables/deco'
import { toast } from '@/composables/toast'
import { selectableTaskStatuses } from '@/config/features'
import { fmtDate } from '@/utils/domain'
import WorkItemIcon from '@/components/task/WorkItemIcon.vue'
import CellMenu from '@/components/task/CellMenu.vue'
import type { Priority, TaskGroup, TaskStatus } from '@/types'

type TaskRowView = ReturnType<ReturnType<typeof useDeco>['decoTask']>

/**
 * 项目表格里的一行主任务，版式参照 ONES：编号、类型图标 + 标题、分组、实心优先级、描边状态胶囊、成员头像。
 *
 * <p>展示出来的字段都能在行内直接改：标题（铅笔）、分组、优先级、状态、负责人、计划开始、截止日期。
 * 创建者与创建日期由服务端记录，只读。状态保存失败时保留本地修改，点状态格即可重试。
 * 下拉菜单悬浮在表格之上（CellMenu），点整个单元格任意处都能打开。
 */
const props = defineProps<{
  task: TaskRowView
  groups: TaskGroup[]
  groupId: string
  expanded: boolean
  dragging: boolean
}>()
const emit = defineEmits<{ 'toggle-subs': []; open: []; completed: []; 'add-sub': [] }>()
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
const PRIORITIES: Priority[] = ['P0', 'P1', 'P2', 'P3']
const statusColor = (status: TaskStatus) => STATUS_COLOR[status] ?? '#9aa0ab'
const statusLabel = (status: TaskStatus) => (status === '待办' ? '未开始' : status)

const menu = ref<'status' | 'pr' | 'owner' | null>(null)
const statusCell = ref<HTMLElement | null>(null)
const prCell = ref<HTMLElement | null>(null)
const ownerCell = ref<HTMLElement | null>(null)
const saving = ref(false)
const failed = ref(false)
const statusOptions = computed(() => selectableTaskStatuses(props.task.status))
const doneSubs = computed(() => props.task.subs.filter((sub) => sub.done).length)
const creator = computed(() => (props.task.createdBy ? session.memberById(props.task.createdBy) : null))
/** 创建日期只展示年月日；极早期没有可靠创建时间的历史任务显示占位。 */
const createdDate = computed(() => props.task.createdAt?.slice(0, 10).replace(/-/g, '/') ?? '')

function toggle(kind: 'status' | 'pr' | 'owner') {
  if (kind === 'status' && saving.value) return
  menu.value = menu.value === kind ? null : kind
}
async function changeStatus(status: TaskStatus) {
  menu.value = null
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
function changePriority(pr: Priority) {
  menu.value = null
  if (pr !== props.task.pr) data.patchTask(props.task.id, { pr }, `将优先级改为 ${pr}`)
}
function changeOwner(id: string) {
  menu.value = null
  if (id !== props.task.owner) data.patchTask(props.task.id, { owner: id }, `将负责人改为 ${session.memberById(id).name}`)
}
function changeGroup(event: Event) {
  data.moveTaskToGroup(props.task.id, (event.target as HTMLSelectElement).value)
}
function changeDate(field: 'start' | 'due', event: Event) {
  const value = (event.target as HTMLInputElement).value
  if ((props.task[field] ?? '') === value) return
  const label = field === 'start' ? '计划开始' : '截止日期'
  data.patchTask(props.task.id, field === 'start' ? { start: value } : { due: value },
    value ? `将${label}改为 ${fmtDate(value)}` : `清空了${label}`)
}

// ---- 标题就地改名 ----
const editingTitle = ref(false)
const titleDraft = ref('')
const titleInput = ref<HTMLInputElement | null>(null)
function startRename() {
  titleDraft.value = props.task.title
  editingTitle.value = true
  nextTick(() => titleInput.value?.select())
}
function commitRename() {
  if (!editingTitle.value) return
  editingTitle.value = false
  const title = titleDraft.value.trim()
  if (title && title !== props.task.title) data.patchTask(props.task.id, { title }, `将标题改为「${title}」`)
}
</script>

<template>
  <tr class="task-row" :class="{ done: task.status === '已完成', dragging }" draggable="true" @click="emit('open')">
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
        <input
          v-if="editingTitle"
          ref="titleInput"
          v-model="titleDraft"
          class="title-input"
          :aria-label="`修改 ${task.title} 的标题`"
          @click.stop
          @keydown.enter.prevent="!$event.isComposing && commitRename()"
          @keydown.esc.stop="editingTitle = false"
          @blur="commitRename"
        />
        <template v-else>
          <button class="trow-title" :class="{ done: task.status === '已完成' }" :title="task.title" @click.stop="emit('open')">{{ task.title }}</button>
          <span v-if="task.waitBadge" class="wait-badge">{{ task.waitBadge }}</span>
          <span v-if="task.subs.length" class="sub-count wao-tabular" :title="`子任务 已完成 ${doneSubs} / 共 ${task.subs.length}`"
            ><i class="ri-node-tree"></i>{{ doneSubs }}/{{ task.subs.length }}</span
          >
          <span class="row-tools">
            <button class="row-tool" title="修改标题" :aria-label="`修改 ${task.title} 的标题`" @click.stop="startRename"><i class="ri-pencil-line"></i></button>
            <button class="row-tool" title="添加子任务" :aria-label="`为 ${task.title} 添加子任务`" @click.stop="emit('add-sub')"><i class="ri-add-line"></i></button>
          </span>
        </template>
      </div>
    </td>
    <td @click.stop>
      <select class="cell-input" :value="groupId" :aria-label="`${task.title} 所在分组`" title="修改分组" @change="changeGroup">
        <option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option>
      </select>
    </td>
    <td ref="prCell" class="cell-pop" @click.stop="toggle('pr')">
      <button class="priority" :style="{ background: PRIORITY_COLOR[task.pr] ?? '#9aa0ab' }" :aria-label="`修改 ${task.title} 的优先级`" aria-haspopup="menu" :aria-expanded="menu === 'pr'">{{ task.pr }}</button>
      <CellMenu v-if="menu === 'pr'" :anchor="prCell" @close="menu = null">
        <button v-for="pr in PRIORITIES" :key="pr" role="menuitemradio" :aria-checked="task.pr === pr" :class="{ on: task.pr === pr }" @click="changePriority(pr)">
          <span class="priority sm" :style="{ background: PRIORITY_COLOR[pr] }">{{ pr }}</span>
          <i v-if="task.pr === pr" class="ri-check-line"></i>
        </button>
      </CellMenu>
    </td>
    <td
      ref="statusCell"
      class="cell-pop"
      :title="failed ? '状态保存失败，点击重试' : '点击修改状态'"
      @click.stop="failed ? changeStatus(task.status) : toggle('status')"
    >
      <button
        class="status-pill task-status-trigger"
        :class="{ saving, error: failed }"
        :style="{ color: statusColor(task.status), borderColor: statusColor(task.status) }"
        aria-haspopup="menu"
        :aria-expanded="menu === 'status'"
      >
        <i v-if="saving" class="ri-loader-4-line spin"></i>
        <i v-else-if="failed" class="ri-error-warning-line"></i>
        {{ statusLabel(task.status) }}
      </button>
      <CellMenu v-if="menu === 'status'" :anchor="statusCell" class="task-status-menu" @close="menu = null">
        <button
          v-for="status in statusOptions"
          :key="status"
          role="menuitemradio"
          :aria-checked="task.status === status"
          :class="{ on: task.status === status }"
          @click="changeStatus(status)"
        >
          <span class="status-pill sm" :style="{ color: statusColor(status), borderColor: statusColor(status) }">{{ statusLabel(status) }}</span>
          <i v-if="task.status === status" class="ri-check-line"></i>
        </button>
      </CellMenu>
    </td>
    <td ref="ownerCell" class="cell-pop" :title="`负责人：${task.ownerName}，点击修改`" @click.stop="toggle('owner')">
      <button class="table-member member-trigger" :aria-label="`修改 ${task.title} 的负责人`" aria-haspopup="menu" :aria-expanded="menu === 'owner'">
        <span class="member-av" :style="{ background: task.ownerColor }">{{ task.ownerInitial }}</span><span class="member-name">{{ task.ownerName }}</span>
      </button>
      <CellMenu v-if="menu === 'owner'" :anchor="ownerCell" :min-width="120" @close="menu = null">
        <button v-for="m in session.members" :key="m.id" role="menuitemradio" :aria-checked="task.owner === m.id" :class="{ on: task.owner === m.id }" @click="changeOwner(m.id)">
          <span class="table-member"><span class="member-av" :style="{ background: m.color }">{{ m.initial }}</span>{{ m.name }}</span>
          <i v-if="task.owner === m.id" class="ri-check-line"></i>
        </button>
      </CellMenu>
    </td>
    <td :title="creator ? `创建者：${creator.name}（系统记录）` : '历史任务没有创建者记录'">
      <span v-if="creator" class="table-member"><span class="member-av" :style="{ background: creator.color }">{{ creator.initial }}</span><span class="member-name">{{ creator.name }}</span></span>
      <span v-else class="faint">—</span>
    </td>
    <td class="wao-tabular created-date" :title="task.createdAt ? `创建于 ${task.createdAt.replace('T', ' ').slice(0, 16)}` : '历史任务没有创建时间'">
      <span v-if="createdDate">{{ createdDate }}</span><span v-else class="faint">—</span>
    </td>
    <td @click.stop>
      <input class="cell-input date" :class="{ empty: !task.start }" type="date" :value="task.start || ''" :max="task.due || undefined" :aria-label="`${task.title} 计划开始`" @change="changeDate('start', $event)" />
    </td>
    <td @click.stop>
      <input class="cell-input date" :class="{ empty: !task.due }" type="date" :value="task.due || ''" :min="task.start || undefined" :style="{ color: task.dueColor }" :aria-label="`${task.title} 截止日期`" @change="changeDate('due', $event)" />
    </td>
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
.task-row td.cell-pop {
  cursor: pointer;
}
.task-row td:last-child {
  border-right: 0;
}

.task-row:hover td {
  background: var(--wao-bg-hover);
}
.task-row.dragging {
  opacity: 0.45;
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
  flex: 0 1 auto;
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
  color: var(--wao-primary-text);
}
.trow-title.done {
  color: var(--wao-text-faint);
}
.title-input {
  flex: 1;
  min-width: 0;
  height: 26px;
  padding: 0 6px;
  border: 1px solid var(--wao-primary);
  border-radius: 4px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: 13px;
  outline: none;
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
/* 行尾小工具：鼠标移到这一行才出现，不常驻占位 */
.row-tools {
  display: inline-flex;
  gap: 2px;
  margin-left: auto;
  flex-shrink: 0;
  opacity: 0;
}
.task-row:hover .row-tools,
.row-tools:focus-within {
  opacity: 1;
}
.row-tool {
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 4px;
  background: none;
  color: var(--wao-text-weak);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}
.row-tool:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary-text);
}
.cell-input {
  width: 100%;
  height: 26px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--wao-text-2);
  font: inherit;
  font-size: 13px;
  padding: 0 2px;
  cursor: pointer;
}
.cell-input:hover,
.cell-input:focus {
  border-color: var(--wao-line-control);
  outline: none;
}
.cell-input.date {
  font-variant-numeric: tabular-nums;
}
/* 没排期的日期格不显示「年/月/日」占位，鼠标移上去才露出输入框 */
.cell-input.date.empty:not(:hover):not(:focus) {
  color: transparent !important;
}
.created-date {
  color: var(--wao-text-weak) !important;
  font-size: 12px !important;
}
.priority {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 18px;
  padding: 0 4px;
  border: none;
  border-radius: 3px;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}
.priority.sm {
  cursor: inherit;
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
.member-trigger {
  max-width: 100%;
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  font-size: 13px;
  color: var(--wao-text-2);
  cursor: pointer;
}
.member-name {
  overflow: hidden;
  text-overflow: ellipsis;
}
.table-member {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
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
