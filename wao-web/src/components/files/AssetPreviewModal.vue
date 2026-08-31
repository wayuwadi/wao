<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fetchFilePreview } from '@/api/board'
import { absoluteUrl } from '@/utils/share'
import { renderMarkdown } from '@/utils/markdown'

export type AssetPreview = {
  kind: 'markdown' | 'image' | 'html'
  name: string
  id?: string
  url?: string
}

const props = defineProps<{ asset: AssetPreview }>()
defineEmits<{ close: [] }>()

const loading = ref(false)
const error = ref('')
const markdown = ref('')
const markdownHtml = computed(() => renderMarkdown(markdown.value))

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
  <div class="preview-mask" role="presentation" @click.self="$emit('close')">
    <section
      class="preview-dialog"
      role="dialog"
      aria-modal="true"
      :aria-label="`预览 ${asset.name}`"
    >
      <header>
        <div>
          <i class="ri-eye-line"></i><span>{{ asset.name }}</span>
        </div>
        <button aria-label="关闭预览" @click="$emit('close')"><i class="ri-close-line"></i></button>
      </header>
      <div v-if="loading" class="preview-state" role="status">
        <i class="ri-loader-4-line spin"></i>正在读取真实文件…
      </div>
      <div v-else-if="error" class="preview-state error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span>
        <button @click="load"><i class="ri-refresh-line"></i>重试</button>
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
        sandbox="allow-scripts allow-modals allow-popups"
      ></iframe>
    </section>
  </div>
</template>

<style scoped>
.preview-mask {
  position: fixed;
  inset: 0;
  z-index: 1600;
  padding: 24px;
  background: rgba(20, 23, 34, 0.52);
  display: grid;
  place-items: center;
}
.preview-dialog {
  width: min(1080px, 100%);
  height: min(780px, calc(100vh - 48px));
  background: var(--wao-bg-card);
  border-radius: 14px;
  box-shadow: 0 22px 70px rgba(18, 22, 36, 0.28);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
header {
  height: 48px;
  flex: 0 0 auto;
  padding: 0 14px 0 18px;
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
header div {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 650;
}
header span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
header button {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
header button:hover {
  background: var(--wao-bg-hover);
}
.preview-state {
  flex: 1;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 10px;
  color: var(--wao-text-weak);
}
.preview-state.error {
  color: var(--wao-danger);
}
.preview-state button {
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 8px;
  padding: 7px 12px;
  cursor: pointer;
}
.markdown-body {
  flex: 1;
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
@media (max-width: 640px) {
  .preview-mask {
    padding: 0;
  }
  .preview-dialog {
    width: 100%;
    height: 100%;
    border-radius: 0;
  }
}
</style>
