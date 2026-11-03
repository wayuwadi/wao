<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import { fetchDrawing, renameDrawing } from '@/api/drawings'
import SettingsDialogShell from './SettingsDialogShell.vue'
import WaoButton from './WaoButton.vue'
import { apiUploadImage } from '@/api/board'

const DrawingsView = defineAsyncComponent(() => import('@/views/DrawingsView.vue').then(module => module.default))
const editing = ref(false), closing = ref(false), closeError = ref('')
const canvasView = ref<{ flush: () => Promise<void> }>()
async function finishDrawing() {
  if (closing.value) return
  closing.value = true; closeError.value = ''
  try {
    if (!canvasView.value) throw new Error('画板仍在加载，请稍后重试')
    await canvasView.value.flush()
    await refreshDrawingSnapshot(true)
    editing.value = false
  } catch (cause) { closeError.value = cause instanceof Error ? cause.message : '保存失败，画板已保留' }
  finally { closing.value = false }
}
const props = defineProps<NodeViewProps>()
const attrs = computed(() => props.node.attrs)
const kind = computed(() => props.node.type.name)
const icon = computed(() => ({
  fileCard: 'ri-attachment-2', mediaCard: attrs.value.mime?.startsWith('video/') ? 'ri-video-line' : 'ri-volume-up-line',
  linkCard: 'ri-links-line', drawingCard: 'ri-shape-line',
}[kind.value] ?? 'ri-file-line'))
const liveTitle = ref(''), liveSnapshot = ref(''), linkedDocument = ref(false)
const label = computed(() => liveTitle.value || attrs.value.title || attrs.value.name || (kind.value === 'drawingCard' ? '未命名画板' : attrs.value.url))
const href = computed(() => {
  // 分享页没有登录态，也不应直接内嵌受保护的音视频流；统一落到带确认的文件下载页。
  if (!props.editor.isEditable && kind.value === 'mediaCard' && attrs.value.id) {
    return `/files/${encodeURIComponent(attrs.value.id)}`
  }
  return attrs.value.url || (attrs.value.id ? `/files/${encodeURIComponent(attrs.value.id)}` : '#')
})
const refreshing = ref(false)
const refreshError = ref('')
let refreshPromise: Promise<void> | null = null
let disposed = false
let objectUrl = ''
async function refreshDrawingSnapshot(persist = false) {
  if (refreshPromise) await refreshPromise
  refreshPromise = renderDrawing(persist)
  try { await refreshPromise } finally { refreshPromise = null }
}
async function renderDrawing(persist: boolean) {
  if (!attrs.value.id) return
  const id = String(attrs.value.id)
  refreshing.value = true
  refreshError.value = ''
  try {
    const drawing = await fetchDrawing(id)
    if (disposed || String(attrs.value.id) !== id) return
    liveTitle.value = drawing.title
    linkedDocument.value = !!drawing.docId
    const scene = JSON.parse(drawing.sceneJson) as { elements?: unknown[]; appState?: Record<string, unknown>; viewBackgroundColor?: string; files?: Record<string, unknown> }
    const { exportToBlob } = await import('@excalidraw/excalidraw')
    const blob = await exportToBlob({
      elements: (scene.elements ?? []) as never,
      appState: { ...scene.appState, viewBackgroundColor: scene.viewBackgroundColor ?? scene.appState?.viewBackgroundColor ?? '#ffffff', exportBackground: true } as never,
      files: (scene.files ?? {}) as never,
      mimeType: 'image/png',
    })
    if (disposed || String(attrs.value.id) !== id) return
    const previous = objectUrl
    objectUrl = URL.createObjectURL(blob)
    liveSnapshot.value = objectUrl
    if (previous) URL.revokeObjectURL(previous)
    if (persist) {
      const uploaded = await apiUploadImage(new File([blob], `${drawing.title || '画板'}.png`, { type: 'image/png' }), '文档画板快照')
      if (!disposed && String(attrs.value.id) === id) props.updateAttributes({ snapshot: uploaded.url, title: drawing.title })
    }
  } catch {
    refreshError.value = '最新预览加载失败，暂时显示已保存的快照'
    if (persist) throw new Error('画板已保存，预览更新失败，请重试完成')
  } finally {
    refreshing.value = false
  }
}
watch(() => [kind.value, attrs.value.id], () => {
  liveTitle.value = ''; liveSnapshot.value = ''; linkedDocument.value = false
  if (kind.value === 'drawingCard' && props.editor.isEditable) void refreshDrawingSnapshot()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; if (objectUrl) URL.revokeObjectURL(objectUrl) })
const naming = ref(false), renaming = ref(false), titleDraft = ref(''), titleError = ref('')
const titleInput = ref<HTMLInputElement>()
async function editTitle() {
  titleDraft.value = String(label.value); titleError.value = ''; naming.value = true
  await nextTick(); titleInput.value?.focus(); titleInput.value?.select()
}
async function saveTitle() {
  if (!naming.value || renaming.value) return
  const title = titleDraft.value.trim(), id = String(attrs.value.id)
  if (!title) { titleError.value = '草图名称不能为空'; return }
  renaming.value = true; titleError.value = ''
  try {
    const saved = await renameDrawing(id, title)
    if (!disposed && String(attrs.value.id) === id) {
      liveTitle.value = saved.title; props.updateAttributes({ title: saved.title }); naming.value = false
    }
  } catch { titleError.value = '名称保存失败，请重试' }
  finally { renaming.value = false; if (titleError.value) { await nextTick(); titleInput.value?.focus() } }
}
function openDrawing() { if (props.editor.isEditable) editing.value = true }
</script>

<template>
  <NodeViewWrapper class="rich-card" :class="kind" contenteditable="false">
    <template v-if="kind === 'drawingCard'">
      <div class="drawing-preview" :role="props.editor.isEditable ? 'button' : undefined" :tabindex="props.editor.isEditable ? 0 : undefined" :aria-label="`画板：${label}，双击编辑`" :title="props.editor.isEditable ? '双击编辑画板' : undefined" @click.stop @dblclick.stop="openDrawing" @keydown.enter.prevent="openDrawing" @keydown.space.prevent="openDrawing">
        <img v-if="liveSnapshot || attrs.snapshot" :src="liveSnapshot || attrs.snapshot" :alt="label" />
        <div v-else class="drawing-placeholder"><i :class="icon" />{{ refreshing ? '正在加载画板…' : '空白画板' }}</div>
      </div>
      <div class="drawing-caption">
        <input v-if="naming" ref="titleInput" v-model="titleDraft" aria-label="草图名称" maxlength="128" :disabled="renaming" @blur="saveTitle" @keydown.enter.prevent="!$event.isComposing && saveTitle()" @keydown.esc.stop.prevent="naming = false" />
        <button v-else-if="props.editor.isEditable && !linkedDocument" type="button" title="点击修改草图名称" @click="editTitle">{{ label }}</button>
        <span v-else>{{ label }}</span>
        <small v-if="titleError" role="alert">{{ titleError }}</small>
      </div>
    </template>
    <div v-else class="rich-card-main">
      <i :class="icon"></i>
      <div><b>{{ label }}</b><small>{{ attrs.size || attrs.mime || href }}</small></div>
      <a :href="href" target="_blank" rel="noopener noreferrer">{{ props.editor.isEditable ? '打开' : '确认下载' }}</a>
    </div>
    <audio v-if="props.editor.isEditable && kind === 'mediaCard' && attrs.mime?.startsWith('audio/')" :src="href" controls preload="metadata"></audio>
    <video v-else-if="props.editor.isEditable && kind === 'mediaCard' && attrs.mime?.startsWith('video/')" :src="href" controls preload="metadata"></video>
    <SettingsDialogShell v-if="editing" :title="String(label)" description="在正文中编辑画板，完成后保存并更新预览" width="1400px" height="calc(100dvh - 32px)" :close-disabled="closing" @close="finishDrawing">
      <DrawingsView ref="canvasView" :drawing-id="String(attrs.id)" embedded />
      <template #footer><span v-if="closeError" role="alert">{{ closeError }}</span><WaoButton :loading="closing" @click="finishDrawing">完成并返回正文</WaoButton></template>
    </SettingsDialogShell>
    <small v-if="refreshError" class="snapshot-error">{{ refreshError }}</small>
  </NodeViewWrapper>
</template>

<style scoped>
.drawing-preview { cursor: default; }
.drawing-preview[role=button] { cursor: pointer; }
.drawing-preview:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
.drawing-preview img { display: block; width: 100%; max-height: 320px; object-fit: contain; background: var(--wao-bg-soft); }
.drawing-placeholder { display: grid; place-content: center; min-height: 120px; color: var(--wao-text-weak); }
.drawing-caption { display: flex; flex-direction: column; align-items: center; padding: var(--wao-space-2); text-align: center; }
.drawing-caption button,.drawing-caption input { max-width: 100%; border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-text); font: inherit; text-align: center; padding: var(--wao-space-1); }
.drawing-caption button { cursor: text; overflow-wrap: anywhere; }
.drawing-caption button:hover { background: var(--wao-bg-hover); }
.drawing-caption input { width: min(100%, 420px); outline: 1px solid var(--wao-primary); }
.drawing-caption small { color: var(--wao-danger-strong); }
.rich-card{margin:12px 0;border:1px solid var(--wao-line-control);border-radius:12px;background:var(--wao-bg-card);overflow:hidden}.rich-card.ProseMirror-selectednode{outline:2px solid var(--wao-primary);outline-offset:2px}.rich-card>img{display:block;width:100%;max-height:320px;object-fit:contain;background:var(--wao-bg-soft)}.rich-card-main{display:flex;align-items:center;gap:11px;padding:11px 13px}.rich-card-main>i{font-size:22px;color:var(--wao-primary-text)}.rich-card-main>div{min-width:0;flex:1;display:flex;flex-direction:column}.rich-card-main b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rich-card-main small{color:var(--wao-text-weak);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rich-card-main a{color:var(--wao-primary-text);text-decoration:none}.rich-card-main button{border:0;border-radius:6px;padding:5px 8px;background:var(--wao-primary-soft);color:var(--wao-primary-text);cursor:pointer}.rich-card-main button:disabled{opacity:.55}audio,video{display:block;width:calc(100% - 26px);margin:0 13px 13px;max-height:360px}.snapshot-error{display:block;margin:0 13px 10px;color:var(--wao-danger-strong)}
</style>
