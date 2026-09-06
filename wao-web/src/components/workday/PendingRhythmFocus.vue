<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { fetchPendingRhythmFocus, completePendingRhythmFocus, type PendingRhythmFocusItem } from '@/api/workdayRhythm'
import { useSessionStore } from '@/stores/session'

const props = defineProps<{ date: string }>()
const session = useSessionStore()
type Row = PendingRhythmFocusItem & { completed: boolean; saving: boolean; error: string; retry: boolean | null }
const rows = ref<Row[]>([])
const loading = ref(false)
const error = ref('')
const skippedCount = ref(0)
let sequence = 0

async function load() {
  if (rows.value.some(row => row.saving)) return
  const request = ++sequence
  loading.value = true
  error.value = ''
  try {
    const result = await fetchPendingRhythmFocus()
    if (request !== sequence) return
    rows.value = result.items.map(item => ({ ...item, completed: false, saving: false, error: '', retry: null }))
    skippedCount.value = result.skippedCount
  } catch (cause) {
    if (request === sequence) error.value = cause instanceof Error ? cause.message : '未完成事项读取失败'
  } finally {
    if (request === sequence) loading.value = false
  }
}

async function setCompleted(row: Row, completed: boolean) {
  if (loading.value || row.saving) return
  const request = sequence
  row.saving = true
  row.error = ''
  try {
    await completePendingRhythmFocus(row, completed)
    if (request !== sequence) return
    row.completed = completed
    row.retry = null
  } catch (cause) {
    if (request === sequence) {
      row.error = cause instanceof Error ? cause.message : '保存失败，请重试'
      row.retry = completed
    }
  } finally {
    if (request === sequence) row.saving = false
  }
}

function sourceLabel(date: string) {
  return Math.round((Date.parse(`${props.date}T00:00:00+08:00`) - Date.parse(`${date}T00:00:00+08:00`)) / 86_400_000) === 1
    ? `昨日 · ${date.slice(5)}` : date
}

watch(() => [props.date, session.currentUser.id], () => {
  sequence++
  rows.value = []
  skippedCount.value = 0
  void load()
}, { immediate: true })
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <section v-if="loading || error || skippedCount || rows.length" class="pending-focus" aria-label="往日未完成重点" :aria-busy="loading">
    <header><b>往日未完成 <span v-if="rows.length">{{ rows.filter(row => !row.completed).length }}</span></b><button type="button" :disabled="loading || rows.some(row => row.saving)" @click="load">刷新</button></header>
    <p v-if="loading" role="status">正在读取未完成事项…</p>
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="load">重试读取</button></p>
    <p v-if="skippedCount" role="alert">{{ skippedCount }} 天存档无法读取，请到历史记录检查。</p>
    <div v-if="rows.length" class="pending-list" tabindex="0" aria-label="往日未完成事项列表">
    <div v-for="row in rows" :key="`${row.date}:${row.index}`" class="pending-row" :class="{ done: row.completed }">
      <div><time :datetime="row.date">{{ sourceLabel(row.date) }}</time><span>{{ row.title }}</span></div>
      <button type="button" :disabled="loading || row.saving" :aria-label="`${row.completed ? '撤销完成' : '完成'}：${row.title}`" @click="setCompleted(row, row.retry ?? !row.completed)">
        {{ row.saving ? '保存中…' : row.error ? '重试保存' : row.completed ? '已完成 · 撤销' : '完成' }}
      </button>
      <p v-if="row.error" role="alert">{{ row.error }}</p>
    </div>
    </div>
    <small v-if="rows.length > 3" class="scroll-hint">可在列表内滚动查看全部事项</small>
  </section>
</template>

<style scoped>
.pending-focus{margin:12px 0;padding:10px 12px;border:1px solid var(--wao-line,#eadfcd);border-radius:10px;background:var(--wao-bg-page,#fffaf1);color:var(--wao-text,#463e33);font-size:12px;flex-shrink:0}
.pending-list{max-height:180px;overflow-y:auto;overscroll-behavior:contain;scrollbar-gutter:stable}.pending-list:focus-visible{outline:2px solid var(--wao-text-2,#267c65);outline-offset:2px}.scroll-hint{display:block;margin-top:7px;color:var(--wao-text-faint,#827967);font-size:10px}
header{display:flex;align-items:center;justify-content:space-between;gap:8px}header b{color:var(--wao-text-2,#705221)}header b span{margin-left:5px}
button{border:0;background:transparent;color:var(--wao-text-2,#267c65);font:inherit;cursor:pointer;white-space:nowrap;padding:4px}button:disabled{opacity:.5;cursor:default}
.pending-row{display:flex;align-items:center;flex-wrap:wrap;gap:6px 10px;border-top:1px solid var(--wao-line,#eadfcd);padding:8px 0 0;margin-top:8px}.pending-row>div{flex:1;min-width:0}.pending-row time{display:block;color:var(--wao-text-faint,#827967);font-size:10px;margin-bottom:3px}.pending-row span{overflow-wrap:anywhere;white-space:pre-wrap}.pending-row.done span{text-decoration:line-through;opacity:.6}p{margin:6px 0 0;line-height:1.6}.pending-row p{flex-basis:100%;color:#ad4f3b}
</style>
