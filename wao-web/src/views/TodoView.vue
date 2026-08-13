<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useDeco } from '@/composables/deco'
import { useCompletionSettle } from '@/composables/completion'
import { TASK_STATUSES, TASK_STATUS_DOTS } from '@/utils/domain'
import type { Task, TaskStatus } from '@/types'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const { decoTask } = useDeco()

const taskFilter = ref<'全部' | '我负责' | '我参与' | '等待他人'>('全部')
const taskView = ref<'list' | 'board' | 'pdca'>('list')

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

type PdcaPhase = 'P' | 'D' | 'C' | 'A'
const PDCA_META: Record<PdcaPhase, { title: string; subtitle: string; color: string }> = {
  P: { title: '计划', subtitle: '明确结果与判定标准', color: '#4358D6' },
  D: { title: '执行', subtitle: '推进步骤并记录事实', color: '#B9821F' },
  C: { title: '检查', subtitle: '对照目标复盘偏差', color: '#7A5CC6' },
  A: { title: '改进', subtitle: '沉淀经验或开启下一轮', color: '#2C8A5B' },
}
function pdcaPhaseOf(task: Task): PdcaPhase {
  const cycles = task.pdcaCycles ?? []
  const cycle = cycles[cycles.length - 1]
  if (cycle?.reviewedAt) return 'A'
  if (task.status === '待验证' || task.status === '已完成') return 'C'
  if (task.status === '进行中' || task.status === 'AI 进行中') return 'D'
  return 'P'
}
const pdcaCols = computed(() => (Object.keys(PDCA_META) as PdcaPhase[]).map((phase) => ({
  phase,
  ...PDCA_META[phase],
  items: myList.value
    .filter((task) => task.pdcaCycles?.length && pdcaPhaseOf(task) === phase)
    .map((task) => {
      const cycles = task.pdcaCycles!
      const cycle = cycles[cycles.length - 1]!
      const all = [...task.steps, ...task.subs]
      return {
        task,
        cycle,
        deco: decoTask(task),
        progress: all.length ? `${all.filter((item) => item.done).length}/${all.length} 步` : '未拆分步骤',
      }
    }),
})))
const pdcaTaskCount = computed(() => pdcaCols.value.reduce((sum, col) => sum + col.items.length, 0))
const pdcaReviewCount = computed(() => pdcaCols.value.find((col) => col.phase === 'C')?.items.length ?? 0)
const pdcaClosedCount = computed(() => pdcaCols.value.find((col) => col.phase === 'A')?.items.length ?? 0)

const tasksEmpty = computed(() => taskGroups.value.length === 0)

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
  <div class="todo">
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
      <div class="seg">
        <button
          v-for="v in ([['列表', 'ri-list-check-2', 'list'], ['看板', 'ri-layout-column-line', 'board'], ['PDCA', 'ri-loop-right-line', 'pdca']] as const)"
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

    <div v-else-if="taskView === 'board'" class="board">
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

    <section v-else class="pdca-view">
      <div class="pdca-summary">
        <div><b class="wao-tabular">{{ pdcaTaskCount }}</b><span>跟踪中的事情</span></div>
        <div><b class="wao-tabular review">{{ pdcaReviewCount }}</b><span>等待复盘</span></div>
        <div><b class="wao-tabular closed">{{ pdcaClosedCount }}</b><span>已形成闭环</span></div>
        <p>只统计已启用 PDCA 的真实任务；打开任意任务可补计划、复盘或开启下一轮。</p>
      </div>
      <div v-if="pdcaTaskCount" class="pdca-board">
        <div v-for="col in pdcaCols" :key="col.phase" class="pdca-col">
          <div class="pdca-col-head">
            <span class="pdca-letter" :style="{ background: col.color }">{{ col.phase }}</span>
            <div><b>{{ col.title }}</b><small>{{ col.subtitle }}</small></div>
            <em class="wao-tabular">{{ col.items.length }}</em>
          </div>
          <div class="pdca-cards">
            <button v-for="item in col.items" :key="item.task.id" type="button" class="pdca-card" @click="openTask(item.task.id)">
              <b>{{ item.task.title }}</b>
              <p v-if="col.phase === 'A'">{{ item.cycle.act }}</p>
              <p v-else>{{ item.cycle.plan || '还没有写本轮计划与判定标准' }}</p>
              <div class="pdca-card-foot">
                <span><i class="proj-dot" :style="{ background: item.deco.projColor }"></i>{{ item.deco.projName }}</span>
                <span v-if="col.phase === 'A'" class="pdca-outcome">{{ item.cycle.outcome }}</span>
                <span v-else>{{ item.progress }}</span>
              </div>
            </button>
            <div v-if="!col.items.length" class="pdca-col-empty">暂无事项</div>
          </div>
        </div>
      </div>
      <div v-else class="empty pdca-empty">
        <i class="ri-loop-right-line"></i>
        <b>还没有使用 PDCA 跟踪的事情</b>
        <span>新建任务时打开“使用 PDCA 跟踪”，或在已有任务详情里启用。</span>
        <button class="btn-primary" @click="ui.openModal('task')"><i class="ri-add-line"></i>新建任务</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.todo {
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
  border-color: #c7d0f1;
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
  color: var(--wao-primary);
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
  border: 1.5px solid #c9cdda;
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

/* PDCA 视图只投影真实启用记录，不把普通任务伪装成方法论数据。 */
.pdca-view { margin-top: 14px; }
.pdca-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.pdca-summary > div {
  min-width: 96px;
  padding-right: 12px;
  border-right: 1px solid var(--wao-divider);
  display: grid;
}
.pdca-summary b { color: var(--wao-primary-text); font-size: 20px; line-height: 1; }
.pdca-summary b.review { color: #7A5CC6; }
.pdca-summary b.closed { color: var(--wao-success-strong); }
.pdca-summary span { margin-top: 4px; color: var(--wao-text-weak); font-size: 11.5px; }
.pdca-summary p { margin: 0 0 0 auto; max-width: 390px; color: var(--wao-text-weak); font-size: 11.5px; line-height: 1.55; }
.pdca-board {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(4, minmax(190px, 1fr));
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 6px;
}
.pdca-col {
  min-width: 190px;
  padding: 10px;
  border-radius: 12px;
  background: var(--wao-bg-hover-2);
}
.pdca-col-head { display: flex; align-items: center; gap: 8px; padding: 1px 2px 9px; }
.pdca-letter {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 750;
  flex-shrink: 0;
}
.pdca-col-head > div { min-width: 0; display: grid; }
.pdca-col-head b { color: var(--wao-text-2); font-size: 12.5px; }
.pdca-col-head small { color: var(--wao-text-faint); font-size: 10.5px; white-space: nowrap; }
.pdca-col-head > em { margin-left: auto; color: var(--wao-text-weak); font-size: 11px; font-style: normal; }
.pdca-cards { display: grid; gap: 8px; }
.pdca-card {
  width: 100%;
  padding: 10px 11px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  text-align: left;
  cursor: pointer;
}
.pdca-card:hover { border-color: #c6cce3; box-shadow: 0 2px 8px rgba(16, 18, 28, .06); }
.pdca-card > b { display: block; font-size: 12.5px; line-height: 1.45; }
.pdca-card > p {
  margin: 6px 0 0;
  color: var(--wao-text-3);
  font-size: 11.5px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-wrap;
}
.pdca-card-foot { margin-top: 9px; display: flex; justify-content: space-between; gap: 6px; color: var(--wao-text-weak); font-size: 10.5px; }
.pdca-card-foot span { display: inline-flex; align-items: center; gap: 5px; min-width: 0; }
.pdca-outcome { color: var(--wao-success-strong); font-weight: 600; }
.pdca-col-empty { padding: 22px 6px; text-align: center; color: var(--wao-text-faint); font-size: 11.5px; }
.pdca-empty { display: grid; justify-items: center; gap: 7px; }
.pdca-empty > i { color: var(--wao-primary); font-size: 27px; }
.pdca-empty > b { color: var(--wao-text-2); font-size: 13.5px; }
.pdca-empty > span { color: var(--wao-text-weak); font-size: 12px; }
.pdca-empty .btn-primary { margin-top: 5px; }

@media (max-width: 760px) {
  .todo { padding: 16px 12px 28px; }
  .btn-primary { min-height: 36px; }
  .trow { align-items: flex-start; flex-wrap: wrap; }
  .trow-title { flex-basis: calc(100% - 30px); white-space: normal; }
  .trow-proj { max-width: calc(100% - 80px); margin-left: 27px; }
  .trow-due { margin-left: auto; }
  .pdca-summary { align-items: stretch; flex-wrap: wrap; }
  .pdca-summary > div { flex: 1; min-width: 78px; }
  .pdca-summary p { width: 100%; max-width: none; margin: 0; }
  .pdca-board { grid-template-columns: repeat(4, minmax(78vw, 1fr)); scroll-snap-type: x proximity; }
  .pdca-col { scroll-snap-align: start; }
}
</style>
