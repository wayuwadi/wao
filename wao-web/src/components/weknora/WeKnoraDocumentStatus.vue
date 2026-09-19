<script setup lang="ts">
import type { DocumentSyncState } from '@/api/weknoraSync'
defineProps<{ state?: DocumentSyncState; error?: string; dirty?: boolean }>()
defineEmits<{ retry: [] }>()
const labels: Record<string, string> = {
  unconfigured: '尚未连接 WeKnora', excluded: '未纳入同步', pending: '待同步',
  processing: '文档处理中', processed: '文档已处理', failed: '同步需重试',
  unavailable: '文档不可访问',
}
</script>
<template>
  <span class="weknora-status" @dblclick.stop>
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
</style>
