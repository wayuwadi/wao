<script setup lang="ts">
import { computed, watch } from 'vue'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { useSessionStore } from '@/stores/session'
const props = defineProps<{ scope: string }>()
const order = useTaskOrderStore()
const session = useSessionStore()
const status = computed(() => order.state(props.scope))
watch(() => [props.scope, session.userId], () => { void order.load(props.scope) }, { immediate: true })
</script>
<template>
  <div class="order-status" role="status">
    <span v-if="status.loading">正在读取个人顺序…</span>
    <span v-else-if="status.saving">顺序保存中…</span>
    <span v-else-if="status.error" role="alert">{{ status.error }} <button @click="order.retry(scope)">重试</button></span>
    <span v-else-if="status.dirty">有未保存的个人顺序 <button @click="order.retry(scope)">继续保存</button></span>
    <span v-else>拖拽调整个人顺序 · {{ status.ids.length ? '已保存，可跨设备恢复' : '不改变任务层级' }}</span>
  </div>
</template>
<style scoped>
.order-status { font-size: 12px; color: var(--wao-text-weak); padding: 4px 0; }
.order-status button { color: var(--wao-primary); border: 0; background: none; cursor: pointer; }
</style>
