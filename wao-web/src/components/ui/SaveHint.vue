<script setup lang="ts">
/**
 * 自动保存回执 —— 挂在每个自动保存的编辑区右上角。
 *
 * <p>任务描述、子任务描述、知识库文档都是「敲完就存」，没有保存按钮。没有回执时
 * 用户无从判断东西到底进没进服务器，所以统一显示「自动保存成功 14:32」，正在写透
 * 时显示「保存中…」，写透失败时显示红色的「未保存」并保留上一次成功的时间。
 */
import { computed } from 'vue'
import { useDataStore } from '@/stores/data'
import { absoluteTime, clockTime } from '@/utils/time'

const props = defineProps<{
  /** 保存状态登记簿的 key，如 `task:t17`、`doc:d3`。 */
  saveKey: string
  /** 有本地改动还没写透（去抖窗口内）；有它时即使请求还没发出也显示「保存中…」。 */
  pending?: boolean
}>()

const data = useDataStore()
const state = computed(() => data.saveState(props.saveKey))
const saving = computed(() => state.value.saving || props.pending === true)
const savedAt = computed(() => state.value.savedAt)
</script>

<template>
  <span v-if="saving" class="hint">
    <i class="ri-loader-4-line spin"></i>保存中…
  </span>
  <span v-else-if="savedAt" class="hint ok" :title="absoluteTime(savedAt)">
    <i class="ri-checkbox-circle-line"></i>自动保存成功 {{ clockTime(savedAt) }}
  </span>
</template>

<style scoped>
.hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  white-space: nowrap;
}
.hint.ok {
  color: var(--wao-success-strong);
}
.hint i {
  font-size: 12.5px;
}
.spin {
  animation: waoSpin 0.9s linear infinite;
}
@keyframes waoSpin {
  to {
    transform: rotate(360deg);
  }
}
</style>
