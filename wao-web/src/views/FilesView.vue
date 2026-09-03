<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { copyText } from '@/utils/clipboard'
import { absoluteUrl, fileShareUrl } from '@/utils/share'
import { toast } from '@/composables/toast'
import DemoBanner from '@/components/ui/DemoBanner.vue'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import FileSettingsModal from '@/components/overlays/FileSettingsModal.vue'
import { fetchFileSettings, type FileSettings } from '@/api/system'
import type { HostedPage } from '@/types'
import AssetPreviewModal, { type AssetPreview } from '@/components/files/AssetPreviewModal.vue'
import FileDisplayNameEditor from '@/components/files/FileDisplayNameEditor.vue'
import { fileDisplayName } from '@/utils/files'

const data = useDataStore()
const tab = ref<'files' | 'img' | 'pages'>('files')
const pageDialog = ref(false)
const editingPage = ref<HostedPage | null>(null)
const pageTitle = ref('')
const pageSlug = ref('')
const pageHtml = ref<File | null>(null)
const pageIcon = ref<File | null>(null)
const pageSaving = ref(false)
const pageTitleAuto = ref(false)
const pageSlugAuto = ref(false)
const pageHtmlInput = ref<HTMLInputElement | null>(null)
const pageIconInput = ref<HTMLInputElement | null>(null)
const pageHtmlDragging = ref(false)
const pageIconDragging = ref(false)
const settingsOpen = ref(false)
const fileSettings = ref<FileSettings>({
  fileMaxMb: 50,
  imageMaxMb: 10,
  downloadCaptchaEnabled: true,
})
const fileSettingsLoaded = ref(false)
const fileSettingsError = ref(false)

const HOSTED_PAGE_SIZE = 10
const hostedPage = ref(1)
type HostedPageSourceFilter = 'ALL' | 'MANUAL_UPLOAD' | 'AI_RUN_REPORT'
const hostedPageSource = ref<HostedPageSourceFilter>('ALL')
const hostedPageSourceFilters: Array<{ id: HostedPageSourceFilter; label: string }> = [
  { id: 'ALL', label: '全部类型' },
  { id: 'MANUAL_UPLOAD', label: '人工上传' },
  { id: 'AI_RUN_REPORT', label: 'AI Run 报告' },
]
function hostedPageSourceType(page: HostedPage) {
  return page.sourceType ?? (page.id.startsWith('run-report-') ? 'AI_RUN_REPORT' : 'MANUAL_UPLOAD')
}
const filteredHostedPages = computed(() =>
  hostedPageSource.value === 'ALL'
    ? data.hostedPages
    : data.hostedPages.filter((page) => hostedPageSourceType(page) === hostedPageSource.value),
)
const hostedPageCount = computed(() =>
  Math.max(1, Math.ceil(filteredHostedPages.value.length / HOSTED_PAGE_SIZE)),
)
const visibleHostedPages = computed(() =>
  filteredHostedPages.value.slice(
    (hostedPage.value - 1) * HOSTED_PAGE_SIZE,
    hostedPage.value * HOSTED_PAGE_SIZE,
  ),
)

/** 图床默认只铺三排（网格是 5 列，即 15 张），其余点开再看。 */
const IMG_PAGE = 15
const showAllImages = ref(false)
const visibleImages = computed(() =>
  showAllImages.value ? data.imgs : data.imgs.slice(0, IMG_PAGE),
)
const hiddenCount = computed(() =>
  showAllImages.value ? 0 : Math.max(0, data.imgs.length - IMG_PAGE),
)
const fileInput = ref<HTMLInputElement | null>(null)
const unifiedInput = ref<HTMLInputElement | null>(null)
const unifiedDragging = ref(false)
const uploading = ref(0)
const preview = ref<AssetPreview | null>(null)

// 打开页面时重新拉一次，让其他标签页 / 其他人上传的图也出现
onMounted(() => {
  void data.refreshImages()
  void data.refreshFiles()
  void data.refreshHostedPages()
  void fetchFileSettings()
    .then((settings) => {
      fileSettings.value = settings
      fileSettingsLoaded.value = true
      fileSettingsError.value = false
    })
    .catch(() => {
      fileSettingsError.value = true
    })
})

watch(
  () => [filteredHostedPages.value.length, hostedPageSource.value],
  () => {
    if (hostedPage.value > hostedPageCount.value) hostedPage.value = hostedPageCount.value
  },
)

function selectHostedPageSource(source: HostedPageSourceFilter) {
  hostedPageSource.value = source
  hostedPage.value = 1
}

/** 从图床页直接上传，来源标成「图床」以便和任务里粘贴的图区分。 */
async function uploadImages(files: FileList | File[]) {
  const list = Array.from(files).filter((f) => f.type.startsWith('image/'))
  if (!list.length) {
    toast('请选择图片文件', 'ri-error-warning-line')
    return
  }
  uploading.value += list.length
  let failed = 0
  try {
    for (const f of list) {
      if (f.size > fileSettings.value.imageMaxMb * 1024 * 1024) {
        toast(
          `「${f.name}」超过 ${fileSettings.value.imageMaxMb}MB，已跳过`,
          'ri-error-warning-line',
        )
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

function hostedPageBaseName(fileName: string) {
  return fileName
    .replace(/^.*[\\/]/, '')
    .replace(/\.(?:html?|HTML?)$/, '')
    .trim()
}

function nextHostedPageSlug(fileName: string) {
  const base = hostedPageBaseName(fileName)
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64)
  const candidate = base || `page-${Math.random().toString(36).slice(2, 10)}`
  if (!data.hostedPages.some((page) => page.slug === candidate)) return candidate
  return `${candidate.slice(0, 72)}-${Math.random().toString(36).slice(2, 8)}`
}

function setHostedPageHtml(file: File) {
  pageHtml.value = file
  if (editingPage.value) return
  if (pageTitleAuto.value || !pageTitle.value.trim()) {
    pageTitle.value = hostedPageBaseName(file.name) || '未命名页面'
    pageTitleAuto.value = true
  }
  if (pageSlugAuto.value || !pageSlug.value.trim()) {
    pageSlug.value = nextHostedPageSlug(file.name)
    pageSlugAuto.value = true
  }
}

function openPageDialog(page?: HostedPage, html?: File) {
  editingPage.value = page ?? null
  pageTitle.value = page?.title ?? ''
  pageSlug.value = page?.slug ?? ''
  pageHtml.value = null
  pageIcon.value = null
  pageTitleAuto.value = !page
  pageSlugAuto.value = !page
  if (html) setHostedPageHtml(html)
  pageDialog.value = true
}

function closePageDialog() {
  if (pageSaving.value) return
  pageDialog.value = false
}

function onPageHtmlPick(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (file) setHostedPageHtml(file)
}

function onPageHtmlDrop(event: DragEvent) {
  pageHtmlDragging.value = false
  const file = event.dataTransfer?.files[0]
  if (!file) return
  if (!isHostedHtml(file)) {
    toast('请拖入 HTML 文件', 'ri-error-warning-line')
    return
  }
  setHostedPageHtml(file)
}

function setPageIcon(file: File) {
  if (!file.type.startsWith('image/')) {
    toast('请拖入图片文件作为 icon', 'ri-error-warning-line')
    return
  }
  pageIcon.value = file
}

function onPageIconPick(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (file) setPageIcon(file)
}

function onPageIconDrop(event: DragEvent) {
  pageIconDragging.value = false
  const file = event.dataTransfer?.files[0]
  if (file) setPageIcon(file)
}

async function savePage() {
  if (
    !pageTitle.value.trim() ||
    !pageSlug.value.trim() ||
    (!editingPage.value && !pageHtml.value)
  ) {
    toast('请填写标题、链接后缀并选择 HTML 文件', 'ri-error-warning-line')
    return
  }
  pageSaving.value = true
  try {
    const page = await data.saveHostedPage({
      id: editingPage.value?.id,
      html: pageHtml.value ?? undefined,
      icon: pageIcon.value ?? undefined,
      slug: pageSlug.value,
      title: pageTitle.value,
    })
    pageDialog.value = false
    hostedPage.value = 1
    toast(`已发布「${page.title}」`, 'ri-global-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '发布失败，请重试', 'ri-error-warning-line')
  } finally {
    pageSaving.value = false
  }
}
async function removePage(page: HostedPage) {
  if (!window.confirm(`删除托管页面“${page.title}”？公开链接将立即失效。`)) return
  await data.deleteHostedPage(page.id)
  toast(`已删除「${page.title}」`, 'ri-delete-bin-line')
}

async function uploadAttachments(files: FileList | File[]) {
  const list = Array.from(files)
  if (!list.length) return
  uploading.value += list.length
  let failed = 0
  try {
    for (const file of list) {
      if (file.size > fileSettings.value.fileMaxMb * 1024 * 1024 || !(await data.uploadFile(file)))
        failed++
    }
  } finally {
    uploading.value -= list.length
  }
  if (list.length - failed) toast(`已上传 ${list.length - failed} 个文件`, 'ri-upload-cloud-2-line')
  if (failed)
    toast(
      `${failed} 个文件上传失败或超过 ${fileSettings.value.fileMaxMb}MB`,
      'ri-error-warning-line',
    )
}

function onPick(e: Event) {
  const files = (e.target as HTMLInputElement).files
  if (files?.length) {
    if (tab.value === 'img') void uploadImages(files)
    else void uploadAttachments(files)
  }
  if (fileInput.value) fileInput.value.value = ''
}
function onDrop(e: DragEvent) {
  const files = e.dataTransfer?.files
  if (files?.length) {
    if (tab.value === 'img') void uploadImages(files)
    else void uploadAttachments(files)
  }
}

function isHostedHtml(file: File) {
  return file.type === 'text/html' || /\.html?$/i.test(file.name)
}

/** 统一入口只做确定性的类型路由；真正的大小、内容和权限约束仍由对应服务端接口裁决。 */
function autoClassifyUploads(files: FileList | File[]) {
  const list = Array.from(files)
  if (!list.length) return
  const images = list.filter((file) => file.type.startsWith('image/'))
  const pages = list.filter((file) => !file.type.startsWith('image/') && isHostedHtml(file))
  const attachments = list.filter((file) => !file.type.startsWith('image/') && !isHostedHtml(file))

  if (images.length) void uploadImages(images)
  if (attachments.length) void uploadAttachments(attachments)
  if (pages.length) {
    tab.value = 'pages'
    openPageDialog(undefined, pages[0])
    if (pages.length > 1) {
      toast('一次先配置 1 个托管网页，其余 HTML 请再次拖入', 'ri-information-line')
    }
  } else if (images.length && !attachments.length) {
    tab.value = 'img'
  } else if (attachments.length && !images.length) {
    tab.value = 'files'
  }
}

function onUnifiedPick(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files?.length) autoClassifyUploads(target.files)
  target.value = ''
}

function onUnifiedDrop(event: DragEvent) {
  unifiedDragging.value = false
  if (event.dataTransfer?.files.length) autoClassifyUploads(event.dataTransfer.files)
}

/** 图床里的图是站内相对地址，复制出去必须补成绝对地址才能公网访问。 */
function copyImageLink(url: string) {
  copyText(absoluteUrl(url), '已复制公网链接')
}
function openImage(url: string) {
  const image = data.imgs.find((item) => item.url === url)
  preview.value = { kind: 'image', name: image?.name || '图片预览', url }
}
function isMarkdown(name: string) {
  return /\.(?:md|markdown)$/i.test(name)
}
function projectName(projectId?: string | null) {
  return projectId ? data.projectById(projectId)?.name || projectId : ''
}
async function removeImage(id: string | undefined, name: string) {
  if (!id) {
    toast('内置示例图不可删除', 'ri-information-line')
    return
  }
  if (!window.confirm(`删除图片“${name}”？此操作不可恢复。`)) return
  await data.deleteImage(id)
  toast(`已删除「${name}」`, 'ri-delete-bin-line')
}
async function removeFile(id: string | undefined, name: string) {
  if (!id || !window.confirm(`删除文件“${name}”？`)) return
  await data.deleteFile(id)
  toast(`已删除「${name}」`, 'ri-delete-bin-line')
}

function onFileSettingsSaved(settings: FileSettings) {
  fileSettings.value = settings
  fileSettingsLoaded.value = true
  fileSettingsError.value = false
}
</script>

<template>
  <div class="files">
    <DemoBanner
      icon="ri-hard-drive-2-line"
      title="文件与图床"
      text="文件、图床和网页托管均已接入真实后端持久化。网页托管可上传单文件 HTML，自定义公网链接后缀、标题和 icon；页面在隔离环境中运行，不会接触 WAO 登录态。"
      storage-key="files"
    />
    <div class="ftop">
      <div class="ftitle">文件与图床</div>
      <div class="seg">
        <button
          v-for="t in ['files', 'img', 'pages'] as const"
          :key="t"
          class="seg-btn"
          :class="{ on: tab === t }"
          @click="tab = t"
        >
          {{ t === 'files' ? '文件' : t === 'img' ? '图床' : '网页托管' }}
        </button>
      </div>
      <div class="spacer"></div>
      <button class="settings-button" @click="settingsOpen = true">
        <i class="ri-settings-3-line"></i>设置
      </button>
      <button
        class="btn-primary"
        :disabled="uploading > 0"
        @click="tab === 'pages' ? openPageDialog() : fileInput?.click()"
      >
        <i class="ri-upload-2-line"></i
        >{{
          uploading > 0
            ? `上传中 ${uploading}…`
            : tab === 'img'
              ? '上传图片'
              : tab === 'pages'
                ? '托管 HTML'
                : '上传文件'
        }}
      </button>
      <input
        ref="fileInput"
        type="file"
        :accept="tab === 'img' ? 'image/*' : undefined"
        multiple
        hidden
        @change="onPick"
      />
    </div>

    <div
      class="unified-drop"
      :class="{ dragging: unifiedDragging, busy: uploading > 0 }"
      role="button"
      tabindex="0"
      @click="unifiedInput?.click()"
      @keydown.enter.prevent="unifiedInput?.click()"
      @keydown.space.prevent="unifiedInput?.click()"
      @dragenter.prevent="unifiedDragging = true"
      @dragover.prevent="unifiedDragging = true"
      @dragleave.prevent="unifiedDragging = false"
      @drop.prevent="onUnifiedDrop"
    >
      <i :class="uploading > 0 ? 'ri-loader-4-line spin' : 'ri-drag-drop-line'"></i>
      <div>
        <b>{{
          uploading > 0 ? `正在上传 ${uploading} 个文件…` : '拖拽或选择任意文件，自动分类'
        }}</b>
        <span>图片进入图床，HTML 进入网页托管设置，其余进入文件库</span>
      </div>
      <button type="button" :disabled="uploading > 0" @click.stop="unifiedInput?.click()">
        选择文件
      </button>
      <input ref="unifiedInput" type="file" multiple hidden @change="onUnifiedPick" />
    </div>

    <template v-if="tab === 'files'">
      <div v-if="fileSettingsLoaded" class="note">
        支持拖拽或选择文件上传，单个文件不超过
        {{ fileSettings.fileMaxMb }}MB；分享链接始终需要人工点击并使用一次性票据<span
          v-if="fileSettings.downloadCaptchaEnabled"
          >，同时回答一道算术题</span
        >。
      </div>
      <div v-else class="note">
        {{
          fileSettingsError
            ? '上传上限读取失败，上传时仍由服务端按真实设置校验。'
            : '正在读取上传与下载设置…'
        }}
      </div>
      <div class="file-drop" @dragover.prevent @drop.prevent="onDrop">
        <div v-if="!data.files.length" class="img-empty">
          <i class="ri-folder-line"></i>
          <div>还没有文件——把文件拖到这里，或点右上角“上传文件”</div>
        </div>
        <div v-else class="file-list">
          <div v-for="(f, i) in data.files" :key="i" class="file-row">
            <div class="file-icon"><i :class="f.icon"></i></div>
            <div class="file-main">
              <FileDisplayNameEditor :file="f" />
              <div class="file-sub">
                {{ f.by }} · {{ f.time }} ·
                <span v-if="f.projectId" class="project-badge"
                  >项目 · {{ projectName(f.projectId) }}</span
                >
                <span class="wao-mono">{{ f.id ? fileShareUrl(f.id) : f.link }}</span>
              </div>
            </div>
            <span class="file-size">{{ f.size }}</span>
            <button
              v-if="f.id && isMarkdown(f.name)"
              class="copy-btn"
              title="站内预览 Markdown"
              @click="preview = { kind: 'markdown', id: f.id, name: fileDisplayName(f) }"
            >
              <i class="ri-eye-line"></i>预览
            </button>
            <button
              class="copy-btn"
              title="复制分享链接（对方需人工点击并通过校验）"
              @click="copyText(f.id ? fileShareUrl(f.id) : absoluteUrl(f.link), '已复制分享链接')"
            >
              <i class="ri-links-line"></i>复制链接
            </button>
            <a class="dl-btn" title="直接下载" :href="absoluteUrl(f.link)"
              ><i class="ri-download-2-line"></i
            ></a>
            <button class="dl-btn danger" title="删除" @click="removeFile(f.id, fileDisplayName(f))">
              <i class="ri-delete-bin-line"></i>
            </button>
          </div>
        </div>
      </div>
    </template>

    <template v-else-if="tab === 'img'">
      <div v-if="fileSettingsLoaded" class="note">
        任务描述、子任务、新建任务里粘贴或上传的图片都统一归档到这里，单图不超过
        {{ fileSettings.imageMaxMb }}MB，并提供公网访问链接。
      </div>
      <div v-else class="note">
        {{
          fileSettingsError
            ? '图床上限读取失败，上传时仍由服务端按真实设置校验。'
            : '正在读取图床设置…'
        }}
      </div>
      <div class="img-drop" @dragover.prevent @drop.prevent="onDrop">
        <div v-if="data.loadStates.images === 'loading'" class="img-empty" role="status">
          <i class="ri-loader-4-line spin"></i>
          <div>正在读取真实图床…</div>
        </div>
        <div v-else-if="data.loadStates.images === 'error'" class="img-empty" role="alert">
          <i class="ri-error-warning-line"></i>
          <div>{{ data.loadErrors.images || '图床读取失败，请重试' }}</div>
          <button class="img-more" @click="data.refreshImages()">重新读取</button>
        </div>
        <div v-else class="img-grid">
          <div v-for="(im, i) in visibleImages" :key="im.id ?? i" class="img-card">
            <div
              class="img-thumb"
              :style="{
                background: `repeating-linear-gradient(45deg, ${im.c1} 0 10px, ${im.c2} 10px 20px)`,
              }"
            >
              <img :src="im.url" :alt="im.name" loading="lazy" @click="openImage(im.url)" />
              <button
                v-if="im.id"
                class="img-del"
                title="从图床删除"
                @click.stop="removeImage(im.id, im.name)"
              >
                <i class="ri-delete-bin-line"></i>
              </button>
            </div>
            <div class="img-info">
              <div class="img-name" :title="im.name">{{ im.name }}</div>
              <div class="img-meta">{{ im.size }} · {{ im.time }} · 来自 {{ im.from }}</div>
              <div v-if="im.projectId" class="img-meta">项目 · {{ projectName(im.projectId) }}</div>
              <button class="img-copy" @click="copyImageLink(im.url)">
                <i class="ri-links-line"></i>复制公网链接
              </button>
            </div>
          </div>
        </div>
        <button v-if="hiddenCount" class="img-more" @click="showAllImages = true">
          <i class="ri-arrow-down-s-line"></i>展开其余 {{ hiddenCount }} 张
        </button>
        <button
          v-else-if="showAllImages && data.imgs.length > IMG_PAGE"
          class="img-more"
          @click="showAllImages = false"
        >
          <i class="ri-arrow-up-s-line"></i>收起，只看最近 {{ IMG_PAGE }} 张
        </button>

        <div v-if="data.loadStates.images === 'loaded' && !data.imgs.length" class="img-empty">
          <i class="ri-image-add-line"></i>
          <div>图床还是空的——把图片拖到这里，或点右上角「上传图片」</div>
        </div>
        <div v-else class="drop-tip">也可以把图片直接拖到这一片区域上传</div>
      </div>
    </template>

    <template v-else>
      <div class="note">
        上传单文件 HTML，自定义公网链接后缀、浏览器标题和 icon。页面脚本在隔离环境中运行，无法读取
        WAO 登录态。
      </div>
      <div class="page-source-bar" role="tablist" aria-label="网页托管类型">
        <span>类型</span>
        <button
          v-for="option in hostedPageSourceFilters"
          :key="option.id"
          :class="{ active: hostedPageSource === option.id }"
          @click="selectHostedPageSource(option.id)"
        >
          {{ option.label }}
        </button>
      </div>
      <div v-if="!data.hostedPages.length" class="img-empty page-empty">
        <i class="ri-global-line"></i>
        <div>还没有托管网页——点右上角“托管 HTML”快速发布</div>
      </div>
      <div v-else-if="!filteredHostedPages.length" class="img-empty page-empty">
        <i class="ri-filter-off-line"></i>
        <div>当前类型下还没有托管网页</div>
      </div>
      <div v-else class="file-list">
        <div v-for="page in visibleHostedPages" :key="page.id" class="file-row">
          <div class="file-icon"><i class="ri-html5-line"></i></div>
          <div class="file-main">
            <div class="file-name page-name">
              {{ page.title }}
              <span :class="['page-source', hostedPageSourceType(page).toLowerCase()]">
                {{ hostedPageSourceType(page) === 'AI_RUN_REPORT' ? 'AI Run 报告' : '人工上传' }}
              </span>
            </div>
            <div class="file-sub">
              {{ page.fileName }} · {{ page.updatedAt }} ·
              <span v-if="page.projectId" class="project-badge"
                >项目 · {{ projectName(page.projectId) }}</span
              >
              <span class="wao-mono">{{ absoluteUrl(page.url) }}</span>
            </div>
          </div>
          <button class="copy-btn" @click="copyText(absoluteUrl(page.url), '已复制公网链接')">
            <i class="ri-links-line"></i>复制链接
          </button>
          <button
            class="dl-btn"
            title="站内预览"
            @click="preview = { kind: 'html', name: page.title, url: page.url }"
          >
            <i class="ri-eye-line"></i>
          </button>
          <button class="dl-btn" title="修改" @click="openPageDialog(page)">
            <i class="ri-edit-line"></i>
          </button>
          <button class="dl-btn danger" title="删除" @click="removePage(page)">
            <i class="ri-delete-bin-line"></i>
          </button>
        </div>
        <WaoPagination
          v-if="filteredHostedPages.length > HOSTED_PAGE_SIZE"
          v-model:page="hostedPage"
          class="pagination"
          :page-count="hostedPageCount"
          :total="filteredHostedPages.length"
          aria-label="托管网页分页"
        />
      </div>
    </template>

    <div v-if="pageDialog" class="dialog-mask" @click.self="closePageDialog">
      <form class="page-dialog" @submit.prevent="savePage">
        <div class="dialog-title">{{ editingPage ? '修改托管网页' : '托管 HTML' }}</div>
        <label
          >页面标题<input
            v-model="pageTitle"
            maxlength="200"
            placeholder="显示在浏览器标签页"
            @input="pageTitleAuto = false"
        /></label>
        <label
          >链接后缀
          <div class="slug-input">
            <span>/api/pages/</span
            ><input
              v-model="pageSlug"
              maxlength="80"
              placeholder="my-page"
              @input="pageSlugAuto = false"
            /></div
        ></label>
        <div class="page-file-field">
          HTML 文件
          <button
            type="button"
            class="page-file-picker html-picker"
            :class="{ dragging: pageHtmlDragging, selected: pageHtml }"
            :disabled="pageSaving"
            @click="pageHtmlInput?.click()"
            @dragenter.prevent="pageHtmlDragging = true"
            @dragover.prevent="pageHtmlDragging = true"
            @dragleave.prevent="pageHtmlDragging = false"
            @drop.prevent="onPageHtmlDrop"
          >
            <i :class="pageHtml ? 'ri-file-check-line' : 'ri-drag-drop-line'"></i>
            <span>
              <b>{{
                pageHtml
                  ? pageHtml.name
                  : editingPage
                    ? '拖入新的 HTML，或点击替换'
                    : '拖入 HTML，或点击选择'
              }}</b>
              <small>{{
                pageHtml
                  ? '已选择，可直接发布'
                  : editingPage
                    ? '不选择则保留现有文件'
                    : '支持 .html 和 .htm 单文件'
              }}</small>
            </span>
          </button>
          <input
            ref="pageHtmlInput"
            type="file"
            accept=".html,.htm,text/html"
            hidden
            @change="onPageHtmlPick"
          />
        </div>
        <div class="page-file-field">
          icon 图片（可选）
          <button
            type="button"
            class="page-file-picker icon-picker"
            :class="{ dragging: pageIconDragging, selected: pageIcon }"
            :disabled="pageSaving"
            @click="pageIconInput?.click()"
            @dragenter.prevent="pageIconDragging = true"
            @dragover.prevent="pageIconDragging = true"
            @dragleave.prevent="pageIconDragging = false"
            @drop.prevent="onPageIconDrop"
          >
            <i :class="pageIcon ? 'ri-image-check-line' : 'ri-image-add-line'"></i>
            <span>
              <b>{{ pageIcon ? pageIcon.name : '拖入图片，或点击选择' }}</b>
              <small
                >最大 1MB；不选择则{{
                  editingPage ? '保留现有 icon' : '使用浏览器默认 icon'
                }}</small
              >
            </span>
          </button>
          <input ref="pageIconInput" type="file" accept="image/*" hidden @change="onPageIconPick" />
        </div>
        <div class="dialog-actions">
          <button type="button" class="copy-btn" @click="closePageDialog">取消</button
          ><button class="btn-primary" :disabled="pageSaving">
            {{ pageSaving ? '发布中…' : '发布' }}
          </button>
        </div>
      </form>
    </div>

    <FileSettingsModal
      v-if="settingsOpen"
      @close="settingsOpen = false"
      @saved="onFileSettingsSaved"
    />
    <AssetPreviewModal v-if="preview" :asset="preview" @close="preview = null" />
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
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(16, 18, 28, 0.1);
}
.project-badge {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--wao-primary-soft, #eef0ff);
  color: var(--wao-primary);
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
.settings-button {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary i {
  font-size: 14px;
}
.unified-drop {
  margin-top: 14px;
  min-height: 82px;
  padding: 14px 18px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  display: flex;
  align-items: center;
  gap: 13px;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s,
    transform 0.15s;
}
.unified-drop:hover,
.unified-drop:focus-visible,
.unified-drop.dragging {
  border-color: var(--wao-primary);
  background: var(--wao-primary-tint);
  outline: none;
}
.unified-drop.dragging {
  transform: translateY(-1px);
}
.unified-drop > i {
  font-size: 26px;
  color: var(--wao-primary);
}
.unified-drop > div {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 4px;
}
.unified-drop b {
  font-size: 13px;
}
.unified-drop span {
  color: var(--wao-text-weak);
  font-size: 11.5px;
}
.unified-drop button {
  height: 30px;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
}
.unified-drop button:disabled,
.unified-drop.busy {
  cursor: progress;
}
.note {
  margin-top: 12px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.page-empty {
  margin-top: 16px;
}
.page-source-bar {
  margin-top: 12px;
  padding: 7px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-card);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.page-source-bar > span {
  padding: 0 6px;
  color: var(--wao-text-weak);
  font-size: 11px;
  font-weight: 650;
}
.page-source-bar button {
  min-height: 28px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
  font-size: 11px;
}
.page-source-bar button.active {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 650;
}
.page-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
}
.page-source {
  padding: 2px 6px;
  border-radius: 999px;
  background: var(--wao-bg-hover);
  color: var(--wao-text-weak);
  font-size: 9.5px;
  font-weight: 650;
}
.page-source.ai_run_report {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(15, 18, 30, 0.42);
  display: grid;
  place-items: center;
  padding: 16px;
}
.page-dialog {
  width: min(480px, 100%);
  background: var(--wao-bg-card);
  border-radius: 14px;
  padding: 20px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  display: grid;
  gap: 14px;
}
.dialog-title {
  font-size: 18px;
  font-weight: 700;
}
.page-dialog label,
.page-file-field {
  display: grid;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}
.page-dialog input {
  min-width: 0;
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg);
  color: var(--wao-text);
}
.page-dialog input[type='file'] {
  height: auto;
  padding: 8px;
}
.page-file-picker {
  width: 100%;
  min-height: 66px;
  padding: 10px 12px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: 9px;
  background: var(--wao-bg);
  color: var(--wao-text-2);
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.page-file-picker:hover,
.page-file-picker:focus-visible,
.page-file-picker.dragging {
  border-color: var(--wao-primary);
  background: var(--wao-primary-tint);
  outline: none;
}
.page-file-picker.selected {
  border-style: solid;
  border-color: var(--wao-primary);
}
.page-file-picker:disabled {
  cursor: progress;
  opacity: 0.7;
}
.page-file-picker > i {
  flex-shrink: 0;
  font-size: 22px;
  color: var(--wao-primary);
}
.page-file-picker > span {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.page-file-picker b {
  max-width: 390px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
}
.page-dialog small {
  color: var(--wao-text-weak);
  font-weight: 400;
}
.slug-input {
  display: flex;
  align-items: center;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  overflow: hidden;
  background: var(--wao-bg);
}
.slug-input span {
  padding-left: 10px;
  color: var(--wao-text-weak);
  font: 12px monospace;
}
.slug-input input {
  flex: 1;
  border: 0;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.file-list {
  margin-top: 10px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
}
.file-drop {
  min-height: 120px;
  border-radius: 12px;
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
  background: var(--wao-bg-tab);
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
  background: var(--wao-bg-card);
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
  background: var(--wao-bg-hover);
}
.copy-btn i {
  font-size: 13px;
}
.dl-btn {
  width: 27px;
  height: 27px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  text-decoration: none;
}
.dl-btn:hover {
  background: var(--wao-bg-hover);
}
.dl-btn i {
  font-size: 13px;
}
.dl-btn.danger:hover {
  color: var(--wao-danger-strong);
  background: var(--wao-danger-bg);
}

.img-grid {
  margin-top: 10px;
  display: grid;
  /* 固定 5 列：配合 IMG_PAGE=15，默认正好铺满三排 */
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
}
@media (max-width: 1100px) {
  .img-grid {
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  }
}
.img-more {
  margin-top: 12px;
  width: 100%;
  height: 34px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  border-radius: 9px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.img-more:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary);
}
.img-more i {
  font-size: 15px;
}
.img-card {
  background: var(--wao-bg-card);
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
  background: var(--wao-bg-card);
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
  background: var(--wao-bg-hover);
}
.img-copy i {
  font-size: 12px;
}

@media (max-width: 760px) {
  .files {
    padding: 16px 12px 28px;
  }
  .toolbar {
    gap: 7px;
  }
  .toolbar .search {
    flex: 1 1 100%;
  }
  .toolbar .search input {
    width: 100%;
    height: 38px;
  }
  .img-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 9px;
  }
  .file-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .file-main {
    min-width: calc(100% - 48px);
  }
  .file-size {
    margin-left: 42px;
  }
  .unified-drop {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .unified-drop > div {
    min-width: calc(100% - 44px);
  }
  .unified-drop button {
    margin-left: 39px;
  }
}
</style>
