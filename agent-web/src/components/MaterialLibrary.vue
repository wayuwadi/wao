<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listMedia, uploadMedia } from '@/api/publish'
import { formatBytes } from '@/publishStatus'
import type { MediaAsset } from '@/types'

const assets = ref<MediaAsset[]>([])
const loading = ref(true)
const uploading = ref(false)
const error = ref('')
const message = ref('')

function kindLabel(kind: string) {
  return kind === 'VIDEO' ? '视频' : kind === 'IMAGE' ? '图片' : kind
}

function kindIcon(kind: string) {
  return kind === 'VIDEO' ? 'ri-video-line' : 'ri-image-line'
}

function formatCreatedAt(value: string) {
  const time = new Date(value)
  return Number.isNaN(time.getTime()) ? value : time.toLocaleString('zh-CN', { hour12: false })
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    assets.value = await listMedia()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '素材列表读取失败'
  } finally {
    loading.value = false
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploading.value = true
  error.value = ''
  message.value = ''
  try {
    const asset = await uploadMedia(file)
    await refresh()
    message.value = `已上传「${asset.originalName}」`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '素材上传失败，请重试'
  } finally {
    uploading.value = false
    input.value = ''
  }
}

onMounted(refresh)
</script>

<template>
  <div class="material-library">
    <div class="library-head">
      <div>
        <h2>素材库</h2>
        <p>这里只展示当前内容工作区已经真实上传并落库的图片和视频。</p>
      </div>
      <div class="library-actions">
        <button class="btn-ghost" :disabled="loading" @click="refresh">
          <i class="ri-refresh-line"></i>{{ loading ? '读取中…' : '刷新' }}
        </button>
        <label class="btn-primary upload-button" :class="{ disabled: uploading }">
          <input type="file" accept="image/*,video/*" :disabled="uploading" @change="upload" />
          <i class="ri-upload-cloud-2-line"></i>{{ uploading ? '上传中…' : '上传素材' }}
        </label>
      </div>
    </div>

    <p v-if="message" class="message-bar"><i class="ri-checkbox-circle-line"></i>{{ message }}</p>
    <div v-if="error" class="library-error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ error }}</span><button @click="refresh">重试</button>
    </div>
    <div v-if="loading" class="library-loading"><span></span>正在读取真实素材…</div>
    <div v-else-if="!assets.length" class="empty library-empty">
      <i class="ri-gallery-line"></i><b>还没有素材</b>
      <span>上传第一张图片或视频后，会在这里显示真实文件记录。</span>
    </div>
    <div v-else class="asset-grid">
      <article v-for="asset in assets" :key="asset.id" class="asset-card">
        <div class="asset-icon"><i :class="kindIcon(asset.kind)"></i></div>
        <div class="asset-meta">
          <strong :title="asset.originalName">{{ asset.originalName }}</strong>
          <span>{{ kindLabel(asset.kind) }} · {{ formatBytes(asset.byteSize) }}</span>
          <small>{{ formatCreatedAt(asset.createdAt) }}</small>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.material-library{display:grid;gap:14px}.library-head{padding:16px;display:flex;align-items:center;justify-content:space-between;gap:16px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.library-head h2{margin:0 0 4px;font-size:15px}.library-head p{margin:0;color:var(--wao-text-weak);font-size:11px}.library-actions{display:flex;gap:8px}.upload-button{min-height:34px;padding:0 13px;display:inline-flex;align-items:center;gap:6px;border-radius:8px;cursor:pointer;font-size:12px}.upload-button input{display:none}.upload-button.disabled{opacity:.55;cursor:not-allowed}.library-error{padding:10px 12px;display:flex;align-items:center;gap:8px;color:var(--wao-danger-strong);background:var(--wao-danger-bg);border:1px solid var(--wao-danger-line);border-radius:9px;font-size:12px}.library-error span{flex:1}.library-error button{border:0;background:transparent;color:inherit;cursor:pointer}.library-loading{min-height:240px;display:grid;place-items:center;align-content:center;gap:10px;color:var(--wao-text-weak);font-size:12px}.library-loading>span{width:28px;height:28px;border:3px solid var(--wao-primary-line);border-top-color:var(--wao-primary);border-radius:50%;animation:spin .8s linear infinite}.library-empty{min-height:230px;display:grid;place-items:center;align-content:center;gap:7px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card)}.library-empty i{font-size:30px;color:var(--wao-primary-text)}.asset-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:11px}.asset-card{min-width:0;padding:13px;display:flex;align-items:center;gap:11px;border:1px solid var(--wao-line);border-radius:11px;background:var(--wao-bg-card)}.asset-icon{width:42px;height:42px;display:grid;place-items:center;flex:none;border-radius:9px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:21px}.asset-meta{min-width:0;display:grid;gap:3px}.asset-meta strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.asset-meta span,.asset-meta small{color:var(--wao-text-weak);font-size:10px}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:640px){.library-head{align-items:stretch;flex-direction:column}.library-actions{display:grid;grid-template-columns:1fr 1fr}.library-actions>*{justify-content:center}.asset-grid{grid-template-columns:1fr}}
</style>
