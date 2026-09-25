<script setup lang="ts">
import type { DocumentSyncState } from '@/api/weknoraSync'
defineProps<{ state?: DocumentSyncState; error?: string; dirty?: boolean; compact?: boolean }>()
defineEmits<{ retry: [] }>()
const labels: Record<string, string> = {
  unconfigured: '尚未连接 WeKnora', excluded: '未纳入同步', pending: '待同步',
  processing: '文档处理中', processed: '文档已处理', failed: '同步需重试',
  unavailable: '文档不可访问',
}
</script>
<template>
  <span v-if="compact" class="weknora-status compact" @dblclick.stop>
    <button v-if="error" type="button" :title="`${error}，点击重试`" :aria-label="`WeKnora 状态读取失败：${error}，点击重试`" @click.stop="$emit('retry')"><i class="ri-error-warning-line" aria-hidden="true"></i></button>
    <a v-else-if="state" :href="state.sourceUrl || '/apps/weknora?tab=sync'" :class="state.state" :title="`WeKnora · ${labels[state.state] || '状态待核对'}${state.message ? `：${state.message}` : ''}`" :aria-label="`WeKnora：${labels[state.state] || '状态待核对'}`" target="_blank" rel="noopener"><i :class="state.state === 'processed' ? 'ri-cloud-line' : state.state === 'processing' ? 'ri-loader-4-line' : state.state === 'failed' ? 'ri-error-warning-line' : 'ri-cloud-upload-line'" aria-hidden="true"></i></a>
    <span v-else role="status" aria-label="正在读取 WeKnora 同步状态" title="正在读取 WeKnora 同步状态"><i class="ri-loader-4-line" aria-hidden="true"></i></span>
  </span>
  <span v-else class="weknora-status" @dblclick.stop>
    <span v-if="dirty" role="status">WeKnora · 修改保存后同步</span>
    <template v-else-if="error"><span role="status">{{ error }}</span><button type="button" @click.stop="$emit('retry')">重试</button></template>
    <template v-else-if="state">
      <a :href="state.sourceUrl || '/apps/weknora?tab=sync'" :title="state.message" target="_blank" rel="noopener">WeKnora · {{ labels[state.state] || '状态待核对' }}</a>
      <a v-if="state.knowledgeUrl" :href="state.knowledgeUrl" title="进入目标知识库，核对 Wiki 并提问" target="_blank" rel="noopener">去提问 ↗</a>
    </template>
    <span v-else role="status">正在读取同步状态…</span>
  </span>
</template>
<style scoped>
.weknora-status { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 8px; color: var(--wao-text-faint); font-size: 11px; }
a, button { color: var(--wao-primary); font: inherit; text-decoration: none; }
button { border: 0; background: none; padding: 2px; cursor: pointer; }
.compact { display: inline-flex; width: 22px; height: 22px; flex: none; align-items: center; justify-content: center; }
.compact a, .compact button, .compact > span { display: inline-grid; place-items: center; width: 22px; height: 22px; padding: 0; font-size: 17px; }
.compact a.processed { color: var(--wao-success-strong); }
.compact a.failed, .compact button { color: var(--wao-danger-strong); }
.compact a.pending, .compact a.processing { color: var(--wao-warning); }
</style>
