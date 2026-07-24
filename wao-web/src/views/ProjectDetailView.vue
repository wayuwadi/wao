<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'
import { useDeco } from '@/composables/deco'
import { fmtDate } from '@/utils/domain'
import { toast } from '@/composables/toast'
import ProjectSettingsModal from '@/components/overlays/ProjectSettingsModal.vue'
import type { TaskStatus } from '@/types'

const route = useRoute()
const router = useRouter()
const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const tabs = useTabsStore()
const { decoProj, decoTask } = useDeco()

const pid = computed(() => String(route.params.id))
const project = computed(() => data.projectById(pid.value))
const projTab = computed(() => (route.query.tab as string) || '总览')
const pdTaskView = computed(() => (route.query.view as string) || 'list')

function setTab(t: string) {
  router.replace({ query: { ...route.query, tab: t === '总览' ? undefined : t } })
}
function setView(v: string) {
  router.replace({ query: { ...route.query, view: v === 'list' ? undefined : v } })
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

const BOARD_DOTS: Record<string, string> = { 待办: '#C9CDDA', 进行中: '#4358D6', 阻塞: '#D9A13B', 已完成: '#5CB88A' }

const pd = computed(() => (project.value ? decoProj(project.value) : null))
const ptasks = computed(() => (project.value ? data.tasksByProject(project.value.id) : []))
const kb = computed(() => data.kbs.find((k) => k.project === pid.value))

const healthColor = computed(() => {
  const map: Record<string, string> = { 良好: '#2C8A5B', 需要关注: '#B9821F', 风险: '#BC4141' }
  return map[project.value?.health ?? ''] ?? '#2C8A5B'
})
const remainDays = computed(() =>
  project.value ? Math.max(0, Math.round((+new Date(project.value.due) - +new Date(data.today)) / 86400000)) : 0,
)
const spark = computed(() => {
  const trend = project.value && project.value.trend.length > 1 ? project.value.trend : [0, 0]
  return trend
    .map((v, i) => `${(i * (240 / (trend.length - 1))).toFixed(1)},${(58 - v * 0.5).toFixed(1)}`)
    .join(' ')
})
const dist = computed(() => {
  const total = ptasks.value.length || 1
  return (['待办', '进行中', '阻塞', '已完成'] as const)
    .map((st) => ({ label: st, count: ptasks.value.filter((t) => t.status === st).length, color: BOARD_DOTS[st]! }))
    .filter((d) => d.count)
    .map((d) => ({ ...d, w: (d.count / total) * 100 + '%' }))
})
const milestones = computed(() =>
  (project.value?.milestones ?? []).map((m) => ({
    name: m.name, date: m.date, done: m.state === 2,
    dotBg: m.state === 2 ? '#4358D6' : '#FFFFFF',
    dotBorder: m.state === 0 ? '#D9DCE5' : '#4358D6',
    nameColor: m.state === 1 ? '#1A1C23' : '#5A5E6B',
    nameWeight: m.state === 1 ? 600 : 400,
    chip: m.state === 1 ? '进行中' : '',
  })),
)
const recentTasks = computed(() => ptasks.value.filter((t) => t.status !== '已完成').slice(0, 6).map(decoTask))
const openCount = computed(() => ptasks.value.filter((t) => t.status !== '已完成').length)
const members = computed(() =>
  (project.value?.members ?? []).map((mid) => {
    const m = session.memberById(mid)
    const isOwner = mid === project.value?.owner
    return {
      name: m.name, initial: m.initial, color: m.color,
      role: isOwner ? '负责人' : '参与',
      roleBg: isOwner ? '#E9EDFB' : '#F0F1F4',
      roleColor: isOwner ? '#3A4CC2' : '#5A5E6B',
    }
  }),
)
const linkedDocs = computed(() =>
  (project.value?.docs ?? []).map((id) => {
    const d = data.docById(id)
    return { id, title: d?.title ?? '', updated: d?.updated ?? '' }
  }),
)
const allTasks = computed(() => ptasks.value.map(decoTask))
const board = computed(() =>
  (['待办', '进行中', '阻塞', '已完成'] as const).map((st) => ({
    label: st, dot: BOARD_DOTS[st]!, items: ptasks.value.filter((t) => t.status === st).map(decoTask),
  })),
)
const pdTabs = computed(() => {
  const counts: Record<string, number> = { 任务: ptasks.value.length, 文档: linkedDocs.value.length, 文件: project.value?.files.length ?? 0 }
  return (['总览', '任务', '文档', '文件', '动态'] as const).map((t) => ({
    label: t, count: counts[t], showCount: ['任务', '文档', '文件'].includes(t), on: projTab.value === t,
  }))
})

function openTask(id: string) {
  ui.openTask(id)
}
function toggleTask(id: string) {
  data.toggleComplete(id)
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
          <span class="phead-health" :style="{ color: healthColor }"><span class="hdot" :style="{ background: healthColor }"></span>健康度 · {{ pd.health }}</span>
        </div>
        <div class="phead-sub">{{ pd.areaName }} · {{ fmtDate(pd.start) }} – {{ fmtDate(pd.due) }} · <i class="ri-lock-line"></i> {{ pd.visibility }}</div>
      </div>
      <div class="spacer"></div>
      <div class="phead-avs">
        <span v-for="(mv, i) in pd.memberAvs" :key="i" class="phead-av" :style="{ background: mv.color }">{{ mv.initial }}</span>
      </div>
      <button class="btn-ghost" @click="comingSoon('邀请链接已复制（演示）· 可设置查看 / 编辑权限')"><i class="ri-user-add-line"></i>邀请</button>
      <button class="btn-icon" title="项目设置" @click="settingsOpen = true"><i class="ri-settings-3-line"></i></button>
      <button class="btn-primary sm" @click="ui.openModal('task', pid)"><i class="ri-add-line"></i>新建任务</button>
    </div>

    <div class="ptabs">
      <button v-for="tb in pdTabs" :key="tb.label" class="ptab" :class="{ on: tb.on }" @click="setTab(tb.label)">
        {{ tb.label }}<span v-if="tb.showCount" class="ptab-count wao-tabular">{{ tb.count }}</span>
      </button>
    </div>

    <!-- 总览 -->
    <div v-if="projTab === '总览'" class="ov">
      <div class="ov-left">
        <div class="card pad">
          <div class="card-eyebrow">项目目标</div>
          <div class="goal">{{ pd.goal }}</div>
        </div>

        <div v-if="milestones.length" class="card pad">
          <div class="card-title mb8">里程碑</div>
          <div class="ms-wrap">
            <div class="ms-line"></div>
            <div v-for="(ms, i) in milestones" :key="i" class="ms-row">
              <div class="ms-dot" :style="{ background: ms.dotBg, borderColor: ms.dotBorder }">
                <i v-if="ms.done" class="ri-check-line"></i>
              </div>
              <div class="ms-name" :style="{ color: ms.nameColor, fontWeight: ms.nameWeight }">{{ ms.name }}</div>
              <span v-if="ms.chip" class="ms-chip">{{ ms.chip }}</span>
              <div class="ms-date wao-tabular">{{ ms.date }}</div>
            </div>
          </div>
        </div>

        <div class="card recent">
          <div class="recent-head">
            <div class="card-title">近期任务</div>
            <span class="count wao-tabular">{{ openCount }} 项未完成</span>
          </div>
          <div v-for="t in recentTasks" :key="t.id" class="rt-row" @click="openTask(t.id)">
            <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" @click.stop="toggleTask(t.id)"><i class="ri-check-line" :style="{ opacity: t.checkOp }"></i></button>
            <span class="rt-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</span>
            <span v-if="t.waitBadge" class="wait-badge">{{ t.waitBadge }}</span>
            <span class="mini" :style="{ background: t.stBg, color: t.stColor }">{{ t.status }}</span>
            <span class="mini" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
            <span class="rt-due" :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
          </div>
          <button class="rt-add" @click="ui.openModal('task', pid)"><i class="ri-add-line"></i>新建任务</button>
        </div>

        <div v-if="pd.blockers.length" class="blockers">
          <div class="blockers-title"><i class="ri-alert-line"></i>阻塞项</div>
          <div v-for="(bk, i) in pd.blockers" :key="i" class="bk-row">
            <div class="bk-main">
              <div class="bk-text">{{ bk.text }}</div>
              <div class="bk-sub">等待{{ bk.who }} · 已 {{ bk.days }}</div>
            </div>
            <button class="bk-nudge" @click="comingSoon(`已提醒${bk.who}（演示）`)">催办</button>
          </div>
        </div>
      </div>

      <div class="ov-right">
        <div class="card pad">
          <div class="ring-row">
            <div class="ring" :style="{ background: `conic-gradient(#4358D6 ${Math.round(pd.progress * 3.6)}deg, #E9EBF1 0)` }">
              <div class="ring-hole"><div class="ring-pct wao-tabular">{{ pd.pct }}<span>%</span></div></div>
            </div>
            <div class="ring-meta">
              <div class="rm-row"><span>开始</span><span>{{ fmtDate(pd.start) }}</span></div>
              <div class="rm-row"><span>验收</span><span>{{ fmtDate(pd.due) }}</span></div>
              <div class="rm-row"><span>剩余</span><b>{{ remainDays }} 天</b></div>
            </div>
          </div>
          <div class="sub-block">
            <div class="sub-label">进度趋势 · 近 8 周</div>
            <svg viewBox="0 0 240 62" class="spark">
              <polyline :points="spark" fill="none" stroke="#4358D6" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
            </svg>
          </div>
          <div v-if="dist.length" class="sub-block">
            <div class="sub-label">任务分布</div>
            <div class="dist-bar">
              <span v-for="(d, i) in dist" :key="i" :style="{ width: d.w, background: d.color }"></span>
            </div>
            <div class="dist-legend">
              <span v-for="(d, i) in dist" :key="i" class="dl"><span class="dl-dot" :style="{ background: d.color }"></span>{{ d.label }} {{ d.count }}</span>
            </div>
          </div>
        </div>

        <div class="card pad">
          <div class="card-title">成员与可见范围</div>
          <div v-for="(m, i) in members" :key="i" class="mem-row">
            <span class="mem-av" :style="{ background: m.color }">{{ m.initial }}</span>
            <span class="mem-name">{{ m.name }}</span>
            <span class="mem-role" :style="{ background: m.roleBg, color: m.roleColor }">{{ m.role }}</span>
          </div>
          <div class="vis-row"><i class="ri-eye-line"></i>{{ pd.visibility }}</div>
          <button class="invite-btn" @click="comingSoon('邀请链接已复制（演示）· 可设置查看 / 编辑权限')"><i class="ri-user-add-line"></i>邀请成员并设置权限</button>
        </div>

        <div v-if="linkedDocs.length" class="card pad-sm">
          <div class="card-title mb2">关联知识</div>
          <div v-for="d in linkedDocs" :key="d.id" class="side-row" @click="openDoc(d.id)">
            <i class="ri-file-text-line"></i>
            <span class="side-title">{{ d.title }}</span>
            <span class="side-time">{{ d.updated }}</span>
          </div>
        </div>

        <div v-if="pd.files.length" class="card pad-sm">
          <div class="card-title mb2">文件</div>
          <div v-for="(f, i) in pd.files" :key="i" class="side-row" @click="comingSoon('演示：文件预览与下载在正式版本提供')">
            <i :class="f.icon"></i>
            <span class="side-title">{{ f.name }}</span>
            <span class="side-time">{{ f.size }}</span>
          </div>
        </div>

        <div v-if="pd.activity.length" class="card pad">
          <div class="card-title">最近动态</div>
          <div class="feed">
            <div v-for="(ac, i) in pd.activity" :key="i" class="feed-row">
              <i :class="ac.icon" :style="{ color: ac.color }"></i>
              <div class="feed-main">
                <div class="feed-text">{{ ac.text }}</div>
                <div class="feed-time">{{ ac.time }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 任务 -->
    <div v-else-if="projTab === '任务'">
      <div class="tab-toolbar">
        <div class="seg">
          <button v-for="v in ([['列表', 'ri-list-check-2', 'list'], ['看板', 'ri-layout-column-line', 'board']] as const)" :key="v[2]" class="seg-btn icon" :class="{ on: pdTaskView === v[2] }" @click="setView(v[2])">
            <i :class="v[1]"></i>{{ v[0] }}
          </button>
        </div>
      </div>
      <div v-if="pdTaskView === 'list'" class="task-list-card">
        <div
          v-for="t in allTasks"
          :key="t.id"
          class="trow"
          :class="{ dragging: dragId === t.id }"
          draggable="true"
          @click="openTask(t.id)"
          @dragstart="onDragStart(t.id)"
          @dragover.prevent
          @drop="onDropReorder(t.id)"
        >
          <i class="drag-grip ri-draggable" title="拖拽排序"></i>
          <button class="check" :style="{ borderColor: t.checkBorder, background: t.checkBg }" @click.stop="toggleTask(t.id)"><i class="ri-check-line" :style="{ opacity: t.checkOp }"></i></button>
          <span class="trow-title" :style="{ color: t.titleColor, textDecoration: t.deco }">{{ t.title }}</span>
          <span v-if="t.waitBadge" class="wait-badge">{{ t.waitBadge }}</span>
          <span class="mini" :style="{ background: t.stBg, color: t.stColor }">{{ t.status }}</span>
          <span class="mini" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
          <span class="owner-av" :title="t.ownerName" :style="{ background: t.ownerColor }">{{ t.ownerInitial }}</span>
          <span class="trow-due" :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
        </div>
        <div v-if="allTasks.length === 0" class="empty-inline">还没有任务——点右上角「新建任务」开始拆解</div>
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
          <div class="board-col-head">
            <span class="board-dot" :style="{ background: col.dot }"></span>
            <span class="board-col-label">{{ col.label }}</span>
            <span class="board-col-count wao-tabular">{{ col.items.length }}</span>
          </div>
          <div class="board-cards">
            <div
              v-for="t in col.items"
              :key="t.id"
              class="board-card"
              :class="{ dragging: dragId === t.id }"
              draggable="true"
              @click="openTask(t.id)"
              @dragstart="onDragStart(t.id)"
            >
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
          <div class="feed-time">{{ ac.time }}</div>
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
.phead-health {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
}
.hdot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
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
.btn-ghost {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-ghost:hover {
  background: #f4f5f8;
}
.btn-ghost i {
  font-size: 14px;
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

.card {
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
}
.card.pad {
  padding: 16px 18px;
}
.card.pad-sm {
  padding: 14px 18px 8px;
}
.card-eyebrow {
  font-size: 12px;
  color: var(--wao-text-weak);
  letter-spacing: 1px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
}
.card-title.mb8 {
  margin-bottom: 8px;
}
.card-title.mb2 {
  margin-bottom: 2px;
}
.count {
  margin-left: 8px;
  font-size: 11.5px;
  background: #f0f1f4;
  color: var(--wao-text-3);
  border-radius: 999px;
  padding: 1px 8px;
}

.ov {
  margin-top: 16px;
  display: grid;
  grid-template-columns: 1.65fr 1fr;
  gap: 14px;
  align-items: start;
}
@media (max-width: 1150px) {
  .ov {
    grid-template-columns: 1fr;
  }
}
.ov-left,
.ov-right {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.goal {
  margin-top: 6px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--wao-text);
}

/* 里程碑 */
.ms-wrap {
  position: relative;
}
.ms-line {
  position: absolute;
  left: 7px;
  top: 14px;
  bottom: 14px;
  width: 2px;
  background: #e9ebf1;
}
.ms-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 7px 0;
  position: relative;
}
.ms-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid #4358d6;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  z-index: 1;
}
.ms-dot i {
  font-size: 10px;
  color: #fff;
}
.ms-name {
  flex: 1;
  font-size: 13px;
}
.ms-chip {
  font-size: 11px;
  background: #e9edfb;
  color: #3a4cc2;
  border-radius: 5px;
  padding: 1px 7px;
}
.ms-date {
  font-size: 12px;
  color: var(--wao-text-weak);
}

/* 近期任务 */
.recent {
  padding: 14px 12px 8px;
}
.recent-head {
  display: flex;
  align-items: center;
  padding: 0 6px 8px;
}
.rt-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 8px;
  cursor: pointer;
}
.rt-row:hover {
  background: var(--wao-bg-hover);
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
.check i {
  font-size: 12px;
  color: #fff;
}
.rt-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rt-due {
  font-size: 12px;
  width: 58px;
  text-align: right;
  flex-shrink: 0;
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
.rt-add {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: none;
  border-radius: 8px;
  padding: 8px 6px;
  font-size: 12.5px;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.rt-add:hover {
  background: var(--wao-bg-hover);
  color: var(--wao-primary);
}
.rt-add i {
  font-size: 14px;
}

/* 阻塞 */
.blockers {
  background: #fffdf7;
  border: 1px solid #f0e3c4;
  border-radius: 12px;
  padding: 14px 18px;
}
.blockers-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
  font-weight: 600;
  color: #8a5b10;
}
.bk-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid #f5eedc;
}
.bk-text {
  font-size: 13px;
  color: var(--wao-text);
}
.bk-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--wao-warning-strong);
}
.bk-main {
  flex: 1;
  min-width: 0;
}
.bk-nudge {
  height: 26px;
  padding: 0 10px;
  border: 1px solid #eadfc2;
  background: #fff;
  border-radius: 7px;
  font-size: 12px;
  color: #8a5b10;
  cursor: pointer;
  flex-shrink: 0;
}
.bk-nudge:hover {
  background: var(--wao-warning-bg);
}

/* 进度环 */
.ring-row {
  display: flex;
  align-items: center;
  gap: 14px;
}
.ring {
  width: 92px;
  height: 92px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.ring-hole {
  width: 70px;
  height: 70px;
  border-radius: 50%;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ring-pct {
  font-size: 20px;
  font-weight: 700;
}
.ring-pct span {
  font-size: 11px;
  font-weight: 500;
  color: var(--wao-text-weak);
}
.ring-meta {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
}
.rm-row {
  display: flex;
  justify-content: space-between;
}
.rm-row span:first-child {
  color: var(--wao-text-weak);
}
.rm-row span:last-child {
  color: var(--wao-text-2);
}
.sub-block {
  margin-top: 12px;
  border-top: 1px solid var(--wao-divider-2);
  padding-top: 12px;
}
.sub-label {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.spark {
  width: 100%;
  height: 56px;
  display: block;
  margin-top: 4px;
}
.dist-bar {
  margin-top: 8px;
  display: flex;
  height: 8px;
  border-radius: 99px;
  overflow: hidden;
  background: var(--wao-divider);
}
.dist-legend {
  margin-top: 8px;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.dl {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--wao-text-3);
}
.dl-dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
}

/* 成员 */
.mem-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 0;
}
.mem-av {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #fff;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.mem-name {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text);
}
.mem-role {
  font-size: 11px;
  border-radius: 5px;
  padding: 1.5px 7px;
}
.vis-row {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 0;
  border-top: 1px solid var(--wao-divider-2);
  font-size: 12.5px;
  color: var(--wao-text-3);
}
.vis-row i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.invite-btn {
  width: 100%;
  height: 31px;
  border: 1px dashed #d3d6df;
  background: none;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.invite-btn:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary);
}

/* 侧栏行（关联知识/文件） */
.side-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 6px;
  margin: 0 -6px;
  border-radius: 7px;
  cursor: pointer;
}
.side-row:hover {
  background: var(--wao-bg-hover);
}
.side-row > i {
  font-size: 14px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.side-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.side-time {
  font-size: 11.5px;
  color: var(--wao-text-weak);
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
.board-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.board-card {
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
