<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useDeco } from '@/composables/deco'
import { useCompletionSettle } from '@/composables/completion'
import { statusMeta, TASK_STATUSES, TASK_STATUS_DOTS } from '@/utils/domain'
import type { Project, ProjectStatus, TaskStatus } from '@/types'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const { decoTask, decoProj } = useDeco()

const projMode = computed(() => (route.query.view === 'mytasks' ? 'mytasks' : 'projects'))
function setMode(m: 'projects' | 'mytasks') {
  router.replace({ query: m === 'mytasks' ? { view: 'mytasks' } : {} })
}

// ---------- 项目视图 ----------
const q = ref('')
const groupBy = ref<'不分组' | '按领域' | '按状态'>('不分组')
const sortBy = ref<'按截止日期' | '按进度' | '按名称'>('按截止日期')
const statusF = ref<'全部' | ProjectStatus>('全部')
const openDrop = ref<'group' | 'sort' | null>(null)

const filteredProjects = computed(() => {
  const kw = q.value.trim().toLowerCase()
  let list = data.projects.filter(
    (p) => !kw || p.name.toLowerCase().includes(kw) || p.goal.toLowerCase().includes(kw),
  )
  if (statusF.value !== '全部') list = list.filter((p) => p.status === statusF.value)
  const sorts: Record<string, (a: Project, b: Project) => number> = {
    按截止日期: (a, b) => (a.due || '9').localeCompare(b.due || '9'),
    按进度: (a, b) => b.progress - a.progress,
    按名称: (a, b) => a.name.localeCompare(b.name, 'zh'),
  }
  return list.slice().sort(sorts[sortBy.value]!)
})

const projGroups = computed(() => {
  const list = filteredProjects.value
  let groups: { label: string; dot: string; items: Project[]; hide?: boolean }[]
  if (groupBy.value === '按领域') {
    groups = data.areas
      .map((a) => ({ label: a.name, dot: a.color, items: list.filter((p) => p.area === a.id) }))
      .filter((g) => g.items.length)
  } else if (groupBy.value === '按状态') {
    groups = (['进行中', '有风险', '规划中', '已完成'] as ProjectStatus[])
      .map((st) => ({ label: st, dot: statusMeta(st).color, items: list.filter((p) => p.status === st) }))
      .filter((g) => g.items.length)
  } else {
    groups = [{ label: '全部项目', dot: '#9A9DA9', items: list, hide: true }]
  }
  return groups.map((g) => ({
    label: g.label, dot: g.dot, count: g.items.length, showHead: !g.hide,
    items: g.items.map(decoProj),
  }))
})

const statusChips = computed(() =>
  (['全部', '进行中', '有风险', '规划中', '已完成'] as const).map((st) => ({
    label: st,
    count: data.projects.filter((p) => (st === '全部' ? true : p.status === st)).length,
    on: statusF.value === st,
  })),
)

// ---------- 我的任务视图 ----------
const taskFilter = ref<'全部' | '我负责' | '我参与' | '等待他人'>('全部')
const taskView = ref<'list' | 'board'>('list')

const myList = computed(() => {
  const me = session.currentUser.id
  let list = data.tasks
  if (taskFilter.value === '我负责') list = list.filter((t) => t.owner === me)
  if (taskFilter.value === '我参与') list = list.filter((t) => t.parts.includes(me))
  if (taskFilter.value === '等待他人') list = list.filter((t) => t.waiting)
  return list
})

// 已完成默认收起，点分组标题展开；勾完成后先原地停留 3 秒再归到「已完成」里。
const showDone = ref(false)
const { toggle: toggleTask, isSettling } = useCompletionSettle()

const taskGroups = computed(() => {
  const list = myList.value
  const open = list.filter((t) => t.status !== '已完成' || isSettling(t.id))
  const done = list.filter((t) => t.status === '已完成' && !isSettling(t.id))
  const buckets: [string, (due: string) => boolean][] = [
    ['今天', (due) => !!due && due <= data.today],
    ['本周', (due) => !!due && due > data.today && due <= '2026-07-26'],
    ['稍后', (due) => !due || due > '2026-07-26'],
  ]
  const groups: { label: string; items: ReturnType<typeof decoTask>[]; collapsible?: boolean }[] = buckets
    .map(([label, fn]) => ({ label, items: open.filter((t) => fn(t.due)).map(decoTask) }))
    .filter((g) => g.items.length)
  if (done.length) groups.push({ label: '已完成', items: done.map(decoTask), collapsible: true })
  return groups
})

const boardCols = computed(() =>
  TASK_STATUSES.map((st) => ({
    label: st, dot: TASK_STATUS_DOTS[st], collapsible: st === '已完成',
    items: myList.value.filter((t) => t.status === st).map(decoTask),
  })),
)

const tasksEmpty = computed(() => taskGroups.value.length === 0)

// ---------- 交互 ----------
function openProject(id: string) {
  router.push(`/projects/${id}`)
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
  if (t && t.status !== status) data.patchTask(id, { status }, `你将状态改为「${status}」`)
}
</script>

<template>
  <div class="projects">
    <!-- header -->
    <div class="ptop">
      <div class="ptitle">项目</div>
      <div class="seg">
        <button
          v-for="m in (['projects', 'mytasks'] as const)"
          :key="m"
          class="seg-btn"
          :class="{ on: projMode === m }"
          @click="setMode(m)"
        >
          {{ m === 'projects' ? '项目' : '我的任务' }}
        </button>
      </div>
      <span class="ptip">
        {{ projMode === 'projects'
          ? `${filteredProjects.length} 个项目 · 软件项目与人生大事都值得正式立项`
          : '跨项目任务汇总 · 任务归属于各自项目' }}
      </span>
      <div class="spacer"></div>
      <button class="btn-primary" @click="ui.openModal(projMode === 'projects' ? 'project' : 'task')">
        <i class="ri-add-line"></i>{{ projMode === 'projects' ? '新建项目' : '新建任务' }}
      </button>
    </div>

    <!-- 项目视图 -->
    <template v-if="projMode === 'projects'">
      <div class="toolbar">
        <div class="spacer"></div>
        <div v-if="openDrop" class="drop-mask" @click="openDrop = null"></div>
        <div class="search">
          <i class="ri-search-line"></i>
          <input v-model="q" placeholder="搜索项目名称或目标…" />
        </div>
        <div class="drop-wrap">
          <button class="tool-btn" @click="openDrop = openDrop === 'group' ? null : 'group'">
            <i class="ri-node-tree"></i>{{ groupBy }}<i class="ri-arrow-down-s-line chev"></i>
          </button>
          <div v-if="openDrop === 'group'" class="drop-menu">
            <button
              v-for="o in (['不分组', '按领域', '按状态'] as const)"
              :key="o"
              class="drop-item"
              @click="((groupBy = o), (openDrop = null))"
            >
              <span>{{ o }}</span><i v-if="o === groupBy" class="ri-check-line"></i>
            </button>
          </div>
        </div>
        <div class="drop-wrap">
          <button class="tool-btn" @click="openDrop = openDrop === 'sort' ? null : 'sort'">
            <i class="ri-arrow-up-down-line"></i>{{ sortBy }}<i class="ri-arrow-down-s-line chev"></i>
          </button>
          <div v-if="openDrop === 'sort'" class="drop-menu">
            <button
              v-for="o in (['按截止日期', '按进度', '按名称'] as const)"
              :key="o"
              class="drop-item"
              @click="((sortBy = o), (openDrop = null))"
            >
              <span>{{ o }}</span><i v-if="o === sortBy" class="ri-check-line"></i>
            </button>
          </div>
        </div>
      </div>

      <div class="chips">
        <button
          v-for="c in statusChips"
          :key="c.label"
          class="chip"
          :class="{ on: c.on }"
          @click="statusF = c.label"
        >
          {{ c.label }}<span class="chip-count wao-tabular">{{ c.count }}</span>
        </button>
      </div>

      <div class="groups">
        <div v-for="g in projGroups" :key="g.label">
          <div v-if="g.showHead" class="group-head">
            <span class="group-dot" :style="{ background: g.dot }"></span>{{ g.label }}
            <span class="group-count wao-tabular">{{ g.count }}</span>
          </div>
          <div class="card-grid">
            <div v-for="p in g.items" :key="p.id" class="proj-card" @click="openProject(p.id)">
              <div class="pc-top">
                <div class="pc-icon" :style="{ background: p.areaColor }">
                  <i v-if="p.icon" :class="p.icon"></i>
                  <template v-else>{{ p.initialChar }}</template>
                </div>
                <div class="pc-name-wrap">
                  <div class="pc-name">{{ p.name }}</div>
                  <div class="pc-sub">{{ p.areaName }} · 负责人 {{ p.ownerName }}</div>
                </div>
                <span class="pc-status" :style="{ background: p.stBg, color: p.stColor }">{{ p.status }}</span>
              </div>
              <div class="pc-goal">{{ p.goalShort }}</div>
              <div class="pc-meta">
                <span class="pc-m"><i class="ri-checkbox-circle-line"></i>{{ p.taskOpen }} / {{ p.taskTotal }} 项任务</span>
                <span class="pc-m" :style="{ color: p.dueColor }"><i class="ri-calendar-line"></i>{{ p.dueStr }}</span>
                <span class="spacer"></span>
                <span class="pc-avs">
                  <span v-for="(mv, i) in p.memberAvs" :key="i" class="pc-av" :style="{ background: mv.color }">{{ mv.initial }}</span>
                </span>
              </div>
              <div class="pc-prog">
                <span class="pc-bar"><span class="pc-fill" :style="{ width: p.pct + '%', background: p.progColor }"></span></span>
                <b class="wao-tabular">{{ p.pct }}%</b>
              </div>
            </div>
          </div>
        </div>
        <div v-if="filteredProjects.length === 0" class="empty">
          <i class="ri-filter-3-line"></i>
          <div>没有符合条件的项目——试试清除搜索或切换状态筛选</div>
        </div>
      </div>
    </template>

    <!-- 我的任务视图 -->
    <template v-else>
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
        <div class="seg">
          <button
            v-for="v in ([['列表', 'ri-list-check-2', 'list'], ['看板', 'ri-layout-column-line', 'board']] as const)"
            :key="v[2]"
            class="seg-btn icon"
            :class="{ on: taskView === v[2] }"
            @click="taskView = v[2]"
          >
            <i :class="v[1]"></i>{{ v[0] }}
          </button>
        </div>
      </div>

      <template v-if="taskView === 'list'">
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
    </template>
  </div>
</template>

<style scoped>
.projects {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}
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

.toolbar {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.drop-mask {
  position: fixed;
  inset: 0;
  z-index: 69;
}
.search {
  position: relative;
}
.search i {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--wao-text-weak);
  font-size: 14px;
}
.search input {
  height: 32px;
  width: 210px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 10px 0 32px;
  font-size: 13px;
  background: #fff;
  color: var(--wao-text);
  outline: none;
}
.search input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.drop-wrap {
  position: relative;
}
.tool-btn {
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.tool-btn:hover {
  background: #f4f5f8;
}
.tool-btn i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.tool-btn .chev {
  font-size: 13px;
}
.drop-menu {
  position: absolute;
  right: 0;
  top: 36px;
  z-index: 70;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop);
  padding: 5px;
  width: 130px;
  animation: waoPop 0.14s ease;
}
.drop-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 7px 9px;
  font-size: 13px;
  color: var(--wao-text);
  cursor: pointer;
}
.drop-item span {
  flex: 1;
  text-align: left;
}
.drop-item i {
  font-size: 14px;
  color: var(--wao-primary);
}
.drop-item:hover {
  background: var(--wao-bg-hover-2);
}

.chips {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  background: #fff;
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
  border-color: #c7d0f1;
  font-weight: 600;
}
.chip-count {
  opacity: 0.65;
}

.groups {
  margin-top: 2px;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 2px 0;
  font-size: 12.5px;
  color: var(--wao-text-3);
  font-weight: 600;
}
.group-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
}
.group-count {
  color: var(--wao-text-faint);
  font-weight: 400;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.proj-card {
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 15px 16px 13px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.proj-card:hover {
  border-color: #c9cdda;
  box-shadow: 0 3px 12px rgba(16, 18, 28, 0.07);
}
.pc-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pc-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 16px;
  flex-shrink: 0;
}
.pc-icon i {
  font-size: 19px;
}
.pc-name-wrap {
  flex: 1;
  min-width: 0;
}
.pc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pc-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.pc-status {
  font-size: 11px;
  border-radius: 999px;
  padding: 2px 9px;
  flex-shrink: 0;
}
.pc-goal {
  font-size: 12px;
  color: var(--wao-text-3);
  line-height: 1.7;
  min-height: 40px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pc-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.pc-m {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pc-m i {
  font-size: 13px;
}
.pc-avs {
  display: flex;
}
.pc-av {
  width: 19px;
  height: 19px;
  border-radius: 50%;
  color: #fff;
  font-size: 9.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #fff;
  margin-left: -5px;
}
.pc-prog {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pc-bar {
  flex: 1;
  height: 5px;
  border-radius: 99px;
  background: var(--wao-divider);
  overflow: hidden;
}
.pc-fill {
  display: block;
  height: 100%;
  border-radius: 99px;
}
.pc-prog b {
  font-size: 11.5px;
  color: var(--wao-text-3);
}

.empty {
  margin-top: 14px;
  padding: 44px;
  text-align: center;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.empty i {
  font-size: 22px;
  color: var(--wao-text-faint-2);
}
.empty div {
  margin-top: 8px;
}
.empty.simple {
  margin-top: 24px;
}

/* my tasks */
.mt-toolbar {
  margin-top: 14px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
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
  background: #fff;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 12px;
  padding: 14px;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-faint);
}
.tlist {
  background: #fff;
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
.bc-proj {
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  gap: 6px;
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
  .projects { padding: 16px 12px 28px; }
  .toolbar { gap: 7px; }
  .search { flex: 1 1 100%; }
  .search input { width: 100%; height: 38px; }
  .tool-btn, .btn-primary { min-height: 36px; }
  .card-grid { grid-template-columns: 1fr; }
  .trow { align-items: flex-start; flex-wrap: wrap; }
  .trow-title { flex-basis: calc(100% - 30px); white-space: normal; }
  .trow-proj { max-width: calc(100% - 80px); margin-left: 27px; }
  .trow-due { margin-left: auto; }
}
</style>
