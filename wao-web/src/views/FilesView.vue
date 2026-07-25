<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { copyText } from '@/utils/clipboard'
import { absoluteUrl } from '@/utils/share'
import { toast } from '@/composables/toast'
import DemoBanner from '@/components/ui/DemoBanner.vue'

const data = useDataStore()
const tab = ref<'files' | 'img'>('img')
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(0)

// 打开页面时重新拉一次，让其他标签页 / 其他人上传的图也出现
onMounted(() => data.refreshImages())

/** 从图床页直接上传，来源标成「图床」以便和任务里粘贴的图区分。 */
async function uploadFiles(files: FileList | File[]) {
  const list = Array.from(files).filter((f) => f.type.startsWith('image/'))
  if (!list.length) {
    toast('请选择图片文件', 'ri-error-warning-line')
    return
  }
  uploading.value += list.length
  let failed = 0
  try {
    for (const f of list) {
      if (f.size > data.MAX_IMAGE_BYTES) {
        toast(`「${f.name}」超过 10MB，已跳过`, 'ri-error-warning-line')
        failed++
        continue
      }
      if (!(await data.uploadImage(f, '图床'))) failed++
    }
  } finally {
    uploading.value -= list.length
  }
  const ok = list.length - failed
  if (ok) toast(`已上传 ${ok} 张图片到图床`, 'ri-image-add-line')
}

function onPick(e: Event) {
  const files = (e.target as HTMLInputElement).files
  if (files?.length) uploadFiles(files)
  if (fileInput.value) fileInput.value.value = ''
}
function onDrop(e: DragEvent) {
  const files = e.dataTransfer?.files
  if (files?.length) uploadFiles(files)
}

/** 图床里的图是站内相对地址，复制出去必须补成绝对地址才能公网访问。 */
function copyImageLink(url: string) {
  copyText(absoluteUrl(url), '已复制公网链接')
}
function openImage(url: string) {
  window.open(absoluteUrl(url), '_blank')
}
async function removeImage(id: string | undefined, name: string) {
  if (!id) {
    toast('内置示例图不可删除', 'ri-information-line')
    return
  }
  await data.deleteImage(id)
  toast(`已删除「${name}」`, 'ri-delete-bin-line')
}
</script>

<template>
  <div class="files">
    <DemoBanner
      icon="ri-hard-drive-2-line"
      title="文件模块仍是演示"
      text="图床已经是真实功能：系统各处上传 / 粘贴的图片都会归档到这里，可预览、复制公网链接、删除。文件（附件）标签页仍为示例数据，真实上传与下载将在后续迭代接入。"
      storage-key="files"
    />
    <div class="ftop">
      <div class="ftitle">文件与图床</div>
      <div class="seg">
        <button v-for="t in (['files', 'img'] as const)" :key="t" class="seg-btn" :class="{ on: tab === t }" @click="tab = t">
          {{ t === 'files' ? '文件' : '图床' }}
        </button>
      </div>
      <div class="spacer"></div>
      <button v-if="tab === 'img'" class="btn-primary" :disabled="uploading > 0" @click="fileInput?.click()">
        <i class="ri-upload-2-line"></i>{{ uploading > 0 ? `上传中 ${uploading}…` : '上传图片' }}
      </button>
      <button v-else class="btn-primary" @click="toast('演示：文件上传在正式版本提供', 'ri-upload-2-line')"><i class="ri-upload-2-line"></i>上传</button>
      <input ref="fileInput" type="file" accept="image/*" multiple hidden @change="onPick" />
    </div>

    <template v-if="tab === 'files'">
      <div class="note">上传的文件自动生成公开直链，可直接分享下载（演示链接为示意，不可真实访问）。</div>
      <div class="file-list">
        <div v-for="(f, i) in data.files" :key="i" class="file-row">
          <div class="file-icon"><i :class="f.icon"></i></div>
          <div class="file-main">
            <div class="file-name">{{ f.name }}</div>
            <div class="file-sub">{{ f.by }} · {{ f.time }} · <span class="wao-mono">{{ f.link }}</span></div>
          </div>
          <span class="file-size">{{ f.size }}</span>
          <button class="copy-btn" @click="copyText(f.link, '已复制直链（演示）')"><i class="ri-links-line"></i>复制直链</button>
          <button class="dl-btn" title="下载" @click="toast('开始下载（演示）', 'ri-download-2-line')"><i class="ri-download-2-line"></i></button>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="note">任务描述、子任务、新建任务里粘贴或上传的图片都统一归档到这里，并提供公网访问链接。</div>
      <div class="img-drop" @dragover.prevent @drop.prevent="onDrop">
        <div class="img-grid">
          <div v-for="(im, i) in data.imgs" :key="im.id ?? i" class="img-card">
            <div class="img-thumb" :style="{ background: `repeating-linear-gradient(45deg, ${im.c1} 0 10px, ${im.c2} 10px 20px)` }">
              <img :src="im.url" :alt="im.name" loading="lazy" @click="openImage(im.url)" />
              <button v-if="im.id" class="img-del" title="从图床删除" @click.stop="removeImage(im.id, im.name)">
                <i class="ri-delete-bin-line"></i>
              </button>
            </div>
            <div class="img-info">
              <div class="img-name" :title="im.name">{{ im.name }}</div>
              <div class="img-meta">{{ im.size }} · {{ im.time }} · 来自 {{ im.from }}</div>
              <button class="img-copy" @click="copyImageLink(im.url)"><i class="ri-links-line"></i>复制公网链接</button>
            </div>
          </div>
        </div>
        <div v-if="!data.imgs.length" class="img-empty">
          <i class="ri-image-add-line"></i>
          <div>图床还是空的——把图片拖到这里，或点右上角「上传图片」</div>
        </div>
        <div v-else class="drop-tip">也可以把图片直接拖到这一片区域上传</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.files {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}
.ftop {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.ftitle {
  font-size: 20px;
  font-weight: 700;
}
.seg {
  display: flex;
  background: var(--wao-divider);
  border-radius: 9px;
  padding: 3px;
  gap: 2px;
}
.seg-btn {
  height: 26px;
  padding: 0 12px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: #7a7d89;
  font-size: 12.5px;
  cursor: pointer;
}
.seg-btn.on {
  background: #fff;
  color: var(--wao-text);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(16, 18, 28, 0.1);
}
.btn-primary {
  height: 32px;
  padding: 0 13px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary i {
  font-size: 14px;
}
.note {
  margin-top: 12px;
  font-size: 12px;
  color: var(--wao-text-weak);
}

.file-list {
  margin-top: 10px;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 16px;
  border-bottom: 1px solid var(--wao-divider-2);
}
.file-row:last-child {
  border-bottom: none;
}
.file-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: #f0f1f4;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.file-icon i {
  font-size: 16px;
  color: var(--wao-text-3);
}
.file-main {
  flex: 1;
  min-width: 0;
}
.file-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.file-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.file-size {
  font-size: 12px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.copy-btn {
  height: 27px;
  padding: 0 11px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 7px;
  font-size: 12px;
  color: var(--wao-text-2);
  cursor: pointer;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.copy-btn:hover {
  background: #f4f5f8;
}
.copy-btn i {
  font-size: 13px;
}
.dl-btn {
  width: 27px;
  height: 27px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.dl-btn:hover {
  background: #f4f5f8;
}
.dl-btn i {
  font-size: 13px;
}

.img-grid {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 12px;
}
.img-card {
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
/* 底色是加载中 / 破图时的兜底，真图盖在上面 */
.img-thumb {
  position: relative;
  height: 110px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.img-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  cursor: zoom-in;
}
.img-del {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 7px;
  background: rgba(23, 25, 35, 0.55);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.12s;
}
.img-card:hover .img-del {
  opacity: 1;
}
.img-del:hover {
  background: #bc4141;
}
.img-del i {
  font-size: 13px;
}
.img-drop {
  border-radius: 12px;
}
.img-empty {
  margin-top: 10px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 12px;
  padding: 44px 20px;
  text-align: center;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.img-empty i {
  display: block;
  font-size: 26px;
  margin-bottom: 8px;
  color: var(--wao-text-faint-2);
}
.drop-tip {
  margin-top: 10px;
  text-align: center;
  font-size: 11.5px;
  color: var(--wao-text-faint);
}
.img-info {
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.img-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.img-meta {
  font-size: 11px;
  color: var(--wao-text-weak);
}
.img-copy {
  margin-top: 3px;
  height: 26px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 7px;
  font-size: 11.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
}
.img-copy:hover {
  background: #f4f5f8;
}
.img-copy i {
  font-size: 12px;
}
</style>
