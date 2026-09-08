<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchAiRunConversation } from '@/api/ai'
import type { AiRun, AiRunProgress } from '@/types'

const props = defineProps<{ run: AiRun }>()
const older = ref<AiRunProgress[]>([])
const loading = ref(false)
const error = ref('')
const exhausted = ref(false)
const items = computed(() => [...new Map([...older.value, ...props.run.progress]
  .map((item) => [item.id, item])).values()]
  .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id)))
watch(() => props.run.id, () => { older.value = []; exhausted.value = false; error.value = '' })

async function loadEarlier() {
  const runId = props.run.id
  loading.value = true
  error.value = ''
  try {
    const page = await fetchAiRunConversation(runId, items.value[0]?.id)
    if (props.run.id !== runId) return
    older.value = [...page.items, ...items.value]
    exhausted.value = !page.nextCursor
  } catch (cause) {
    if (props.run.id === runId) error.value = cause instanceof Error ? cause.message : '读取失败，请重试'
  } finally { loading.value = false }
}

function speaker(item: AiRunProgress) {
  if (item.kind === 'user' || /^(用户在执行中补充：|用户要求继续优化：|用户补充：)/.test(item.content)) return '你'
  return item.kind === 'message' ? 'AI' : item.kind === 'plan' ? '计划' : '运行状态'
}

function timeText(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function emptyText(run: AiRun) {
  if (run.latestProgress) return run.latestProgress
  if (['queued', 'claimed', 'running'].includes(run.status)) return '正在等待第一条 AI 反馈…'
  // 退出码 3：AI 结束了会话但没报告任务状态，不能笼统说成「执行失败」。
  if (run.status === 'failed' && run.exitCode === 3) return 'AI 结束了会话但未报告任务状态，请根据下方原始答复确认结果'
  return run.status === 'failed' ? '本次 AI 执行失败，详细原因见下方' : '本次 AI 执行已经结束，最终结果见下方'
}
</script>

<template>
  <div class="ai-progress" :class="{ active: ['queued', 'claimed', 'running'].includes(run.status) }">
    <button v-if="!exhausted && items.length >= 50" class="history-button" :disabled="loading" @click="loadEarlier">
      {{ loading ? '正在读取…' : error || '查看更早的对话' }}
    </button>
    <div v-if="!items.length" class="progress-empty">
      <i :class="['queued', 'claimed', 'running'].includes(run.status) ? 'ri-loader-4-line' : 'ri-history-line'"></i>{{ emptyText(run) }}
    </div>
    <ol v-else>
      <li v-for="item in items" :key="item.id" :class="[`kind-${item.kind}`, { 'from-user': speaker(item) === '你' }]">
        <span class="progress-dot"><i :class="item.kind === 'result' ? 'ri-check-line' : item.kind === 'goal' ? 'ri-focus-3-line' : 'ri-sparkling-2-line'"></i></span>
        <div><b class="speaker">{{ speaker(item) }}</b><p>{{ item.content }}</p><time>{{ timeText(item.createdAt) }}</time></div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.ai-progress { max-height: 330px; overflow: auto; padding: 13px 14px; border: 1px solid var(--wao-line); border-radius: 11px; background: var(--wao-bg-sub); }
.ai-progress ol { margin: 0; padding: 0; list-style: none; }
.history-button { width: 100%; margin-bottom: 14px; border: 1px solid var(--wao-line); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-2); padding: 7px; cursor: pointer; }
.speaker { display: block; margin-bottom: 4px; font-size: 10px; color: var(--wao-text-weak); }
.from-user > div { padding: 8px 10px; border-radius: 9px; background: var(--wao-primary-soft); }
.ai-progress li > div { min-width: 0; overflow-wrap: anywhere; }
.ai-progress li { position: relative; min-height: 42px; padding: 0 0 14px 30px; display: flex; }
.ai-progress li:not(:last-child)::after { content: ''; position: absolute; left: 10px; top: 22px; bottom: 0; width: 1px; background: var(--wao-line); }
.progress-dot { position: absolute; left: 0; top: 0; width: 21px; height: 21px; border-radius: 50%; background: var(--wao-primary-soft); color: var(--wao-primary); display: grid; place-items: center; font-size: 10px; }
.active li:last-child .progress-dot, .active .progress-empty i { animation: pulse 1.5s infinite; }
.kind-result .progress-dot { background: var(--wao-success-bg); color: var(--wao-success); }
.kind-goal .progress-dot { background: #e8ecff; color: #4358d6; }
.ai-progress p { margin: 0; color: var(--wao-text-2); font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
.ai-progress time { display: block; margin-top: 3px; color: var(--wao-text-weak); font-size: 10px; }
.progress-empty { color: var(--wao-text-3); font-size: 12px; display: flex; align-items: center; gap: 8px; }
.progress-empty i { color: var(--wao-primary); }
@keyframes pulse { 50% { opacity: .35; transform: scale(.88); } }
</style>
