<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import { absoluteUrl } from '@/utils/share'

export interface ReplacementTarget {
  kind: 'file' | 'html'
  id: string
  name: string
  url: string
}
const props = defineProps<{ target: ReplacementTarget }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const data = useDataStore()
const input = ref<HTMLInputElement | null>(null)
const dialog = ref<HTMLFormElement | null>(null)
let previousFocus: HTMLElement | null = null
const selected = ref<File | null>(null)
const saving = ref(false)
const error = ref('')
const dragging = ref(false)

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
  if (props.target.kind === 'html' && file.size > 10 * 1024 * 1024) {
    error.value = 'HTML 文件超过 10MB，请重新选择'
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
  dragging.value = false
  choose(event.dataTransfer?.files[0])
}
function close() {
  if (saving.value) return
  if (selected.value && !window.confirm('放弃已选择的新文件？')) return
  emit('close')
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (!selected.value && !saving.value) return
  event.preventDefault()
  event.returnValue = ''
}
function trapFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const controls = Array.from(
    dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled)') ?? [],
  )
  const first = controls[0]
  const last = controls[controls.length - 1]
  if (!first || !last) {
    event.preventDefault()
    return
  }
  if (
    event.shiftKey &&
    (document.activeElement === first || document.activeElement === dialog.value)
  ) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
onMounted(() => {
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  dialog.value?.focus()
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  previousFocus?.focus()
})
async function save() {
  if (saving.value) return
  if (!selected.value) {
    error.value = '请先选择要替换的新文件'
    return
  }
  saving.value = true
  error.value = ''
  try {
    if (props.target.kind === 'html') await data.replaceHostedPage(props.target.id, selected.value)
    else await data.replaceFile(props.target.id, selected.value)
    toast('文件已更新，原访问地址保持不变', 'ri-check-line')
    emit('saved')
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '重新上传失败，请重试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="replace-mask" @click.self="close" @keydown.esc.stop="close">
      <form
        ref="dialog"
        tabindex="-1"
        class="replace-dialog"
        @keydown="trapFocus"
        role="dialog"
        aria-modal="true"
        aria-label="重新上传文件"
        @submit.prevent="save"
      >
        <h3>重新上传文件</h3>
        <b class="target-name">{{ target.name }}</b>
        <p>确认后将替换原文件内容，原访问地址、项目归属和自定义名称保持不变。</p>
        <div class="retained-url">{{ absoluteUrl(target.url) }}</div>
        <button
          type="button"
          class="file-picker"
          :class="{ dragging }"
          :disabled="saving"
          @click="input?.click()"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="drop"
        >
          <i class="ri-upload-2-line"></i>
          {{ selected ? selected.name : '拖入新文件，或点击选择' }}
        </button>
        <input
          ref="input"
          type="file"
          :accept="target.kind === 'html' ? '.html,.htm' : undefined"
          hidden
          :disabled="saving"
          @change="pick"
        />
        <small>{{
          target.kind === 'html'
            ? '支持 UTF-8 HTML，最大 10MB'
            : '文件大小沿用系统上传限制；下载文件名会更新为新文件名'
        }}</small>
        <p v-if="error" class="replace-error" role="alert">{{ error }}</p>
        <p v-if="saving" role="status">上传保存中，请稍候…</p>
        <footer>
          <button type="button" :disabled="saving" @click="close">取消</button>
          <button class="primary" :disabled="saving">
            {{ saving ? '保存中…' : error && selected ? '重试' : '确认替换' }}
          </button>
        </footer>
      </form>
    </div>
  </Teleport>
</template>

<style scoped>
.replace-mask {
  position: fixed;
  inset: 0;
  z-index: 1600;
  display: grid;
  place-items: center;
  padding: 16px;
  background: rgba(20, 23, 34, 0.5);
}
.replace-dialog {
  width: min(480px, 100%);
  max-height: calc(100dvh - 32px);
  overflow-y: auto;
  box-sizing: border-box;
  padding: 22px;
  border-radius: 13px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: 0 18px 60px rgba(18, 22, 36, 0.25);
}
h3 {
  margin: 0 0 14px;
}
.target-name,
.retained-url,
.file-picker {
  overflow-wrap: anywhere;
}
p,
small {
  color: var(--wao-text-weak);
  font-size: 13px;
  line-height: 1.6;
}
.retained-url {
  padding: 9px;
  border-radius: 8px;
  background: var(--wao-bg-page);
  font-size: 12px;
}
.file-picker {
  display: block;
  width: 100%;
  min-height: 76px;
  margin: 16px 0 8px;
  border: 1px dashed var(--wao-line-strong);
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.file-picker.dragging {
  border-color: var(--wao-primary);
  background: var(--wao-primary-soft);
}
button {
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  padding: 8px 13px;
  cursor: pointer;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
button:disabled {
  cursor: wait;
  opacity: 0.65;
}
footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 18px;
}
.primary {
  background: var(--wao-primary);
  color: #fff;
}
.replace-error {
  color: var(--wao-danger);
}
</style>
