<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore, type TaskView } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'
import { useDeco } from '@/composables/deco'
import { useCompletionSettle } from '@/composables/completion'
import { useNow } from '@/composables/now'
import { absoluteTime, relativeTime } from '@/utils/time'
import { fmtDate, isOpenStatus, TASK_STATUSES, TASK_STATUS_DOTS } from '@/utils/domain'
import { toast } from '@/composables/toast'
import { createAiRuns } from '@/api/ai'
import ProjectSettingsModal from '@/components/overlays/ProjectSettingsModal.vue'
import { useFavoritesStore } from '@/stores/favorites'
import { MAX_TASK_GROUPS } from '@/types'
import type { TaskStatus } from '@/types'

const route = useRoute()
const router = useRouter()
const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const tabs = useTabsStore()
const favorites = useFavoritesStore()
const { decoProj, decoTask } = useDeco()

// 动态时间随时间推移自己变新
const now = useNow()
const showTime = (t: string) => relativeTime(t, now.value)

const pid = computed(() => String(route.params.id))
const project = computed(() => data.projectById(pid.value))
// 「总览」标签页已下线：里程碑 / 趋势 / 阻塞项 / 成员权限都是没有落地的演示卡片，
// 进项目直接看任务。老链接上的 ?tab=总览 也归到任务，不至于打开一片空白。
const projTab = computed(() => {
  const t = route.query.tab as string | undefined
  return t && t !== '总览' ? t : '任务'
})
// URL 上写了 view 就听 URL 的（分享出去的链接要能指定视图）；没写时用记住的偏好。
const pdTaskView = computed<TaskView>(() => {
  const q = route.query.view
  return q === 'list' || q === 'board' || q === 'group' ? q : ui.taskViewOf(pid.value)
})

function setTab(t: string) {
  router.replace({ query: { ...route.query, tab: t === '任务' ? undefined : t } })
}
function setView(v: TaskView) {
  ui.setTaskView(pid.value, v)
  router.replace({ query: { ...route.query, view: v } })
}

// 项目名解析 / 变更后回填页签标签（修复刷新后页签仍显示「项目 <id>」占位）。
watch(
  () => project.value?.name,
  (name) => {
    if (name) tabs.setLabel(`proj:${pid.value}`, name)
  },
  { immediate: true },
)

const settingsOpen = ref(false)
const selectedTaskIds = ref<string[]>([])
const aiBatchStarting = ref(false)
const creatorFilterOpen = ref(false)
/** 创建人筛选：按项目记在本地存储里，下次进来还是上次选的（此前刷新即丢）。 */
const creatorFilter = computed<string | null>({
  get: () => ui.creatorFilterOf(pid.value) || null,
  set: (v) => ui.setCreatorFilter(pid.value, v ?? ''),
})
function onProjectDeleted() {
  settingsOpen.value = false
  const next = tabs.close(`proj:${pid.value}`)
  router.push(next?.path ?? '/projects')
  toast('项目已删除', 'ri-delete-bin-line')
}

// ---- 拖拽：看板改状态 / 列表排序 ----
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
function onDropReorder(overId: string) {
  const id = dragId.value
  dragId.value = null
  if (id) data.reorderTask(id, overId)
}

const pd = computed(() => (project.value ? decoProj(project.value) : null))
const ptasks = computed(() => (project.value ? data.tasksByProject(project.value.id) : []))
const kb = computed(() => data.kbs.find((k) => k.project === pid.value))

const linkedDocs = computed(() =>
  (project.value?.docs ?? []).map((id) => {
    const d = data.docById(id)
    return { id, title: d?.title ?? '', updated: d?.updated ?? '' }
  }),
)
// ---- 已完成任务的收起 / 缓冲 ----
// 默认收起已完成，点击展开；刚勾完成的任务先原地停留 3 秒再归档，给操作一个反应时间。
const showDone = ref(false)
const { toggle: toggleTask, isSettling } = useCompletionSettle()

const creatorOptions = computed(() => {
  const ids = [...new Set(ptasks.value.map((task) => task.createdBy).filter((id): id is string => Boolean(id)))]
  return ids.map((id) => ({ id, name: session.members.find((member) => member.id === id)?.name ?? id }))
})
/** 记住的创建人已经不在当前任务列表里（任务删光了）时视为未筛选，避免整页空白。 */
const effectiveCreator = computed(() =>
  creatorFilter.value && creatorOptions.value.some((c) => c.id === creatorFilter.value) ? creatorFilter.value : null,
)
const activeCreatorName = computed(() =>
  effectiveCreator.value ? creatorOptions.value.find((creator) => creator.id === effectiveCreator.value)?.name ?? effectiveCreator.value : '创建人',
)
const allTasks = computed(() => ptasks.value
  .filter((task) => !effectiveCreator.value || task.createdBy === effectiveCreator.value)
  .map(decoTask))
const openTasks = computed(() => allTasks.value.filter((t) => t.status !== '已完成' || isSettling(t.id)))
/** 已完成倒序：最新完成的排最前；老数据没有完成时间时按 id（含创建时间戳）倒序兜底。 */
const doneDesc = (a: { completedAt?: string | null; id: string }, b: { completedAt?: string | null; id: string }) =>
  (b.completedAt ?? '').localeCompare(a.completedAt ?? '') || b.id.localeCompare(a.id)
const doneTasks = computed(() =>
  allTasks.value.filter((t) => t.status === '已完成' && !isSettling(t.id)).sort(doneDesc))
// 看板没有勾选框，状态只能靠拖拽改变，所以不需要缓冲；「已完成」列默认收起，点列头展开。
const board = computed(() =>
  TASK_STATUSES.map((st) => ({
    label: st,
    dot: TASK_STATUS_DOTS[st],
    collapsible: st === '已完成',
    // 新任务一律落在「待办」，所以加号只出现在这一列——省掉一次把鼠标甩到右上角的来回。
    addable: st === '待办',
    items: st === '已完成'
      ? allTasks.value.filter((t) => t.status === st).sort(doneDesc)
      : allTasks.value.filter((t) => t.status === st),
  })),
)
const pdTabs = computed(() => {
  // 任务角标只数未完成的：一个跑了半年的项目里「25」几乎全是已完成，看不出还剩多少事。
  const openCount = ptasks.value.filter((t) => isOpenStatus(t.status)).length
  const counts: Record<string, number> = { 任务: openCount, 文档: linkedDocs.value.length, 文件: project.value?.files.length ?? 0 }
  return (['任务', '文档', '文件', '动态'] as const).map((t) => ({
    label: t, count: counts[t], showCount: ['任务', '文档', '文件'].includes(t), on: projTab.value === t,
    title: t === '任务' ? `${openCount} 个未完成 / 共 ${ptasks.value.length} 个` : '',
  }))
})

function openTask(id: string) {
  ui.openTask(id)
}
function toggleSelection(id: string) {
  selectedTaskIds.value = selectedTaskIds.value.includes(id)
    ? selectedTaskIds.value.filter((item) => item !== id)
    : [...selectedTaskIds.value, id]
}
function selectAllOpen() {
  selectedTaskIds.value = selectedTaskIds.value.length === openTasks.value.length
    ? []
    : openTasks.value.map((task) => task.id)
}
async function runSelectedWithAi() {
  if (!selectedTaskIds.value.length || aiBatchStarting.value) return
  aiBatchStarting.value = true
  try {
    const created = await createAiRuns(selectedTaskIds.value)
    created.forEach((run) => data.patchTask(run.taskId, { status: 'AI 进行中' }, '已将任务交给 AI Run'))
    selectedTaskIds.value = []
    toast(`已将 ${created.length} 个任务加入 AI Run，可在首页查看`, 'ri-robot-2-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '批量 AI Run 启动失败', 'ri-error-warning-line')
  } finally {
    aiBatchStarting.value = false
  }
}
function openDoc(id: string) {
  const k = data.kbs.find((kk) => kk.folders.some((f) => f.docs.includes(id)))
  if (k) router.push({ path: `/kb/${k.id}`, query: { doc: id } })
  else router.push('/knowledge')
}
function openKb() {
  if (kb.value) router.push(`/kb/${kb.value.id}`)
}
function comingSoon(msg: string) {
  toast(msg)
}

// ---- 分组视图 ----
// 一个项目里同时压着好几个 domain 的事：按状态切的看板帮不上忙，得按「这是哪一摊事」分。
// 分组里只关心做没做完，所以行上不再堆状态 / 优先级 / 负责人这些标签。

/** 分组列表；项目还没建过分组时是一个「未分组」兜底（不落库，改名或加组才写透）。 */
const groups = computed(() => data.taskGroupsOf(pid.value))
const canAddGroup = computed(() => groups.value.length < MAX_TASK_GROUPS)
/** 分组视图里正在改名的分组 id。 */
const renamingGroup = ref<string | null>(null)
const renameText = ref('')
/** 分组视图里正在快速新建任务的分组 id，以及输入框内容。 */
const quickAddGroup = ref<string | null>(null)
const quickAddText = ref('')
// v-for 里的模板 ref 是数组（一次只会渲染一个输入框，取第一个即可）
const quickAddInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)
const renameInput = ref<HTMLInputElement | HTMLInputElement[] | null>(null)
const firstOf = (r: HTMLInputElement | HTMLInputElement[] | null) => (Array.isArray(r) ? r[0] : r)
/** 每个分组的已完成是否展开（分组之间互不影响）。 */
const openedDoneGroups = ref<string[]>([])

/** 任务归属分组：没归过组、或归到已删除的分组，都算落在第一个分组里。 */
function groupIdOf(task: { groupId?: string | null }): string {
  const id = task.groupId
  return id && groups.value.some((g) => g.id === id) ? id : groups.value[0]!.id
}

/** 分组视图的数据：每个分组一段，未完成在前、已完成收起在后。 */
const groupSections = computed(() =>
  groups.value.map((g) => {
    const mine = allTasks.value.filter((t) => groupIdOf(t) === g.id)
    return {
      ...g,
      open: mine.filter((t) => t.status !== '已完成'),
      done: mine.filter((t) => t.status === '已完成').sort(doneDesc),
      total: mine.length,
    }
  }),
)

async function persistGroups(next: { id: string; name: string }[], failHint: string) {
  if (!(await data.setTaskGroups(pid.value, next))) toast(failHint, 'ri-error-warning-line')
}
async function addGroup() {
  if (!canAddGroup.value) return toast(`最多 ${MAX_TASK_GROUPS} 个分组`, 'ri-information-line')
  const created = { id: `tg${Date.now()}`, name: `分组 ${groups.value.length + 1}` }
  await persistGroups([...groups.value, created], '分组创建失败，请重试')
  startRename(created.id, created.name)
}
function startRename(id: string, name: string) {
  renamingGroup.value = id
  renameText.value = name
  nextTick(() => firstOf(renameInput.value)?.select())
}
async function commitRename() {
  const id = renamingGroup.value
  const name = renameText.value.trim()
  renamingGroup.value = null
  if (!id || !name) return
  await persistGroups(groups.value.map((g) => (g.id === id ? { ...g, name } : g)), '分组改名失败，请重试')
}
/** 删掉一个分组：里面的任务不删，落回第一个分组。最后一个分组不给删，否则没地方放任务。 */
async function removeGroup(id: string) {
  if (groups.value.length <= 1) return toast('至少保留一个分组', 'ri-information-line')
  const rest = groups.value.filter((g) => g.id !== id)
  ptasks.value.filter((t) => t.groupId === id).forEach((t) => data.patchTask(t.id, { groupId: rest[0]!.id }))
  await persistGroups(rest, '分组删除失败，请重试')
  toast('分组已删除，里面的任务已归到第一个分组', 'ri-inbox-unarchive-line')
}
function toggleGroupDone(id: string) {
  openedDoneGroups.value = openedDoneGroups.value.includes(id)
    ? openedDoneGroups.value.filter((x) => x !== id)
    : [...openedDoneGroups.value, id]
}
/** 分组头上的「+」：就地展开一行输入框，回车即建，手机上不用再进弹窗。 */
function startQuickAdd(groupId: string) {
  quickAddGroup.value = groupId
  quickAddText.value = ''
  nextTick(() => firstOf(quickAddInput.value)?.focus())
}
function submitQuickAdd(groupId: string) {
  const title = quickAddText.value.trim()
  if (!title) return
  void data.addTask({
    title, project: pid.value, pr: 'P2', owner: session.currentUser.id,
    due: data.today, desc: '', images: [], groupId,
  })
  quickAddText.value = ''
  toast(`任务「${title}」已创建`, 'ri-checkbox-circle-fill')
}
function onDropGroup(groupId: string, overId?: string) {
  const id = dragId.value
  dragId.value = null
  if (!id) return
  data.moveTaskToGroup(id, groupId)
  // 同组内落到某条任务上就是排序；空白处只改变分组，保持该组已有顺序。
  if (overId && overId !== id) data.reorderTask(id, overId)
}
</script>

<template>
  <div v-if="!pd" class="missing">项目不存在或已被移除。</div>
  <div v-else class="detail">
    <button class="back" @click="router.push('/projects')"><i class="ri-arrow-left-line"></i>全部项目</button>

    <div class="phead">
      <div class="phead-icon" :style="{ background: pd.areaColor }"><i :class="pd.icon || 'ri-stack-line'"></i></div>
      <div class="phead-name-wrap">
        <div class="phead-name-row">
          <div class="phead-name">{{ pd.name }}</div>
          <span class="phead-status" :style="{ background: pd.stBg, color: pd.stColor }">{{ pd.status }}</span>
        </div>
        <div class="phead-sub">{{ pd.areaName }} · {{ fmtDate(pd.start) }} – {{ fmtDate(pd.due) }}</div>
      </div>
      <div class="spacer"></div>
      <div class="phead-avs">
        <span v-for="(mv, i) in pd.memberAvs" :key="i" class="phead-av" :style="{ background: mv.color }">{{ mv.initial }}</span>
      </div>
      <button
        class="btn-icon"
        :class="{ faved: favorites.isFav('project', pid) }"
        :title="favorites.isFav('project', pid) ? '取消收藏' : '收藏到左侧菜单'"
        @click="favorites.toggle('project', pid, pd.name)"
      >
        <i :class="favorites.isFav('project', pid) ? 'ri-star-fill' : 'ri-star-line'"></i>
      </button>
      <button class="btn-icon" title="项目设置" @click="settingsOpen = true"><i class="ri-settings-3-line"></i></button>
      <button class="btn-primary sm" @click="ui.openModal('task', pid)"><i class="ri-add-line"></i>新建任务</button>
    </div>

    <div class="ptabs">
      <button v-for="tb in pdTabs" :key="tb.label" class="ptab" :class="{ on: tb.on }" :title="tb.title" @click="setTab(tb.label)">
        {{ tb.label }}<span v-if="tb.showCount" class="ptab-count wao-tabular">{{ tb.count }}</span>
      </button>
    </div>

    <!-- 任务 -->
    <div v-if="projTab === '任务'">
      <div class="tab-toolbar">
        <div class="seg">
          <button v-for="v in ([['列表', 'ri-list-check-2', 'list'], ['看板', 'ri-layout-column-line', 'board'], ['分组', 'ri-folder-2-line', 'group']] as const)" :key="v[2]" class="seg-btn icon" :class="{ on: pdTaskView === v[2] }" @click="setView(v[2])">
            <i :class="v[1]"></i>{{ v[0] }}
          </button>
        </div>
        <div class="creator-filter">
          <button
            class="creator-filter-btn"
            :class="{ on: creatorFilter }"
            :disabled="!creatorOptions.length"
            :title="creatorOptions.length ? '按创建人筛选任务' : '历史任务没有可靠的创建人记录'"
            @click="creatorFilterOpen = !creatorFilterOpen"
          >
            <i class="ri-user-search-line"></i>{{ activeCreatorName }}<i class="ri-arrow-down-s-line"></i>
          </button>
          <div v-if="creatorFilterOpen" class="creator-filter-menu">
            <button :class="{ on: !creatorFilter }" @click="creatorFilter = null; creatorFilterOpen = false">全部创建人</button>
            <button
              v-for="creator in creatorOptions"
              :key="creator.id"
              :class="{ on: creatorFilter === creator.id }"
              @click="creatorFilter = creator.id; creatorFilterOpen = false"
            >{{ creator.name }}</button>
          </div>
        </div>
        <div class="spacer"></div>
        <button v-if="pdTaskView === 'list' && openTasks.length" class="select-all" @click="selectAllOpen">
          <i :class="selectedTaskIds.length === openTasks.length ? 'ri-checkbox-fill' : 'ri-checkbox-blank-line'"></i>
          {{ selectedTaskIds.length === openTasks.length ? '取消全选' : '选择任务' }}
        </button>
        <button
          v-if="selectedTaskIds.length"
          class="batch-ai"
          :disabled="aiBatchStarting"
          @click="runSelectedWithAi"
        >
          <i :class="aiBatchStarting ? 'ri-loader-4-line spin' : 'ri-robot-2-line'"></i>
          AI run run · {{ selectedTaskIds.length }}
        </button>
      </div>
      <div v-if="pdTaskView === 'list'" class="task-list-card">
        <div
          v-for="t in openTasks"
          :key="t.id"
          class="trow"
          :class="{ dragging: dragId === t.id, settling: isSettling(t.id) }"
          draggable="true"
          @click="openTask(t.id)"
          @dragstart="onDragStart(t.id)"
          @dragover.prevent
          @drop="onDropReorder(t.id)"
        >
          <i class="drag-grip ri-draggable" title="拖拽排序"></i>
          <button
            class="select-dot"
            :class="{ on: selectedTaskIds.includes(t.id) }"
            title="选择后批量交给 AI"
            @click.stop="toggleSelection(t.id)"
          >
            <i class="ri-robot-2-line"></i>
          </button>
          <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" @click.stop="toggleTask(t.id)"><i class="ri-check-line" :style="{ opacity: t.checkOp }"></i></button>
          <span class="trow-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</span>
          <span v-if="t.waitBadge" class="wait-badge">{{ t.waitBadge }}</span>
          <span class="mini" :style="{ background: t.stBg, color: t.stColor }">{{ t.status }}</span>
          <span class="mini" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
          <span class="owner-av" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
          <span class="trow-due" :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
        </div>

        <!-- 已完成默认收起 -->
        <button v-if="doneTasks.length" class="done-toggle" @click="showDone = !showDone">
          <i :class="showDone ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
          已完成<span class="done-count wao-tabular">{{ doneTasks.length }}</span>
          <span class="done-hint">{{ showDone ? '点击收起' : '点击展开' }}</span>
        </button>
        <template v-if="showDone">
          <div v-for="t in doneTasks" :key="t.id" class="trow done" @click="openTask(t.id)">
            <i class="drag-grip ri-draggable placeholder"></i>
            <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" @click.stop="toggleTask(t.id)"><i class="ri-check-line" :style="{ opacity: t.checkOp }"></i></button>
            <span class="trow-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</span>
            <span class="mini" :style="{ background: t.stBg, color: t.stColor }">{{ t.status }}</span>
            <span class="mini" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
            <span class="owner-av" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
            <span class="trow-due" :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
          </div>
        </template>

        <div v-if="allTasks.length === 0" class="empty-inline">还没有任务——点右上角「新建任务」开始拆解</div>
        <div v-else-if="!openTasks.length && !showDone" class="empty-inline">未完成的任务都清空了 🎉</div>
      </div>

      <!-- 分组：按自己定义的 domain 分堆，组内只看做没做完 -->
      <div v-else-if="pdTaskView === 'group'" class="groups">
        <div
          v-for="g in groupSections"
          :key="g.id"
          class="group-card"
          :class="{ 'drop-hint': dragId }"
          @dragover.prevent
          @drop="onDropGroup(g.id)"
        >
          <div class="group-head">
            <input
              v-if="renamingGroup === g.id"
              ref="renameInput"
              v-model="renameText"
              class="group-name-input"
              maxlength="20"
              placeholder="分组名称"
              @keydown.enter="commitRename"
              @keydown.esc="renamingGroup = null"
              @blur="commitRename"
            />
            <button v-else class="group-name" title="点击改名" @click="startRename(g.id, g.name)">
              {{ g.name }}<i class="ri-pencil-line"></i>
            </button>
            <span class="group-count wao-tabular" :title="`${g.open.length} 个未完成 / 共 ${g.total} 个`">
              {{ g.open.length }} / {{ g.total }}
            </span>
            <span class="spacer"></span>
            <button class="group-op" title="在这个分组里新建任务" aria-label="新建任务" @click="startQuickAdd(g.id)">
              <i class="ri-add-line"></i>
            </button>
            <button
              v-if="groups.length > 1"
              class="group-op danger"
              title="删除分组（里面的任务会归到第一个分组）"
              @click="removeGroup(g.id)"
            >
              <i class="ri-delete-bin-line"></i>
            </button>
          </div>

          <div v-if="quickAddGroup === g.id" class="group-add-row">
            <i class="ri-add-line"></i>
            <input
              ref="quickAddInput"
              v-model="quickAddText"
              placeholder="任务标题，回车创建，连着敲可以一次录好几条"
              @keydown.enter="submitQuickAdd(g.id)"
              @keydown.esc="quickAddGroup = null"
            />
            <button class="group-add-done" @click="quickAddGroup = null">完成</button>
          </div>

          <div
            v-for="t in g.open"
            :key="t.id"
            class="grow"
            :class="{ dragging: dragId === t.id }"
            draggable="true"
            @click="openTask(t.id)"
            @dragstart="onDragStart(t.id)"
            @dragover.prevent.stop
            @drop.stop="onDropGroup(g.id, t.id)"
          >
            <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" @click.stop="toggleTask(t.id)">
              <i class="ri-check-line" :style="{ opacity: t.checkOp }"></i>
            </button>
            <span class="grow-title">{{ t.title }}</span>
            <i class="ri-arrow-right-s-line grow-chev"></i>
          </div>

          <button v-if="g.done.length" class="group-done-toggle" @click="toggleGroupDone(g.id)">
            <i :class="openedDoneGroups.includes(g.id) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
            已完成<span class="done-count wao-tabular">{{ g.done.length }}</span>
          </button>
          <template v-if="openedDoneGroups.includes(g.id)">
            <div v-for="t in g.done" :key="t.id" class="grow done" @click="openTask(t.id)">
              <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" @click.stop="toggleTask(t.id)">
                <i class="ri-check-line" :style="{ opacity: t.checkOp }"></i>
              </button>
              <span class="grow-title">{{ t.title }}</span>
              <i class="ri-arrow-right-s-line grow-chev"></i>
            </div>
          </template>

          <div v-if="!g.total" class="group-empty">点右上角的「+」往这里加第一条任务</div>
        </div>

        <button v-if="canAddGroup" class="group-add" @click="addGroup">
          <i class="ri-add-line"></i>新建分组<em>还能建 {{ MAX_TASK_GROUPS - groups.length }} 个</em>
        </button>
        <div v-else class="group-full">已经有 {{ MAX_TASK_GROUPS }} 个分组了——再多就一屏扫不完了</div>
      </div>

      <div v-else class="board">
        <div
          v-for="col in board"
          :key="col.label"
          class="board-col"
          :class="{ 'drop-hint': dragId }"
          @dragover.prevent
          @drop="onDropStatus(col.label)"
        >
          <div class="board-col-head" :class="{ clickable: col.collapsible }" @click="col.collapsible && (showDone = !showDone)">
            <span class="board-dot" :style="{ background: col.dot }"></span>
            <span class="board-col-label">{{ col.label }}</span>
            <span class="board-col-count wao-tabular">{{ col.items.length }}</span>
            <button
              v-if="col.addable"
              class="board-col-add"
              title="在这里新建任务"
              aria-label="新建任务"
              @click.stop="ui.openModal('task', pid)"
            >
              <i class="ri-add-line"></i>
            </button>
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
              <button
                class="select-dot board-select"
                :class="{ on: selectedTaskIds.includes(t.id) }"
                title="选择后批量交给 AI"
                @click.stop="toggleSelection(t.id)"
              >
                <i class="ri-robot-2-line"></i>
              </button>
              <div class="bc-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</div>
              <div class="bc-foot">
                <span class="mini" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
                <span class="bc-due" :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
                <span class="spacer"></span>
                <span class="owner-av small" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
              </div>
            </div>
            <div v-if="!col.items.length" class="board-empty">拖到此处标记为「{{ col.label }}」</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 文档 -->
    <div v-else-if="projTab === '文档'">
      <div class="tab-toolbar gap">
        <button v-if="kb" class="kb-chip" @click="openKb"><i class="ri-book-2-line"></i>关联知识库 · {{ kb.name }}</button>
        <span v-else class="no-kb">该项目尚未关联知识库</span>
        <div class="spacer"></div>
        <button class="btn-primary xs" @click="comingSoon('新建文档将在知识库中创建（演示）')"><i class="ri-file-add-line"></i>新建文档</button>
      </div>
      <div class="doc-list-card">
        <div v-for="d in linkedDocs" :key="d.id" class="doc-row" @click="openDoc(d.id)">
          <i class="ri-file-text-line"></i>
          <span class="doc-title">{{ d.title }}</span>
          <span class="doc-time">{{ d.updated }}</span>
          <i class="ri-arrow-right-s-line chev"></i>
        </div>
      </div>
    </div>

    <!-- 文件 -->
    <div v-else-if="projTab === '文件'">
      <div class="file-list-card">
        <div v-for="(f, i) in pd.files" :key="i" class="doc-row" @click="comingSoon('演示：文件预览与下载在正式版本提供')">
          <i :class="f.icon"></i>
          <span class="doc-title">{{ f.name }}</span>
          <span class="doc-time">{{ f.size }}</span>
          <i class="ri-download-2-line chev"></i>
        </div>
      </div>
    </div>

    <!-- 动态 -->
    <div v-else class="feed-card">
      <div v-for="(ac, i) in pd.activity" :key="i" class="feed-row bordered">
        <i :class="ac.icon" :style="{ color: ac.color }"></i>
        <div class="feed-main">
          <div class="feed-text lg">{{ ac.text }}</div>
          <div class="feed-time" :title="absoluteTime(ac.time)">{{ showTime(ac.time) }}</div>
        </div>
      </div>
      <div v-if="!pd.activity.length" class="feed-empty">还没有动态——创建任务、完成任务、沉淀文档都会记录在这里。</div>
    </div>

    <ProjectSettingsModal
      v-if="settingsOpen && project"
      :project="project"
      @close="settingsOpen = false"
      @deleted="onProjectDeleted"
    />
  </div>
</template>

<style scoped>
.missing {
  padding: 60px 24px;
  text-align: center;
  color: var(--wao-text-weak);
}
.detail {
  padding: 14px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  color: #7a7d89;
  font-size: 12.5px;
  cursor: pointer;
  padding: 4px 6px;
  margin-left: -6px;
  border-radius: 6px;
}
.back:hover {
  background: var(--wao-divider);
  color: var(--wao-text-2);
}
.back i {
  font-size: 14px;
}

.phead {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.phead-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  opacity: 0.92;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 19px;
}
.phead-name-wrap {
  min-width: 0;
}
.phead-name-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.phead-name {
  font-size: 20px;
  font-weight: 700;
}
.phead-status {
  font-size: 11.5px;
  border-radius: 999px;
  padding: 2px 9px;
}
.phead-sub {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--wao-text-weak);
}
.phead-sub i {
  font-size: 12px;
}
.phead-avs {
  display: flex;
  margin-right: 2px;
}
.phead-av {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #fff;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--wao-bg-page);
  margin-left: -7px;
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
  font-size: 14px;
}
.btn-primary.sm {
  height: 32px;
  padding: 0 13px;
  font-size: 12.5px;
}
.btn-primary.xs {
  height: 30px;
  padding: 0 12px;
  font-size: 12.5px;
}
.btn-icon {
  width: 32px;
  height: 32px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 8px;
  color: var(--wao-text-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.btn-icon:hover {
  background: #f4f5f8;
  color: var(--wao-text-2);
}
.btn-icon i {
  font-size: 15px;
}
.btn-icon.faved,
.btn-icon.faved:hover {
  color: #b9821f;
  border-color: #e5d3a8;
  background: #fdf6e7;
}

.ptabs {
  margin-top: 16px;
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--wao-line);
}
.ptab {
  height: 36px;
  padding: 0 13px;
  border: none;
  background: none;
  border-bottom: 2px solid transparent;
  color: #7a7d89;
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-bottom: -1px;
}
.ptab.on {
  color: var(--wao-text);
  font-weight: 600;
  border-bottom-color: var(--wao-primary);
}
.ptab-count {
  font-size: 11px;
  color: var(--wao-text-weak);
}
.check {
  width: 17px;
  height: 17px;
  border-radius: 5px;
  border: 1.5px solid #c9cdda;
  background: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
}
.select-dot {
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #f0f1f4;
  color: #6b6e79;
  cursor: pointer;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.select-dot:hover {
  border-color: var(--wao-primary);
}
.select-dot.on {
  color: #fff;
  background: var(--wao-primary);
}
.select-dot i {
  font-size: 14px;
}
.board-select {
  position: absolute;
  top: 10px;
  right: 10px;
}
.check i {
  font-size: 12px;
  color: #fff;
}
.wait-badge {
  font-size: 11px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 5px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.mini {
  font-size: 11px;
  border-radius: 5px;
  padding: 1px 6px;
  flex-shrink: 0;
}

/* 动态 */
.feed-row {
  display: flex;
  gap: 10px;
  padding: 7px 0;
}
.feed-row.bordered {
  padding: 8px 0;
  border-bottom: 1px solid #f6f6f8;
}
.feed-row > i {
  font-size: 14px;
  flex-shrink: 0;
  margin-top: 1px;
}
.feed-main {
  flex: 1;
  min-width: 0;
}
.feed-text {
  font-size: 12.5px;
  color: var(--wao-text-2);
  line-height: 1.6;
}
.feed-text.lg {
  font-size: 13px;
}
.feed-time {
  font-size: 11px;
  color: var(--wao-text-faint);
  margin-top: 1px;
}

/* 任务/文档/文件 tabs */
.tab-toolbar {
  margin-top: 14px;
  display: flex;
  align-items: center;
}
.select-all,
.batch-ai {
  height: 32px;
  padding: 0 11px;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.select-all {
  border: 1px solid var(--wao-line-control);
  background: #fff;
  color: var(--wao-text-3);
}
.batch-ai {
  border: 1px solid #bec8f5;
  background: linear-gradient(135deg, #eef1ff, #fff);
  color: var(--wao-primary-text);
  font-weight: 650;
}
.batch-ai:disabled {
  opacity: 0.6;
}
.spin {
  display: inline-block;
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.tab-toolbar.gap {
  gap: 10px;
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
  color: #7a7d89;
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
  background: #fff;
  color: var(--wao-text);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(16, 18, 28, 0.1);
}
.creator-filter {
  position: relative;
  margin-left: 8px;
}
.creator-filter-btn {
  height: 32px;
  padding: 0 9px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: #fff;
  color: var(--wao-text-3);
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.creator-filter-btn:hover:not(:disabled),
.creator-filter-btn.on {
  border-color: #c7d0f1;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.creator-filter-btn:disabled { cursor: not-allowed; opacity: 0.55; }
.creator-filter-menu {
  position: absolute;
  z-index: 5;
  top: calc(100% + 5px);
  left: 0;
  min-width: 132px;
  padding: 4px;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  box-shadow: var(--wao-shadow-modal);
}
.creator-filter-menu button {
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--wao-text-2);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.creator-filter-menu button:hover,
.creator-filter-menu button.on { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.task-list-card,
.doc-list-card,
.file-list-card {
  margin-top: 10px;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 6px 8px;
}
.trow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
}
.trow:hover {
  background: var(--wao-bg-hover);
}
.trow.dragging {
  opacity: 0.5;
}
/* 刚勾完成、还在 3 秒缓冲里的任务：淡出但不移位，给操作一个反应时间 */
.trow.settling {
  opacity: 0.55;
  transition: opacity 0.4s ease;
}
.trow.done {
  cursor: pointer;
}
.done-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  background: none;
  border-top: 1px solid var(--wao-divider);
  margin-top: 6px;
  padding: 10px;
  font-size: 12.5px;
  color: var(--wao-text-weak);
  cursor: pointer;
  text-align: left;
}
.done-toggle:hover {
  color: var(--wao-text-2);
}
.done-toggle > i {
  font-size: 15px;
}
.done-count {
  background: var(--wao-divider);
  border-radius: 999px;
  padding: 1px 8px;
  font-size: 11.5px;
}
.done-hint {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.drag-grip.placeholder {
  visibility: hidden;
}
.drag-grip {
  font-size: 15px;
  color: var(--wao-text-faint-2);
  cursor: grab;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.12s;
}
.trow:hover .drag-grip {
  opacity: 1;
}
.trow-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.trow-due {
  font-size: 12px;
  width: 62px;
  text-align: right;
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
.empty-inline {
  padding: 34px;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 13px;
}

.doc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-radius: 8px;
  cursor: pointer;
}
.doc-row:hover {
  background: var(--wao-bg-hover);
}
.doc-row > i:first-child {
  font-size: 15px;
  color: var(--wao-text-weak);
}
.doc-title {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text);
}
.doc-time {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.doc-row .chev {
  font-size: 14px;
  color: var(--wao-text-faint-2);
}

.kb-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--wao-line-control);
  background: #fff;
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12px;
  color: #3a4cc2;
  cursor: pointer;
}
.kb-chip:hover {
  border-color: #c7d0f1;
  background: var(--wao-primary-tint);
}
.kb-chip i {
  font-size: 13px;
}
.no-kb {
  font-size: 12px;
  color: var(--wao-text-weak);
}

/* 分组视图：纵向堆叠的卡片，宽屏两列、手机一列都成立 */
.groups {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
  align-items: start;
}
.group-card {
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 8px 10px 10px;
  transition: box-shadow 0.12s, border-color 0.12s;
}
.group-card.drop-hint {
  border-color: #c7d0f1;
  box-shadow: inset 0 0 0 1px #c7d0f1;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 2px 8px;
  border-bottom: 1px solid var(--wao-divider);
}
.group-name {
  border: none;
  background: none;
  padding: 2px 4px;
  margin-left: -4px;
  border-radius: 6px;
  font-size: 13.5px;
  font-weight: 650;
  color: var(--wao-text);
  cursor: text;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-name i {
  font-size: 12px;
  color: var(--wao-text-faint-2);
  opacity: 0;
}
.group-name:hover {
  background: var(--wao-bg-hover);
}
.group-name:hover i {
  opacity: 1;
}
.group-name-input {
  flex: 1;
  min-width: 0;
  height: 28px;
  border: 1px solid var(--wao-primary);
  border-radius: 7px;
  padding: 0 8px;
  font-size: 13.5px;
  font-weight: 650;
  outline: none;
}
.group-count {
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.group-op {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 7px;
  background: none;
  color: var(--wao-text-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.group-op i {
  font-size: 16px;
}
.group-op:hover {
  background: var(--wao-primary);
  color: #fff;
}
.group-op.danger:hover {
  background: var(--wao-danger);
}
.group-add-row {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 8px;
  padding: 0 8px;
  height: 38px;
  border: 1px solid var(--wao-primary);
  border-radius: 9px;
  background: var(--wao-primary-soft);
}
.group-add-row > i {
  font-size: 15px;
  color: var(--wao-primary);
}
.group-add-row input {
  flex: 1;
  min-width: 0;
  border: none;
  background: none;
  font-size: 13px;
  outline: none;
  color: var(--wao-text);
}
.group-add-done {
  border: none;
  background: none;
  color: var(--wao-primary-text);
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
}
.grow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 8px;
  cursor: pointer;
  min-height: 38px;
}
.grow:hover {
  background: var(--wao-bg-hover);
}
.grow.dragging {
  opacity: 0.5;
}
.grow-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--wao-text);
  word-break: break-word;
}
.grow.done .grow-title {
  color: var(--wao-text-faint);
  text-decoration: line-through;
}
.grow-chev {
  font-size: 15px;
  color: var(--wao-text-faint-2);
  flex-shrink: 0;
}
.group-done-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  background: none;
  border-top: 1px solid var(--wao-divider);
  margin-top: 6px;
  padding: 9px 8px;
  font-size: 12px;
  color: var(--wao-text-weak);
  cursor: pointer;
  text-align: left;
}
.group-done-toggle > i {
  font-size: 15px;
}
.group-empty {
  padding: 18px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.group-add {
  height: 44px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 12px;
  background: none;
  color: var(--wao-text-3);
  font-size: 13px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
}
.group-add:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
  background: var(--wao-primary-tint);
}
.group-add em {
  font-style: normal;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.group-full {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}

/* board 复用 */
.board {
  margin-top: 10px;
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
  background: #f1f2f5;
  border-radius: 12px;
  padding: 10px;
  transition: background 0.12s, box-shadow 0.12s;
}
.board-col.drop-hint {
  background: #eaedfb;
  box-shadow: inset 0 0 0 1.5px #c7d0f1;
}
.board-empty {
  border: 1px dashed #cfd3de;
  border-radius: 9px;
  padding: 14px 8px;
  text-align: center;
  font-size: 11.5px;
  color: var(--wao-text-weak);
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
.board-col-add {
  width: 22px;
  height: 22px;
  margin-left: auto;
  border: none;
  border-radius: 6px;
  background: none;
  color: var(--wao-text-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.board-col-add:hover {
  background: var(--wao-primary);
  color: #fff;
}
.board-col-add i {
  font-size: 15px;
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
  flex-direction: column;
  gap: 8px;
}
.board-card {
  position: relative;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 11px 12px;
  cursor: grab;
}
.board-card.dragging {
  opacity: 0.5;
}
.board-card:hover {
  border-color: #c9cdda;
  box-shadow: 0 2px 8px rgba(16, 18, 28, 0.06);
}
.bc-title {
  font-size: 13px;
  line-height: 1.5;
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

.feed-card {
  margin-top: 16px;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 14px 18px;
}
.feed-empty {
  padding: 28px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--wao-text-weak);
}

@media (max-width: 760px) {
  .detail { width: 100%; box-sizing: border-box; overflow-x: hidden; padding: 10px 12px 28px; }
  .back { min-height: 36px; margin-left: -4px; padding: 5px 4px; font-size: 12px; }

  /* 项目头压成一行主信息 + 一行日期，避免桌面布局在窄屏上散成四五行。 */
  .phead {
    margin-top: 2px; display: grid; grid-template-columns: 38px minmax(0, 1fr) auto auto;
    align-items: center; gap: 7px;
  }
  .phead-icon { grid-column: 1; width: 36px; height: 36px; border-radius: 10px; font-size: 17px; }
  .phead-name-wrap { grid-column: 2; }
  .phead-name-row { flex-wrap: nowrap; gap: 6px; }
  .phead-name { min-width: 0; overflow: hidden; font-size: 17px; line-height: 1.25; text-overflow: ellipsis; white-space: nowrap; }
  .phead-status { flex-shrink: 0; padding: 2px 7px; font-size: 10.5px; }
  .phead-sub { margin-top: 2px; overflow: hidden; font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
  .phead > .spacer { display: none; }
  .phead-avs { grid-column: 3; margin: 0; padding-left: 5px; }
  .phead-av { width: 24px; height: 24px; border-color: var(--wao-bg-page); }
  .phead .btn-icon { grid-column: 4; width: 38px; height: 38px; }
  .phead > .btn-primary { display: none; }

  .ptabs { margin: 13px -12px 0; padding: 0 8px; overflow-x: auto; scrollbar-width: none; }
  .ptabs::-webkit-scrollbar { display: none; }
  .ptab { min-width: max-content; height: 42px; padding: 0 12px; }

  .tab-toolbar { margin-top: 10px; flex-wrap: wrap; align-items: center; gap: 8px; }
  .tab-toolbar > .seg { order: 1; width: 100%; }
  .seg-btn { flex: 1; justify-content: center; height: 36px; }
  .creator-filter { order: 2; margin-left: 0; }
  .creator-filter-btn, .select-all, .batch-ai { min-height: 40px; }
  .tab-toolbar > .spacer { order: 3; }
  .select-all { order: 4; }
  .batch-ai { order: 5; }
  .creator-filter-menu { min-width: 160px; }

  .task-list-card, .doc-list-card, .file-list-card { margin-top: 8px; padding: 4px; border-radius: 11px; }
  .trow { min-height: 58px; align-items: center; flex-wrap: wrap; gap: 6px 8px; padding: 10px 8px; }
  .drag-grip { display: none; }
  .trow .select-dot { order: 1; width: 30px; height: 30px; }
  .trow .check { order: 2; width: 22px; height: 22px; }
  .trow-title { order: 3; flex: 1 1 calc(100% - 68px); min-width: calc(100% - 68px); white-space: normal; overflow: visible; line-height: 1.45; text-overflow: clip; }
  .trow.done .trow-title { flex-basis: calc(100% - 32px); min-width: calc(100% - 32px); }
  .trow .wait-badge { order: 4; margin-left: 60px; }
  .trow .mini { order: 5; }
  .trow .owner-av { order: 6; }
  .trow-due { order: 7; width: auto; margin-left: auto; }
  .done-toggle { min-height: 44px; }

  /* 看板改成纵向状态分区：手机不再露半张下一列，也不用横拖。 */
  .board { display: grid; grid-template-columns: 1fr; gap: 10px; overflow: visible; padding-bottom: 0; }
  .board-col { width: auto; min-width: 0; max-width: none; padding: 9px; }
  .board-col-head { min-height: 34px; padding-bottom: 7px; }
  .board-col-add { width: 32px; height: 32px; }
  .board-card { min-height: 62px; padding: 11px 12px; cursor: pointer; }
  .bc-title { padding-right: 28px; font-size: 13.5px; overflow-wrap: anywhere; }

  /* 分组 / 内容也只走单列，触控按钮统一放大。 */
  .groups { grid-template-columns: minmax(0, 1fr); }
  .group-card { min-width: 0; }
  .grow { min-height: 46px; }
  .group-op { width: 36px; height: 36px; }
  .group-add-row { min-height: 44px; height: auto; }
  .doc-row { min-height: 48px; padding: 9px 7px; }
  .doc-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .doc-time { flex-shrink: 0; max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .tab-toolbar.gap .kb-chip { max-width: 100%; min-height: 38px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
</style>
