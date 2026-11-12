<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { Popover } from 'ant-design-vue'
import { fetchSharedTodayTianrenReading, type TianrenReading } from '@/api/tianren'

defineProps<{ integrated?: boolean }>()
const emit = defineEmits<{ preview: [] }>()
const open = ref(false)
const trigger = ref<HTMLButtonElement>()
const closeButton = ref<HTMLButtonElement>()
watch(closeButton, element => { if (open.value) element?.focus() }, { flush: 'post' })
let disposed = false
const lines = computed(() => [...(reading.value?.lines ?? [])].reverse())
const summary = computed(() => {
  const value = reading.value
  if (!value) return ''
  const primary = `${value.primary.name}卦（上${value.primary.upper.nature}下${value.primary.lower.nature}）`
  return value.relating
    ? `本卦为${primary}，变化后为${value.relating.name}卦，共 ${value.changingLines.length} 个变爻。`
    : `本卦为${primary}，今日没有变爻。`
})
async function onOpen(value: boolean) {
  if (!value && !isTopmost()) return
  open.value = value
  if (!value) return
  emit('preview')
  await nextTick()
  closeButton.value?.focus()
}
function close(restoreFocus = false) {
  open.value = false
  if (restoreFocus) trigger.value?.focus()
}
const { isTopmost } = useOverlayDismiss(() => open.value, () => close(true))
defineExpose({ close })

const reading = ref<TianrenReading | null>(null)
const loading = ref(true)
const error = ref('')
let timer: ReturnType<typeof setInterval> | undefined
let request = 0

async function load() {
  const current = ++request
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
  if (reading.value?.date !== today) reading.value = null
  if (!reading.value) loading.value = true
  error.value = ''
  try {
    const result = await fetchSharedTodayTianrenReading()
    if (!disposed && current === request) reading.value = result
  } catch (cause) {
    if (!disposed && current === request) error.value = cause instanceof Error ? cause.message : '今日卦象暂时无法读取'
  } finally {
    if (!disposed && current === request) loading.value = false
  }
}

onMounted(() => { void load(); timer = setInterval(() => void load(), 60_000) })
onBeforeUnmount(() => { disposed = true; clearInterval(timer) })
</script>

<template>
  <div class="home-tianren" :class="{ integrated }">
    <Popover :open="open" trigger="click" placement="bottomLeft" overlay-class-name="home-tianren-preview" @open-change="onOpen">
    <button ref="trigger" class="tianren-link" type="button" :aria-expanded="open" aria-label="预览今日卦象">
      <span v-if="!integrated" class="tianren-icon" aria-hidden="true">☯</span>
      <span class="tianren-copy">
        <small><span v-if="integrated" class="integrated-icon" aria-hidden="true">☯</span>今日卦象</small>
        <strong v-if="loading">读取中…</strong>
        <strong v-else-if="reading">{{ reading.primary.name }}卦</strong>
        <strong v-else-if="error">暂不可用</strong>
        <strong v-else>每天 8 点生成</strong>
        <span v-if="integrated" class="tianren-hint">{{ reading?.relating ? `之 ${reading.relating.name}卦 · 查看卦象` : '查看卦象' }}<i class="ri-arrow-down-s-line" aria-hidden="true"></i></span>
      </span>
      <span v-if="!integrated" class="tianren-symbol" aria-hidden="true">{{ reading?.primary.symbol || '☰' }}</span>
    </button>
    <template #content>
      <section class="preview-content" aria-label="今日卦象预览">
        <header><span>今日卦象<span v-if="reading"> · {{ reading.date }}</span></span><button ref="closeButton" type="button" aria-label="关闭卦象预览" @click="close(true)"><i class="ri-close-line" aria-hidden="true"></i></button></header>
        <p v-if="loading" role="status">正在读取今日卦象…</p>
        <div v-else-if="error" role="alert"><p>{{ error }}</p><button type="button" @click="load">重新读取</button></div>
        <template v-else-if="reading">
          <div class="preview-title"><span class="preview-symbol" aria-hidden="true">{{ reading.primary.symbol }}</span><div><h2>{{ reading.primary.name }}卦</h2><p>第 {{ reading.primary.number }} 卦 · 上{{ reading.primary.upper.name }}下{{ reading.primary.lower.name }} · {{ reading.changingLines.length ? `${reading.changingLines.length} 个变爻` : '无变爻' }}</p></div></div>
          <p v-if="reading.relating">之卦 · {{ reading.relating.name }}（第 {{ reading.relating.number }} 卦）</p>
          <div class="preview-summary" aria-live="polite"><small>今日共享卦象</small><p>{{ summary }}</p></div>
          <details><summary>展开卦象结构</summary><div class="preview-lines" aria-label="六爻，从上爻到初爻"><div v-for="(line, index) in lines" :key="index"><small>{{ ['上', '五', '四', '三', '二', '初'][index] }}</small><span :class="{ yin: line % 2 === 0 }"><i></i><i v-if="line % 2 === 0"></i></span><small>{{ line }}{{ line === 6 || line === 9 ? ' · 变' : '' }}</small></div></div></details>
        </template>
        <p v-else>今日共享卦象将在早上 8 点生成，生成后自动显示在首页。</p>
        <footer><button type="button" @click="close(true)">关闭</button></footer>
      </section>
    </template>
    </Popover>
    <button v-if="error" class="tianren-retry" type="button" :disabled="loading" @click="load">重试</button>
  </div>
</template>

<style scoped>
.home-tianren { position: relative; }
.tianren-link { font: inherit; text-align: left; cursor: pointer; }
.preview-content { width: min(370px, calc(100vw - 56px)); max-height: min(680px, 75dvh); overflow: auto; color: var(--wao-text); font-size: 13px; line-height: 1.65; overflow-wrap: anywhere; }
.preview-content header, .preview-content footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.preview-content header, .preview-content small { color: var(--wao-text-3); font-size: 12px; }
.preview-content button { border: 0; background: transparent; color: var(--wao-primary-text); cursor: pointer; font: inherit; padding: 6px; }
.preview-content footer { border-top: 1px solid var(--wao-line); margin-top: 18px; padding-top: 14px; }
.preview-content summary { color: var(--wao-primary-text); cursor: pointer; }
.preview-title { display: flex; align-items: center; gap: 16px; margin: 18px 0; }
.preview-title h2 { font-size: 20px; margin: 0; }
.preview-title p { font-size: 12px; color: var(--wao-text-3); margin: 3px 0 0; }
.preview-symbol { font-size: 52px; color: var(--wao-primary); line-height: 1; }
.preview-summary { border-top: 1px solid var(--wao-line); padding-top: 16px; margin-bottom: 12px; }
.preview-summary p { white-space: pre-wrap; }
.preview-lines { display: grid; gap: 10px; margin: 16px 0; }
.preview-lines > div { display: grid; grid-template-columns: 24px 1fr 42px; gap: 12px; align-items: center; }
.preview-lines span { display: flex; gap: 16%; }
.preview-lines i { flex: 1; height: 6px; background: var(--wao-primary); }
.tianren-link { display: inline-flex; align-items: center; gap: 9px; height: var(--home-day-control-height, 42px); box-sizing: border-box; padding: 3px 12px; border: 1px solid var(--wao-line-control); border-radius: var(--home-day-control-radius, 10px); color: var(--wao-primary-text); background: var(--wao-bg-card); text-decoration: none; transition: border-color .18s, background .18s; }
.tianren-link:hover { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.tianren-link:focus-visible, .tianren-retry:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.tianren-icon { color: var(--wao-primary); font-size: 19px; line-height: 1; }
.tianren-copy { display: grid; gap: 2px; }
.tianren-copy small { color: var(--wao-text-3); font-size: 10px; }
.tianren-copy strong { font-size: 13px; font-weight: 650; }
.tianren-symbol { display: grid; width: 29px; height: 29px; place-items: center; border-radius: 8px; background: var(--wao-primary-soft); color: var(--wao-primary-text); font-size: 17px; }
.tianren-retry { position: absolute; top: calc(100% + 2px); right: 0; border: 0; background: transparent; color: var(--wao-primary-text); font-size: 11px; cursor: pointer; }
.integrated { min-width: 0; }
.integrated .tianren-link { display: flex; width: 100%; height: 100%; min-height: 62px; border: 0; border-radius: 0; padding: 1px 14px; background: transparent; }
.integrated .tianren-link:hover { background: var(--wao-primary-soft); }
.integrated .tianren-copy { min-width: 0; gap: 5px; }
.integrated .tianren-copy small { display: flex; align-items: center; gap: 5px; font-size: 11px; }
.integrated .tianren-copy strong { font-size: 16px; overflow-wrap: anywhere; }
.integrated-icon { color: var(--wao-primary-text); }
.tianren-hint { font-size: 11px; color: var(--wao-text-3); }
.integrated .tianren-retry { position: static; display: block; margin: 0 14px 6px; }
@media (max-width: 600px) { .integrated .tianren-link { padding: 12px 14px; } }
</style>
