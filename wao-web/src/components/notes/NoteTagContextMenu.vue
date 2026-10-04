<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { apiDeleteNoteTag, apiMergeNoteTag } from '@/api/board'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { toast } from '@/composables/toast'
import { publishNoteTagChange, replaceCachedNoteTags } from '@/utils/noteTagChanges'

const props = defineProps<{ choices: string[]; disabled?: boolean }>()
const emit = defineEmits<{ 'saving-change': [value: boolean] }>()
const menu = ref<InstanceType<typeof ContextActionMenu>>()
const panel = ref<HTMLElement>()
const mode = ref<'rename' | 'delete' | null>(null)
const source = ref(''), name = ref(''), error = ref('')
const saving = ref(false), ready = ref(false)
const position = ref({ left: 8, top: 8 })
let anchor: HTMLElement | null = null
const merging = computed(() => name.value.trim() !== source.value && props.choices.includes(name.value.trim()))
function close() {
  if (saving.value) return
  mode.value = null
  if (anchor?.isConnected) anchor.focus({ preventScroll: true })
}
useOverlayDismiss(() => !!mode.value, close, event => {
  if (!panel.value?.contains(event.target as Node)) close()
})
async function show(next: 'rename' | 'delete') {
  mode.value = next; name.value = source.value; error.value = ''; ready.value = false
  await nextTick()
  const rect = anchor?.getBoundingClientRect(), bounds = panel.value?.getBoundingClientRect()
  position.value = {
    left: Math.max(8, Math.min(rect?.left ?? 8, window.innerWidth - (bounds?.width ?? 320) - 8)),
    top: Math.max(8, Math.min(rect?.bottom ?? 8, window.innerHeight - (bounds?.height ?? 200) - 8)),
  }
  ready.value = true
  await nextTick()
  const input = panel.value?.querySelector<HTMLInputElement>('input')
  if (input) { input.focus(); input.select() } else panel.value?.querySelector<HTMLButtonElement>('button')?.focus()
}
function open(event: MouseEvent | KeyboardEvent, tag: string) {
  if (event instanceof KeyboardEvent && !(event.key === 'ContextMenu' || event.key === 'F10' && event.shiftKey)) return
  event.preventDefault(); event.stopPropagation()
  if (props.disabled || saving.value) return
  source.value = tag
  anchor = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  void menu.value?.openAt(event, `# ${tag}`, [
    { id: 'rename', label: '修改标签', run: () => show('rename') },
    { id: 'delete', label: '删除标签', danger: true, run: () => show('delete') },
  ])
}
async function submit() {
  if (saving.value || props.disabled) return
  const target = mode.value === 'delete' ? null : name.value.trim()
  if (target !== null && (!target || target.length > 32 || /[\u0000-\u001f\u007f]/.test(target))) { error.value = '请输入 1–32 个字符的标签名称，不含换行'; return }
  if (target === source.value) { close(); return }
  saving.value = true; emit('saving-change', true); error.value = ''
  try {
    if (!useDataStore().apiLive) throw new Error('服务尚未连接，请稍后重试')
    if (target === null) await apiDeleteNoteTag(source.value)
    else await apiMergeNoteTag(source.value, target)
    const change = { source: source.value, target }
    const cacheSaved = replaceCachedNoteTags(useSessionStore().currentUser.id, change)
    publishNoteTagChange(change)
    if (!cacheSaved) toast('标签已修改，部分浏览器草稿标签未同步；请保留输入并检查浏览器存储。', 'ri-error-warning-line')
    saving.value = false; close()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '标签修改失败，请重试' }
  finally { saving.value = false; emit('saving-change', false) }
}
function keys(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = [...(panel.value?.querySelectorAll<HTMLElement>('input:not(:disabled), button:not(:disabled)') ?? [])]
  const first = buttons[0], last = buttons[buttons.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
defineExpose({ open })
</script>

<template>
  <ContextActionMenu ref="menu" />
  <Teleport to="body">
    <form v-if="mode" ref="panel" class="note-tag-panel" role="dialog" :aria-label="mode === 'rename' ? '修改标签' : '删除标签'" aria-modal="true"
      :style="{ left: `${position.left}px`, top: `${position.top}px`, visibility: ready ? 'visible' : 'hidden' }" @submit.prevent="submit" @keydown="keys" @contextmenu.prevent>
      <strong>{{ mode === 'rename' ? '修改标签' : '删除标签' }}</strong>
      <div class="tag-source"># {{ source }}</div>
      <input v-if="mode === 'rename'" v-model="name" aria-label="标签新名称" maxlength="32" :disabled="saving" />
      <p v-if="mode === 'delete'">从标签清单和已关联的小记中移除，文章会保留。</p>
      <p v-else>{{ merging ? '同名标签已存在，确认后合并并去重。' : '名称会同步到已关联的小记，正文保持原样。' }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions"><button type="button" :disabled="saving" @click="close">取消</button><button type="submit" :disabled="saving || disabled || mode === 'rename' && !name.trim()" :class="{ danger: mode === 'delete' }">{{ saving ? '保存中…' : error ? '重试' : mode === 'delete' ? '删除标签' : merging ? '合并标签' : '保存修改' }}</button></div>
    </form>
  </Teleport>
</template>

<style scoped>
.note-tag-panel { position: fixed; z-index: 3100; box-sizing: border-box; width: min(320px, calc(100vw - 16px)); max-height: calc(100dvh - 16px); overflow: auto; padding: 16px; background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: 12px; box-shadow: var(--wao-shadow-pop); }
.note-tag-panel strong { font-size: 14px; }
.tag-source { margin: 10px 0; color: var(--wao-primary-text); overflow-wrap: anywhere; }
.note-tag-panel input { box-sizing: border-box; width: 100%; min-height: 34px; padding: 6px 9px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text); }
.note-tag-panel p { margin: 10px 0; font-size: 12px; line-height: 1.6; color: var(--wao-text-2); }
.note-tag-panel p.error { color: var(--wao-danger-strong); }
.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.actions button { padding: 6px 12px; border: 1px solid var(--wao-line-control); border-radius: 7px; color: var(--wao-text); background: var(--wao-bg-card); cursor: pointer; }
.actions button[type=submit] { background: var(--wao-primary); color: #fff; }
.actions button.danger { background: var(--wao-danger-strong); }
.actions button:disabled { opacity: .5; cursor: wait; }
</style>
