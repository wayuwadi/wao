<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import { chatShareLink } from '@/utils/share'
const ui = useUiStore()
import { computed, onMounted, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import { copyText } from '@/utils/clipboard'
import { absoluteUrl, fileShareUrl } from '@/utils/share'
import AssetPreviewModal, { type AssetPreview } from './AssetPreviewModal.vue'
import FileDisplayNameEditor from './FileDisplayNameEditor.vue'
import AssetReplaceModal, { type ReplacementTarget } from '@/components/files/AssetReplaceModal.vue'
import { fileDisplayName } from '@/utils/files'
import type { HostedPage } from '@/types'

const props = defineProps<{ projectId: string; projectName: string }>()
const data = useDataStore()
const tab = ref<'files' | 'images' | 'pages'>('files')
const input = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const uploadError = ref('')
const dragging = ref(false)
const replacement = ref<ReplacementTarget | null>(null)
const preview = ref<AssetPreview | null>(null)
const htmlDialog = ref(false)
const editingPage = ref<HostedPage | null>(null)
const htmlFile = ref<File | null>(null)
const htmlTitle = ref('')
const htmlSlug = ref('')
const htmlSaving = ref(false)
const htmlFormError = ref('')

const files = computed(() => data.files.filter((item) => item.projectId === props.projectId))
const images = computed(() => data.imgs.filter((item) => item.projectId === props.projectId))
const pages = computed(() => data.hostedPages.filter((item) => item.projectId === props.projectId))
const total = computed(() => files.value.length + images.value.length + pages.value.length)

onMounted(() => {
  void Promise.all([data.refreshFiles(), data.refreshImages(), data.refreshHostedPages()])
})

function isHtml(file: File) {
  return file.type === 'text/html' || /\.html?$/i.test(file.name)
}
function isMarkdown(name: string) {
  return /\.(?:md|markdown)$/i.test(name)
}
function baseName(name: string) {
  return name
    .replace(/^.*[\\/]/, '')
    .replace(/\.html?$/i, '')
    .trim()
}
function suggestedSlug(name: string) {
  const base = baseName(name)
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48)
  const prefix = `project-${props.projectId
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .slice(0, 20)}`
  const candidate = `${prefix}-${base || 'page'}`.replace(/-+/g, '-').slice(0, 72).replace(/-$/, '')
  return `${candidate}-${Math.random().toString(36).slice(2, 7)}`
}
function openHtmlDialog(file: File) {
  editingPage.value = null
  htmlFile.value = file
  htmlTitle.value = baseName(file.name) || '未命名页面'
  htmlSlug.value = suggestedSlug(file.name)
  htmlFormError.value = ''
  htmlDialog.value = true
  tab.value = 'pages'
}

function editPage(page: HostedPage) {
  editingPage.value = page
  htmlFile.value = null
  htmlTitle.value = page.title
  htmlSlug.value = page.slug
  htmlFormError.value = ''
  htmlDialog.value = true
}

function closeHtmlDialog() {
  if (htmlSaving.value) return
  htmlDialog.value = false
  htmlFormError.value = ''
}

async function uploadFiles(selected: File[]) {
  uploadError.value = ''
  uploading.value = true
  let failed = 0
  try {
    for (const file of selected) {
      if (!(await data.uploadFile(file, props.projectId))) failed++
    }
  } finally {
    uploading.value = false
  }
  if (selected.length - failed)
    toast(`已上传 ${selected.length - failed} 个项目文件`, 'ri-upload-cloud-2-line')
  if (failed) uploadError.value = `${failed} 个文件上传失败，请检查大小或服务状态后重试`
}

async function uploadImages(selected: File[]) {
  uploadError.value = ''
  uploading.value = true
  let failed = 0
  try {
    for (const file of selected) {
      const result = await data.uploadImage(file, `项目 · ${props.projectName}`, {
        requirePersisted: true,
        projectId: props.projectId,
      })
      if (!result) failed++
    }
  } finally {
    uploading.value = false
  }
  if (selected.length - failed)
    toast(`已上传 ${selected.length - failed} 张项目图片`, 'ri-image-add-line')
  if (failed) uploadError.value = `${failed} 张图片上传失败，请检查格式、大小或服务状态后重试`
}

function classify(selected: File[]) {
  const pictures = selected.filter((file) => file.type.startsWith('image/'))
  const html = selected.filter((file) => !file.type.startsWith('image/') && isHtml(file))
  const attachments = selected.filter((file) => !file.type.startsWith('image/') && !isHtml(file))
  if (pictures.length) void uploadImages(pictures)
  if (attachments.length) void uploadFiles(attachments)
  if (html[0]) openHtmlDialog(html[0])
  if (html.length > 1) toast('一次先配置 1 个 HTML，其余请再次上传', 'ri-information-line')
  if (pictures.length && !attachments.length && !html.length) tab.value = 'images'
  if (attachments.length && !pictures.length && !html.length) tab.value = 'files'
}

function pick(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files?.length) classify(Array.from(target.files))
  target.value = ''
}
function drop(event: DragEvent) {
  dragging.value = false
  if (event.dataTransfer?.files.length) classify(Array.from(event.dataTransfer.files))
}

async function saveHtml() {
  if (
    (!editingPage.value && !htmlFile.value) ||
    !htmlTitle.value.trim() ||
    !htmlSlug.value.trim()
  ) {
    htmlFormError.value = editingPage.value
      ? '请填写页面标题和链接后缀'
      : '请填写页面标题、链接后缀并选择 HTML 文件'
    return
  }
  htmlSaving.value = true
  htmlFormError.value = ''
  try {
    const page = await data.saveHostedPage({
      id: editingPage.value?.id,
      html: htmlFile.value ?? undefined,
      title: htmlTitle.value,
      slug: htmlSlug.value,
      projectId: editingPage.value ? undefined : props.projectId,
    })
    htmlDialog.value = false
    if (editingPage.value) {
      toast('WAO 显示名称已保存', 'ri-check-line')
    } else {
      preview.value = { kind: 'html', name: page.title, url: page.url }
      toast('HTML 已归入项目并可站内预览', 'ri-html5-line')
    }
  } catch (cause) {
    htmlFormError.value =
      cause instanceof Error
        ? cause.message
        : `${editingPage.value ? '名称保存' : 'HTML 发布'}失败，请重试`
  } finally {
    htmlSaving.value = false
  }
}

async function removeFile(id: string | undefined, name: string) {
  if (!id || !window.confirm(`删除项目文件“${name}”？`)) return
  try {
    await data.deleteFile(id)
  } catch {
    toast('文件删除失败，请重试', 'ri-error-warning-line')
  }
}
async function removeImage(id: string | undefined, name: string) {
  if (!id || !window.confirm(`删除项目图片“${name}”？`)) return
  try {
    await data.deleteImage(id)
  } catch {
    toast('图片删除失败，请重试', 'ri-error-warning-line')
  }
}
async function removePage(page: HostedPage) {
  if (!window.confirm(`删除托管页面“${page.title}”？公网链接将立即失效。`)) return
  try {
    await data.deleteHostedPage(page.id)
  } catch {
    toast('页面删除失败，请重试', 'ri-error-warning-line')
  }
}
</script>

<template>
  <section class="project-assets">
    <div class="asset-toolbar">
      <div class="asset-tabs" role="tablist" aria-label="项目文件类型">
        <button :class="{ on: tab === 'files' }" @click="tab = 'files'">
          文件 <em>{{ files.length }}</em>
        </button>
        <button :class="{ on: tab === 'images' }" @click="tab = 'images'">
          图床 <em>{{ images.length }}</em>
        </button>
        <button :class="{ on: tab === 'pages' }" @click="tab = 'pages'">
          HTML <em>{{ pages.length }}</em>
        </button>
      </div>
      <span class="asset-summary">共 {{ total }} 项，复用全局“文件与图床”存储</span>
      <button class="upload-button" :disabled="uploading" @click="input?.click()">
        <i :class="uploading ? 'ri-loader-4-line spin' : 'ri-upload-2-line'"></i
        >{{ uploading ? '上传中…' : '上传资料' }}
      </button>
      <input ref="input" type="file" multiple hidden @change="pick" />
    </div>

    <div
      class="asset-drop"
      :class="{ dragging }"
      @dragenter.prevent="dragging = true"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="drop"
    >
      <i class="ri-drag-drop-line"></i>
      <span>拖入任意资料：图片进项目图床，HTML 进入隔离托管，其余进入项目文件</span>
    </div>
    <div v-if="uploadError" class="asset-error" role="alert">
      <i class="ri-error-warning-line"></i>{{ uploadError }}
    </div>

    <div v-if="tab === 'files'" class="asset-list">
      <div v-for="file in files" :key="file.id" class="asset-row">
        <i :class="file.icon"></i>
        <div>
          <FileDisplayNameEditor :file="file" />
          <span>{{ file.size }} · {{ file.by }} · {{ file.time }}</span>
        </div>
        <button
          v-if="isMarkdown(file.name)"
          @click="preview = { kind: 'markdown', id: file.id, name: fileDisplayName(file) }"
        >
          <i class="ri-eye-line"></i>预览
        </button>
        <button @click="copyText(fileShareUrl(file.id!), '已复制公网分享链接')">
          <i class="ri-links-line"></i>分享
        </button>
        <button v-if="file.id" @click="ui.shareToChat(chatShareLink(fileDisplayName(file), fileShareUrl(file.id)))"><i class="ri-chat-forward-line"></i>分享到聊天</button>
        <button v-if="file.id" title="重新上传文件" @click="replacement = { kind: 'file', id: file.id, name: fileDisplayName(file), url: fileShareUrl(file.id) }"><i class="ri-upload-2-line"></i>重新上传</button>
        <a :href="absoluteUrl(file.link)" title="下载"><i class="ri-download-2-line"></i></a>
        <button class="danger" @click="removeFile(file.id, fileDisplayName(file))">
          <i class="ri-delete-bin-line"></i>
        </button>
      </div>
      <div v-if="!files.length" class="asset-empty">
        <i class="ri-file-add-line"></i>还没有项目文件，可上传 Markdown 或其它资料。
      </div>
    </div>

    <div v-else-if="tab === 'images'" class="image-grid">
      <article v-for="image in images" :key="image.id" class="image-card">
        <button
          class="image-open"
          @click="preview = { kind: 'image', name: image.name, url: image.url }"
        >
          <img :src="image.url" :alt="image.name" />
        </button>
        <div>
          <b>{{ image.name }}</b
          ><span>{{ image.size }} · {{ image.time }}</span>
        </div>
        <button @click="copyText(absoluteUrl(image.url), '已复制公网图片链接')">
          <i class="ri-links-line"></i>分享
        </button>
        <button class="danger" @click="removeImage(image.id, image.name)">
          <i class="ri-delete-bin-line"></i>
        </button>
      </article>
      <div v-if="!images.length" class="asset-empty">
        <i class="ri-image-add-line"></i>还没有项目图片。
      </div>
    </div>

    <div v-else class="asset-list">
      <div v-if="data.hostedPageLoadState === 'loading'" class="asset-empty" role="status">正在加载项目 HTML…</div>
      <div v-else-if="data.hostedPageLoadState === 'error'" class="asset-error" role="alert">
        {{ data.hostedPageLoadError }}
        <button @click="data.refreshHostedPages()">重新加载</button>
      </div>
      <div v-for="page in pages" :key="page.id" class="asset-row">
        <i class="ri-html5-line"></i>
        <div>
          <b>{{ page.title }}</b
          ><span>{{ page.fileName }} · {{ page.updatedAt }}</span>
        </div>
        <button @click="preview = { kind: 'html', name: page.title, url: page.url }">
          <i class="ri-eye-line"></i>预览
        </button>
        <button @click="copyText(absoluteUrl(page.url), '已复制公网分享链接')">
          <i class="ri-links-line"></i>分享
        </button>
        <button title="重新上传 HTML" @click="replacement = { kind: 'html', id: page.id, name: page.title, url: page.url }"><i class="ri-upload-2-line"></i>重新上传</button>
        <button title="修改 WAO 显示名称" @click="editPage(page)">
          <i class="ri-edit-line"></i>改名
        </button>
        <button class="danger" @click="removePage(page)"><i class="ri-delete-bin-line"></i></button>
      </div>
      <div v-if="data.hostedPageLoadState === 'loaded' && !pages.length" class="asset-empty">
        <i class="ri-html5-line"></i>还没有项目 HTML 报告。
      </div>
    </div>

    <div v-if="htmlDialog" class="html-mask" @click.self="closeHtmlDialog">
      <form class="html-dialog" @submit.prevent="saveHtml">
        <h3>{{ editingPage ? '修改 WAO 显示名称' : '托管项目 HTML' }}</h3>
        <p v-if="editingPage">
          修改后会同步到项目列表、站内预览和浏览器标题；HTML 内容和公网分享地址保持不变。
        </p>
        <p v-else>页面会归入当前项目，并在受限 iframe 内站内预览；公网分享链接保持可用。</p>
        <label>WAO 显示名称<input v-model="htmlTitle" maxlength="200" autofocus /></label>
        <label v-if="!editingPage">分享链接后缀<input v-model="htmlSlug" maxlength="80" /></label>
        <div v-if="htmlFile" class="selected-file">
          <i class="ri-file-check-line"></i>{{ htmlFile.name }}
        </div>
        <div v-if="htmlFormError" class="html-form-error" role="alert">{{ htmlFormError }}</div>
        <footer>
          <button type="button" :disabled="htmlSaving" @click="closeHtmlDialog">取消</button
          ><button class="primary" :disabled="htmlSaving">
            {{ htmlSaving ? '保存中…' : editingPage ? '保存名称' : '发布并预览' }}
          </button>
        </footer>
      </form>
    </div>
    <AssetReplaceModal v-if="replacement" :target="replacement" @close="replacement = null" @saved="preview = null" />
    <AssetPreviewModal v-if="preview" :asset="preview" @close="preview = null" />
  </section>
</template>

<style scoped>
.project-assets {
  padding-top: 14px;
}
.asset-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.asset-tabs {
  display: flex;
  padding: 3px;
  border-radius: 9px;
  background: var(--wao-divider);
}
.asset-tabs button {
  border: 0;
  border-radius: 7px;
  padding: 6px 11px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.asset-tabs button.on {
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: 0 1px 3px rgba(20, 24, 38, 0.12);
}
.asset-tabs em {
  margin-left: 4px;
  font-style: normal;
  opacity: 0.7;
}
.asset-summary {
  flex: 1;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.upload-button,
.html-dialog .primary {
  border: 0;
  border-radius: 8px;
  padding: 8px 13px;
  background: var(--wao-primary);
  color: #fff;
  cursor: pointer;
}
.asset-drop {
  margin: 12px 0;
  min-height: 58px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 10px;
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  text-align: center;
}
.asset-drop.dragging {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
}
.asset-error {
  margin-bottom: 10px;
  padding: 9px 11px;
  border-radius: 8px;
  color: var(--wao-danger);
  background: var(--wao-danger-bg);
}
.asset-list {
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  overflow: hidden;
  background: var(--wao-bg-card);
}
.asset-row {
  min-height: 62px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  align-items: center;
  gap: 10px;
}
.asset-row:last-child {
  border-bottom: 0;
}
.asset-row > i {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: var(--wao-bg-page);
  display: grid;
  place-items: center;
  font-size: 18px;
}
.asset-row > div {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.asset-row b,
.image-card b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.asset-row span,
.image-card span {
  color: var(--wao-text-weak);
  font-size: 12px;
}
.asset-row button,
.asset-row a,
.image-card > button:not(.image-open) {
  border: 0;
  background: transparent;
  color: var(--wao-text-3);
  padding: 6px 7px;
  border-radius: 7px;
  cursor: pointer;
  text-decoration: none;
}
.asset-row button:hover,
.asset-row a:hover {
  background: var(--wao-bg-hover);
}
.danger {
  color: var(--wao-danger) !important;
}
.asset-empty {
  min-height: 150px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  color: var(--wao-text-weak);
}
.asset-empty i {
  font-size: 30px;
}
.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 10px;
}
.image-card {
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
}
.image-open {
  width: 100%;
  height: 130px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  overflow: hidden;
  background: var(--wao-bg-page);
  cursor: pointer;
}
.image-open img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.image-card > div {
  padding: 8px 3px 4px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.html-mask {
  position: fixed;
  inset: 0;
  z-index: 1500;
  padding: 20px;
  background: rgba(20, 23, 34, 0.5);
  display: grid;
  place-items: center;
}
.html-dialog {
  width: min(460px, 100%);
  padding: 22px;
  border-radius: 13px;
  background: var(--wao-bg-card);
  box-shadow: 0 18px 60px rgba(18, 22, 36, 0.25);
}
.html-dialog h3 {
  margin: 0;
}
.html-dialog p {
  color: var(--wao-text-weak);
  font-size: 13px;
  line-height: 1.6;
}
.html-dialog label {
  display: grid;
  gap: 6px;
  margin-top: 13px;
  font-size: 13px;
}
.html-dialog input {
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-strong);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
.selected-file {
  margin-top: 13px;
  padding: 9px;
  border-radius: 8px;
  background: var(--wao-bg-page);
  overflow-wrap: anywhere;
}
.html-form-error {
  margin-top: 12px;
  color: var(--wao-danger);
  font-size: 12px;
}
.html-dialog footer {
  margin-top: 18px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.html-dialog footer button {
  padding: 8px 13px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  cursor: pointer;
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
  .asset-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .asset-row > div {
    width: calc(100% - 46px);
    flex: none;
  }
  .asset-summary {
    flex-basis: 100%;
    order: 3;
  }
}
</style>
