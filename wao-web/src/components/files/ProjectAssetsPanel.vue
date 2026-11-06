<script setup lang="ts">
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import { useConfirmAction } from '@/composables/confirmAction'
const { confirmation, confirmAction, dismissConfirmation } = useConfirmAction()
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUploadStore } from '@/stores/uploads'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
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
const session = useSessionStore()
const uploads = useUploadStore()
const tab = ref<'files' | 'images' | 'pages'>('files')
const input = ref<HTMLInputElement | null>(null)
const projectUploads = computed(() => uploads.uploadAttempts.filter(item => item.projectId === props.projectId))
const uploading = computed(() => projectUploads.value.some(item => item.state === 'waiting' || item.state === 'uploading'))
const failedUploads = computed(() => projectUploads.value.filter(item => item.state === 'failed'))
const uploadError = computed(() => failedUploads.value.length ? `${failedUploads.value.length} 项上传未完成：${failedUploads.value[0]?.reason}` : '')
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
const htmlExpectedRevision = ref<number>()
const htmlConflict = ref(false)
const htmlReadingConflict = ref(false)
const htmlLatest = ref<HostedPage | null>(null)
const htmlDirty = computed(() => !!htmlFile.value || htmlTitle.value !== (editingPage.value?.title ?? '') || htmlSlug.value !== (editingPage.value?.slug ?? ''))
let generation = 0
let disposed = false
watch(() => [props.projectId, session.account?.accountId, session.userId], () => {
  generation++
  preview.value = null
  replacement.value = null
  htmlDialog.value = false
  htmlFile.value = null
  editingPage.value = null
  htmlTitle.value = ''
  htmlSlug.value = ''
  htmlSaving.value = false
  htmlFormError.value = ''
  resetHtmlConflict()
  dismissConfirmation()
}, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; generation++; htmlFile.value = null; window.removeEventListener('beforeunload', beforeUnload) })

const files = computed(() => data.files.filter((item) => item.projectId === props.projectId))
const images = computed(() => data.imgs.filter((item) => item.projectId === props.projectId))
const pages = computed(() => data.hostedPages.filter((item) => item.projectId === props.projectId))
const total = computed(() => files.value.length + images.value.length + pages.value.length)

onMounted(() => {
  void Promise.all([data.refreshFiles(), data.refreshImages(), data.refreshHostedPages()])
  window.addEventListener('beforeunload', beforeUnload)
})

function beforeUnload(event: BeforeUnloadEvent) {
  if (!htmlDialog.value || (!htmlDirty.value && !htmlSaving.value)) return
  event.preventDefault()
  event.returnValue = ''
}
function resetHtmlConflict(revision?: number) {
  htmlExpectedRevision.value = revision
  htmlConflict.value = false
  htmlReadingConflict.value = false
  htmlLatest.value = null
}

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
  generation++
  editingPage.value = null
  resetHtmlConflict()
  htmlFile.value = file
  htmlTitle.value = baseName(file.name) || '未命名页面'
  htmlSlug.value = suggestedSlug(file.name)
  htmlFormError.value = ''
  htmlDialog.value = true
  tab.value = 'pages'
}

function editPage(page: HostedPage) {
  generation++
  editingPage.value = { ...page }
  resetHtmlConflict(page.revision)
  htmlFile.value = null
  htmlTitle.value = page.title
  htmlSlug.value = page.slug
  htmlFormError.value = ''
  htmlDialog.value = true
}

function closeHtmlDialog() {
  if (htmlSaving.value) return
  const current = generation
  const dismiss = () => {
    if (current !== generation || disposed) return
    generation++
    htmlDialog.value = false
    htmlFile.value = null
    editingPage.value = null
    htmlTitle.value = ''
    htmlSlug.value = ''
    htmlFormError.value = ''
    resetHtmlConflict()
  }
  if (!htmlDirty.value) dismiss()
  else confirmAction({
    title: '放弃未保存的网页修改？',
    description: '尚未发布的内容和所选 HTML 文件将被清除，已保存的网页保持不变。',
    confirmLabel: '放弃修改',
    run: dismiss,
  })
}

function retryProjectUploads() {
  for (const item of failedUploads.value) void uploads.retryUpload(item)
}

function classify(selected: File[]) {
  const pictures = selected.filter((file) => file.type.startsWith('image/'))
  const html = selected.filter((file) => !file.type.startsWith('image/') && isHtml(file))
  const attachments = selected.filter((file) => !file.type.startsWith('image/') && !isHtml(file))
  const context = { projectId: props.projectId, source: `项目 · ${props.projectName}` }
  if (pictures.length) void uploads.enqueue(pictures, 'img', undefined, context)
  if (attachments.length) void uploads.enqueue(attachments, 'files', undefined, context)
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
  if (htmlSaving.value || htmlConflict.value || confirmation.value) return
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
  const current = generation
  const editing = editingPage.value
  try {
    const page = await data.saveHostedPage({
      id: editing?.id,
      html: htmlFile.value ?? undefined,
      title: htmlTitle.value,
      slug: htmlSlug.value,
      projectId: editing ? undefined : props.projectId,
      ...(editing && htmlExpectedRevision.value !== undefined ? { expectedRevision: htmlExpectedRevision.value } : {}),
    })
    if (disposed || current !== generation) return
    htmlDialog.value = false
    htmlFile.value = null
    if (editing) {
      toast('WAO 显示名称已保存', 'ri-check-line')
    } else {
      preview.value = { kind: 'html', name: page.title, url: page.url, sourceType: page.sourceType }
      toast('HTML 已归入项目并可站内预览', 'ri-html5-line')
    }
  } catch (cause) {
    if (disposed || current !== generation) return
    if (editing && (cause as { status?: number })?.status === 409) {
      htmlConflict.value = true
      await refreshHtmlConflict()
      return
    }
    htmlFormError.value =
      cause instanceof Error
        ? cause.message
        : `${editingPage.value ? '名称保存' : 'HTML 发布'}失败，请重试`
  } finally {
    if (!disposed && current === generation) htmlSaving.value = false
  }
}
async function refreshHtmlConflict() {
  if (htmlReadingConflict.value || !editingPage.value) return
  const current = generation
  const id = editingPage.value.id
  htmlLatest.value = null
  htmlReadingConflict.value = true
  htmlFormError.value = ''
  const success = await data.refreshHostedPages().catch(() => false)
  if (disposed || current !== generation) return
  htmlReadingConflict.value = false
  const latest = success ? data.hostedPages.find(page => page.id === id) : undefined
  if (latest && Number.isSafeInteger(latest.revision) && latest.revision! >= 0) htmlLatest.value = { ...latest }
  else htmlFormError.value = '最新网页版本读取失败或已不可访问，修改已保留，请重新读取。'
}
function useLatestHtml() {
  if (htmlSaving.value || htmlReadingConflict.value || !htmlLatest.value) return
  const latest = htmlLatest.value
  editingPage.value = { ...latest }
  htmlTitle.value = latest.title
  htmlSlug.value = latest.slug
  htmlFile.value = null
  htmlFormError.value = ''
  resetHtmlConflict(latest.revision)
}
function overwriteHtml() {
  if (htmlSaving.value || htmlReadingConflict.value || !htmlLatest.value) return
  const latest = htmlLatest.value
  // The project dialog edits only the title; preserve any newly changed public slug.
  editingPage.value = { ...latest }
  htmlSlug.value = latest.slug
  htmlExpectedRevision.value = latest.revision
  htmlConflict.value = false
  void saveHtml()
}

async function removeFile(id: string | undefined, name: string) {
  if (!id) return
  confirmAction({ title: '删除项目文件', description: `确认删除「${name}」？此操作不可恢复，原下载链接将无法使用。`, run: () => data.deleteFile(id) })
}
async function removeImage(id: string | undefined, name: string) {
  if (!id) return
  confirmAction({ title: '删除项目图片', description: `确认删除「${name}」？此操作不可恢复，引用这张图片的内容可能无法继续显示它。`, run: () => data.deleteImage(id) })
}
async function removePage(page: HostedPage) {
  confirmAction({ title: '删除托管页面', description: `删除「${page.title}」后，公开链接将立即失效，此操作不可恢复。`, run: () => data.deleteHostedPage(page.id) })
}
</script>

<template>
  <ConfirmActionDialog v-if="confirmation" :action="confirmation" @close="dismissConfirmation" />
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
      <span class="asset-summary">共 {{ total }} 项</span>
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
      <WaoButton v-if="failedUploads.some(item => item.retryable)" :disabled="uploading" @click="retryProjectUploads">重试失败项</WaoButton>
      <RouterLink :to="{ path: '/files', query: { uploads: '1' } }">查看上传队列</RouterLink>
    </div>

    <div v-if="tab === 'files'" class="asset-list">
      <div v-if="data.fileLoadState === 'loading' && !files.length" class="asset-empty" role="status">正在加载项目文件…</div>
      <div v-if="data.fileLoadState === 'error'" class="asset-error" role="alert">{{ data.fileLoadError }}<WaoButton @click="data.refreshFiles()">重新加载</WaoButton></div>
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
        <button v-if="file.id" title="重新上传文件" @click="replacement = { kind: 'file', id: file.id, name: fileDisplayName(file), url: fileShareUrl(file.id) }"><i class="ri-upload-2-line"></i>重新上传</button>
        <a :href="absoluteUrl(file.link)" title="下载"><i class="ri-download-2-line"></i></a>
        <button class="danger" @click="removeFile(file.id, fileDisplayName(file))">
          <i class="ri-delete-bin-line"></i>
        </button>
      </div>
      <div v-if="data.fileLoadState === 'loaded' && !files.length" class="asset-empty">
        <i class="ri-file-add-line"></i>还没有项目文件，可上传 Markdown 或其它资料。
      </div>
    </div>

    <div v-else-if="tab === 'images'" class="image-grid">
      <div v-if="data.loadStates.images === 'loading' && !images.length" class="asset-empty" role="status">正在加载项目图片…</div>
      <div v-if="data.loadStates.images === 'error'" class="asset-error" role="alert">{{ data.loadErrors.images }}<WaoButton @click="data.refreshImages()">重新加载</WaoButton></div>
      <article v-for="image in images" :key="image.id" class="image-card">
        <button
          class="image-open"
          @click="preview = { kind: 'image', name: image.name, url: image.url }"
        >
          <img :src="image.previewUrl || image.url" :alt="image.name" loading="lazy" decoding="async" />
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
      <div v-if="data.loadStates.images === 'loaded' && !images.length" class="asset-empty">
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
        <button @click="preview = { kind: 'html', name: page.title, url: page.url, sourceType: page.sourceType }">
          <i class="ri-eye-line"></i>预览
        </button>
        <button @click="copyText(absoluteUrl(page.url), '已复制公网分享链接')">
          <i class="ri-links-line"></i>分享
        </button>
        <button title="重新上传 HTML" @click="replacement = { kind: 'html', id: page.id, name: page.title, url: page.url, revision: page.revision }"><i class="ri-upload-2-line" aria-hidden="true"></i>重新上传</button>
        <button title="修改 WAO 显示名称" @click="editPage(page)">
          <i class="ri-edit-line"></i>改名
        </button>
        <button class="danger" @click="removePage(page)"><i class="ri-delete-bin-line"></i></button>
      </div>
      <div v-if="data.hostedPageLoadState === 'loaded' && !pages.length" class="asset-empty">
        <i class="ri-html5-line"></i>还没有项目 HTML 报告。
      </div>
    </div>

    <SettingsDialogShell
      v-if="htmlDialog"
      :title="editingPage ? '修改 WAO 显示名称' : '托管项目 HTML'"
      width="480px"
      height="auto"
      appearance="compact"
      icon="ri-html5-line"
      :close-disabled="htmlSaving"
      @close="closeHtmlDialog"
    >
      <form class="html-dialog" @submit.prevent="saveHtml">
        <p v-if="editingPage">
          修改后会同步到项目列表、站内预览和浏览器标题；HTML 内容和公网分享地址保持不变。
        </p>
        <p v-else>页面会归入当前项目，在隔离窗口中运行；演示数据保存在当前浏览器，刷新后仍可恢复。</p>
        <WaoField v-slot="field" label="WAO 显示名称" required>
          <input :id="field.id" v-model="htmlTitle" :aria-describedby="field.describedBy" :required="field.required" :disabled="htmlSaving" maxlength="200" autofocus />
        </WaoField>
        <WaoField v-if="!editingPage" v-slot="field" label="分享链接后缀" required>
          <input :id="field.id" v-model="htmlSlug" :aria-describedby="field.describedBy" :required="field.required" :disabled="htmlSaving" maxlength="80" />
        </WaoField>
        <div v-if="htmlFile" class="selected-file">
          <i class="ri-file-check-line" aria-hidden="true"></i>{{ htmlFile.name }}
        </div>
        <div v-if="htmlFormError" class="html-form-error" role="alert">{{ htmlFormError }}</div>
        <div v-if="htmlConflict" class="html-conflict" role="alert">
          <p>网页已在另一处更新，输入已保留。{{ htmlReadingConflict ? '正在读取最新版本…' : '请决定采用最新名称，还是仍保存当前名称。' }}</p>
          <p v-if="htmlLatest">最新名称：{{ htmlLatest.title }}</p>
          <WaoButton v-if="!htmlLatest" :disabled="htmlSaving || htmlReadingConflict" @click="refreshHtmlConflict">重新读取最新版本</WaoButton>
          <template v-else>
            <WaoButton :disabled="htmlSaving || htmlReadingConflict" @click="useLatestHtml">使用最新版本</WaoButton>
            <WaoButton :disabled="htmlSaving || htmlReadingConflict" @click="overwriteHtml">仍保存当前名称</WaoButton>
          </template>
        </div>
        <footer>
          <WaoButton :disabled="htmlSaving" @click="closeHtmlDialog">取消</WaoButton>
          <WaoButton type="submit" class="primary" variant="primary" :loading="htmlSaving" :disabled="htmlConflict">
            {{ htmlSaving ? '保存中…' : editingPage ? '保存名称' : '发布并预览' }}
          </WaoButton>
        </footer>
      </form>
    </SettingsDialogShell>
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
.upload-button {
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
.html-dialog {
  min-width: 0;
  display: grid;
  gap: var(--wao-space-2);
  padding: var(--wao-space-2) var(--wao-space-3) var(--wao-space-3);
}
.html-dialog p {
  margin: 0;
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
  line-height: var(--wao-line-body);
}
.selected-file {
  padding: var(--wao-space-2);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-page);
  overflow-wrap: anywhere;
}
.html-form-error {
  color: var(--wao-danger-strong);
  font-size: var(--wao-font-help);
  overflow-wrap: anywhere;
}
.html-conflict {
  display: grid;
  gap: var(--wao-space-1);
  padding: var(--wao-space-2);
  border: 1px solid var(--wao-warning-line);
  border-radius: var(--wao-radius-control);
  background: var(--wao-warning-bg);
  overflow-wrap: anywhere;
}
.html-dialog footer {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--wao-space-1);
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
