<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import DemoBanner from '@/components/ui/DemoBanner.vue'
import type { AutomationJob } from '@/types'

const data = useDataStore()
const tab = ref<'jobs' | 'runs'>('jobs')
const selName = ref<string | null>(null)

const isFail = (result: string) => result.includes('失败')
const durOf = (i: number, j: number) => `${((i * 7 + j * 13) % 46) + 9} 秒`

function stMeta(a: AutomationJob) {
  return a.status === '正常'
    ? { bg: '#E8F5EE', color: '#257A50' }
    : { bg: '#FBEBEB', color: '#BC4141' }
}

const jobs = computed(() =>
  data.autos.map((a, i) => {
    const st = stMeta(a)
    const dots = a.runs
      .map((r) => ({ bg: isFail(r[1]) ? '#D65454' : '#2C8A5B', title: `${r[0]} · ${r[1]}` }))
      .concat([{ bg: '#E3E4EA', title: '更早的运行' }, { bg: '#E3E4EA', title: '更早的运行' }])
      .slice(0, 5)
    return { job: a, index: i, stBg: st.bg, stColor: st.color, needFix: a.status === '待修复', dots }
  }),
)

const allRuns = computed(() => {
  const rows: { time: string; job: string; icon: string; result: string; dur: string; color: string; bg: string; name: string }[] = []
  data.autos.forEach((a, i) =>
    a.runs.forEach((r, j) =>
      rows.push({
        time: r[0], job: a.name, icon: a.icon, result: r[1], dur: durOf(i, j),
        color: isFail(r[1]) ? '#BC4141' : '#257A50', bg: isFail(r[1]) ? '#FBEBEB' : '#E8F5EE', name: a.name,
      }),
    ),
  )
  return rows
})

const sel = computed(() => data.autos.find((a) => a.name === selName.value) ?? null)
const selMeta = computed(() => (sel.value ? stMeta(sel.value) : { bg: '', color: '' }))
const selIndex = computed(() => (sel.value ? data.autos.indexOf(sel.value) : 0))
const aoRuns = computed(() =>
  (sel.value?.runs ?? []).map((r, j) => ({
    time: r[0], result: r[1], dur: durOf(selIndex.value, j),
    color: isFail(r[1]) ? '#BC4141' : '#257A50', bg: isFail(r[1]) ? '#FBEBEB' : '#E8F5EE',
  })),
)

function run(name: string) {
  toast(`已手动触发「${name}」（演示）`, 'ri-play-line')
}
function fix() {
  toast('演示：重新授权流程在正式版本提供', 'ri-tools-line')
}
function pauseJob() {
  toast('已暂停，将不再按计划运行（演示）', 'ri-pause-circle-line')
}
function newAuto() {
  toast('演示：自动化编辑器在下一轮迭代提供')
}
</script>

<template>
  <div class="auto">
    <DemoBanner
      icon="ri-flashlight-line"
      title="自动化 · 功能演示"
      text="当前为示例自动化任务与运行记录，用于演示定时同步、归档与提醒的形态。可视化编排、触发器与真实执行引擎将在后续迭代交付。"
      storage-key="automation"
    />
    <!-- 列表 -->
    <template v-if="!sel">
      <div class="atop">
        <div class="atitle">自动化 / 定时任务</div>
        <span class="atip">让记录自己发生</span>
        <div class="spacer"></div>
        <button class="btn-primary" @click="newAuto"><i class="ri-add-line"></i>新建自动化</button>
      </div>
      <div class="tabs">
        <button v-for="t in ([['任务列表', 'jobs'], ['运行记录', 'runs']] as const)" :key="t[1]" class="tab" :class="{ on: tab === t[1] }" @click="tab = t[1]">{{ t[0] }}</button>
      </div>

      <div v-if="tab === 'jobs'" class="table-card">
        <div class="grid-jobs th">
          <div>名称</div><div>计划</div><div>状态</div><div>上次运行</div><div>近 5 次</div><div class="right">操作</div>
        </div>
        <div v-for="jb in jobs" :key="jb.job.name" class="grid-jobs row">
          <button class="job-name" @click="selName = jb.job.name">
            <div class="job-icon"><i :class="jb.job.icon"></i></div>
            <span class="job-link">{{ jb.job.name }}</span>
          </button>
          <div class="cell">{{ jb.job.schedule }}</div>
          <div><span class="mini" :style="{ background: jb.stBg, color: jb.stColor }">{{ jb.job.status }}</span></div>
          <div class="cell muted ellip">{{ jb.job.last }}</div>
          <div class="dots"><span v-for="(d, i) in jb.dots" :key="i" class="dot" :title="d.title" :style="{ background: d.bg }"></span></div>
          <div class="ops">
            <button v-if="jb.needFix" class="op fix" title="重新授权" @click="fix"><i class="ri-tools-line"></i></button>
            <button class="op" title="手动运行" @click="run(jb.job.name)"><i class="ri-play-line"></i></button>
          </div>
        </div>
      </div>

      <div v-else class="table-card">
        <div class="grid-runs th">
          <div>开始时间</div><div>任务</div><div>时长</div><div>结果</div>
        </div>
        <div v-for="(rn, i) in allRuns" :key="i" class="grid-runs row link" @click="selName = rn.name">
          <div class="cell muted wao-tabular">{{ rn.time }}</div>
          <div class="run-job"><i :class="rn.icon"></i><span class="ellip">{{ rn.job }}</span></div>
          <div class="cell muted wao-tabular">{{ rn.dur }}</div>
          <div><span class="mini" :style="{ background: rn.bg, color: rn.color }">{{ rn.result }}</span></div>
        </div>
      </div>
      <div class="foot-note">演示环境不会真实执行任务；「飞书群聊 → 知识库沉淀」的结果可在知识库「群聊沉淀」查看。</div>
    </template>

    <!-- Job 详情 -->
    <template v-else>
      <button class="back" @click="selName = null"><i class="ri-arrow-left-line"></i>全部自动化</button>
      <div class="job-head">
        <div class="jh-icon"><i :class="sel.icon"></i></div>
        <div class="jh-main">
          <div class="jh-title-row">
            <div class="jh-title">{{ sel.name }}</div>
            <span class="mini" :style="{ background: selMeta.bg, color: selMeta.color }">{{ sel.status }}</span>
          </div>
          <div class="jh-desc">{{ sel.desc }}</div>
        </div>
        <div class="spacer"></div>
        <button v-if="sel.status === '待修复'" class="btn-danger" @click="fix">重新授权</button>
        <button class="btn-primary sm" @click="run(sel.name)"><i class="ri-play-line"></i>手动运行</button>
        <button class="btn-ghost" @click="pauseJob"><i class="ri-pause-circle-line"></i>暂停</button>
      </div>

      <div class="job-cols">
        <div class="table-card flush">
          <div class="jc-head">运行记录</div>
          <div class="grid-aoruns th sub">
            <div>开始时间</div><div>时长</div><div>结果</div>
          </div>
          <div v-for="(r, i) in aoRuns" :key="i" class="grid-aoruns row">
            <div class="cell muted wao-tabular">{{ r.time }}</div>
            <div class="cell muted wao-tabular">{{ r.dur }}</div>
            <div><span class="mini" :style="{ background: r.bg, color: r.color }">{{ r.result }}</span></div>
          </div>
        </div>

        <div class="detail-card">
          <div class="dc-title">Job 详情</div>
          <div class="dc-rows">
            <div class="dc-row"><span class="dc-key">触发器</span><span class="dc-val">定时 · {{ sel.schedule }}</span></div>
            <div class="dc-row"><span class="dc-key">数据源</span><span class="dc-val">{{ sel.source }}</span></div>
            <div class="dc-row"><span class="dc-key">输出到</span><span class="dc-val">{{ sel.target }}</span></div>
          </div>
          <div class="dc-steps">
            <div class="dc-eyebrow">执行步骤</div>
            <div class="steps">
              <div v-for="(s, i) in sel.steps" :key="i" class="step">
                <span class="step-n wao-tabular">{{ i + 1 }}</span><span class="step-t">{{ s }}</span>
              </div>
            </div>
          </div>
          <div class="dc-note">配置项在本轮为只读演示；编辑器与更多触发方式在后续迭代提供。</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.auto {
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
.atop {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.atitle {
  font-size: 20px;
  font-weight: 700;
}
.atip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.btn-primary {
  height: 32px;
  padding: 0 13px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 12.5px;
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
  height: 31px;
}
.btn-primary.sm i {
  font-size: 13px;
}
.btn-ghost {
  height: 31px;
  padding: 0 13px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.btn-ghost:hover {
  background: #f4f5f8;
}
.btn-ghost i {
  font-size: 13px;
}
.btn-danger {
  height: 31px;
  padding: 0 13px;
  background: #bc4141;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 12.5px;
  cursor: pointer;
}
.btn-danger:hover {
  background: #a63838;
}

.tabs {
  margin-top: 12px;
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--wao-line);
}
.tab {
  height: 34px;
  padding: 0 13px;
  border: none;
  background: none;
  border-bottom: 2px solid transparent;
  color: #7a7d89;
  font-size: 13px;
  cursor: pointer;
  margin-bottom: -1px;
}
.tab.on {
  color: var(--wao-text);
  font-weight: 600;
  border-bottom-color: var(--wao-primary);
}

.table-card {
  margin-top: 14px;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow-x: auto;
}
.table-card.flush {
  overflow: hidden;
}
.grid-jobs {
  display: grid;
  grid-template-columns: minmax(230px, 1.8fr) 110px 84px minmax(190px, 1.4fr) 110px 86px;
  gap: 10px;
  padding: 11px 16px;
  align-items: center;
  min-width: 860px;
}
.grid-jobs.th {
  padding: 9px 16px;
  border-bottom: 1px solid var(--wao-divider);
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.grid-jobs.row {
  border-bottom: 1px solid var(--wao-divider-2);
}
.grid-runs {
  display: grid;
  grid-template-columns: 130px minmax(230px, 1.8fr) 84px minmax(170px, 1fr);
  gap: 10px;
  padding: 10px 16px;
  align-items: center;
}
.grid-runs.th {
  padding: 9px 16px;
  border-bottom: 1px solid var(--wao-divider);
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.grid-runs.row {
  border-bottom: 1px solid var(--wao-divider-2);
}
.grid-runs.link {
  cursor: pointer;
}
.grid-runs.link:hover {
  background: #fafafc;
}
.right {
  text-align: right;
}
.job-name {
  display: flex;
  align-items: center;
  gap: 9px;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  text-align: left;
  min-width: 0;
}
.job-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: #f0f1f4;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.job-icon i {
  font-size: 14px;
  color: var(--wao-text-3);
}
.job-link {
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-primary-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.job-link:hover {
  text-decoration: underline;
}
.cell {
  font-size: 12px;
  color: var(--wao-text-3);
}
.cell.muted {
  color: var(--wao-text-weak);
}
.mini {
  font-size: 11px;
  border-radius: 5px;
  padding: 1.5px 7px;
}
.dots {
  display: flex;
  gap: 5px;
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}
.ops {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
.op {
  width: 26px;
  height: 26px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.op:hover {
  background: #f4f5f8;
}
.op i {
  font-size: 13px;
}
.op.fix {
  border-color: #f0c9c9;
  color: #bc4141;
}
.op.fix:hover {
  background: var(--wao-danger-bg);
}
.run-job {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.run-job i {
  font-size: 13px;
  color: var(--wao-text-weak);
}
.run-job span {
  font-size: 12.5px;
  color: var(--wao-text);
}
.foot-note {
  margin-top: 14px;
  font-size: 12px;
  color: var(--wao-text-faint);
}

/* 详情 */
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
.job-head {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.jh-icon {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  background: #f0f1f4;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--wao-text-3);
  font-size: 19px;
}
.jh-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.jh-title {
  font-size: 18px;
  font-weight: 700;
}
.jh-desc {
  margin-top: 2px;
  font-size: 12.5px;
  color: var(--wao-text-weak);
}

.job-cols {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1.7fr 1fr;
  gap: 14px;
  align-items: start;
}
@media (max-width: 1000px) {
  .job-cols {
    grid-template-columns: 1fr;
  }
}
.jc-head {
  padding: 12px 16px 9px;
  font-size: 14px;
  font-weight: 600;
}
.grid-aoruns {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 90px minmax(150px, 1.4fr);
  gap: 10px;
  padding: 10px 16px;
  align-items: center;
}
.grid-aoruns.th {
  padding: 8px 16px;
  border-top: 1px solid var(--wao-divider);
  border-bottom: 1px solid var(--wao-divider);
  font-size: 11.5px;
  color: var(--wao-text-weak);
  background: var(--wao-bg-sub);
}
.grid-aoruns.row {
  border-bottom: 1px solid var(--wao-divider-2);
}
.detail-card {
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 15px 18px;
}
.dc-title {
  font-size: 14px;
  font-weight: 600;
}
.dc-rows {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  font-size: 12.5px;
}
.dc-row {
  display: flex;
  gap: 10px;
}
.dc-key {
  width: 56px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.dc-val {
  color: var(--wao-text-2);
}
.dc-steps {
  margin-top: 12px;
  border-top: 1px solid var(--wao-divider-2);
  padding-top: 12px;
}
.dc-eyebrow {
  font-size: 12px;
  color: var(--wao-text-weak);
  letter-spacing: 1px;
}
.steps {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.step {
  display: flex;
  gap: 10px;
  font-size: 13px;
  color: var(--wao-text-2);
}
.step-n {
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
  margin-top: 1px;
}
.step-t {
  line-height: 1.7;
}
.dc-note {
  margin-top: 12px;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
</style>
