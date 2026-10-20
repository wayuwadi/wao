<script setup lang="ts">
import { ref, watch } from 'vue'
import type { RichDocContent } from '@/types'
import { columnName, readSheet, sheetContent, parseSheetClipboard, sheetClipboard, MAX_SHEET_CELLS, MAX_SHEET_ROWS, MAX_SHEET_COLUMNS } from '@/utils/knowledgeSheet'
import { copyToClipboard } from '@/utils/clipboard'
const props = defineProps<{ content?: RichDocContent | null; disabled?: boolean }>()
const emit = defineEmits<{ change: [content: RichDocContent] }>()
const rows = ref<string[][]>([])
const error = ref('')
const loadError = ref(false)
const selected = ref({ row: 0, col: 0 })
const grid = ref<HTMLElement | null>(null)
watch(() => props.content, value => {
  try { rows.value = readSheet(value); error.value = ''; loadError.value = false }
  catch (cause) { error.value = String(cause); loadError.value = true }
}, { immediate: true })
function publish() { emit('change', sheetContent(rows.value)) }
function resize(rowCount: number, colCount: number) {
  if (rowCount > MAX_SHEET_ROWS || colCount > MAX_SHEET_COLUMNS || rowCount * colCount > MAX_SHEET_CELLS) {
    error.value = '当前表格最多 100 行、26 列和 1000 个单元格，请缩小粘贴范围。'; return false
  }
  rows.value = Array.from({ length: rowCount }, (_, row) => Array.from({ length: colCount }, (_, col) => rows.value[row]?.[col] ?? ''))
  error.value = ''; return true
}
function addRow() { if (resize(rows.value.length + 1, rows.value[0]!.length)) publish() }
function addColumn() { if (resize(rows.value.length, rows.value[0]!.length + 1)) publish() }
function deleteRow() {
  if (rows.value.length <= 1 || !window.confirm('删除当前行及其中内容？')) return
  rows.value.splice(selected.value.row, 1); selected.value.row = Math.min(selected.value.row, rows.value.length - 1); publish()
}
function deleteColumn() {
  if (rows.value[0]!.length <= 1 || !window.confirm('删除当前列及其中内容？')) return
  rows.value.forEach(row => row.splice(selected.value.col, 1)); selected.value.col = Math.min(selected.value.col, rows.value[0]!.length - 1); publish()
}
function input(row: number, col: number, event: Event) {
  rows.value[row]![col] = (event.target as HTMLInputElement).value; publish()
}
function paste(row: number, col: number, event: ClipboardEvent) {
  const text = event.clipboardData?.getData('text/plain')
  if (text == null) return
  event.preventDefault()
  const pasted = parseSheetClipboard(text)
  if (pasted.some(line => line.some(cell => cell.length > 20000))) { error.value = '单元格内容过长'; return }
  if (!resize(Math.max(rows.value.length, row + pasted.length), Math.max(rows.value[0]!.length, col + Math.max(...pasted.map(line => line.length))))) return
  pasted.forEach((line, r) => line.forEach((value, c) => { rows.value[row + r]![col + c] = value }))
  publish()
}
function move(row: number, col: number, event: KeyboardEvent) {
  if (event.isComposing) return
  const offsets: Record<string, number[]> = { Enter: [event.shiftKey ? -1 : 1, 0], ArrowUp: [-1, 0], ArrowDown: [1, 0], Tab: [0, event.shiftKey ? -1 : 1] }
  const offset = offsets[event.key]
  if (!offset) return
  let r = row + offset[0]!, c = col + offset[1]!
  if (c >= rows.value[0]!.length) { c = 0; r++ }
  if (c < 0) { c = rows.value[0]!.length - 1; r-- }
  const target = grid.value?.querySelector<HTMLInputElement>(`[data-cell="${r}:${c}"]`)
  if (target) { event.preventDefault(); target.focus(); target.select() }
}
async function copyAll() {
  try { error.value = await copyToClipboard(sheetClipboard(rows.value)) ? '' : '复制失败，请重试' }
  catch { error.value = '复制失败，请重试' }
}
</script>
<template>
  <section class="knowledge-sheet" aria-label="独立表格">
    <div class="sheet-toolbar">
      <span>{{ columnName(selected.col) }}{{ selected.row + 1 }}</span>
      <button :disabled="disabled || loadError" @click="addRow">增加行</button>
      <button :disabled="disabled || loadError" @click="addColumn">增加列</button>
      <button :disabled="disabled || loadError || rows.length <= 1" @click="deleteRow">删除当前行</button>
      <button :disabled="disabled || loadError || (rows[0]?.length ?? 0) <= 1" @click="deleteColumn">删除当前列</button>
      <button :disabled="loadError" @click="copyAll">复制表格</button>
    </div>
    <p class="sheet-help">可从 Excel 复制多格内容后粘贴；Tab 切换单元格，Enter 换行。此表格保存文本和数值，暂不计算公式。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="!loadError" ref="grid" class="sheet-scroll">
      <table aria-label="表格单元格">
        <thead><tr><th></th><th v-for="(_, col) in rows[0]" :key="col" scope="col">{{ columnName(col) }}</th></tr></thead>
        <tbody><tr v-for="(row, r) in rows" :key="r"><th scope="row">{{ r + 1 }}</th><td v-for="(value, c) in row" :key="c"><input :value="value" :data-cell="`${r}:${c}`" :aria-label="`${columnName(c)}${r + 1}`" :disabled="disabled" maxlength="20000" @focus="selected = { row: r, col: c }" @input="input(r, c, $event)" @paste="paste(r, c, $event)" @keydown="move(r, c, $event)" /></td></tr></tbody>
      </table>
    </div>
  </section>
</template>
<style scoped>
.knowledge-sheet { min-width: 0; }
.sheet-toolbar { display: flex; flex-wrap: wrap; gap: var(--wao-space-1); align-items: center; margin-bottom: var(--wao-space-1); }
.sheet-toolbar button { min-height: 44px; padding: 0 var(--wao-space-1); background: var(--wao-bg-card); color: var(--wao-text); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); cursor: pointer; }
.sheet-toolbar button:disabled { opacity: .5; cursor: default; }
.sheet-help { color: var(--wao-text-3); }
.sheet-scroll { overflow: auto; max-height: 65vh; border: 1px solid var(--wao-line); }
table { border-collapse: separate; border-spacing: 0; table-layout: fixed; }
th, td { border-right: 1px solid var(--wao-line); border-bottom: 1px solid var(--wao-line); padding: 0; }
th { min-width: 44px; background: var(--wao-bg-sub); color: var(--wao-text-3); }
thead th { position: sticky; top: 0; z-index: 1; height: 32px; }
tbody th { position: sticky; left: 0; }
td input { width: 140px; min-height: 44px; padding: var(--wao-space-1); border: 0; background: var(--wao-bg-card); color: var(--wao-text); font: inherit; }
td input:focus { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
</style>
