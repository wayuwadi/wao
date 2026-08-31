<script setup lang="ts">
import { computed } from 'vue'
import { useFunctionHealth } from '@/composables/functionHealth'

const props = defineProps<{ featureId: string; label: string }>()
const health = useFunctionHealth()
const item = computed(() => health.byId.value[props.featureId])
const score = computed(() => item.value?.score ?? 1)
const saving = computed(() => health.savingFeature.value === props.featureId)

function change(delta: -1 | 1) {
  void health.adjust(props.featureId, delta).catch(() => undefined)
}
</script>

<template>
  <div class="score-control" :aria-label="`${label}功能评分`">
    <button
      type="button"
      :disabled="saving || score <= 1"
      title="人工校准 -1 分"
      @click="change(-1)"
    >
      −1
    </button>
    <span :title="item?.insight || '默认 1 分，后续功能提交自动加分'">
      <i class="ri-award-line"></i><b>{{ score }}</b
      ><small>/100</small>
    </span>
    <button
      type="button"
      :disabled="saving || score >= 100"
      title="人工校准 +1 分"
      @click="change(1)"
    >
      +1
    </button>
  </div>
</template>

<style scoped>
.score-control {
  height: 32px;
  display: flex;
  align-items: center;
  overflow: hidden;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-sm);
}
.score-control button {
  height: 100%;
  padding: 0 8px;
  border: 0;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
  font-size: 10px;
}
.score-control button:hover:not(:disabled) {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.score-control button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.score-control span {
  height: 100%;
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 0 9px;
  border-inline: 1px solid var(--wao-divider);
  color: var(--wao-primary-text);
}
.score-control i {
  font-size: 14px;
}
.score-control b {
  font-size: 13px;
}
.score-control small {
  font-size: 8px;
  color: var(--wao-text-faint);
}
@media (max-width: 760px) {
  .score-control button {
    padding: 0 6px;
  }
  .score-control span {
    padding: 0 7px;
  }
}
</style>
