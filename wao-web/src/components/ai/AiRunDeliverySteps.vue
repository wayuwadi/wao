<script setup lang="ts">
import { computed } from 'vue'
import type { AiRunDeliveryStage } from '@/types'

const props = defineProps<{ stage: AiRunDeliveryStage }>()

const steps = computed(() => {
  const deploymentDone = ['deployment_succeeded', 'deployment_failed'].includes(props.stage)
  const codeDone = props.stage === 'waiting_deployment' || deploymentDone
  return [
    { label: codeDone ? '代码完成' : props.stage === 'code_failed' ? '代码未完成' : '编写代码', state: codeDone ? 'done' : props.stage === 'code_failed' ? 'failed' : 'active' },
    { label: '等待部署', state: props.stage === 'waiting_deployment' ? 'active' : deploymentDone ? 'done' : 'pending' },
    { label: props.stage === 'deployment_failed' ? '部署失败' : props.stage === 'deployment_succeeded' ? '部署成功' : '部署结果', state: deploymentDone ? (props.stage === 'deployment_failed' ? 'failed' : 'done') : 'pending' },
  ]
})
</script>

<template>
  <ol class="delivery-steps" aria-label="代码与部署进度">
    <li v-for="step in steps" :key="step.label" :class="step.state">
      <i :class="step.state === 'done' ? 'ri-check-line' : step.state === 'failed' ? 'ri-close-line' : 'ri-more-line'"></i>
      <span>{{ step.label }}</span>
    </li>
  </ol>
</template>

<style scoped>
.delivery-steps { margin: 10px 0 8px; padding: 7px 9px; border-radius: 8px; background: var(--wao-bg-sub); list-style: none; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
.delivery-steps li { min-width: 0; color: var(--wao-text-weak); font-size: 10px; display: flex; align-items: center; gap: 4px; }
.delivery-steps li i { width: 16px; height: 16px; flex: none; border-radius: 50%; background: var(--wao-bg-hover-2); display: grid; place-items: center; }
.delivery-steps li.done { color: var(--wao-success-strong); }
.delivery-steps li.done i { background: var(--wao-success-bg); }
.delivery-steps li.active { color: var(--wao-warning-strong); font-weight: 600; }
.delivery-steps li.active i { background: var(--wao-warning-bg); }
.delivery-steps li.failed { color: var(--wao-danger-strong); }
.delivery-steps li.failed i { background: var(--wao-danger-bg); }
</style>
