<script setup lang="ts">
import { computed } from 'vue'
import type { DataDomain, DataLoadState } from '@/stores/data'
import { DATA_DOMAIN_LABELS } from '@/utils/pageDataDomains'
import WaoButton from './WaoButton.vue'

const props = defineProps<{
  states: Record<DataDomain, DataLoadState>
  errors: Partial<Record<DataDomain, string>>
  activeDomains: DataDomain[]
}>()
const emit = defineEmits<{ retry: [domain: DataDomain] }>()
const domains = Object.keys(DATA_DOMAIN_LABELS) as DataDomain[]
const currentErrors = computed(() => props.activeDomains.filter(domain => props.states[domain] === 'error'))
const otherErrors = computed(() => domains.filter(domain => !props.activeDomains.includes(domain) && props.states[domain] === 'error'))
const loading = computed(() => props.activeDomains.filter(domain => props.states[domain] === 'loading'))
</script>

<template>
  <div v-if="currentErrors.length || otherErrors.length || loading.length" class="data-load-status">
    <div v-for="domain in currentErrors" :key="domain" class="domain-error" role="alert">
      <i class="ri-error-warning-line" aria-hidden="true"></i>
      <span><strong>{{ DATA_DOMAIN_LABELS[domain] }}读取失败</strong><span class="domain-reason">{{ errors[domain] || '请检查连接后重试。' }}</span></span>
      <WaoButton variant="ghost" :aria-label="`重新读取${DATA_DOMAIN_LABELS[domain]}`" @click="emit('retry', domain)">重试</WaoButton>
    </div>
    <p v-if="loading.length" class="domain-loading" role="status">正在读取{{ loading.map(domain => DATA_DOMAIN_LABELS[domain]).join('、') }}…</p>
    <details v-if="otherErrors.length" class="other-errors">
      <summary>其他模块有 {{ otherErrors.length }} 项数据未读取成功</summary>
      <div v-for="domain in otherErrors" :key="domain" class="domain-error">
        <span><strong>{{ DATA_DOMAIN_LABELS[domain] }}</strong><span class="domain-reason">{{ errors[domain] || '读取失败，请重试。' }}</span></span>
        <WaoButton variant="ghost" :aria-label="`重新读取${DATA_DOMAIN_LABELS[domain]}`" @click="emit('retry', domain)">重试</WaoButton>
      </div>
    </details>
  </div>
</template>

<style scoped>
.data-load-status { padding: var(--wao-space-1) var(--wao-space-2); font-size: var(--wao-font-help); color: var(--wao-text-3); }
.domain-error { display: flex; align-items: center; flex-wrap: wrap; gap: var(--wao-space-1); color: var(--wao-danger-strong); }
.domain-error > span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.domain-reason { display: block; }
.domain-loading { margin: 0; }
.other-errors summary { cursor: pointer; padding-block: var(--wao-space-1); }
.other-errors .domain-error { padding-left: var(--wao-space-2); }
.domain-error :deep(button) { flex-shrink: 0; }
</style>
