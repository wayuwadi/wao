<script setup lang="ts">
import TaskOrderStatus from './TaskOrderStatus.vue'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { taskDropAfter } from '@/utils/taskDrop'
import { computed, nextTick, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import {
  subtaskRows, subtaskState, SUBTASK_DRAG_TYPE, SUBTASK_STATE_COLOR, SUBTASK_STATES, readSubtaskDrag,
} from '@/utils/subtaskTree'
import WorkItemIcon from '@/components/task/WorkItemIcon.vue'
import CellMenu from '@/components/task/CellMenu.vue'
import { priorityMeta } from '@/utils/domain'
import type { Priority, SubtaskState, TaskStep } from '@/types'
/**
 * 子任务列表：项目表格里渲染成与主任务同列的 `<tbody>` 行；任务 / 子任务详情里渲染成紧凑列表。
 * 两种形态共用同一套数据、编号、拖拽与草稿逻辑，只是外观不同。
 *
 * <p>表格形态的「隐藏已完成」由表格工具条统一控制（`hideDone`），每个任务下不再各放一个按钮；
 * 新增行也不常驻，只在点了行尾「+」后出现（`adding`，`addParentId` 为要挂到哪条子任务下），
 * 并且紧跟在要挂的那一行下面——新子任务本来就插在最前，输入框和刚加好的条目在同一个位置。
 * 有下级的子任务可以单独收起；`ownerFilter` 只保留该负责人的子任务及其上级。
 */
const props = defineProps<{
  columns?: readonly string[]
  taskId: string
  table?: boolean
  taskDepth?: number
  parentId?: string | null
  hideDone?: boolean
  adding?: boolean
  addParentId?: string | null
  ownerFilter?: string | null
}>()
const hasColumn = (key: string) => props.columns?.includes(key) ?? true
const emit = defineEmits<{ 'add-sub': [parentId: string | null]; 'close-add': [] }>()
const data = useDataStore()
const order = useTaskOrderStore()
const orderScope = computed(() => `subs:${props.taskId}`)
const draggingSub = ref<string | null>(null)
const orderingOver = ref<{ id: string; after: boolean } | null>(null)
const ui = useUiStore()
const session = useSessionStore()
const orderNotice = computed(() => {
  const state = order.state(orderScope.value)
  return state.loading || state.saving || state.error || state.dirty
})
watch(() => [orderScope.value, session.userId], () => { void order.load(orderScope.value) }, { immediate: true })
const task = computed(() => data.taskById(props.taskId))
const localHideDone = ref(false)
const hidingDone = computed(() => (props.table ? props.hideDone === true : localHideDone.value))
const rows = computed(() => {
  const subs = task.value?.subs || []
  const keys = new Map(subs.map((sub, idx) => [sub, data.subKey(props.taskId, sub, idx)]))
  const all = subtaskRows(props.taskId, order.sort(orderScope.value, subs, sub => keys.get(sub)!))
    .map(row => ({ ...row, key: keys.get(row.sub)!, idx: subs.indexOf(row.sub) }))
  if (!props.parentId) return all
  const parent = all.find(row => row.key === props.parentId)
  if (!parent) return []
  const start = all.indexOf(parent) + 1
  const children = []
  for (let i = start; i < all.length && all[i]!.depth > parent.depth; i++) children.push({ ...all[i]!, depth: all[i]!.depth - parent.depth - 1 })
  return children
})
/** 有下级的子任务 id，只有它们才显示收起箭头。 */
const parentIds = computed(() => new Set(rows.value.map(row => row.sub.parentId).filter((id): id is string => !!id)))
/** 表格里收起的子任务：只在本次页面内记住，展开主任务时默认全部展开。 */
const collapsed = ref<string[]>([])
function toggleCollapse(subId: string) {
  collapsed.value = collapsed.value.includes(subId) ? collapsed.value.filter(id => id !== subId) : [...collapsed.value, subId]
}
/** 按负责人筛选时保留命中的子任务和它们的上级链，层级才看得懂。 */
const ownerKeep = computed(() => {
  const owner = props.ownerFilter
  if (!owner) return null
  const byId = new Map(rows.value.map(row => [row.key, row.sub]))
  const keep = new Set<string>()
  for (const row of rows.value) {
    if (row.sub.owner !== owner) continue
    let id: string | null | undefined = row.key
    while (id && !keep.has(id)) {
      keep.add(id)
      id = byId.get(id)?.parentId
    }
  }
  return keep
})
const visibleRows = computed(() => {
  const hiddenUnder: number[] = []
  return rows.value.filter(row => {
    while (hiddenUnder.length && hiddenUnder[hiddenUnder.length - 1]! >= row.depth) hiddenUnder.pop()
    if (props.table && hiddenUnder.length) return false
    if (hidingDone.value && row.sub.done) return false
    if (ownerKeep.value && !ownerKeep.value.has(row.key)) return false
    if (props.table && collapsed.value.includes(row.key)) hiddenUnder.push(row.depth)
    return true
  })
})
/** 表格行序列：新增输入行插在要挂的父行下面，挂在主任务下时就是第一行。 */
const tableItems = computed(() => {
  const items: ({ kind: 'row'; row: (typeof visibleRows.value)[number] } | { kind: 'add'; depth: number })[] =
    visibleRows.value.map(row => ({ kind: 'row' as const, row }))
  if (!props.adding) return items
  const parent = props.addParentId ?? null
  if (!parent) return [{ kind: 'add' as const, depth: 0 }, ...items]
  const at = items.findIndex(item => item.kind === 'row' && item.row.key === parent)
  if (at < 0) return [...items, { kind: 'add' as const, depth: 0 }]
  const depth = (items[at] as { row: { depth: number } }).row.depth + 1
  return [...items.slice(0, at + 1), { kind: 'add' as const, depth }, ...items.slice(at + 1)]
})
// 给某条子任务加下级时，它和它的上级都得展开，输入框才看得见。
watch(() => [props.adding, props.addParentId], () => {
  let id = props.adding ? props.addParentId : null
  const byId = new Map(rows.value.map(row => [row.key, row.sub]))
  while (id) {
    const current: string = id
    collapsed.value = collapsed.value.filter(item => item !== current)
    id = byId.get(current)?.parentId
  }
})
const doneCount = computed(() => rows.value.filter(row => row.sub.done).length)
function drag(event: DragEvent, subId: string) {
  draggingSub.value = subId
  event.dataTransfer?.setData(SUBTASK_DRAG_TYPE, JSON.stringify({ taskId: props.taskId, subId }))
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function dragOver(event: DragEvent, id: string) {
  orderingOver.value = draggingSub.value && draggingSub.value !== id ? { id, after: taskDropAfter(event) } : null
}
async function drop(event: DragEvent, targetId?: string | null) {
  const payload = readSubtaskDrag(event), after = taskDropAfter(event)
  draggingSub.value = null; orderingOver.value = null
  if (!payload || !targetId || payload.subId === targetId) return
  localError.value = ''
  const source = data.subById(payload.taskId, payload.subId), target = data.subById(props.taskId, targetId)
  if (payload.taskId !== props.taskId || !source || !target || (source.parentId || null) !== (target.parentId || null)) {
    localError.value = '请在同一层级内调整顺序；更改归属请使用移动操作'; return
  }
  await order.move(orderScope.value, rows.value.map(row => row.key), payload.subId, targetId, after)
}
function member(id?: string | null) {
  return id ? session.memberById(id) : null
}
function stateStyle(sub: TaskStep) {
  const color = SUBTASK_STATE_COLOR[subtaskState(sub)]
  return { color, borderColor: color }
}
function createdDate(sub: TaskStep) {
  return sub.createdAt?.slice(0, 10).replace(/-/g, '/') ?? ''
}
// ---- 状态 / 负责人悬浮菜单：点单元格任意处打开，悬浮在表格之上 ----
const menu = ref<{ kind: 'state' | 'owner' | 'priority'; subId: string; anchor: HTMLElement } | null>(null)
const menuSub = computed(() => (menu.value ? data.subById(props.taskId, menu.value.subId) : undefined))
function openMenu(kind: 'state' | 'owner' | 'priority', subId: string, event: Event) {
  const anchor = event.currentTarget as HTMLElement
  menu.value = menu.value?.kind === kind && menu.value.subId === subId ? null : { kind, subId, anchor }
}
function pickState(state: SubtaskState) {
  if (menu.value) data.setSubState(props.taskId, menu.value.subId, state)
  menu.value = null
}
const priorities: Priority[] = ['P0', 'P1', 'P2', 'P3']
function pickPriority(pr: Priority) {
  if (menu.value && menuSub.value?.pr !== pr) data.updateSub(props.taskId, menu.value.subId, { pr })
  menu.value = null
}
function pickOwner(owner: string) {
  if (menu.value && menuSub.value?.owner !== owner) data.updateSub(props.taskId, menu.value.subId, { owner })
  menu.value = null
}
/** 新子任务挂到哪里：表格里由行尾「+」决定，详情里固定挂在当前查看的节点下。 */
const targetParent = computed(() => (props.table ? props.addParentId ?? null : props.parentId ?? null))
const targetParentText = computed(() => {
  const id = targetParent.value
  return id ? task.value?.subs.find((sub) => sub.id === id)?.text ?? '' : ''
})
function setDue(subId: string, event: Event) {
  data.updateSub(props.taskId, subId, { due: (event.target as HTMLInputElement).value || null })
}
// ---- 子任务标题就地改名（表格） ----
const renamingId = ref<string | null>(null)
const renameDraft = ref('')
const renameInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)
function startRename(subId: string, text: string) {
  renamingId.value = subId
  renameDraft.value = text
  nextTick(() => {
    const el = Array.isArray(renameInput.value) ? renameInput.value[0] : renameInput.value
    el?.select()
  })
}
function commitRename() {
  const id = renamingId.value
  renamingId.value = null
  const text = renameDraft.value.trim()
  if (id && text) data.updateSub(props.taskId, id, { text })
}
const addInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)
watch(() => [props.adding, props.addParentId], () => {
  if (props.table && props.adding) nextTick(() => (Array.isArray(addInput.value) ? addInput.value[0] : addInput.value)?.focus())
}, { immediate: true })
function closeAddIfEmpty() {
  if (!draft.value.trim()) emit('close-add')
}
function cancelAdd() {
  draft.value = ''
  emit('close-add')
}
const draft = ref('')
const localError = ref('')
const key = computed(() => `wao-inline-sub:${session.userId}:${props.taskId}${targetParent.value ? `:${targetParent.value}` : ''}`)
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
  if (data.addSub(props.taskId, draft.value, targetParent.value)) {
    draft.value = ''
    void data.flushTask(props.taskId)
  }
}
</script>
<template>
  <tbody v-if="task && table" class="table-subs" @click.stop @dragstart.stop @dragover.prevent @drop.prevent.stop :inert="data.movingSubtasks">
    <tr v-if="rows.length > 1 && orderNotice"><td :colspan="columns?.length ?? 10"><TaskOrderStatus :scope="orderScope" /></td></tr>
    <tr v-if="localError"><td :colspan="columns?.length ?? 10"><small class="error" role="alert">{{ localError }}</small></td></tr>
    <template v-for="item in tableItems" :key="item.kind === 'row' ? data.subKey(taskId, item.row.sub, item.row.idx) : '__add'">
      <tr v-if="item.kind === 'add'" class="sub-add-row">
        <td v-if="hasColumn('number')"></td>
        <td :colspan="(columns?.length ?? 10) - Number(hasColumn('number'))">
          <form :style="{ paddingLeft: `${44 + (item.depth + (taskDepth ?? 0)) * 20}px` }" @submit.prevent="add">
            <input
              ref="addInput"
              v-model="draft"
              :aria-label="`为 ${task.title} 添加子任务`"
              :placeholder="targetParentText ? `添加到「${targetParentText}」下，回车保存，Esc 收起` : '添加子任务，回车保存，Esc 收起'"
              @keydown.enter="$event.isComposing && $event.preventDefault()"
              @keydown.esc.stop="cancelAdd"
              @blur="closeAddIfEmpty"
            /><button :disabled="!draft.trim()">添加</button>
          </form>
        </td>
      </tr>
      <tr
        v-else
        class="inline-sub-row" :class="{ 'is-done': item.row.sub.done, 'order-before': orderingOver?.id === item.row.key && !orderingOver.after, 'order-after': orderingOver?.id === item.row.key && orderingOver.after }" draggable="true" @dragstart.stop="drag($event, item.row.key)" @dragover.prevent.stop="dragOver($event, item.row.key)" @dragleave="orderingOver = null" @dragend="draggingSub = null; orderingOver = null" @drop.prevent.stop="drop($event, item.row.key)"
        @click="ui.openTask(taskId, item.row.key)"
      >
        <td v-if="hasColumn('number')" class="sub-number wao-tabular">{{ item.row.sub.number ?? '…' }}</td>
        <td v-if="hasColumn('title')">
          <div class="sub-title" :style="{ paddingLeft: `${22 + (item.row.depth + (taskDepth ?? 0)) * 20}px` }">
            <button
              v-if="parentIds.has(item.row.key)"
              class="tree-toggle"
              :aria-label="`展开或收起 ${item.row.sub.text} 的子任务`"
              :aria-expanded="!collapsed.includes(item.row.key)"
              @click.stop="toggleCollapse(item.row.key)"
            ><i :class="collapsed.includes(item.row.key) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
            <span v-else class="tree-spacer"></span>
            <WorkItemIcon kind="subtask" /><input
              v-if="renamingId === item.row.key"
              ref="renameInput"
              v-model="renameDraft"
              class="rename-input"
              :aria-label="`修改子任务 ${item.row.sub.text} 的标题`"
              @click.stop
              @keydown.enter.prevent="!$event.isComposing && commitRename()"
              @keydown.esc.stop="renamingId = null"
              @blur="commitRename"
            /><template v-else><button
              class="sub-title-text"
              :class="{ done: item.row.sub.done }"
              :title="item.row.sub.text"
              @click.stop="ui.openTask(taskId, item.row.key)"
            >
              {{ item.row.sub.text }}
            </button><span class="row-tools">
              <button class="row-tool" title="修改标题" :aria-label="`修改子任务 ${item.row.sub.text} 的标题`" @click.stop="startRename(item.row.key, item.row.sub.text)"><i class="ri-pencil-line"></i></button>
              <button class="row-tool" title="在这条下添加子任务" :aria-label="`在 ${item.row.sub.text} 下添加子任务`" @click.stop="emit('add-sub', item.row.key)"><i class="ri-add-line"></i></button>
            </span></template>
          </div>
        </td>
        <td v-if="hasColumn('group')"></td>
        <td v-if="hasColumn('priority')" class="cell-pop" title="点击修改优先级" @click.stop="openMenu('priority', item.row.key, $event)">
          <button class="sub-priority" :style="item.row.sub.pr ? { background: priorityMeta(item.row.sub.pr).bg, color: priorityMeta(item.row.sub.pr).color } : {}" :aria-label="`修改子任务 ${item.row.sub.text} 的优先级`" aria-haspopup="menu" :aria-expanded="menu?.kind === 'priority' && menu.subId === item.row.key">{{ item.row.sub.pr || '—' }}</button>
        </td>
        <td v-if="hasColumn('status')" class="cell-pop" title="点击修改状态" @click.stop="openMenu('state', item.row.key, $event)">
          <button
            class="sub-state"
            :style="stateStyle(item.row.sub)"
            :aria-label="`修改子任务 ${item.row.sub.text} 的状态`"
            aria-haspopup="menu"
            :aria-expanded="menu?.kind === 'state' && menu.subId === item.row.key"
          >{{ subtaskState(item.row.sub) }}</button>
        </td>
        <td v-if="hasColumn('owner')" class="cell-pop" :title="member(item.row.sub.owner) ? `负责人：${member(item.row.sub.owner)!.name}，点击修改` : '点击设置负责人'" @click.stop="openMenu('owner', item.row.key, $event)">
          <button class="member-trigger" :aria-label="`修改子任务 ${item.row.sub.text} 的负责人`" aria-haspopup="menu">
            <template v-if="member(item.row.sub.owner)">
              <span class="sub-av" :style="{ background: member(item.row.sub.owner)!.color }">{{ member(item.row.sub.owner)!.initial }}</span><span class="member-name">{{ member(item.row.sub.owner)!.name }}</span>
            </template>
            <span v-else class="faint">—</span>
          </button>
        </td>
        <td v-if="hasColumn('creator')" :title="member(item.row.sub.createdBy) ? `创建者：${member(item.row.sub.createdBy)!.name}` : '历史子任务没有创建者记录'">
          <span v-if="member(item.row.sub.createdBy)" class="member-trigger">
            <span class="sub-av" :style="{ background: member(item.row.sub.createdBy)!.color }">{{ member(item.row.sub.createdBy)!.initial }}</span><span class="member-name">{{ member(item.row.sub.createdBy)!.name }}</span>
          </span>
          <span v-else class="faint">—</span>
        </td>
        <td v-if="hasColumn('created')" class="wao-tabular created-date">
          <span v-if="createdDate(item.row.sub)">{{ createdDate(item.row.sub) }}</span><span v-else class="faint">—</span>
        </td>
        <td v-if="hasColumn('start')"><span class="faint">—</span></td>
        <td v-if="hasColumn('due')" @click.stop>
          <input class="cell-input date wao-tabular" :class="{ empty: !item.row.sub.due }" type="date" :value="item.row.sub.due || ''" :aria-label="`子任务 ${item.row.sub.text} 的截止日期`" @change="setDue(item.row.key, $event)" />
        </td>
      </tr>
    </template>
    <tr v-if="data.taskSaveState(taskId) === 'error'" class="sub-add-row">
      <td v-if="hasColumn('number')"></td>
      <td :colspan="(columns?.length ?? 10) - Number(hasColumn('number'))">
        <small class="error" role="alert"
          >保存失败{{ data.taskSaveError(taskId) ? `：${data.taskSaveError(taskId)}` : '' }}，草稿已保留<button v-if="data.taskConflicts[taskId]" type="button" @click="ui.openTask(taskId)">查看并处理冲突</button><button v-else type="button" @click="data.flushTask(taskId)">重试保存</button></small
        >
      </td>
    </tr>
    <CellMenu v-if="menu" :anchor="menu.anchor" :min-width="menu.kind === 'owner' ? 120 : 110" @close="menu = null">
      <template v-if="menu.kind === 'state'">
        <button v-for="state in SUBTASK_STATES" :key="state" role="menuitemradio" :aria-checked="!!menuSub && subtaskState(menuSub) === state" :class="{ on: menuSub && subtaskState(menuSub) === state }" @click="pickState(state)">
          <span class="sub-state" :style="{ color: SUBTASK_STATE_COLOR[state], borderColor: SUBTASK_STATE_COLOR[state] }">{{ state }}</span>
          <i v-if="menuSub && subtaskState(menuSub) === state" class="ri-check-line"></i>
        </button>
      </template>
      <template v-else-if="menu.kind === 'priority'">
        <button v-for="pr in priorities" :key="pr" role="menuitemradio" :aria-checked="menuSub?.pr === pr" @click="pickPriority(pr)"><span class="sub-priority" :style="{ background: priorityMeta(pr).bg, color: priorityMeta(pr).color }">{{ pr }}</span><i v-if="menuSub?.pr === pr" class="ri-check-line"></i></button>
      </template>
      <template v-else>
        <button v-for="m in session.members" :key="m.id" role="menuitemradio" :aria-checked="menuSub?.owner === m.id" :class="{ on: menuSub?.owner === m.id }" @click="pickOwner(m.id)">
          <span class="member-trigger"><span class="sub-av" :style="{ background: m.color }">{{ m.initial }}</span>{{ m.name }}</span>
          <i v-if="menuSub?.owner === m.id" class="ri-check-line"></i>
        </button>
      </template>
    </CellMenu>
  </tbody>
  <section
    v-else-if="task"
    class="inline-subs"
    aria-label="子任务"
    @click.stop
    @dragstart.stop @dragover.prevent @drop.prevent.stop :inert="data.movingSubtasks"
  >
    <TaskOrderStatus v-if="rows.length > 1" :scope="orderScope" />
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
      class="inline-sub" :class="{ 'is-done': sub.done, 'order-before': orderingOver?.id === subId && !orderingOver.after, 'order-after': orderingOver?.id === subId && orderingOver.after }" :style="{ paddingLeft: `${8 + depth * 20}px` }" draggable="true" @dragstart.stop="drag($event, subId)" @dragover.prevent.stop="dragOver($event, subId)" @dragleave="orderingOver = null" @dragend="draggingSub = null; orderingOver = null" @drop.prevent.stop="drop($event, subId)"
    >
      <i class="ri-draggable grip" title="拖拽调整个人顺序"></i>
      <WorkItemIcon kind="subtask" />
      <span class="number wao-tabular">{{ sub.number ?? '…' }}</span>
      <button class="sub-open" :class="{ done: sub.done }" :title="sub.text" @click="ui.openTask(taskId, subId)">{{ sub.text }}</button>
      <i v-if="sub.desc || sub.images?.length" class="ri-file-text-line flag" title="有描述 / 图片"></i>
      <span v-if="sub.due" class="due wao-tabular">{{ sub.due.slice(5) }}</span>
      <span v-if="member(sub.owner)" class="sub-av" :title="member(sub.owner)!.name" :style="{ background: member(sub.owner)!.color }">{{ member(sub.owner)!.initial }}</span>
      <button
        class="sub-state"
        :style="stateStyle(sub)"
        :aria-label="`修改子任务 ${sub.text} 的状态`"
        aria-haspopup="menu"
        @click="openMenu('state', subId, $event)"
      >{{ subtaskState(sub) }}</button>
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
      >保存失败{{ data.taskSaveError(taskId) ? `：${data.taskSaveError(taskId)}` : '' }}，草稿已保留<button v-if="data.taskConflicts[taskId]" type="button" @click="ui.openTask(taskId)">查看并处理冲突</button><button v-else type="button" @click="data.flushTask(taskId)">重试保存</button></small
    >
    <small v-if="localError" class="error" role="alert">{{ localError }}</small>
    <CellMenu v-if="menu?.kind === 'state'" :anchor="menu.anchor" @close="menu = null">
      <button v-for="state in SUBTASK_STATES" :key="state" role="menuitemradio" :class="{ on: menuSub && subtaskState(menuSub) === state }" @click="pickState(state)">
        <span class="sub-state" :style="{ color: SUBTASK_STATE_COLOR[state], borderColor: SUBTASK_STATE_COLOR[state] }">{{ state }}</span>
        <i v-if="menuSub && subtaskState(menuSub) === state" class="ri-check-line"></i>
      </button>
    </CellMenu>
  </section>
</template>
<style scoped>
.order-before { box-shadow: inset 0 2px var(--wao-primary); }
.order-after { box-shadow: inset 0 -2px var(--wao-primary); }
.sub-priority { border: 0; border-radius: 4px; padding: 3px 6px; font-size: 11px; cursor: pointer; }
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
  color: var(--wao-primary-text);
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
  color: var(--wao-primary-text);
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
.sub-title .sub-title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
  padding: 0;
  color: var(--wao-text);
}
.sub-title .sub-title-text:hover {
  color: var(--wao-primary-text);
}
.sub-title .sub-title-text.done {
  color: var(--wao-text-faint);
}
.row-tools {
  display: inline-flex;
  gap: 2px;
  flex-shrink: 0;
  opacity: 0;
}
.inline-sub-row:hover .row-tools,
.row-tools:focus-within {
  opacity: 1;
}
.row-tool {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  color: var(--wao-text-weak);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  padding: 0;
}
.row-tool:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary-text);
}
.rename-input {
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
.table-subs td.cell-pop {
  cursor: pointer;
}
.member-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 0;
  color: var(--wao-text-2);
}
.member-trigger .member-name {
  overflow: hidden;
  text-overflow: ellipsis;
}
.created-date {
  color: var(--wao-text-weak);
  font-size: 12px;
}
.tree-toggle {
  width: 16px;
  height: 16px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--wao-text-weak);
  font-size: 16px;
  flex-shrink: 0;
}
.tree-spacer {
  width: 16px;
  flex-shrink: 0;
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
.cell-input.date.empty:not(:hover):not(:focus) {
  color: transparent;
}
.cell-input:hover,
.cell-input:focus {
  border-color: var(--wao-line-control);
  outline: none;
}
.table-subs form {
  display: flex;
  align-items: center;
  gap: 8px;
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
  color: var(--wao-primary-text);
}
.table-subs .sub-add-row td {
  height: 30px;
  color: var(--wao-text-faint);
}
</style>
