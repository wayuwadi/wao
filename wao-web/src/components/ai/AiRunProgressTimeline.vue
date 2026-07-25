<script setup lang="ts">
import type { AiRun } from '@/types'

defineProps<{ run: AiRun }>()

function timeText(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
</script>

<template>
  <div class="ai-progress" :class="{ active: ['queued', 'claimed', 'running'].includes(run.status) }">
    <div v-if="!run.progress.length" class="progress-empty">
      <i class="ri-loader-4-line"></i>{{ run.latestProgress || '正在等待第一条 AI 反馈…' }}
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
