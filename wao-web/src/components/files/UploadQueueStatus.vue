<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useUploadStore } from '@/stores/uploads'
import WaoButton from '@/components/ui/WaoButton.vue'
const uploads = useUploadStore()
function beforeUnload(event: BeforeUnloadEvent) {
  if (!uploads.uploading && !uploads.failedUploads.length) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>
<template>
  <div v-if="uploads.uploadAttempts.length" class="upload-queue-status" aria-label="上传队列">
    <span role="status"
      >已上传 {{ uploads.successfulUploads }} 项<span v-if="uploads.uploading"
        >，还有 {{ uploads.uploading }} 项正在处理</span
      ><span v-if="uploads.failedUploads.length"
        >，{{ uploads.failedUploads.length }} 项需重试或处理</span
      ></span
    >
    <RouterLink :to="{ path: '/files', query: { uploads: '1' } }">查看上传</RouterLink>
    <WaoButton
      v-if="!uploads.uploading && !uploads.failedUploads.length"
      variant="ghost"
      aria-label="清除已完成上传记录"
      @click="uploads.clear()"
      >收起</WaoButton
    >
  </div>
</template>
<style scoped>
.upload-queue-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wao-space-1);
  padding: var(--wao-space-1) var(--wao-space-2);
  border-bottom: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
}
a {
  color: var(--wao-primary-text);
  min-height: 44px;
  display: inline-flex;
  align-items: center;
}
</style>
