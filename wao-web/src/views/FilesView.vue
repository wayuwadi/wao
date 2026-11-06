<script setup lang="ts">
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import { useConfirmAction } from '@/composables/confirmAction'
const { confirmation, confirmAction, dismissConfirmation: dismissAction } = useConfirmAction()
let pendingPageLeave: ((allow: boolean) => void) | null = null
function dismissConfirmation() {
  pendingPageLeave?.(false)
  pendingPageLeave = null
  dismissAction()
}
import {
  computed,
  inject,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  toRefs,
  useId,
  watch,
} from 'vue'
import { storeToRefs } from 'pinia'
import { matchedRouteKey, onBeforeRouteLeave, useRoute } from 'vue-router'
import { useUploadStore, type UploadAttempt } from '@/stores/uploads'
import { useBrowsingState, oneOf, validPage, validSearch } from '@/composables/browsingState'
import { useBrowsingScroll } from '@/composables/browsingScroll'
import { useDataStore } from '@/stores/data'
import { copyText } from '@/utils/clipboard'
import { absoluteUrl, fileShareUrl } from '@/utils/share'
import { toast } from '@/composables/toast'
import WaoPagination from '@/components/ui/WaoPagination.vue'
import WaoEmptyState from '@/components/ui/WaoEmptyState.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import FileSettingsModal from '@/components/overlays/FileSettingsModal.vue'
import { fetchFileSettings, type FileSettings } from '@/api/system'
import type { FileItem, HostedPage } from '@/types'
import AssetPreviewModal, { type AssetPreview } from '@/components/files/AssetPreviewModal.vue'
import FileDisplayNameEditor from '@/components/files/FileDisplayNameEditor.vue'
import AssetReplaceModal, { type ReplacementTarget } from '@/components/files/AssetReplaceModal.vue'
import { fileDisplayName } from '@/utils/files'
import { useSessionStore } from '@/stores/session'
import { useClientPagination } from '@/composables/clientPagination'
import {
  fileCategories,
  fileCategory,
  formatResourceBytes,
  matchesResourceText,
  normalizedResourceText,
  resourceBytes,
  resourceCapacity,
  searchTerms,
  sortResources,
  type FileCategory,
  type ResourceSort,
} from '@/utils/resourceLibrary'

const data = useDataStore()
const session = useSessionStore()
const route = useRoute()
const validScroll = (value: unknown) =>
  typeof value === 'number' && value >= 0 && Number.isFinite(value)
const browse = useBrowsingState(
  'files',
  {
    tab: 'files' as 'files' | 'img' | 'pages',
    hostedPage: 1,
    imagePage: 1,
    filePage: 1,
    searchFiles: '',
    searchImages: '',
    searchPages: '',
    fileType: 'all' as FileCategory,
    sortFiles: 'recent' as ResourceSort,
    sortImages: 'recent' as ResourceSort,
    sortPages: 'recent' as ResourceSort,
    hostedPageSource: 'ALL' as HostedPageSourceFilter,
    scrollFiles: 0,
    scrollImages: 0,
    scrollPages: 0,
    lastTarget: '',
  },
  {
    tab: oneOf('files', 'img', 'pages'),
    hostedPage: validPage,
    imagePage: validPage,
    filePage: validPage,
    searchFiles: validSearch,
    searchImages: validSearch,
    searchPages: validSearch,
    fileType: oneOf(...fileCategories.map((category) => category.id)),
    sortFiles: oneOf('recent', 'name', 'size'),
    sortImages: oneOf('recent', 'name', 'size'),
    sortPages: oneOf('recent', 'name'),
    hostedPageSource: oneOf('ALL', 'MANUAL_UPLOAD', 'AI_RUN_REPORT'),
    scrollFiles: validScroll,
    scrollImages: validScroll,
    scrollPages: validScroll,
    lastTarget: validSearch,
  },
)
const { tab, hostedPage, hostedPageSource } = toRefs(browse)
const searchInput = ref<HTMLInputElement>()
const searchKey = computed(() =>
  tab.value === 'files' ? 'searchFiles' : tab.value === 'img' ? 'searchImages' : 'searchPages',
)
const sortKey = computed(() =>
  tab.value === 'files' ? 'sortFiles' : tab.value === 'img' ? 'sortImages' : 'sortPages',
)
function resetCurrentPage() {
  if (tab.value === 'files') browse.filePage = 1
  else if (tab.value === 'img') browse.imagePage = 1
  else browse.hostedPage = 1
}
const search = computed({
  get: () => browse[searchKey.value],
  set: (value) => {
    browse[searchKey.value] = value
    resetCurrentPage()
  },
})
const sort = computed({
  get: () => browse[sortKey.value],
  set: (value) => {
    browse[sortKey.value] = value
    resetCurrentPage()
  },
})
const searchLabel = computed(() =>
  tab.value === 'files'
    ? '搜索文件名称、上传者或项目'
    : tab.value === 'img'
      ? '搜索图片名称、来源或项目'
      : '搜索网页标题、文件名或链接',
)
const projectNames = computed(
  () => new Map(data.projects.map((project) => [project.id, project.name])),
)
const memberNames = computed(() => {
  const names = new Map(session.members.map((member) => [member.id, member.name]))
  if (session.account?.member) names.set(session.account.member.id, session.account.member.name)
  return names
})
function fileAuthor(by: string) {
  return memberNames.value.get(by) || by
}
function fileMeta(file: FileItem) {
  return [
    fileAuthor(file.by),
    file.time,
    file.projectId ? `项目 · ${projectName(file.projectId)}` : '',
  ]
    .filter(Boolean)
    .join(' · ')
}
const fileTerms = computed(() => searchTerms(browse.searchFiles))
const imageTerms = computed(() => searchTerms(browse.searchImages))
const pageTerms = computed(() => searchTerms(browse.searchPages))
const indexedFiles = computed(() =>
  data.files.map((file) => ({
    file,
    category: fileCategory(file),
    text: normalizedResourceText(
      fileDisplayName(file),
      file.name,
      fileAuthor(file.by),
      file.by,
      projectName(file.projectId),
    ),
  })),
)
const sortedFiles = computed(() =>
  sortResources(
    indexedFiles.value,
    browse.sortFiles,
    (entry) => fileDisplayName(entry.file),
    (entry) => resourceBytes(entry.file),
  ),
)
const filteredFiles = computed(() =>
  sortedFiles.value
    .filter(
      (entry) =>
        (browse.fileType === 'all' || entry.category === browse.fileType) &&
        matchesResourceText(fileTerms.value, entry.text),
    )
    .map((entry) => entry.file),
)
const indexedImages = computed(() =>
  data.imgs.map((image) => ({
    image,
    text: normalizedResourceText(image.name, image.from, projectName(image.projectId)),
  })),
)
const sortedImages = computed(() =>
  sortResources(
    indexedImages.value,
    browse.sortImages,
    (entry) => entry.image.name,
    (entry) => resourceBytes(entry.image),
  ),
)
const filteredImages = computed(() =>
  sortedImages.value
    .filter((entry) => matchesResourceText(imageTerms.value, entry.text))
    .map((entry) => entry.image),
)
function clearFilters() {
  search.value = ''
  if (tab.value === 'files') browse.fileType = 'all'
  if (tab.value === 'pages') hostedPageSource.value = 'ALL'
  resetCurrentPage()
  void nextTick(() => searchInput.value?.focus())
}
const filesRoot = ref<HTMLElement>()
const scrollKey = computed(() =>
  tab.value === 'files' ? 'scrollFiles' : tab.value === 'img' ? 'scrollImages' : 'scrollPages',
)
const { stopRestoring } = useBrowsingScroll(
  filesRoot,
  computed({
    get: () => browse[scrollKey.value],
    set: (value) => {
      browse[scrollKey.value] = value
    },
  }),
  () =>
    tab.value === 'files'
      ? data.fileLoadState === 'loaded'
      : tab.value === 'img'
        ? data.loadStates.images === 'loaded'
        : data.hostedPageLoadState === 'loaded',
  () => `${session.account?.accountId}:${session.userId}:${tab.value}`,
)
const uploads = useUploadStore()
const {
  uploadAttempts,
  uploading,
  failedUploads,
  successfulUploads,
  checkingUploads,
  uploadCheckMessage,
} = storeToRefs(uploads)
const { retryUpload, checkUploadList } = uploads
const pageDialog = ref(false)
const pageFormId = `hosted-page-${useId()}`
const pageTitleInput = ref<HTMLInputElement>()
let pageGeneration = 0
const editingPage = ref<HostedPage | null>(null)
const pageExpectedRevision = ref<number>()
const pageConflict = ref(false)
const pageLatest = ref<HostedPage | null>(null)
const pageReadingLatest = ref(false)
const pageLatestError = ref('')
const pageTitle = ref('')
const pageSlug = ref('')
const pageHtml = ref<File | null>(null)
const pageIcon = ref<File | null>(null)
const pageSaving = ref(false)
const pageDirty = computed(
  () =>
    !!pageHtml.value ||
    !!pageIcon.value ||
    pageTitle.value !== (editingPage.value?.title ?? '') ||
    pageSlug.value !== (editingPage.value?.slug ?? ''),
)
const pageError = ref('')
const pageValidation = ref(false)
const pageTitleError = computed(() =>
  pageValidation.value && !pageTitle.value.trim() ? '请填写显示名称' : '',
)
const pageSlugError = computed(() =>
  pageValidation.value && !/^[a-z0-9](?:[a-z0-9-]{0,78}[a-z0-9])?$/.test(pageSlug.value.trim())
    ? '使用 1–80 个小写字母、数字或中划线，首尾不能是中划线'
    : '',
)
const pageHtmlError = ref('')
const pageIconError = ref('')
const pageTitleAuto = ref(false)
const pageSlugAuto = ref(false)
const pageHtmlInput = ref<HTMLInputElement | null>(null)
const pageIconInput = ref<HTMLInputElement | null>(null)
const pageHtmlDragging = ref(false)
const pageIconDragging = ref(false)
const settingsOpen = ref(false)
const fileSettings = ref<FileSettings>({
  fileMaxMb: 200,
  imageMaxMb: 10,
  downloadCaptchaEnabled: true,
})
const fileSettingsLoaded = ref(false)
const fileSettingsError = ref(false)
let settingsGeneration = 0
async function loadFileSettings() {
  const generation = ++settingsGeneration
  try {
    const settings = await fetchFileSettings()
    if (generation !== settingsGeneration) return
    fileSettings.value = settings
    fileSettingsLoaded.value = true
    fileSettingsError.value = false
  } catch {
    if (generation === settingsGeneration) fileSettingsError.value = true
  }
}

function guardPageUnload(event: BeforeUnloadEvent) {
  if (!pageDialog.value || (!pageDirty.value && !pageSaving.value)) return
  event.preventDefault()
  event.returnValue = ''
}

if (inject(matchedRouteKey, undefined)) {
  onBeforeRouteLeave(() => {
    if (pageSaving.value) return false
    if (!pageDialog.value || !pageDirty.value) return true
    if (confirmation.value) return false
    return new Promise<boolean>((resolve) => {
      pendingPageLeave = resolve
      confirmAction({
        title: '离开并放弃未发布的修改？',
        description: '网页尚未发布。离开后需要重新填写内容并选择文件。',
        confirmLabel: '放弃并离开',
        run: () => {
          pendingPageLeave = null
          discardPageDraft()
          resolve(true)
        },
      })
    })
  })
}

const HOSTED_PAGE_SIZE = 10
type HostedPageSourceFilter = 'ALL' | 'MANUAL_UPLOAD' | 'AI_RUN_REPORT'
const hostedPageSourceFilters: Array<{ id: HostedPageSourceFilter; label: string }> = [
  { id: 'ALL', label: '全部类型' },
  { id: 'MANUAL_UPLOAD', label: '人工上传' },
  { id: 'AI_RUN_REPORT', label: 'AI Run 报告' },
]
function hostedPageSourceType(page: HostedPage) {
  return page.sourceType ?? (page.id.startsWith('run-report-') ? 'AI_RUN_REPORT' : 'MANUAL_UPLOAD')
}
const indexedPages = computed(() =>
  data.hostedPages.map((page) => ({
    page,
    source: hostedPageSourceType(page),
    text: normalizedResourceText(
      page.title,
      page.fileName,
      page.slug,
      page.url,
      projectName(page.projectId),
    ),
  })),
)
const sortedPages = computed(() =>
  sortResources(indexedPages.value, browse.sortPages, (entry) => entry.page.title),
)
const filteredHostedPages = computed(() =>
  sortedPages.value
    .filter(
      (entry) =>
        (hostedPageSource.value === 'ALL' || entry.source === hostedPageSource.value) &&
        matchesResourceText(pageTerms.value, entry.text),
    )
    .map((entry) => entry.page),
)
const resultCount = computed(() =>
  tab.value === 'files'
    ? filteredFiles.value.length
    : tab.value === 'img'
      ? filteredImages.value.length
      : filteredHostedPages.value.length,
)
const totalCount = computed(() =>
  tab.value === 'files'
    ? data.files.length
    : tab.value === 'img'
      ? data.imgs.length
      : data.hostedPages.length,
)
const listState = computed(() =>
  tab.value === 'files'
    ? data.fileLoadState
    : tab.value === 'img'
      ? data.loadStates.images
      : data.hostedPageLoadState,
)
const isFiltered = computed(
  () =>
    !!search.value.trim() ||
    (tab.value === 'files' && browse.fileType !== 'all') ||
    (tab.value === 'pages' && hostedPageSource.value !== 'ALL'),
)
const capacity = computed(() =>
  resourceCapacity(tab.value === 'files' ? filteredFiles.value : filteredImages.value),
)
const capacityText = computed(
  () =>
    `${capacity.value.approximate ? '约 ' : ''}${formatResourceBytes(capacity.value.bytes)}${capacity.value.incomplete ? '（部分大小未知）' : ''}`,
)
function refreshCurrentList() {
  if (tab.value === 'files') return data.refreshFiles()
  if (tab.value === 'img') return data.refreshImages()
  return data.refreshHostedPages()
}
const hostedPageCount = computed(() =>
  Math.max(1, Math.ceil(filteredHostedPages.value.length / HOSTED_PAGE_SIZE)),
)
const visibleHostedPages = computed(() =>
  filteredHostedPages.value.slice(
    (hostedPage.value - 1) * HOSTED_PAGE_SIZE,
    hostedPage.value * HOSTED_PAGE_SIZE,
  ),
)

/** 图床每页三排，避免一次展开数千张卡片阻塞页面。 */
const IMG_PAGE = 15
const FILE_PAGE_SIZE = 30
const {
  page: filePage,
  pageCount: filePageCount,
  visible: visibleFiles,
} = useClientPagination(filteredFiles, [], FILE_PAGE_SIZE, {
  page: toRefs(browse).filePage,
  ready: () => data.fileLoadState === 'loaded',
})
const {
  page: imagePage,
  pageCount: imagePageCount,
  visible: visibleImages,
} = useClientPagination(filteredImages, [], IMG_PAGE, {
  page: toRefs(browse).imagePage,
  ready: () => data.loadStates.images === 'loaded',
})
const failedThumbnails = ref(new Set<string>())
function thumbnailFailed(url: string) {
  failedThumbnails.value = new Set(failedThumbnails.value).add(url)
}
function retryThumbnail(url: string) {
  const next = new Set(failedThumbnails.value)
  next.delete(url)
  failedThumbnails.value = next
}
const fileInput = ref<HTMLInputElement | null>(null)
const unifiedInput = ref<HTMLInputElement | null>(null)
const unifiedDragging = ref(false)
function uploadBatch(files: File[], kind: UploadAttempt['kind']) {
  return uploads.enqueue(
    files,
    kind,
    fileSettingsLoaded.value
      ? kind === 'img'
        ? fileSettings.value.imageMaxMb
        : fileSettings.value.fileMaxMb
      : undefined,
  )
}

const replacement = ref<ReplacementTarget | null>(null)
const preview = ref<AssetPreview | null>(null)

// 打开页面时重新拉一次，让其他标签页 / 其他人上传的图也出现
onMounted(() => {
  void data.refreshImages()
  void data.refreshFiles()
  void data.refreshHostedPages()
  void loadFileSettings()
  window.addEventListener('beforeunload', guardPageUnload)
})

watch(
  () => [filteredHostedPages.value.length, hostedPageSource.value, data.hostedPageLoadState],
  () => {
    if (data.hostedPageLoadState === 'loaded' && hostedPage.value > hostedPageCount.value)
      hostedPage.value = hostedPageCount.value
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
  await uploadBatch(list, 'img')
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
    .replace(/-+$/g, '')
  const candidate = base || `page-${Math.random().toString(36).slice(2, 10)}`
  if (!data.hostedPages.some((page) => page.slug === candidate)) return candidate
  return `${candidate.slice(0, 72)}-${Math.random().toString(36).slice(2, 8)}`
}

function setHostedPageHtml(file: File) {
  if (pageSaving.value) return
  if (!isHostedHtml(file)) {
    pageHtmlError.value = '请选择 .html 或 .htm 文件'
    return
  }
  if (!file.size) {
    pageHtmlError.value = 'HTML 文件不能为空'
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    pageHtmlError.value = 'HTML 文件不能超过 10MB，请压缩内容后重选'
    return
  }
  pageHtmlError.value = ''
  pageHtml.value = file
  if (editingPage.value) return
  if (pageTitleAuto.value || !pageTitle.value.trim()) {
    pageTitle.value = hostedPageBaseName(file.name).slice(0, 200) || '未命名页面'
    pageTitleAuto.value = true
  }
  if (pageSlugAuto.value || !pageSlug.value.trim()) {
    pageSlug.value = nextHostedPageSlug(file.name)
    pageSlugAuto.value = true
  }
}

function openPageDialog(page?: HostedPage, html?: File) {
  if (pageSaving.value) return
  pageGeneration++
  editingPage.value = page ? { ...page } : null
  pageExpectedRevision.value = page?.revision
  pageConflict.value = false
  pageLatest.value = null
  pageReadingLatest.value = false
  pageLatestError.value = ''
  pageTitle.value = page?.title ?? ''
  pageSlug.value = page?.slug ?? ''
  pageHtml.value = null
  pageIcon.value = null
  pageTitleAuto.value = !page
  pageSlugAuto.value = !page
  pageValidation.value = false
  pageError.value = ''
  pageHtmlError.value = ''
  pageIconError.value = ''
  if (html) setHostedPageHtml(html)
  pageDialog.value = true
  const generation = pageGeneration
  void nextTick(() => {
    if (pageDialog.value && generation === pageGeneration) pageTitleInput.value?.focus({ preventScroll: true })
  })
}

function closePageDialog() {
  if (pageSaving.value) return
  if (pageDirty.value) {
    confirmAction({
      title: '放弃未发布的修改？',
      description: '填写的内容和选择的文件尚未发布，放弃后需要重新填写。',
      confirmLabel: '放弃修改',
      run: discardPageDraft,
    })
    return
  }
  discardPageDraft()
}

function discardPageDraft() {
  pageGeneration++
  pageDialog.value = false
  pageHtml.value = null
  pageIcon.value = null
  pageConflict.value = false
  pageLatest.value = null
  pageReadingLatest.value = false
  pageLatestError.value = ''
}

async function readLatestPage() {
  if (!editingPage.value?.id || pageReadingLatest.value) return
  const generation = pageGeneration
  const id = editingPage.value.id
  pageReadingLatest.value = true
  pageLatest.value = null
  pageLatestError.value = ''
  const loaded = await data.refreshHostedPages().catch(() => false)
  if (generation !== pageGeneration) return
  pageReadingLatest.value = false
  if (!loaded) {
    pageLatestError.value = '最新版本读取失败，你的输入与文件已保留，请重试。'
    return
  }
  const latest = data.hostedPages.find(page => page.id === id)
  if (!latest) {
    pageLatestError.value = '网页已删除或当前无法访问，不能继续覆盖。你的输入与文件仍保留在窗口中。'
    return
  }
  if (!Number.isSafeInteger(latest.revision) || Number(latest.revision) < 0) {
    pageLatestError.value = '暂时无法确认最新版本，请重新读取后再发布。'
    return
  }
  pageLatest.value = { ...latest }
}

function useLatestPage() {
  if (!pageLatest.value || pageSaving.value) return
  const latest = pageLatest.value
  editingPage.value = { ...latest }
  pageExpectedRevision.value = latest.revision
  pageTitle.value = latest.title
  pageSlug.value = latest.slug
  pageHtml.value = null
  pageIcon.value = null
  pageError.value = pageHtmlError.value = pageIconError.value = ''
  pageValidation.value = false
  pageConflict.value = false
  pageLatest.value = null
}

async function republishPageDraft() {
  if (!pageLatest.value || pageSaving.value) return
  editingPage.value = { ...pageLatest.value }
  pageExpectedRevision.value = pageLatest.value.revision
  pageConflict.value = false
  pageLatest.value = null
  await savePage()
}

function replacePage(page: HostedPage) {
  replacement.value = { kind: 'html', id: page.id, name: page.title, url: page.url, ...(page.revision !== undefined ? { revision: page.revision } : {}) }
}

function onPageHtmlPick(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (file) setHostedPageHtml(file)
  ;(event.target as HTMLInputElement).value = ''
}

function onPageHtmlDrop(event: DragEvent) {
  pageHtmlDragging.value = false
  const file = event.dataTransfer?.files[0]
  if (!file) return
  setHostedPageHtml(file)
}

function setPageIcon(file: File) {
  if (pageSaving.value) return
  if (!file.type.startsWith('image/')) {
    pageIconError.value = '请选择图片文件作为网页图标'
    return
  }
  if (file.size > 1024 * 1024) {
    pageIconError.value = '网页图标不能超过 1MB，请压缩后重选'
    return
  }
  pageIconError.value = ''
  pageIcon.value = file
}

function onPageIconPick(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (file) setPageIcon(file)
  ;(event.target as HTMLInputElement).value = ''
}

function onPageIconDrop(event: DragEvent) {
  pageIconDragging.value = false
  const file = event.dataTransfer?.files[0]
  if (file) setPageIcon(file)
}

function clearPageHtml() {
  if (pageSaving.value) return
  pageHtml.value = null
  pageHtmlError.value = ''
}

function clearPageIcon() {
  if (pageSaving.value) return
  pageIcon.value = null
  pageIconError.value = ''
}

async function savePage() {
  if (pageSaving.value || pageConflict.value) return
  pageValidation.value = true
  pageError.value = ''
  if (!editingPage.value && !pageHtml.value) pageHtmlError.value = '请选择要发布的 HTML 文件'
  if (pageTitleError.value || pageSlugError.value || pageHtmlError.value || pageIconError.value)
    return
  const generation = pageGeneration
  pageSaving.value = true
  try {
    const page = await data.saveHostedPage({
      id: editingPage.value?.id,
      html: pageHtml.value ?? undefined,
      icon: pageIcon.value ?? undefined,
      slug: pageSlug.value.trim(),
      title: pageTitle.value.trim(),
      ...(editingPage.value && pageExpectedRevision.value !== undefined ? { expectedRevision: pageExpectedRevision.value } : {}),
    })
    if (generation !== pageGeneration) return
    pageDialog.value = false
    pageHtml.value = null
    pageIcon.value = null
    hostedPageSource.value = 'ALL'
    browse.searchPages = ''
    hostedPage.value = Math.max(
      1,
      Math.floor(
        filteredHostedPages.value.findIndex((item) => item.id === page.id) / HOSTED_PAGE_SIZE,
      ) + 1,
    )
    toast(`已发布「${page.title}」`, 'ri-global-line')
  } catch (error) {
    if (generation !== pageGeneration) return
    if ((error as { status?: number })?.status === 409 && editingPage.value) {
      pageConflict.value = true
      pageSaving.value = false
      pageError.value = ''
      await readLatestPage()
      return
    }
    pageError.value = error instanceof Error ? error.message : '发布失败，请重试'
  } finally {
    if (generation === pageGeneration) pageSaving.value = false
  }
}
async function removePage(page: HostedPage) {
  confirmAction({
    title: '删除托管页面',
    description: `删除「${page.title}」后，公开链接将立即失效，此操作不可恢复。`,
    run: () => data.deleteHostedPage(page.id),
  })
}

async function uploadAttachments(files: FileList | File[]) {
  const list = Array.from(files)
  if (!list.length) return
  await uploadBatch(list, 'files')
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
  copyText(absoluteUrl(url), '已复制图片链接')
}
function openImage(url: string) {
  const image = data.imgs.find((item) => item.url === url)
  preview.value = { kind: 'image', name: image?.name || '图片预览', url }
}
function isMarkdown(name: string) {
  return /\.(?:md|markdown)$/i.test(name)
}
function projectName(projectId?: string | null) {
  return projectId ? projectNames.value.get(projectId) || projectId : ''
}
async function removeImage(id: string | undefined, name: string) {
  if (!id) {
    toast('内置示例图不可删除', 'ri-information-line')
    return
  }
  confirmAction({
    title: '删除图片',
    description: `确认删除「${name}」？此操作不可恢复，引用这张图片的内容可能无法继续显示它。`,
    run: () => data.deleteImage(id),
  })
}
async function removeFile(id: string | undefined, name: string) {
  if (!id) return
  confirmAction({
    title: '删除文件',
    description: `确认删除「${name}」？此操作不可恢复，原下载链接将无法使用。`,
    run: () => data.deleteFile(id),
  })
}

function onFileSettingsSaved(settings: FileSettings) {
  settingsGeneration++
  fileSettings.value = settings
  fileSettingsLoaded.value = true
  fileSettingsError.value = false
}
watch(
  () => [route?.query.file, route?.query.findRequest, route?.query.uploads, data.fileLoadState],
  async () => {
    const id = typeof route?.query.file === 'string' ? route.query.file : ''
    const target = `${id}:${route?.query.findRequest ?? ''}:${route?.query.uploads ?? ''}`
    if (target === browse.lastTarget || (!id && !route?.query.uploads)) return
    if (id && data.fileLoadState !== 'loaded') return
    browse.lastTarget = target
    if (id) {
      tab.value = 'files'
      browse.searchFiles = ''
      browse.fileType = 'all'
      const index = filteredFiles.value.findIndex((file) => file.id === id)
      if (index >= 0) filePage.value = Math.floor(index / FILE_PAGE_SIZE) + 1
    }
    await nextTick()
    stopRestoring()
    const element = id
      ? [...(filesRoot.value?.querySelectorAll<HTMLElement>('[data-file-id]') ?? [])].find(
          (node) => node.dataset.fileId === id,
        )
      : filesRoot.value?.querySelector<HTMLElement>('#upload-results')
    element?.scrollIntoView?.({ block: 'center' })
    element?.focus({ preventScroll: true })
  },
  { immediate: true, flush: 'post' },
)
watch(
  () => `${session.account?.accountId}:${session.userId}`,
  () => {
    pageGeneration++
    pageDialog.value = false
    pageSaving.value = false
    pageTitle.value = ''
    pageSlug.value = ''
    editingPage.value = null
    pageExpectedRevision.value = undefined
    pageConflict.value = false
    pageLatest.value = null
    pageReadingLatest.value = false
    pageLatestError.value = ''
    pageHtml.value = null
    pageIcon.value = null
    preview.value = null
    replacement.value = null
    failedThumbnails.value = new Set()
    settingsOpen.value = false
    fileSettingsLoaded.value = false
    fileSettingsError.value = false
    void loadFileSettings()
    dismissConfirmation()
  },
)
onBeforeUnmount(() => {
  pageGeneration++
  settingsGeneration++
  pendingPageLeave?.(false)
  pendingPageLeave = null
  window.removeEventListener('beforeunload', guardPageUnload)
})
</script>

<template>
  <ConfirmActionDialog v-if="confirmation" :action="confirmation" @close="dismissConfirmation" />
  <div ref="filesRoot" class="files wao-page-frame">
    <div class="ftop">
      <h1 class="ftitle">资源库</h1>
      <div class="seg" role="group" aria-label="资源类型">
        <button
          v-for="t in ['files', 'img', 'pages'] as const"
          :key="t"
          class="seg-btn"
          :class="{ on: tab === t }"
          :aria-pressed="tab === t"
          @click="tab = t"
        >
          {{ t === 'files' ? '文件' : t === 'img' ? '图片' : '网页托管' }}
        </button>
      </div>
      <div class="spacer"></div>
      <WaoButton class="settings-button" variant="ghost" @click="settingsOpen = true">
        <i aria-hidden="true" class="ri-settings-3-line"></i>设置
      </WaoButton>
      <WaoButton
        class="btn-primary"
        variant="primary"
        :disabled="pageSaving"
        @click="tab === 'pages' ? openPageDialog() : fileInput?.click()"
      >
        <i aria-hidden="true" class="ri-upload-2-line"></i
        >{{ tab === 'img' ? '上传图片' : tab === 'pages' ? '托管 HTML' : '上传文件' }}
      </WaoButton>
      <input
        ref="fileInput"
        type="file"
        :accept="tab === 'img' ? 'image/*' : undefined"
        multiple
        hidden
        @change="onPick"
      />
    </div>

    <button
      class="unified-drop"
      type="button"
      :class="{ dragging: unifiedDragging, busy: uploading > 0 }"
      aria-label="选择文件并自动分类，或拖拽文件到这里"
      @click="unifiedInput?.click()"
      @dragenter.prevent="unifiedDragging = true"
      @dragover.prevent="unifiedDragging = true"
      @dragleave.prevent="unifiedDragging = false"
      @drop.prevent="onUnifiedDrop"
    >
      <i
        aria-hidden="true"
        :class="uploading > 0 ? 'ri-loader-4-line spin' : 'ri-drag-drop-line'"
      ></i>
      <span class="drop-copy">
        <b>{{
          uploading > 0 ? `上传中 ${uploading} 项` : '自动分类上传'
        }}</b>
        <span>{{ tab === 'pages' ? 'HTML 需填写信息后发布' : '图片与文件离页继续上传' }}</span>
      </span>
      <span class="unified-action" aria-hidden="true">选择文件</span>
    </button>
    <input ref="unifiedInput" type="file" multiple hidden @change="onUnifiedPick" />

    <section
      v-if="uploadAttempts.length"
      id="upload-results"
      class="upload-results"
      aria-label="本次上传结果"
    >
      <div class="upload-result-header">
        <p role="status">
          已上传 {{ successfulUploads }} 项<span v-if="uploading"
            >，正在处理 {{ uploading }} 项</span
          ><span v-if="failedUploads.length">，{{ failedUploads.length }} 项需处理</span>。
        </p>
        <button type="button" :disabled="uploading > 0 || checkingUploads" @click="checkUploadList">
          {{ checkingUploads ? '正在核对…' : '刷新列表核对' }}
        </button>
        <button type="button" :disabled="uploading > 0" @click="uploads.clear()">
          清除上传记录
        </button>
        <button
          v-if="failedUploads.some((item) => item.retryable)"
          type="button"
          :disabled="uploading > 0"
          @click="uploads.retryFailedUploads()"
        >
          重试失败项
        </button>
      </div>
      <p v-if="uploadCheckMessage" role="status">{{ uploadCheckMessage }}</p>
      <p v-if="failedUploads.length">
        切换页面后仍可继续上传和重试；刷新或关闭浏览器后需重新选择未完成文件。清除记录不会删除已上传文件。
      </p>
      <ul>
        <li v-for="item in uploadAttempts" :key="item.id">
          <strong>{{ item.file.name }}</strong>
          <span
            >{{ item.kind === 'img' ? '图床' : '文件库' }} ·
            {{
              item.state === 'success'
                ? '已上传'
                : item.state === 'uploading'
                  ? item.progress === 100
                    ? '已传输，正在保存…'
                    : item.progress !== undefined
                      ? `上传中 ${item.progress}%`
                      : '上传中…'
                  : item.state === 'waiting'
                    ? '等待上传'
                    : '需处理'
            }}</span
          >
          <progress
            v-if="item.state === 'uploading'"
            :value="item.progress"
            max="100"
            :aria-label="`${item.file.name} 上传进度`"
          />
          <span v-if="item.reason" role="alert" class="upload-reason">{{ item.reason }}</span>
          <button
            v-if="item.state === 'failed' && item.retryable"
            type="button"
            :disabled="uploading > 0"
            :aria-label="`重试上传 ${item.file.name}`"
            @click="retryUpload(item)"
          >
            重试上传
          </button>
          <button
            v-if="item.state === 'failed' || item.state === 'waiting'"
            type="button"
            :aria-label="`移除上传记录 ${item.file.name}`"
            @click="uploads.removeUpload(item)"
          >
            移除
          </button>
        </li>
      </ul>
    </section>

    <div class="resource-toolbar" :class="{ 'file-tools': tab === 'files' }">
      <label class="resource-search">
        <i class="ri-search-line" aria-hidden="true"></i>
        <input
          ref="searchInput"
          v-model="search"
          type="search"
          maxlength="200"
          :aria-label="searchLabel"
          :placeholder="searchLabel"
        />
      </label>
      <label v-if="tab === 'files'" class="resource-select">
        <span>类型</span>
        <select v-model="browse.fileType" aria-label="文件类型" @change="resetCurrentPage">
          <option v-for="category in fileCategories" :key="category.id" :value="category.id">
            {{ category.label }}
          </option>
        </select>
      </label>
      <label class="resource-select">
        <span>排序</span>
        <select v-model="sort" aria-label="资源排序">
          <option value="recent">{{ tab === 'pages' ? '最近更新' : '最近上传' }}</option>
          <option value="name">名称 A–Z</option>
          <option v-if="tab !== 'pages'" value="size">大小从大到小</option>
        </select>
      </label>
      <WaoButton
        class="resource-refresh"
        variant="ghost"
        :disabled="listState === 'loading'"
        :aria-busy="listState === 'loading'"
        aria-label="刷新当前资源列表"
        @click="refreshCurrentList"
      >
        <i class="ri-refresh-line" :class="{ spin: listState === 'loading' }" aria-hidden="true"></i
        ><span class="refresh-label">刷新</span>
      </WaoButton>
    </div>
    <div class="resource-summary" aria-live="polite" aria-atomic="true">
      <span v-if="listState === 'loaded' || totalCount">
        {{ isFiltered ? `找到 ${resultCount} / ${totalCount} 项` : `共 ${totalCount} 项` }}
        <span v-if="tab !== 'pages' && capacity.known"> · {{ capacityText }}</span>
        <span v-if="listState === 'error'"> · 上次读取结果</span>
      </span>
      <WaoButton v-if="isFiltered" variant="ghost" @click="clearFilters">清除筛选</WaoButton>
    </div>

    <template v-if="tab === 'files'">
      <div v-if="fileSettingsLoaded" class="note">
        文件上限 {{ fileSettings.fileMaxMb }}MB · 分享下载需{{ fileSettings.downloadCaptchaEnabled ? '验证' : '确认' }}
      </div>
      <div v-else class="note">
        {{
          fileSettingsError
            ? '上传限制读取失败，上传时将自动校验。'
            : '正在读取上传与下载设置…'
        }}
      </div>
      <div class="file-drop" @dragover.prevent @drop.prevent="onDrop">
        <WaoEmptyState
          v-if="!data.files.length && data.fileLoadState === 'loading'"
          title="正在读取文件…"
          icon="ri-loader-4-line"
          role="status"
        />
        <WaoEmptyState
          v-else-if="data.fileLoadState === 'error'"
          title="文件读取失败"
          :description="
            data.files.length ? '以下为上次读取的文件，请重试获取最新内容。' : '请检查连接后重试。'
          "
          icon="ri-error-warning-line"
          role="alert"
        >
          <template #actions
            ><WaoButton @click="data.refreshFiles()">重新读取文件</WaoButton></template
          >
        </WaoEmptyState>
        <WaoEmptyState
          v-else-if="!data.files.length && data.fileLoadState === 'loaded'"
          title="还没有文件"
          description="把文件拖到这里，或直接选择要上传的文件。"
          icon="ri-folder-line"
        >
          <template #actions
            ><WaoButton variant="primary" :disabled="uploading > 0" @click="unifiedInput?.click()"
              >选择文件</WaoButton
            ></template
          >
        </WaoEmptyState>
        <p
          v-if="
            route?.query.file &&
            data.fileLoadState === 'loaded' &&
            !data.files.some((file) => file.id === route?.query.file)
          "
          role="status"
        >
          该文件已不在当前可访问列表中，请重新搜索。
        </p>
        <WaoEmptyState
          v-if="data.files.length && !filteredFiles.length && data.fileLoadState === 'loaded'"
          title="没有匹配的文件"
          description="换个关键词或清除类型筛选后再试。"
          icon="ri-search-line"
        >
          <template #actions><WaoButton @click="clearFilters">清除筛选</WaoButton></template>
        </WaoEmptyState>
        <div v-if="filteredFiles.length" class="file-list">
          <div
            v-for="(f, i) in visibleFiles"
            :key="f.id || i"
            class="file-row"
            :data-file-id="f.id"
            :class="{ 'search-target': route?.query.file === f.id }"
            tabindex="-1"
          >
            <div class="file-icon"><i aria-hidden="true" :class="f.icon"></i></div>
            <div class="file-main">
              <FileDisplayNameEditor :file="f" />
              <div class="file-sub" :title="fileMeta(f)">{{ fileMeta(f) }}</div>
            </div>
            <span class="file-size">{{ f.size }}</span>
            <div class="file-actions">
              <button
                v-if="f.id && isMarkdown(f.name)"
                class="copy-btn"
                title="站内预览 Markdown"
                @click="preview = { kind: 'markdown', id: f.id, name: fileDisplayName(f) }"
              >
                <i aria-hidden="true" class="ri-eye-line"></i>预览
              </button>
              <button
                class="copy-btn"
                title="复制分享链接（对方需人工点击并通过校验）"
                @click="copyText(f.id ? fileShareUrl(f.id) : absoluteUrl(f.link), '已复制分享链接')"
              >
                <i aria-hidden="true" class="ri-links-line"></i>复制链接
              </button>
              <button
                v-if="f.id"
                class="copy-btn"
                title="重新上传文件"
                @click="
                  replacement = {
                    kind: 'file',
                    id: f.id,
                    name: fileDisplayName(f),
                    url: fileShareUrl(f.id),
                  }
                "
              >
                <i aria-hidden="true" class="ri-upload-2-line"></i>重新上传
              </button>
              <a
                class="dl-btn"
                title="直接下载"
                :aria-label="`下载 ${fileDisplayName(f)}`"
                :href="absoluteUrl(f.link)"
                ><i aria-hidden="true" class="ri-download-2-line"></i
              ></a>
              <button
                class="dl-btn danger"
                title="删除"
                :aria-label="`删除文件 ${fileDisplayName(f)}`"
                @click="removeFile(f.id, fileDisplayName(f))"
              >
                <i aria-hidden="true" class="ri-delete-bin-line"></i>
              </button>
            </div>
          </div>
        </div>
        <WaoPagination
          v-if="filePageCount > 1"
          v-model:page="filePage"
          class="pagination"
          :page-count="filePageCount"
          :total="filteredFiles.length"
          aria-label="文件分页"
        />
      </div>
    </template>

    <template v-else-if="tab === 'img'">
      <div v-if="fileSettingsLoaded" class="note">
        站内上传的图片统一整理在这里，单图不超过
        {{ fileSettings.imageMaxMb }}MB。图片链接沿用原内容的访问权限。
      </div>
      <div v-else class="note">
        {{
          fileSettingsError
            ? '图床上限读取失败，上传时仍由服务端按真实设置校验。'
            : '正在读取图床设置…'
        }}
      </div>
      <div class="img-drop" @dragover.prevent @drop.prevent="onDrop">
        <WaoEmptyState
          v-if="!data.imgs.length && data.loadStates.images === 'loading'"
          title="正在读取图片…"
          icon="ri-loader-4-line"
          role="status"
        />
        <WaoEmptyState
          v-else-if="data.loadStates.images === 'error'"
          title="图片读取失败"
          :description="
            data.imgs.length
              ? '以下为上次读取的图片，请重试获取最新内容。'
              : data.loadErrors.images || '请检查连接后重试。'
          "
          icon="ri-error-warning-line"
          role="alert"
        >
          <template #actions
            ><WaoButton @click="data.refreshImages()">重新读取图片</WaoButton></template
          >
        </WaoEmptyState>
        <WaoEmptyState
          v-else-if="!data.imgs.length && data.loadStates.images === 'loaded'"
          title="还没有图片"
          description="把图片拖到这里，或选择图片上传。"
          icon="ri-image-add-line"
        >
          <template #actions
            ><WaoButton variant="primary" @click="fileInput?.click()">上传图片</WaoButton></template
          >
        </WaoEmptyState>
        <WaoEmptyState
          v-else-if="
            data.imgs.length && !filteredImages.length && data.loadStates.images === 'loaded'
          "
          title="没有匹配的图片"
          description="试试图片名称、来源或项目名称。"
          icon="ri-search-line"
        >
          <template #actions><WaoButton @click="clearFilters">清除筛选</WaoButton></template>
        </WaoEmptyState>
        <div v-if="filteredImages.length" class="img-grid">
          <div v-for="(im, i) in visibleImages" :key="im.id ?? i" class="img-card">
            <div class="img-thumb">
              <button
                class="img-open"
                :aria-label="`预览图片 ${im.name}`"
                @click="openImage(im.url)"
              >
                <img
                  v-if="!failedThumbnails.has(im.previewUrl || im.url)"
                  :src="im.previewUrl || im.url"
                  :alt="im.name"
                  loading="lazy"
                  decoding="async"
                  @error="thumbnailFailed(im.previewUrl || im.url)"
                />
                <span v-else class="thumbnail-fallback"
                  ><i class="ri-image-line" aria-hidden="true"></i
                  >缩略图未加载<br />点击查看原图</span
                >
              </button>
              <button
                v-if="im.id"
                class="img-del"
                title="从图床删除"
                :aria-label="`删除图片 ${im.name}`"
                @click.stop="removeImage(im.id, im.name)"
              >
                <i aria-hidden="true" class="ri-delete-bin-line"></i>
              </button>
            </div>
            <div class="img-info">
              <div class="img-name" :title="im.name">{{ im.name }}</div>
              <div class="img-meta">{{ im.size }} · {{ im.time }} · 来自 {{ im.from }}</div>
              <div v-if="im.projectId" class="img-meta">项目 · {{ projectName(im.projectId) }}</div>
              <button
                v-if="failedThumbnails.has(im.previewUrl || im.url)"
                class="img-copy"
                @click="retryThumbnail(im.previewUrl || im.url)"
              >
                重试缩略图
              </button>
              <button class="img-copy" @click="copyImageLink(im.url)">
                <i aria-hidden="true" class="ri-links-line"></i>复制图片链接
              </button>
            </div>
          </div>
        </div>
        <WaoPagination
          v-if="imagePageCount > 1"
          v-model:page="imagePage"
          class="pagination"
          :page-count="imagePageCount"
          :total="filteredImages.length"
          aria-label="图床分页"
        />
      </div>
    </template>

    <template v-else>
      <div class="note">
        上传单文件 HTML，自定义公网链接后缀、浏览器标题和 icon。页面脚本在隔离环境中运行，无法读取
        WAO 登录态。
      </div>
      <div class="page-source-bar" role="group" aria-label="网页托管类型">
        <span>类型</span>
        <button
          v-for="option in hostedPageSourceFilters"
          :key="option.id"
          :class="{ active: hostedPageSource === option.id }"
          :aria-pressed="hostedPageSource === option.id"
          @click="selectHostedPageSource(option.id)"
        >
          {{ option.label }}
        </button>
      </div>
      <WaoEmptyState
        v-if="!data.hostedPages.length && data.hostedPageLoadState === 'loading'"
        title="正在读取托管网页…"
        icon="ri-loader-4-line"
        role="status"
      />
      <WaoEmptyState
        v-else-if="data.hostedPageLoadState === 'error'"
        title="托管网页读取失败"
        :description="
          data.hostedPages.length
            ? '以下为上次读取的网页，请重试获取最新内容。'
            : data.hostedPageLoadError || '请检查连接后重试。'
        "
        icon="ri-error-warning-line"
        role="alert"
      >
        <template #actions
          ><WaoButton @click="data.refreshHostedPages()">重新读取网页</WaoButton></template
        >
      </WaoEmptyState>
      <WaoEmptyState
        v-else-if="!data.hostedPages.length && data.hostedPageLoadState === 'loaded'"
        title="还没有托管网页"
        description="上传单文件 HTML，获得可分享的网页链接。"
        icon="ri-global-line"
      >
        <template #actions
          ><WaoButton variant="primary" @click="openPageDialog()">托管 HTML</WaoButton></template
        >
      </WaoEmptyState>
      <WaoEmptyState
        v-else-if="!filteredHostedPages.length && data.hostedPageLoadState === 'loaded'"
        title="没有匹配的网页"
        description="换个关键词或清除来源筛选后再试。"
        icon="ri-search-line"
      >
        <template #actions><WaoButton @click="clearFilters">清除筛选</WaoButton></template>
      </WaoEmptyState>
      <div v-if="filteredHostedPages.length" class="file-list">
        <div v-for="page in visibleHostedPages" :key="page.id" class="file-row">
          <div class="file-icon"><i aria-hidden="true" class="ri-html5-line"></i></div>
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
          <div class="file-actions">
            <button class="copy-btn" @click="copyText(absoluteUrl(page.url), '已复制公网链接')">
              <i aria-hidden="true" class="ri-links-line"></i>复制链接
            </button>
            <button
              class="dl-btn"
              title="站内预览"
              :aria-label="`预览网页 ${page.title}`"
              @click="
                preview = {
                  kind: 'html',
                  name: page.title,
                  url: page.url,
                  sourceType: page.sourceType,
                }
              "
            >
              <i aria-hidden="true" class="ri-eye-line"></i>
            </button>
            <button
              class="copy-btn"
              title="重新上传 HTML"
              @click="replacePage(page)"
            >
              <i aria-hidden="true" class="ri-upload-2-line"></i>重新上传
            </button>
            <button
              class="dl-btn"
              title="修改"
              :aria-label="`修改网页 ${page.title}`"
              @click="openPageDialog(page)"
            >
              <i aria-hidden="true" class="ri-edit-line"></i>
            </button>
            <button
              class="dl-btn danger"
              title="删除"
              :aria-label="`删除网页 ${page.title}`"
              @click="removePage(page)"
            >
              <i aria-hidden="true" class="ri-delete-bin-line"></i>
            </button>
          </div>
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

    <SettingsDialogShell
      v-if="pageDialog"
      :title="editingPage ? '修改托管网页' : '托管 HTML'"
      width="640px"
      height="auto"
      appearance="compact"
      :close-disabled="pageSaving"
      @close="closePageDialog"
    >
      <form :id="pageFormId" class="page-dialog" :aria-busy="pageSaving" @submit.prevent="savePage">
        <section v-if="pageConflict" class="page-conflict" role="alert" aria-label="网页版本冲突">
          <p>网页已在另一处更新，你的输入与所选文件已保留。</p>
          <p v-if="pageReadingLatest">正在读取最新版本…</p>
          <p v-else-if="pageLatestError">{{ pageLatestError }}</p>
          <template v-else-if="pageLatest">
            <p>最新名称：{{ pageLatest.title }}<br />最新链接：/api/pages/{{ pageLatest.slug }}</p>
            <p>使用最新版本会放弃本次修改；重新发布会用下面的草稿更新网页。</p>
            <div class="page-conflict-actions">
              <WaoButton :disabled="pageSaving" @click="useLatestPage">使用最新版本</WaoButton>
              <WaoButton :disabled="pageSaving" @click="republishPageDraft">保留草稿重新发布</WaoButton>
            </div>
          </template>
          <WaoButton v-if="!pageLatest" :disabled="pageReadingLatest" @click="readLatestPage">重新读取最新版本</WaoButton>
        </section>
        <WaoField
          v-slot="field"
          label="WAO 显示名称"
          hint="列表中的名称；浏览器标题以 HTML 原文为准。"
          :error="pageTitleError"
          required
        >
          <input
            ref="pageTitleInput"
            :id="field.id"
            :aria-describedby="field.describedBy"
            :aria-invalid="field.invalid"
            :disabled="pageSaving"
            v-model="pageTitle"
            maxlength="200"
            placeholder="例如：团队周报"
            @input="pageTitleAuto = false"
        /></WaoField>
        <WaoField
          v-slot="field"
          label="链接后缀"
          hint="仅使用小写字母、数字和中划线。"
          :error="pageSlugError"
          required
        >
          <div class="slug-input">
            <span>/api/pages/</span
            ><input
              :id="field.id"
              :aria-describedby="field.describedBy"
              :aria-invalid="field.invalid"
              :disabled="pageSaving"
              v-model="pageSlug"
              maxlength="80"
              placeholder="my-page"
              @input="pageSlugAuto = false"
            /></div
        ></WaoField>
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
            <i
              aria-hidden="true"
              :class="pageHtml ? 'ri-file-check-line' : 'ri-drag-drop-line'"
            ></i>
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
          <p v-if="pageHtmlError" class="page-error" role="alert">{{ pageHtmlError }}</p>
          <WaoButton
            v-if="pageHtml || pageHtmlError"
            variant="ghost"
            :disabled="pageSaving"
            @click="clearPageHtml"
            >{{ editingPage ? '保留现有 HTML' : '清除本次选择' }}</WaoButton
          >
        </div>
        <div class="page-file-field">
          网页图标（可选）
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
            <i
              aria-hidden="true"
              :class="pageIcon ? 'ri-image-check-line' : 'ri-image-add-line'"
            ></i>
            <span>
              <b>{{ pageIcon ? pageIcon.name : '拖入图片，或点击选择' }}</b>
              <small
                >最大 1MB；不选择则{{ editingPage ? '保留现有图标' : '使用浏览器默认图标' }}</small
              >
            </span>
          </button>
          <input ref="pageIconInput" type="file" accept="image/*" hidden @change="onPageIconPick" />
          <p v-if="pageIconError" class="page-error" role="alert">{{ pageIconError }}</p>
          <WaoButton
            v-if="pageIcon || pageIconError"
            variant="ghost"
            :disabled="pageSaving"
            @click="clearPageIcon"
            >清除本次图标选择</WaoButton
          >
        </div>
        <p v-if="pageError" class="page-error" role="alert">
          {{ pageError }}。输入与所选文件已保留，请重试。
        </p>
      </form>
      <template #footer>
        <div class="dialog-actions">
          <WaoButton type="button" :disabled="pageSaving" @click="closePageDialog">取消</WaoButton>
          <WaoButton
            class="btn-primary"
            type="submit"
            :form="pageFormId"
            variant="primary"
            :disabled="pageSaving || pageConflict"
          >
            {{ pageSaving ? '发布中…' : '发布' }}
          </WaoButton>
        </div>
      </template>
    </SettingsDialogShell>

    <FileSettingsModal
      v-if="settingsOpen"
      @close="settingsOpen = false"
      @saved="onFileSettingsSaved"
    />
    <AssetReplaceModal
      v-if="replacement"
      :target="replacement"
      @close="replacement = null"
      @saved="preview = null"
    />
    <AssetPreviewModal v-if="preview" :asset="preview" @close="preview = null" />
  </div>
</template>

<style scoped>
.files {
  container: resource-library / inline-size;
  padding: var(--wao-space-2) var(--wao-space-3) var(--wao-space-4);
  width: 100%;
  min-width: 0;
  max-width: var(--wao-content-max);
  box-sizing: border-box;
  margin: 0 auto;
}
.ftop,
.seg,
.resource-toolbar,
.resource-select,
.resource-summary,
.file-actions,
.upload-result-header {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
}
.ftop,
.resource-toolbar,
.resource-summary,
.file-actions,
.upload-result-header {
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.ftitle {
  margin: 0;
  font-size: var(--wao-font-title);
  font-weight: var(--wao-weight-heading);
  line-height: var(--wao-line-heading);
}
.seg {
  gap: 2px;
  padding: 3px;
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
}
.seg-btn {
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: var(--wao-text-3);
  font: inherit;
  cursor: pointer;
  white-space: nowrap;
}
.seg-btn.on {
  border-color: var(--wao-line);
  background: var(--wao-bg-card);
  color: var(--wao-primary-text);
  font-weight: var(--wao-weight-heading);
}
.unified-drop {
  width: 100%;
  box-sizing: border-box;
  text-align: left;
  font: inherit;
  margin-top: var(--wao-space-2);
  min-height: 56px;
  padding: var(--wao-space-1) var(--wao-space-2);
  border: 1px dashed var(--wao-line-strong);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--wao-space-1);
  cursor: pointer;
}
.unified-drop:hover,
.unified-drop.dragging {
  border-color: var(--wao-primary);
  background: var(--wao-primary-tint);
}
.unified-drop > i {
  font-size: var(--wao-font-title);
  color: var(--wao-primary-text);
}
.unified-drop > .drop-copy {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px var(--wao-space-2);
}
.unified-drop b {
  font-size: var(--wao-font-body);
}
.unified-drop span {
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
}
.unified-action,
.upload-results button {
  min-height: 34px;
  padding: 6px 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font: inherit;
  cursor: pointer;
  flex: none;
}
.unified-action { display: inline-flex; align-items: center; justify-content: center; white-space: nowrap; }
.resource-toolbar {
  margin-top: var(--wao-space-2);
}
.resource-search {
  min-width: 180px;
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
}
.resource-search:focus-within {
  border-color: var(--wao-primary);
  outline: 2px solid var(--wao-primary-soft);
}
.resource-search input {
  width: 100%;
  min-width: 0;
  height: var(--wao-control-height);
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text);
  font: inherit;
}
.resource-select {
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
  white-space: nowrap;
}
.resource-select select {
  min-width: 0;
  max-width: 160px;
  height: var(--wao-control-height);
  padding: 0 var(--wao-space-1);
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  font-size: var(--wao-font-body);
}
.resource-summary {
  min-height: 34px;
  justify-content: space-between;
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
}
.resource-summary :deep(.wao-button) {
  min-height: 30px;
}
.note {
  margin: var(--wao-space-1) 0;
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
  line-height: var(--wao-line-body);
}
.file-drop,
.img-drop {
  min-height: 120px;
}
.file-list {
  margin-top: var(--wao-space-1);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  overflow: hidden;
  background: var(--wao-bg-card);
}
.file-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px var(--wao-space-2);
  border-bottom: 1px solid var(--wao-divider);
}
.file-row:last-child {
  border-bottom: 0;
}
.file-row.search-target {
  outline: 2px solid var(--wao-primary);
  outline-offset: -2px;
  background: var(--wao-primary-soft);
}
.file-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  display: grid;
  place-items: center;
  flex: none;
  font-size: var(--wao-font-section);
}
.file-main {
  flex: 1;
  min-width: 0;
}
.file-name {
  font-size: var(--wao-font-body);
  font-weight: var(--wao-weight-heading);
  color: var(--wao-text);
  overflow-wrap: anywhere;
}
.file-sub {
  margin-top: 3px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-size {
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
  white-space: nowrap;
}
.file-actions {
  flex: none;
  gap: 6px;
}
.copy-btn,
.dl-btn,
.img-copy {
  min-height: 32px;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 0 var(--wao-space-1);
  font: inherit;
  font-size: var(--wao-font-help);
  cursor: pointer;
  text-decoration: none;
  white-space: nowrap;
}
.dl-btn {
  width: 32px;
  padding: 0;
  flex: none;
}
.copy-btn:hover,
.dl-btn:hover,
.img-copy:hover {
  background: var(--wao-bg-hover);
}
.dl-btn.danger:hover {
  color: var(--wao-danger-strong);
  background: var(--wao-danger-bg);
}
.project-badge {
  color: var(--wao-primary-text);
}
.pagination {
  margin-top: var(--wao-space-2);
  padding-bottom: var(--wao-space-1);
}
.img-grid {
  margin-top: var(--wao-space-1);
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}
.img-card {
  min-width: 0;
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  overflow: hidden;
  background: var(--wao-bg-card);
  display: flex;
  flex-direction: column;
}
.img-thumb {
  position: relative;
  height: 140px;
  background: var(--wao-bg-sub);
}
.img-open {
  display: block;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--wao-text-3);
  cursor: zoom-in;
  font: inherit;
}
.img-thumb img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.thumbnail-fallback {
  display: grid;
  justify-items: center;
  gap: 4px;
  padding: var(--wao-space-1);
  font-size: var(--wao-font-help);
}
.thumbnail-fallback i {
  font-size: var(--wao-font-title);
}
.img-del {
  position: absolute;
  right: 6px;
  top: 6px;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font: inherit;
  cursor: pointer;
  opacity: 0;
}
.img-card:hover .img-del,
.img-card:focus-within .img-del {
  opacity: 1;
}
.img-del:hover {
  color: var(--wao-danger-strong);
  background: var(--wao-danger-bg);
}
.img-info {
  padding: 10px 12px;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 5px;
}
.img-name {
  color: var(--wao-text);
  font-size: var(--wao-font-body);
  font-weight: var(--wao-weight-heading);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.img-meta {
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
  overflow-wrap: anywhere;
}
.img-copy {
  width: 100%;
  margin-top: auto;
}
.page-source-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: var(--wao-space-1) 0;
}
.page-source-bar > span {
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
  padding-right: 4px;
}
.page-source-bar button {
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: var(--wao-radius-control);
  background: transparent;
  color: var(--wao-text-3);
  font: inherit;
  font-size: var(--wao-font-help);
  cursor: pointer;
}
.page-source-bar button.active {
  background: var(--wao-primary-soft);
  border-color: var(--wao-primary-line);
  color: var(--wao-primary-text);
}
.page-name {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
}
.page-source {
  display: inline-block;
  padding: 1px 6px;
  border-radius: var(--wao-radius-pill);
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
  font-weight: 400;
  white-space: nowrap;
}
.page-source.ai_run_report {
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
}
.upload-results {
  margin: var(--wao-space-2) 0;
  padding: var(--wao-space-2);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  background: var(--wao-bg-card);
  font-size: var(--wao-font-body);
}
.upload-results p {
  margin: 0 0 var(--wao-space-1);
}
.upload-results ul {
  margin: var(--wao-space-1) 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--wao-space-1);
  max-height: 280px;
  overflow: auto;
}
.upload-results li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--wao-space-1);
}
.upload-results progress {
  width: 130px;
  height: 8px;
  accent-color: var(--wao-primary);
}
.upload-results strong,
.upload-reason {
  overflow-wrap: anywhere;
}
.upload-results button:disabled {
  opacity: 0.5;
  cursor: wait;
}
.upload-reason,
.page-error {
  color: var(--wao-danger-strong);
}
.page-dialog {
  display: grid;
  gap: var(--wao-space-2);
  padding: var(--wao-space-2) var(--wao-space-3) var(--wao-space-3);
}
.page-file-field {
  display: grid;
  gap: var(--wao-space-1);
  color: var(--wao-text);
  font-size: var(--wao-font-body);
}
.page-file-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 72px;
  padding: 10px 12px;
  border: 1px dashed var(--wao-line-strong);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
  color: var(--wao-text-2);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.page-file-picker > span {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.page-file-picker > i {
  flex: none;
  font-size: var(--wao-font-title);
  color: var(--wao-primary-text);
}
.page-file-picker b {
  font-size: var(--wao-font-body);
  overflow-wrap: anywhere;
}
.page-conflict { display: grid; gap: var(--wao-space-1); padding: var(--wao-space-2); border: 1px solid var(--wao-warning-line); border-radius: var(--wao-radius-control); background: var(--wao-warning-bg); color: var(--wao-text); font-size: var(--wao-font-help); overflow-wrap: anywhere; }
.page-conflict p { margin: 0; }
.page-conflict-actions { display: flex; flex-wrap: wrap; gap: var(--wao-space-1); }
.page-file-picker:hover,
.page-file-picker.dragging {
  border-color: var(--wao-primary);
  background: var(--wao-primary-tint);
}
.page-file-picker.selected {
  border-style: solid;
  border-color: var(--wao-primary);
}
.page-file-picker:disabled {
  opacity: 0.6;
  cursor: progress;
}
.page-dialog small {
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
  font-weight: 400;
}
.page-error {
  margin: 0;
  font-size: var(--wao-font-help);
  overflow-wrap: anywhere;
}
.slug-input {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
}
.slug-input span {
  flex: none;
  color: var(--wao-text-3);
  font-family: var(--wao-font-mono);
  font-size: var(--wao-font-caption);
}
.slug-input input {
  flex: 1;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--wao-space-1);
}
button:focus-visible,
a:focus-visible,
select:focus-visible,
.unified-drop:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 2px;
}
.img-open:focus-visible {
  outline-offset: -3px;
}
@media (max-width: 1100px) {
  .img-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .file-row {
    flex-wrap: wrap;
  }
  .file-main {
    flex-basis: calc(100% - 110px);
  }
  .file-actions {
    margin-left: 48px;
  }
}
@media (max-width: 760px) {
  .files {
    padding: var(--wao-space-2) 12px var(--wao-space-3);
  }
  .ftop {
    gap: var(--wao-space-1);
  }
  .ftop .seg {
    order: 4;
    flex: 1 0 100%;
  }
  .seg-btn {
    flex: 1;
  }
  .resource-search {
    flex: 1 0 100%;
    box-sizing: border-box;
  }
  .resource-toolbar { display: grid; grid-template-columns: minmax(0, 1fr) 44px; }
  .resource-toolbar.file-tools { grid-template-columns: repeat(2, minmax(0, 1fr)) 44px; }
  .resource-search { grid-column: 1 / -1; }
  .resource-select { min-width: 0; }
  .resource-refresh { width: 44px; min-height: 44px; padding: 0; }
  .refresh-label { display: none; }
  .resource-search input,
  .resource-select select {
    font-size: var(--wao-font-reading);
  }
  .resource-select {
    flex: 1;
  }
  .resource-select select {
    flex: 1;
    max-width: 100%;
  }
  .resource-select > span {
    display: none;
  }
  .img-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--wao-space-1);
  }
  .img-thumb {
    height: 120px;
  }
  .img-info {
    padding: var(--wao-space-1);
  }
  .file-row {
    padding: 12px;
    gap: var(--wao-space-1);
  }
  .file-main {
    flex-basis: calc(100% - 48px);
  }
  .file-actions {
    flex: 1 0 100%;
    margin-left: 0;
  }
  .file-size {
    margin-left: 44px;
  }
  .unified-drop {
    align-items: center;
    padding: var(--wao-space-1);
  }
  .unified-drop > .drop-copy {
    display: grid;
    gap: 2px;
  }
  .unified-action {
    margin-left: auto;
  }
  .page-dialog {
    padding: var(--wao-space-2);
  }
  .page-dialog input {
    min-height: 44px;
  }
  .slug-input {
    flex-wrap: wrap;
  }
  .slug-input input {
    flex-basis: 100%;
  }
}
@media (max-width: 760px), (hover: none), (pointer: coarse) {
  .copy-btn,
  .dl-btn,
  .img-copy,
  .img-del,
  .seg-btn,
  .page-source-bar button,
  .resource-select select,
  .resource-search input,
  .unified-action,
  .upload-results button,
  .settings-button,
  .btn-primary {
    min-height: 44px;
  }
  .dl-btn,
  .img-del {
    min-width: 44px;
  }
  .img-del {
    opacity: 1;
  }
}
@container resource-library (max-width: 900px) {
  .file-row {
    flex-wrap: wrap;
  }
  .file-main {
    flex-basis: calc(100% - 110px);
  }
  .file-actions {
    margin-left: 48px;
  }
  .img-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@container resource-library (max-width: 600px) {
  .ftop .seg {
    order: 4;
    flex: 1 0 100%;
  }
  .seg-btn {
    flex: 1;
  }
  .resource-search {
    flex: 1 0 100%;
    box-sizing: border-box;
  }
  .resource-toolbar { display: grid; grid-template-columns: minmax(0, 1fr) 44px; }
  .resource-toolbar.file-tools { grid-template-columns: repeat(2, minmax(0, 1fr)) 44px; }
  .resource-search { grid-column: 1 / -1; }
  .resource-select { min-width: 0; }
  .resource-refresh { width: 44px; min-height: 44px; padding: 0; }
  .refresh-label { display: none; }
  .resource-select {
    flex: 1;
  }
  .resource-select select {
    flex: 1;
    max-width: 100%;
  }
  .resource-select > span {
    display: none;
  }
  .file-main {
    flex-basis: calc(100% - 48px);
  }
  .file-actions {
    flex: 1 0 100%;
    margin-left: 0;
  }
  .file-size {
    margin-left: 44px;
  }
  .img-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--wao-space-1);
  }
  .unified-drop > .drop-copy {
    display: grid;
    gap: 2px;
  }
  .unified-action {
    margin-left: auto;
  }
}
</style>
