<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchFilePreview } from '@/api/board'
import { renderMarkdown } from '@/utils/markdown'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'

export type AssetPreview = {
  kind: 'markdown' | 'image' | 'html'
  name: string
  id?: string
  url?: string
  sourceType?: string
}

const props = defineProps<{ asset: AssetPreview }>()
const emit = defineEmits<{ close: [] }>()
const loading = ref(false)
const error = ref('')
const markdown = ref('')
const mediaVersion = ref(0)
const markdownHtml = computed(() => renderMarkdown(markdown.value))
const htmlSandbox = computed(() => props.asset.sourceType === 'MANUAL_UPLOAD'
  ? 'allow-scripts allow-same-origin allow-forms allow-modals allow-popups'
  : 'allow-scripts allow-modals allow-popups')
const mediaUrl = computed(() => {
  if (!props.asset.url) return ''
  try {
    const url = new URL(props.asset.url, window.location.origin)
    if (url.protocol === 'http:' || url.protocol === 'https:') return url.href
    if (props.asset.kind === 'image' && /^data:image\//i.test(props.asset.url)) return props.asset.url
  } catch { /* Invalid addresses are shown as a recoverable preview error. */ }
  return ''
})
let request: AbortController | undefined
let generation = 0
let disposed = false

function stopRequest() {
  generation++
  request?.abort()
  request = undefined
}
function close() {
  stopRequest()
  emit('close')
}
async function load() {
  stopRequest()
  const current = generation
  error.value = ''
  markdown.value = ''
  loading.value = false
  mediaVersion.value++
  if (props.asset.kind !== 'markdown') {
    if (!mediaUrl.value) error.value = '没有可预览的地址，请刷新资源列表后重试。'
    else loading.value = true
    return
  }
  if (!props.asset.id) {
    error.value = '缺少文件信息，请刷新资源列表后重试。'
    return
  }
  const controller = new AbortController()
  request = controller
  loading.value = true
  try {
    const result = await fetchFilePreview(props.asset.id, { signal: controller.signal })
    if (disposed || current !== generation) return
    markdown.value = result.content
  } catch (cause) {
    if (disposed || current !== generation || controller.signal.aborted) return
    error.value = cause instanceof Error ? cause.message : '文件预览失败，请重试。'
  } finally {
    if (!disposed && current === generation) {
      loading.value = false
      request = undefined
    }
  }
}
function isCurrentMedia(event: Event) {
  return !disposed && (event.currentTarget as HTMLElement | null)?.dataset.previewVersion === String(mediaVersion.value)
}
function mediaLoaded(event: Event) {
  if (!isCurrentMedia(event)) return
  loading.value = false
}
function mediaFailed(event: Event) {
  if (!isCurrentMedia(event)) return
  loading.value = false
  error.value = props.asset.kind === 'image'
    ? '图片未能加载，请检查网络或刷新资源列表后重试。'
    : '网页未能加载，请检查网络后重试。'
}
watch(() => [props.asset.kind, props.asset.id, props.asset.url, props.asset.sourceType], load, { immediate: true })
onBeforeUnmount(() => {
  disposed = true
  stopRequest()
})
</script>

<template>
  <SettingsDialogShell
    :title="`预览 ${asset.name}`"
    width="1080px"
    height="min(780px, calc(100dvh - 48px))"
    appearance="compact"
    icon="ri-eye-line"
    @close="close"
  >
    <div class="preview-content" :aria-busy="loading">
      <div v-if="loading" class="preview-state" role="status">
        <i class="ri-loader-4-line spin" aria-hidden="true"></i>
        {{ asset.kind === 'image' ? '正在加载图片…' : asset.kind === 'html' ? '正在打开网页…' : '正在读取文件…' }}
      </div>
      <div v-if="error" class="preview-state error" role="alert">
        <i class="ri-error-warning-line" aria-hidden="true"></i><span>{{ error }}</span>
        <WaoButton @click="load"><i class="ri-refresh-line" aria-hidden="true"></i>重试</WaoButton>
      </div>
      <template v-else-if="asset.kind === 'markdown' && !loading">
        <article v-if="markdown" class="markdown-body" v-html="markdownHtml"></article>
        <div v-else class="preview-state" role="status">这个文件还没有内容。</div>
      </template>
      <div v-else-if="asset.kind === 'image' && mediaUrl" v-show="!loading && !error" class="image-preview">
        <img :key="mediaVersion" :data-preview-version="mediaVersion" :src="mediaUrl" :alt="asset.name" decoding="async" @load="mediaLoaded" @error="mediaFailed" />
      </div>
      <iframe
        v-else-if="asset.kind === 'html' && mediaUrl"
        v-show="!loading && !error"
        :key="mediaVersion"
        :data-preview-version="mediaVersion"
        class="html-preview"
        :src="mediaUrl"
        :title="asset.name"
        :sandbox="htmlSandbox"
        tabindex="0"
        @load="mediaLoaded"
        @error="mediaFailed"
      ></iframe>
    </div>
  </SettingsDialogShell>
</template>

<style scoped>
:deep(.dialog-heading > div) { min-width: 0; }
:deep(.dialog-heading h2) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}
.preview-state {
  flex: 1;
  padding: var(--wao-space-3);
  display: grid;
  place-content: center;
  justify-items: center;
  gap: var(--wao-space-1);
  color: var(--wao-text-3);
  overflow-wrap: anywhere;
  text-align: center;
}
.preview-state.error { color: var(--wao-danger-strong); }
.markdown-body {
  flex: 1;
  overflow: auto;
  padding: var(--wao-space-3) clamp(16px, 5vw, 72px);
  line-height: var(--wao-line-body);
  font-size: var(--wao-font-reading);
  overflow-wrap: anywhere;
}
.markdown-body :deep(img) { max-width: 100%; height: auto; }
.markdown-body :deep(pre) {
  overflow: auto;
  padding: var(--wao-space-2);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
}
.markdown-body :deep(table) { display: block; max-width: 100%; overflow: auto; }
.image-preview {
  flex: 1;
  min-height: 0;
  padding: var(--wao-space-2);
  display: grid;
  place-items: center;
  background: var(--wao-bg-sub);
  overflow: auto;
}
.image-preview img { max-width: 100%; max-height: 100%; object-fit: contain; }
.html-preview { flex: 1; min-height: 0; width: 100%; border: 0; background: var(--wao-bg-card); }
.spin { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
