<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
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
import type { TaskStatus } from '@/types'

const route = useRoute()
const router = useRouter()
const data = useDataStore()
const ui = useUiStore()
const tabs = useTabsStore()
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
  return q === 'list' || q === 'board' ? q : ui.taskView
})

function setTab(t: string) {
  router.replace({ query: { ...route.query, tab: t === '任务' ? undefined : t } })
}
function setView(v: TaskView) {
  ui.setTaskView(v)
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
  if (t && t.status !== status) data.patchTask(id, { status }, `你将状态改为「${status}」`)
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

const allTasks = computed(() => ptasks.value.map(decoTask))
const openTasks = computed(() => allTasks.value.filter((t) => t.status !== '已完成' || isSettling(t.id)))
const doneTasks = computed(() => allTasks.value.filter((t) => t.status === '已完成' && !isSettling(t.id)))
// 看板没有勾选框，状态只能靠拖拽改变，所以不需要缓冲；「已完成」列默认收起，点列头展开。
const board = computed(() =>
  TASK_STATUSES.map((st) => ({
    label: st,
    dot: TASK_STATUS_DOTS[st],
    collapsible: st === '已完成',
    // 新任务一律落在「待办」，所以加号只出现在这一列——省掉一次把鼠标甩到右上角的来回。
    addable: st === '待办',
    items: ptasks.value.filter((t) => t.status === st).map(decoTask),
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
    created.forEach((run) => data.patchTask(run.taskId, { status: 'AI 进行中' }, '你已将任务交给 AI Run'))
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
          <button v-for="v in ([['列表', 'ri-list-check-2', 'list'], ['看板', 'ri-layout-column-line', 'board']] as const)" :key="v[2]" class="seg-btn icon" :class="{ on: pdTaskView === v[2] }" @click="setView(v[2])">
            <i :class="v[1]"></i>{{ v[0] }}
          </button>
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
  width: 262px;
  flex-shrink: 0;
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
</style>
