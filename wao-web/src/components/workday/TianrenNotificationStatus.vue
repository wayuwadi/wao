<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchTianrenNotification, retryTianrenNotification, type TianrenNotification } from '@/api/tianren'

const props = defineProps<{ readingId: string; interpreting: boolean }>()
const notification = ref<TianrenNotification | null>(null)
const loading = ref(false)
const retrying = ref(false)
const error = ref('')
const confirmed = ref(false)
let generation = 0
let attempts = 0
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
const labels: Record<TianrenNotification['state'], string> = {
  DISABLED: '未启用', CONFIGURATION_REQUIRED: '待配置', WAITING: '等待解读与投递',
  PENDING: '待发送', SENDING: '发送中', SENT: '已发送', FAILED: '发送失败', SKIPPED: '已跳过', UNKNOWN: '接收结果待确认',
}

function clearTimer() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

async function refresh(reset = false) {
  if (loading.value || retrying.value || disposed) return
  clearTimer()
  if (reset) attempts = 0
  const current = generation
  const id = props.readingId
  loading.value = true
  error.value = ''
  try {
    const result = await fetchTianrenNotification(id)
    if (disposed || current !== generation) return
    notification.value = result
    if (['WAITING', 'PENDING', 'SENDING'].includes(result.state) && attempts++ < 24) {
      timer = setTimeout(() => { void refresh() }, 5000)
    }
  } catch (cause) {
    if (current === generation && !disposed) error.value = cause instanceof Error ? cause.message : '通知状态读取失败'
  } finally {
    if (current === generation) loading.value = false
  }
}

async function retry() {
  if (retrying.value || loading.value || !notification.value?.retryable) return
  if (notification.value.state === 'UNKNOWN' && !confirmed.value) return
  clearTimer()
  const current = generation
  retrying.value = true
  error.value = ''
  try {
    const result = await retryTianrenNotification(props.readingId, confirmed.value)
    if (disposed || current !== generation) return
    notification.value = result
    confirmed.value = false
    attempts = 0
    timer = setTimeout(() => { void refresh() }, 3000)
  } catch (cause) {
    if (current === generation && !disposed) error.value = cause instanceof Error ? cause.message : '重试失败'
  } finally {
    if (current === generation) retrying.value = false
  }
}

watch(() => props.readingId, () => {
  generation++
  clearTimer()
  attempts = 0
  loading.value = false
  retrying.value = false
  notification.value = null
  confirmed.value = false
  void refresh(true)
}, { immediate: true })
watch(() => props.interpreting, (active) => { if (!active) void refresh(true) })
onBeforeUnmount(() => { disposed = true; generation++; clearTimer() })
</script>

<template>
  <section class="tianren-notification" aria-label="卦象飞书群通知" aria-live="polite">
    <div class="notice-heading">
      <strong>飞书群卡片 · {{ notification ? labels[notification.state] : error ? '读取失败' : '读取中' }}</strong>
      <RouterLink to="/workday-rhythm">配置通知群</RouterLink>
    </div>
    <p v-if="notification">{{ notification.targetName ? `${notification.targetName} · ` : '' }}{{ notification.message }}</p>
    <p v-if="error" class="notice-error" role="alert">{{ error }}</p>
    <label v-if="notification?.state === 'UNKNOWN'">
      <input v-model="confirmed" type="checkbox" :disabled="retrying" />
      已确认群里没有收到（重试可能产生重复卡片）
    </label>
    <div class="notice-actions">
      <button v-if="notification?.retryable" type="button" :disabled="retrying || loading || (notification.state === 'UNKNOWN' && !confirmed)" @click="retry">
        {{ retrying ? '正在安排重试…' : '重试发送原群' }}
      </button>
      <button type="button" :disabled="loading || retrying" @click="refresh(true)">{{ loading ? '读取中…' : '刷新状态' }}</button>
    </div>
    <small v-if="notification?.state === 'DISABLED' || notification?.state === 'CONFIGURATION_REQUIRED'">在今日节奏 → 调整节奏 → 通知中开启「卦象解读完成后发群」。</small>
  </section>
</template>

<style scoped>
.tianren-notification { margin-bottom: 16px; padding: 14px 18px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); font-size: 12px; overflow-wrap: anywhere; }
.notice-heading, .notice-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.notice-heading a { color: #8760aa; }
.notice-actions { justify-content: flex-start; margin-top: 8px; }
button { padding: 5px 10px; border: 1px solid var(--wao-line); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text); cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
.notice-error { color: #b54c4c; }
small { display: block; margin-top: 8px; color: var(--wao-text-weak); }
</style>
