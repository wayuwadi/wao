<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import AiRunProgressTimeline from '@/components/ai/AiRunProgressTimeline.vue'
import AiRunMemoryReferences from '@/components/ai/AiRunMemoryReferences.vue'
import AiRunSkillCapabilities from '@/components/ai/AiRunSkillCapabilities.vue'
import { continueAiRun } from '@/api/ai'
import { toast } from '@/composables/toast'
import { formatCost, formatTokens, reasoningEffortLabel, shortModel } from '@/composables/aiUsage'
import { engineIcon, engineLabel } from '@/composables/aiEngines'
import { useNow } from '@/composables/now'
import { absoluteTime, relativeTime } from '@/utils/time'
import type { AiRun, AiRunStatus } from '@/types'

// 继续输入才需要 ProseMirror；档案本身不能把编辑器重新压进任务抽屉的首屏包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))

const props = withDefaults(defineProps<{
  runs: AiRun[]
  loading?: boolean
  loaded?: boolean
  requestedRunId?: string
  showScope?: boolean
}>(), {
  loading: false,
  loaded: false,
  requestedRunId: '',
  showScope: true,
})

const emit = defineEmits<{
  refresh: []
  uploadImage: [file: File, done: (url: string | null, name: string) => void]
}>()

const continuation = ref<Record<string, string>>({})
const continuingRunId = ref<string | null>(null)
const now = useNow()
const activeRun = computed(() => props.runs.find((run) => ['queued', 'claimed', 'running'].includes(run.status)))

const runLabel: Record<AiRunStatus, string> = {
  queued: '等待领取', claimed: '准备中', running: '正在运行', succeeded: '等待验收',
  failed: '执行失败', cancelled: '已取消',
}

function statusUnreported(run: AiRun) {
  return run.status === 'failed' && run.exitCode === 3
}

function stateLabel(run: AiRun) {
  return statusUnreported(run) ? '结果待确认' : runLabel[run.status]
}

function resultTitle(run: AiRun) {
  if (statusUnreported(run)) return '结果待确认（AI 未报告任务状态）'
  return run.status === 'failed' ? '失败原因' : '最终结果'
}

async function continueRun(runId: string) {
  const input = continuation.value[runId]?.trim()
  if (!input) return toast('请先输入要补充给 AI 的信息', 'ri-information-line')
  const targetRun = props.runs.find((run) => run.id === runId)
  const optimizing = targetRun?.status === 'succeeded'
  const active = !!targetRun && ['queued', 'claimed', 'running'].includes(targetRun.status)
  continuingRunId.value = runId
  try {
    await continueAiRun(runId, input)
    continuation.value[runId] = ''
    toast(active ? '补充已发送，执行电脑接收后会立即调整当前 AI 会话方向'
      : optimizing ? '已发送，AI 将带着这次的记忆继续优化' : '补充已发送，AI 将在原会话中继续执行',
    'ri-robot-2-line')
    emit('refresh')
  } catch (error) {
    toast(error instanceof Error ? error.message : '发送补充失败，请重试', 'ri-error-warning-line')
  } finally {
    continuingRunId.value = null
  }
}
</script>

<template>
  <div v-if="runs.length || (loading && !loaded)" class="ai-archive">
    <div class="ai-archive-title">
      <span>AI 执行记录</span>
      <em v-if="activeRun"><i></i>实时更新中</em>
    </div>
    <div v-if="loading && !runs.length" class="ai-archive-loading"><i class="ri-loader-4-line"></i>正在读取 AI 记录…</div>
    <details v-for="(run, index) in runs" v-else :key="run.id" class="ai-run-entry" :open="run.id === requestedRunId || index === 0">
      <summary>
        <span class="ai-run-state" :class="statusUnreported(run) ? 'state-unconfirmed' : `state-${run.status}`">{{ stateLabel(run) }}</span>
        <span class="ai-run-engine" :title="`执行工具：${engineLabel(run.engine)}`"><i :class="engineIcon(run.engine)"></i></span>
        <span v-if="showScope && run.subId" class="ai-run-scope" :title="`只跑子任务：${run.subTitle ?? run.subId}`">
          子任务 · {{ run.subTitle ?? run.subId }}
        </span>
        <span class="ai-run-latest">{{ run.latestProgress || run.resultSummary || '暂无执行摘要' }}</span>
        <time :title="absoluteTime(run.startedAt || run.createdAt)">{{ relativeTime(run.startedAt || run.createdAt, now) }}</time>
        <i class="ri-arrow-down-s-line"></i>
      </summary>
      <div class="ai-run-body">
        <div v-if="run.requestedModel || run.planMode" class="ai-run-parameters">
          <i class="ri-cpu-line"></i>
          <span v-if="run.requestedModel" class="usage-model">{{ shortModel(run.requestedModel) }}</span>
          <span v-if="run.reasoningEffort">推理{{ reasoningEffortLabel(run.reasoningEffort) }}</span>
          <span v-if="run.serviceTier">{{ run.serviceTier === 'priority' ? 'Fast' : '标准速度（Fast 未开启）' }}</span>
          <span v-if="run.planMode"><i class="ri-route-line"></i>Plan 模式</span>
        </div>
        <div v-if="run.usage?.recorded" class="ai-run-usage" :title="run.usage.models || ''">
          <i class="ri-copper-coin-line"></i>
          <span v-if="shortModel(run.usage.model)" class="usage-model">{{ shortModel(run.usage.model) }}</span>
          <span class="usage-part">输入 <b>{{ formatTokens(run.usage.inputTokens) }}</b></span>
          <span class="usage-part">输出 <b>{{ formatTokens(run.usage.outputTokens) }}</b></span>
          <span class="usage-part">缓存 <b>{{ formatTokens(run.usage.cachedInputTokens) }}</b></span>
          <span class="usage-part">合计 <b>{{ formatTokens(run.usage.totalTokens) }}</b></span>
          <span v-if="formatCost(run.usage.costUsd)" class="usage-part">{{ formatCost(run.usage.costUsd) }}</span>
        </div>
        <AiRunProgressTimeline :run="run" />
        <AiRunMemoryReferences :reads="run.memoryReads" :writes="run.memoryWrites" />
        <AiRunSkillCapabilities :skills="run.skills" :audits="run.skillAudits" />
        <div v-if="run.resultSummary" class="ai-final-result">
          <b>{{ resultTitle(run) }}</b>
          <p>{{ run.resultSummary }}</p>
        </div>
        <a v-if="run.reportUrl" class="ai-run-report" :href="run.reportUrl" target="_blank" rel="noopener">
          <span class="ai-run-report-icon"><i class="ri-file-chart-line"></i></span>
          <span><b>持久化报告</b><small>完整前因后果与验收证据，可长期查看和分享</small></span>
          <em>查看报告<i class="ri-arrow-right-up-line"></i></em>
        </a>
        <div
          v-if="['queued', 'claimed', 'running'].includes(run.status)
            || (['failed', 'succeeded'].includes(run.status) && run.sessionId && run.runnerId)"
          class="ai-continue"
          :class="{ pending: run.continuationPending }"
        >
          <div class="ai-continue-head">
            <span class="ai-continue-icon"><i class="ri-chat-1-line"></i></span>
            <div>
              <b>{{ ['queued', 'claimed', 'running'].includes(run.status) ? '执行中继续输入' : run.status === 'succeeded' ? '继续优化这次结果' : '在原会话中补充信息' }}</b>
              <small v-if="run.continuationPending">补充已保存，正在等待 {{ run.runnerName || '执行电脑' }} 接收</small>
              <small v-else-if="['queued', 'claimed', 'running'].includes(run.status)">发送后加入当前线程，{{ run.runnerName || '执行电脑' }} 会据此调整方向</small>
              <small v-else>将固定回到 {{ run.runnerName || '原执行电脑' }}，保留本次上下文</small>
            </div>
          </div>
          <div class="ai-continue-input">
            <MarkdownEditor
              :model-value="continuation[run.id] ?? ''"
              :min-height="56"
              :readonly="!!run.continuationPending"
              :placeholder="run.continuationPending
                ? '上一条补充正在投递…'
                : ['queued', 'claimed', 'running'].includes(run.status)
                  ? '继续说你的想法，AI 会在当前线程中调整方向；截图可直接粘贴'
                  : run.status === 'succeeded'
                  ? '跑完了但不符合预期？写下要优化的地方，AI 带着这次的记忆接着改；截图可直接粘贴'
                  : '补充信息后，让 AI 在原会话中继续执行；截图可直接粘贴'"
              @update:model-value="(value: string) => (continuation[run.id] = value)"
              @upload-image="(file, done) => emit('uploadImage', file, done)"
            />
          </div>
          <button :disabled="run.continuationPending || continuingRunId === run.id || !continuation[run.id]?.trim()" @click="continueRun(run.id)">
            <i :class="continuingRunId === run.id || run.continuationPending ? 'ri-loader-4-line spin' : 'ri-send-plane-2-line'"></i>{{ run.continuationPending ? '等待接收…' : continuingRunId === run.id ? '发送中…' : ['queued', 'claimed', 'running'].includes(run.status) ? '发送到当前线程' : run.status === 'succeeded' ? '继续优化' : '继续对话' }}
          </button>
        </div>
      </div>
    </details>
  </div>
</template>

<style scoped>
.ai-archive { margin-top: 18px; padding-top: 14px; border-top: 1px solid #f0f0f3; }
.ai-archive-title { margin-bottom: 4px; color: var(--wao-text-weak); font-size: 11px; letter-spacing: 1.5px; display: flex; align-items: center; }
.ai-archive-title span { flex: 1; }
.ai-archive-title em { color: #278159; font-size: 10.5px; font-style: normal; letter-spacing: 0; }
.ai-archive-title em i { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: #35a86e; animation: aiLive 1.5s infinite; }
.ai-archive-loading { padding: 12px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 12px; }
.ai-run-entry { margin-top: 7px; overflow: hidden; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); }
.ai-run-entry summary { min-height: 44px; padding: 8px 10px; cursor: pointer; list-style: none; display: flex; align-items: center; gap: 8px; }
.ai-run-entry summary::-webkit-details-marker { display: none; }
.ai-run-entry summary > i { color: var(--wao-text-weak); transition: transform .15s; }
.ai-run-entry[open] summary > i { transform: rotate(180deg); }
.ai-run-state { flex: 0 0 auto; padding: 3px 7px; border-radius: 5px; background: #eef1ff; color: #4056c5; font-size: 10px; }
.ai-run-state.state-succeeded { background: #e8f7ef; color: #237a4b; }
.ai-run-state.state-failed { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.ai-run-state.state-unconfirmed { background: var(--wao-warning-bg); color: var(--wao-warning-strong); }
.ai-run-scope { flex: 0 0 auto; max-width: 130px; overflow: hidden; padding: 3px 7px; border-radius: 5px; background: var(--wao-bg-tab); color: var(--wao-area-finance); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.ai-run-latest { flex: 1; min-width: 0; overflow: hidden; color: var(--wao-text-2); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.ai-run-entry summary time { flex: 0 0 auto; color: var(--wao-text-faint); font-size: 10px; }
.ai-run-body { padding: 0 10px 10px; }
.ai-run-parameters, .ai-run-usage { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; margin-bottom: 8px; padding: 6px 9px; border-radius: 7px; background: var(--wao-bg-sub); color: var(--wao-text-3); font-size: 10.5px; }
.ai-run-parameters i, .ai-run-usage i { margin-right: -3px; color: #a8862f; }
.ai-run-parameters .usage-model, .ai-run-usage .usage-model { color: var(--wao-text-2); font-weight: 600; }
.ai-run-usage .usage-part b { font-weight: 600; color: var(--wao-text-2); }
.ai-final-result { margin-top: 10px; padding: 10px 12px; border-radius: 9px; background: var(--wao-bg-sub); }
.ai-final-result b { font-size: 11px; }
.ai-final-result p { max-height: 180px; margin: 5px 0 0; overflow: auto; color: var(--wao-text-3); font-size: 11px; line-height: 1.65; white-space: pre-wrap; }
.ai-run-report { margin-top: 10px; padding: 10px 12px; border: 1px solid #cfe8dc; border-radius: 10px; background: linear-gradient(135deg, #f0faf5, #fff); color: inherit; text-decoration: none; display: flex; align-items: center; gap: 9px; }
.ai-run-report:hover { border-color: #89c9aa; box-shadow: 0 5px 16px rgba(27, 126, 83, .08); }
.ai-run-report-icon { width: 29px; height: 29px; flex: none; border-radius: 8px; background: #daf1e5; color: #16815c; display: grid; place-items: center; }
.ai-run-report > span:nth-child(2) { flex: 1; min-width: 0; }
.ai-run-report b, .ai-run-report small { display: block; }
.ai-run-report b { font-size: 11.5px; }
.ai-run-report small { margin-top: 2px; color: var(--wao-text-weak); font-size: 10px; }
.ai-run-report em { flex: none; color: #16815c; font-size: 10.5px; font-style: normal; font-weight: 600; }
.ai-run-report em i { margin-left: 3px; }
.ai-continue { margin-top: 10px; padding: 11px; border: 1px solid #dce2fa; border-radius: 10px; background: linear-gradient(135deg, #f7f8ff, #fff); }
.ai-continue.pending { border-color: #f0d89a; background: linear-gradient(135deg, #fffaf0, #fff); }
.ai-continue-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ai-continue-icon { width: 25px; height: 25px; border-radius: 8px; background: #e8ecff; color: #4358d6; display: grid; place-items: center; }
.ai-continue-head b, .ai-continue-head small { display: block; }
.ai-continue-head b { color: var(--wao-text); font-size: 11.5px; }
.ai-continue-head small { margin-top: 2px; color: var(--wao-text-weak); font-size: 10px; }
.ai-continue-input { box-sizing: border-box; width: 100%; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); }
.ai-continue-input:focus-within { border-color: var(--wao-primary); box-shadow: 0 0 0 3px rgba(67, 88, 214, .1); }
.ai-continue-input :deep(.md .md-body), .ai-continue-input :deep(.md:not(.ro) .md-body:hover), .ai-continue-input :deep(.md:not(.ro) .md-body:focus-within) { border-color: transparent; }
.ai-continue-input :deep(.md .tiptap) { padding: 8px 9px; font-size: 11.5px; line-height: 1.55; }
.ai-continue > button { float: right; margin-top: 8px; padding: 6px 10px; border: 0; border-radius: 7px; background: var(--wao-primary); color: #fff; cursor: pointer; font-size: 11px; display: inline-flex; align-items: center; gap: 5px; }
.ai-continue > button:disabled { cursor: not-allowed; opacity: .55; }
.ai-continue::after { content: ''; display: block; clear: both; }
@keyframes aiLive { 50% { opacity: .35; transform: scale(.85); } }
</style>
