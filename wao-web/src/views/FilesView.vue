<script setup lang="ts">
import { ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'

const data = useDataStore()
const tab = ref<'files' | 'img'>('files')
</script>

<template>
  <div class="files">
    <div class="ftop">
      <div class="ftitle">文件与图床</div>
      <div class="seg">
        <button v-for="t in (['files', 'img'] as const)" :key="t" class="seg-btn" :class="{ on: tab === t }" @click="tab = t">
          {{ t === 'files' ? '文件' : '图床' }}
        </button>
      </div>
      <div class="spacer"></div>
      <button class="btn-primary" @click="toast('演示：拖拽上传在正式版本提供', 'ri-upload-2-line')"><i class="ri-upload-2-line"></i>上传</button>
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
      <div class="note">系统各处上传的图片统一归档到图床，并提供公网访问链接（演示环境为示意图与虚构链接）。</div>
      <div class="img-grid">
        <div v-for="(im, i) in data.imgs" :key="i" class="img-card">
          <div class="img-thumb" :style="{ background: `repeating-linear-gradient(45deg, ${im.c1} 0 10px, ${im.c2} 10px 20px)` }">
            <span class="img-chip wao-mono">{{ im.name }}</span>
          </div>
          <div class="img-info">
            <div class="img-name">{{ im.name }}</div>
            <div class="img-meta">{{ im.size }} · {{ im.time }} · 来自 {{ im.from }}</div>
            <button class="img-copy" @click="copyText(im.url, '已复制公网链接（演示）')"><i class="ri-links-line"></i>复制公网链接</button>
          </div>
        </div>
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
.img-thumb {
  height: 110px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.img-chip {
  font-size: 10.5px;
  color: #7a7d89;
  background: rgba(255, 255, 255, 0.85);
  border-radius: 5px;
  padding: 2px 8px;
  max-width: 88%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
