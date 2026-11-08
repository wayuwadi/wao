<script setup lang="ts">
import { DOCUMENT_EDITOR_FEATURES } from '@/config/documentEditor'
import { useTaskViewMemory } from '@/composables/taskViewMemory'
import { useNarrowWorkspace } from '@/composables/viewport'
import { useInputComposition } from '@/composables/inputComposition'
import { usePageVisibility } from '@/composables/pageVisibility'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { loadSheetRuntime } from '@/utils/knowledgeSheetRuntime'
import KnowledgeSheet from '@/components/knowledge/KnowledgeSheet.vue'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'
import KnowledgeIconPicker from '@/components/knowledge/KnowledgeIconPicker.vue'
import KnowledgeDirectoryTree from '@/components/knowledge/KnowledgeDirectoryTree.vue'
import NoteWidthSwitch from '@/components/notes/NoteWidthSwitch.vue'
import { useNoteWritingWidth } from '@/composables/noteWritingWidth'
import { useDocumentOutline } from '@/composables/documentOutline'
import { documentMarkdown, downloadDocumentMarkdown } from '@/utils/documentExport'
import WaoButton from '@/components/ui/WaoButton.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import { useConfirmAction } from '@/composables/confirmAction'
import { directoryParents, directoryRows, groupLevelExpansion } from '@/utils/knowledgeDirectory'
import { isColoredKnowledgeIcon } from '@/utils/knowledgeIcons'
/**
 * 知识库详情：左边是文件夹 / 文档树，右边是文档本身。
 *
 * 默认打开就能写（ADR-015）；阅读模式只关闭同一编辑器的输入，不重建正文。
 * 输入停顿即自动保存，右上角显示保存状态与此刻同在这篇文档里的人（ADR-017）。
 */
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'
import { useDocPresence } from '@/composables/docPresence'
import { openEditorSelectionTask } from '@/composables/editorSelectionTask'
import { toast } from '@/composables/toast'
import { copyToClipboard } from '@/utils/clipboard'
import { docOriginalUrl } from '@/utils/share'
import NoteShareDialog from '@/components/notes/NoteShareDialog.vue'
const shareNoteId = ref('')
import { absoluteTime, relativeTime } from '@/utils/time'
import { blocksToText, textToBlocks } from '@/utils/doc'
import { resolvedRichContent, richHeadings } from '@/utils/richDoc'
import { useNow } from '@/composables/now'
import ResourceMenu from '@/components/ui/ResourceMenu.vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import {
  apiCreateDocTemplate,
  apiDeleteDocTemplate,
  apiPatchDocTemplate,
  fetchDocTemplates,
} from '@/api/board'
import type { DocLink, DocTemplate, FileItem, RichDocContent } from '@/types'
// 异步加载，理由同 TaskDrawer：ProseMirror 不进首屏包。
const KnowledgeDrawing = defineAsyncComponent(() => import('@/views/DrawingsView.vue'))
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))

const sheetEditor = ref<InstanceType<typeof KnowledgeSheet> | null>(null)
const props = withDefaults(defineProps<{ navigationHidden?: boolean }>(), { navigationHidden: false })
async function flushSheet() { await sheetEditor.value?.flush() }
onBeforeRouteLeave(flushSheet)
onBeforeRouteUpdate(flushSheet)
const route = useRoute()
const router = useRouter()
const data = useDataStore()
const session = useSessionStore()
const currentIdentity = () => JSON.stringify([session.account?.accountId, session.userId])
const contentWidth = useNoteWritingWidth()
const ui = useUiStore()
const tabs = useTabsStore()
const workspace = ref<HTMLElement | null>(null)
const isMobile = useNarrowWorkspace(workspace)
const pageVisible = usePageVisibility()
const desktopTocOpen = ref(true)
const mobileTocOpen = ref(false)
const mobileDirectoryOpen = ref(false)
const tocOpen = computed({ get: () => isMobile.value ? mobileTocOpen.value : desktopTocOpen.value, set: value => { if (isMobile.value) mobileTocOpen.value = value; else desktopTocOpen.value = value } })
const navigationComposition = useInputComposition()
const directoryTrigger = ref<HTMLButtonElement | null>(null)
const directoryPanel = ref<HTMLElement | null>(null)
const outlinePanel = ref<HTMLElement | null>(null)
const documentPane = ref<HTMLElement | null>(null)
const outlineFloating = ref(false)
const outlineManual = ref(false)
const outlineVisible = computed(() => tocOpen.value && (isMobile.value || !outlineFloating.value || outlineManual.value) && !focused.value)
let paneObserver: ResizeObserver | undefined
watch(documentPane, element => {
  paneObserver?.disconnect()
  if (!element) return
  const update = () => {
    if (element.clientWidth) outlineFloating.value = element.clientWidth <= 1000
    fitTitle()
  }
  update()
  if (typeof ResizeObserver !== 'undefined') { paneObserver = new ResizeObserver(update); paneObserver.observe(element) }
}, { flush: 'post' })
onBeforeUnmount(() => paneObserver?.disconnect())
const { confirmation, confirmAction, dismissConfirmation } = useConfirmAction()
const emit = defineEmits<{ 'focus-change': [focused: boolean] }>()
const reading = ref(false)
const focused = ref(false)
const shortcutsOpen = ref(false)
const characterCount = ref(0)
const documentEditor = ref<InstanceType<typeof MarkdownEditor> | null>(null)
const modeTrigger = ref<HTMLButtonElement | null>(null)
const focusTrigger = ref<HTMLButtonElement | null>(null)
watch(focused, value => emit('focus-change', value), { flush: 'sync' })
function toggleReading() {
  if (!reading.value && documentEditor.value?.hasPendingUploads?.()) {
    toast('图片或附件仍在上传，完成后再切换阅读', 'ri-upload-cloud-line')
    return
  }
  documentEditor.value?.dismissTransientUi?.()
  flushCurrentDoc()
  reading.value = !reading.value
}
function toggleFocus() { focused.value = !focused.value }
const documentShortcuts = [
  ['Ctrl / ⌘ + S', '立即保存当前文档'],
  ['Ctrl / ⌘ + F', '查找文档内容（正文内）'],
  ['Ctrl / ⌘ + K', '插入或编辑链接'],
  ['Ctrl / ⌘ + Z', '撤销编辑'],
  ['/', '在空白段落插入内容块'],
  ['Alt + Shift + F', '进入或退出专注'],
  ['Esc', '关闭当前浮层或退出专注'],
]
function onWorkspaceKeydown(event: KeyboardEvent) {
  if (event.defaultPrevented || navigationComposition.active(event)) return
  if (event.key === 'Escape' && mobileDirectoryOpen.value) { event.preventDefault(); closeMobileDirectory(); return }
  if (event.altKey && event.shiftKey && event.key.toLowerCase() === 'f' && pageType.value === 'document' && doc.value) {
    event.preventDefault(); toggleFocus()
  } else if (event.key === 'Escape' && focused.value && !shareMenu.value && !moreMenuOpen.value && !shortcutsOpen.value
    && !(event.target instanceof Element && event.target.closest('[role="dialog"], [role="menu"], [role="listbox"], .find-panel'))) {
    focused.value = false
    event.preventDefault()
    nextTick(() => focusTrigger.value?.focus())
  }
}
async function copyMarkdown() {
  if (!doc.value) return
  moreMenuOpen.value = false
  const copied = await copyToClipboard(documentMarkdown(doc.value.title, body.value))
  toast(copied ? '已复制当前文档的 Markdown' : '复制失败，请重试或导出 Markdown', copied ? 'ri-check-line' : 'ri-error-warning-line')
}
function exportMarkdown() {
  if (!doc.value) return
  moreMenuOpen.value = false
  try { downloadDocumentMarkdown(doc.value.title, body.value) }
  catch { toast('导出失败，请重试；文档内容未改变', 'ri-error-warning-line') }
}

const kbId = computed(() => String(route.params.id || route.query.kb || ''))
let workspaceGeneration = 0
const directoryWidth = ref(262)
const paneMemory = useTaskViewMemory('knowledge-directory', () => ({ collapsed: false }))
const directoryCollapsed = computed({ get: () => isMobile.value ? !mobileDirectoryOpen.value : paneMemory.value.collapsed, set: value => { if (isMobile.value) mobileDirectoryOpen.value = !value; else paneMemory.value.collapsed = value } })
const directoryVisible = computed(() => !focused.value && !directoryCollapsed.value)
function closeMobileDirectory() { closeDirectory() }
const kb = computed(() => data.kbById(kbId.value))
watch(kb, library => { if (!library) { focused.value = false; reading.value = false } })

const docId = ref<string | null>((route.query.doc as string) || null)
let documentNavigationRequest = 0
// 桌面按篇展开；手机优先阅读正文，不把临时面板选择写入桌面偏好。
watch(docId, () => { mobileTocOpen.value = false; if (!isMobile.value) desktopTocOpen.value = true; outlineManual.value = false; focused.value = false; characterCount.value = 0; closeDirectory() })
watch(isMobile, () => { mobileTocOpen.value = false; mobileDirectoryOpen.value = false })
watch([() => session.account?.accountId, () => session.userId, pageVisible], () => { mobileTocOpen.value = false; mobileDirectoryOpen.value = false })
watch(() => props.navigationHidden, hidden => { if (hidden) { mobileTocOpen.value = false; mobileDirectoryOpen.value = false } })
watch(kbId, () => { mobileTocOpen.value = false; closeDirectory() })
watch(() => session.userId, () => { focused.value = false; reading.value = false })
const folderOpen = reactive<Record<string, boolean>>({})
const scrollEl = ref<HTMLElement | null>(null)
const toolbarHost = ref<HTMLElement | null>(null)
const outlineTrigger = ref<HTMLButtonElement | null>(null)
function closeOutline() {
  tocOpen.value = false
  void nextTick(() => outlineTrigger.value?.focus({ preventScroll: true }))
}
function closeDirectory() {
  if (!mobileDirectoryOpen.value) return
  mobileDirectoryOpen.value = false
  void nextTick(() => directoryTrigger.value?.focus({ preventScroll: true }))
}
async function toggleDirectory() {
  if (isMobile.value && mobileDirectoryOpen.value) { closeDirectory(); return }
  directoryCollapsed.value = !directoryCollapsed.value
  if (!isMobile.value) return
  mobileTocOpen.value = false
  await nextTick()
  directoryPanel.value?.querySelector<HTMLButtonElement>('.mobile-directory-close')?.focus({ preventScroll: true })
}
async function toggleOutline() {
  if (outlineVisible.value) { closeOutline(); return }
  outlineManual.value = true
  tocOpen.value = true
  if (!isMobile.value) return
  mobileDirectoryOpen.value = false
  await nextTick()
  outlinePanel.value?.querySelector<HTMLButtonElement>('button')?.focus({ preventScroll: true })
}
function navigationKeydown(event: KeyboardEvent, pane: 'directory' | 'outline') {
  if (event.defaultPrevented || navigationComposition.active(event)) return
  if (event.key === 'Escape' && (isMobile.value || pane === 'outline')) {
    event.preventDefault(); event.stopPropagation()
    if (pane === 'outline') closeOutline(); else closeDirectory()
    return
  }
  if (!isMobile.value || event.key !== 'Tab') return
  const root = pane === 'directory' ? directoryPanel.value : outlinePanel.value
  const controls = [...(root?.querySelectorAll<HTMLElement>('button, input, select, textarea, [tabindex]') ?? [])].filter(element => element.tabIndex >= 0 && !element.matches(':disabled') && !element.closest('[hidden], [inert]') && getComputedStyle(element).display !== 'none')
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
const titleEl = ref<HTMLTextAreaElement | null>(null)
function fitTitle() {
  const input = titleEl.value
  if (!input) return
  input.style.height = 'auto'
  input.style.height = `${Math.max(input.scrollHeight, 42)}px`
}
function onTitleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.isComposing) return
  event.preventDefault()
  scrollEl.value?.querySelector<HTMLElement>('.doc-body .tiptap[contenteditable="true"]')?.focus()
}
watch([titleEl, () => route.query.findRequest, () => route.query.find, () => route.query.match, () => route.query.doc], async () => {
  if (route.query.match !== 'title' || typeof route.query.find !== 'string') return
  await nextTick()
  const input = titleEl.value, term = route.query.find
  if (!input || !term) return
  const start = input.value.toLowerCase().indexOf(term.toLowerCase())
  input.scrollIntoView?.({ block: 'center' })
  input.focus()
  if (start >= 0) input.setSelectionRange(start, start + term.length)
})

/** 正在重命名的文件夹 id。 */
const renamingFolder = ref<string | null>(null)
const folderNameDraft = ref('')
const folderRenameSaving = ref(false)
const folderRenameError = ref('')
function folderNameKeydown(event: KeyboardEvent, id: string) {
  if (navigationComposition.active(event)) return
  if (event.key === 'Enter') { event.preventDefault(); void commitFolderName(id) }
  else if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); renamingFolder.value = null; folderRenameError.value = '' }
}
function startFolderRename(id: string) {
  folderNameDraft.value = kb.value?.folders.find(folder => folder.id === id)?.name ?? ''
  folderRenameError.value = ''
  renamingFolder.value = id
  nextTick(() => treeElement.value?.querySelector<HTMLInputElement>('.folder-input')?.select())
}
const archivedOpen = ref(false)
const archivedLoading = ref(false)
const archivedError = ref(false)
const { peers, setCaretLine, markTyping } = useDocPresence(docId)
/** 相对时间文案要自己走起来，否则「刚刚」会一直停在打开那一刻。 */
const now = useNow()

watch(
  () => [kbId.value, route.query.doc],
  () => {
    documentNavigationRequest++
    flushCurrentDoc()
    docId.value = typeof route.query.doc === 'string' ? route.query.doc : null
  },
)


const doc = computed(() => (docId.value && data.docById(docId.value)
  && data.docById(docId.value)?.bodyLoaded !== false ? data.docOf(docId.value) : null))
const base = computed(() => doc.value?.base ?? null)
const pageType = computed(() => base.value?.pageType ?? 'document')
watch([titleEl, () => doc.value?.title, contentWidth, directoryCollapsed, focused], async () => { await nextTick(); fitTitle() }, { flush: 'post' })
function pageIcon(id: string) { return ({ spreadsheet: 'ri-table-line', drawing: 'ri-artboard-line', document: 'ri-file-text-line' })[data.docById(id)?.pageType ?? 'document'] }
const editingPrivateNote = computed(() => kbId.value === 'kb-notes' || base.value?.kbId === 'kb-notes')
// 同一知识库仍复用一个页签，标题和地址指向当前文档，避免与“小记”模块同名。
watch([() => data.docs[docId.value ?? '']?.title, () => kb.value?.name, docId], () => {
  if (!kb.value) return
  const title = data.docs[docId.value ?? '']?.title
  tabs.setMeta(`kb:${kbId.value}`, { label: docId.value ? title || '未命名文档' : kb.value.name, icon: kb.value.icon })
}, { immediate: true })
const archivedDocs = computed(() => data.archivedDocsOf(kbId.value))
const currentArchived = computed(() => Boolean(docId.value && data.isDocArchived(docId.value)))

let archiveRequest = 0
let archiveLoadingKb = ''
let archiveLoaded = false
async function loadArchivedDocs() {
  const targetKb = kbId.value
  if (!data.apiLive || (archivedLoading.value && archiveLoadingKb === targetKb)) return
  const request = ++archiveRequest
  archiveLoadingKb = targetKb
  archivedLoading.value = true
  archivedError.value = false
  try {
    const loaded = await data.refreshArchivedDocs(targetKb)
    if (request !== archiveRequest || kbId.value !== targetKb) return
    archivedError.value = !loaded
    archiveLoaded = loaded
    if (docId.value && data.isDocArchived(docId.value)) archivedOpen.value = true
  } catch {
    if (request === archiveRequest && kbId.value === targetKb) archivedError.value = true
  } finally {
    if (request === archiveRequest) archivedLoading.value = false
  }
}
watch([kbId, currentIdentity], () => {
  archiveRequest++
  archiveLoaded = false
  archivedOpen.value = false; archivedLoading.value = false; archivedError.value = false
}, { flush: 'sync' })
watch([() => data.apiLive, kbId, docId], ([live]) => {
  // Most visits use the active directory. Fetch archives only on demand or for an archive deep link.
  if (live && docId.value && !kb.value?.folders.some(folder => folder.docs.includes(docId.value!))) void loadArchivedDocs()
}, { immediate: true })
watch(archivedOpen, open => { if (!open) archiveLoaded = false; else if (!archiveLoaded) void loadArchivedDocs() })
const author = computed(() => {
  if (base.value?.systemGenerated) return { name: '系统', initial: '系', color: '#7A5CC6' }
  const a = base.value?.author ?? ''
  if (a === 'auto') return { name: '自动化', initial: '自', color: '#B9821F' }
  return session.memberById(a)
})
/** 正文：改一个字就走去抖写透，不需要「完成」按钮。 */
const body = computed({
  get: () => doc.value?.text ?? '',
  set: (v: string) => {
    if (!docId.value) return
    data.setDocEdit(docId.value, { text: v })
    markTyping()
  },
})
const bodyContent = computed(() => doc.value?.content ?? null)
function onContentChange(content: RichDocContent) {
  if (!docId.value) return
  data.setDocEdit(docId.value, { content })
  markTyping()
}
const currentSaveState = computed(() => docId.value ? data.saveState(`doc:${docId.value}`) : null)
const saving = computed(() => currentSaveState.value?.saving ?? false)
const saveFailed = computed(() => currentSaveState.value?.failed ?? false)
/** 上一次写透成功的时刻，给「自动保存成功 14:32」用。 */
const docSavedAt = computed(() =>
  docId.value ? data.saveState(`doc:${docId.value}`).savedAt : null,
)
const createdLabel = computed(() =>
  base.value?.created ? relativeTime(base.value.created, now.value) : '—',
)
const updatedLabel = computed(() =>
  base.value?.updated ? relativeTime(base.value.updated, now.value) : '—',
)

const TYPE_ICON: Record<string, string> = {
  task: 'ri-checkbox-circle-line',
  doc: 'ri-file-text-line',
}
function linkName(l: DocLink) {
  if (l.type === 'task') return '任务 · ' + (data.taskById(l.id)?.title ?? '')
  return '文档 · ' + (data.docOf(l.id).title ?? '')
}
function goLink(l: DocLink) {
  if (l.type === 'task') ui.openTask(l.id)
  else openDoc(l.id)
}
const docLinks = computed(() =>
  (base.value?.links ?? []).filter(l => l.type !== 'project').map((l) => ({ icon: TYPE_ICON[l.type]!, label: linkName(l), link: l })),
)
/**
 * 任务把文档 id 保存在自己的 docs 中，因此这里按当前成员可见任务动态反查。
 * 不向文档再写一份任务关系，避免一端解除关联后另一端留下过期入口。
 */
const linkedTasks = computed(() => {
  if (!docId.value) return []
  return data.tasks
    .filter((task) => task.docs.includes(docId.value!))
    .map((task) => ({
      id: task.id,
      title: task.title || '未命名任务',
    }))
})
function openLinkedTask(id: string) {
  ui.openTask(id)
}
const backlinks = computed(() =>
  (base.value?.backlinks ?? []).map((b) => ({
    icon: TYPE_ICON[b.type]!,
    label: linkName(b),
    snippet: b.snippet,
    link: b,
  })),
)

/** 目录展示一至三级标题；旧文档标题没有层级时按二级处理。 */
const storedHeadings = computed(() => doc.value ? richHeadings(doc.value.content) : [])
const { activeIndex: activeToc, progress: readingProgress, renderedHeadings, onScroll: onDocScroll, goTo: goToHeading, toTop: backToTop } = useDocumentOutline(scrollEl, () => [docId.value, storedHeadings.value])
// Once mounted, use the actual editor headings: setext, quotes and nested blocks share one order with scrolling.
function tocGo(index: number) { goToHeading(index); if (isMobile.value) closeOutline() }
const toc = computed(() => renderedHeadings.value ?? storedHeadings.value)
const parentMap = computed(() => kb.value ? directoryParents(kb.value) : {})
const docBreadcrumb = computed(() => {
  const path: { key: string; title: string; id: string; document: boolean }[] = []
  const visited = new Set<string>()
  let key = parentMap.value[`d:${docId.value}`]
  while (key && !visited.has(key)) {
    visited.add(key)
    const id = key.slice(2), document = key.startsWith('d:')
    path.unshift({ key, id, document, title: document ? documentTitle(id) : kb.value?.folders.find(folder => folder.id === id)?.name ?? '' })
    key = parentMap.value[key]
  }
  return path
})
async function goBreadcrumb(item: { key: string; id: string; document: boolean }) {
  if (item.document) { await selectDoc(item.id); return }
  focused.value = false
  if (!isMobile.value) directoryCollapsed.value = false
  mobileDirectoryOpen.value = isMobile.value; treeQuery.value = ''; folderOpen[item.key] = true
  await nextTick()
  const target = Array.from(treeElement.value?.querySelectorAll<HTMLButtonElement>('[data-node]') ?? []).find(node => node.dataset.node === item.key)
  target?.scrollIntoView?.({ block: 'nearest' }); target?.focus()
}

const folderStates = computed(() => ({ ...(kb.value && !kb.value.notes ? groupLevelExpansion(kb.value) : {}), ...folderOpen }))
function isOpen(fid: string) {
  return folderStates.value[fid] !== false
}
function toggleFolder(fid: string) { folderOpen[fid] = !isOpen(fid) }
const allFoldersOpen = computed(() => !!kb.value?.folders.length && Object.keys(directoryParents(kb.value)).every(key => isOpen(key)))
function resetFolderExpansion() {
  for (const id of Object.keys(folderOpen)) delete folderOpen[id]
}
function toggleAllFolders() {
  const expand = !allFoldersOpen.value
  for (const key of Object.keys(kb.value ? directoryParents(kb.value) : {})) folderOpen[key] = expand
}
// 切换知识库时使用该库的分组级默认值；同一页面保留用户的展开选择。
watch(kbId, resetFolderExpansion, { flush: 'sync' })
watch(
  [docId, kb],
  ([id, library]) => {
    if (!id || !library) return
    const parents = directoryParents(library)
    let ancestor = parents[`d:${id}`]
    const visited = new Set<string>()
    while (ancestor && !visited.has(ancestor)) {
      visited.add(ancestor)
      folderOpen[ancestor] = true
      ancestor = parents[ancestor]
    }
  },
  { immediate: true },
)

async function loadSelectedDoc() {
  const id = docId.value
  if (id && await data.ensureDocLoaded(id) && docId.value === id)
    await data.restoreDocumentDraft(id)
}
watch([docId, () => data.loadStates.docs,
  () => docId.value ? data.docById(docId.value)?.bodyLoaded : undefined,
  () => docId.value ? data.docById(docId.value)?.detailLoaded : undefined], ([id, state]) => {
  if (id && state === 'loaded') void loadSelectedDoc()
}, { immediate: true })

function flushCurrentDoc() {
  if (docId.value && data.docPending(docId.value)) void data.persistDocNow(docId.value)
}
function onVisibilityChange() {
  if (document.visibilityState === 'hidden') flushCurrentDoc()
}
onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('pagehide', flushCurrentDoc)
})
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('pagehide', flushCurrentDoc)
  flushCurrentDoc()
  emit('focus-change', false)
})
function preloadSheet(id: string) {
  if (data.docById(id)?.pageType === 'spreadsheet') void loadSheetRuntime().catch(() => {})
}
async function selectDoc(id: string) {
  closeDirectory()
  const request = ++documentNavigationRequest, generation = workspaceGeneration
  if (id === docId.value) return
  await flushSheet()
  if (generation !== workspaceGeneration || request !== documentNavigationRequest) return
  flushCurrentDoc()
  docId.value = id
  void router.replace({ query: { ...route.query, doc: id } })
}
let preparingDocumentTask = false
async function addSelectionToTask(text: string) {
  if (!text.trim() || !docId.value || preparingDocumentTask) return
  preparingDocumentTask = true
  try {
    const id = docId.value
    const sourceTitle = doc.value?.title || '未命名文档'
    const sourceUrl = `/kb/${encodeURIComponent(kbId.value)}?doc=${encodeURIComponent(id)}`
    await openEditorSelectionTask(text, { id, title: sourceTitle, url: sourceUrl, docId: id })
  } catch {
    toast('暂时无法准备待办，请重试；文档内容未改变', 'ri-error-warning-line')
  } finally {
    preparingDocumentTask = false
  }
}
function openDoc(id: string) {
  const k = data.kbs.find((kk) => kk.folders.some((f) => f.docs.includes(id)))
  if (k && k.id !== kbId.value) router.push({ path: '/knowledge', query: { kb: k.id, doc: id } })
  else selectDoc(id)
}
const newDocFolderId = ref<string | null>(null)
const creatingDoc = ref(false)
const templatePickerOpen = ref(false)
async function createDocFrom(templateId?: string, type: 'document' | 'spreadsheet' | 'drawing' = 'document') {
  const folderId = newDocFolderId.value
  if (!folderId || creatingDoc.value) return
  const targetKb = kbId.value, generation = workspaceGeneration, parent = newDocParent.value
  creatingDoc.value = true
  const id = await data.addDoc(targetKb, folderId, templateId, type)
  if (generation !== workspaceGeneration) return
  creatingDoc.value = false
  if (id) {
    newDocFolderId.value = null
    folderOpen[`f:${folderId}`] = true
    if (parent) { await moveNode(parent, `d:${id}`); newDocParent.value = null }
    if (generation !== workspaceGeneration) return
    selectDoc(id)
    toast(type === 'drawing' ? '已创建画板，可在草图中打开' : type === 'spreadsheet' ? '已创建表格' : templateId ? '已从模板创建文档' : '已创建空白文档', 'ri-file-add-line')
    // 新文档标题是空的，光标直接落在标题上，敲进去就是自己的标题
    nextTick(() => { if (docId.value === id) titleEl.value?.focus() })
  } else {
    toast('文档没有保存到服务器，请重试', 'ri-error-warning-line')
  }
}

// ---- 文档模板 ----

const templates = ref<DocTemplate[]>([])
const templateLoading = ref(false)
const templateError = ref('')
const templateManagerOpen = ref(false)
const saveTemplateOpen = ref(false)
const savingTemplate = ref(false)
const saveTemplateError = ref('')
let templateGeneration = 0
let templateRequest = 0
let templateSaveRequest = 0
watch([docId, kbId, currentIdentity], () => {
  templateSaveRequest++
  saveTemplateOpen.value = false; savingTemplate.value = false; saveTemplateError.value = ''
}, { flush: 'sync' })
function invalidateTemplateListRequest() { templateRequest++; templateLoading.value = false }
const templateForm = reactive({ name: '', description: '' })
const editingTemplateId = ref<string | null>(null)
const editingTemplateSaving = ref(false)
const editTemplateForm = reactive({ name: '', description: '', content: '' })
const editTemplateRichContent = ref<RichDocContent | null>(null)
type TemplateEditState = 'saved' | 'dirty' | 'saving' | 'error' | 'conflict'
interface StoredTemplateDraft {
  name: string
  description: string
  content: string
  richContent?: RichDocContent
  templateUpdatedAt: string
  savedAt: string
}
const templateEditState = ref<TemplateEditState>('saved')
const templateDraftRestored = ref(false)
const templateDraftStorageError = ref(false)
const editingTemplateExpectedUpdatedAt = ref('')
const templateConflictRefreshing = ref(false)
let hydratingTemplateEdit = false

const templateEditStatusText = computed(() => {
  if (templateEditState.value === 'conflict') {
    return '服务器已有其他窗口保存的新版本；本机草稿仍在，请选择处理方式'
  }
  if (templateDraftStorageError.value) return '本机草稿暂时无法保存，离开前请先保存到服务器'
  if (templateEditState.value === 'saving') return '正在保存到服务器…'
  if (templateEditState.value === 'error') return '保存失败，本机草稿仍在，可直接重试'
  if (templateEditState.value === 'dirty') {
    return templateDraftRestored.value ? '已恢复本机草稿，尚未同步' : '本机草稿已保存，尚未同步'
  }
  return '已保存到服务器'
})

function templateDraftKey(templateId: string) {
  const memberId = session.userId
  if (!memberId) return null
  return `wao-doc-template-draft-v1:${encodeURIComponent(memberId)}:${encodeURIComponent(templateId)}`
}

function sameAsTemplate(template: DocTemplate) {
  return editTemplateForm.name === template.name
    && editTemplateForm.description === template.description
    && JSON.stringify(editTemplateRichContent.value ?? { markdown: editTemplateForm.content })
      === JSON.stringify(template.content ?? { markdown: blocksToText(template.blocks) })
}

function removeTemplateDraft(templateId: string) {
  const key = templateDraftKey(templateId)
  if (!key) return
  try {
    localStorage.removeItem(key)
  } catch {
    // 服务端保存结果不应被浏览器存储权限错误反转成失败；状态区会提示后续编辑无法落本机。
  }
}

function readTemplateDraft(template: DocTemplate): StoredTemplateDraft | null {
  const key = templateDraftKey(template.id)
  if (!key) return null
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const draft = JSON.parse(raw) as Partial<StoredTemplateDraft>
    if (typeof draft.name !== 'string'
      || typeof draft.description !== 'string'
      || typeof draft.content !== 'string') {
      removeTemplateDraft(template.id)
      return null
    }
    if (draft.name === template.name
      && draft.description === template.description
      && draft.content === blocksToText(template.blocks)) {
      removeTemplateDraft(template.id)
      return null
    }
    return {
      name: draft.name,
      description: draft.description,
      content: draft.content,
      richContent: draft.richContent,
      templateUpdatedAt: typeof draft.templateUpdatedAt === 'string' ? draft.templateUpdatedAt : '',
      savedAt: typeof draft.savedAt === 'string' ? draft.savedAt : '',
    }
  } catch {
    removeTemplateDraft(template.id)
    return null
  }
}

function persistTemplateDraft(template?: DocTemplate) {
  const id = editingTemplateId.value
  const current = template ?? templates.value.find((item) => item.id === id)
  if (!id || !current || hydratingTemplateEdit || editingTemplateSaving.value) return
  if (sameAsTemplate(current)) {
    removeTemplateDraft(id)
    templateEditState.value = 'saved'
    templateDraftRestored.value = false
    templateDraftStorageError.value = false
    return
  }
  const key = templateDraftKey(id)
  if (!key) {
    if (templateEditState.value !== 'conflict') templateEditState.value = 'dirty'
    templateDraftStorageError.value = true
    return
  }
  try {
    localStorage.setItem(key, JSON.stringify({
      name: editTemplateForm.name,
      description: editTemplateForm.description,
      content: editTemplateForm.content,
      richContent: editTemplateRichContent.value ?? undefined,
      templateUpdatedAt: editingTemplateExpectedUpdatedAt.value || current.updatedAt,
      savedAt: new Date().toISOString(),
    } satisfies StoredTemplateDraft))
    if (templateEditState.value !== 'conflict') templateEditState.value = 'dirty'
    templateDraftStorageError.value = false
  } catch {
    if (templateEditState.value !== 'conflict') templateEditState.value = 'dirty'
    templateDraftStorageError.value = true
  }
}

watch(
  [
    () => editTemplateForm.name,
    () => editTemplateForm.description,
    () => editTemplateForm.content,
    () => editTemplateRichContent.value,
  ],
  () => persistTemplateDraft(),
  { flush: 'sync' },
)

async function loadTemplates() {
  if (!data.apiLive || templateLoading.value) return
  const generation = templateGeneration, request = ++templateRequest
  templateLoading.value = true
  templateError.value = ''
  try {
    const result = await fetchDocTemplates()
    if (generation === templateGeneration && request === templateRequest) templates.value = result
  } catch {
    if (generation === templateGeneration && request === templateRequest) templateError.value = '模板加载失败，请重试'
  } finally {
    if (generation === templateGeneration && request === templateRequest) templateLoading.value = false
  }
}
watch([() => data.apiLive, templatePickerOpen, templateManagerOpen], ([live, picker, manager]) => {
  if (live && (picker || manager)) void loadTemplates()
}, { immediate: true })
watch(currentIdentity, () => {
  templateGeneration++; templateRequest++
  hydratingTemplateEdit = true
  editingTemplateId.value = null
  templates.value = []
  templateLoading.value = false; templateError.value = ''; saveTemplateError.value = ''
  templateManagerOpen.value = false; templatePickerOpen.value = false; saveTemplateOpen.value = false
  savingTemplate.value = false; editingTemplateSaving.value = false; templateConflictRefreshing.value = false
  hydratingTemplateEdit = false
}, { flush: 'sync' })
onBeforeUnmount(() => { templateGeneration++; templateRequest++ })
function openSaveTemplate() {
  templateForm.name = `${doc.value?.title || '未命名文档'}模板`
  templateForm.description = ''
  saveTemplateError.value = ''
  saveTemplateOpen.value = true
}
async function saveAsTemplate() {
  if (!docId.value || !templateForm.name.trim() || savingTemplate.value) return
  const generation = templateGeneration
  const request = ++templateSaveRequest
  savingTemplate.value = true
  saveTemplateError.value = ''
  try {
    const created = await apiCreateDocTemplate({
      name: templateForm.name.trim(),
      description: templateForm.description.trim(),
      sourceDocId: docId.value,
      blocks: doc.value?.blocks ?? [],
      content: doc.value?.content,
    })
    if (generation !== templateGeneration) return
    invalidateTemplateListRequest()
    templates.value.push(created)
    if (request === templateSaveRequest) saveTemplateOpen.value = false
    toast('已另存为模板，可在新建文档时直接选择', 'ri-file-copy-2-line')
  } catch {
    if (generation === templateGeneration && request === templateSaveRequest) saveTemplateError.value = '模板没有保存成功，名称和说明已保留，请重试。'
  } finally {
    if (generation === templateGeneration && request === templateSaveRequest) savingTemplate.value = false
  }
}
function mayLeaveTemplateEdit() {
  if (!editingTemplateId.value) return true
  if (editingTemplateSaving.value) return false
  persistTemplateDraft()
  if (!templateDraftStorageError.value || templateEditState.value === 'saved') return true
  return window.confirm('本机草稿无法保存，离开编辑会丢失未保存内容。仍要离开吗？')
}
function closeTemplateManager() {
  if (!mayLeaveTemplateEdit()) return
  editingTemplateId.value = null
  templateManagerOpen.value = false
}
function finishTemplateEdit() {
  if (!mayLeaveTemplateEdit()) return
  editingTemplateId.value = null
}
function beginEditTemplate(template: DocTemplate) {
  if (editingTemplateId.value === template.id) return
  if (editingTemplateId.value && !mayLeaveTemplateEdit()) return
  const draft = readTemplateDraft(template)
  hydratingTemplateEdit = true
  editingTemplateId.value = null
  editTemplateForm.name = draft?.name ?? template.name
  editTemplateForm.description = draft?.description ?? template.description
  editTemplateForm.content = draft?.content ?? blocksToText(template.blocks)
  editTemplateRichContent.value = draft?.richContent ?? template.content ?? null
  editingTemplateExpectedUpdatedAt.value = draft?.templateUpdatedAt || template.updatedAt
  editingTemplateId.value = template.id
  templateEditState.value = draft?.templateUpdatedAt && draft.templateUpdatedAt !== template.updatedAt
    ? 'conflict'
    : draft ? 'dirty' : 'saved'
  templateDraftRestored.value = draft !== null
  templateDraftStorageError.value = false
  hydratingTemplateEdit = false
}
function onTemplateContentChange(value: RichDocContent) {
  editTemplateRichContent.value = value
  editTemplateForm.content = value.markdown
}
async function saveTemplateMeta(template: DocTemplate) {
  if (!editTemplateForm.name.trim()
    || editingTemplateSaving.value
    || templateEditState.value === 'conflict') return
  persistTemplateDraft(template)
  const generation = templateGeneration
  editingTemplateSaving.value = true
  templateEditState.value = 'saving'
  try {
    const updated = await apiPatchDocTemplate(template.id, {
      name: editTemplateForm.name.trim(),
      description: editTemplateForm.description.trim(),
      blocks: textToBlocks(editTemplateForm.content),
      ...(editTemplateRichContent.value ? { content: editTemplateRichContent.value } : {}),
      expectedUpdatedAt: editingTemplateExpectedUpdatedAt.value,
    })
    if (generation !== templateGeneration) return
    invalidateTemplateListRequest()
    Object.assign(template, updated)
    const listed = templates.value.find(item => item.id === template.id)
    if (listed && listed !== template) Object.assign(listed, updated)
    hydratingTemplateEdit = true
    editTemplateForm.name = updated.name
    editTemplateForm.description = updated.description
    editTemplateForm.content = blocksToText(updated.blocks)
    editTemplateRichContent.value = updated.content ?? resolvedRichContent({ blocks: updated.blocks })
    editingTemplateExpectedUpdatedAt.value = updated.updatedAt
    hydratingTemplateEdit = false
    removeTemplateDraft(template.id)
    templateEditState.value = 'saved'
    templateDraftRestored.value = false
    templateDraftStorageError.value = false
    toast('模板名称、说明与正文已保存', 'ri-check-line')
  } catch (cause) {
    if (generation !== templateGeneration) return
    editingTemplateSaving.value = false
    persistTemplateDraft(template)
    const conflict = cause instanceof Error && cause.message.includes('模板已被其他窗口更新')
    templateEditState.value = conflict ? 'conflict' : 'error'
    toast(conflict ? '服务器模板已有新版本，本机草稿已保留' : '模板修改失败，请重试', 'ri-error-warning-line')
  } finally {
    if (generation === templateGeneration) editingTemplateSaving.value = false
  }
}
async function latestTemplateForConflict(templateId: string) {
  if (templateConflictRefreshing.value) return null
  const generation = templateGeneration
  templateConflictRefreshing.value = true
  try {
    const latest = await fetchDocTemplates()
    if (generation !== templateGeneration) return null
    templates.value = latest
    return latest.find((item) => item.id === templateId) ?? null
  } catch {
    if (generation === templateGeneration) toast('服务器最新版没有加载成功，请重试', 'ri-error-warning-line')
    return null
  } finally {
    if (generation === templateGeneration) templateConflictRefreshing.value = false
  }
}
async function discardConflictedTemplateDraft(templateId: string) {
  const latest = await latestTemplateForConflict(templateId)
  if (!latest) return
  hydratingTemplateEdit = true
  editTemplateForm.name = latest.name
  editTemplateForm.description = latest.description
  editTemplateForm.content = blocksToText(latest.blocks)
  editTemplateRichContent.value = latest.content ?? resolvedRichContent({ blocks: latest.blocks })
  editingTemplateExpectedUpdatedAt.value = latest.updatedAt
  hydratingTemplateEdit = false
  removeTemplateDraft(templateId)
  templateDraftRestored.value = false
  templateDraftStorageError.value = false
  templateEditState.value = 'saved'
  toast('已载入服务器最新版', 'ri-refresh-line')
}
async function confirmConflictedTemplateOverwrite(templateId: string) {
  const latest = await latestTemplateForConflict(templateId)
  if (!latest) return
  editingTemplateExpectedUpdatedAt.value = latest.updatedAt
  templateEditState.value = 'dirty'
  templateDraftRestored.value = true
  persistTemplateDraft(latest)
  toast('已确认以本机草稿为准，请再次点击保存', 'ri-draft-line')
}
function removeTemplate(template: DocTemplate) {
  const generation = templateGeneration
  confirmAction({ title: '删除模板', description: `删除文档模板「${template.name}」？已用该模板创建的文档不会受影响。`, confirmLabel: '删除模板', run: async () => {
    if (generation !== templateGeneration) return
    try { await apiDeleteDocTemplate(template.id) }
    catch { throw new Error('模板删除失败，内容仍然保留，请重试。') }
    if (generation !== templateGeneration) return
    invalidateTemplateListRequest()
    removeTemplateDraft(template.id)
    templates.value = templates.value.filter((item) => item.id !== template.id)
    toast(`已删除模板「${template.name}」`, 'ri-delete-bin-line')
  } })
}

// ---- 文档树拖拽 ----

const treeQuery = ref('')
const treeSearchInput = ref<HTMLInputElement | null>(null)
const treeFocusKey = ref('')
// Body draft writes replace docEdits. Keep the title projection stable while typing body text.
const directoryTitles = computed<Map<string, string>>(previous => {
  const next = new Map<string, string>()
  for (const folder of kb.value?.folders ?? []) for (const id of folder.docs) next.set(id, (data.docEdits[id]?.title ?? data.docById(id)?.title) || '未命名文档')
  if (previous?.size === next.size && [...next].every(([id, title]) => previous.get(id) === title)) return previous
  return next
})
function documentTitle(id: string): string {
  return directoryTitles.value.get(id) || (data.docEdits[id]?.title ?? data.docById(id)?.title) || '未命名文档'
}
const noteTimeOrder = ref<'desc' | 'asc'>('desc')
function compareNoteTime(leftId: string, rightId: string): number {
  const timeOf = (id: string) => {
    const item = data.docById(id)
    const time = Date.parse(item?.updated || item?.created || '')
    return Number.isNaN(time) ? 0 : time
  }
  const difference = timeOf(leftId) - timeOf(rightId)
  return (noteTimeOrder.value === 'desc' ? -difference : difference) || leftId.localeCompare(rightId)
}
function homeDocIds(ids: string[]): string[] {
  return kb.value?.notes ? [...ids].sort(compareNoteTime) : ids
}
const treeElement = ref<HTMLElement | null>(null)
const currentFolderId = computed(
  () => kb.value?.folders.find((folder) => folder.docs.includes(docId.value ?? ''))?.id ?? '',
)
const mixedRows = computed(() => kb.value ? directoryRows(kb.value, documentTitle, folderStates.value, treeQuery.value, kb.value.notes ? compareNoteTime : undefined, { includeMatchedDescendants: true }) : [])
const treeRenderLimit = ref(500)
const renderedTreeRows = computed(() => mixedRows.value.slice(0, treeRenderLimit.value))
watch([kbId, treeQuery], () => { treeRenderLimit.value = 500 }, { flush: 'sync' })
const treeResultCount = computed(() => mixedRows.value.filter(node => node.kind === 'doc').length)
watch(renderedTreeRows, rows => {
  if (!rows.some(row => row.key === treeFocusKey.value)) treeFocusKey.value = rows.find(row => row.id === docId.value)?.key ?? rows[0]?.key ?? ''
}, { immediate: true })
async function focusTreeNode(key: string) {
  const index = mixedRows.value.findIndex(node => node.key === key)
  if (index >= treeRenderLimit.value) treeRenderLimit.value = Math.ceil((index + 1) / 500) * 500
  treeFocusKey.value = key
  await nextTick()
  const target = Array.from(treeElement.value?.querySelectorAll<HTMLElement>('[data-node]') ?? []).find(node => node.dataset.node === key)
  target?.focus({ preventScroll: true }); target?.scrollIntoView?.({ block: 'nearest' })
}
async function showMoreTreeRows() {
  const firstNew = mixedRows.value[treeRenderLimit.value]?.key
  treeRenderLimit.value += 500
  if (firstNew) await focusTreeNode(firstNew)
}
function onTreeKeydown(event: KeyboardEvent) {
  if (navigationComposition.active(event) || event.altKey || event.ctrlKey || event.metaKey) return
  const key = (event.target as HTMLElement).closest<HTMLElement>('[data-node]')?.dataset.node
  const index = mixedRows.value.findIndex(node => node.key === key)
  const node = mixedRows.value[index]
  if (!node) return
  let next: string | undefined
  switch (event.key) {
    case 'ArrowDown': next = renderedTreeRows.value[Math.min(index + 1, renderedTreeRows.value.length - 1)]?.key; break
    case 'ArrowUp': next = mixedRows.value[Math.max(0, index - 1)]?.key; break
    case 'Home': next = mixedRows.value[0]?.key; break
    case 'End': next = renderedTreeRows.value[renderedTreeRows.value.length - 1]?.key; break
    case 'ArrowRight':
      if (node.hasChildren && !isOpen(node.key) && !treeQuery.value.trim()) folderOpen[node.key] = true
      else if (mixedRows.value[index + 1]?.parent === node.key) next = mixedRows.value[index + 1]?.key
      break
    case 'ArrowLeft':
      if (node.hasChildren && isOpen(node.key) && !treeQuery.value.trim()) folderOpen[node.key] = false
      else next = node.parent
      break
    default: return
  }
  event.preventDefault()
  if (next) void focusTreeNode(next)
}
function onTreeSearchKeydown(event: KeyboardEvent) {
  if (navigationComposition.active(event)) return
  if (event.key === 'ArrowDown' && mixedRows.value[0]) { event.preventDefault(); void focusTreeNode(mixedRows.value[0].key) }
  if (event.key === 'Escape' && treeQuery.value) { event.preventDefault(); event.stopPropagation(); treeQuery.value = '' }
}
const draggingNode = ref<string | null>(null)
const dropTarget = ref<{ key: string; placement: 'before' | 'inside' | 'after' } | null>(null)
const directoryError = ref('')
let pendingMove: { kbId: string; node: string; parent: string; expectedParent: string; beforeSibling?: string | null } | null = null
function startNodeDrag(key: string, event: DragEvent) {
  draggingNode.value = key
  dropTarget.value = null
  event.dataTransfer?.setData('application/x-wao-directory', key)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function endNodeDrag() { draggingNode.value = null; dropTarget.value = null }
function dragLeaveNode(key: string, event: DragEvent) {
  if (dropTarget.value?.key === key && !(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) dropTarget.value = null
}
function dragOverNode(key: string, event: DragEvent) {
  const node = draggingNode.value
  if (!node || node === key || !kb.value || treeSaveState.value === 'saving') { dropTarget.value = null; return }
  const row = event.currentTarget as HTMLElement
  const ratio = (event.clientY - row.getBoundingClientRect().top) / row.getBoundingClientRect().height
  const placement = ratio < .25 ? 'before' : ratio > .75 ? 'after' : 'inside'
  const parents = parentMap.value
  const parent = placement === 'inside' ? key : parents[key] ?? ''
  if (node.startsWith('d:') && !parent) { dropTarget.value = null; return }
  let cursor = parent
  const visited = new Set<string>()
  while (cursor) {
    if (cursor === node || visited.has(cursor)) { dropTarget.value = null; return }
    visited.add(cursor)
    cursor = parents[cursor] ?? ''
  }
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dropTarget.value = { key, placement }
}
async function dropOnNode(key: string) {
  const target = dropTarget.value
  if (!target || target.key !== key || !kb.value) return
  const parents = directoryParents(kb.value)
  const parent = target.placement === 'inside' ? key : parents[key] ?? ''
  const siblings = Object.keys(parents).filter(candidate => parents[candidate] === parent && candidate !== draggingNode.value)
  const beforeSibling = target.placement === 'before' ? key
    : target.placement === 'after' ? siblings[siblings.indexOf(key) + 1] ?? null : null
  await moveNode(parent, draggingNode.value, beforeSibling)
}
async function moveNode(parent: string, node = draggingNode.value, beforeSibling?: string | null) {
  draggingNode.value = null
  dropTarget.value = null
  if (!kb.value || !node || treeSaveState.value === 'saving') return
  const parents = directoryParents(kb.value)
  let cursor = parent
  const visited = new Set<string>()
  while (cursor) {
    if (cursor === node || visited.has(cursor)) { directoryError.value = '不能移动到自身或后代节点中'; return }
    visited.add(cursor)
    cursor = parents[cursor] ?? ''
  }
  if (parents[node] === parent && beforeSibling === undefined) return
  pendingMove = { kbId: kb.value.id, node, parent, expectedParent: parents[node] ?? '', beforeSibling }
  await persistNodeMove()
}
async function persistNodeMove() {
  if (!pendingMove) return
  const generation = workspaceGeneration
  const move = { ...pendingMove }
  const targetKb = data.kbById(move.kbId)
  if (!targetKb) return
  directoryError.value = ''
  treeSaveState.value = 'saving'
  try {
    const parents = directoryParents(targetKb)
    const children = new Map<string, string[]>()
    for (const [key, parent] of Object.entries(parents)) {
      const siblings = children.get(parent)
      if (siblings) siblings.push(key); else children.set(parent, [key])
    }
    const descendants: string[] = [], pending = [move.node], visited = new Set<string>()
    while (pending.length) {
      const key = pending.pop()!
      if (visited.has(key)) continue
      visited.add(key)
      if (key.startsWith('d:')) descendants.push(key.slice(2))
      for (const child of children.get(key) ?? []) pending.push(child)
    }
    for (const id of descendants) {
      if (data.docPending(id) && (!(await data.persistDocNow(id)) || data.docPending(id))) {
        throw new Error('请先完成文档保存，再重试移动目录')
      }
      if (generation !== workspaceGeneration) return
    }
    const saved = await data.moveKnowledgeNode(targetKb.id, move.node, move.parent, move.expectedParent, move.beforeSibling)
    if (generation !== workspaceGeneration) return
    if (!saved) throw new Error('目录已变化')
    folderOpen[move.parent] = true
    treeSaveState.value = 'saved'
    pendingMove = null
  } catch {
    if (generation !== workspaceGeneration) return
    treeSaveState.value = 'error'
    directoryError.value = '目录移动未完成，请先确认文档已保存后重试；若其他窗口已移动请刷新后重新选择'
  }
}
async function locateCurrentDoc() {
  treeQuery.value = ''
  let ancestor = parentMap.value[`d:${docId.value}`]
  const visited = new Set<string>()
  while (ancestor && !visited.has(ancestor)) { visited.add(ancestor); folderOpen[ancestor] = true; ancestor = parentMap.value[ancestor] }
  await nextTick()
  await focusTreeNode(`d:${docId.value}`)
}
async function moveCurrentDoc(event: Event) {
  if (docId.value) await moveNode(`f:${(event.target as HTMLSelectElement).value}`, `d:${docId.value}`)
  ;(event.target as HTMLSelectElement).value = currentFolderId.value
}

const treeSaveState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
async function addFolder() {
  const generation = workspaceGeneration
  const id = await data.addFolder(kbId.value)
  if (generation !== workspaceGeneration) return
  if (id) {
    startFolderRename(id)
    toast('已创建文件夹', 'ri-folder-add-line')
  } else {
    toast('文件夹没有保存到服务器，请重试', 'ri-error-warning-line')
  }
}
function editTitle(e: Event) {
  if (docId.value) data.setDocEdit(docId.value, { title: (e.target as HTMLInputElement).value })
}
async function commitFolderName(folderId: string) {
  if (renamingFolder.value !== folderId || folderRenameSaving.value) return
  const name = folderNameDraft.value.trim()
  if (!name) { folderRenameError.value = '名称不能为空'; return }
  folderRenameSaving.value = true
  const generation = workspaceGeneration
  const saved = await data.renameFolder(kbId.value, folderId, name)
  if (generation !== workspaceGeneration) return
  folderRenameSaving.value = false
  if (saved) { renamingFolder.value = null; folderRenameError.value = '' }
  else folderRenameError.value = '重命名失败，输入已保留，按回车重试'
}
function removeFolder(folderId: string) {
  const folder = kb.value?.folders.find((f) => f.id === folderId)
  if (!folder) return
  const targetKb = kbId.value
  const identity = currentIdentity()
  confirmAction({ title: '删除文件夹', description: `将「${folder.name}」及其中全部文档（包括已归档）放入回收站？7 天内可恢复。`, confirmLabel: '移入回收站', run: async () => {
    if (identity !== currentIdentity()) return
    const removedDoc = base.value?.folderId === folderId || folder.docs.includes(docId.value ?? '') ? docId.value : null
    try { await data.deleteFolder(targetKb, folderId) }
    catch { throw new Error('删除失败，请先移出子分组后重试。目录和文档仍然保留。') }
    if (identity !== currentIdentity()) return
    if (removedDoc && docId.value === removedDoc && kbId.value === targetKb) await goHome()
    toast(`已把「${folder.name}」放进回收站，7 天内可恢复`, 'ri-delete-bin-line')
  } })
}
/**
 * 删掉整个知识库并回到列表。
 *
 * <p>入口放在这里是因为：新建知识库后会直接进详情页，此前想删只能退回列表、
 * 在卡片上找齿轮 —— 找不到就等于删不掉。删除同样是软删除，7 天内可恢复。
 */
function removeKb() {
  const target = kb.value
  if (!target) return
  const name = target.name
  const identity = currentIdentity()
  confirmAction({ title: '删除知识库', description: `将「${name}」及其中全部文档（包括已归档）放入回收站？7 天内可恢复。`, confirmLabel: '移入回收站', run: async () => {
    if (identity !== currentIdentity()) return
    try { await data.deleteKb(target.id) }
    catch { throw new Error('知识库删除失败，内容仍然保留，请重试。') }
    if (identity !== currentIdentity()) return
    if (kbId.value === target.id) kbSettingsOpen.value = false
    const next = tabs.close(`kb:${target.id}`)
    if (kbId.value === target.id) await router.push(next?.path ?? '/knowledge?home=1')
    toast(`已把「${name}」放进回收站，7 天内可恢复`, 'ri-delete-bin-line')
  } })
}

function removeDoc(id: string) {
  const title = data.docOf(id).title || '未命名文档'
  const identity = currentIdentity()
  confirmAction({ title: '删除文档', description: `将「${title}」放入回收站？7 天内可恢复。`, confirmLabel: '移入回收站', run: async () => {
    if (identity !== currentIdentity()) return
    try { await data.deleteDoc(id) }
    catch { throw new Error('删除失败，请先移出子节点后重试。文档仍然保留。') }
    if (identity !== currentIdentity()) return
    if (docId.value === id) await goHome()
    toast(`已把「${title}」放进回收站，7 天内可恢复`, 'ri-delete-bin-line')
  } })
}
function archiveDoc(id: string) {
  const title = data.docOf(id).title || '未命名文档'
  const identity = currentIdentity()
  const targetKb = kbId.value
  confirmAction({ title: '归档文档', danger: false, confirmLabel: '归档', description: `归档「${title}」后正文会长期保留，可随时从“已归档”恢复。`, run: async () => {
    if (identity !== currentIdentity()) return
    await flushSheet()
    if (identity !== currentIdentity()) return
    if (data.docPending(id)) await data.persistDocNow(id)
    if (identity !== currentIdentity()) return
    if (data.docPending(id) || data.saveState(`doc:${id}`).failed) throw new Error('请先保存文档再归档，当前草稿仍然保留。')
    if (!await data.archiveDoc(id)) throw new Error('文档归档失败，内容和目录未改变，请重试。')
    if (identity !== currentIdentity()) return
    if (targetKb === kbId.value) archivedOpen.value = true
    toast(`已归档「${title}」`, 'ri-archive-line')
  } })
}
async function unarchiveDoc(id: string) {
  const title = data.docOf(id).title || '未命名文档'
  const generation = workspaceGeneration
  const restored = await data.unarchiveDoc(id)
  if (generation !== workspaceGeneration) return
  if (restored) {
    const folderId = data.docById(id)?.folderId
    if (folderId) folderOpen[`f:${folderId}`] = true
    toast(`已把「${title}」恢复到原文件夹`, 'ri-inbox-unarchive-line')
  } else {
    toast('取消归档失败，文档仍在归档中，请重试', 'ri-error-warning-line')
  }
}
/** 分享菜单开关（文档与小记共用同一个入口）。 */
const exportingExcel = ref(false)
async function exportSheetExcel() {
  if (exportingExcel.value) return
  exportingExcel.value = true; moreMenuOpen.value = false
  try {
    if (!sheetEditor.value) throw new Error('表格尚未加载完成')
    await sheetEditor.value.exportExcel(doc.value?.title || '工作簿')
    toast('Excel 已导出', 'ri-download-line')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '导出失败，请重试', 'ri-error-warning-line')
  } finally { exportingExcel.value = false }
}
const shareMenu = ref(false)
const moreMenuOpen = ref(false)
const shareTrigger = ref<HTMLButtonElement | null>(null)
const moreTrigger = ref<HTMLButtonElement | null>(null)
const sharePanel = ref<HTMLElement | null>(null)
const morePanel = ref<HTMLElement | null>(null)
watch([shareMenu, moreMenuOpen], async ([share, more]) => {
  await nextTick()
  ;(share ? sharePanel.value : more ? morePanel.value : null)?.querySelector<HTMLElement>('button:not(:disabled), select:not(:disabled)')?.focus()
})
function onDocumentMenuKeydown(event: KeyboardEvent, type: 'share' | 'more') {
  if (event.isComposing) return
  const panel = type === 'share' ? sharePanel.value : morePanel.value
  const close = () => { if (type === 'share') shareMenu.value = false; else moreMenuOpen.value = false }
  if (event.key === 'Escape') {
    event.preventDefault(); event.stopPropagation(); close()
    ;(type === 'share' ? shareTrigger.value : moreTrigger.value)?.focus()
    return
  }
  if (event.target instanceof HTMLSelectElement) return
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const items = Array.from(panel?.querySelectorAll<HTMLElement>('button:not(:disabled), select:not(:disabled)') ?? [])
    .filter(item => getComputedStyle(item).display !== 'none')
  if (!items.length) return
  const current = items.indexOf(document.activeElement as HTMLElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
    : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
  event.preventDefault()
  items[next]?.focus()
}
watch(docId, () => { moreMenuOpen.value = false; shareMenu.value = false; shareNoteId.value = ''; saveTemplateOpen.value = false })
async function copyOriginalLink() {
  if (!docId.value) return
  shareMenu.value = false; moreMenuOpen.value = false
  const url = docOriginalUrl(kbId.value, docId.value)
  toast(await copyToClipboard(url) ? '知识库原文链接已复制，可用于定位文档' : '复制失败，请重试', 'ri-links-line')
}
function openDocumentShare() {
  if (!docId.value) return
  shareMenu.value = false
  shareNoteId.value = docId.value
}

/** 粘贴 / 拖入的图片存进图床，再把地址回插到光标处。 */
async function onUploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) {
    toast('图片超过 10MB，已跳过', 'ri-error-warning-line')
    done(null, '')
    return
  }
  const ref = await data.uploadImage(
    file,
    `${editingPrivateNote.value ? '小记' : '文档'} · ${doc.value?.title || '未命名文档'}`,
    { requirePersisted: true },
  )
  if (!ref) toast('图片上传失败，请重试', 'ri-error-warning-line')
  done(ref?.url ?? null, ref?.name ?? file.name)
}
async function onUploadFile(file: File, done: (item: FileItem | null) => void) {
  if (file.size > data.MAX_FILE_BYTES) {
    toast('文件超过 200MB，已跳过', 'ri-error-warning-line')
    done(null)
    return
  }
  const item = await data.uploadFile(file, undefined, true)
  if (!item) toast('文件上传失败，正文仍可继续编辑', 'ri-error-warning-line')
  done(item)
}
async function retryDocSave() {
  if (docId.value && !(await data.persistDocNow(docId.value))) {
    const reason = data.saveState(`doc:${docId.value}`).errorMessage
    toast(reason ? `${reason}；本机草稿保持完整` : '仍未保存到服务器，本机草稿保持完整', 'ri-error-warning-line')
  }
}
async function loadServerConflict() {
  if (docId.value && await data.useServerDoc(docId.value)) toast('已载入服务器版本', 'ri-refresh-line')
}
async function keepConflictCopy() {
  if (!docId.value) return
  const copyId = await data.saveDocConflictCopy(docId.value)
  if (!copyId) {
    toast('副本保存失败，本机草稿仍保留', 'ri-error-warning-line')
    return
  }
  selectDoc(copyId)
  toast('本机草稿已另存为冲突副本', 'ri-file-copy-2-line')
}
function homeDocumentTime(value: string): string {
  const date = new Date(value)
  if (!value || Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  const today = new Date()
  const day = date.toDateString() === today.toDateString() ? '今天' : `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  return `${day} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
const homeFolderOpen = reactive<Record<string, boolean>>({})
const docTotal = computed(() => kb.value?.folders.reduce((total, folder) => total + folder.docs.length, 0) ?? 0)
const homeOrder = ref<'directory' | 'updated' | 'title'>('directory')
const homeSearch = computed(() => treeQuery.value.trim().toLocaleLowerCase())
const homeDirectoryRows = computed(() => kb.value ? directoryRows(kb.value, documentTitle, homeFolderOpen, treeQuery.value, kb.value.notes ? compareNoteTime : undefined, { includeMatchedDescendants: true }) : [])
const homeDirectoryMatches = computed(() => kb.value ? directoryRows(kb.value, documentTitle, {}, treeQuery.value, kb.value.notes ? compareNoteTime : undefined, { includeMatchedDescendants: true }) : [])
const homeGroups = computed(() => {
  const groups = (kb.value?.folders ?? []).map(folder => ({
    id: folder.id,
    name: folder.name,
    docs: homeDocIds(folder.docs).filter(id => !homeSearch.value
      || folder.name.toLocaleLowerCase().includes(homeSearch.value)
      || documentTitle(id).toLocaleLowerCase().includes(homeSearch.value)),
  })).filter(folder => !homeSearch.value || folder.docs.length || folder.name.toLocaleLowerCase().includes(homeSearch.value))
  if (homeOrder.value === 'directory') return groups
  const ids = [...new Set(homeDirectoryMatches.value.filter(node => node.kind === 'doc').map(node => node.id))]
  const titleOf = documentTitle
  const timeOf = (id: string) => Date.parse(data.docById(id)?.updated || data.docById(id)?.created || '') || 0
  ids.sort((a, b) => homeOrder.value === 'updated'
    ? timeOf(b) - timeOf(a) || titleOf(a).localeCompare(titleOf(b), 'zh-CN', { numeric: true })
    : titleOf(a).localeCompare(titleOf(b), 'zh-CN', { numeric: true }))
  return ids.length ? [{ id: `sorted:${homeOrder.value}`, name: homeOrder.value === 'updated' ? '最近更新' : '按名称', docs: ids }] : []
})
const homeResultCount = computed(() => homeOrder.value === 'directory' ? homeDirectoryMatches.value.filter(node => node.kind === 'doc').length : homeGroups.value.reduce((total, group) => total + group.docs.length, 0))
const allHomeGroupsOpen = computed(() => homeOrder.value === 'directory'
  ? homeDirectoryRows.value.filter(node => node.hasChildren).every(node => homeFolderOpen[node.key] !== false)
  : homeGroups.value.every(group => homeFolderOpen[group.id] !== false))
function toggleHomeGroups() {
  const expand = !allHomeGroupsOpen.value
  if (homeOrder.value === 'directory') for (const key of Object.keys(parentMap.value)) homeFolderOpen[key] = expand
  else for (const group of homeGroups.value) homeFolderOpen[group.id] = expand
}
watch(kbId, () => { treeQuery.value = ''; homeOrder.value = 'directory'; for (const key of Object.keys(homeFolderOpen)) delete homeFolderOpen[key] })
// 搜索结果始终可见，清空后恢复用户此前的折叠选择。
function isHomeGroupOpen(id: string) { return !!homeSearch.value || homeFolderOpen[id] !== false }
const kbSettingsOpen = ref(false)
const kbIconPickerExpanded = ref(false)
const canManageKb = computed(() => !data.apiLive || kb.value?.ownerId === session.userId)
const kbSettings = reactive({ name: '', desc: '', icon: '', perm: '仅自己' })
const kbSettingsBusy = ref(false)
const kbSettingsError = ref('')
const moveDocId = ref<string | null>(null)
const moveFolderId = ref('')
const directoryContext = ref<InstanceType<typeof ContextActionMenu> | null>(null)
const createItems = computed(() => [
  { id: 'doc', label: '新建文档', icon: 'ri-file-text-line' },
  { id: 'spreadsheet', label: '新建表格', icon: 'ri-table-line' },
  { id: 'drawing', label: '新建画板', icon: 'ri-artboard-line' },
  { id: 'template', label: '从模板新建…', icon: 'ri-file-copy-2-line' },
  { id: 'folder', label: '新建分组', icon: 'ri-folder-add-line' },
].filter(item => !kb.value?.notes || !['spreadsheet', 'drawing'].includes(item.id)))
function openDirectoryContext(event: MouseEvent, parent?: string) {
  void directoryContext.value?.openAt(event, '新增内容', createItems.value.map(item => ({ ...item, disabled: creatingDoc.value, run: () => parent ? createNested(item.id, parent) : createAction(item.id) })))
}
const folderItems = [
  { id: 'rename', label: '重命名', icon: 'ri-edit-line' },
  { id: 'delete', label: '移入回收站', icon: 'ri-delete-bin-line', danger: true },
]
const docItems = [
  { id: 'rename', label: '重命名', icon: 'ri-edit-line' },
  { id: 'move', label: '移动…', icon: 'ri-folder-transfer-line' },
  { id: 'template', label: '另存为模板…', icon: 'ri-file-copy-2-line' },
  { id: 'archive', label: '归档', icon: 'ri-archive-line' },
  { id: 'delete', label: '移入回收站', icon: 'ri-delete-bin-line', danger: true },
]
async function goHome() {
  mobileDirectoryOpen.value = false
  const request = ++documentNavigationRequest, generation = workspaceGeneration
  await flushSheet()
  if (generation !== workspaceGeneration || request !== documentNavigationRequest) return
  flushCurrentDoc()
  docId.value = null
  const query = { ...route.query }; delete query.doc
  void router.replace({ query })
}
function switchKb(event: Event) {
  flushCurrentDoc()
  void router.push({ path: '/knowledge', query: { kb: (event.target as HTMLSelectElement).value } })
}
async function createNested(action: string, parent: string) {
  if (creatingDoc.value || treeSaveState.value === 'saving' || !kb.value) return
  const generation = workspaceGeneration
  if (action === 'folder') {
    const id = await data.addFolder(kbId.value)
    if (generation !== workspaceGeneration) return
    if (!id) { toast('分组创建失败，请重试', 'ri-error-warning-line'); return }
    await moveNode(parent, `f:${id}`)
    if (generation !== workspaceGeneration) return
    startFolderRename(id)
    return
  }
  const folderId = parent.startsWith('f:') ? parent.slice(2) : kb.value.folders.find(f => f.docs.includes(parent.slice(2)))?.id
  if (!folderId) return
  await createAction(action, folderId, parent)
}
const newDocParent = ref<string | null>(null)
async function createAction(action: string, folderId?: string, parent?: string) {
  if (creatingDoc.value) return
  const generation = workspaceGeneration
  newDocParent.value = parent ?? null
  if (action === 'folder') { await addFolder(); return }
  const target = folderId || currentFolderId.value || kb.value?.folders[0]?.id || await data.addFolder(kbId.value)
  if (generation !== workspaceGeneration) return
  if (!target) { toast('创建目录失败，请重试', 'ri-error-warning-line'); return }
  templatePickerOpen.value = action === 'template'
  newDocFolderId.value = target
  if (action === 'doc') await createDocFrom()
  if (action === 'spreadsheet' || action === 'drawing') await createDocFrom(undefined, action)
}
function folderAction(action: string, id: string) {
  if (action === 'rename') startFolderRename(id)
  else if (action === 'delete') void removeFolder(id)
}
async function docAction(action: string, id: string) {
  const generation = workspaceGeneration
  if (action === 'delete') { await removeDoc(id); return }
  if (action === 'archive') { await archiveDoc(id); return }
  if (action === 'move') {
    moveDocId.value = id
    moveFolderId.value = kb.value?.folders.find((folder) => folder.docs.includes(id))?.id || ''
    return
  }
  await selectDoc(id)
  await nextTick()
  if (generation !== workspaceGeneration || docId.value !== id) return
  if (action === 'rename') { reading.value = false; await nextTick(); titleEl.value?.focus(); titleEl.value?.select() }
  if (action === 'template') openSaveTemplate()
}
async function confirmMove() {
  if (!moveDocId.value || !moveFolderId.value) return
  const generation = workspaceGeneration
  await moveNode(`f:${moveFolderId.value}`, `d:${moveDocId.value}`)
  if (generation !== workspaceGeneration) return
  if (treeSaveState.value === 'saved') { folderOpen[`f:${moveFolderId.value}`] = true; moveDocId.value = null }
}
function openKbIconSettings() {
  openKbSettings()
  kbIconPickerExpanded.value = true
}
function openKbSettings() {
  if (!canManageKb.value) return
  kbSettings.perm = kb.value?.perm || '仅自己'
  kbIconPickerExpanded.value = false
  kbSettings.name = kb.value?.name || ''
  kbSettings.desc = kb.value?.desc || ''
  kbSettings.icon = kb.value?.icon || 'wao-kb-icon-notebook'
  kbSettingsError.value = ''
  kbSettingsOpen.value = true
}
async function saveKbSettings() {
  if (!kbSettings.name.trim() || kbSettingsBusy.value || !kb.value) return
  const targetKb = kbId.value, generation = workspaceGeneration
  kbSettingsBusy.value = true
  try {
    const patch = { name: kbSettings.name.trim(), desc: kbSettings.desc.trim(), icon: kbSettings.icon, perm: kbSettings.perm }
    const saved = await data.updateKb(targetKb, patch)
    if (generation !== workspaceGeneration) return
    if (!saved) throw new Error('save failed')
    tabs.setMeta(`kb:${targetKb}`, { label: patch.name, icon: patch.icon })
    kbSettingsOpen.value = false
  } catch { if (generation === workspaceGeneration) kbSettingsError.value = '设置保存失败，输入已保留，请重试。' }
  finally { if (generation === workspaceGeneration) kbSettingsBusy.value = false }
}

// Forms belong to the library/account where they were opened. Late replies cannot
// close or unlock a new library's form, even after navigating A → B → A.
watch([kbId, currentIdentity], () => {
  workspaceGeneration++
  kbSettingsOpen.value = false; kbSettingsBusy.value = false; kbSettingsError.value = ''
  Object.assign(kbSettings, { name: '', desc: '', icon: '', perm: '仅自己' })
  moveDocId.value = null; moveFolderId.value = ''; pendingMove = null
  treeSaveState.value = 'idle'; directoryError.value = ''
  renamingFolder.value = null; folderNameDraft.value = ''; folderRenameSaving.value = false; folderRenameError.value = ''
  newDocFolderId.value = null; newDocParent.value = null; creatingDoc.value = false; templatePickerOpen.value = false
  saveTemplateOpen.value = false; shareNoteId.value = ''; shareMenu.value = false; moreMenuOpen.value = false; shortcutsOpen.value = false
  focused.value = false; reading.value = false; mobileDirectoryOpen.value = false
  directoryContext.value?.close?.(); dismissConfirmation()
}, { flush: 'sync' })
onBeforeUnmount(() => { workspaceGeneration++ })

</script>

<template>
  <ContextActionMenu ref="directoryContext" />
  <div v-if="!kb" class="missing">知识库不存在。</div>
  <div v-else ref="workspace" class="kb-detail wao-page-frame" :class="{ 'directory-collapsed': !directoryVisible, 'document-reading': reading, 'document-focused': focused }" @keydown="onWorkspaceKeydown">
    <button v-show="!focused" ref="directoryTrigger" type="button" class="directory-toggle" :title="directoryVisible ? '收起知识库目录' : '展开知识库目录'" :aria-label="directoryVisible ? '收起知识库目录' : '展开知识库目录'" aria-controls="kb-directory" :aria-expanded="directoryVisible" @click="toggleDirectory"><i class="ri-side-bar-line" aria-hidden="true"></i></button>
    <div v-if="isMobile && mobileDirectoryOpen" class="mobile-pane-mask" aria-hidden="true" @click="closeDirectory" />
    <!-- 左栏 -->
    <div v-show="directoryVisible" id="kb-directory" ref="directoryPanel" class="side" :style="{ '--directory-width': `${directoryWidth}px` }" :role="isMobile ? 'dialog' : undefined" :aria-modal="isMobile ? true : undefined" aria-label="知识库目录" @keydown="navigationKeydown($event, 'directory')" @compositionstart="navigationComposition.start" @compositionend="navigationComposition.end">
      <button v-if="isMobile" type="button" class="mobile-directory-close" aria-label="关闭知识库目录" @click="closeDirectory"><i class="ri-close-line" aria-hidden="true" />关闭目录</button>
      <PaneResizeHandle v-if="!isMobile" :storage-key="`wao:directory-width:${session.currentUser.id}`" label="知识库目录宽度" :default-width="262" :max="520" @resize="directoryWidth = $event" />
      <div class="side-top">
        <button class="back" @click="router.push('/knowledge?home=1')">
          <i class="ri-arrow-left-line"></i>全部知识库
        </button>
        <div class="side-kb">
          <div class="side-icon" :style="{ background: isColoredKnowledgeIcon(kb.icon) ? 'transparent' : kb.color }"><i :class="kb.icon"></i></div>
          <select class="kb-switch" :value="kbId" aria-label="切换知识库" @change="switchKb"><option v-for="library in data.kbs" :key="library.id" :value="library.id">{{ library.name }}</option></select>
          <ResourceMenu label="知识库设置" :items="[...(canManageKb ? [{ id: 'settings', label: '设置', icon: 'ri-settings-3-line' }] : []), { id: 'templates', label: '模板管理', icon: 'ri-file-copy-2-line' }]" @select="$event === 'settings' ? openKbSettings() : templateManagerOpen = true" />
        </div>

        <p v-if="creatingDoc" class="tree-save">正在创建文档…</p>
        <div v-if="treeSaveState !== 'idle'" class="tree-save" :class="treeSaveState">
          <i :class="treeSaveState === 'saving' ? 'ri-loader-4-line spin' : treeSaveState === 'error' ? 'ri-error-warning-line' : 'ri-check-line'"></i>
          <span>{{ treeSaveState === 'saving' ? '正在保存目录' : treeSaveState === 'error' ? '目录保存失败' : '目录已保存' }}</span>
          <button v-if="treeSaveState === 'error'" @click="persistNodeMove()">重试</button>
        </div>
      </div>

      <div class="tree-search"><label><i class="ri-search-line"></i><input ref="treeSearchInput" v-model="treeQuery" type="search" aria-label="搜索本知识库文件夹和文档" placeholder="搜索文档" @keydown="onTreeSearchKeydown" /><button v-if="treeQuery" type="button" aria-label="清除目录搜索" @click="treeQuery = ''; treeSearchInput?.focus()"><i class="ri-close-line" aria-hidden="true"></i></button></label><ResourceMenu label="新增内容" icon="ri-add-line" :items="createItems" @select="createAction($event)" /></div>
      <p v-if="treeQuery.trim()" class="directory-search-count" role="status">找到 {{ treeResultCount }} 篇文档</p>
      <button class="library-home-link" :class="{ on: !docId }" @click="goHome"><i class="ri-home-4-line"></i>首页</button>
      <div class="directory-heading"><span><i class="ri-list-check"></i>目录</span><div class="directory-actions"><select v-if="kb.notes" v-model="noteTimeOrder" class="note-time-sort" aria-label="小记目录时间排序"><option value="desc">时间倒序</option><option value="asc">时间正序</option></select><button :disabled="!docId" title="定位当前文档" @click="locateCurrentDoc"><i class="ri-focus-3-line"></i></button><button :disabled="!kb.folders.length" :title="allFoldersOpen ? '折叠全部' : '展开全部'" :aria-label="allFoldersOpen ? '折叠全部目录' : '展开全部目录'" :aria-expanded="allFoldersOpen" @click="toggleAllFolders"><i :class="allFoldersOpen ? 'ri-collapse-diagonal-line' : 'ri-expand-diagonal-line'"></i></button></div></div>
      <p v-if="folderRenameError" role="alert">{{ folderRenameError }}</p>
      <div ref="treeElement" class="tree" role="tree" aria-label="知识库文档目录" @keydown="onTreeKeydown" @contextmenu.prevent="openDirectoryContext($event)">
        <p v-if="directoryError" role="alert">{{ directoryError }}</p>
        <div v-if="draggingNode?.startsWith('f:')" class="root-drop" @dragover.prevent @drop.prevent.stop="moveNode('', draggingNode, null)">拖到这里移至目录顶层</div>
        <p v-if="treeQuery.trim() && !mixedRows.length" class="folder-empty">没有匹配的文件夹或文档</p>
        <div v-for="node in renderedTreeRows" :key="node.key" class="directory-row" :class="[node.kind === 'folder' ? 'folder-row' : 'doc-row', { selected: docId === node.id, 'drag-source': draggingNode === node.key, 'drop-before': dropTarget?.key === node.key && dropTarget.placement === 'before', 'drop-inside': dropTarget?.key === node.key && dropTarget.placement === 'inside', 'drop-after': dropTarget?.key === node.key && dropTarget.placement === 'after' }]"
          :style="{ '--node-depth': Math.min(node.depth, 8) }" @contextmenu.prevent.stop="openDirectoryContext($event, node.key)" :draggable="renamingFolder !== node.id && treeSaveState !== 'saving' && !treeQuery.trim()"
          @dragstart="startNodeDrag(node.key, $event)" @dragend="endNodeDrag" @dragover="dragOverNode(node.key, $event)" @dragleave="dragLeaveNode(node.key, $event)" @drop.prevent.stop="dropOnNode(node.key)">
          <button v-if="node.hasChildren" class="tree-caret" tabindex="-1" :aria-label="`展开或收起${node.title}`" :aria-expanded="isOpen(node.key)" @click="toggleFolder(node.key)"><i class="chev" :class="!isOpen(node.key) ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
          <span v-else class="tree-caret-spacer" aria-hidden="true"></span>
          <input v-if="node.kind === 'folder' && renamingFolder === node.id" class="folder-input" v-model="folderNameDraft" :disabled="folderRenameSaving"
            @keydown="folderNameKeydown($event, node.id)" @blur="commitFolderName(node.id)" />
          <button v-else :class="[node.kind === 'folder' ? 'folder-btn' : 'doc-node', { on: docId === node.id }]" :title="node.title" :aria-label="node.title" role="treeitem" :aria-level="node.depth + 1" :aria-selected="docId === node.id" :aria-expanded="node.hasChildren ? (treeQuery.trim() ? true : isOpen(node.key)) : undefined" :tabindex="treeFocusKey === node.key ? 0 : -1" :data-node="node.key" @pointerenter="preloadSheet(node.id)" @focus="treeFocusKey = node.key; preloadSheet(node.id)" :style="{ minWidth: 0 }" @click="node.kind === 'folder' ? (toggleFolder(node.key)) : selectDoc(node.id)">
            <i v-if="node.kind === 'doc'" :class="pageIcon(node.id)" aria-hidden="true"></i>
            <span :class="node.kind === 'folder' ? 'folder-name' : 'doc-node-title'" :style="{ color: docId === node.id ? 'var(--wao-primary)' : undefined }">{{ node.title }}</span>
          </button>
          <div class="tree-row-actions"><ResourceMenu :label="`${node.title}的更多操作`" :items="node.kind === 'folder' ? folderItems : docItems.filter(item => item.id !== 'template' || !data.docById(node.id)?.pageType || data.docById(node.id)?.pageType === 'document')" @select="node.kind === 'folder' ? folderAction($event, node.id) : docAction($event, node.id)" />
          <ResourceMenu :label="`在${node.title}新增内容`" icon="ri-add-line" :items="createItems" @select="createNested($event, node.key)" /></div>
        </div>

        <button v-if="mixedRows.length > treeRenderLimit" type="button" class="directory-load-more" @click="showMoreTreeRows">继续显示目录（已显示 {{ renderedTreeRows.length }} / {{ mixedRows.length }}）</button>

        <section class="archive-tree">
          <button class="archive-head" :aria-expanded="archivedOpen" @click="archivedOpen = !archivedOpen">
            <i :class="archivedOpen ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
            <i class="ri-archive-line"></i>
            <span>已归档</span>
            <span v-if="archivedDocs.length" class="folder-count wao-tabular">{{ archivedDocs.length }}</span>
          </button>
          <div v-if="archivedOpen" class="doc-tree archived-list">
            <div v-if="archivedLoading" class="archive-state"><i class="ri-loader-4-line spin"></i>正在读取归档…</div>
            <div v-else-if="archivedError" class="archive-state error">归档读取失败<button @click="loadArchivedDocs">重试</button></div>
            <div v-else-if="!archivedDocs.length" class="archive-state">还没有归档文档</div>
            <template v-else>
              <div v-for="item in archivedDocs" :key="item.id" class="doc-row archived-row">
                <button class="doc-node" :class="{ on: docId === item.id }" @pointerenter="preloadSheet(item.id)" @focus="preloadSheet(item.id)" @click="selectDoc(item.id)">
                  <i :class="[pageIcon(item.id), 'doc-node-icon']"></i>
                  <span class="doc-node-title">{{ item.title || '未命名文档' }}</span>
                </button>
                <button class="row-act" title="恢复到原文件夹" @click="unarchiveDoc(item.id)">
                  <i class="ri-inbox-unarchive-line"></i>
                </button>
              </div>
            </template>
          </div>
        </section>
      </div>

      <div v-if="kb.auto" class="sync-bar">
        <div class="sync-title"><i class="ri-archive-line" aria-hidden="true"></i>飞书群聊 · 历史归档</div>
        <div class="sync-sub">
          同步状态请前往自动化查看 ·
          <button class="link" @click="router.push('/automation')">查看自动化</button>
        </div>
      </div>
    </div>

    <!-- 文档区 -->
    <div ref="documentPane" class="document-pane" :inert="isMobile && mobileDirectoryOpen ? true : undefined" :class="{ 'content-narrow': contentWidth === 'narrow' && pageType === 'document', 'editor-workspace': pageType === 'spreadsheet' || pageType === 'drawing' }">
          <div v-if="directoryCollapsed && !doc" class="directory-home-bar" aria-hidden="true"></div>
          <div v-if="doc" class="doc-bar">
            <div class="doc-location" :title="`${kb.name} / ${doc.title || '未命名文档'}`">
              <button class="ellip library-name breadcrumb-link" @click="goHome">{{ kb.name }}</button>
              <i class="ri-arrow-right-s-line b-sep"></i>
              <input v-if="pageType !== 'document'" class="sheet-title" :aria-label="pageType === 'drawing' ? '画布名称' : '表格名称'" :value="doc.title" @input="editTitle" />
              <span v-else class="ellip document-name">{{ doc.title || '未命名文档' }}</span>
              <span v-if="base?.auto" class="tag-auto">自动沉淀</span>
              <span v-if="currentArchived" class="tag-archived">已归档</span>
            </div>
            <span v-if="saving || saveFailed || docSavedAt" class="document-save-status"><SaveHint :saving="saving" :saved-at="docSavedAt" :failed="saveFailed" /></span>
            <NoteWidthSwitch v-if="pageType === 'document'" v-model="contentWidth" label="文档宽度" />
            <button v-if="pageType === 'document'" ref="modeTrigger" type="button" class="document-mode" :class="{ selected: reading }" :aria-label="reading ? '编辑文档' : '阅读文档'" :aria-pressed="reading" @click="toggleReading"><i :class="reading ? 'ri-edit-line' : 'ri-book-open-line'" aria-hidden="true"></i><span>{{ reading ? '编辑' : '阅读' }}</span></button>
            <button v-if="pageType === 'document'" ref="focusTrigger" type="button" class="icon-act focus-toggle" :title="focused ? '退出专注（Esc）' : '专注模式（Alt + Shift + F）'" :aria-label="focused ? '退出专注' : '专注模式'" :aria-pressed="focused" @click="toggleFocus"><i :class="focused ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'" aria-hidden="true"></i></button>

            <!-- 协同：只有此刻真的打开着这篇文档的人才会出现 -->
            <span class="collab">
              <span
                class="c-av"
                :title="`${session.currentUser.name}（我）`"
                :style="{ background: session.currentUser.color }"
              >
                {{ session.currentUser.initial }}
              </span>
              <span
                v-for="p in peers"
                :key="p.id"
                class="c-av peer"
                :title="`${p.name} 正在${p.editing ? '编辑' : '查看'}`"
                :style="{ background: p.color }"
                >{{ p.initial }}</span
              >
            </span>
            <span v-if="peers.length" class="realtime"
              ><span class="rt-dot"></span>{{ peers.length + 1 }} 人在线</span
            >

            <label v-if="docId && !currentArchived" class="move-document bar-move">移动到<select :value="currentFolderId" :disabled="treeSaveState === 'saving'" aria-label="移动当前文档到文件夹" @change="moveCurrentDoc"><option v-for="folder in kb.folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label>
            <button v-if="pageType === 'document'" ref="outlineTrigger" class="icon-act outline-toggle" title="文档大纲" aria-label="文档大纲" :aria-expanded="outlineVisible" @click="toggleOutline"><i class="ri-layout-right-line"></i><span>大纲</span></button>
            <span class="doc-info" :title="`${author.name} · 创建于 ${absoluteTime(base?.created ?? '')} · 更新于 ${absoluteTime(base?.updated ?? '')}`"><i class="ri-information-line"></i><span class="sr-only">{{ author.name }} · 创建于 {{ createdLabel }} · 更新于 {{ updatedLabel }}</span></span>
            <button v-if="saveFailed && !data.docConflicts[docId!]" class="save-retry" @click="retryDocSave">重试</button>

            <button v-if="pageType === 'spreadsheet'" class="icon-act" title="导出 Excel" aria-label="导出 Excel" :disabled="exportingExcel" @click="exportSheetExcel"><i class="ri-file-excel-2-line"></i></button>
            <div v-if="pageType !== 'drawing'" class="share-wrap bar-share">
              <button ref="shareTrigger" class="icon-act" title="分享文档" aria-label="分享文档" aria-haspopup="menu" :aria-expanded="shareMenu" @click="moreMenuOpen = false; shareMenu = !shareMenu">
                <i class="ri-links-line"></i>
              </button>
              <div v-if="shareMenu" class="menu-mask" @click="shareMenu = false"></div>
              <div v-if="shareMenu" ref="sharePanel" class="share-menu" aria-label="文档分享选项" @keydown="onDocumentMenuKeydown($event, 'share')">
                <div class="share-scope">
                  分享整篇{{ kb.notes ? '小记' : '文档' }} · 默认需登录，限时链接可免登录
                </div>
                <button class="share-item" @click="copyOriginalLink">
                  <i class="ri-file-text-line"></i>
                  <span class="share-main"><b>复制知识库原文链接</b><em>直接定位此文档，需登录并拥有访问权限</em></span>
                </button>
                <button class="share-item" @click="openDocumentShare()">
                  <i class="ri-external-link-line"></i>
                  <span class="share-main"><b>限时分享设置</b><em>生成或撤销链接，可选 1 小时、1 天或 7 天</em></span>
                </button>
              </div>
            </div>
            <div class="doc-more-wrap" :class="{ 'has-save-error': saveFailed }">
              <button ref="moreTrigger" class="icon-act" title="更多文档操作" aria-label="更多文档操作" aria-haspopup="menu" :aria-expanded="moreMenuOpen" @click="shareMenu = false; moreMenuOpen = !moreMenuOpen"><i class="ri-more-2-fill"></i></button>
              <div v-if="moreMenuOpen" class="menu-mask" @click="moreMenuOpen = false"></div>
              <div v-if="moreMenuOpen" ref="morePanel" class="doc-more-menu" aria-label="更多文档操作" @keydown="onDocumentMenuKeydown($event, 'more')">
                <div v-if="saving || saveFailed || docSavedAt" class="more-save"><SaveHint :saving="saving" :saved-at="docSavedAt" :failed="saveFailed" /></div>
                <label v-if="docId && !currentArchived" class="move-document more-move">移动到<select :value="currentFolderId" :disabled="treeSaveState === 'saving'" aria-label="在更多菜单中移动当前文档到文件夹" @change="moveCurrentDoc($event); moreMenuOpen = false"><option v-for="folder in kb.folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label>
                <button v-if="pageType === 'document'" @click="moreMenuOpen = false; documentEditor?.openSearch()"><i class="ri-search-line"></i>查找文档内容</button>
                <button v-if="pageType === 'document'" @click="copyMarkdown"><i class="ri-markdown-line"></i>复制 Markdown</button>
                <button v-if="pageType === 'document'" @click="exportMarkdown"><i class="ri-download-2-line"></i>导出 Markdown</button>
                <button v-if="pageType === 'document'" @click="moreMenuOpen = false; openSaveTemplate()"><i class="ri-file-copy-2-line"></i>另存为模板</button>
                <button v-if="pageType === 'document'" @click="moreMenuOpen = false; shortcutsOpen = true"><i class="ri-keyboard-line"></i>快捷键</button>
                <button class="more-share" @click="copyOriginalLink"><i class="ri-file-text-line"></i>复制知识库原文链接</button>
                <button v-if="pageType !== 'drawing'" class="more-share" @click="moreMenuOpen = false; openDocumentShare()"><i class="ri-links-line"></i>限时分享设置</button>
                <button v-if="docId" @click="moreMenuOpen = false; currentArchived ? unarchiveDoc(docId) : archiveDoc(docId)"><i :class="currentArchived ? 'ri-inbox-unarchive-line' : 'ri-archive-line'"></i>{{ currentArchived ? '恢复到原文件夹' : '归档文档' }}</button>
                <button class="danger" @click="moreMenuOpen = false; removeDoc(docId!)"><i class="ri-delete-bin-line"></i>删除这篇文档</button>
              </div>
            </div>
          </div>

      <div ref="toolbarHost" v-show="!reading" class="document-toolbar"></div>
      <div class="document-content" :class="{ 'has-outline': doc && outlineVisible && pageType === 'document' }">
    <div ref="scrollEl" class="doc-scroll" :inert="isMobile && mobileTocOpen ? true : undefined" :class="{ 'library-home-scroll': !docId }" @scroll.passive="onDocScroll">
      <div class="doc-wrap">
        <div class="doc-col">
          <section v-if="!docId" class="library-home">
            <header><button class="library-cover-icon" :style="{ color: kb.color }" title="更换知识库图标" aria-label="更换知识库图标" @click="openKbIconSettings"><i :class="kb.icon" aria-hidden="true"></i></button><div class="library-heading"><h1>{{ kb.name }}</h1><p>{{ docTotal }} 文档</p></div><button v-if="canManageKb" class="library-star" title="知识库设置" @click="openKbSettings"><i class="ri-settings-3-line"></i>设置</button></header>
            <p v-if="kb.desc" class="library-description">{{ kb.desc }}</p>
            <div class="home-tools">
              <label class="home-search"><i class="ri-search-line" aria-hidden="true"></i><input v-model="treeQuery" type="search" aria-label="搜索首页文档" placeholder="搜索文件夹或文档标题" @keydown.esc="treeQuery = ''" /></label>
              <select v-model="homeOrder" aria-label="首页文档排序"><option value="directory">按目录</option><option value="updated">最近更新</option><option value="title">按名称</option></select>
              <button class="library-star" :disabled="creatingDoc" @click="createAction('doc')"><i class="ri-add-line" aria-hidden="true"></i>新建文档</button>
            </div>
            <div v-if="data.loadStates.docs === 'error'" class="home-load-error" role="alert"><span>目录加载失败：{{ data.loadErrors.docs || '请检查网络后重试' }}</span><button class="library-star" @click="data.refreshDocs()">重试</button></div>
            <div v-else-if="data.loadStates.docs !== 'loaded'" class="doc-empty" role="status">正在加载目录…</div>
            <div v-else-if="!docTotal" class="doc-empty">从第一篇文档开始整理知识。</div>
            <div v-if="docTotal" class="home-list-heading"><span class="home-result-count" role="status">{{ homeSearch ? `找到 ${homeResultCount} 篇文档` : `${docTotal} 篇文档` }}</span><button v-if="!homeSearch && homeGroups.length" class="home-collapse" :aria-expanded="allHomeGroupsOpen" @click="toggleHomeGroups">{{ allHomeGroupsOpen ? '折叠全部分组' : '展开全部分组' }}</button><button v-if="homeSearch" class="home-clear-search" @click="treeQuery = ''">清空搜索</button></div>
            <p v-if="homeSearch && !homeGroups.length && data.loadStates.docs === 'loaded'" class="doc-empty">没有匹配的文件夹或文档，试试其他关键词。</p>
            <div v-if="homeOrder === 'directory'" class="home-documents home-directory-index">
              <KnowledgeDirectoryTree :nodes="homeDirectoryRows" :expanded="homeFolderOpen" :searching="!!homeSearch" label="知识库首页目录" :icon-of="pageIcon" density="comfortable" @open="selectDoc" @toggle="(key, value) => homeFolderOpen[key] = value" @preload="preloadSheet">
                <template #title="{ node }"><span :class="{ 'home-document-name': node.kind === 'doc' }">{{ node.title }}</span></template>
                <template #suffix="{ node }"><span v-if="node.kind === 'doc'" class="home-document-leader" aria-hidden="true"></span><time v-if="node.kind === 'doc'" :title="absoluteTime(data.docById(node.id)?.updated ?? '')">{{ homeDocumentTime(data.docById(node.id)?.updated ?? '') }}</time></template>
              </KnowledgeDirectoryTree>
            </div>
            <template v-else><section v-for="folder in homeGroups" :key="folder.id" class="home-folder">
              <button class="home-folder-title" :aria-expanded="isHomeGroupOpen(folder.id)" :disabled="!!homeSearch" @click="homeFolderOpen[folder.id] = homeFolderOpen[folder.id] === false"><i :class="isHomeGroupOpen(folder.id) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" aria-hidden="true"></i><span>{{ folder.name }}</span><span class="home-folder-count">{{ folder.docs.length }}</span></button>
              <div v-if="isHomeGroupOpen(folder.id)" class="home-documents"><button v-for="id in folder.docs" :key="id" @pointerenter="preloadSheet(id)" @focus="preloadSheet(id)" @click="selectDoc(id)"><i :class="pageIcon(id)" aria-hidden="true"></i><span class="home-document-name" :title="documentTitle(id)">{{ documentTitle(id) }}</span><time :title="absoluteTime(data.docById(id)?.updated ?? '')">{{ homeDocumentTime(data.docById(id)?.updated ?? '') }}</time></button><p v-if="!folder.docs.length" class="folder-empty">此文件夹还没有文档</p></div>
            </section></template>
          </section>
          <template v-if="doc">
            <nav v-if="pageType === 'document' && docBreadcrumb.length" class="document-path" aria-label="文档所在目录"><template v-for="(item, index) in docBreadcrumb" :key="item.key"><i v-if="index" class="ri-arrow-right-s-line" aria-hidden="true"></i><button type="button" :title="item.title" @click="goBreadcrumb(item)">{{ item.title }}</button></template></nav>
            <textarea
              v-if="pageType === 'document'"
              ref="titleEl"
              class="doc-title"
              rows="1"
              :value="doc.title"
              :readonly="reading"
              aria-label="文档标题"
              placeholder="未命名文档"
              @input="editTitle"
              @keydown="onTitleKeydown"
            />
            <div v-if="pageType === 'document'" class="document-byline"><span>{{ author.name }}</span><span :title="absoluteTime(base?.updated ?? '')">更新于 {{ updatedLabel }}</span><span>{{ characterCount.toLocaleString('zh-CN') }} 字</span><span v-if="characterCount">约 {{ Math.max(1, Math.ceil(characterCount / 500)) }} 分钟阅读</span><span v-if="reading" class="reading-label">阅读模式</span><span v-if="saving || saveFailed || docSavedAt" class="compact-save-status"><SaveHint :saving="saving" :saved-at="docSavedAt" :failed="saveFailed" /></span></div>

            <div v-if="docLinks.length" class="doc-links">
              <span class="dl-label">关联</span>
              <button v-for="(l, i) in docLinks" :key="i" class="dl-chip" @click="goLink(l.link)">
                <i :class="l.icon"></i><span class="ellip">{{ l.label }}</span>
              </button>
            </div>
            <div v-if="linkedTasks.length" class="doc-links linked-tasks">
              <span class="dl-label">关联任务</span>
              <button
                v-for="task in linkedTasks"
                :key="task.id"
                class="dl-chip"
                :title="`打开任务：${task.title}`"
                @click="openLinkedTask(task.id)"
              >
                <i class="ri-checkbox-circle-line"></i><span class="ellip">{{ task.title }}</span>
              </button>
            </div>
            <div v-if="pageType === 'document'" class="doc-hr"></div>

            <div v-if="docId && data.docConflicts[docId]" class="doc-conflict" role="alert">
              <i class="ri-git-merge-line"></i>
              <span><b>服务器已有另一版</b><small>本机草稿没有被覆盖，请选择载入服务器版，或把本机内容另存为副本。</small></span>
              <button @click="loadServerConflict">载入服务器版</button>
              <button class="primary" @click="keepConflictCopy">本机草稿另存副本</button>
            </div>
            <div v-else-if="docId && data.docDraftErrors[docId]" class="doc-draft-warning" role="alert"><i class="ri-error-warning-line"></i>浏览器暂时无法写入离线草稿；请保持页面打开，并在网络恢复后点击保存重试。</div>
            <div v-if="saveFailed && currentSaveState?.errorMessage && docId && !data.docConflicts[docId]" class="doc-draft-warning" role="alert">保存失败：{{ currentSaveState.errorMessage }}。本机草稿已保留，可点击上方“重试”。</div>

            <KnowledgeSheet v-if="pageType === 'spreadsheet'" ref="sheetEditor" :document-id="docId ?? undefined" :content="bodyContent" :disabled="!!(docId && data.docConflicts[docId])" @change="onContentChange" />
            <KnowledgeDrawing v-else-if="pageType === 'drawing' && base?.drawingId" :key="base.drawingId" :drawing-id="base.drawingId" embedded />
            <MarkdownEditor v-bind="DOCUMENT_EDITOR_FEATURES" v-else
              ref="documentEditor"
              :key="docId ?? 'empty-doc'"
              :search-request="String(route.query.findRequest ?? '')"
              :search-term="route.query.match === 'title' ? '' : typeof route.query.find === 'string' ? route.query.find : ''"
              :model-value="body"
              :document="bodyContent?.document"
              :toolbar-target="toolbarHost"
              class="doc-body"
              :readonly="reading"
              hide-character-count
              bare
              allow-h1
              advanced
              polishable
              link-controls
              image-controls
              :article-image-key="`document:${session.currentUser.id}:${docId}`"
              :note-title-on-image="!!kb?.notes"
              :note-ai-origin="kb?.notes && doc && base ? { stableId: base.id, docId: base.id, sourceRev: base.rev, title: doc.title, blocks: base.blocks, links: base.links } : undefined"
              @generated-title="docId && data.setDocEdit(docId, { title: $event })"
              @ai-applied="docId && data.setDocEdit(docId, { sourceInputIds: [...new Set([...(data.docEdits[docId]?.sourceInputIds ?? []), $event])] })"
              placeholder="开始写作…输入 / 插入内容块，支持 Markdown 和粘贴截图"
              :min-height="360"
              :peers="peers"
              @caret-line="setCaretLine"
              @character-count="characterCount = $event"
              @content-change="onContentChange"
              @add-task="addSelectionToTask"
              @upload-image="onUploadImage"
              @upload-file="onUploadFile"
            />

            <div v-if="backlinks.length" class="backlinks">
              <div class="bl-title"><i class="ri-arrow-go-back-line"></i>反向链接</div>
              <div v-for="(bl, i) in backlinks" :key="i" class="bl-row" @click="goLink(bl.link)">
                <i :class="bl.icon" class="bl-icon"></i>
                <div class="bl-main">
                  <div class="bl-label">{{ bl.label }}</div>
                  <div class="bl-snippet">{{ bl.snippet }}</div>
                </div>
                <i class="ri-arrow-right-s-line bl-chev"></i>
              </div>
            </div>
          </template>
          <div v-else-if="docId" class="doc-empty pad" role="status">
            <template v-if="data.docLoadErrors[docId]">文档正文加载失败：{{ data.docLoadErrors[docId] }} <button @click="loadSelectedDoc">重试</button></template>
            <template v-else>正在加载文档正文…</template>
          </div>
        </div>


      </div>
    </div>

        <div v-if="isMobile && doc && outlineVisible && pageType === 'document'" class="mobile-outline-mask" aria-hidden="true" @click="closeOutline" />
        <aside v-if="doc && outlineVisible && pageType === 'document'" ref="outlinePanel" class="toc" :role="isMobile ? 'dialog' : undefined" :aria-modal="isMobile ? true : undefined" aria-label="文档大纲" @keydown="navigationKeydown($event, 'outline')" @compositionstart="navigationComposition.start" @compositionend="navigationComposition.end">
          <div class="toc-label"><span>文档大纲 <small>{{ toc.length || '' }}</small></span><button class="icon-act" aria-label="关闭文档大纲" @click="closeOutline"><i class="ri-close-line"></i></button></div><p v-if="!toc.length" class="folder-empty">添加标题后会在这里显示目录。</p>
          <div class="toc-list">
            <button v-for="(t, i) in toc" :key="t.id" class="toc-item" :title="t.text || '未命名标题'" :aria-current="activeToc === i ? 'location' : undefined" :class="{ sub: t.level === 2, deep: t.level === 3, active: activeToc === i }" @click="tocGo(i)">{{ t.text || '未命名标题' }}</button>
          </div>
          <div class="outline-progress"><span>阅读进度 {{ readingProgress }}%</span><button type="button" @click="backToTop">回到顶部</button><progress :value="readingProgress" max="100" aria-label="文档阅读进度"></progress></div>
        </aside>
      </div>
    </div>

    <SettingsDialogShell v-if="kbSettingsOpen" title="知识库设置" icon="ri-settings-3-line" width="560px" height="auto" appearance="compact" :close-disabled="kbSettingsBusy || !!confirmation" @close="kbSettingsOpen = false">
      <form id="knowledge-settings-form" class="knowledge-dialog-form" @submit.prevent="saveKbSettings">
        <KnowledgeIconPicker v-model="kbSettings.icon" :disabled="kbSettingsBusy" :initially-expanded="kbIconPickerExpanded" />
        <label>名称<input :disabled="kbSettingsBusy" v-model="kbSettings.name" maxlength="128" required /></label>
        <label>简介<textarea :disabled="kbSettingsBusy" v-model="kbSettings.desc" maxlength="2000" rows="3"></textarea></label>
        <label>共享范围<select v-model="kbSettings.perm" :disabled="kbSettingsBusy"><option value="仅自己">仅自己：只有我可以查看和编辑</option><option value="成员可编辑">共享：组织内成员可以查看和编辑</option></select></label>
        <p v-if="kbSettingsError" class="dialog-error" role="alert">{{ kbSettingsError }}</p>
      </form>
      <template #footer><div class="knowledge-dialog-actions"><WaoButton v-if="!kb.notes" variant="danger" :disabled="kbSettingsBusy" @click="removeKb">删除知识库</WaoButton><span></span><WaoButton :disabled="kbSettingsBusy" @click="kbSettingsOpen = false">取消</WaoButton><WaoButton type="submit" form="knowledge-settings-form" variant="primary" :loading="kbSettingsBusy" :disabled="!kbSettings.name.trim()">保存</WaoButton></div></template>
    </SettingsDialogShell>
    <SettingsDialogShell v-if="moveDocId" title="移动文档" icon="ri-folder-transfer-line" width="480px" height="auto" appearance="compact" :close-disabled="treeSaveState === 'saving'" @close="moveDocId = null">
      <form id="knowledge-move-form" class="knowledge-dialog-form" @submit.prevent="confirmMove"><label>目标分组<select v-model="moveFolderId" :disabled="treeSaveState === 'saving'"><option v-for="folder in kb.folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label><p v-if="treeSaveState === 'error'" class="dialog-error" role="alert">{{ directoryError || '移动失败，请重试。' }}</p></form>
      <template #footer><div class="knowledge-dialog-actions"><WaoButton :disabled="treeSaveState === 'saving'" @click="moveDocId = null">取消</WaoButton><WaoButton type="submit" form="knowledge-move-form" variant="primary" :loading="treeSaveState === 'saving'" :disabled="!moveFolderId">移动</WaoButton></div></template>
    </SettingsDialogShell>
    <SettingsDialogShell v-if="newDocFolderId && templatePickerOpen" title="新建文档" description="选择空白文档，或复用已有模板的正文结构。" icon="ri-file-add-line" width="680px" height="auto" appearance="compact" :close-disabled="creatingDoc" @close="newDocFolderId = null; templatePickerOpen = false">
      <section class="template-modal template-shell-body">
        <div v-if="templateLoading" class="template-state"><i class="ri-loader-4-line spin"></i>正在加载模板…</div>
        <div v-else-if="templateError" class="template-state error">{{ templateError }}<button @click="loadTemplates">重试</button></div>
        <div v-else class="template-grid">
          <button class="template-card blank" :disabled="creatingDoc" @click="createDocFrom()"><i class="ri-file-add-line"></i><b>空白文档</b><span>从空白页面开始写作</span></button>
          <button v-for="item in templates" :key="item.id" class="template-card" :disabled="creatingDoc" @click="createDocFrom(item.id)"><i class="ri-file-copy-2-line"></i><b>{{ item.name }}</b><span>{{ item.description || `${item.blocks.length} 个正文块` }}</span></button>
        </div>
        <footer><WaoButton :disabled="creatingDoc" @click="newDocFolderId = null; templatePickerOpen = false; templateManagerOpen = true">管理模板</WaoButton><span v-if="creatingDoc" role="status">正在创建并保存…</span></footer>
      </section>
    </SettingsDialogShell>

    <SettingsDialogShell v-if="saveTemplateOpen" title="另存为模板" description="保存当前正文快照，之后新建文档可直接复用。" icon="ri-file-copy-2-line" width="520px" height="auto" appearance="compact" :close-disabled="savingTemplate" @close="saveTemplateOpen = false">
      <form id="save-document-template" class="knowledge-dialog-form" @submit.prevent="saveAsTemplate"><label>模板名称<input v-model="templateForm.name" maxlength="128" :disabled="savingTemplate" required /></label><label>使用说明<textarea v-model="templateForm.description" maxlength="512" rows="3" :disabled="savingTemplate" placeholder="例如：每天复盘时使用"></textarea></label><p v-if="saveTemplateError" class="dialog-error" role="alert">{{ saveTemplateError }}</p></form>
      <template #footer><div class="knowledge-dialog-actions"><WaoButton :disabled="savingTemplate" @click="saveTemplateOpen = false">取消</WaoButton><WaoButton type="submit" form="save-document-template" variant="primary" :disabled="!templateForm.name.trim()" :loading="savingTemplate">保存模板</WaoButton></div></template>
    </SettingsDialogShell>

    <SettingsDialogShell v-if="templateManagerOpen" title="文档模板管理" description="模板属于当前账号；删除模板不会影响已创建的文档。" icon="ri-file-copy-2-line" width="780px" height="min(760px, calc(100dvh - 48px))" appearance="compact" :close-disabled="editingTemplateSaving || templateConflictRefreshing || !!confirmation" @close="closeTemplateManager">
      <section class="template-modal manager template-shell-body">
        <div v-if="templateLoading" class="template-state"><i class="ri-loader-4-line spin"></i>正在加载模板…</div>
        <div v-else-if="templateError" class="template-state error">{{ templateError }}<button @click="loadTemplates">重试</button></div>
        <div v-else-if="!templates.length" class="template-state empty"><i class="ri-file-copy-2-line"></i><b>还没有模板</b><span>打开任意文档，点击顶部“另存为模板”即可创建。</span></div>
        <div v-else class="template-list">
          <article v-for="item in templates" :key="item.id">
            <template v-if="editingTemplateId === item.id">
              <label>模板名称<input v-model="editTemplateForm.name" maxlength="128" :disabled="editingTemplateSaving" /></label>
              <label>使用说明<textarea v-model="editTemplateForm.description" maxlength="512" rows="2" :disabled="editingTemplateSaving"></textarea></label>
              <label class="template-content-field">模板内容</label>
              <MarkdownEditor v-bind="DOCUMENT_EDITOR_FEATURES"
                v-model="editTemplateForm.content"
                :document="editTemplateRichContent?.document"
                class="template-content-editor"
                allow-h1
                advanced
                link-controls
                image-controls
                :readonly="editingTemplateSaving"
                placeholder="模板正文可以为空；支持标题、链接与图片"
                :min-height="180"
                @content-change="onTemplateContentChange"
                @upload-image="onUploadImage"
                @upload-file="onUploadFile"
              />
              <div class="template-edit-footer">
                <span class="template-edit-status" :class="templateEditState">
                  <i :class="templateEditState === 'error' || templateEditState === 'conflict' || templateDraftStorageError ? 'ri-error-warning-line' : templateEditState === 'saved' ? 'ri-checkbox-circle-line' : templateEditState === 'saving' ? 'ri-loader-4-line spin' : 'ri-draft-line'"></i>
                  {{ templateEditStatusText }}
                </span>
                <div v-if="templateEditState === 'conflict'" class="template-row-actions conflict-actions">
                  <button :disabled="templateConflictRefreshing" @click="discardConflictedTemplateDraft(item.id)">载入服务器版</button>
                  <button class="primary" :disabled="templateConflictRefreshing" @click="confirmConflictedTemplateOverwrite(item.id)">{{ templateConflictRefreshing ? '检查中…' : '确认保留本机草稿' }}</button>
                </div>
                <div v-else class="template-row-actions"><button :disabled="editingTemplateSaving" @click="finishTemplateEdit">{{ templateEditState === 'saved' ? '完成' : '稍后继续' }}</button><button class="primary" :disabled="!editTemplateForm.name.trim() || editingTemplateSaving || templateEditState === 'saved'" @click="saveTemplateMeta(item)">{{ editingTemplateSaving ? '保存中…' : templateEditState === 'error' ? '重试保存' : '保存' }}</button></div>
              </div>
            </template>
            <template v-else>
              <div class="template-summary">
                <div class="template-copy"><b>{{ item.name }}</b><span>{{ item.description || `${item.blocks.length} 个正文块` }}</span></div>
                <div class="template-row-actions"><button @click="beginEditTemplate(item)"><i class="ri-edit-line"></i>编辑内容</button><button class="danger" @click="removeTemplate(item)"><i class="ri-delete-bin-line"></i>删除</button></div>
              </div>
              <div class="template-content-preview">
                <MarkdownEditor v-bind="DOCUMENT_EDITOR_FEATURES"
                  v-if="item.blocks.length || item.content?.document"
                  :model-value="item.content?.markdown ?? blocksToText(item.blocks)"
                  :document="item.content?.document"
                  readonly
                  bare
                  allow-h1
                  advanced
                  :min-height="0"
                />
                <span v-else>暂无正文内容</span>
              </div>
            </template>
          </article>
        </div>
      </section>
    </SettingsDialogShell>
  </div>
  <SettingsDialogShell v-if="shortcutsOpen" title="文档快捷键" icon="ri-keyboard-line" width="520px" height="auto" appearance="compact" @close="shortcutsOpen = false"><dl class="document-shortcuts"><div v-for="shortcut in documentShortcuts" :key="shortcut[0]"><dt>{{ shortcut[1] }}</dt><dd><kbd>{{ shortcut[0] }}</kbd></dd></div></dl><template #footer><WaoButton @click="shortcutsOpen = false">知道了</WaoButton></template></SettingsDialogShell>
  <ConfirmActionDialog v-if="confirmation" :action="confirmation" @close="dismissConfirmation" />
  <NoteShareDialog v-if="shareNoteId" :key="shareNoteId" :note-id="shareNoteId" :note="data.docs[shareNoteId]?.kbId === 'kb-notes'" :title="data.docs[shareNoteId]?.title" @close="shareNoteId = ''" />
</template>

<style scoped>
/* 同一个按钮始终锚定工作区左上角，切换目录不移动焦点或入口。 */
.directory-toggle { position: absolute; top: 10px; left: var(--wao-space-2); z-index: 2; display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; padding: 0; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.directory-toggle:hover { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
.directory-toggle:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.side-top > .back { min-height: 28px; margin-left: 40px; }
.directory-collapsed > .document-pane > .doc-bar { padding-left: 52px; }
.directory-home-bar { flex: 0 0 48px; border-bottom: 1px solid var(--wao-line); }
.mobile-pane-mask { position:absolute; inset:0; z-index:40; background:var(--wao-mask-bg); }
.mobile-outline-mask { position:absolute; inset:0; z-index:29; background:var(--wao-mask-bg); }
.mobile-directory-close { display:flex; align-items:center; justify-content:flex-end; gap:var(--wao-space-1); min-height:44px; padding:var(--wao-space-1) var(--wao-space-2); border:0; background:var(--wao-bg-card); color:var(--wao-text); font:inherit; cursor:pointer; }
@media (pointer: coarse) {
  .directory-toggle { top: 2px; left: var(--wao-space-1); width: 44px; height: 44px; }
  .side-top > .back { margin-left: 48px; }
  .directory-collapsed > .document-pane > .doc-bar { padding-left: 60px; min-height: 48px; }
}
.editor-workspace .doc-scroll { display: flex; overflow: hidden; }
.editor-workspace .doc-wrap { flex: 1; min-width: 0; min-height: 0; padding: 0; }
.editor-workspace .doc-col { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.sheet-title { width: 220px; min-width: 60px; max-width: 100%; padding: var(--wao-space-1); border: 0; color: var(--wao-text); background: transparent; font: inherit; }

.document-save-status { flex: 0 1 150px; min-width: 0; overflow: hidden; white-space: nowrap; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
.doc-info { color: var(--wao-text-weak); cursor: help; }
.tree-search { padding: 6px 10px !important; }
.tree-search label { padding: 5px 8px !important; background: var(--wao-bg-sub); border: 0 !important; }
.tree-search input { height: 22px; font-size: var(--wao-font-body); }
.doc-row { cursor: grab; }
.doc-row:active { cursor: grabbing; }

.kb-switch { min-width: 0; flex: 1; border: 0; font-size: var(--wao-font-body); font-weight: 600; background: transparent; color: var(--wao-text); padding: 6px 0; text-overflow: ellipsis; }
.library-home-link { display: flex; align-items: center; gap: 10px; margin: 4px 10px; padding: 9px 12px; border: 0; border-radius: 7px; background: transparent; color: var(--wao-text); cursor: pointer; text-align: left; }
.library-home-link.on { background: var(--wao-bg-hover); font-weight: 600; }
.directory-heading { display: flex; align-items: center; justify-content: space-between; padding: 12px 22px 8px; font-size: var(--wao-font-body); color: var(--wao-text-weak); }
.directory-heading > span { display: flex; align-items: center; gap: 10px; }
.library-home-link { font-size: var(--wao-font-body); }
.directory-actions { display: flex; align-items: center; gap: 8px; }
.note-time-sort { max-width: 94px; min-width: 0; padding: 4px 2px; border: 1px solid var(--wao-line-control); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text-2); font-size: var(--wao-font-caption); cursor: pointer; }
.directory-heading button { border: 0; background: transparent; color: inherit; cursor: pointer; }
/* 首页使用独立纸面与紧凑目录，不改变正文阅读宽度。 */
.doc-scroll.library-home-scroll { background: var(--wao-bg-sub); }
.library-home-scroll .doc-wrap { padding: var(--wao-space-4); }
.library-home-scroll .doc-col { max-width: 1120px; min-width: 0; }
.library-home { padding: var(--wao-space-4); margin: 0 auto; width: 100%; min-height: 0; background: var(--wao-bg-card); border-radius: var(--wao-radius-card); }
.library-home header { display: flex; align-items: center; gap: var(--wao-space-2); flex-wrap: wrap; }
.library-cover-icon { padding: 0; border: 0; border-radius: var(--wao-radius-control); background: transparent; cursor: pointer; font-size: 32px; line-height: 44px; flex-shrink: 0; }
.library-cover-icon:hover { background: var(--wao-bg-hover); }
.library-cover-icon:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 4px; }
.library-heading { flex: 1; min-width: 120px; }
.library-heading h1 { font-size: var(--wao-font-title); line-height: 1.4; margin: 0 0 var(--wao-space-1); color: var(--wao-text); overflow-wrap: anywhere; }
.library-heading p { margin: 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.library-description { margin: var(--wao-space-2) 0 0; color: var(--wao-text-3); font-size: var(--wao-font-help); white-space: pre-wrap; overflow-wrap: anywhere; }
.library-star { display: inline-flex; gap: 6px; align-items: center; justify-content: center; min-height: 40px; border: 1px solid var(--wao-line); border-radius: var(--wao-radius-control); padding: 7px 10px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; white-space: nowrap; }
.library-star:hover { background: var(--wao-bg-hover); }
.library-star:disabled { opacity: 0.6; cursor: wait; }
.home-tools { display: flex; align-items: center; gap: var(--wao-space-1); flex-wrap: wrap; margin-top: var(--wao-space-3); }
.home-search { display: flex; align-items: center; gap: var(--wao-space-1); flex: 1 1 200px; min-width: 0; min-height: 40px; padding: 0 var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); color: var(--wao-text-3); }
.home-search:focus-within { border-color: var(--wao-primary); }
.home-search input { width: 100%; min-width: 0; padding: var(--wao-space-1) 0; border: 0; outline: 0; background: transparent; color: var(--wao-text); font: inherit; }
.home-tools select { min-height: 40px; max-width: 100%; padding: var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); font: inherit; }
.home-list-heading { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: var(--wao-space-1); padding: var(--wao-space-2) 0 var(--wao-space-1); color: var(--wao-text-3); font-size: var(--wao-font-help); border-bottom: 1px solid var(--wao-line); }
.home-collapse, .home-clear-search { min-height: 36px; padding: 0 var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-primary-text); cursor: pointer; }
.home-load-error { display: flex; align-items: center; gap: var(--wao-space-2); flex-wrap: wrap; padding: var(--wao-space-2) 0; color: var(--wao-danger); overflow-wrap: anywhere; }
.library-home button:focus-visible, .home-tools select:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.home-folder { margin: 0; }
.home-folder-title { display: flex; align-items: center; gap: var(--wao-space-1); width: 100%; min-height: 44px; padding: var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: none; color: var(--wao-text); font-size: var(--wao-font-body); text-align: left; cursor: pointer; overflow-wrap: anywhere; }
.home-folder-title > span:first-of-type { min-width: 0; }
.home-folder-count { margin-left: auto; color: var(--wao-text-3); font-size: var(--wao-font-caption); font-variant-numeric: tabular-nums; }
.home-folder-title:hover, .home-documents button:hover { background: var(--wao-bg-hover); }
.home-folder-title i { color: var(--wao-text-3); font-size: 14px; }
.home-documents { padding-left: 26px; }
.home-documents button { display: flex; align-items: center; gap: var(--wao-space-1); width: 100%; min-height: 44px; padding: var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: none; color: var(--wao-text-2); font-size: var(--wao-font-body); text-align: left; cursor: pointer; }
.home-documents button > i { flex-shrink: 0; color: var(--wao-text-3); }
.home-document-name { flex: 1; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.home-documents time { flex-shrink: 0; font-size: var(--wao-font-help); color: var(--wao-text-3); white-space: nowrap; }
@media (max-width: 760px) {
  .library-home-scroll .doc-wrap { padding: 16px 12px 32px; }
  .library-home { padding: 20px 14px; min-height: 0; }
  .library-home header { gap: 12px; }
  .library-heading h1 { font-size: var(--wao-font-title); }
  .library-description { margin-left: 0; }
  .home-tools .home-search { flex-basis: 100%; }
  .home-search input { font-size: var(--wao-font-card-title); }
  .home-tools select, .library-star, .home-collapse, .home-clear-search { min-height: 44px; }
  .home-documents { padding-left: 16px; }
  .home-document-name { max-width: none; }
  .home-documents time { display: none; }
}

.tree-search { padding:10px 12px; display:grid; gap:7px; font-size:var(--wao-font-body); border-bottom:1px solid var(--wao-line); }.tree-search strong { font-weight:500; color:var(--wao-text-weak); }.tree-search label { display:flex; align-items:center; gap:6px; padding:7px; border:1px solid var(--wao-line); border-radius:6px; }.tree-search input { min-width:0; width:100%; border:0; outline:0; background:none; color:var(--wao-text); }.tree-search button { justify-self:start; border:0; background:none; color:var(--wao-primary-text); cursor:pointer; }
.move-document { display:flex; align-items:center; gap:5px; font-size:var(--wao-font-caption); color:var(--wao-text-weak); }.move-document select { max-width:130px; min-width:70px; padding:5px; border:1px solid var(--wao-line); border-radius:5px; background:var(--wao-bg-card); color:var(--wao-text); }

.missing {
  padding: 60px 24px;
  text-align: center;
  color: var(--wao-text-weak);
}
.kb-detail {
  position: relative;
  display: flex;
  /* 应用壳已经占去顶栏和页签栏；锁定剩余视口后，左右两栏才能各自滚动。 */
  height: calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h));
  min-height: 0;
  overflow: hidden;
  animation: waoFade 0.2s ease;
}
.spacer {
  flex: 1;
}
.ellip {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.save-retry { border: 0; background: transparent; color: var(--wao-danger-strong); font-size: var(--wao-font-caption); font-weight: 700; cursor: pointer; }
.doc-conflict { display:flex; align-items:center; gap:10px; margin:0 0 14px; padding:11px 12px; border:1px solid color-mix(in srgb,var(--wao-danger-strong) 35%,var(--wao-line)); border-radius:10px; background:var(--wao-danger-bg); color:var(--wao-danger-strong); }
.doc-conflict > i { font-size:20px; }.doc-conflict > span { min-width:0; flex:1; display:flex; flex-direction:column; }.doc-conflict small { color:var(--wao-text-2); }
.doc-conflict button { min-height:30px; border:1px solid var(--wao-line-control); border-radius:7px; padding:0 10px; background:var(--wao-bg-card); color:var(--wao-text-2); cursor:pointer; white-space:nowrap; }.doc-conflict button.primary { border:0; background:var(--wao-primary); color:#fff; }
.doc-draft-warning { display:flex; align-items:center; gap:7px; margin:0 0 12px; padding:9px 11px; border-radius:8px; background:var(--wao-danger-bg); color:var(--wao-danger-strong); font-size:var(--wao-font-caption); }
@media (max-width: 760px) { .doc-conflict { align-items:stretch; flex-wrap:wrap; }.doc-conflict > span { flex-basis:calc(100% - 34px); }.doc-conflict button { flex:1; } }

/* 左栏 */
.side {
  position: relative;
  width: min(var(--directory-width, 262px), 35vw);
  flex-shrink: 0;
  border-right: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.side-top {
  padding: 10px 12px 8px;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  color: #7a7d89;
  font-size: var(--wao-font-caption);
  cursor: pointer;
  padding: 3px 6px;
  margin-left: -6px;
  border-radius: 6px;
}
.back:hover {
  background: var(--wao-divider);
  color: var(--wao-text-2);
}
.back i {
  font-size: 13px;
}
.side-kb {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.side-icon {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 13px;
}
.kb-del {
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  border-radius: 7px;
  color: var(--wao-text-faint);
  cursor: pointer;
  flex-shrink: 0;
}
.kb-del:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-danger-strong);
}
.side-name {
  flex: 1;
  min-width: 0;
  font-size: var(--wao-font-body);
  font-weight: 700;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.new-folder {
  margin-top: 8px;
  width: 100%;
  height: 29px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px dashed #d3d6df;
  background: none;
  border-radius: 8px;
  cursor: pointer;
  color: var(--wao-text-3);
  font-size: var(--wao-font-caption);
}
.new-folder:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
}
.new-folder i {
  font-size: 13px;
}
.side-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.tree-save { margin-top: 7px; min-height: 24px; display: flex; align-items: center; gap: 5px; color: var(--wao-text-weak); font-size: var(--wao-font-caption); }
.tree-save.saved { color: var(--wao-success-strong); }
.tree-save.error { color: var(--wao-danger-strong); }
.tree-save button { border: 0; background: none; color: inherit; text-decoration: underline; cursor: pointer; padding: 0; }
.tree {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 4px 8px 8px;
}
.folder-row {
  display: flex;
  align-items: center;
  margin-top: 4px;
}
.folder-btn {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 6px;
  cursor: pointer;
  min-width: 0;
}
.folder-btn:hover {
  background: var(--wao-bg-tab);
}
.folder-input {
  flex: 1;
  min-width: 0;
  height: 27px;
  border: 1px solid var(--wao-primary);
  border-radius: 7px;
  padding: 0 8px;
  font-size: var(--wao-font-body);
  font-weight: 600;
  color: var(--wao-text);
  outline: none;
  background: var(--wao-bg-card);
}
.chev {
  font-size: var(--wao-font-body);
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.folder-icon {
  font-size: 14px;
  color: #b9a15e;
  flex-shrink: 0;
}
.folder-name {
  flex: 1;
  text-align: left;
  font-size: var(--wao-font-body);
  font-weight: 600;
  color: var(--wao-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.folder-count {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
/* 行内操作按钮：平时淡出，鼠标进到这一行才明显起来 */
.row-act {
  width: 22px;
  height: 22px;
  border: none;
  background: none;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-text-faint-2);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.12s;
}
.folder-row:hover .row-act,
.doc-row:hover .row-act {
  opacity: 1;
}
.row-act:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary-text);
}
.row-act.danger:hover {
  background: var(--wao-danger-bg);
  color: #bc4141;
}
.row-act i {
  font-size: 13px;
}
.doc-tree {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 1px 0 2px;
}
.doc-row {
  display: flex;
  align-items: center;
}
.doc-row.dragging { opacity: .38; }
.drag-handle { flex: none; color: var(--wao-text-faint); cursor: grab; font-size: var(--wao-font-body); }
.drag-handle:active { cursor: grabbing; }
.doc-node {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  border-radius: 7px;
  padding: 5.5px 6px 5.5px 28px;
  cursor: pointer;
  background: transparent;
}
.doc-node:hover {
  background: var(--wao-bg-tab);
}
.doc-node.on {
  background: var(--wao-primary-soft);
}
.doc-node-icon {
  font-size: 13px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.doc-node.on .doc-node-icon {
  color: var(--wao-primary-text);
}
.doc-node-title {
  flex: 1;
  text-align: left;
  font-size: var(--wao-font-body);
  color: var(--wao-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.doc-node.on .doc-node-title {
  color: var(--wao-primary-text);
  font-weight: 600;
}
.folder-empty {
  padding: 4px 6px 4px 28px;
  font-size: var(--wao-font-help);
  color: var(--wao-text-faint-2);
}
.archive-tree {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--wao-divider);
}
.archive-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: 7px;
  background: none;
  padding: 6px;
  color: var(--wao-text-2);
  font-size: var(--wao-font-body);
  font-weight: 600;
  cursor: pointer;
}
.archive-head:hover { background: var(--wao-bg-tab); }
.archive-head span:first-of-type { flex: 1; text-align: left; }
.archive-state { padding: 7px 8px 7px 28px; color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.archive-state.error { color: var(--wao-danger-strong); }
.archive-state button { margin-left: 5px; border: 0; background: none; color: inherit; text-decoration: underline; cursor: pointer; }
.archived-row .doc-node { padding-left: 28px; }
.sync-bar {
  border-top: 1px solid var(--wao-line);
  padding: 10px 12px;
  background: var(--wao-bg-sub);
}
.sync-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--wao-font-body);
  color: var(--wao-text-2);
}
.sync-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wao-success);
  animation: waoPulse 2s infinite;
}
.sync-sub {
  margin-top: 3px;
  padding-left: 14px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
}
.link {
  border: none;
  background: none;
  color: var(--wao-primary-text);
  font-size: var(--wao-font-caption);
  cursor: pointer;
  padding: 0;
}

/* 桌面悬停显示行操作；键盘焦点、已打开菜单和触屏始终有入口。 */
@media (hover: hover) and (pointer: fine) {
  :is(.folder-row, .doc-row) :deep(.resource-trigger) { opacity: 0; pointer-events: none; }
  :is(.folder-row, .doc-row):is(:hover, :focus-within, :has(.resource-trigger[aria-expanded="true"])) :deep(.resource-trigger) { opacity: 1; pointer-events: auto; }
}
/* 文档区 */
.document-pane { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; background: var(--wao-bg-card); container: document-pane / inline-size; }
.document-pane > .doc-bar { flex: 0 0 auto; padding: 10px 16px; border-bottom: 1px solid var(--wao-divider); }
.document-content { flex: 1; min-height: 0; position: relative; display: flex; }
.document-toolbar { flex: 0 0 auto; }
.doc-scroll {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--wao-bg-card);
}
.doc-wrap {
  display: flex;
  justify-content: center;
  gap: 26px;
  padding: 28px 36px 64px;
}
.doc-col {
  flex: 1;
  min-width: min(420px, 100%);
  max-width: none;
}
.document-pane.content-narrow .doc-scroll:not(.library-home-scroll) .doc-col { max-width: 650px; }
.doc-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
  flex-wrap: nowrap;
  min-width: 0;
}
.doc-location { display: flex; align-items: center; flex: 1 1 auto; min-width: 0; gap: 8px; overflow: hidden; white-space: nowrap; }
.doc-location .library-name { flex: 0 1 auto; max-width: 90px; }
.doc-location .document-name { flex: 1 1 auto; min-width: 0; }
.doc-location :is(.tag-auto, .tag-archived) { flex: none; }
.doc-bar > :is(.collab, .realtime, .bar-move, .outline-toggle, .doc-info, .bar-sync, .save-retry, .bar-favorite, .bar-share, .doc-more-wrap) { flex-shrink: 0; }
.bar-sync { display: inline-flex; max-width: 195px; min-width: 0; overflow: hidden; }
.bar-sync :deep(.weknora-status) { flex-wrap: nowrap; white-space: nowrap; min-width: 0; }
.bar-sync :deep(a) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.doc-more-wrap { position: relative; }
.doc-more-wrap.has-save-error > button { border-color: var(--wao-danger-strong); color: var(--wao-danger-strong); }
.doc-more-menu { position: absolute; right: 0; top: 34px; z-index: 89; display: flex; flex-direction: column; gap: 2px; width: min(280px, calc(100vw - 24px)); max-height: min(70dvh, 440px); overflow-y: auto; padding: 6px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop-2); }
.doc-more-menu > button { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px; border: 0; border-radius: 6px; background: transparent; color: var(--wao-text-2); font-size: var(--wao-font-body); text-align: left; cursor: pointer; }
.doc-more-menu > button:hover { background: var(--wao-bg-hover); }
.doc-more-menu > button.danger { color: var(--wao-danger-strong); }
.doc-more-menu > :is(.more-save, .more-sync, .more-move) { padding: 7px 8px; }
.doc-more-menu .more-move { justify-content: space-between; }
.doc-more-menu .more-sync { border-bottom: 1px solid var(--wao-line); }
.doc-more-menu :deep(.weknora-status) { gap: 6px; }
.more-move, .more-share { display: none !important; }
@container document-pane (max-width: 980px) {
  .document-save-status, .doc-info, .bar-favorite, .bar-move { display: none; }
  .more-move { display: flex !important; }
  .doc-location .library-name { max-width: 60px; }
}
@container document-pane (max-width: 700px) {
  .bar-sync, .realtime, .bar-share { display: none; }
  .more-share { display: flex !important; }
  .doc-location .library-name, .doc-location .b-sep { display: none; }
}
@container document-pane (max-width: 420px) {
  .collab, .outline-toggle span { display: none; }
  .icon-act.outline-toggle { width: 28px; padding: 0; }
}
.b-sep {
  font-size: var(--wao-font-body);
  color: var(--wao-text-faint-2);
}
.tag-auto {
  font-size: var(--wao-font-caption);
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 4px;
  padding: 1px 6px;
}
.tag-archived {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-line-control);
  border-radius: 4px;
  padding: 1px 6px;
}
.collab {
  display: flex;
  align-items: center;
  margin-right: 2px;
}
.c-av {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  color: #fff;
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #fff;
}
.c-av.peer {
  margin-left: -5px;
}
.realtime {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--wao-font-caption);
  color: var(--wao-success-strong);
  white-space: nowrap;
}
.rt-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--wao-success);
  animation: waoPulse 2s infinite;
}
.icon-act {
  width: 28px;
  height: 28px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-act.outline-toggle { width: auto; padding: 0 8px; gap: 4px; white-space: nowrap; flex-shrink: 0; }
.icon-act:hover {
  background: var(--wao-bg-hover);
}
.icon-act.danger:hover {
  background: var(--wao-danger-bg);
  border-color: #f0c9c9;
  color: #bc4141;
}
.icon-act.faved,
.icon-act.faved:hover {
  color: #b9821f;
  border-color: #e5d3a8;
  background: var(--wao-warning-bg);
}
/* 分享菜单（样式与 TaskDrawer 的分享菜单保持一致手感） */
.share-wrap {
  position: relative;
}
.menu-mask {
  position: fixed;
  inset: 0;
  z-index: 88;
}
.share-menu {
  position: absolute;
  right: 0;
  top: 34px;
  z-index: 89;
  width: 236px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop-2);
  padding: 5px;
  animation: waoPop 0.14s ease;
}
.share-scope {
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
  padding: 5px 8px 6px;
  border-bottom: 1px solid var(--wao-divider);
  margin-bottom: 3px;
}
.share-item {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 8px;
  cursor: pointer;
  text-align: left;
}
.share-item:hover {
  background: var(--wao-bg-hover-2);
}
.share-item > i {
  font-size: 15px;
  color: var(--wao-text-3);
  margin-top: 1px;
}
.share-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.share-main b {
  font-size: var(--wao-font-body);
  font-weight: 500;
  color: var(--wao-text);
}
.share-main em {
  font-size: var(--wao-font-caption);
  font-style: normal;
  color: var(--wao-text-weak);
}
.icon-act i {
  font-size: 13px;
}
.doc-title {
  margin-top: 14px;
  width: 100%;
  border: none;
  outline: none;
  font-size: calc(var(--wao-font-reading) * 2);
  font-weight: 700;
  color: var(--wao-text);
  background: none;
  padding: 2px 0;
  border-radius: var(--wao-radius-control);
  display: block;
  resize: none;
  overflow: hidden;
  min-height: 42px;
  font-family: inherit;
  line-height: 1.4;
  overflow-wrap: anywhere;
}
.doc-title:focus {
  background: var(--wao-bg-sub);
  box-shadow: 0 0 0 3px var(--wao-bg-sub);
}
.doc-title::placeholder {
  color: var(--wao-text-faint-2);
}
.doc-meta {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
}
.m-av {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: #fff;
  font-size: 10.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.m-name {
  color: var(--wao-text-2);
}
.doc-links {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.dl-label {
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
}
.dl-chip {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-sub);
  border-radius: 999px;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 280px;
}
.dl-chip:hover {
  border-color: #c7d0f1;
  color: var(--wao-primary-text);
  background: var(--wao-primary-tint);
}
.dl-chip i {
  font-size: 13px;
}
.doc-hr {
  margin-top: 20px;
  border-top: 1px solid var(--wao-divider);
}
/* 正文按文档的字号排版，比任务描述宽松一档 */
.doc-body {
  margin-top: 12px;
}
.doc-body :deep(.tiptap) {
  font-size: var(--wao-font-reading);
  line-height: 1.85;
  color: var(--wao-text);
  padding: 4px 0;
}
.doc-body :deep(h2) {
  font-size: 1.25em;
  margin: 26px 0 6px;
}
.doc-body :deep(h1) {
  font-size: 1.5em;
  margin: 30px 0 8px;
}
.doc-body :deep(h3) {
  font-size: 1.125em;
}
.doc-empty {
  margin-top: 26px;
  color: var(--wao-text-3);
  font-size: var(--wao-font-help);
}
.doc-empty.pad {
  padding: 40px 0;
}
.backlinks {
  margin-top: 36px;
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-divider);
  border-radius: 12px;
  padding: 14px 16px;
}
.bl-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--wao-font-body);
  font-weight: 600;
  color: var(--wao-text-2);
}
.bl-title i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.bl-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px;
  margin: 0 -8px;
  border-radius: 8px;
  cursor: pointer;
}
.bl-row:hover {
  background: var(--wao-bg-hover-2);
}
.bl-icon {
  font-size: 14px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.bl-main {
  flex: 1;
  min-width: 0;
}
.bl-label {
  font-size: var(--wao-font-help);
  color: var(--wao-primary-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bl-snippet {
  font-size: var(--wao-font-caption);
  color: var(--wao-text-weak);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bl-chev {
  font-size: var(--wao-font-body);
  color: var(--wao-text-faint-2);
  flex-shrink: 0;
}

/* TOC */
.toc {
  position: relative;
  flex: 0 0 208px;
  width: 208px;
  max-width: 100%;
  max-height: 100%;
  box-sizing: border-box;
  overflow: auto;
  padding: var(--wao-space-3) var(--wao-space-2);
  border-left: 1px solid var(--wao-divider);
  background: var(--wao-bg-card);
}
.toc-label {
  display: flex; align-items: center; justify-content: space-between;
  font-size: var(--wao-font-help);
  color: var(--wao-text-3);
  margin-bottom: 8px;
}
.toc-list {
  border-left: 2px solid var(--wao-divider);
  display: flex;
  flex-direction: column;
}
.toc-item {
  border: none;
  background: none;
  text-align: left;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
  cursor: pointer;
  padding: 5px 10px;
  border-left: 2px solid transparent;
  margin-left: -2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.toc-item:hover {
  color: var(--wao-primary-text);
  border-left-color: var(--wao-primary);
}
.toc-item.sub { padding-left: 22px; font-size: var(--wao-font-caption); }
.toc-item.deep { padding-left: 34px; font-size: var(--wao-font-caption); color: var(--wao-text-weak); }
.toc-item.active { color: var(--wao-primary-text); border-left-color: var(--wao-primary); background: var(--wao-primary-soft); }

/* 文档模板弹窗 */
.modal-backdrop { position: fixed; inset: 0; z-index: 1100; display: grid; place-items: center; padding: 20px; background: rgba(13, 17, 35, .56); backdrop-filter: blur(3px); }
.template-modal { width: min(680px, 100%); max-height: calc(100vh - 40px); overflow: auto; border: 1px solid var(--wao-line); border-radius: 18px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-modal); padding: 22px; }
.template-modal.compact { width: min(520px, 100%); }
.template-modal.manager { width: min(780px, 100%); }
.template-modal header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.template-modal h2 { margin: 0; color: var(--wao-text); font-size: var(--wao-font-title); }
.template-modal header p { margin: 5px 0 0; color: var(--wao-text-weak); font-size: var(--wao-font-caption); }
.template-modal header > button { width: 32px; height: 32px; flex: none; border: 0; border-radius: 50%; background: var(--wao-bg-sub); color: var(--wao-text-3); cursor: pointer; }
.template-modal label { display: flex; flex-direction: column; gap: 7px; margin-top: 14px; color: var(--wao-text-2); font-size: var(--wao-font-help); font-weight: 600; }
.template-modal input, .template-modal textarea { width: 100%; border: 1px solid var(--wao-line-control); border-radius: 9px; background: var(--wao-bg-card); color: var(--wao-text); padding: 10px 11px; font: inherit; resize: vertical; }
.template-modal footer { display: flex; justify-content: flex-end; align-items: center; gap: 9px; min-height: 34px; margin-top: 18px; color: var(--wao-text-weak); font-size: var(--wao-font-caption); }
.template-modal footer .link-button { margin-right: auto; border: 0; background: none; color: var(--wao-primary-text); cursor: pointer; }
.template-modal button.primary, .template-modal footer .primary { border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; padding: 9px 14px; cursor: pointer; }
.template-modal button.secondary { border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); padding: 8px 14px; cursor: pointer; }
.template-modal button:disabled { opacity: .55; cursor: wait; }
.template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 10px; }
.template-card { min-height: 118px; display: flex; flex-direction: column; align-items: flex-start; gap: 7px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); padding: 15px; text-align: left; cursor: pointer; }
.template-card:hover { border-color: var(--wao-primary); background: var(--wao-primary-tint); }
.template-card > i { font-size: 21px; color: var(--wao-primary-text); }
.template-card > b { color: var(--wao-text); font-size: var(--wao-font-body); }
.template-card > span { color: var(--wao-text-weak); font-size: var(--wao-font-caption); line-height: 1.5; }
.template-card.blank { border-style: dashed; }
.template-state { min-height: 160px; display: flex; align-items: center; justify-content: center; gap: 8px; color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.template-state.error { color: var(--wao-danger-strong); }
.template-state.error button { border: 0; background: none; color: inherit; text-decoration: underline; cursor: pointer; }
.template-state.empty { flex-direction: column; }
.template-state.empty i { font-size: 30px; color: var(--wao-text-faint); }
.template-list { display: flex; flex-direction: column; gap: 9px; }
.template-list article { display: grid; gap: 12px; border: 1px solid var(--wao-line); border-radius: 11px; padding: 14px; }
.template-summary { display: flex; align-items: center; gap: 12px; }
.template-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.template-copy b { color: var(--wao-text); font-size: var(--wao-font-body); }
.template-copy span { color: var(--wao-text-3); font-size: var(--wao-font-body); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.template-content-preview { max-height: 230px; overflow: auto; padding: 11px 12px; border: 1px solid var(--wao-divider); border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: var(--wao-font-caption); overscroll-behavior-y: contain; }
.template-content-preview :deep(.tiptap) { font-size: var(--wao-font-help); line-height: 1.65; }
.template-content-field { margin-bottom: -5px; }
.template-content-editor { border: 1px solid var(--wao-line-control); border-radius: 9px; }
.template-content-editor :deep(.tiptap) { max-height: 340px; overflow: auto; overscroll-behavior-y: contain; }
.template-edit-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.template-edit-status { display: inline-flex; align-items: center; gap: 5px; color: var(--wao-warning-strong); font-size: var(--wao-font-help); line-height: 1.45; }
.template-edit-status.saved { color: var(--wao-success-strong); }
.template-edit-status.error, .template-edit-status.conflict { color: var(--wao-danger-strong); }
.template-row-actions { display: flex; justify-content: flex-end; gap: 6px; }
.template-row-actions button { border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-3); padding: 6px 9px; cursor: pointer; }
.template-row-actions button.danger { color: var(--wao-danger-strong); }
@media (max-width: 620px) {
  .template-edit-footer { align-items: flex-start; flex-direction: column; }
  .template-row-actions { align-self: flex-end; }
}
.spin { display: inline-block; animation: waoSpin .9s linear infinite; }

@container kb-workspace (max-width: 760px) {
  .side {
    position:absolute;
    inset:0 auto 0 0;
    width:min(320px, calc(100% - 32px));
    max-height:100%;
    z-index:41;
    box-shadow:var(--wao-shadow-pop);
  }
  .side-top > .back { margin-left:0; min-height:44px; }
  .directory-toggle { top:2px; left:var(--wao-space-1); width:44px; height:44px; }
  .document-pane > .doc-bar { padding-left:60px; min-height:48px; }
  .toc { width:min(320px, calc(100% - 24px)); }
  .toc-item { min-height:44px; white-space:normal; overflow-wrap:anywhere; }
  .toc-label .icon-act, .icon-act.outline-toggle { min-width:44px; min-height:44px; }
  .doc-scroll {
    min-height: 0;
  }
  .doc-wrap {
    padding: 16px 12px 40px;
  }
  .doc-col {
    min-width: 0;
  }
  .doc-head {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .doc-actions {
    width: 100%;
    flex-wrap: wrap;
  }
  .doc-actions button {
    flex: 1;
    justify-content: center;
  }
}
.tree-search { display: flex; align-items: center; gap: 6px; }
.tree-search label { flex: 1; min-width: 0; }

/* 语雀式目录：缩进属于整行，展开位固定宽度，选中和拖放覆盖整行。 */
.tree { padding: 4px 10px 8px; }
.directory-row {
  position: relative;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  width: 100%;
  height: 38px;
  margin: 0;
  padding: 0 4px 0 calc(4px + var(--node-depth) * 18px);
  border-radius: 7px;
  cursor: grab;
}
.directory-row:active { cursor: grabbing; }
.directory-row:hover, .directory-row:focus-within { background: var(--wao-bg-hover); }
.directory-row.selected, .directory-row.selected:hover { background: var(--wao-primary-soft); }
.directory-row.drag-source { opacity: .4; }
.tree-caret, .tree-caret-spacer { flex: 0 0 20px; width: 20px; height: 28px; }
.tree-caret { display: grid; place-items: center; padding: 0; border: 0; border-radius: 5px; background: transparent; cursor: pointer; }
.tree-caret:hover { background: var(--wao-bg-hover-2); }
.directory-row .folder-btn, .directory-row .doc-node {
  flex: 1 1 auto;
  height: 34px;
  padding: 0 4px;
  gap: 0;
  text-align: left;
  background: transparent;
}
.directory-row .folder-btn:hover, .directory-row .doc-node:hover,
.directory-row .doc-node.on { background: transparent; }
.directory-row .folder-name, .directory-row .doc-node-title { font-size: var(--wao-font-body); font-weight: 400; }
.directory-row.selected .doc-node-title { font-weight: 600; }
.directory-load-more { display: block; width: 100%; min-height: 40px; padding: var(--wao-space-1); border: 0; background: transparent; color: var(--wao-primary-text); font-size: var(--wao-font-help); text-align: left; cursor: pointer; }
.directory-load-more:hover { background: var(--wao-bg-hover); }
.tree-row-actions {
  position: absolute;
  right: 3px;
  top: 4px;
  display: flex;
  align-items: center;
  height: 30px;
  padding-left: 8px;
  background: var(--wao-bg-hover);
  border-radius: 6px;
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
}
.directory-row.selected .tree-row-actions { background: var(--wao-primary-soft); }
.directory-row:hover .tree-row-actions,
.directory-row:focus-within .tree-row-actions,
.directory-row:has(.resource-trigger[aria-expanded="true"]) .tree-row-actions { opacity: 1; visibility: visible; pointer-events: auto; }
.directory-row.drop-before::before, .directory-row.drop-after::after {
  content: '';
  position: absolute;
  z-index: 2;
  left: calc(4px + var(--node-depth) * 18px);
  right: 4px;
  height: 2px;
  border-radius: 2px;
  background: var(--wao-primary);
}
.directory-row.drop-before::before { top: 0; }
.directory-row.drop-after::after { bottom: 0; }
.directory-row.drop-inside { outline: 2px solid var(--wao-primary); outline-offset: -2px; background: var(--wao-primary-soft); }
.root-drop { margin: 2px 0 6px; padding: 9px; border: 1px dashed var(--wao-primary); border-radius: 7px; color: var(--wao-primary-text); font-size: var(--wao-font-caption); text-align: center; }
@media (hover: none), (pointer: coarse) { .tree-row-actions { opacity: 1; visibility: visible; pointer-events: auto; } }
.directory-search-count { margin: 0; padding: 4px var(--wao-space-2); font-size: var(--wao-font-caption); color: var(--wao-text-3); }
.tree-search label:focus-within { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
.directory-row [data-node]:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
.directory-row .doc-node { gap: 6px; }
.home-documents.home-directory-index { padding-left: 0; }
.home-directory-index :deep(.tree-node) { gap: var(--wao-space-1); }
.home-directory-index :deep(.node-title) { flex: 0 1 auto; }
.home-document-leader { flex: 1; min-width: var(--wao-space-2); border-bottom: 1px dashed var(--wao-line); }
.home-directory-index time { flex-shrink: 0; color: var(--wao-text-3); font-size: var(--wao-font-caption); white-space: nowrap; }

/* A quiet reading surface, with the same live editor and save lifecycle. */
.document-mode { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; flex-shrink: 0; padding: 0 var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-2); font: inherit; cursor: pointer; }
.document-mode.selected { background: var(--wao-primary-soft); border-color: var(--wao-primary-line); color: var(--wao-primary-text); }
.document-focused > .document-pane > .doc-bar { padding-left: var(--wao-space-2); }
.document-focused .doc-scroll:not(.library-home-scroll) .doc-col { max-width: 920px; }
.document-focused .document-pane.content-narrow .doc-col { max-width: 650px; }
.document-reading .doc-title:focus { background: transparent; box-shadow: none; }
.document-path { display: flex; align-items: center; gap: 4px; min-width: 0; flex-wrap: wrap; margin-bottom: var(--wao-space-2); color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.document-path button, .breadcrumb-link { border: 0; background: transparent; color: inherit; font: inherit; cursor: pointer; padding: 2px 0; text-align: left; }
.document-path button { max-width: 100%; overflow-wrap: anywhere; }
.document-path button:hover, .breadcrumb-link:hover { color: var(--wao-primary-text); text-decoration: underline; }
.document-byline { display: flex; flex-wrap: wrap; align-items: center; gap: 4px var(--wao-space-2); margin-top: var(--wao-space-1); color: var(--wao-text-3); font-size: var(--wao-font-caption); line-height: 1.7; }
.reading-label { color: var(--wao-primary-text); }
.compact-save-status { display: none; }
@container document-pane (max-width: 980px) { .compact-save-status { display: inline-flex; flex-basis: 100%; } }
.doc-body :deep(h1), .doc-body :deep(h2), .doc-body :deep(h3) { scroll-margin-top: var(--wao-space-3); line-height: 1.5; }
.toc-item.deep { color: var(--wao-text-3); }
.toc-item { min-height: 32px; font-size: var(--wao-font-help); }
.toc-label small { margin-left: 4px; font: inherit; }
.outline-progress { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: var(--wao-space-1); margin-top: var(--wao-space-3); color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.outline-progress button { padding: 4px 0; border: 0; background: transparent; color: var(--wao-primary-text); font: inherit; cursor: pointer; }
.outline-progress progress { width: 100%; height: 4px; accent-color: var(--wao-primary); }
.document-shortcuts { display: grid; gap: var(--wao-space-2); padding: var(--wao-space-3); margin: 0; }
.document-shortcuts > div { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: var(--wao-space-1); }
.document-shortcuts dd { margin: 0; }
.document-shortcuts kbd { display: inline-block; padding: 4px var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-sub); color: var(--wao-text-2); font-size: var(--wao-font-help); }
.document-pane button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@container document-pane (max-width: 1000px) {
  .toc { position: absolute; z-index: 30; inset: var(--wao-space-1) var(--wao-space-1) auto auto; width: 240px; max-width: calc(100% - var(--wao-space-2)); max-height: calc(100% - var(--wao-space-2)); border: 1px solid var(--wao-line); border-radius: var(--wao-radius-control); box-shadow: var(--wao-shadow-pop); padding: var(--wao-space-2); }
  .document-content.has-outline .doc-scroll { padding-right: 0; }
}
@container document-pane (max-width: 520px) {
  .home-directory-index :deep(.node-title) { flex: 1; }
  .home-documents time, .home-document-leader { display: none; }
  .doc-bar { gap: 6px; flex-wrap: wrap; }
  .doc-location { flex-basis: 100%; min-height: 24px; }
  .document-focused .doc-location { flex-basis: 100%; }
  .doc-bar :deep(.note-width-switch) { margin-right: auto; }
  .doc-wrap { padding: var(--wao-space-3) var(--wao-space-2) var(--wao-space-4); }
  .doc-title { font-size: calc(var(--wao-font-reading) * 1.5); }
}
@media (pointer: coarse) {
  .directory-row { height: 48px; padding-left: calc(4px + min(var(--node-depth), 3) * 12px); padding-right: 100px; }
  .directory-row .folder-btn, .directory-row .doc-node { min-height: 44px; }
  .tree-caret, .tree-caret-spacer { flex-basis: 44px; width: 44px; height: 44px; }
  .tree-row-actions { top: 2px; height: 44px; }
  .tree-row-actions :deep(.resource-trigger) { flex-basis: 44px; width: 44px; height: 44px; }
  .tree-search input, .folder-input { font-size: var(--wao-font-reading); }
  .document-mode, .icon-act, .toc-item, .document-path button, .outline-progress button, .doc-more-menu > button { min-height: 44px; }
  .icon-act { min-width: 44px; }
}
@media (prefers-reduced-motion: reduce) { .kb-detail { animation: none; } }
.directory-backdrop { position: absolute; inset: 0; z-index: 40; padding: 0; border: 0; background: var(--wao-mask-bg); cursor: pointer; }
.knowledge-dialog-form { display: grid; gap: var(--wao-space-2); padding: var(--wao-space-3); }
.knowledge-dialog-form label { display: grid; gap: var(--wao-space-1); color: var(--wao-text-2); font-size: var(--wao-font-help); }
.knowledge-dialog-form :is(input, textarea, select) { width: 100%; min-width: 0; min-height: 40px; padding: var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); color: var(--wao-text); background: var(--wao-bg-card); font: inherit; resize: vertical; }
.knowledge-dialog-form :is(input, textarea, select):focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.knowledge-dialog-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: var(--wao-space-1); }
.knowledge-dialog-actions > span { flex: 1; }
.dialog-error { margin: 0; color: var(--wao-danger-strong); overflow-wrap: anywhere; }
.template-modal.template-shell-body { width: 100%; max-height: none; border: 0; border-radius: 0; box-shadow: none; padding: var(--wao-space-3); }
@container kb-workspace (max-width: 760px) {
  .side { position: absolute; z-index: 41; inset: 0 auto 0 0; width: min(320px, calc(100% - 32px)); max-height: none; border-right: 1px solid var(--wao-line); border-bottom: 0; box-shadow: var(--wao-shadow-pop); }
  .directory-toggle { z-index: 42; }
}

</style>
