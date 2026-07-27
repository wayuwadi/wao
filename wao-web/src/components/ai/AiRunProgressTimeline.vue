<script setup lang="ts">
import type { AiRun } from '@/types'

defineProps<{ run: AiRun }>()

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
    <div v-if="!run.progress.length" class="progress-empty">
      <i :class="['queued', 'claimed', 'running'].includes(run.status) ? 'ri-loader-4-line' : 'ri-history-line'"></i>{{ emptyText(run) }}
    </div>
    <ol v-else>
      <li v-for="item in run.progress" :key="item.id" :class="`kind-${item.kind}`">
        <span class="progress-dot"><i :class="item.kind === 'result' ? 'ri-check-line' : 'ri-sparkling-2-line'"></i></span>
        <div><p>{{ item.content }}</p><time>{{ timeText(item.createdAt) }}</time></div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.ai-progress { max-height: 330px; overflow: auto; padding: 13px 14px; border: 1px solid #e4e7f2; border-radius: 11px; background: #f8f9fd; }
.ai-progress ol { margin: 0; padding: 0; list-style: none; }
.ai-progress li { position: relative; min-height: 42px; padding: 0 0 14px 30px; display: flex; }
.ai-progress li:not(:last-child)::after { content: ''; position: absolute; left: 10px; top: 22px; bottom: 0; width: 1px; background: #d9deef; }
.progress-dot { position: absolute; left: 0; top: 0; width: 21px; height: 21px; border-radius: 50%; background: #e9edff; color: #4358d6; display: grid; place-items: center; font-size: 10px; }
.active li:last-child .progress-dot, .active .progress-empty i { animation: pulse 1.5s infinite; }
.kind-result .progress-dot { background: #e5f7ed; color: #238052; }
.ai-progress p { margin: 0; color: #303545; font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
.ai-progress time { display: block; margin-top: 3px; color: #9a9ead; font-size: 10px; }
.progress-empty { color: #747b90; font-size: 12px; display: flex; align-items: center; gap: 8px; }
.progress-empty i { color: #4358d6; }
@keyframes pulse { 50% { opacity: .35; transform: scale(.88); } }
</style>
