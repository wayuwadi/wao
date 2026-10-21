<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { IWorkbookData } from '@univerjs/core'
import type { FWorkbook } from '@univerjs/sheets/facade'
import type { RichDocContent } from '@/types'
import { readWorkbook, workbookContent } from '@/utils/knowledgeSheet'
import { loadSheetRuntime } from '@/utils/knowledgeSheetRuntime'
import { useTheme } from '@/composables/theme'
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
const props = defineProps<{ content?: RichDocContent | null; disabled?: boolean; documentId?: string }>()
const emit = defineEmits<{ change: [content: RichDocContent] }>()
const host = ref<HTMLElement | null>(null)
const loading = ref(true)
const error = ref('')
const { effective } = useTheme()
let instance: Awaited<ReturnType<typeof import('@univerjs/presets')['createUniver']>> | null = null
let workbook: FWorkbook | null = null
let subscription: { dispose(): void } | null = null
let generation = 0
let mounted = false
let acceptedContent = ''
let lastSnapshot = ''
function teardown() {
  subscription?.dispose(); subscription = null
  workbook = null
  instance?.univer.dispose(); instance = null
}
function publish() {
  if (!workbook || props.disabled) return
  const snapshot = workbook.save()
  const serialized = JSON.stringify(snapshot)
  if (serialized === lastSnapshot) return
  lastSnapshot = serialized
  const content = workbookContent(snapshot)
  acceptedContent = JSON.stringify(content)
  emit('change', content)
}
async function load() {
  const token = ++generation
  loading.value = true; error.value = ''
  try {
    const snapshot = readWorkbook(props.content, `kb-${props.documentId ?? 'workbook'}`)
    const [{ createUniver, LocaleType, CommandType, mergeLocales }, { UniverSheetsCorePreset }, { default: zhCN }, { UniverSheetsFilterPreset }, { default: filterZhCN }, { UniverSheetsSortPreset }, { default: sortZhCN }] = await loadSheetRuntime()
    if (!mounted || token !== generation || !host.value) return
    subscription?.dispose(); subscription = null
    if (workbook) instance?.univerAPI.disposeUnit(workbook.getId())
    workbook = null
    instance ??= createUniver({ locale: LocaleType.ZH_CN, locales: { [LocaleType.ZH_CN]: mergeLocales(zhCN, filterZhCN, sortZhCN) }, darkMode: effective.value === 'dark',
      presets: [UniverSheetsCorePreset({ container: host.value, header: true, ribbonType: 'grid', toolbar: true, formulaBar: true, footer: { sheetBar: true, statisticBar: true, zoomSlider: true, menus: true }, disableAutoFocus: true }), UniverSheetsFilterPreset(), UniverSheetsSortPreset()] })
    workbook = instance.univerAPI.createWorkbook(snapshot as IWorkbookData)
    workbook.setEditable(!props.disabled)
    acceptedContent = JSON.stringify(props.content)
    lastSnapshot = JSON.stringify(workbook.save())
    subscription = workbook.onCommandExecuted(command => { if (command.type === CommandType.MUTATION) publish() })
  } catch (cause) {
    if (token !== generation) return
    teardown(); error.value = cause instanceof Error ? cause.message : '表格加载失败'
  } finally { if (token === generation) loading.value = false }
}
async function flush() {
  if (workbook?.isCellEditing()) await workbook.endEditingAsync(true)
  publish()
}
watch(() => [props.documentId, props.content] as const, ([id, value], [previousId]) => {
  if (mounted && (id !== previousId || JSON.stringify(value) !== acceptedContent)) void load()
})
watch(() => props.disabled, value => workbook?.setEditable(!value))
watch(effective, value => instance?.univerAPI.toggleDarkMode(value === 'dark'))
function beforeUnload(event: BeforeUnloadEvent) {
  if (workbook?.isCellEditing()) { event.preventDefault(); event.returnValue = '' }
}
onMounted(() => { mounted = true; window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { mounted = false; generation++; window.removeEventListener('beforeunload', beforeUnload); teardown() })
useSaveBeforeRefresh(flush)
defineExpose({ flush })
</script>
<template>
  <section class="knowledge-sheet" aria-label="电子表格工作簿">
    <div v-if="loading" class="sheet-state" role="status">正在打开表格…</div>
    <div v-if="error" class="sheet-state" role="alert">{{ error }}<button @click="load">重试</button></div>
    <div ref="host" class="sheet-engine" :class="{ 'sheet-hidden': loading || error }"></div>
  </section>
</template>
<style scoped>
.knowledge-sheet { position: relative; display: flex; flex: 1; flex-direction: column; min-width: 0; min-height: 0; height: 100%; overflow: hidden; }
.sheet-engine { flex: 1; width: 100%; min-height: 0; overflow: hidden; }
.sheet-hidden { visibility: hidden; }
.sheet-state { padding: var(--wao-space-2); color: var(--wao-text); }
.sheet-state button { margin-left: var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); padding: var(--wao-space-1); }
</style>
