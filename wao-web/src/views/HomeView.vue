<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  createAiPairCode,
  fetchAiRunners,
  fetchAiRuns,
  fetchAiWorkflows,
  requestRunnerScriptUpdate,
  rerunAiRun,
  saveAiProjectConfig,
  updateRunnerMaxConcurrency,
  wakeAiRunner,
} from '@/api/ai'
import AiRunProgressTimeline from '@/components/ai/AiRunProgressTimeline.vue'
import { AI_ENGINES, engineColor, engineIcon, engineLabel } from '@/composables/aiEngines'
import { formatCost, formatTokens, shortModel, usageSummary } from '@/composables/aiUsage'
import { toast } from '@/composables/toast'
import type { AiEngine, AiRun, AiRunStatus, AiRunner, AiWorkflow } from '@/types'

type ConsoleView = 'runs' | 'runners' | 'workflows'

const router = useRouter()
const runs = ref<AiRun[]>([])
const runners = ref<AiRunner[]>([])
const workflows = ref<AiWorkflow[]>([])
const loading = ref(true)
const workflowsLoading = ref(false)
const selectedRun = ref<AiRun | null>(null)
const view = ref<ConsoleView>('runs')
const pairCode = ref('')
const pairExpiresAt = ref('')
const editingProjectId = ref<string | null>(null)
const saving = ref(false)
const rerunning = ref(false)
const editingRunnerId = ref<string | null>(null)
const editingRunnerValue = ref(2)
const editingRunnerSaving = ref(false)
const updatingRunners = ref(false)
const wakingRunnerId = ref<string | null>(null)
const form = reactive({
  enabled: false,
  repoUrl: '',
  defaultBranch: 'main',
  workspaceDir: '',
  defaultEngine: 'codex' as AiEngine,
  preferredRunnerId: '',
  maxParallelRuns: 2,
})
let timer: ReturnType<typeof setInterval> | null = null

const activeRuns = computed(() => runs.value.filter((run) => ['queued', 'claimed', 'running'].includes(run.status)))
const recentRuns = computed(() => runs.value.filter((run) => !['queued', 'claimed', 'running'].includes(run.status)).slice(0, 8))
const onlineRunners = computed(() => runners.value.filter((runner) => runner.online))
const totalSlots = computed(() => onlineRunners.value.reduce((sum, runner) => sum + runner.maxConcurrency, 0))
const usedSlots = computed(() => onlineRunners.value.reduce((sum, runner) => sum + runner.activeRuns, 0))

/** 每种 AI 工具当前有几台电脑在线，决定 hero 上的接入状态。 */
const engineStatus = computed(() =>
  AI_ENGINES.map((engine) => ({
    ...engine,
    total: runners.value.filter((runner) => runner.engines.includes(engine.id)).length,
    online: onlineRunners.value.filter((runner) => runner.engines.includes(engine.id)).length,
  })),
)

const VIEWS: Array<{ id: ConsoleView; label: string; icon: string }> = [
  { id: 'runs', label: '运行状态', icon: 'ri-pulse-line' },
  { id: 'runners', label: '执行电脑', icon: 'ri-computer-line' },
  { id: 'workflows', label: 'AI 工作流', icon: 'ri-git-branch-line' },
]

const statusMeta: Record<AiRunStatus, { label: string; icon: string; color: string; bg: string }> = {
  queued: { label: '等待电脑领取', icon: 'ri-time-line', color: '#8a5b0d', bg: '#fff7df' },
  claimed: { label: '准备工作区', icon: 'ri-git-branch-line', color: '#3650b4', bg: '#eef1ff' },
  running: { label: 'AI 运行中', icon: 'ri-loader-4-line', color: '#3650b4', bg: '#eef1ff' },
  succeeded: { label: '等待验收', icon: 'ri-checkbox-circle-line', color: '#237a4b', bg: '#eaf8f0' },
  failed: { label: '运行失败', icon: 'ri-error-warning-line', color: '#b33a3a', bg: '#fff0f0' },
  cancelled: { label: '已取消', icon: 'ri-stop-circle-line', color: '#747989', bg: '#f1f2f5' },
}

// 退出码 3 是 Runner 的约定：AI 结束了会话但没报告任务状态，成败需要人判断，
// 不能直接挂一个「运行失败」让人分不清到底成没成。
const UNCONFIRMED_META = { label: '结果待确认', icon: 'ri-question-line', color: '#8a5b0d', bg: '#fff7df' }

function statusUnreported(run: AiRun) {
  return run.status === 'failed' && run.exitCode === 3
}

function metaFor(run: AiRun) {
  return statusUnreported(run) ? UNCONFIRMED_META : statusMeta[run.status]
}

function resultTitle(run: AiRun) {
  if (statusUnreported(run)) return '结果待确认（AI 未报告任务状态）'
  return run.status === 'failed' ? '失败原因' : '最终结果'
}

async function refresh() {
  try {
    const [nextRuns, nextRunners] = await Promise.all([fetchAiRuns(false), fetchAiRunners()])
    runs.value = nextRuns
    runners.value = nextRunners
    if (selectedRun.value) selectedRun.value = nextRuns.find((run) => run.id === selectedRun.value?.id) ?? selectedRun.value
  } catch {
    // 首页轮询失败时保留上一帧，避免网络抖动让运行面板闪空。
  } finally {
    loading.value = false
  }
}

async function refreshWorkflows() {
  workflowsLoading.value = true
  try {
    workflows.value = await fetchAiWorkflows()
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI 工作流读取失败', 'ri-error-warning-line')
  } finally {
    workflowsLoading.value = false
  }
}

watch(view, (next) => {
  if (next === 'workflows' && !workflows.value.length) void refreshWorkflows()
})

/** 某台电脑当前正在跑的任务，用于电脑卡片直接看到它在忙什么。 */
function runsOnRunner(runnerId: string) {
  return activeRuns.value.filter((run) => run.runnerId === runnerId)
}

function runnersFor(engine: AiEngine | null) {
  return runners.value.filter((runner) => !engine || runner.engines.includes(engine))
}

function startEditingRunner(runner: AiRunner) {
  editingRunnerId.value = runner.id
  editingRunnerValue.value = runner.maxConcurrency
  editingRunnerSaving.value = false
}

async function saveRunnerConcurrency(runner: AiRunner) {
  if (editingRunnerValue.value < 1 || editingRunnerValue.value > 8) {
    toast('并发数必须在 1 到 8 之间', 'ri-error-warning-line')
    return
  }
  editingRunnerSaving.value = true
  try {
    const updated = await updateRunnerMaxConcurrency(runner.id, editingRunnerValue.value)
    const index = runners.value.findIndex((r) => r.id === runner.id)
    if (index >= 0) runners.value[index] = { ...updated, configuredMaxConcurrency: updated.maxConcurrency }
    editingRunnerId.value = null
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存失败', 'ri-error-warning-line')
  } finally {
    editingRunnerSaving.value = false
  }
}

function cancelEditingRunner() {
  editingRunnerId.value = null
}

/** 一键把最新 Runner 脚本下发给所有执行电脑；各电脑空闲后自动更新重启。 */
async function pushRunnerUpdate() {
  updatingRunners.value = true
  try {
    const next = await requestRunnerScriptUpdate()
    runners.value = next
    const pending = next.filter((runner) => runner.updateRequested).length
    toast(
      pending
        ? `已通知 ${pending} 台电脑更新，空闲后自动重启生效`
        : '所有执行电脑的 Runner 脚本已是最新版',
      'ri-download-cloud-2-line',
    )
  } catch (error) {
    toast(error instanceof Error ? error.message : 'Runner 更新下发失败', 'ri-error-warning-line')
  } finally {
    updatingRunners.value = false
  }
}
async function wakeRunner(runner: AiRunner) {
  if (!runner.wakeConfigured) {
    toast('这台电脑尚未自动上报唤醒信息，请先正常启动一次 Runner', 'ri-information-line')
    return
  }
  wakingRunnerId.value = runner.id
  try {
    const result = await wakeAiRunner(runner.id)
    toast(result.message, 'ri-send-plane-line')
    await refresh()
  } catch (error) {
    toast(error instanceof Error ? error.message : '唤醒包发送失败', 'ri-error-warning-line')
  } finally {
    wakingRunnerId.value = null
  }
}

function startEditing(workflow: AiWorkflow) {
  editingProjectId.value = workflow.projectId
  Object.assign(form, {
    enabled: workflow.config.enabled,
    repoUrl: workflow.config.repoUrl,
    defaultBranch: workflow.config.defaultBranch,
    workspaceDir: workflow.config.workspaceDir,
    defaultEngine: workflow.config.defaultEngine,
    preferredRunnerId: workflow.config.preferredRunnerId ?? '',
    maxParallelRuns: workflow.config.maxParallelRuns,
  })
}

async function saveWorkflow(projectId: string) {
  saving.value = true
  try {
    await saveAiProjectConfig(projectId, {
      enabled: form.enabled,
      repoUrl: form.repoUrl.trim(),
      defaultBranch: form.defaultBranch.trim() || 'main',
      workspaceDir: form.workspaceDir.trim(),
      defaultEngine: form.defaultEngine,
      preferredRunnerId: form.preferredRunnerId || null,
      maxParallelRuns: form.maxParallelRuns,
    })
    editingProjectId.value = null
    await refreshWorkflows()
    toast('AI 工作流已保存', 'ri-check-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI 工作流保存失败', 'ri-error-warning-line')
  } finally {
    saving.value = false
  }
}

async function generatePairCode() {
  try {
    const result = await createAiPairCode()
    pairCode.value = result.code
    pairExpiresAt.value = result.expiresAt
    toast('已生成一次性配对码，十分钟内有效', 'ri-computer-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '配对码生成失败', 'ri-error-warning-line')
  }
}

function openRun(run: AiRun) {
  router.push({ path: `/projects/${run.projectId}`, query: { task: run.taskId } })
}

function inspectRun(run: AiRun) {
  selectedRun.value = run
}

/** 失败的运行可以带着报错重跑一次：AI 接着这个错往下修，不用从头再来。 */
async function rerun(run: AiRun) {
  if (rerunning.value) return
  rerunning.value = true
  try {
    const next = await rerunAiRun(run.id)
    selectedRun.value = null
    await refresh()
    toast(`已带着上次的报错重新排队：${next.taskTitle}`, 'ri-refresh-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '重跑失败', 'ri-error-warning-line')
  } finally {
    rerunning.value = false
  }
}

function resultPreview(run: AiRun) {
  if (!run.resultSummary) return run.status === 'failed' ? '执行节点没有返回错误详情' : ''
  const meaningfulLine = run.resultSummary
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line && !line.startsWith('工作目录：') && !line.startsWith('执行工具：') && !line.startsWith('本机日志：'))
  return meaningfulLine || run.resultSummary.trim()
}

function timeText(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function durationText(startedAt: string | null, finishedAt: string | null) {
  if (!startedAt) return '尚未开始'
  const end = finishedAt ? new Date(finishedAt).getTime() : Date.now()
  const seconds = Math.max(0, Math.floor((end - new Date(startedAt).getTime()) / 1000))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  return minutes < 60 ? `${minutes} 分钟` : `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`
}

function progressAge(value: string | null) {
  if (!value) return '等待反馈'
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000))
  if (seconds < 10) return '刚刚更新'
  if (seconds < 60) return `${seconds} 秒前更新`
  return `${Math.floor(seconds / 60)} 分钟前更新`
}

onMounted(() => {
  void refresh()
  timer = setInterval(refresh, 5_000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="home">
    <section class="hero">
      <div>
        <div class="eyebrow">AI CONTROL CENTER</div>
        <h1>现在有 {{ activeRuns.length }} 个任务在跑</h1>
        <p>同一个项目可以交给不同电脑上的不同 AI 工具，每个任务使用独立会话和 Git worktree。</p>
        <div class="engine-status">
          <span v-for="engine in engineStatus" :key="engine.id" :class="{ dim: !engine.online }">
            <i :class="engine.icon"></i>{{ engine.label }}
            <b>{{ engine.online ? `${engine.online} 台在线` : engine.total ? '全部离线' : '未接入' }}</b>
          </span>
        </div>
      </div>
      <div class="capacity" :style="{ '--capacity': `${totalSlots ? Math.min(100, usedSlots / totalSlots * 100) : 0}%` }">
        <div class="capacity-orbit" aria-hidden="true"><i></i><b></b><em></em></div>
        <div class="capacity-copy">
          <div class="capacity-num">{{ usedSlots }}<span>/ {{ totalSlots }}</span></div>
          <div>执行槽位占用</div>
          <div class="capacity-bar"><i></i></div>
        </div>
      </div>
    </section>

    <nav class="console-tabs">
      <button
        v-for="item in VIEWS"
        :key="item.id"
        :class="{ on: view === item.id }"
        @click="view = item.id"
      >
        <i :class="item.icon"></i>{{ item.label }}
        <em v-if="item.id === 'runs' && activeRuns.length">{{ activeRuns.length }}</em>
        <em v-else-if="item.id === 'runners' && onlineRunners.length">{{ onlineRunners.length }}</em>
      </button>
      <span class="live"><i></i>每 5 秒刷新</span>
    </nav>

    <template v-if="view === 'runs'">
      <section class="run-section">
        <div class="section-head">
          <div>
            <h2>正在运行</h2>
            <p>点击卡片查看 AI 的实时反馈和执行档案。</p>
          </div>
        </div>
        <div v-if="loading" class="empty"><i class="ri-loader-4-line spin"></i>正在读取 AI 运行状态…</div>
        <div v-else-if="!activeRuns.length" class="empty ready">
          <i class="ri-robot-2-line"></i>
          <b>当前没有任务在运行</b>
          <span>打开项目任务，点击 “AI run” 选择电脑和 AI 工具即可开始。</span>
        </div>
        <div v-else class="run-grid">
          <button v-for="run in activeRuns" :key="run.id" class="run-card" @click="inspectRun(run)">
            <div class="run-top">
              <span class="status" :style="{ color: statusMeta[run.status].color, background: statusMeta[run.status].bg }">
                <i :class="[statusMeta[run.status].icon, { spin: run.status === 'running' }]"></i>
                {{ statusMeta[run.status].label }}
              </span>
              <span class="run-time">{{ timeText(run.startedAt || run.createdAt) }}</span>
            </div>
            <h3>{{ run.taskTitle }}</h3>
            <div class="run-project"><i class="ri-stack-line"></i>{{ run.projectName }}</div>
            <div class="live-progress">
              <span class="live-pulse"></span>
              <div><b>{{ run.latestProgress || '正在等待第一条 AI 反馈…' }}</b><small>{{ progressAge(run.progressUpdatedAt) }}</small></div>
            </div>
            <div class="run-foot">
              <span class="engine-tag" :style="{ color: engineColor(run.engine) }">
                <i :class="engineIcon(run.engine)"></i>{{ engineLabel(run.engine) }}
              </span>
              <span class="foot-runner"><i class="ri-computer-line"></i>{{ run.runnerName || '等待自动分配' }}</span>
              <i class="ri-arrow-right-line"></i>
            </div>
          </button>
        </div>
      </section>

      <section v-if="recentRuns.length" class="recent-section">
        <div class="section-head">
          <div><h2>最近完成</h2><p>完成后任务会自动进入“待验证”。</p></div>
        </div>
        <div class="recent-list">
          <button v-for="run in recentRuns" :key="run.id" @click="inspectRun(run)">
            <span class="recent-icon" :style="{ color: metaFor(run).color, background: metaFor(run).bg }">
              <i :class="metaFor(run).icon"></i>
            </span>
            <span class="recent-main">
              <b>{{ run.taskTitle }}</b>
              <em>
                {{ run.projectName }} · {{ engineLabel(run.engine) }} · {{ run.runnerName || '未分配电脑' }}
                <span v-if="usageSummary(run.usage)" class="recent-usage">· {{ usageSummary(run.usage) }}</span>
              </em>
              <small v-if="run.status === 'failed'">{{ resultPreview(run) }}</small>
            </span>
            <span class="recent-status">{{ metaFor(run).label }}</span>
            <span class="run-time">{{ timeText(run.finishedAt) }}</span>
            <i class="ri-arrow-right-s-line"></i>
          </button>
        </div>
      </section>
    </template>

    <template v-else-if="view === 'runners'">
      <section class="run-section">
        <div class="section-head">
          <div>
            <h2>执行电脑</h2>
            <p>每台电脑自己声明装了哪些 AI 工具；登录态和源码都留在本机。</p>
          </div>
          <button class="pair-btn ghost" :disabled="updatingRunners" @click="pushRunnerUpdate">
            <i :class="updatingRunners ? 'ri-loader-4-line spin' : 'ri-download-cloud-2-line'"></i>更新 Runner 脚本
          </button>
          <button class="pair-btn" @click="generatePairCode"><i class="ri-link"></i>生成配对码</button>
        </div>
        <div v-if="pairCode" class="pair-code">
          <div>
            <b>{{ pairCode }}</b>
            <small>十分钟内有效 · 到期 {{ pairExpiresAt.replace('T', ' ') }}</small>
          </div>
          <code>python wao-ai-runner.py --server https://wao.wayuwa.cn --pair-code {{ pairCode }} --name 电脑名 --engines codex,claude,qoder</code>
        </div>
        <div v-if="!runners.length && !loading" class="empty ready">
          <i class="ri-computer-line"></i>
          <b>还没有电脑接入</b>
          <span>点击右上角生成配对码，在目标电脑上启动 wao-ai-runner.py。</span>
        </div>
        <div v-else class="runner-grid">
          <article v-for="runner in runners" :key="runner.id" class="runner-card" :class="{ offline: !runner.online }">
            <header>
              <span class="online-dot"></span>
              <b>{{ runner.name }}</b>
              <span class="runner-state">{{ runner.online ? '在线' : '离线' }}</span>
            </header>
            <div class="runner-platform"><i class="ri-hard-drive-2-line"></i>{{ runner.platform || '未知平台' }}</div>
            <div class="engine-tags">
              <span
                v-for="engine in runner.engines"
                :key="engine"
                class="engine-tag boxed"
                :style="{ color: engineColor(engine), borderColor: `${engineColor(engine)}33` }"
              >
                <i :class="engineIcon(engine)"></i>{{ engineLabel(engine) }}
              </span>
            </div>
            <div class="slot-line">
              <template v-if="editingRunnerId === runner.id">
                <span class="concurrency-edit">
                  并发
                  <input
                    v-model.number="editingRunnerValue"
                    type="number"
                    min="1"
                    max="8"
                    :disabled="editingRunnerSaving"
                    @keyup.enter="saveRunnerConcurrency(runner)"
                    @keyup.esc="cancelEditingRunner"
                  />
                  <button
                    class="icon-btn"
                    :disabled="editingRunnerSaving"
                    aria-label="保存"
                    @click="saveRunnerConcurrency(runner)"
                  >
                    <i :class="editingRunnerSaving ? 'ri-loader-4-line spin' : 'ri-check-line'"></i>
                  </button>
                  <button
                    class="icon-btn secondary"
                    :disabled="editingRunnerSaving"
                    aria-label="取消"
                    @click="cancelEditingRunner"
                  >
                    <i class="ri-close-line"></i>
                  </button>
                </span>
              </template>
              <template v-else>
                <span>
                  槽位 {{ runner.activeRuns }} / {{ runner.maxConcurrency }}
                  <button class="icon-btn secondary" aria-label="修改并发数" @click="startEditingRunner(runner)">
                    <i class="ri-edit-line"></i>
                  </button>
                </span>
              </template>
              <span class="run-time">{{ runner.lastSeenAt ? `心跳 ${timeText(runner.lastSeenAt)}` : '从未上报' }}</span>
            </div>
            <div class="slot-bar">
              <i :style="{ width: `${Math.min(100, runner.activeRuns / Math.max(1, runner.maxConcurrency) * 100)}%` }"></i>
            </div>
            <div v-if="runner.configuredMaxConcurrency != null" class="runner-config-hint">
              <i class="ri-settings-3-line"></i>后台已配置为 {{ runner.configuredMaxConcurrency }} 线程
            </div>
            <div v-if="runner.updateRequested" class="runner-script-hint pending">
              <i class="ri-download-cloud-2-line"></i>
              {{ runner.scriptUpToDate == null
                ? '已下发更新；旧版 Runner 不支持自动更新，需手动替换一次'
                : '更新已下发，等该电脑空闲后自动重启生效' }}
            </div>
            <div v-else-if="runner.scriptUpToDate === false" class="runner-script-hint stale">
              <i class="ri-refresh-line"></i>Runner 脚本落后于服务端版本，可点上方「更新 Runner 脚本」
            </div>
            <div v-if="!runner.online" class="wake-panel">
                <button class="wake-btn" :disabled="wakingRunnerId === runner.id || !runner.wakeConfigured" @click="wakeRunner(runner)">
                  <i :class="wakingRunnerId === runner.id ? 'ri-loader-4-line spin' : 'ri-shut-down-line'"></i>
                  {{ wakingRunnerId === runner.id ? '正在发送唤醒包' : '远程唤醒电脑' }}
                </button>
                <small v-if="!runner.wakeConfigured">首次在线时会自动准备，无需手动填写网络参数</small>
                <small v-if="runner.lastWakeRequestedAt">上次请求 {{ timeText(runner.lastWakeRequestedAt) }}，是否上线以心跳为准</small>
            </div>
            <ul v-if="runsOnRunner(runner.id).length" class="runner-tasks">
              <li v-for="run in runsOnRunner(runner.id)" :key="run.id">
                <i :class="engineIcon(run.engine)" :style="{ color: engineColor(run.engine) }"></i>
                <button @click="inspectRun(run)">{{ run.taskTitle }}</button>
              </li>
            </ul>
            <div v-else class="runner-idle">当前空闲</div>
          </article>
        </div>
      </section>
    </template>

    <template v-else>
      <section class="run-section">
        <div class="section-head">
          <div>
            <h2>AI 工作流</h2>
            <p>每个项目一条工作流：仓库、基线分支、默认 AI 工具、固定电脑和并发上限。</p>
          </div>
          <button class="pair-btn ghost" :disabled="workflowsLoading" @click="refreshWorkflows">
            <i :class="workflowsLoading ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>刷新
          </button>
        </div>
        <div v-if="workflowsLoading && !workflows.length" class="empty"><i class="ri-loader-4-line spin"></i>正在读取工作流…</div>
        <div v-else class="workflow-list">
          <article v-for="workflow in workflows" :key="workflow.projectId" class="workflow-card" :class="{ off: !workflow.config.enabled }">
            <header>
              <span class="flow-state" :class="{ on: workflow.config.enabled }">
                {{ workflow.config.enabled ? '已开启' : '未接入' }}
              </span>
              <b>{{ workflow.projectName }}</b>
              <span
                class="engine-tag"
                :style="{ color: engineColor(workflow.config.defaultEngine) }"
              >
                <i :class="engineIcon(workflow.config.defaultEngine)"></i>{{ engineLabel(workflow.config.defaultEngine) }}
              </span>
              <div class="spacer"></div>
              <button class="link-btn" @click="editingProjectId === workflow.projectId ? editingProjectId = null : startEditing(workflow)">
                {{ editingProjectId === workflow.projectId ? '收起' : workflow.config.enabled ? '编辑' : '接入' }}
              </button>
            </header>
            <dl class="flow-meta">
              <div><dt>仓库</dt><dd>{{ workflow.config.repoUrl || '未填写' }}</dd></div>
              <div><dt>基线分支</dt><dd>{{ workflow.config.defaultBranch }}</dd></div>
              <div><dt>固定电脑</dt><dd>{{ workflow.preferredRunnerName || '自动分配' }}</dd></div>
              <div><dt>项目并发</dt><dd>{{ workflow.config.maxParallelRuns }}</dd></div>
            </dl>
            <div class="flow-stats">
              <span><i class="ri-loader-4-line"></i>进行中 {{ workflow.activeRuns }}</span>
              <span><i class="ri-checkbox-circle-line"></i>成功 {{ workflow.succeededRuns }}</span>
              <span><i class="ri-error-warning-line"></i>失败 {{ workflow.failedRuns }}</span>
              <span class="run-time">{{ workflow.lastRunAt ? `最近运行 ${timeText(workflow.lastRunAt)}` : '还没有跑过' }}</span>
            </div>

            <div v-if="editingProjectId === workflow.projectId" class="flow-form">
              <label class="switch-row">
                <input v-model="form.enabled" type="checkbox" />
                <span>允许这个项目触发 AI Run</span>
              </label>
              <label class="form-field wide">
                <span>Git 仓库</span>
                <input v-model="form.repoUrl" placeholder="https://github.com/组织/仓库.git" />
              </label>
              <label class="form-field">
                <span>基线分支</span>
                <input v-model="form.defaultBranch" placeholder="main" />
              </label>
              <label class="form-field">
                <span>本机工程文件夹</span>
                <input v-model="form.workspaceDir" placeholder="wao" />
              </label>
              <label class="form-field">
                <span>默认 AI 工具</span>
                <select v-model="form.defaultEngine">
                  <option v-for="engine in AI_ENGINES" :key="engine.id" :value="engine.id">{{ engine.label }}</option>
                </select>
              </label>
              <label class="form-field">
                <span>固定执行电脑</span>
                <select v-model="form.preferredRunnerId">
                  <option value="">自动分配在线电脑</option>
                  <option v-for="runner in runnersFor(form.defaultEngine)" :key="runner.id" :value="runner.id">
                    {{ runner.name }}{{ runner.online ? ' · 在线' : ' · 离线' }}
                  </option>
                </select>
              </label>
              <label class="form-field">
                <span>同项目最大并发</span>
                <input v-model.number="form.maxParallelRuns" type="number" min="1" max="10" />
              </label>
              <div class="flow-actions">
                <button class="secondary" @click="editingProjectId = null">取消</button>
                <button class="primary" :disabled="saving" @click="saveWorkflow(workflow.projectId)">
                  {{ saving ? '保存中…' : '保存工作流' }}
                </button>
              </div>
            </div>
          </article>
        </div>
      </section>
    </template>

    <div v-if="selectedRun" class="run-detail-mask" @click.self="selectedRun = null">
      <section class="run-detail" role="dialog" aria-modal="true" aria-labelledby="run-detail-title">
        <header>
          <span class="recent-icon" :style="{ color: metaFor(selectedRun).color, background: metaFor(selectedRun).bg }">
            <i :class="metaFor(selectedRun).icon"></i>
          </span>
          <div>
            <h2 id="run-detail-title">{{ metaFor(selectedRun).label }}</h2>
            <p>{{ selectedRun.taskTitle }}</p>
          </div>
          <button class="detail-close" aria-label="关闭运行详情" @click="selectedRun = null"><i class="ri-close-line"></i></button>
        </header>
        <dl>
          <div><dt>AI 工具</dt><dd>{{ engineLabel(selectedRun.engine) }}</dd></div>
          <div><dt>执行电脑</dt><dd>{{ selectedRun.runnerName || '未分配电脑' }}</dd></div>
          <div><dt>触发人</dt><dd>{{ selectedRun.requestedBy || '系统未记录' }}</dd></div>
          <div><dt>执行时长</dt><dd>{{ durationText(selectedRun.startedAt, selectedRun.finishedAt) }}</dd></div>
          <div><dt>退出码</dt><dd>{{ selectedRun.exitCode ?? '—' }}</dd></div>
          <div><dt>{{ selectedRun.finishedAt ? '结束时间' : '最近反馈' }}</dt><dd>{{ timeText(selectedRun.finishedAt || selectedRun.progressUpdatedAt) || '等待中' }}</dd></div>
        </dl>
        <div class="result-title">本次消耗</div>
        <div v-if="selectedRun.usage?.recorded" class="usage-grid">
          <div><dt>模型</dt><dd :title="selectedRun.usage.models || ''">{{ shortModel(selectedRun.usage.model) || '未回传' }}</dd></div>
          <div><dt>合计 token</dt><dd>{{ formatTokens(selectedRun.usage.totalTokens) }}</dd></div>
          <div><dt>输入 / 输出</dt><dd>{{ formatTokens(selectedRun.usage.inputTokens) }} / {{ formatTokens(selectedRun.usage.outputTokens) }}</dd></div>
          <div><dt>缓存命中</dt><dd>{{ formatTokens(selectedRun.usage.cachedInputTokens) }}</dd></div>
          <div><dt>折算额度</dt><dd>{{ formatCost(selectedRun.usage.costUsd) || '该工具未回传' }}</dd></div>
        </div>
        <p v-else class="usage-empty">
          {{ ['queued', 'claimed', 'running'].includes(selectedRun.status) ? '运行结束后会记下本次消耗。' : '这次运行没有记上账（执行节点未回传用量）。' }}
        </p>
        <div class="result-title result-gap">AI 执行记录</div>
        <AiRunProgressTimeline :run="selectedRun" />
        <template v-if="selectedRun.resultSummary">
          <div class="result-title result-gap">{{ resultTitle(selectedRun) }}</div>
          <pre>{{ selectedRun.resultSummary }}</pre>
        </template>
        <footer>
          <span v-if="selectedRun.retryOfRunId" class="retry-note">
            <i class="ri-corner-down-right-line"></i>本次是基于上一次报错的重跑
          </span>
          <button class="secondary" @click="selectedRun = null">关闭</button>
          <button
            v-if="selectedRun.status === 'failed'"
            class="secondary retry"
            :disabled="rerunning"
            @click="rerun(selectedRun)"
          >
            <i :class="rerunning ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i>基于报错重跑
          </button>
          <button class="primary" @click="openRun(selectedRun)">打开对应任务</button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.home {
  min-height: 100%;
  padding: 24px 28px 60px;
  background: var(--wao-bg-page);
}
.hero {
  min-height: 170px;
  padding: 28px 30px;
  border-radius: 18px;
  background:
    radial-gradient(circle at 85% 20%, rgba(136, 153, 255, .35), transparent 35%),
    linear-gradient(135deg, #202b69, #4358d6);
  color: #fff;
  display: flex;
  align-items: center;
  gap: 24px;
  box-shadow: 0 14px 35px rgba(43, 59, 141, .2);
}
.hero > div:first-child { flex: 1; }
.eyebrow { font-size: 10px; letter-spacing: 2px; opacity: .65; }
.hero h1 { margin: 8px 0 6px; font-size: 28px; }
.hero p { margin: 0; font-size: 13px; opacity: .72; }
.engine-status { margin-top: 14px; display: flex; flex-wrap: wrap; gap: 8px; }
.engine-status span {
  padding: 5px 10px;
  border: 1px solid #ffffff2e;
  border-radius: 8px;
  background: #ffffff14;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.engine-status span.dim { opacity: .55; }
.engine-status b { font-weight: 600; opacity: .8; }
.capacity {
  position: relative;
  width: 185px;
  padding: 18px;
  border: 1px solid #ffffff2b;
  border-radius: 14px;
  background: #ffffff12;
  font-size: 11px;
  color: #ffffffa8;
  display: flex;
  align-items: center;
  gap: 12px;
  overflow: hidden;
}
.capacity-orbit { position: relative; width: 48px; height: 48px; flex: 0 0 auto; border: 2px solid #ffffff32; border-radius: 50%; }
.capacity-orbit::before, .capacity-orbit::after { content: ''; position: absolute; border: 1px solid #ffffff4a; border-radius: 50%; animation: orbit 4s linear infinite; }
.capacity-orbit::before { inset: 7px -8px; transform: rotate(58deg); }
.capacity-orbit::after { inset: -8px 7px; animation-duration: 6s; animation-direction: reverse; }
.capacity-orbit i { position: absolute; inset: 8px; border-radius: 50%; background: conic-gradient(#fff var(--capacity), #ffffff22 0); mask: radial-gradient(transparent 58%, #000 60%); }
.capacity-orbit b { position: absolute; left: 50%; top: 50%; width: 7px; height: 7px; border-radius: 50%; background: #fff; transform: translate(-50%, -50%); box-shadow: 0 0 12px #fff; animation: corePulse 1.8s ease-in-out infinite; }
.capacity-orbit em { position: absolute; right: -3px; top: 6px; width: 5px; height: 5px; border-radius: 50%; background: #aef5d0; box-shadow: 0 0 9px #aef5d0; }
.capacity-copy { min-width: 0; flex: 1; }
.capacity-num { color: #fff; font-size: 30px; font-weight: 750; }
.capacity-num span { font-size: 14px; opacity: .6; }
.capacity-bar { height: 5px; margin-top: 11px; overflow: hidden; border-radius: 9px; background: #ffffff24; }
.capacity-bar i { display: block; height: 100%; width: var(--capacity); border-radius: inherit; background: #fff; transition: width .3s; }
@keyframes orbit { to { transform: rotate(418deg); } }
@keyframes corePulse { 50% { transform: translate(-50%, -50%) scale(.68); opacity: .55; } }
.console-tabs {
  margin-top: 18px;
  padding: 5px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: #fff;
  display: flex;
  align-items: center;
  gap: 4px;
}
.console-tabs button {
  padding: 8px 14px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--wao-text-2);
  cursor: pointer;
  font-size: 12.5px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.console-tabs button:hover { background: #f4f5fa; }
.console-tabs button.on { background: #eef1ff; color: #3650b4; font-weight: 650; }
.console-tabs em { padding: 1px 6px; border-radius: 20px; background: #e3e7f6; color: #4358d6; font-size: 10px; font-style: normal; }
.console-tabs .live { margin-left: auto; padding-right: 8px; }
.section-title { margin-right: 4px; font-size: 12px; font-weight: 650; color: var(--wao-text-2); }
.online-dot { width: 7px; height: 7px; border-radius: 50%; background: #31a56a; box-shadow: 0 0 0 3px #dff5e9; }
.offline .online-dot { background: #9ca1ad; box-shadow: none; }
.run-section, .recent-section { margin-top: 22px; }
.section-head { display: flex; align-items: flex-end; gap: 8px; margin-bottom: 12px; }
.section-head > div { flex: 1; }
.section-head h2 { margin: 0; font-size: 17px; }
.section-head p { margin: 4px 0 0; color: var(--wao-text-weak); font-size: 11.5px; }
.live { color: var(--wao-text-weak); font-size: 10.5px; }
.live i { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: #35a86e; }
.run-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: 12px; }
.run-card {
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 13px;
  background: #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: .16s;
}
.run-card:hover { transform: translateY(-2px); border-color: #bec7ef; box-shadow: 0 8px 22px #27366c14; }
.run-top, .run-foot { display: flex; align-items: center; gap: 8px; }
.status { padding: 4px 7px; border-radius: 6px; font-size: 10.5px; font-weight: 650; }
.run-time { margin-left: auto; color: var(--wao-text-weak); font-size: 10.5px; }
.run-card h3 { min-height: 40px; margin: 14px 0 5px; font-size: 14px; line-height: 1.45; }
.run-project { color: var(--wao-text-weak); font-size: 11.5px; }
.run-project i { margin-right: 5px; }
.live-progress { margin-top: 13px; padding: 10px; border-radius: 9px; background: #f7f8fd; display: flex; gap: 9px; align-items: flex-start; }
.live-progress > div { min-width: 0; }
.live-progress b, .live-progress small { display: block; }
.live-progress b { overflow: hidden; color: #41485c; font-size: 11px; font-weight: 550; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.live-progress small { margin-top: 3px; color: #969bac; font-size: 9.5px; }
.live-pulse { width: 8px; height: 8px; margin-top: 4px; flex: 0 0 auto; border-radius: 50%; background: #36a86f; box-shadow: 0 0 0 4px #dff5e9; animation: livePulse 1.5s infinite; }
.run-foot { margin-top: 15px; padding-top: 11px; border-top: 1px solid #f0f1f5; color: var(--wao-text-3); font-size: 11px; }
.run-foot .foot-runner { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-foot span i { margin-right: 5px; }
.engine-tag { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 600; }
.engine-tag.boxed { padding: 3px 8px; border: 1px solid #e2e5f0; border-radius: 7px; }
.empty {
  min-height: 150px;
  border: 1px dashed #d6d9e5;
  border-radius: 13px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  font-size: 12px;
}
.empty.ready { flex-direction: column; gap: 5px; }
.empty.ready > i { margin-bottom: 4px; color: #7d8ddd; font-size: 26px; }
.empty.ready b { color: var(--wao-text-2); font-size: 13px; }
.runner-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.runner-card { padding: 16px; border: 1px solid var(--wao-line); border-radius: 13px; background: #fff; }
.runner-card.offline { background: #fbfbfc; opacity: .8; }
.runner-card header { display: flex; align-items: center; gap: 8px; }
.runner-card header b { font-size: 13.5px; }
.runner-state { margin-left: auto; color: var(--wao-text-weak); font-size: 10.5px; }
.runner-platform { margin-top: 8px; color: var(--wao-text-weak); font-size: 11px; }
.runner-platform i { margin-right: 5px; }
.engine-tags { margin-top: 11px; display: flex; flex-wrap: wrap; gap: 6px; }
.slot-line { margin-top: 13px; display: flex; align-items: center; color: var(--wao-text-3); font-size: 11px; }
.slot-bar { height: 5px; margin-top: 6px; overflow: hidden; border-radius: 9px; background: #eef0f6; }
.slot-bar i { display: block; height: 100%; border-radius: inherit; background: #4358d6; transition: width .3s; }
.runner-tasks { margin: 12px 0 0; padding: 11px 0 0; border-top: 1px solid #f0f1f5; list-style: none; }
.runner-tasks li { display: flex; align-items: center; gap: 7px; margin-top: 6px; font-size: 11.5px; }
.runner-tasks li:first-child { margin-top: 0; }
.runner-tasks button { flex: 1; padding: 0; border: 0; overflow: hidden; background: none; color: var(--wao-text-2); cursor: pointer; font-size: 11.5px; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.runner-tasks button:hover { color: #4358d6; text-decoration: underline; }
.runner-idle { margin-top: 12px; padding-top: 11px; border-top: 1px solid #f0f1f5; color: var(--wao-text-weak); font-size: 11px; }
.concurrency-edit { display: inline-flex; align-items: center; gap: 5px; }
.concurrency-edit input {
  width: 42px;
  padding: 3px 6px;
  border: 1px solid var(--wao-line);
  border-radius: 6px;
  text-align: center;
  font-size: 11px;
}
.icon-btn {
  padding: 3px 5px;
  border: 1px solid #4358d6;
  border-radius: 6px;
  background: #4358d6;
  color: #fff;
  cursor: pointer;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-btn:disabled { opacity: .6; cursor: not-allowed; }
.icon-btn.secondary { border-color: var(--wao-line); background: #fff; color: var(--wao-text-2); }
.icon-btn.secondary:hover { border-color: #bec7ef; color: #4358d6; }
.runner-config-hint {
  margin-top: 7px;
  color: #237a4b;
  font-size: 10.5px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.runner-script-hint {
  margin-top: 7px;
  font-size: 10.5px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.runner-script-hint.pending { color: #8a5b0d; }
.runner-script-hint.stale { color: #b33a3a; }
.wake-panel { margin-top: 11px; padding-top: 11px; border-top: 1px solid #eceef4; display: flex; flex-wrap: wrap; align-items: center; gap: 7px; }
.wake-panel button { padding: 6px 9px; border: 1px solid #cbd3f7; border-radius: 7px; background: #f1f4ff; color: #3650b4; cursor: pointer; font-size: 11px; }
.wake-panel button:disabled { opacity: .6; cursor: wait; }
.wake-panel .wake-btn { border-color: #4358d6; background: #4358d6; color: #fff; font-weight: 650; }
.wake-panel small { flex-basis: 100%; color: var(--wao-text-weak); font-size: 10px; line-height: 1.5; }
.pair-btn {
  padding: 8px 13px;
  border: 1px solid #4358d6;
  border-radius: 9px;
  background: #4358d6;
  color: #fff;
  cursor: pointer;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.pair-btn.ghost { border-color: var(--wao-line); background: #fff; color: var(--wao-text-2); }
.pair-code { margin-bottom: 12px; padding: 13px 15px; border: 1px solid #dfe4f6; border-radius: 12px; background: #f7f8ff; }
.pair-code > div { display: flex; align-items: baseline; gap: 10px; }
.pair-code b { font-size: 19px; font-weight: 700; letter-spacing: 3px; color: #2f3f9e; }
.pair-code small { color: var(--wao-text-weak); font-size: 11px; }
.pair-code code { display: block; margin-top: 9px; overflow-x: auto; padding: 9px 11px; border-radius: 8px; background: #eceffb; color: #47506e; font: 11px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre; }
.workflow-list { display: grid; gap: 12px; }
.workflow-card { padding: 16px 18px; border: 1px solid var(--wao-line); border-radius: 13px; background: #fff; }
.workflow-card.off { background: #fbfbfc; }
.workflow-card header { display: flex; align-items: center; gap: 10px; }
.workflow-card header b { font-size: 14px; }
.workflow-card .spacer { flex: 1; }
.flow-state { padding: 3px 8px; border-radius: 6px; background: #f1f2f5; color: var(--wao-text-weak); font-size: 10.5px; font-weight: 650; }
.flow-state.on { background: #eaf8f0; color: #237a4b; }
.link-btn { padding: 5px 10px; border: 1px solid var(--wao-line); border-radius: 8px; background: #fff; color: var(--wao-text-2); cursor: pointer; font-size: 11.5px; }
.link-btn:hover { border-color: #bec7ef; color: #4358d6; }
.flow-meta { margin: 14px 0 0; display: grid; grid-template-columns: 2fr 1fr 1fr 1fr; gap: 10px; }
.flow-meta > div { min-width: 0; padding: 9px 11px; border-radius: 9px; background: #f6f7fa; }
.flow-meta dt { color: var(--wao-text-weak); font-size: 10px; }
.flow-meta dd { margin: 4px 0 0; overflow: hidden; font-size: 11.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.flow-stats { margin-top: 11px; display: flex; align-items: center; gap: 14px; color: var(--wao-text-3); font-size: 11px; }
.flow-stats i { margin-right: 4px; }
.flow-form { margin-top: 14px; padding-top: 14px; border-top: 1px solid #f0f1f5; display: grid; grid-template-columns: repeat(2, 1fr); gap: 11px; }
.switch-row { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; font-size: 12px; }
.form-field { display: flex; flex-direction: column; gap: 5px; font-size: 11px; color: var(--wao-text-weak); }
.form-field.wide { grid-column: 1 / -1; }
.form-field input, .form-field select {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: #fff;
  color: var(--wao-text-1);
  font-size: 12px;
}
.form-field input:focus, .form-field select:focus { border-color: #8f9ee6; outline: none; }
.flow-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 9px; }
.flow-actions button { padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 12px; }
.flow-actions .secondary { border: 1px solid var(--wao-line); background: #fff; }
.flow-actions .primary { border: 1px solid #4358d6; background: #4358d6; color: #fff; }
.flow-actions .primary:disabled { opacity: .6; cursor: not-allowed; }
.recent-list { overflow: hidden; border: 1px solid var(--wao-line); border-radius: 12px; background: #fff; }
.recent-list button {
  width: 100%;
  min-height: 58px;
  padding: 9px 14px;
  border: 0;
  border-bottom: 1px solid #f0f1f4;
  background: #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 11px;
}
.recent-list button:last-child { border-bottom: 0; }
.recent-list button:hover { background: #fafbff; }
.recent-icon { width: 30px; height: 30px; border-radius: 8px; display: grid; place-items: center; }
.recent-main { flex: 1; min-width: 0; }
.recent-main b, .recent-main em { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent-main b { font-size: 12.5px; }
.recent-main em { margin-top: 2px; color: var(--wao-text-weak); font-size: 10.5px; font-style: normal; }
.recent-main small { display: block; margin-top: 4px; overflow: hidden; color: #b33a3a; font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.recent-status { color: var(--wao-text-3); font-size: 11px; }
.run-detail-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  padding: 24px;
  background: rgba(20, 25, 45, .34);
  display: grid;
  place-items: center;
}
.run-detail {
  width: min(680px, 100%);
  max-height: min(720px, calc(100vh - 48px));
  overflow: auto;
  padding: 22px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 24px 70px rgba(26, 31, 61, .22);
}
.run-detail header { display: flex; align-items: center; gap: 12px; }
.run-detail header > div { flex: 1; min-width: 0; }
.run-detail h2 { margin: 0; font-size: 17px; }
.run-detail header p { margin: 3px 0 0; overflow: hidden; color: var(--wao-text-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.detail-close { width: 32px; height: 32px; border: 0; border-radius: 8px; background: #f4f5f8; cursor: pointer; }
.run-detail dl { margin: 18px 0; display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.run-detail dl > div { padding: 10px 12px; border-radius: 9px; background: #f6f7fa; }
.run-detail dt { color: var(--wao-text-weak); font-size: 10px; }
.run-detail dd { margin: 4px 0 0; font-size: 11.5px; font-weight: 600; }
.result-title { margin-bottom: 7px; font-size: 12px; font-weight: 650; }
.result-title.result-gap { margin-top: 16px; }
.usage-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(96px, 1fr)); gap: 8px; }
.usage-grid > div { min-width: 0; padding: 9px 11px; border: 1px solid #e6e9f4; border-radius: 9px; background: #fafbff; }
.usage-grid dt { color: var(--wao-text-weak); font-size: 10px; }
.usage-grid dd { margin: 4px 0 0; overflow: hidden; font-size: 11.5px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.usage-empty { margin: 0; padding: 11px; border: 1px dashed #dfe2ec; border-radius: 9px; color: var(--wao-text-weak); font-size: 11.5px; }
.recent-usage { color: var(--wao-text-3); }
.run-detail pre { max-height: 360px; margin: 0; overflow: auto; padding: 14px; border: 1px solid #ead3d3; border-radius: 10px; background: #fff8f8; color: #753030; font: 11.5px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
.run-detail footer { margin-top: 18px; display: flex; align-items: center; justify-content: flex-end; gap: 9px; }
.run-detail footer .retry-note { margin-right: auto; color: var(--wao-text-weak); font-size: 11px; }
.run-detail footer .retry-note i { margin-right: 4px; }
.run-detail footer button { padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 12px; }
.run-detail footer .retry { display: inline-flex; align-items: center; gap: 6px; color: #b33a3a; border-color: #ead3d3; }
.run-detail footer .retry:disabled { opacity: .6; cursor: not-allowed; }
.run-detail footer .secondary { border: 1px solid var(--wao-line); background: #fff; }
.run-detail footer .primary { border: 1px solid #4358d6; background: #4358d6; color: #fff; }
@keyframes livePulse { 50% { opacity: .45; box-shadow: 0 0 0 7px #dff5e955; } }
.spin { display: inline-block; animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .home { padding: 16px; }
  .hero { padding: 22px; flex-direction: column; align-items: stretch; }
  .capacity { width: auto; }
  .console-tabs { overflow-x: auto; }
  .flow-meta { grid-template-columns: 1fr; }
  .flow-form { grid-template-columns: 1fr; }
  .run-detail dl { grid-template-columns: 1fr; }
}
</style>
