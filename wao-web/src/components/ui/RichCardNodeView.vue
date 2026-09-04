<script setup lang="ts">
import { computed, ref } from 'vue'
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import { fetchDrawing } from '@/api/drawings'
import { apiUploadImage } from '@/api/board'

const props = defineProps<NodeViewProps>()
const attrs = computed(() => props.node.attrs)
const kind = computed(() => props.node.type.name)
const icon = computed(() => ({
  fileCard: 'ri-attachment-2', mediaCard: attrs.value.mime?.startsWith('video/') ? 'ri-video-line' : 'ri-volume-up-line',
  linkCard: 'ri-links-line', drawingCard: 'ri-shape-line',
}[kind.value] ?? 'ri-file-line'))
const label = computed(() => attrs.value.title || attrs.value.name || (kind.value === 'drawingCard' ? '未命名画板' : attrs.value.url))
const href = computed(() => {
  // 分享页没有登录态，也不应直接内嵌受保护的音视频流；统一落到带确认的文件下载页。
  if (!props.editor.isEditable && kind.value === 'mediaCard' && attrs.value.id) {
    return `/files/${encodeURIComponent(attrs.value.id)}`
  }
  return attrs.value.url || (attrs.value.id ? `/files/${encodeURIComponent(attrs.value.id)}` : '#')
})
const refreshing = ref(false)
const refreshError = ref('')
async function refreshDrawingSnapshot() {
  if (!attrs.value.id || refreshing.value) return
  refreshing.value = true
  refreshError.value = ''
  try {
    const drawing = await fetchDrawing(String(attrs.value.id))
    const scene = JSON.parse(drawing.sceneJson) as { elements?: unknown[]; viewBackgroundColor?: string; files?: Record<string, unknown> }
    const { exportToBlob } = await import('@excalidraw/excalidraw')
    const blob = await exportToBlob({
      elements: (scene.elements ?? []) as never,
      appState: { viewBackgroundColor: scene.viewBackgroundColor ?? '#ffffff', exportBackground: true } as never,
      files: (scene.files ?? {}) as never,
      mimeType: 'image/png',
    })
    const uploaded = await apiUploadImage(new File([blob], `${drawing.title || '画板'}.png`, { type: 'image/png' }), '文档画板快照')
    props.updateAttributes({ snapshot: uploaded.url, title: drawing.title })
  } catch {
    refreshError.value = '快照刷新失败'
  } finally {
    refreshing.value = false
  }
}
</script>

<template>
  <NodeViewWrapper class="rich-card" :class="kind" contenteditable="false">
    <img v-if="kind === 'drawingCard' && attrs.snapshot" :src="attrs.snapshot" :alt="label" />
    <div class="rich-card-main">
      <i :class="icon"></i>
      <div><b>{{ label }}</b><small>{{ attrs.size || attrs.mime || href }}</small></div>
      <button v-if="kind === 'drawingCard' && props.editor.isEditable" type="button" :disabled="refreshing" @click="refreshDrawingSnapshot">{{ refreshing ? '刷新中…' : '刷新快照' }}</button>
      <a :href="href" target="_blank" rel="noopener noreferrer">{{ kind === 'drawingCard' ? '打开画板' : props.editor.isEditable ? '打开' : '确认下载' }}</a>
    </div>
    <audio v-if="props.editor.isEditable && kind === 'mediaCard' && attrs.mime?.startsWith('audio/')" :src="href" controls preload="metadata"></audio>
    <video v-else-if="props.editor.isEditable && kind === 'mediaCard' && attrs.mime?.startsWith('video/')" :src="href" controls preload="metadata"></video>
    <small v-if="refreshError" class="snapshot-error">{{ refreshError }}</small>
  </NodeViewWrapper>
</template>

<style scoped>
.rich-card{margin:12px 0;border:1px solid var(--wao-line-control);border-radius:12px;background:var(--wao-bg-card);overflow:hidden}.rich-card.ProseMirror-selectednode{outline:2px solid var(--wao-primary);outline-offset:2px}.rich-card>img{display:block;width:100%;max-height:320px;object-fit:contain;background:var(--wao-bg-soft)}.rich-card-main{display:flex;align-items:center;gap:11px;padding:11px 13px}.rich-card-main>i{font-size:22px;color:var(--wao-primary)}.rich-card-main>div{min-width:0;flex:1;display:flex;flex-direction:column}.rich-card-main b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rich-card-main small{color:var(--wao-text-weak);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rich-card-main a{color:var(--wao-primary);text-decoration:none}.rich-card-main button{border:0;border-radius:6px;padding:5px 8px;background:var(--wao-primary-soft);color:var(--wao-primary);cursor:pointer}.rich-card-main button:disabled{opacity:.55}audio,video{display:block;width:calc(100% - 26px);margin:0 13px 13px;max-height:360px}.snapshot-error{display:block;margin:0 13px 10px;color:var(--wao-danger-strong)}
</style>
