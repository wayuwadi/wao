<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchTianrenNotification, retryTianrenNotification, type TianrenNotification } from '@/api/tianren'

const props = defineProps<{ readingId: string; interpreting: boolean }>()
const notification = ref<TianrenNotification | null>(null)
const loading = ref(false)
const retrying = ref(false)
const error = ref('')
const confirmed = ref(false)
const expanded = ref(false)
const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const panelId = useId()
const statusLabel = computed(() => error.value ? '读取失败' : notification.value ? labels[notification.value.state] : '读取中')
const needsAttention = computed(() => !!error.value || ['FAILED', 'UNKNOWN', 'CONFIGURATION_REQUIRED'].includes(notification.value?.state ?? ''))

function closeDetails() {
  expanded.value = false
  trigger.value?.focus()
}

function dismissOutside(event: PointerEvent) {
  if (event.target instanceof Node && !root.value?.contains(event.target)) expanded.value = false
}

onMounted(() => document.addEventListener('pointerdown', dismissOutside))
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
  expanded.value = false
  clearTimer()
  attempts = 0
  loading.value = false
  retrying.value = false
  notification.value = null
  confirmed.value = false
  void refresh(true)
}, { immediate: true })
watch(() => props.interpreting, (active) => { if (!active) void refresh(true) })
onBeforeUnmount(() => { disposed = true; generation++; clearTimer(); document.removeEventListener('pointerdown', dismissOutside) })
</script>

<template>
  <div ref="root" class="tianren-notification" aria-label="卦象飞书群通知" @keydown.esc.stop.prevent="closeDetails">
    <button
      ref="trigger"
      class="notice-trigger"
      :class="{ attention: needsAttention }"
      type="button"
      :aria-expanded="expanded"
      :aria-controls="panelId"
      @click="expanded = !expanded"
    >
      <i :class="needsAttention ? 'ri-error-warning-line' : 'ri-notification-3-line'" aria-hidden="true"></i>
      <span aria-live="polite">飞书 · {{ statusLabel }}</span>
      <i :class="expanded ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'" aria-hidden="true"></i>
    </button>
    <RouterLink class="notice-settings" to="/workday-rhythm"><i class="ri-settings-3-line" aria-hidden="true"></i>配置通知群</RouterLink>
    <section v-if="expanded" :id="panelId" class="notice-details" aria-label="飞书通知详情">
      <div class="notice-heading">
        <strong>飞书群卡片 · {{ statusLabel }}</strong>
        <button class="notice-close" type="button" aria-label="关闭通知详情" @click="closeDetails"><i class="ri-close-line" aria-hidden="true"></i></button>
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
  </div>
</template>

<style scoped>
.tianren-notification { position: relative; margin-left: auto; display: inline-flex; align-items: center; gap: 8px; max-width: 100%; font-size: 12px; }
.notice-trigger { min-height: 30px; display: inline-flex; align-items: center; gap: 5px; border-radius: 999px; font-size: 11px; white-space: nowrap; color: var(--wao-text-3); }
.notice-trigger.attention { border-color: var(--wao-warning-line); background: var(--wao-warning-bg); color: var(--wao-warning-strong); }
.notice-settings { display: inline-flex; align-items: center; gap: 4px; min-height: 30px; color: var(--wao-text-3); font-size: 11px; white-space: nowrap; }
.notice-settings:hover { color: var(--wao-primary-text); }
.notice-details { position: absolute; z-index: 20; top: calc(100% + 8px); right: 0; width: min(340px, calc(100vw - 24px)); box-sizing: border-box; padding: 14px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-card-hover); overflow-wrap: anywhere; }
.notice-heading, .notice-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.notice-heading { flex-wrap: nowrap; }
.notice-close { flex-shrink: 0; padding: 3px 5px; border: 0; }
.notice-actions { justify-content: flex-start; margin-top: 8px; }
button { padding: 5px 10px; border: 1px solid var(--wao-line); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text); cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--wao-primary-text); outline-offset: 2px; }
.notice-error { color: var(--wao-danger-strong); }
small { display: block; margin-top: 8px; color: var(--wao-text-weak); }
</style>
