<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { toast } from '@/composables/toast'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement>()
const mounted = ref(false)
const saving = ref(false)
const fullscreen = ref(false)
let returnFocus: HTMLElement | null = null
watch(() => props.open, async open => {
  if (!open) return
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  mounted.value = true
  await nextTick()
  const editor = panel.value?.querySelector<HTMLElement>('.tiptap')
  if (editor) editor.focus()
  else panel.value?.querySelector<HTMLButtonElement>('.close-note')?.focus()
}, { immediate: true })
function close() {
  if (saving.value) return
  emit('close')
  void nextTick(() => returnFocus?.isConnected && returnFocus.focus())
}
useOverlayDismiss(() => props.open, () => {
  if (!fullscreen.value) close()
})
function saved() {
  // saved 在编辑器重置之后发出，此时 finally 尚未解除 saving。
  emit('close')
  toast('小记已保存，继续当前页面', 'ri-checkbox-circle-line')
  void nextTick(() => returnFocus?.isConnected && returnFocus.focus())
}
</script>

<template>
  <Teleport to="body">
    <div v-if="mounted" v-show="open" ref="panel" class="quick-note-popover" role="dialog" aria-label="新建小记">
      <header><span><i class="ri-file-add-line" aria-hidden="true"></i>新建小记</span><button class="close-note" type="button" aria-label="收起小记，保留草稿" :disabled="saving" @click="close"><i class="ri-close-line" aria-hidden="true"></i></button></header>
      <NoteComposer draft-scope="floating" compact autofocus :active="open" @saved="saved" @saving-change="saving = $event" @fullscreen-change="fullscreen = $event" />
    </div>
  </Teleport>
</template>

<style scoped>
.quick-note-popover { position:fixed; z-index:1100; top:64px; right:16px; box-sizing:border-box; width:min(460px, calc(100vw - 24px)); max-height:calc(100dvh - 80px); overflow:auto; padding:14px; border:1px solid var(--wao-line-control); border-radius:14px; background:var(--wao-bg-card); box-shadow:var(--wao-shadow-modal); }
header { display:flex; align-items:center; justify-content:space-between; margin-bottom:10px; color:var(--wao-text); font-size:14px; }
header span { display:flex; align-items:center; gap:8px; } header span i { color:var(--wao-primary-text); }
.close-note { display:grid; place-items:center; width:30px; height:30px; border:0; border-radius:7px; background:transparent; color:var(--wao-text-3); cursor:pointer; }
.close-note:hover { background:var(--wao-bg-hover); } .close-note:disabled { opacity:.4; cursor:wait; }
:deep(.note-composer:not(.fullscreen)) { padding:0; border:0; border-radius:0; }
:deep(.note-composer:not(.fullscreen) .composer-heading) { margin-bottom:4px; }
:deep(.note-composer:not(.fullscreen) .composer-heading b), :deep(.note-composer:not(.fullscreen) .composer-heading > i) { display:none; }
:deep(.note-composer:not(.fullscreen) .editor-space) { min-height:200px; max-height: min(360px, 45dvh); overflow:auto; }
:deep(.composer-footer) { flex-wrap:wrap; gap:8px; }
@media(max-width:760px) { .quick-note-popover { top:12px; right:12px; max-height:calc(100dvh - 24px); } .close-note { width:44px; height:44px; } }
</style>
