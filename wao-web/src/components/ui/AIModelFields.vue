<script setup lang="ts">
import { ref } from 'vue'
import type { CodexModelCatalog } from '@/api/codexModels'
const props = withDefaults(
  defineProps<{
    model: string
    reasoningEffort: string
    catalog: CodexModelCatalog | null
    efforts: string[]
    error?: string
    loading?: boolean
    disabled?: boolean
    scope: string
    allowDefault?: boolean
    description: string
  }>(),
  { error: '', loading: false, disabled: false, allowDefault: false },
)
const emit = defineEmits<{
  'update:model': [value: string]
  'update:reasoningEffort': [value: string]
  refresh: []
}>()
const showCatalogStatus = ref(false)
function refresh() {
  showCatalogStatus.value = true
  emit('refresh')
}
const labels: Record<string, string> = {
  none: '无',
  minimal: '极低',
  low: '低',
  medium: '中',
  high: '高',
  xhigh: '极高',
  max: '最高',
  ultra: '超高',
}
</script>
<template>
  <section class="model-fields" aria-label="模型与思考深度">
    <div class="model-heading">
      <strong><i class="ri-cpu-line" aria-hidden="true"></i> AI 能力</strong><span>本机 Codex</span>
    </div>
    <div class="model-inputs">
      <label
        >处理模型<select
          :aria-label="`${scope}处理模型`"
          :value="model"
          :disabled="disabled"
          @change="emit('update:model', ($event.target as HTMLSelectElement).value)"
        >
          <option v-if="allowDefault" value="">沿用已配置的默认模型</option>
          <option v-if="model && !catalog?.models.some((item) => item.id === model)" :value="model">
            {{ model }} · 已保留，待确认可用
          </option>
          <option v-for="item in catalog?.models || []" :key="item.id" :value="item.id">
            {{ item.label }}
          </option>
        </select></label
      >
      <label
        >思考深度<select
          :aria-label="`${scope}思考深度`"
          :value="reasoningEffort"
          :disabled="disabled"
          @change="emit('update:reasoningEffort', ($event.target as HTMLSelectElement).value)"
        >
          <option v-if="!efforts.includes(reasoningEffort)" :value="reasoningEffort">
            {{ labels[reasoningEffort] || reasoningEffort }} ·
            {{ catalog ? '当前模型不支持' : '已保留，待确认' }}
          </option>
          <option v-for="effort in efforts" :key="effort" :value="effort">
            {{ labels[effort] || effort }}{{ effort === 'medium' ? ' · 默认' : '' }}
          </option>
        </select></label
      >
    </div>
    <div class="model-meta">
      <span>{{ description }}</span
      ><button type="button" :disabled="props.loading" @click="refresh">
        <i class="ri-refresh-line" aria-hidden="true"></i>
        {{ loading ? '读取模型目录中…' : '刷新模型目录' }}
      </button>
    </div>
    <p v-if="error" class="model-error" role="alert">{{ error }}</p>
    <p
      v-else-if="catalog?.message && (catalog.stale || showCatalogStatus)"
      class="model-status"
      role="status"
    >
      {{ catalog.message }}
    </p>
  </section>
</template>
<style scoped>
.model-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 14px;
  font-size: 14px;
}
.model-heading strong {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 600;
}
.model-heading strong i {
  font-size: 16px;
}
.model-heading > span {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 20px;
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
}
.model-inputs {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 16px;
}
label {
  display: grid;
  gap: 8px;
  font-size: 12px;
  color: var(--wao-text-3);
}
select {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  height: 40px;
  padding: 0 10px;
  font: inherit;
  font-size: 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
}
.model-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 11px;
  font-size: 12px;
  color: var(--wao-text-3);
}
button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  color: var(--wao-primary-text);
  font: inherit;
  font-size: 12px;
  padding: 3px 0;
  cursor: pointer;
}
p {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--wao-text-3);
}
.model-error {
  color: var(--wao-danger-strong);
}
@media (max-width: 650px) {
  .model-inputs {
    gap: 10px;
    grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  }
  select {
    font-size: 16px;
  }
  .model-heading {
    margin-bottom: 10px;
  }
}
</style>
