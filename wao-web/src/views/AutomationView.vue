<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { automationResult } from '@/utils/automationResult'
import { useDataStore } from '@/stores/data'
import DemoBanner from '@/components/ui/DemoBanner.vue'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import { useRouter } from 'vue-router'
import HttpPerformanceConfigModal from '@/components/overlays/HttpPerformanceConfigModal.vue'
import type { HttpPerformanceInspectionConfig } from '@/api/board'
import type { AutomationJob } from '@/types'

const data = useDataStore()
const tab = ref<'jobs' | 'runs'>('jobs')
const selId = ref<string | null>(null)
// 保留既有弹层状态以兼容历史深链；界面不再暴露泛化“新建自动化”入口。
const newOpen = ref(false)
const newName = ref('')
const newSchedule = ref('')
const newDesc = ref('')
const newSource = ref('')
const newTarget = ref('')
const newSteps = ref('')
const newCron = ref('')
const newNotification = ref(false)

/** 有配置页的执行器：任务列表与详情上多一个「配置」入口。 */
const CHAT_SYNC_HANDLER = 'feishu-chat-sync'
const HTTP_PERFORMANCE_HANDLER = 'http-performance-ai-run'
const router = useRouter()
function openChatConfig() { void router.push('/capabilities?area=bots&pane=receiver') }
const httpConfigOpen = ref(false)
const RUN_PAGE_SIZE = 10
const runPage = ref(1)

const isConfigurable = (handler?: string | null) =>
  handler === CHAT_SYNC_HANDLER || handler === HTTP_PERFORMANCE_HANDLER
const durationOf = (durationMs?: number | null) =>
  durationMs == null ? '—' : `${Math.max(1, Math.round(durationMs / 1000))} 秒`

function stMeta(a: AutomationJob) {
  if (a.status === '正常')
    return { bg: 'var(--wao-bg-sub)', color: 'var(--wao-text-weak)', label: '已启用' }
  if (a.status === '已暂停')
    return { bg: 'var(--wao-bg-sub)', color: 'var(--wao-text-weak)', label: '已暂停' }
  return { bg: 'var(--wao-danger-bg)', color: 'var(--wao-danger-strong)', label: '需要处理' }
}

const jobs = computed(() =>
  data.autos.filter(a => !['feishu-chat-sync', 'feishu-daily-archive', 'shared-chat-nightly-sync', 'http-performance-ai-run', 'weknora-content-sync'].includes(a.handler ?? '')).map((a) => {
    const st = stMeta(a)
    const dots = a.runs
      .map((r) => ({
        bg: automationResult(r.result, r.aiRunId).color,
        title: `${r.time} · ${r.executor} · ${automationResult(r.result, r.aiRunId).label} · ${r.result}`,
      }))
      .slice(0, 5)
    return {
      job: a,
      stBg: st.bg,
      stLabel: st.label,
      latest: a.runs[0] ? automationResult(a.runs[0].result, a.runs[0].aiRunId).label : '尚未运行',
      stColor: st.color,
      needFix: a.status === '待修复',
      configurable: isConfigurable(a.handler),
      dots,
    }
  }),
)

const allRuns = computed(() => {
  const rows: {
    time: string
    executor: string
    job: string
    icon: string
    result: string
    label: string
    aiRunId?: string | null
    dur: string
    color: string
    bg: string
    id: string
    runAt: string | null
    order: number
  }[] = []
  let order = 0
  data.autos.forEach((a) =>
    a.runs.forEach((r) =>
      rows.push({
        time: r.time,
        executor: r.executor,
        job: a.name,
        icon: a.icon,
        result: r.result,
        dur: durationOf(r.durationMs),
        ...automationResult(r.result, r.aiRunId),
        aiRunId: r.aiRunId,
        id: a.id,
        runAt: r.runAt ?? null,
        order: order++,
      }),
    ),
  )
  return rows.sort((left, right) => {
    const byTime = Date.parse(right.runAt ?? '') - Date.parse(left.runAt ?? '')
    return Number.isNaN(byTime) || byTime === 0 ? left.order - right.order : byTime
  })
})
const runPageCount = computed(() => Math.max(1, Math.ceil(allRuns.value.length / RUN_PAGE_SIZE)))
const visibleRuns = computed(() =>
  allRuns.value.slice((runPage.value - 1) * RUN_PAGE_SIZE, runPage.value * RUN_PAGE_SIZE),
)

const sel = computed(() => data.autos.find((a) => a.id === selId.value) ?? null)
const selMeta = computed(() => (sel.value ? stMeta(sel.value) : { bg: '', color: '', label: '' }))
const aoRuns = computed(() =>
  (sel.value?.runs ?? []).map((r) => ({
    time: r.time,
    executor: r.executor,
    result: r.result,
    dur: durationOf(r.durationMs),
    ...automationResult(r.result, r.aiRunId),
    aiRunId: r.aiRunId,
  })),
)

type Operation = 'run' | 'fix' | 'pause' | 'resume'
const pending = ref<Record<string, Operation | undefined>>({})
const feedback = ref<Record<string, { message: string; error: boolean }>>({})
const operationLabels = {
  run: '正在运行',
  fix: '正在重新授权',
  pause: '正在暂停',
  resume: '正在恢复',
}

async function operate(id: string, operation: Operation) {
  if (pending.value[id]) return
  pending.value[id] = operation
  delete feedback.value[id]
  try {
    if (operation === 'run') {
      await data.runJob(id)
      const run = data.autos.find((job) => job.id === id)?.runs[0]
      const result = run ? automationResult(run.result, run.aiRunId) : null
      feedback.value[id] = {
        message: run
          ? `${result!.label}：${run.result}`
          : '请求已返回，尚未取得本次运行结果，请查看运行记录。',
        error: result?.label === '失败' || result?.label === '部分成功',
      }
    } else {
      if (operation === 'fix') await data.reauthJob(id)
      if (operation === 'pause') await data.pauseJob(id)
      if (operation === 'resume') await data.resumeJob(id)
      feedback.value[id] = {
        message:
          operation === 'pause'
            ? '已暂停，后续不再按计划运行。'
            : operation === 'resume'
              ? '已恢复，后续按计划运行。'
              : '已重新授权，请查看后续运行结果。',
        error: false,
      }
    }
  } catch (error) {
    feedback.value[id] = {
      message: `${error instanceof Error ? error.message : '请求失败'}。未能确认操作结果，请先刷新记录核对，避免重复执行。`,
      error: true,
    }
  } finally {
    delete pending.value[id]
  }
}
const onRun = (id: string) => operate(id, 'run')
const onFix = (id: string) => operate(id, 'fix')
const onPause = (id: string) => operate(id, 'pause')
const onResume = (id: string) => operate(id, 'resume')

async function inspectRecords(id: string) {
  selId.value = id
  await data.refreshAutomation()
}
async function createAuto() {
  if (!newName.value.trim()) return
  const job = await data.createJob({
    name: newName.value.trim(),
    schedule: newSchedule.value.trim() || undefined,
    desc: newDesc.value.trim() || undefined,
    source: newSource.value.trim() || undefined,
    target: newTarget.value.trim() || undefined,
    steps: newSteps.value
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean),
    cronExpr: newCron.value.trim() || undefined,
    handler: newNotification.value ? 'feishu-notification' : undefined,
  })
  newOpen.value = false
  selId.value = job.id
}

function switchTab(next: 'jobs' | 'runs') {
  tab.value = next
  if (next === 'runs') runPage.value = 1
}

function openConfig(job: AutomationJob) {
  if (job.handler === CHAT_SYNC_HANDLER) openChatConfig()
  if (job.handler === HTTP_PERFORMANCE_HANDLER) httpConfigOpen.value = true
}

function onHttpConfigSaved(config: HttpPerformanceInspectionConfig) {
  const job = data.autos.find((item) => item.handler === HTTP_PERFORMANCE_HANDLER)
  if (!job) return
  const frequency =
    config.frequency === 'DAILY' ? '每日' : config.frequency === 'WEEKDAYS' ? '工作日' : '每周一'
  job.schedule = `${frequency} ${config.time}`
  job.status = config.enabled ? '正常' : '已暂停'
}

onMounted(() => {
  void data.ensureAutomationLoaded()
})
</script>

<template>
  <div class="auto wao-page-frame">
    <DemoBanner
      icon="ri-flashlight-line"
      title="自动化"
      text="查看定时任务是否启用，以及每次运行发生了什么。已启用只代表允许按计划运行；已入队或已提交执行不代表任务完成。不再需要的任务可以暂停。"
      storage-key="automation"
    />
    <div v-if="data.loadStates.automation === 'loading'" class="automation-state" role="status">
      <i class="ri-loader-4-line spin"></i>正在读取自动化任务与真实运行记录…
    </div>
    <div
      v-else-if="data.loadStates.automation === 'error'"
      class="automation-state error"
      role="alert"
    >
      <span>{{ data.loadErrors.automation || '自动化读取失败' }}</span>
      <button @click="data.refreshAutomation()">重新读取</button>
    </div>
    <div
      v-for="job in data.autos.filter((job) => pending[job.id] || feedback[job.id])"
      :key="job.id"
      class="automation-state operation-feedback"
      :class="{ error: feedback[job.id]?.error }"
      :role="feedback[job.id]?.error ? 'alert' : 'status'"
      :aria-busy="!!pending[job.id]"
    >
      <strong>{{ job.name }}</strong>
      <span>{{
        pending[job.id]
          ? `${operationLabels[pending[job.id]!]}，请稍候…`
          : feedback[job.id]?.message
      }}</span>
      <button v-if="!pending[job.id]" @click="inspectRecords(job.id)">刷新并查看记录</button>
    </div>
    <!-- 列表 -->
    <template v-if="!sel">
      <div class="atop">
        <div class="atitle">自动化 / 定时任务</div>
        <span class="atip">让记录自己发生</span>
        <button class="btn-ghost" @click="openChatConfig">飞书归档与 @ 记录</button>
        <div class="spacer"></div>
      </div>
      <div class="tabs">
        <button
          v-for="t in [
            ['任务列表', 'jobs'],
            ['运行记录', 'runs'],
          ] as const"
          :key="t[1]"
          class="tab"
          :class="{ on: tab === t[1] }"
          @click="switchTab(t[1])"
        >
          {{ t[0] }}
        </button>
      </div>
      <div v-if="tab === 'jobs'" class="table-card">
        <div class="grid-jobs th">
          <div>名称</div>
          <div>计划</div>
          <div>计划开关</div>
          <div>上次运行</div>
          <div>近 5 次</div>
          <div class="right">操作</div>
        </div>
        <div v-for="jb in jobs" :key="jb.job.id" class="grid-jobs row">
          <button class="job-name" @click="selId = jb.job.id">
            <div class="job-icon"><i :class="jb.job.icon"></i></div>
            <span class="job-link">{{ jb.job.name }}</span>
          </button>
          <div class="cell">{{ jb.job.schedule }}</div>
          <div>
            <span class="mini" :style="{ background: jb.stBg, color: jb.stColor }">{{
              jb.stLabel
            }}</span>
          </div>
          <div class="cell muted latest-result">
            <strong>{{ jb.latest }}</strong
            ><span>{{ jb.job.last }}</span>
          </div>
          <div class="dots">
            <span
              v-for="(d, i) in jb.dots"
              :key="i"
              class="dot"
              :title="d.title"
              :aria-label="d.title"
              role="img"
              :style="{ background: d.bg }"
            ></span>
          </div>
          <div class="ops">
            <button
              v-if="jb.configurable"
              class="op"
              title="配置"
              :disabled="!!pending[jb.job.id]"
              @click="openConfig(jb.job)"
            >
              <i class="ri-settings-3-line"></i>
            </button>
            <button
              v-if="jb.needFix"
              class="op fix"
              title="重新授权"
              :disabled="!!pending[jb.job.id]"
              @click="onFix(jb.job.id)"
            >
              <i class="ri-tools-line"></i>
            </button>
            <button
              v-if="jb.job.status === '已暂停'"
              class="op"
              title="恢复"
              :disabled="!!pending[jb.job.id]"
              @click="onResume(jb.job.id)"
            >
              <i class="ri-play-circle-line"></i>
            </button>
            <button
              v-else
              class="op"
              title="暂停"
              :disabled="!!pending[jb.job.id]"
              @click="onPause(jb.job.id)"
            >
              <i class="ri-pause-circle-line"></i>
            </button>
            <button
              class="op"
              title="手动运行"
              :disabled="!!pending[jb.job.id]"
              @click="onRun(jb.job.id)"
            >
              <i class="ri-play-line"></i>
            </button>
          </div>
        </div>
      </div>

      <div v-else class="table-card">
        <div class="grid-runs th">
          <div>开始时间</div>
          <div>任务</div>
          <div>时长</div>
          <div>结果</div>
        </div>
        <div
          v-for="(rn, i) in visibleRuns"
          :key="`${rn.id}-${rn.runAt ?? rn.time}-${i}`"
          class="grid-runs row link"
          @click="selId = rn.id"
        >
          <div class="cell muted wao-tabular">{{ rn.time }}</div>
          <div class="run-job">
            <i :class="rn.icon"></i><span class="ellip">{{ rn.job }}</span>
          </div>
          <div class="cell muted wao-tabular">{{ rn.dur }}</div>
          <div>
            <span class="mini" :style="{ background: rn.bg, color: rn.color }">{{ rn.label }}</span>
            <div class="result-detail">触发记录：{{ rn.result }}</div>
          </div>
        </div>
        <WaoPagination
          v-if="allRuns.length > RUN_PAGE_SIZE"
          v-model:page="runPage"
          class="run-pagination"
          :page-count="runPageCount"
          :total="allRuns.length"
          aria-label="自动化运行记录分页"
        />
      </div>
      <div class="foot-note">
        每日午夜归档与 @ 快捷记录在能力中心管理；旧聊天同步和表情轮询已停用。
      </div>
    </template>

    <!-- Job 详情 -->
    <template v-else>
      <button class="back" @click="selId = null">
        <i class="ri-arrow-left-line"></i>全部自动化
      </button>
      <div class="job-head">
        <div class="jh-icon"><i :class="sel.icon"></i></div>
        <div class="jh-main">
          <div class="jh-title-row">
            <div class="jh-title">{{ sel.name }}</div>
            <span class="mini" :style="{ background: selMeta.bg, color: selMeta.color }">{{
              selMeta.label
            }}</span>
          </div>
          <div class="jh-desc">{{ sel.desc }}</div>
        </div>
        <div class="spacer"></div>
        <button
          v-if="isConfigurable(sel.handler)"
          class="btn-ghost"
          :disabled="!!pending[sel.id]"
          @click="openConfig(sel)"
        >
          <i class="ri-settings-3-line"></i>配置
        </button>
        <button
          v-if="sel.status === '待修复'"
          class="btn-danger"
          :disabled="!!pending[sel.id]"
          @click="onFix(sel.id)"
        >
          重新授权
        </button>
        <button class="btn-primary sm" :disabled="!!pending[sel.id]" @click="onRun(sel.id)">
          <i class="ri-play-line"></i>手动运行
        </button>
        <button
          v-if="sel.status === '已暂停'"
          class="btn-ghost"
          :disabled="!!pending[sel.id]"
          @click="onResume(sel.id)"
        >
          <i class="ri-play-circle-line"></i>恢复
        </button>
        <button v-else class="btn-ghost" :disabled="!!pending[sel.id]" @click="onPause(sel.id)">
          <i class="ri-pause-circle-line"></i>暂停
        </button>
      </div>

      <div class="job-cols">
        <div class="table-card flush">
          <div class="jc-head">运行记录</div>
          <div v-if="!aoRuns.length" class="automation-state">
            尚未运行。可等待计划执行，或使用“手动运行”。
          </div>
          <div class="grid-aoruns th sub">
            <div>开始时间</div>
            <div>执行人</div>
            <div>时长</div>
            <div>结果</div>
          </div>
          <div v-for="(r, i) in aoRuns" :key="i" class="grid-aoruns row">
            <div class="cell muted wao-tabular">{{ r.time }}</div>
            <div class="cell muted">{{ r.executor }}</div>
            <div class="cell muted wao-tabular">{{ r.dur }}</div>
            <div>
              <span class="mini" :style="{ background: r.bg, color: r.color }">{{ r.label }}</span>
              <div class="result-detail">触发记录：{{ r.result }}</div>
            </div>
          </div>
        </div>

        <div class="detail-card">
          <div class="dc-title">任务详情</div>
          <div class="dc-rows">
            <div class="dc-row">
              <span class="dc-key">触发器</span
              ><span class="dc-val">定时 · {{ sel.schedule }}</span>
            </div>
            <div class="dc-row">
              <span class="dc-key">数据源</span><span class="dc-val">{{ sel.source }}</span>
            </div>
            <div class="dc-row">
              <span class="dc-key">输出到</span><span class="dc-val">{{ sel.target }}</span>
            </div>
          </div>
          <div class="dc-steps">
            <div class="dc-eyebrow">执行步骤</div>
            <div class="steps">
              <div v-for="(s, i) in sel.steps" :key="i" class="step">
                <span class="step-n wao-tabular">{{ i + 1 }}</span
                ><span class="step-t">{{ s }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <HttpPerformanceConfigModal
      v-if="httpConfigOpen"
      @close="httpConfigOpen = false"
      @saved="onHttpConfigSaved"
    />

    <!-- 新建自动化弹层 -->
    <template v-if="newOpen">
      <div class="modal-mask" @click="newOpen = false"></div>
      <div class="modal">
        <div class="modal-head">
          <div class="modal-title">新建自动化</div>
          <div class="spacer"></div>
          <button class="icon-btn" @click="newOpen = false"><i class="ri-close-line"></i></button>
        </div>
        <div class="modal-body">
          <label class="f-label">名称</label>
          <input v-model="newName" class="f-input" placeholder="给自动化任务起个名字" />
          <label class="f-label">计划（展示文案，如「每日 06:00」）</label>
          <input v-model="newSchedule" class="f-input" placeholder="选填" />
          <label class="f-label">Cron 表达式（选填，留空则仅手动触发）</label>
          <input v-model="newCron" class="f-input" placeholder="例如 0 0 6 * * *" />
          <label class="f-label">描述</label>
          <textarea
            v-model="newDesc"
            class="f-textarea"
            rows="2"
            :placeholder="newNotification ? '通知正文（将原样发到飞书群）' : '选填'"
          ></textarea>
          <label class="switch"
            ><input v-model="newNotification" type="checkbox" /><span
              >这是飞书群提醒 / 公告（发送“做到了”和“没做到”反馈按钮）</span
            ></label
          >
          <label class="f-label">数据源</label>
          <input v-model="newSource" class="f-input" placeholder="选填" />
          <label class="f-label">输出到</label>
          <input v-model="newTarget" class="f-input" placeholder="选填" />
          <label class="f-label">执行步骤（每行一步）</label>
          <textarea v-model="newSteps" class="f-textarea" rows="3" placeholder="选填"></textarea>
        </div>
        <div class="modal-foot">
          <div class="spacer"></div>
          <button class="btn-primary" :disabled="!newName.trim()" @click="createAuto">创建</button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.operation-feedback {
  padding: 12px 14px;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}
.operation-feedback strong {
  color: var(--wao-text);
}
button:disabled {
  opacity: 0.6;
  cursor: wait;
}

.latest-result {
  display: grid;
  gap: 4px;
  overflow-wrap: anywhere;
}
.latest-result strong {
  color: var(--wao-text);
  font-weight: 500;
}
.result-detail {
  margin-top: 5px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
  overflow-wrap: anywhere;
}
.result-link {
  display: inline-block;
  margin-top: 5px;
  color: var(--wao-primary-text);
  font-size: var(--wao-font-caption);
}
.result-link:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}

.auto {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.automation-state {
  min-height: 48px;
  margin: 12px 0;
  padding: 0 14px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  gap: 8px;
}
.automation-state.error {
  color: var(--wao-danger-strong);
}
.automation-state button {
  margin-left: auto;
  border: 0;
  background: none;
  color: var(--wao-primary-text);
  cursor: pointer;
}
.automation-state .spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
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
  font-size: var(--wao-font-title);
  font-weight: 700;
}
.atip {
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
}
.btn-primary {
  flex-shrink: 0;
  white-space: nowrap;
  height: 32px;
  padding: 0 13px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: var(--wao-font-body);
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
  flex-shrink: 0;
  white-space: nowrap;
  height: 31px;
  padding: 0 13px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 8px;
  font-size: var(--wao-font-body);
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.btn-ghost:hover {
  background: var(--wao-bg-hover);
}
.btn-ghost i {
  font-size: 13px;
}
.btn-danger {
  flex-shrink: 0;
  white-space: nowrap;
  height: 31px;
  padding: 0 13px;
  background: #bc4141;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: var(--wao-font-body);
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
  font-size: var(--wao-font-body);
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
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow-x: auto;
}
.table-card.flush {
  overflow-x: auto;
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
  font-size: var(--wao-font-caption);
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
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
}
.grid-runs.row {
  border-bottom: 1px solid var(--wao-divider-2);
}
.grid-runs.link {
  cursor: pointer;
}
.grid-runs.link:hover {
  background: var(--wao-bg-sub-2);
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
  background: var(--wao-bg-tab);
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
  font-size: var(--wao-font-body);
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
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.cell.muted {
  color: var(--wao-text-weak);
}
.mini {
  font-size: var(--wao-font-caption);
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
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.op:hover {
  background: var(--wao-bg-hover);
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
  font-size: var(--wao-font-body);
  color: var(--wao-text);
}
.foot-note {
  margin-top: 14px;
  font-size: var(--wao-font-caption);
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
  font-size: var(--wao-font-body);
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
  background: var(--wao-bg-tab);
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
  font-size: var(--wao-font-section);
  font-weight: 700;
}
.jh-desc {
  margin-top: 2px;
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
}

.job-cols {
  margin-top: 14px;
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr);
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
  font-size: var(--wao-font-body);
  font-weight: 600;
}
.grid-aoruns {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 80px 70px minmax(150px, 1.4fr);
  gap: 10px;
  padding: 10px 16px;
  align-items: center;
}
.grid-aoruns.th {
  padding: 8px 16px;
  border-top: 1px solid var(--wao-divider);
  border-bottom: 1px solid var(--wao-divider);
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
  background: var(--wao-bg-sub);
}
.grid-aoruns.row {
  border-bottom: 1px solid var(--wao-divider-2);
}
.detail-card {
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 15px 18px;
}
.dc-title {
  font-size: var(--wao-font-body);
  font-weight: 600;
}
.dc-rows {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  font-size: var(--wao-font-body);
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
  font-size: var(--wao-font-caption);
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
  font-size: var(--wao-font-body);
  color: var(--wao-text-2);
}
.step-n {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--wao-primary-tint);
  color: var(--wao-primary-text);
  font-size: var(--wao-font-caption);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 1px;
}
.step-t {
  line-height: 1.7;
}
.btn-ghost.danger {
  color: #bc4141;
  border-color: #f0c9c9;
}
.btn-ghost.danger:hover {
  background: var(--wao-danger-bg);
}
.btn-primary:disabled {
  background: var(--wao-line-strong);
  cursor: not-allowed;
}

/* 新建自动化弹层 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 9vh;
  left: 50%;
  transform: translateX(-50%);
  width: min(540px, calc(100vw - 40px));
  max-height: 80vh;
  overflow-y: auto;
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
}
.modal-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--wao-divider);
}
.modal-title {
  font-size: var(--wao-font-body);
  font-weight: 600;
}
.icon-btn {
  width: 30px;
  height: 30px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
}
.icon-btn:hover {
  background: var(--wao-bg-hover-2);
}
.modal-body {
  padding: 16px 20px;
}
.f-label {
  display: block;
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
  margin: 12px 0 6px;
}
.f-label:first-child {
  margin-top: 0;
}
.f-input,
.f-textarea {
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: var(--wao-font-body);
  color: var(--wao-text);
  outline: none;
  background: var(--wao-bg-card);
}
.f-input:focus,
.f-textarea:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.f-textarea {
  resize: vertical;
}
.modal-foot {
  display: flex;
  align-items: center;
  padding: 14px 20px;
  border-top: 1px solid var(--wao-divider);
}

@media (max-width: 760px) {
  .auto {
    padding: 16px 12px 28px;
  }
  .toolbar {
    gap: 7px;
  }
  .grid-jobs,
  .grid-aoruns {
    min-width: 620px;
  }
  .job-list,
  .runs-list {
    overflow-x: auto;
  }
  .job-cols {
    grid-template-columns: 1fr;
  }
  .modal {
    top: 12px;
    width: calc(100vw - 24px);
    max-height: calc(100dvh - 24px);
  }
}
</style>
