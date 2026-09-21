<script setup lang="ts">
/**
 * 自动保存回执 —— 挂在每个自动保存的编辑区右上角。
 *
 * <p>任务描述、子任务描述、知识库文档都是「敲完就存」，没有保存按钮。只说「已保存」
 * 还不够：用户看不出那是这一秒存的还是十分钟前存的，所以成功时带上时刻
 * （「自动保存成功 14:32」），正在写透时显示「保存中…」，失败时明确说没存上并保留
 * 上一次成功的时间。
 *
 * <p>状态由调用方喂进来：任务走 data store 的 taskSaveState / taskSavedAt，
 * 文档走 saveState(`doc:<id>`)，这里只管怎么显示。
 */
import { computed } from 'vue'
import { absoluteTime, clockTime } from '@/utils/time'

const props = defineProps<{
  /** 正在写透（含去抖等待窗口）。 */
  saving?: boolean
  /** 上一次写透成功的时刻（ISO）；从没成功过就传 null。 */
  savedAt?: string | null
  /** 上一次写透失败了。 */
  failed?: boolean
  /** 无法确认服务端结果时显示具体回执，不把超时说成确定未保存。 */
  errorMessage?: string
}>()

const okTime = computed(() => (props.savedAt ? clockTime(props.savedAt) : ''))
</script>

<template>
  <span v-if="saving" class="hint">
    <i class="ri-loader-4-line spin"></i>保存中…
  </span>
  <span v-else-if="failed" class="hint bad" :title="savedAt ? `上次保存成功于 ${absoluteTime(savedAt)}` : ''">
    <i class="ri-error-warning-line"></i>{{ errorMessage || '保存尚未确认，内容已留在本机' }}
  </span>
  <span v-else-if="okTime" class="hint ok" :title="absoluteTime(savedAt ?? '')">
    <i class="ri-checkbox-circle-line"></i>自动保存成功 {{ okTime }}
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
.hint.bad {
  color: var(--wao-danger-strong);
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
