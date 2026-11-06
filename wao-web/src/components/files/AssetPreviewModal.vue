<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchFilePreview } from '@/api/board'
import { absoluteUrl } from '@/utils/share'
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
defineEmits<{ close: [] }>()

const loading = ref(false)
const error = ref('')
const markdown = ref('')
const markdownHtml = computed(() => renderMarkdown(markdown.value))
const htmlSandbox = computed(() => props.asset.sourceType === 'MANUAL_UPLOAD'
  ? 'allow-scripts allow-same-origin allow-forms allow-modals allow-popups'
  : 'allow-scripts allow-modals allow-popups')

async function load() {
  error.value = ''
  markdown.value = ''
  if (props.asset.kind !== 'markdown' || !props.asset.id) return
  loading.value = true
  try {
    markdown.value = (await fetchFilePreview(props.asset.id)).content
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '文件预览失败，请重试'
  } finally {
    loading.value = false
  }
}

watch(() => props.asset, load, { immediate: true })
</script>

<template>
  <SettingsDialogShell :title="`预览 ${asset.name}`" width="1080px" height="min(780px, calc(100dvh - 48px))" icon="ri-eye-line" appearance="compact" @close="$emit('close')">
      <div v-if="loading" class="preview-state" role="status">
        <i class="ri-loader-4-line spin"></i>正在读取真实文件…
      </div>
      <div v-else-if="error" class="preview-state error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span>
        <WaoButton @click="load"><i class="ri-refresh-line"></i>重试</WaoButton>
      </div>
      <article
        v-else-if="asset.kind === 'markdown'"
        class="markdown-body"
        v-html="markdownHtml"
      ></article>
      <div v-else-if="asset.kind === 'image'" class="image-preview">
        <img :src="asset.url" :alt="asset.name" />
      </div>
      <iframe
        v-else
        class="html-preview"
        :src="absoluteUrl(asset.url || '')"
        :title="asset.name"
        :sandbox="htmlSandbox"
        tabindex="0"
      ></iframe>
  </SettingsDialogShell>
</template>

<style scoped>
.preview-state {
  flex: 1;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 10px;
  color: var(--wao-text-3);
}
.preview-state.error {
  color: var(--wao-danger);
}
.markdown-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 28px clamp(20px, 5vw, 72px);
  line-height: 1.75;
  overflow-wrap: anywhere;
}
.markdown-body :deep(img) {
  max-width: 100%;
}
.markdown-body :deep(pre) {
  overflow: auto;
  padding: 14px;
  border-radius: 9px;
  background: var(--wao-bg-page);
}
.image-preview {
  flex: 1;
  min-height: 0;
  padding: 18px;
  display: grid;
  place-items: center;
  background: var(--wao-bg-page);
  overflow: auto;
}
.image-preview img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}
.html-preview {
  flex: 1;
  min-height: 0;
  width: 100%;
  border: 0;
  background: #fff;
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
