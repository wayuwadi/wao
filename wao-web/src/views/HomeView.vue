<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { toast } from '@/composables/toast'
import { dueInfo, priorityMeta } from '@/utils/domain'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const router = useRouter()

const WD = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const greet = (() => {
  const h = new Date().getHours()
  return h < 5 ? '夜深了' : h < 12 ? '早上好' : h < 14 ? '中午好' : h < 18 ? '下午好' : '晚上好'
})()
const dateStr = computed(() => {
  const d = new Date(data.today)
  return `${+data.today.slice(5, 7)}月${+data.today.slice(8, 10)}日 ${WD[d.getDay()]}`
})
const daysToAccept = computed(() =>
  Math.round((+new Date('2026-08-15') - +new Date(data.today)) / 86400000),
)

// ---- AI 今日聚焦（3 条轮换 + 接受/依据） ----
const aiIdx = ref(0)
const aiAccepted = ref<Record<string, boolean>>({})
const evidenceOpen = ref(false)
const sugg = computed(() => data.suggestions[aiIdx.value % data.suggestions.length]!)
const accepted = computed(() => !!aiAccepted.value[sugg.value.id])
function aiAccept() {
  aiAccepted.value = { ...aiAccepted.value, [sugg.value.id]: true }
  evidenceOpen.value = false
  toast('已接受建议并加入今日计划', 'ri-magic-line')
}
function aiUndo() {
  const next = { ...aiAccepted.value }
  delete next[sugg.value.id]
  aiAccepted.value = next
}
function aiSnooze() {
  aiIdx.value += 1
  evidenceOpen.value = false
  toast('已顺延，今晚 20:00 再次提醒', 'ri-time-line')
}

// ---- 今日待办 ----
const todayRows = computed(() =>
  data.tasks
    .filter((t) => t.due && t.due <= data.today)
    .sort((a, b) => Number(a.status === '已完成') - Number(b.status === '已完成'))
    .map((t) => {
      const p = data.projectById(t.project)
      const due = dueInfo(t.due, data.today)
      const pr = priorityMeta(t.pr)
      const done = t.status === '已完成'
      return {
        id: t.id, title: t.title, done,
        projName: p?.name ?? '未关联', projColor: data.areaColor(p?.area ?? ''),
        dueStr: due.label, dueColor: due.color, pr: t.pr, prBg: pr.bg, prColor: pr.color,
      }
    }),
)
const todayCount = computed(() => todayRows.value.filter((t) => !t.done).length)

// ---- 进行中的项目 ----
const runningProjects = computed(() =>
  data.projects
    .filter((p) => p.status === '进行中' || p.status === '有风险')
    .map((p) => ({
      id: p.id, name: p.name, updated: p.updated, pct: p.progress,
      areaName: data.areaById(p.area)?.name ?? '未分类', areaColor: data.areaColor(p.area),
    })),
)

// ---- 等待他人（演示固定 3 条） ----
const waiting = [
  { title: '评论区组件开发 · 等待交互稿结论', who: '娃哥', days: '2 天', proj: 'WAO 1.0 原型' },
  { title: '导航图标方案 A/B 拍板', who: '娃弟', days: '1 天', proj: 'WAO 1.0 原型' },
  { title: 'NAS 目录规范 v2 确认', who: '娃弟', days: '3 天', proj: '家庭资料归档' },
]

// ---- 自动化状态 ----
const autoOk = computed(() => `${data.autos.filter((a) => a.status === '正常').length} 项正常运行`)
const autoBad = '账单邮件归档 · 授权过期'

// ---- 最近知识 ----
const recentDocs = computed(() =>
  [
    { id: 'd9', auto: true },
    { id: 'd1', auto: false },
    { id: 'd7', auto: false },
  ].map((x) => {
    const d = data.docById(x.id)
    return { id: x.id, title: d?.title ?? '', time: d?.updated ?? '', auto: x.auto }
  }),
)

// ---- 导航 ----
function openTask(id: string) {
  ui.openTask(id)
}
function toggleTask(id: string) {
  data.toggleComplete(id)
}
function openProject(id: string) {
  router.push(`/projects/${id}`)
}
function openDoc(docId: string) {
  const kb = data.kbs.find((k) => k.folders.some((f) => f.docs.includes(docId)))
  if (kb) router.push({ path: `/kb/${kb.id}`, query: { doc: docId } })
  else router.push('/knowledge')
}
function nudge(who: string) {
  toast(`已在项目动态中提醒${who}（演示）`, 'ri-send-plane-2-line')
}
</script>

<template>
  <div class="home">
    <!-- 顶部：问候 + 目标胶囊 -->
    <div class="head">
      <div>
        <div class="greet">{{ greet }}，{{ session.currentUser.name }}</div>
        <div class="date">{{ dateStr }} · 距「WAO 1.0 内部验收」还有 {{ daysToAccept }} 天</div>
      </div>
      <div class="spacer"></div>
      <div class="goals">
        <button v-for="g in data.goals" :key="g.id" class="goal" title="查看人生领域与目标" @click="router.push('/projects')">
          <span class="goal-dot" :style="{ background: g.color }"></span>{{ g.name }}
          <span class="goal-bar"><span class="goal-fill" :style="{ width: g.pct + '%', background: g.color }"></span></span>
          <b class="wao-tabular">{{ g.pct }}%</b>
        </button>
      </div>
    </div>

    <!-- 第一行：AI 聚焦（1.75fr） + 今日待办（1fr） -->
    <div class="row row-a">
      <div class="ai-card">
        <div class="ai-head">
          <div class="ai-badge"><i class="ri-magic-line"></i></div>
          <div class="ai-title">AI 今日聚焦</div>
          <div class="ai-meta">基于 2 个目标 · 14 项任务 · 今日 3 条日程</div>
          <div class="spacer"></div>
          <div class="ai-dots">
            <span v-for="(s, i) in data.suggestions" :key="s.id" class="dot" :style="{ background: i === aiIdx % data.suggestions.length ? '#4358D6' : '#D9DCE5' }"></span>
          </div>
        </div>
        <div class="ai-body">
          <div class="ai-h">{{ sugg.title }}</div>
          <div class="ai-why">{{ sugg.why }}</div>
          <div class="ai-steps">
            <div v-for="(s, i) in sugg.steps" :key="i" class="ai-step">
              <span class="ai-step-n wao-tabular">{{ i + 1 }}</span><span class="ai-step-t">{{ s }}</span>
            </div>
          </div>
          <div v-if="accepted" class="ai-accepted">
            <i class="ri-checkbox-circle-fill"></i>
            <span class="spacer">已加入今日计划，并在「WAO 1.0 原型」动态中通知了娃弟</span>
            <button class="undo" @click="aiUndo">撤销</button>
          </div>
          <div v-else class="ai-actions">
            <button class="btn-primary" @click="aiAccept"><i class="ri-check-line"></i>接受建议</button>
            <button class="btn-ghost" @click="aiSnooze"><i class="ri-time-line"></i>稍后提醒</button>
            <button class="btn-link" @click="evidenceOpen = !evidenceOpen">
              查看依据<i :class="evidenceOpen ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'"></i>
            </button>
          </div>
          <div v-if="evidenceOpen && !accepted" class="ai-ev">
            <div v-for="(e, i) in sugg.ev" :key="i" class="ev-row">
              <span class="ev-chip">{{ e[0] }}</span><span class="ev-text">{{ e[1] }}</span>
            </div>
            <div class="ev-foot">依据来自你的目标、任务与日程数据 · 演示环境为模拟推理</div>
          </div>
        </div>
      </div>

      <div class="card list-card">
        <div class="card-head">
          <div class="card-title">今日待办</div>
          <span class="count wao-tabular">{{ todayCount }}</span>
          <div class="spacer"></div>
          <button class="btn-link plain" @click="router.push('/projects')">全部任务</button>
        </div>
        <div class="today-list">
          <div v-for="t in todayRows" :key="t.id" class="today-row" @click="openTask(t.id)">
            <button class="check" :class="{ done: t.done }" title="完成 / 重新打开" @click.stop="toggleTask(t.id)">
              <i class="ri-check-line"></i>
            </button>
            <div class="today-main">
              <div class="today-title" :class="{ done: t.done }">{{ t.title }}</div>
              <div class="today-sub">
                <span class="proj-dot" :style="{ background: t.projColor }"></span>{{ t.projName }}
                <span :style="{ color: t.dueColor }">{{ t.dueStr }}</span>
              </div>
            </div>
            <span class="pr" :style="{ background: t.prBg, color: t.prColor }">{{ t.pr }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 第二行：今日日程 / 进行中项目 / 等待他人 -->
    <div class="row row-b">
      <div class="card pad">
        <div class="card-head mb8">
          <div class="card-title">今日日程</div>
          <div class="spacer"></div>
          <button class="btn-link plain" @click="router.push('/calendar')">打开日历</button>
        </div>
        <div v-for="(ev, i) in data.schedule" :key="i" class="sched-row">
          <div class="sched-time wao-tabular">{{ ev.time }}</div>
          <div class="sched-bar" :style="{ background: ev.color }"></div>
          <div class="sched-main">
            <div class="sched-title">{{ ev.title }}</div>
            <div class="sched-sub">{{ ev.sub }}</div>
          </div>
        </div>
      </div>

      <div class="card pad">
        <div class="card-head mb6">
          <div class="card-title">进行中的项目</div>
          <div class="spacer"></div>
          <button class="btn-link plain" @click="router.push('/projects')">全部项目</button>
        </div>
        <div v-for="p in runningProjects" :key="p.id" class="run-row" @click="openProject(p.id)">
          <span class="run-dot" :style="{ background: p.areaColor }"></span>
          <div class="run-main">
            <div class="run-name">{{ p.name }}</div>
            <div class="run-sub">{{ p.areaName }} · 更新于 {{ p.updated }}</div>
          </div>
          <span class="run-bar"><span class="run-fill" :style="{ width: p.pct + '%', background: p.areaColor }"></span></span>
          <b class="run-pct wao-tabular">{{ p.pct }}%</b>
        </div>
      </div>

      <div class="card pad">
        <div class="card-head mb6">
          <div class="card-title">等待他人</div>
          <span class="count warn">{{ waiting.length }}</span>
        </div>
        <div v-for="(w, i) in waiting" :key="i" class="wait-row">
          <div class="wait-main">
            <div class="wait-title">{{ w.title }}</div>
            <div class="wait-sub">{{ w.proj }} · 等待{{ w.who }} · {{ w.days }}</div>
          </div>
          <button class="nudge" @click="nudge(w.who)">催一下</button>
        </div>
      </div>
    </div>

    <!-- 第三行：自动化状态条 / 最近知识 -->
    <div class="row row-c">
      <div class="card auto-bar">
        <div class="auto-icon"><i class="ri-flashlight-line"></i></div>
        <div class="card-title">自动化</div>
        <div class="auto-ok"><span class="s-dot ok"></span>{{ autoOk }}</div>
        <div class="auto-bad"><span class="s-dot bad"></span>{{ autoBad }}</div>
        <div class="spacer"></div>
        <button class="btn-link plain" @click="router.push('/automation')">前往处理</button>
      </div>

      <div class="card pad">
        <div class="card-head mb4">
          <div class="card-title">最近知识</div>
          <div class="spacer"></div>
          <button class="btn-link plain" @click="router.push('/knowledge')">打开知识库</button>
        </div>
        <div v-for="d in recentDocs" :key="d.id" class="doc-row" @click="openDoc(d.id)">
          <i :class="d.auto ? 'ri-flashlight-line' : 'ri-file-text-line'" class="doc-icon"></i>
          <div class="doc-title">{{ d.title }}</div>
          <span v-if="d.auto" class="doc-auto">自动沉淀</span>
          <span class="doc-time">{{ d.time }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  padding: 18px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}

/* 顶部 */
.head {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}
.greet {
  font-size: 21px;
  font-weight: 700;
}
.date {
  margin-top: 4px;
  font-size: 13px;
  color: var(--wao-text-weak);
}
.goals {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.goal {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--wao-line);
  background: #fff;
  border-radius: 999px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 12.5px;
  color: var(--wao-text-2);
}
.goal:hover {
  border-color: #c9cdda;
  background: var(--wao-bg-sub);
}
.goal-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.goal-bar {
  width: 56px;
  height: 5px;
  border-radius: 99px;
  background: var(--wao-divider);
  overflow: hidden;
  display: inline-block;
}
.goal-fill {
  display: block;
  height: 100%;
  border-radius: 99px;
}

/* 行网格 */
.row {
  margin-top: 14px;
  display: grid;
  gap: 14px;
  align-items: start;
}
.row-a {
  margin-top: 16px;
  grid-template-columns: 1.75fr 1fr;
}
.row-b {
  grid-template-columns: 1.05fr 1.2fr 1fr;
}
.row-c {
  grid-template-columns: 1.2fr 1fr;
}
@media (max-width: 1120px) {
  .row-a {
    grid-template-columns: 1fr;
  }
  .row-b {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 880px) {
  .row-b,
  .row-c {
    grid-template-columns: 1fr;
  }
}

.card {
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
}
.card.pad {
  padding: 13px 16px 10px;
}
.card-head {
  display: flex;
  align-items: center;
}
.card-head.mb8 {
  margin-bottom: 8px;
}
.card-head.mb6 {
  margin-bottom: 6px;
}
.card-head.mb4 {
  margin-bottom: 4px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
}
.count {
  margin-left: 8px;
  font-size: 11.5px;
  background: #f0f1f4;
  color: var(--wao-text-3);
  border-radius: 999px;
  padding: 1px 8px;
}
.count.warn {
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
}
.btn-link {
  border: none;
  background: none;
  color: var(--wao-primary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.btn-link.plain {
  font-size: 12.5px;
}

/* AI 卡 */
.ai-card {
  background: #fff;
  border: 1px solid var(--wao-primary-line);
  border-radius: 12px;
  overflow: hidden;
}
.ai-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 18px;
  background: var(--wao-primary-tint);
  border-bottom: 1px solid var(--wao-primary-soft-2);
}
.ai-badge {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--wao-primary-soft-2);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--wao-primary);
  font-size: 15px;
}
.ai-title {
  font-size: 14px;
  font-weight: 600;
}
.ai-meta {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.ai-dots {
  display: flex;
  gap: 5px;
  align-items: center;
}
.ai-dots .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.ai-body {
  padding: 18px 20px 20px;
}
.ai-h {
  font-size: 17px;
  font-weight: 650;
  line-height: 1.5;
}
.ai-why {
  margin-top: 8px;
  font-size: 13px;
  color: var(--wao-text-3);
  line-height: 1.8;
}
.ai-steps {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ai-step {
  display: flex;
  gap: 10px;
  font-size: 13px;
  color: var(--wao-text-2);
}
.ai-step-n {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #f0f2fb;
  color: var(--wao-primary);
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}
.ai-step-t {
  line-height: 1.7;
}
.ai-accepted {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 9px;
  background: var(--wao-success-bg);
  border: 1px solid var(--wao-success-line);
  border-radius: 9px;
  padding: 10px 14px;
  font-size: 13px;
  color: var(--wao-success-strong);
}
.ai-accepted i {
  font-size: 16px;
}
.undo {
  border: none;
  background: none;
  color: var(--wao-success-strong);
  font-size: 12.5px;
  cursor: pointer;
  text-decoration: underline;
  flex-shrink: 0;
}
.ai-actions {
  margin-top: 16px;
  display: flex;
  gap: 9px;
  flex-wrap: wrap;
  align-items: center;
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
.btn-ghost {
  height: 33px;
  padding: 0 14px;
  background: #fff;
  border: 1px solid var(--wao-line-strong);
  color: var(--wao-text-2);
  border-radius: 8px;
  font-size: 13px;
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
.ai-actions .btn-link {
  height: 33px;
  padding: 0 10px;
  border-radius: 8px;
  font-size: 13px;
}
.ai-actions .btn-link:hover {
  background: #f2f4fc;
}
.ai-ev {
  margin-top: 14px;
  background: #f7f8fb;
  border: 1px solid #ecedf3;
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  animation: waoFadeUp 0.18s ease;
}
.ev-row {
  display: flex;
  align-items: baseline;
  gap: 9px;
  font-size: 12.5px;
  color: var(--wao-text-2);
}
.ev-chip {
  font-size: 11px;
  background: var(--wao-primary-soft-2);
  color: var(--wao-primary-text);
  border-radius: 5px;
  padding: 1.5px 7px;
  flex-shrink: 0;
}
.ev-text {
  line-height: 1.6;
}
.ev-foot {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  margin-top: 2px;
}

/* 今日待办 */
.list-card {
  display: flex;
  flex-direction: column;
}
.list-card .card-head {
  padding: 13px 16px 8px;
}
.today-list {
  padding: 0 8px 10px;
  display: flex;
  flex-direction: column;
}
.today-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 8px;
  border-radius: 8px;
  cursor: pointer;
}
.today-row:hover {
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
  opacity: 0;
}
.check.done {
  border-color: var(--wao-primary);
  background: var(--wao-primary);
}
.check.done i {
  opacity: 1;
}
.today-main {
  flex: 1;
  min-width: 0;
}
.today-title {
  font-size: 13px;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.today-title.done {
  color: var(--wao-text-weak);
  text-decoration: line-through;
}
.today-sub {
  margin-top: 1px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  gap: 6px;
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

/* 日程 */
.sched-row {
  display: flex;
  gap: 10px;
  padding: 7px 2px;
}
.sched-time {
  width: 40px;
  font-size: 12px;
  color: var(--wao-text-weak);
  padding-top: 1px;
  flex-shrink: 0;
}
.sched-bar {
  width: 3px;
  border-radius: 99px;
  flex-shrink: 0;
}
.sched-title {
  font-size: 13px;
  color: var(--wao-text);
}
.sched-sub {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  margin-top: 1px;
}

/* 进行中项目 */
.run-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  margin: 0 -6px;
  border-radius: 8px;
  cursor: pointer;
}
.run-row:hover {
  background: var(--wao-bg-hover);
}
.run-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  flex-shrink: 0;
}
.run-main {
  flex: 1;
  min-width: 0;
}
.run-name {
  font-size: 13px;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.run-sub {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  margin-top: 1px;
}
.run-bar {
  width: 64px;
  height: 5px;
  border-radius: 99px;
  background: var(--wao-divider);
  overflow: hidden;
  flex-shrink: 0;
}
.run-fill {
  display: block;
  height: 100%;
  border-radius: 99px;
}
.run-pct {
  font-size: 12px;
  color: var(--wao-text-2);
  width: 34px;
  text-align: right;
  flex-shrink: 0;
}

/* 等待他人 */
.wait-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--wao-divider-2);
}
.wait-main {
  flex: 1;
  min-width: 0;
}
.wait-title {
  font-size: 13px;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wait-sub {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  margin-top: 1px;
}
.nudge {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 7px;
  font-size: 12px;
  color: var(--wao-text-2);
  cursor: pointer;
  flex-shrink: 0;
}
.nudge:hover {
  background: #f4f5f8;
}

/* 自动化条 */
.auto-bar {
  padding: 13px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.auto-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #f0f1f4;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--wao-text-3);
  font-size: 15px;
  flex-shrink: 0;
}
.auto-ok,
.auto-bad {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
}
.auto-ok {
  color: var(--wao-success-strong);
}
.auto-bad {
  color: var(--wao-warning-strong);
}
.s-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.s-dot.ok {
  background: var(--wao-success);
}
.s-dot.bad {
  background: var(--wao-warning);
}

/* 最近知识 */
.doc-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 6px;
  margin: 0 -6px;
  border-radius: 8px;
  cursor: pointer;
}
.doc-row:hover {
  background: var(--wao-bg-hover);
}
.doc-icon {
  font-size: 14px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.doc-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.doc-auto {
  font-size: 10.5px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 4px;
  padding: 1px 5px;
  flex-shrink: 0;
}
.doc-time {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
</style>
