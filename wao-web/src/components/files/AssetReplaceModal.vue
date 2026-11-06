<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'
import { absoluteUrl } from '@/utils/share'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import { useConfirmAction } from '@/composables/confirmAction'

export interface ReplacementTarget {
  kind: 'file' | 'html'
  id: string
  name: string
  url: string
  revision?: number
}
const props = defineProps<{ target: ReplacementTarget }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const data = useDataStore()
const session = useSessionStore()
const { confirmation, confirmAction, dismissConfirmation } = useConfirmAction()
const input = ref<HTMLInputElement | null>(null)
const selected = ref<File | null>(null)
const saving = ref(false)
const error = ref('')
const dragging = ref(false)
const expectedRevision = ref(props.target.revision)
const conflict = ref(false)
const readingConflict = ref(false)
const latestRevision = ref<number>()
let generation = 0
let disposed = false

function choose(file?: File) {
  if (saving.value || !file) return
  if (!file.size) {
    error.value = '文件不能为空，请重新选择'
    return
  }
  if (props.target.kind === 'html' && !/\.html?$/i.test(file.name)) {
    error.value = '请选择 .html 或 .htm 文件'
    return
  }
  const maxBytes = props.target.kind === 'html' ? 10 * 1024 * 1024 : data.MAX_FILE_BYTES
  if (file.size > maxBytes) {
    error.value = `文件超过 ${maxBytes / 1024 / 1024}MB，请重新选择`
    return
  }
  selected.value = file
  error.value = ''
}
function pick(event: Event) {
  const element = event.target as HTMLInputElement
  choose(element.files?.[0])
  element.value = ''
}
function drop(event: DragEvent) {
  if (saving.value) return
  dragging.value = false
  if ((event.dataTransfer?.files.length ?? 0) > 1) {
    error.value = '每次只能替换一个文件，请重新选择。'
    return
  }
  choose(event.dataTransfer?.files[0])
}
function close() {
  if (saving.value) return
  if (!selected.value) {
    emit('close')
    return
  }
  confirmAction({
    title: '放弃已选择的新文件？',
    description: '所选文件尚未上传，放弃后原文件保持不变。',
    confirmLabel: '放弃替换',
    run: () => { selected.value = null; emit('close') },
  })
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (!selected.value && !saving.value) return
  event.preventDefault()
  event.returnValue = ''
}
watch(() => [props.target.id, props.target.kind], () => {
  generation++
  selected.value = null
  saving.value = false
  error.value = ''
  dragging.value = false
  expectedRevision.value = props.target.revision
  conflict.value = false
  readingConflict.value = false
  latestRevision.value = undefined
  dismissConfirmation()
})
watch(() => [session.account?.accountId, session.userId], () => {
  generation++
  selected.value = null
  saving.value = false
  error.value = ''
  conflict.value = false
  readingConflict.value = false
  latestRevision.value = undefined
  dismissConfirmation()
  emit('close')
}, { flush: 'sync' })
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => {
  disposed = true
  generation++
  selected.value = null
  window.removeEventListener('beforeunload', beforeUnload)
})
async function save() {
  if (saving.value || confirmation.value || conflict.value) return
  if (!selected.value) {
    error.value = '请先选择要替换的新文件'
    return
  }
  const current = generation
  const target = { ...props.target }
  saving.value = true
  error.value = ''
  try {
    if (target.kind === 'html') {
      if (expectedRevision.value === undefined) await data.replaceHostedPage(target.id, selected.value)
      else await data.replaceHostedPage(target.id, selected.value, expectedRevision.value)
    }
    else await data.replaceFile(target.id, selected.value)
    if (disposed || current !== generation) return
    selected.value = null
    toast('文件已更新，原访问地址保持不变', 'ri-check-line')
    emit('saved')
    emit('close')
  } catch (cause) {
    if (disposed || current !== generation) return
    if (target.kind === 'html' && (cause as { status?: number })?.status === 409) {
      conflict.value = true
      await refreshConflict()
      return
    }
    const message = cause instanceof Error ? cause.message : ''
    error.value =
      !message || /^(Network Error|Request failed with status code|timeout of)/.test(message)
        ? '上传暂时失败，已保留所选文件，请检查网络后重试'
        : message
  } finally {
    if (!disposed && current === generation) saving.value = false
  }
}
async function refreshConflict() {
  if (readingConflict.value) return
  const current = generation
  const id = props.target.id
  latestRevision.value = undefined
  readingConflict.value = true
  error.value = ''
  const success = await data.refreshHostedPages().catch(() => false)
  if (disposed || current !== generation) return
  readingConflict.value = false
  const latest = success ? data.hostedPages.find(page => page.id === id) : undefined
  if (latest && Number.isSafeInteger(latest.revision) && latest.revision! >= 0) {
    latestRevision.value = latest.revision
  } else {
    error.value = '最新网页版本读取失败或已不可访问，已保留所选文件，请重新读取后再决定。'
  }
}
function overwrite() {
  if (saving.value || readingConflict.value || latestRevision.value === undefined) return
  expectedRevision.value = latestRevision.value
  conflict.value = false
  void save()
}
function discardReplacement() {
  if (saving.value || readingConflict.value) return
  selected.value = null
  emit('close')
}
</script>

<template>
  <SettingsDialogShell
    title="重新上传文件"
    width="480px"
    height="auto"
    appearance="compact"
    icon="ri-upload-2-line"
    :close-disabled="saving"
    @close="close"
  >
    <form class="replace-content" @submit.prevent="save">
      <b class="target-name">{{ target.name }}</b>
      <p>确认后将替换原文件内容，原访问地址、项目归属和自定义名称保持不变。</p>
      <div class="retained-url">{{ absoluteUrl(target.url) }}</div>
      <button
        type="button"
        class="file-picker"
        :class="{ dragging }"
        :disabled="saving"
        @click="input?.click()"
        @dragover.prevent="!saving && (dragging = true)"
        @dragleave.prevent="dragging = false"
        @drop.prevent="drop"
      >
        <i class="ri-upload-2-line" aria-hidden="true"></i>
        {{ selected ? selected.name : '拖入新文件，或点击选择' }}
      </button>
      <input ref="input" type="file" :accept="target.kind === 'html' ? '.html,.htm' : undefined" hidden :disabled="saving" @change="pick" />
      <small>{{ target.kind === 'html' ? '支持 UTF-8 HTML，最大 10MB' : '文件大小沿用系统上传限制；下载文件名会更新为新文件名' }}</small>
      <p v-if="error" class="replace-error" role="alert">{{ error }}</p>
      <div v-if="conflict" class="replace-conflict" role="alert">
        <p>网页已在另一处更新，所选文件已保留。{{ readingConflict ? '正在读取最新版本…' : '请决定是否仍用所选文件替换最新内容。' }}</p>
        <div class="replace-actions">
          <WaoButton :disabled="saving || readingConflict" @click="discardReplacement">放弃本次替换</WaoButton>
          <WaoButton v-if="latestRevision === undefined" :disabled="saving || readingConflict" @click="refreshConflict">重新读取最新版本</WaoButton>
          <WaoButton v-else :disabled="saving || readingConflict" @click="overwrite">仍替换</WaoButton>
        </div>
      </div>
      <p v-if="saving && !readingConflict" role="status">上传保存中，请稍候…</p>
    </form>
    <template #footer>
      <div class="replace-actions">
        <WaoButton :disabled="saving" @click="close">取消</WaoButton>
        <WaoButton class="primary" variant="primary" :loading="saving" :disabled="conflict" @click="save">{{ saving ? '保存中…' : error && selected ? '重试' : '确认替换' }}</WaoButton>
      </div>
    </template>
  </SettingsDialogShell>
  <ConfirmActionDialog v-if="confirmation" :action="confirmation" @close="dismissConfirmation" />
</template>

<style scoped>
.replace-content { padding: var(--wao-space-2) var(--wao-space-3) var(--wao-space-3); }
.target-name, .retained-url, .file-picker { overflow-wrap: anywhere; }
p, small { color: var(--wao-text-3); font-size: var(--wao-font-help); line-height: var(--wao-line-body); }
.retained-url { padding: var(--wao-space-1); border-radius: var(--wao-radius-control); background: var(--wao-bg-sub); font-size: var(--wao-font-caption); }
.file-picker {
  display: block;
  width: 100%;
  min-height: 76px;
  margin: var(--wao-space-2) 0 var(--wao-space-1);
  border: 1px dashed var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  padding: var(--wao-space-1) var(--wao-space-2);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  cursor: pointer;
}
.file-picker.dragging { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.file-picker:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.file-picker:disabled { cursor: wait; opacity: 0.65; }
.replace-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: var(--wao-space-1); }
.replace-error { color: var(--wao-danger-strong); }
@media (max-width: 650px) { .replace-content { padding: var(--wao-space-2); } }
</style>
