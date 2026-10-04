<script setup lang="ts">
import { usePreferenceStore } from '@/stores/preferences'
import NoteOriginalReader from '@/components/notes/NoteOriginalReader.vue'
import NoteReadingImages from '@/components/notes/NoteReadingImages.vue'
import NoteWidthSwitch from '@/components/notes/NoteWidthSwitch.vue'
import NoteAiStageButton from '@/components/notes/NoteAiStageButton.vue'
import NoteMediaCopyDialog from '@/components/notes/NoteMediaCopyDialog.vue'
import { useNoteWritingWidth } from '@/composables/noteWritingWidth'
import { publishNoteTagChange, replaceCachedNoteTags, replaceTagNames, useNoteTagChanges } from '@/utils/noteTagChanges'
import NoteAiSettingsDialog from '@/components/notes/NoteAiSettingsDialog.vue'
import { startSavedNoteStage } from '@/api/noteAiWorkflows'
import { newPolishRequestId } from '@/api/polishJobs'
import { useRoute } from 'vue-router'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { contentHit, documentSearchText } from '@/utils/contentSearch'
import { docShareUrl } from '@/utils/share'
import { copyText, copyToClipboard } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { defineAsyncComponent, computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import NoteDiscoveryInline from '@/components/notes/NoteDiscoveryInline.vue'
import NoteDiscoverySavedList from '@/components/notes/NoteDiscoverySavedList.vue'
import { useNoteDiscoveries } from '@/composables/useNoteDiscoveries'
import { fetchDiscoverySource, feedbackDiscovery, clearDiscoveryLegacyCache, type Discovery } from '@/api/noteDiscoveries'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { resolvedRichContent } from '@/utils/richDoc'
import { notePreviewImages, notePreviewSource, type NotePreviewImage } from '@/utils/notePreviewImages'
import type { Doc, RichDocContent } from '@/types'
import ImageCopyMenu from '@/components/ui/ImageCopyMenu.vue'
import { canRetryAutoNoteEnrichment, discardAutoNoteEnrichment, initializeNoteWorkflow, noteEnrichmentProgress, noteEnrichmentStatus } from '@/utils/autoNoteEnrichment'
import { latestNoteContent, type NoteDraftSnapshot } from '@/utils/latestNoteContent'
import { apiCreateNoteTag, apiDeleteNoteTag, apiMergeNoteTag, fetchNoteTags, fetchKbs, fetchDoc } from '@/api/board'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useNow } from '@/composables/now'
import { noteCharactersCreatedOn, shanghaiDateKey } from '@/utils/noteDailyCharacters'
import { readNoteDraft, removeNoteDraft } from '@/utils/noteDraftStorage'
import { fetchNoteAiWorkflows, retryNoteAiWorkflow, type NoteAiWorkflowStatus } from '@/api/noteAiWorkflows'
import { noteAiRecoveryMessage } from '@/utils/noteAiRecoveryMessage'
import { useNoteReaderWidths } from '@/composables/noteReaderWidths'
import { useNotesPaneWidth } from '@/composables/notesPaneWidth'

const contextMenu = ref<InstanceType<typeof ContextActionMenu> | null>(null)
const mediaCopyContent = ref<RichDocContent | null>(null)
function openMediaCopy(note: Doc) { mediaCopyContent.value = JSON.parse(JSON.stringify(displayedContent(note))) as RichDocContent }
const writingOpen = ref(true)
const contentWidth = useNoteWritingWidth()
const workspace = ref<HTMLElement | null>(null)
const { percentage: writingPercentage, style: writingStyle, resizing, start: startWritingResize, stop: stopWritingResize, reset: resetWritingWidth, keydown: resizeWritingByKey } = useNotesPaneWidth(workspace)
watch(writingOpen, () => stopWritingResize())

const pinnedIds = ref<string[]>([])
const pinsReady = ref(false)
const pinSaving = ref(false)
const pinError = ref('')
async function loadPins() {
  pinsReady.value = false
  pinError.value = ''
  try {
    const value: unknown = JSON.parse(await fetchPreference('note-pins-v1') || '[]')
    pinnedIds.value = Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : []
    pinsReady.value = true
  } catch { pinError.value = '置顶设置读取失败，请重试' }
}
async function togglePin(id: string) {
  if (!pinsReady.value || pinSaving.value) return
  pinSaving.value = true
  pinError.value = ''
  const next = pinnedIds.value.includes(id) ? pinnedIds.value.filter(item => item !== id) : [...pinnedIds.value, id]
  try { await savePreference('note-pins-v1', JSON.stringify(next)); pinnedIds.value = next }
  catch { pinError.value = '置顶保存失败，请再次点击重试' }
  finally { pinSaving.value = false }
}

const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const data = useDataStore()
const pagePreferences = usePreferenceStore()
const session = useSessionStore()
const now = useNow()
type NoteEditorHandle = { copyFormatted: () => Promise<void>; positionAtPoint: (left: number, top: number) => { position: number; viewportTop: number } | null; focusImageReference: (index: number) => boolean }
const noteEditors = new Map<string, NoteEditorHandle>()
const readerEditor = ref<NoteEditorHandle | null>(null)
const editAnchors = ref<Record<string, { position: number; viewportTop: number; fullscreen?: boolean } | undefined>>({})
type NoteComposerHandle = { dirty: boolean; save: () => Promise<void> }
const noteComposers = new Map<string, NoteComposerHandle>()
const retryingEnrichment = ref<string[]>([])
const serverWorkflows = ref<Record<string, NoteAiWorkflowStatus>>({})
let workflowTimer: ReturnType<typeof setInterval> | undefined
async function loadServerWorkflows() {
  if (!session.currentUser.id) { serverWorkflows.value = {}; return }
  const memberId = session.currentUser.id
  try {
    const values = await fetchNoteAiWorkflows()
    if (memberId !== session.currentUser.id) return
    const previous = serverWorkflows.value
    serverWorkflows.value = Object.fromEntries(values.map(value => [value.docId, value]))
    if (values.some(value => ['title', 'image', 'polish', 'tags'].some(stage => {
      const key = stage as 'title' | 'image' | 'polish' | 'tags'
      return value[key] === 'DONE' && previous[value.docId]?.[key] !== 'DONE'
    }))) {
      void data.refreshDocs()
      void initializeNoteWorkflow(memberId)
    }
  } catch { /* 保留已显示的阶段状态，下一轮自动重读。 */ }
}
const noteCounts = ref<Record<string, number>>({})
const notePreviewOverflowIds = ref<string[]>([])
const notePreviewBodies = new Map<string, HTMLElement>()
const notePreviewBodyIds = new WeakMap<HTMLElement, string>()
let notePreviewResizeObserver: ResizeObserver | undefined
const NOTE_PREVIEW_BODY_MAX_HEIGHT = 170
function isNotePreviewExpandable(id: string) { return notePreviewOverflowIds.value.includes(id) }
function isNotePreviewExpanded(id: string) { return isNotePreviewExpandable(id) && expandedIds.value.includes(id) }
function measureNotePreview(id: string) {
  const body = notePreviewBodies.get(id)
  if (!body) return
  const shouldCollapse = body.scrollHeight > NOTE_PREVIEW_BODY_MAX_HEIGHT
  const currentlyExpandable = isNotePreviewExpandable(id)
  if (currentlyExpandable === shouldCollapse) return
  notePreviewOverflowIds.value = shouldCollapse
    ? [...notePreviewOverflowIds.value, id]
    : notePreviewOverflowIds.value.filter(item => item !== id)
  // 编辑器重挂载时正文会短暂为空；保留用户选择，实际短文仍由 overflow 判断自然收高。
}
function scheduleNotePreviewMeasure(id: string) { void nextTick(() => measureNotePreview(id)) }
function setNotePreviewBody(id: string, value: unknown) {
  const previous = notePreviewBodies.get(id)
  const body = value instanceof HTMLElement ? value : null
  if (previous && previous !== body) notePreviewResizeObserver?.unobserve(previous)
  if (!body) {
    notePreviewBodies.delete(id)
    return
  }
  notePreviewBodies.set(id, body)
  notePreviewBodyIds.set(body, id)
  notePreviewResizeObserver?.observe(body)
  scheduleNotePreviewMeasure(id)
}
function onNoteCharacterCount(id: string, count: number) {
  noteCounts.value[id] = count
  scheduleNotePreviewMeasure(id)
}
function measureAllNotePreviews() {
  for (const id of notePreviewBodies.keys()) measureNotePreview(id)
}
const previewImage = ref<NotePreviewImage | null>(null)
const imageMenu = ref<{ src: string; x: number; y: number } | null>(null)
function openImageMenu(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof HTMLImageElement) || !target.closest('.note-image-lightbox')) return
  event.preventDefault()
  imageMenu.value = { src: target.dataset.fullSrc || target.currentSrc || target.src, x: event.clientX, y: event.clientY }
}
const lightbox = ref<HTMLElement | null>(null)
let imageTrigger: HTMLElement | null = null
async function openImage(image: NotePreviewImage, event: MouseEvent) {
  imageTrigger = event.currentTarget instanceof HTMLButtonElement ? event.currentTarget : event.target instanceof HTMLImageElement ? event.target : event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  previewImage.value = image
  await nextTick()
  lightbox.value?.focus()
}
function openInlineImage(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof HTMLImageElement) || !target.classList.contains('md-img')) return
  openImage({ src: target.dataset.fullSrc || target.currentSrc || target.src, alt: target.alt }, event)
}
async function closeImage() {
  previewImage.value = null
  await nextTick()
  imageTrigger?.focus({ preventScroll: true })
  if (reading.value && document.activeElement !== imageTrigger) readerClose.value?.focus({ preventScroll: true })
  imageTrigger = null
}
function setNoteEditor(id: string, value: unknown) { if (value) noteEditors.set(id, value as NoteEditorHandle); else noteEditors.delete(id) }
function setNoteComposer(id: string, value: unknown) {
  const composer = value as NoteComposerHandle | null
  if (composer?.save) noteComposers.set(id, composer)
  else noteComposers.delete(id)
}
const route = useRoute()
const query = ref('')
const searchFocus = ref({ id: '', term: '' })
const searchRequest = ref(0)
const sort = ref<'updatedDesc' | 'createdDesc' | 'updatedAsc'>('updatedDesc')
const imageFilter = ref<'all' | 'yes' | 'no'>('all')
const timeFilter = ref<'all' | 'today' | 'yesterday' | 'earlier'>('all')
const selectedAuthor = ref('')
const filterOpen = ref(false)
const filterControl = ref<HTMLElement>()
const selectedTag = ref<string | null>('')
const noteListPreferenceKey = `wao-note-list-preferences-v1:${session.currentUser.id}`
const noteListPreferenceLoaded = ref(false)
function restoreNoteListPreferences() {
  try {
    const saved = JSON.parse(localStorage.getItem(noteListPreferenceKey) || 'null') as Partial<{ sort: string; imageFilter: string; timeFilter: string; selectedAuthor: string; selectedTag: string | null }> | null
    if (saved?.sort && ['updatedDesc', 'createdDesc', 'updatedAsc'].includes(saved.sort)) sort.value = saved.sort as typeof sort.value
    if (saved?.imageFilter && ['all', 'yes', 'no'].includes(saved.imageFilter)) imageFilter.value = saved.imageFilter as typeof imageFilter.value
    if (saved?.timeFilter && ['all', 'today', 'yesterday', 'earlier'].includes(saved.timeFilter)) timeFilter.value = saved.timeFilter as typeof timeFilter.value
    if (typeof saved?.selectedAuthor === 'string') selectedAuthor.value = saved.selectedAuthor
    if (saved?.selectedTag === null || typeof saved?.selectedTag === 'string') selectedTag.value = saved.selectedTag
  } catch { /* 忽略损坏的列表偏好，使用默认筛选。 */ }
  noteListPreferenceLoaded.value = true
}
restoreNoteListPreferences()
watch([sort, imageFilter, timeFilter, selectedAuthor, selectedTag], () => {
  if (!noteListPreferenceLoaded.value) return
  try { localStorage.setItem(noteListPreferenceKey, JSON.stringify({ sort: sort.value, imageFilter: imageFilter.value, timeFilter: timeFilter.value, selectedAuthor: selectedAuthor.value, selectedTag: selectedTag.value })) }
  catch { /* 偏好无法持久化时仍可在当前页面使用筛选。 */ }
}, { flush: 'sync' })
const activeFilterCount = computed(() => Number(!!selectedTag.value || selectedTag.value === null)
  + Number(!!selectedAuthor.value) + Number(imageFilter.value !== 'all') + Number(timeFilter.value !== 'all') + Number(sort.value !== 'updatedDesc'))
const filterSummary = computed(() => [selectedTag.value === null ? '无标签' : selectedTag.value ? `# ${selectedTag.value}` : '',
  selectedAuthor.value ? `作者：${authorName(selectedAuthor.value)}` : '',
  imageFilter.value === 'yes' ? '有图' : imageFilter.value === 'no' ? '无图' : '',
  { today: '今天', yesterday: '昨天', earlier: '以前', all: '' }[timeFilter.value],
  { updatedDesc: '最近更新', createdDesc: '最近创建', updatedAsc: '最早更新' }[sort.value]].filter(Boolean).join(' · '))
function resetFilters() { selectedTag.value = ''; selectedAuthor.value = ''; imageFilter.value = 'all'; timeFilter.value = 'all'; sort.value = 'updatedDesc' }
function onOutsideFilter(event: PointerEvent) { if (!filterControl.value?.contains(event.target as Node)) filterOpen.value = false }
const tagNames = ref<string[]>([])
const tagsLoading = ref(false)
const tagsError = ref('')
const managingTags = ref(false)
const sourceTag = ref('')
const targetTag = ref('')
const tagSaving = ref(false)
useNoteTagChanges(change => {
  tagNames.value = replaceTagNames(tagNames.value, change)
  if (selectedTag.value === change.source) selectedTag.value = change.target ?? ''
  if (sourceTag.value === change.source) sourceTag.value = change.target ?? ''
  if (targetTag.value === change.source) targetTag.value = change.target ?? ''
  void Promise.all([loadTags(), data.refreshDocs()]).catch(() => {
    tagsError.value = '标签已修改，清单刷新失败；请重试，正文与草稿仍保留。'
  })
})
async function manageTag(merge: boolean) {
  if (tagSaving.value || !sourceTag.value || (merge && (!targetTag.value || targetTag.value === sourceTag.value))) return
  const source = sourceTag.value, target = targetTag.value
  if (!window.confirm(merge ? `将“${source}”合并到“${target}”？文章保留，重复标签会去重。` : `删除标签“${source}”？只移除标签，不删除文章。`)) return
  tagSaving.value = true
  tagsError.value = ''
  try {
    if (merge) await apiMergeNoteTag(source, target)
    else await apiDeleteNoteTag(source)
    const change = { source, target: merge ? target : null }
    if (!replaceCachedNoteTags(session.currentUser.id, change)) toast('标签已修改，部分浏览器草稿标签未同步；请保留输入并检查浏览器存储。', 'ri-error-warning-line')
    publishNoteTagChange(change)
    if (selectedTag.value === source) selectedTag.value = merge ? target : ''
    sourceTag.value = ''
    targetTag.value = ''
  } catch (cause) { tagsError.value = cause instanceof Error ? cause.message : '标签修改失败，请重试' }
  finally { tagSaving.value = false }
}
const addingTag = ref(false)
const newTag = ref('')
const creatingTag = ref(false)
const tagError = ref('')
const editingIds = ref<string[]>([])
const noteDrafts = ref<Record<string, NoteDraftSnapshot>>({})
function displayedContent(note: Doc) { return noteDrafts.value[note.id]?.content ?? latestNoteContent(note) }
function displayedTitle(note: Doc) { return noteDrafts.value[note.id]?.title ?? note.title }
function displayedTags(note: Doc) { return noteDrafts.value[note.id]?.tags ?? note.tags ?? [] }
function onDraftChange(id: string, draft: NoteDraftSnapshot | null) {
  if (draft) noteDrafts.value[id] = draft
  else delete noteDrafts.value[id]
}
const expandedIds = ref<string[]>([])
const deletingId = ref('')
const confirmingDeleteId = ref('')
const deleteError = ref<{ id: string; message: string } | null>(null)
const deleteConfirmation = ref<HTMLElement | null>(null)
const deleteButtons = new Map<string, HTMLElement>()
const deleteConfirmationStyle = ref<Record<string, string>>({ visibility: 'hidden' })
function setDeleteConfirmation(value: unknown) { deleteConfirmation.value = value instanceof HTMLElement ? value : null }
function setDeleteButton(id: string, value: unknown) {
  if (value instanceof HTMLElement) deleteButtons.set(id, value)
  else deleteButtons.delete(id)
}
function positionDeleteConfirmation() {
  const anchor = deleteButtons.get(confirmingDeleteId.value)
  const panel = deleteConfirmation.value
  if (!anchor || !panel) return
  const rect = anchor.getBoundingClientRect()
  const width = Math.min(320, window.innerWidth - 24)
  const height = panel.getBoundingClientRect().height
  const below = rect.bottom + 8
  const top = below + height <= window.innerHeight - 12 ? below : rect.top - height - 8
  deleteConfirmationStyle.value = {
    width: `${width}px`,
    left: `${Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12))}px`,
    top: `${Math.max(12, Math.min(top, window.innerHeight - height - 12))}px`,
  }
}
function closeDeleteConfirmation(restoreFocus = false) {
  if (deletingId.value) return
  const anchor = deleteButtons.get(confirmingDeleteId.value)
  confirmingDeleteId.value = ''
  if (restoreFocus) anchor?.focus({ preventScroll: true })
}
function onOutsideDeleteConfirmation(event: PointerEvent) {
  const target = event.target as Node | null
  if (!target || !confirmingDeleteId.value || deleteConfirmation.value?.contains(target)
      || deleteButtons.get(confirmingDeleteId.value)?.contains(target)) return
  closeDeleteConfirmation()
}
function onDeleteConfirmationKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && confirmingDeleteId.value && !deletingId.value) {
    event.preventDefault()
    closeDeleteConfirmation(true)
  }
}
watch([confirmingDeleteId, deleteError, deletingId], async () => {
  await nextTick()
  positionDeleteConfirmation()
})
function closeEditing(id: string) { editingIds.value = editingIds.value.filter(item => item !== id) }
let cardClickTimer: number | undefined
let pendingCardClickId = ''
function clearCardClickTimer() {
  if (cardClickTimer !== undefined) window.clearTimeout(cardClickTimer)
  cardClickTimer = undefined
  pendingCardClickId = ''
}
function scheduleCardPreview(id: string, event: MouseEvent) {
  if (editingIds.value.includes(id)) return
  const target = event.target
  if (target instanceof Element && target.closest('a, button, input, select, textarea, summary, img, [contenteditable="true"]')) return
  const selection = window.getSelection()
  if (selection && !selection.isCollapsed && selection.anchorNode && event.currentTarget instanceof Node && event.currentTarget.contains(selection.anchorNode)) return
  if (cardClickTimer !== undefined) {
    const previousId = pendingCardClickId
    clearCardClickTimer()
    if (previousId === id) return
    if (previousId && isNotePreviewExpandable(previousId)) expandedIds.value = expandedIds.value.includes(previousId)
      ? expandedIds.value.filter(item => item !== previousId)
      : [...expandedIds.value, previousId]
  }
  if (!isNotePreviewExpandable(id)) return
  pendingCardClickId = id
  cardClickTimer = window.setTimeout(() => {
    const clickedId = pendingCardClickId
    cardClickTimer = undefined
    pendingCardClickId = ''
    if (!clickedId || !isNotePreviewExpandable(clickedId)) return
    expandedIds.value = expandedIds.value.includes(clickedId)
      ? expandedIds.value.filter(item => item !== clickedId)
      : [...expandedIds.value, clickedId]
  }, 260)
}
function toggleNotePreview(id: string) {
  clearCardClickTimer()
  if (!isNotePreviewExpandable(id)) return
  expandedIds.value = expandedIds.value.includes(id)
    ? expandedIds.value.filter(item => item !== id)
    : [...expandedIds.value, id]
}
async function toggleDeleteConfirmation(id: string) {
  if (deletingId.value) return
  if (confirmingDeleteId.value === id) { closeDeleteConfirmation(true); return }
  deleteConfirmationStyle.value = { visibility: 'hidden', width: `${Math.min(320, window.innerWidth - 24)}px` }
  confirmingDeleteId.value = id
  deleteError.value = null
  await nextTick()
  positionDeleteConfirmation()
  deleteConfirmation.value?.querySelector<HTMLButtonElement>('.card-delete-cancel')?.focus({ preventScroll: true })
}
function cancelDeleteOnEscape(id: string, event: KeyboardEvent) {
  if (confirmingDeleteId.value !== id || deletingId.value) return
  event.stopPropagation()
  toggleDeleteConfirmation(id)
}
function openNoteMenu(event: MouseEvent | KeyboardEvent, id: string) {
  const note = data.docById(id)
  if (!note || editingIds.value.includes(id)) return
  if (event instanceof KeyboardEvent) {
    if (!(event.shiftKey && event.key === 'F10') && event.key !== 'ContextMenu') return
    event.preventDefault()
  } else {
    if (event.target instanceof Element && event.target.closest('a, button, input, textarea, img, [contenteditable="true"]')) return
    const selection = window.getSelection()
    if (selection && !selection.isCollapsed && selection.anchorNode && event.currentTarget instanceof Node && event.currentTarget.contains(selection.anchorNode)) return
  }
  contextMenu.value?.openAt(event, `小记：${note.title}`, [
    { id: 'edit', label: '编辑小记', run: () => editNote(id) },
    { id: 'copy', label: '复制内容', run: () => { const note = data.docById(id); if (note && cardImages(note).length) openMediaCopy(note); else void noteEditors.get(id)?.copyFormatted() } },
    { id: 'link', label: '复制分享链接', run: () => copyText(docShareUrl(id), '公开分享链接已复制') },
    { id: 'pin', label: pinnedIds.value.includes(id) ? '取消置顶' : '置顶', disabled: !pinsReady.value || pinSaving.value, run: () => togglePin(id) },
    { id: 'delete', label: '移入回收站', danger: true, separatorBefore: true, disabled: !!deletingId.value, run: () => toggleDeleteConfirmation(id) },
  ])
}
async function deleteNote(id: string) {
  if (deletingId.value || confirmingDeleteId.value !== id) return
  if (!data.apiLive) { deleteError.value = { id, message: '服务尚未连接，小记未删除，请稍后重试。' }; return }
  deletingId.value = id
  deleteError.value = null
  try {
    await data.deleteDoc(id)
    discardAutoNoteEnrichment(session.currentUser.id, id)
    try { removeNoteDraft(`wao-note-edit-v1:${session.currentUser.id}:${id}`) } catch { /* 文档已进入回收站。 */ }
    confirmingDeleteId.value = ''
    closeEditing(id)
    if (highlighted.value === id) highlighted.value = ''
    toast('小记已移入知识库回收站，7 天内可恢复', 'ri-delete-bin-line')
  } catch {
    deleteError.value = { id, message: '删除失败，小记未改变；若它包含子节点，请先在完整文档中移走后重试。' }
  } finally { deletingId.value = '' }
}
async function editNote(id: string, event?: MouseEvent) {
  if (event?.target instanceof Element && event.target.closest('a, button, input, select, textarea, summary, img, [contenteditable="true"]')) return
  editAnchors.value[id] = event ? noteEditors.get(id)?.positionAtPoint?.(event.clientX, event.clientY) ?? undefined : undefined
  if (pendingCardClickId === id) clearCardClickTimer()
  confirmingDeleteId.value = ''
  if (!editingIds.value.includes(id)) editingIds.value = [...editingIds.value, id]
  await nextTick()
  const card = Array.from(notesList.value?.querySelectorAll<HTMLElement>('[data-note-id]') ?? []).find(el => el.dataset.noteId === id)
  card?.querySelector<HTMLElement>('[contenteditable="true"], textarea')?.focus({ preventScroll: true })
}
async function editReadingNote(event: MouseEvent) {
  if (originalsOpen.value || !readingNote.value || event.target instanceof Element && event.target.closest('a, button, img')) return
  const id = readingNote.value.id
  const anchor = readerEditor.value?.positionAtPoint?.(event.clientX, event.clientY)
  const fullscreen = readerMode.value === 'full'
  await stopReading()
  limit.value = Math.max(limit.value, matching.value.findIndex(note => note.id === id) + 1)
  editAnchors.value[id] = { position: anchor?.position ?? 1, viewportTop: anchor?.viewportTop ?? 100, fullscreen }
  if (!expandedIds.value.includes(id)) expandedIds.value.push(id)
  if (!editingIds.value.includes(id)) editingIds.value.push(id)
}
const PAGE_SIZE = 10
const limit = ref(PAGE_SIZE)
const notesList = ref<HTMLElement>()
const highlighted = ref('')
const noteSettingsOpen = ref(false)
const reading = ref(false)
const readingId = ref('')
const readerIds = ref<string[]>([])
const readerMode = ref<'side' | 'full'>('side')
const readerDirectoryOpen = ref(false)
const readerDialog = ref<HTMLElement | null>(null)
const readerPage = ref<HTMLElement | null>(null)
const readerLayout = ref<HTMLElement | null>(null)
const readerWidths = useNoteReaderWidths(readerLayout)
const readerClose = ref<HTMLButtonElement | null>(null)
const readerPositions = new Map<string, number>()
const originalsOpen = ref(false)
let originalPositionKey = 'loading'
const readerPositionKey = () => `${readingId.value}:${originalsOpen.value ? originalPositionKey : 'current'}`
function changeOriginalSource(key: string) { rememberReaderPosition(); originalPositionKey = key; readerActiveImage.value = null; readerRestorePending = true; void nextTick(restoreReaderPosition) }
async function toggleOriginals() { rememberReaderPosition(); originalsOpen.value = !originalsOpen.value; readerRestorePending = true; await nextTick(); restoreReaderPosition() }
async function readOriginals(id: string, event: Event) { await startReading(id, event); originalsOpen.value = true }
let readerTrigger: HTMLElement | null = null
let readerApp: HTMLElement | null = null
let readerAppWasInert = false
let readerBodyOverflow = ''
let readerRestorePending = false
// 固定本次阅读的筛选顺序；正文仍取当前单份内容，后台更新不让索引漂移。
const readerNotes = computed(() => readerIds.value.map(id => notes.value.find(note => note.id === id)).filter((note): note is Doc => !!note))
const readingIndex = computed(() => readerNotes.value.findIndex(note => note.id === readingId.value))
const readingNote = computed(() => readerNotes.value.find(note => note.id === readingId.value) ?? null)
const readerOriginalContent = ref<RichDocContent | null>(null)
const readerOriginal = ref<{ focusImageReference: (index: number) => boolean } | null>(null)
const readerImagesPanel = ref<{ locate: (index: number) => void } | null>(null)
const readerActiveImage = ref<number | null>(null)
const readerImages = computed(() => {
  const content = originalsOpen.value ? readerOriginalContent.value : readingNote.value ? displayedContent(readingNote.value) : null
  return content ? notePreviewImages(content) : []
})
watch([readingId, originalsOpen, () => JSON.stringify(readerImages.value)], () => { readerActiveImage.value = null })
watch([readingId, originalsOpen], () => { readerOriginalContent.value = null })
async function locateReadingImage(index: number) {
  if (!readerImages.value[index - 1]) return
  readerRestorePending = false
  readerActiveImage.value = index
  await nextTick()
  readerImagesPanel.value?.locate(index)
}
async function returnToImageReference(index: number) {
  readerRestorePending = false
  readerActiveImage.value = index
  await nextTick()
  if (originalsOpen.value) readerOriginal.value?.focusImageReference(index)
  else readerEditor.value?.focusImageReference(index)
}
const readerCopyBusy = ref(false)
const readerCopyStatus = ref('')
let readerCopyRequest = 0
watch([reading, readingId, () => session.userId], () => {
  readerCopyRequest++
  readerCopyBusy.value = false
  readerCopyStatus.value = ''
}, { flush: 'sync' })
async function copyReadingNote(kind: 'share' | 'body') {
  const note = readingNote.value
  if (!note || readerCopyBusy.value || (kind === 'body' && originalsOpen.value)) return
  const request = ++readerCopyRequest
  const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  readerCopyBusy.value = true
  readerCopyStatus.value = '正在复制…'
  const text = kind === 'share' ? docShareUrl(note.id) : displayedMarkdown(note)
  const copied = await copyToClipboard(text)
  if (request !== readerCopyRequest) return
  readerCopyBusy.value = false
  readerCopyStatus.value = copied ? kind === 'share' ? '公开分享链接已复制' : '正文已复制' : '复制失败，请重试或手动选中复制'
  await nextTick()
  if (request !== readerCopyRequest) return
  if (trigger?.isConnected && readerDialog.value?.contains(trigger)) trigger.focus({ preventScroll: true })
}
async function startReading(id?: string, event?: Event, mode?: 'side' | 'full') {
  clearCardClickTimer()
  if (reading.value) return
  const ids = matching.value.map(note => note.id)
  if (id && !ids.includes(id) && notes.value.some(note => note.id === id)) ids.unshift(id)
  if (!ids.length) return
  originalsOpen.value = false
  readerIds.value = ids
  readingId.value = id && ids.includes(id) ? id : ids.includes(readingId.value) ? readingId.value : ids[0]!
  readerTrigger = event?.currentTarget instanceof HTMLElement ? event.currentTarget : document.activeElement instanceof HTMLElement ? document.activeElement : null
  if (mode) readerMode.value = mode
  readerDirectoryOpen.value = false
  filterOpen.value = false
  reading.value = true
  readerRestorePending = true
  readerApp = document.getElementById('app')
  readerAppWasInert = readerApp?.inert ?? false
  if (readerApp) readerApp.inert = true
  readerBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  await nextTick()
  readerClose.value?.focus({ preventScroll: true })
  restoreReaderPosition()
}
function openPreviewChoices(id: string, event: MouseEvent) {
  const trigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : undefined
  contextMenu.value?.openAt(event, '预览方式', [
    { id: 'preview-side', label: '侧栏速览', run: () => startReading(id, undefined, 'side') },
    { id: 'preview-full', label: '全屏预览', run: () => startReading(id, undefined, 'full') },
  ], trigger)
}
function rememberReaderPosition() {
  if (readingId.value && readerPage.value) readerPositions.set(readerPositionKey(), readerPage.value.scrollTop)
}
function restoreReaderPosition() {
  if (!readerRestorePending || !readerPage.value) return
  const top = readerPositions.get(readerPositionKey()) ?? 0
  readerPage.value.scrollTop = top
  if (top === 0 || readerPage.value.scrollHeight > readerPage.value.clientHeight) readerRestorePending = false
}
async function changeReaderMode(mode: 'side' | 'full') {
  const page = readerPage.value
  const top = page?.getBoundingClientRect().top ?? 0
  const anchor = [...(page?.querySelectorAll<HTMLElement>('.reader-body p, .reader-body h1, .reader-body h2, .reader-body h3, .reader-body li, .reading-image-reference') ?? [])].find(el => el.getBoundingClientRect().bottom > top + 10)
  const offset = anchor ? anchor.getBoundingClientRect().top - top : 0
  readerMode.value = mode
  readerDirectoryOpen.value = false
  await nextTick()
  if (anchor?.isConnected && page) page.scrollTop += anchor.getBoundingClientRect().top - page.getBoundingClientRect().top - offset
  rememberReaderPosition()
}
async function selectReadingNote(id: string) {
  if (!readerNotes.value.some(note => note.id === id)) return
  rememberReaderPosition()
  readingId.value = id
  originalsOpen.value = false
  readerDirectoryOpen.value = false
  readerRestorePending = true
  await nextTick()
  restoreReaderPosition()
  readerClose.value?.focus({ preventScroll: true })
}
function restoreReaderBackground() {
  if (readerApp) readerApp.inert = readerAppWasInert
  readerApp = null
  document.body.style.overflow = readerBodyOverflow
}
async function stopReading() {
  if (!reading.value) return
  rememberReaderPosition()
  readerWidths.stop()
  reading.value = false
  readerDirectoryOpen.value = false
  restoreReaderBackground()
  await nextTick()
  if (readerTrigger?.isConnected) readerTrigger.focus({ preventScroll: true })
  else document.querySelector<HTMLElement>('.reader-toggle')?.focus({ preventScroll: true })
  readerTrigger = null
}
function closeReaderLayer() {
  if (readerDirectoryOpen.value) {
    readerDirectoryOpen.value = false
    void nextTick(() => readerDialog.value?.querySelector<HTMLButtonElement>('.reader-directory-toggle')?.focus())
  } else void stopReading()
}
async function openReaderDirectory() {
  readerDirectoryOpen.value = true
  await nextTick()
  readerDialog.value?.querySelector<HTMLElement>('.reader-directory [aria-current="true"]')?.focus({ preventScroll: true })
}
function onReaderResize() {
  if (window.innerWidth > 650) readerDirectoryOpen.value = false
}
const readerDismiss = useOverlayDismiss(() => reading.value, closeReaderLayer)
useOverlayDismiss(() => !!previewImage.value, closeImage)
function trapReaderFocus(event: KeyboardEvent, container: HTMLElement | null) {
  if (event.key !== 'Tab' || !container) return
  const buttons = [...container.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], [tabindex="0"]')].filter(el => el.getClientRects().length && !el.closest('[inert]'))
  const first = buttons[0], last = buttons[buttons.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch(() => readerNotes.value.map(note => note.id), ids => {
  if (!reading.value || ids.includes(readingId.value)) return
  if (ids.length) void selectReadingNote(ids[0]!)
  else void stopReading()
})
let discoverySourceRequest = 0
watch(() => session.currentUser.id, () => { discoverySourceRequest++; clearDiscoveryLegacyCache(); void stopReading(); readerPositions.clear(); readingId.value = ''; readerMode.value = 'side' })
async function openDiscoverySource(itemId: string, docId: string, event: Event) {
  const trigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  const member = session.currentUser.id, request = ++discoverySourceRequest
  try {
    const doc = await fetchDiscoverySource(itemId, docId)
    if (member !== session.currentUser.id || request !== discoverySourceRequest) return
    data.applyRemoteDoc(doc)
    await startReading(docId)
    if (member === session.currentUser.id && request === discoverySourceRequest) readerTrigger = trigger
  } catch { if (member === session.currentUser.id && request === discoverySourceRequest) toast('来源暂时无法打开，请重试', 'error') }
}
function moveReader(delta: number) {
  if (!readerNotes.value.length) return
  const index = Math.min(readerNotes.value.length - 1, Math.max(0, readingIndex.value + delta))
  void selectReadingNote(readerNotes.value[index]!.id)
}
function onReaderKeydown(event: KeyboardEvent) {
  if (event.defaultPrevented || event.isComposing) return
  if (!reading.value || previewImage.value || !readerDismiss.isTopmost()) return
  trapReaderFocus(event, readerDialog.value)
  const target = event.target
  if (target instanceof HTMLElement && target.closest('input, textarea, select, [contenteditable="true"]')) return
  if (event.key === 'Escape') { event.preventDefault(); closeReaderLayer(); return }
  if (event.key === 'ArrowRight') { event.preventDefault(); moveReader(1); return }
  if (event.key === 'ArrowLeft') { event.preventDefault(); moveReader(-1); return }
  if (event.key === ' ' && target instanceof HTMLElement && target.closest('button, a')) return
  if (['ArrowDown', 'PageDown', ' '].includes(event.key)) {
    event.preventDefault()
    readerPage.value?.scrollBy({ top: Math.max(96, readerPage.value.clientHeight * (event.key === 'ArrowDown' ? .35 : .82)), behavior: 'smooth' })
    return
  }
  if (['ArrowUp', 'PageUp'].includes(event.key)) {
    event.preventDefault()
    readerPage.value?.scrollBy({ top: -Math.max(96, readerPage.value.clientHeight * (event.key === 'ArrowUp' ? .35 : .82)), behavior: 'smooth' })
    return
  }
  if (event.key === 'Home') { event.preventDefault(); void selectReadingNote(readerNotes.value[0]!.id) }
  if (event.key === 'End') { event.preventDefault(); void selectReadingNote(readerNotes.value[readerNotes.value.length - 1]!.id) }
}
const showBackToTop = ref(false)
function onNotesScroll() { discoveries.applyPending(); showBackToTop.value = window.scrollY > 480 || (notesList.value?.scrollTop ?? 0) > 480 }
function backToTop() {
  const behavior: ScrollBehavior = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'
  if ((notesList.value?.scrollHeight ?? 0) > (notesList.value?.clientHeight ?? 0) + 1) notesList.value?.scrollTo({ top: 0, behavior })
  else window.scrollTo({ top: 0, behavior })
}
const refreshError = ref('')
const refreshing = ref(false)
const notes = computed(() => {
  const ids = new Set(data.kbs.filter(kb => kb.notes).map(kb => kb.id))
  return Object.values(data.docs).filter(doc => ids.has(doc.kbId ?? '') && !data.isDocArchived(doc.id))
    .sort((a, b) => Number(pinnedIds.value.includes(b.id)) - Number(pinnedIds.value.includes(a.id))
      || (sort.value === 'updatedAsc' ? 1 : -1) * ((Date.parse(a[sort.value === 'createdDesc' ? 'created' : 'updated']) || Date.parse(a.created) || 0)
        - (Date.parse(b[sort.value === 'createdDesc' ? 'created' : 'updated']) || Date.parse(b.created) || 0))
      || b.id.localeCompare(a.id))
})
const todayCharacters = computed(() => noteCharactersCreatedOn(
  notes.value, session.currentUser.id, shanghaiDateKey(now.value),
))
const todayCountError = computed(() => data.loadErrors.docs || data.loadErrors.knowledge)
const todayCountLoading = computed(() => !todayCountError.value
  && (['idle', 'loading'].includes(data.loadStates.docs) || ['idle', 'loading'].includes(data.loadStates.knowledge)))
const availableTags = computed(() => [...new Set([...tagNames.value, ...notes.value.flatMap(note => note.tags ?? [])])].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const availableAuthors = computed(() => [...new Set(notes.value.map(note => note.author).filter(Boolean))]
  .map(id => ({ id, name: authorName(id) }))
  .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN') || a.id.localeCompare(b.id)))
const matching = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return notes.value.filter(doc => (selectedTag.value === null ? !doc.tags?.length : !selectedTag.value || doc.tags?.includes(selectedTag.value))
    && (!selectedAuthor.value || doc.author === selectedAuthor.value)
    && (imageFilter.value === 'all' || (notePreviewImages(displayedContent(doc)).length > 0) === (imageFilter.value === 'yes'))
    && inTimeRange(doc.updated, timeFilter.value)
    && (!needle || `${displayedTitle(doc)}\n${documentSearchText({ blocks: doc.blocks, content: displayedContent(doc) })}\n${displayedTags(doc).join(' ')}`.toLocaleLowerCase().includes(needle)))
})
function inTimeRange(value: string, range: typeof timeFilter.value) {
  if (range === 'all') return true
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return range === 'earlier'
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today); yesterday.setDate(yesterday.getDate() - 1)
  return range === 'today' ? date >= today : range === 'yesterday' ? date >= yesterday && date < today : date < yesterday
}
function displayedMarkdown(note: Doc) { return displayedContent(note).markdown }
function cardImages(note: Doc): NotePreviewImage[] {
  return notePreviewImages(displayedContent(note))
}
function stageIcon(stage: 'title' | 'image' | 'polish' | 'tags') { return { title: 'T', image: '▧', polish: '✦', tags: '#' }[stage] }
function stageLabel(stage: 'title' | 'image' | 'polish' | 'tags') { return { title: '标题', image: '配图', polish: '润色', tags: '标签' }[stage] }
function workflowProgress(id: string) {
  const server = serverWorkflows.value[id]
  if (!server) return noteEnrichmentProgress[id]
  const state = (value: NoteAiWorkflowStatus['title']) => value.toLowerCase() as 'pending' | 'running' | 'done' | 'failed' | 'skipped'
  const local = noteEnrichmentProgress[id]
  const combined = { title: state(server.title), image: state(server.image), polish: state(server.polish), tags: server.tags ? state(server.tags) : local?.tags ?? 'skipped' }
  return combined
}
function progressVisible(id: string) {
  const steps = workflowProgress(id)
  return !steps || !((steps.title === 'done' || steps.title === 'skipped') && (steps.image === 'done' || steps.image === 'skipped') && (steps.polish === 'done' || steps.polish === 'skipped') && (steps.tags === 'done' || steps.tags === 'skipped'))
}
function stageDisabled(id: string, stage: 'title' | 'image' | 'polish' | 'tags') {
  return retryingEnrichment.value.includes(id) || workflowProgress(id)?.[stage] === 'running'
    || !!serverWorkflows.value[id] && ['PENDING', 'RUNNING'].includes(serverWorkflows.value[id]![stage] ?? '')
}
function stageActionText(id: string, stage: 'title' | 'image' | 'polish' | 'tags') {
  const detail = serverWorkflows.value[id]?.stages?.[stage]
  if (detail?.message) return detail.message + (stageDisabled(id, stage) ? '' : '；点击手动执行一次')
  const value = workflowProgress(id)?.[stage]
  if (value === 'done') return '已完成，点击重新生成'
  if (value === 'running') return '正在处理'
  if (value === 'failed') return '失败，点击重试'
  if (serverWorkflows.value[id] && ['PENDING', 'RUNNING'].includes(serverWorkflows.value[id]![stage] ?? '')) return '等待后台处理'
  return '未生成，点击开始'
}
function serverFailed(id: string) {
  const server = serverWorkflows.value[id]
  return !!server && (server.title === 'FAILED' || server.image === 'FAILED' || server.polish === 'FAILED' || server.tags === 'FAILED')
}
function workflowMessage(id: string) {
  const server = serverWorkflows.value[id]
  if (!server) return noteAiRecoveryMessage(noteEnrichmentStatus[id])
  if (server.message) return noteAiRecoveryMessage(server.message)
  if (['PENDING', 'RUNNING'].some(status => Object.values(server).includes(status))) return '后台自动整理中，关闭页面后仍会继续'
  return noteAiRecoveryMessage(noteEnrichmentStatus[id])
}
function hasPendingNoteDraft(note: Doc) {
  const key = `wao-note-edit-v1:${session.currentUser.id}:${note.id}`
  try {
    const raw = readNoteDraft(key)
    if (raw === null) return false
    const draft = JSON.parse(raw) as { id?: string; text?: string; content?: Doc['content']; tags?: string[]; generatedTitle?: string }
    const saved = resolvedRichContent(note)
    // 旧浏览器留下的草稿若与服务端完全一致，不应永久挡住失败任务重试。
    if (draft.id === note.id && draft.text === saved.markdown
        && JSON.stringify(draft.content?.document ?? null) === JSON.stringify(saved.document ?? null)
        && JSON.stringify(draft.tags ?? []) === JSON.stringify(note.tags ?? [])
        && (draft.generatedTitle || note.title) === note.title) {
      removeNoteDraft(key, raw)
      return false
    }
    return true
  } catch { return true /* 无法确认内容相同时保留草稿。 */ }
}
const chargingNote = ref('')
const aiStages = ['title', 'image', 'polish', 'tags'] as const
function fourStageComplete(id: string, stage: typeof aiStages[number]) {
  if (workflowProgress(id)?.[stage] === 'done') return true
  const server = serverWorkflows.value[id]
  return server?.[stage] === 'SKIPPED' && ['EXISTING_IMAGE', 'MANUAL_TITLE', 'NO_MATCHING_TAGS'].includes(server.stages?.[stage]?.reasonCode ?? '')
}
function fourComplete(id: string) { return aiStages.every(stage => fourStageComplete(id, stage)) }
async function retryOrStart(note: Doc, stage?: 'title' | 'image' | 'polish' | 'tags' | 'four') {
  if (retryingEnrichment.value.includes(note.id)) return
  if (stage === 'four' && fourComplete(note.id)) { toast('四项已完成，无需重复执行'); return }
  retryingEnrichment.value = [...retryingEnrichment.value, note.id]
  try {
    const composer = noteComposers.get(note.id)
    if (!composer && hasPendingNoteDraft(note)) {
      await editNote(note.id)
      noteEnrichmentStatus[note.id] = '自动整理未完成：已打开本地草稿，请确认并保存后再次重试'
      return
    }
    if (composer?.dirty) {
      noteEnrichmentStatus[note.id] = '正在保存小记修改，再继续重试…'
      await composer.save()
      if (composer.dirty) {
        noteEnrichmentStatus[note.id] = '自动整理未完成：小记修改尚未保存，请先处理保存提示后重试'
        return
      }
    }
    if (hasPendingNoteDraft(data.docById(note.id) ?? note)) {
      noteEnrichmentStatus[note.id] = '自动整理未完成：本地草稿尚未保存，请先在编辑卡片中保存后重试'
      return
    }
    if (stage === 'four') {
      let queued = 0
      for (const next of aiStages) {
        const fresh = (await fetchNoteAiWorkflows()).find(status => status.docId === note.id)
        if (fresh) serverWorkflows.value[note.id] = fresh
        const state = workflowProgress(note.id)?.[next]
        if (fourStageComplete(note.id, next) || state === 'running' || fresh?.[next] === 'PENDING') continue
        const latest = await fetchDoc(note.id)
        data.applyRemoteDoc(latest)
        serverWorkflows.value[note.id] = await startSavedNoteStage(note.id, next, latest.rev ?? 1, newPolishRequestId())
        queued++
      }
      toast(queued ? `已接续 ${queued} 项未完成能力` : '已完成或正在处理中，无需重复执行')
      return
    }
    if (stage) {
      const latest = data.docById(note.id) ?? note
      serverWorkflows.value[note.id] = await startSavedNoteStage(note.id, stage, latest.rev ?? 1, newPolishRequestId())
      return
    }
    noteEnrichmentStatus[note.id] = '正在重试未完成的自动整理…'
    serverWorkflows.value[note.id] = await retryNoteAiWorkflow(note.id)
    noteEnrichmentStatus[note.id] = '后台已接续失败步骤，关闭页面后仍会继续'

  } catch (cause) {
    const message = cause instanceof Error ? cause.message : 'AI 操作未完成，请重试'
    noteEnrichmentStatus[note.id] = message
    toast(message, 'ri-error-warning-line', { duration: 6000 })
  } finally {
    retryingEnrichment.value = retryingEnrichment.value.filter(id => id !== note.id)
  }
}
function onStageClick(note: Doc, stage: 'title' | 'image' | 'polish' | 'tags') {
  if (stageDisabled(note.id, stage)) return
  void retryOrStart(note, stage)
}
// 筛选、分页或远端刷新不能卸载正在输入的卡片，尤其是浏览器草稿写入失败时。
const retainedEdits = computed(() => notes.value.filter(note => (editingIds.value.includes(note.id) || searchFocus.value.id === note.id)
  && !matching.value.slice(0, limit.value).some(item => item.id === note.id)))
const visible = computed(() => [...matching.value.slice(0, limit.value), ...retainedEdits.value])
const notePreviews = computed(() => new Map(visible.value.map(note => [note.id, cardImages(note)])))
const cardActiveImages = ref<Record<string, number | undefined>>({})
watch(() => session.currentUser.id, () => { cardActiveImages.value = {} })
watch(() => new Map([...notePreviews.value].map(([id, images]) => [id, JSON.stringify(images)])), (current, previous) => {
  for (const id of Object.keys(cardActiveImages.value)) {
    if (current.get(id) !== previous?.get(id)) delete cardActiveImages.value[id]
  }
})
async function locateCardImage(id: string, index: number) {
  if (!notePreviews.value.get(id)?.[index - 1]) return
  clearCardClickTimer()
  cardActiveImages.value[id] = index
  await nextTick()
  const card = [...(notesList.value?.querySelectorAll<HTMLElement>('[data-note-id]') ?? [])].find(el => el.dataset.noteId === id)
  const button = card?.querySelector<HTMLButtonElement>(`[data-card-image="${index}"] .note-image`)
  button?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
  button?.focus({ preventScroll: true })
}
async function returnToCardImageReference(id: string, index: number) {
  clearCardClickTimer()
  cardActiveImages.value[id] = index
  // 固定高度卡片先展开，以免原位置被预览高度遮住。
  if (isNotePreviewExpandable(id) && !expandedIds.value.includes(id)) expandedIds.value.push(id)
  await nextTick()
  noteEditors.get(id)?.focusImageReference(index)
}
const loading = computed(() => !refreshing.value && (['idle', 'loading'].includes(data.loadStates.docs) || ['idle', 'loading'].includes(data.loadStates.knowledge)))
const loadError = computed(() => refreshError.value || data.loadErrors.docs || data.loadErrors.knowledge)
let searchReturn: { top: number; windowTop: number; limit: number; expanded: string[] } | null = null
watch(query, async (value, previous) => {
  if (value && !previous) searchReturn = { top: notesList.value?.scrollTop ?? 0, windowTop: window.scrollY, limit: limit.value, expanded: [...expandedIds.value] }
  confirmingDeleteId.value = ''
  if (!value && searchReturn) {
    limit.value = searchReturn.limit; expandedIds.value = searchReturn.expanded
    searchFocus.value = { id: '', term: '' }
    const restore = searchReturn; searchReturn = null
    await nextTick()
    notesList.value?.scrollTo?.({ top: restore.top })
    if (window.scrollY !== restore.windowTop) window.scrollTo({ top: restore.windowTop })
  } else { limit.value = PAGE_SIZE; notesList.value?.scrollTo?.({ top: 0 }) }
})
watch([selectedTag, sort, imageFilter, timeFilter], () => {
  limit.value = PAGE_SIZE; confirmingDeleteId.value = ''
  notesList.value?.scrollTo?.({ top: 0 })
})
function noteSearchHit(note: Doc) {
  const term = query.value
  const title = contentHit(displayedTitle(note), term)
  const body = contentHit(documentSearchText({ blocks: note.blocks, content: displayedContent(note) }), term)
  const tags = contentHit(displayedTags(note).join(' '), term)
  return title ? { ...title, field: '标题' } : body ? { ...body, field: '正文' } : tags ? { ...tags, field: '标签' } : null
}
async function locateSearchNote(id: string, term: string, body = true) {
  if (!notes.value.some(note => note.id === id)) return
  searchFocus.value = { id, term: body ? term : '' }; searchRequest.value++
  if (!expandedIds.value.includes(id)) expandedIds.value.push(id)
  await nextTick()
  const card = Array.from(notesList.value?.querySelectorAll<HTMLElement>('[data-note-id]') ?? []).find(el => el.dataset.noteId === id)
  card?.scrollIntoView?.({ block: 'center' })
  if (!body) card?.focus({ preventScroll: true })
}
let lastSearchRoute = ''
watch([() => route.query.findRequest, () => route.query.note, () => route.query.find, () => data.loadStates.docs, () => data.loadStates.knowledge], () => {
  const id = typeof route.query.note === 'string' ? route.query.note : ''
  const term = typeof route.query.find === 'string' ? route.query.find : ''
  const key = `${id}:${term}:${route.query.findRequest ?? ''}`
  if (id && term && data.loadStates.docs === 'loaded' && data.loadStates.knowledge === 'loaded' && key !== lastSearchRoute) {
    lastSearchRoute = key
    void locateSearchNote(id, term, route.query.match !== 'title')
  }
}, { immediate: true })

function onCreated(id: string) {
  void discoveries.refresh()
  query.value = ''
  selectedTag.value = ''
  limit.value = PAGE_SIZE
  highlighted.value = id
  notesList.value?.scrollTo?.({ top: 0 })
  void loadServerWorkflows()
}
function onEdited(sourceId: string, savedId: string) {
  void discoveries.refresh()
  delete noteDrafts.value[sourceId]
  closeEditing(sourceId)
  highlighted.value = savedId
  void loadServerWorkflows()
}
function authorName(id: string) { return session.members.find(member => member.id === id)?.name || '成员' }
function timeLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
async function reload() {
  if (refreshing.value) return
  refreshing.value = true
  refreshError.value = ''
  try {
    const [knowledge, docs] = await Promise.allSettled([fetchKbs(), data.refreshDocs()])
    if (knowledge.status === 'fulfilled') {
      data.kbs = knowledge.value
      data.loadStates.knowledge = 'loaded'
      delete data.loadErrors.knowledge
    } else {
      refreshError.value = '知识库读取失败，请重试'
    }
    if (docs.status === 'rejected' || !docs.value) refreshError.value ||= '小记读取失败，请重试'
  } finally {
    refreshing.value = false
  }
}
async function loadTags() {
  tagsLoading.value = true
  tagsError.value = ''
  try { tagNames.value = await fetchNoteTags() }
  catch { tagsError.value = '标签读取失败，可重试；正文和已选标签仍保留。' }
  finally { tagsLoading.value = false }
}
async function createTag() {
  if (creatingTag.value) return
  const name = newTag.value.trim()
  if (!name || name.length > 32) { tagError.value = '请输入 1–32 个字符的标签名称'; return }
  creatingTag.value = true
  tagError.value = ''
  try {
    if (!data.apiLive) throw new Error('服务尚未连接，请稍后重试')
    const saved = await apiCreateNoteTag(name)
    tagNames.value = [...new Set([...tagNames.value, saved])]
    newTag.value = ''
    addingTag.value = false
  } catch (cause) { tagError.value = cause instanceof Error ? cause.message : '标签保存失败，请重试' }
  finally { creatingTag.value = false }
}
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    notePreviewResizeObserver = new ResizeObserver(entries => {
      for (const entry of entries) {
        const id = notePreviewBodyIds.get(entry.target as HTMLElement)
        if (id) measureNotePreview(id)
      }
    })
    for (const body of notePreviewBodies.values()) notePreviewResizeObserver.observe(body)
  }
  window.addEventListener('resize', measureAllNotePreviews)
  window.addEventListener('resize', onReaderResize)
  window.addEventListener('resize', positionDeleteConfirmation)
  document.addEventListener('scroll', positionDeleteConfirmation, true)
  window.addEventListener('pointerdown', onOutsideDeleteConfirmation)
  window.addEventListener('keydown', onDeleteConfirmationKeydown)
  void data.ensureDocsLoaded()
  void loadTags()
  window.addEventListener('scroll', onNotesScroll, { passive: true })
})
onMounted(() => { void initializeNoteWorkflow(session.currentUser.id); void loadServerWorkflows(); workflowTimer = setInterval(() => { if (!document.hidden) void loadServerWorkflows() }, 20_000); window.addEventListener('pointerdown', onOutsideFilter); window.addEventListener('keydown', onReaderKeydown) })
onBeforeUnmount(() => { if (reading.value) restoreReaderBackground(); clearCardClickTimer(); notePreviewResizeObserver?.disconnect(); window.removeEventListener('resize', measureAllNotePreviews); window.removeEventListener('resize', onReaderResize); window.removeEventListener('resize', positionDeleteConfirmation); document.removeEventListener('scroll', positionDeleteConfirmation, true); window.removeEventListener('pointerdown', onOutsideDeleteConfirmation); window.removeEventListener('keydown', onDeleteConfirmationKeydown); if (workflowTimer) clearInterval(workflowTimer); window.removeEventListener('pointerdown', onOutsideFilter); window.removeEventListener('keydown', onReaderKeydown); window.removeEventListener('scroll', onNotesScroll) })
watch(() => session.currentUser.id, memberId => { serverWorkflows.value = {}; void initializeNoteWorkflow(memberId); void loadServerWorkflows() })
watch([() => session.currentUser.id, notes], ([memberId, values], previous) => {
  if (previous?.[0] !== memberId) noteDrafts.value = {}
  for (const note of values) {
    if (editingIds.value.includes(note.id)) continue
    try {
      const draft = JSON.parse(readNoteDraft(`wao-note-edit-v1:${memberId}:${note.id}`) || 'null')
      if (draft?.id === note.id && typeof draft.text === 'string') {
        noteDrafts.value[note.id] = {
          content: draft.content?.markdown === draft.text ? draft.content : { version: 1, document: null, markdown: draft.text },
          title: typeof draft.generatedTitle === 'string' && draft.generatedTitle !== draft.baseNote?.title ? draft.generatedTitle : undefined,
          tags: Array.isArray(draft.tags) && JSON.stringify(draft.tags) !== JSON.stringify(draft.baseNote?.tags) ? draft.tags : undefined,
        }
      }
    } catch { /* 无有效草稿时使用服务器正文。 */ }
  }
}, { immediate: true })
const discoveryMode = ref<'notes' | 'saved' | 'legacy'>('notes')
const discoveryExpanded = ref(false)
const discoveryUndo = ref<Discovery | null>(null)
let undoTimer: ReturnType<typeof setTimeout> | undefined
function discoverySafe() {
  return !reading.value && !editingIds.value.length && !discoveryExpanded.value
    && (notesList.value?.scrollTop ?? 0) < 20 && window.scrollY < 100
    && !document.activeElement?.closest('input, textarea, [contenteditable="true"]')
}
function preserveDiscoveryPosition() {
  const list = notesList.value
  if (!list) return
  const anchor = Array.from(list.querySelectorAll<HTMLElement>('.note-card')).find(card => card.getBoundingClientRect().bottom > list.getBoundingClientRect().top)
  if (!anchor) return
  const top = anchor.getBoundingClientRect().top
  const ownScroll = getComputedStyle(list).overflowY === 'auto'
  void nextTick(() => { if (!anchor.isConnected) return; const delta = anchor.getBoundingClientRect().top - top; if (ownScroll) list.scrollTop += delta; else window.scrollBy(0, delta) })
}
const discoveries = useNoteDiscoveries(() => session.currentUser.id, () => data.apiLive, discoverySafe, preserveDiscoveryPosition)
function discoveryChanged(item: Discovery) {
  if (item.feedback.dismissed) {
    discoveries.current.value = null
    discoveryExpanded.value = false
    discoveryUndo.value = item
    if (undoTimer) clearTimeout(undoTimer)
    undoTimer = setTimeout(() => { discoveryUndo.value = null }, 20_000)
  } else discoveries.current.value = item
}
async function undoDiscovery() {
  const item = discoveryUndo.value
  if (!item) return
  const memberId = session.currentUser.id
  try { const updated = await feedbackDiscovery(item, { dismissed: false }); if (memberId !== session.currentUser.id) return; discoveryUndo.value = null; discoveries.current.value = updated }
  catch { toast('撤销没有保存成功，请重试', 'error') }
}
function selectDiscoveryMode(mode: 'notes' | 'saved' | 'legacy') { discoveryMode.value = mode; filterOpen.value = false }
watch(() => session.currentUser.id, () => { discoveryUndo.value = null; discoveryMode.value = 'notes'; if (undoTimer) clearTimeout(undoTimer) })
onBeforeUnmount(() => { if (undoTimer) clearTimeout(undoTimer) })
onMounted(() => void loadPins())
</script>

<template>
  <main class="notes-page wao-page-frame" @contextmenu="openImageMenu">
    <ImageCopyMenu v-if="imageMenu" v-bind="imageMenu" @close="imageMenu = null" />
    <NoteMediaCopyDialog v-if="mediaCopyContent" :content="mediaCopyContent" @close="mediaCopyContent = null" />
    <header class="page-heading"><div class="heading-title"><h1><i class="ri-sticky-note-line"></i>小记</h1><span v-if="todayCountError" class="daily-note-count error" role="alert"><i class="ri-error-warning-line" aria-hidden="true"></i>今日统计读取失败 <button type="button" @click="reload">重试</button></span><span v-else-if="todayCountLoading" class="daily-note-count" role="status"><i class="ri-loader-4-line spin" aria-hidden="true"></i>今日统计中…</span><span v-else class="daily-note-count" title="今日新建小记的正文字符数，不含空白、格式标记和图片"><i class="ri-quill-pen-line" aria-hidden="true"></i>今日写字 {{ todayCharacters.toLocaleString('zh-CN') }} 字</span><button class="writing-toggle" type="button" :aria-expanded="writingOpen" aria-controls="notes-writing-pane" @click="writingOpen = !writingOpen"><i :class="writingOpen ? 'ri-layout-left-line' : 'ri-quill-pen-line'" aria-hidden="true"></i>{{ writingOpen ? '收起记录区' : '展开记录区 · 继续写' }}</button></div><span>{{ notes.length }} 条记录</span></header>
    <div ref="workspace" class="notes-workspace" :style="writingStyle" :class="{ 'writing-collapsed': !writingOpen, resizing }">
      <aside v-show="writingOpen" id="notes-writing-pane" class="writing-pane" aria-label="新建小记区">
        <NoteComposer :key="`${session.currentUser.id}:new`" :loading="loading" :tag-options="availableTags" @tag-created="tagNames = [...new Set([...tagNames, $event])]" @saved="onCreated" />
      </aside>
      <div v-show="writingOpen" class="writing-resizer" role="separator" tabindex="0" aria-label="调整记录区宽度" aria-orientation="vertical" aria-controls="notes-writing-pane" :aria-valuenow="writingPercentage" aria-valuemin="25" aria-valuemax="60" :aria-valuetext="`记录区宽度 ${writingPercentage}%`" title="拖拽调整宽度，双击恢复三分之一；方向键微调" @pointerdown="startWritingResize" @keydown="resizeWritingByKey" @dblclick="resetWritingWidth"><span aria-hidden="true"></span></div>
      <section ref="notesList" class="notes-list" aria-label="小记清单" @scroll.passive="onNotesScroll">
        <div class="list-toolbar">
          <label class="search"><i class="ri-search-line"></i><input v-model="query" type="search" placeholder="搜索内容或标签" aria-label="搜索小记内容"><button v-if="query" type="button" aria-label="清除小记查找" @click="query = ''">清除</button></label>
          <div ref="filterControl" class="filter-control">
            <button class="filter-toggle" type="button" :aria-expanded="filterOpen" aria-controls="note-filter-panel" @click="filterOpen = !filterOpen"><i class="ri-equalizer-line"></i>筛选与排序<span v-if="activeFilterCount" class="filter-count">{{ activeFilterCount }}</span><i class="ri-arrow-down-s-line"></i></button>
            <div v-if="filterOpen" id="note-filter-panel" class="filter-panel" role="group" aria-label="小记筛选与排序">
              <div class="filter-group"><strong>标签</strong><div class="filter-options"><button type="button" :aria-pressed="selectedTag === ''" @click="selectedTag = ''">全部</button><button type="button" :aria-pressed="selectedTag === null" @click="selectedTag = null">无标签</button><button v-for="tag in availableTags" :key="tag" type="button" :aria-pressed="selectedTag === tag" @click="selectedTag = tag"># {{ tag }}</button></div></div>
              <div class="filter-group"><strong>作者</strong><select v-model="selectedAuthor" aria-label="按作者筛选"><option value="">全部作者</option><option v-for="author in availableAuthors" :key="author.id" :value="author.id">{{ author.name }}</option></select></div>
              <div class="filter-group"><strong>图片</strong><div class="filter-options"><button v-for="option in [{ value: 'all', label: '全部' }, { value: 'yes', label: '有图' }, { value: 'no', label: '无图' }]" :key="option.value" type="button" :aria-pressed="imageFilter === option.value" @click="imageFilter = option.value as typeof imageFilter">{{ option.label }}</button></div></div>
              <div class="filter-group"><strong>更新时间</strong><div class="filter-options"><button v-for="option in [{ value: 'all', label: '不限' }, { value: 'today', label: '今天' }, { value: 'yesterday', label: '昨天' }, { value: 'earlier', label: '以前' }]" :key="option.value" type="button" :aria-pressed="timeFilter === option.value" @click="timeFilter = option.value as typeof timeFilter">{{ option.label }}</button></div></div>
              <div class="filter-group"><strong>排序</strong><select v-model="sort" aria-label="小记排序"><option value="updatedDesc">最近更新</option><option value="createdDesc">最近创建</option><option value="updatedAsc">最早更新</option></select></div>
              <div class="filter-group"><strong>查看</strong><div class="filter-options"><button type="button" :aria-pressed="discoveryMode === 'notes'" @click="selectDiscoveryMode('notes')">小记</button><button type="button" :aria-pressed="discoveryMode === 'saved'" @click="selectDiscoveryMode('saved')">已收藏的发现</button><button type="button" :aria-pressed="discoveryMode === 'legacy'" @click="selectDiscoveryMode('legacy')">旧版生成内容</button></div></div>
              <div class="filter-footer"><button type="button" @click="addingTag = !addingTag; filterOpen = false">新增标签</button><button type="button" @click="managingTags = !managingTags; filterOpen = false">管理标签</button><button type="button" @click="resetFilters">清除筛选</button></div>
            </div>
          </div>
          <button class="reader-toggle" type="button" aria-label="阅读模式" :disabled="!matching.length" @click="startReading(undefined, $event)"><i class="ri-book-open-line" aria-hidden="true"></i><span>阅读模式</span></button>
          <button class="reader-toggle" type="button" aria-label="小记 AI 设置" @click="noteSettingsOpen = true"><i class="ri-settings-3-line" aria-hidden="true"></i><span>AI 设置</span></button>
          <button class="refresh" :disabled="loading || refreshing" :aria-busy="refreshing" title="刷新清单" aria-label="刷新小记清单" @click="reload"><i :class="refreshing ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i></button>
        </div>
        <NoteDiscoverySavedList v-if="discoveryMode !== 'notes'" :mode="discoveryMode" :member-id="session.currentUser.id" @source="openDiscoverySource" />
        <template v-else>
        <NoteDiscoveryInline v-if="discoveries.current.value" :item="discoveries.current.value" @changed="discoveryChanged" @expanded="discoveryExpanded = $event" @source="openDiscoverySource" />
        <p v-if="discoveryUndo" class="discovery-undo" role="status">已收起这条发现 <button type="button" @click="undoDiscovery">撤销</button></p>
        <div v-if="managingTags" class="new-tag-form" aria-label="标签管理">
          <select v-model="sourceTag" aria-label="待处理标签" :disabled="tagSaving"><option value="">选择标签</option><option v-for="tag in availableTags" :key="tag" :value="tag">{{ tag }}</option></select>
          <button :disabled="tagSaving || !sourceTag" @click="manageTag(false)">删除标签（保留文章）</button>
          <select v-model="targetTag" aria-label="合并到标签" :disabled="tagSaving"><option value="">合并到…</option><option v-for="tag in availableTags.filter(item => item !== sourceTag)" :key="tag" :value="tag">{{ tag }}</option></select>
          <button :disabled="tagSaving || !sourceTag || !targetTag || sourceTag === targetTag" @click="manageTag(true)">{{ tagSaving ? '保存中…' : '合并标签' }}</button>
        </div>
        <form v-if="addingTag" class="new-tag-form" @submit.prevent="createTag">
          <input v-model="newTag" aria-label="新标签名称" placeholder="标签名称" maxlength="32" :disabled="creatingTag">
          <button type="submit" :disabled="creatingTag || !newTag.trim()">{{ creatingTag ? '正在保存…' : tagError ? '重试新增' : '保存标签' }}</button>
          <span v-if="tagError" class="error" role="alert">{{ tagError }}</span>
        </form>
        <p v-if="tagsLoading" class="tag-status" role="status">正在读取标签…</p>
        <p v-if="pinError" class="error" role="alert">{{ pinError }}<button v-if="!pinsReady" @click="loadPins">重试读取置顶</button></p>
        <p v-if="tagsError" class="tag-status error" role="alert">{{ tagsError }}<button @click="loadTags">重试标签</button></p>
        <div class="list-summary"><span>{{ matching.length }} 条小记</span><span>{{ filterSummary }}</span></div>
        <p v-if="loadError" class="empty error" role="alert">小记读取失败，已有内容和草稿仍保留。<button @click="reload">重试加载</button></p>
        <p v-if="loading" class="empty" role="status">正在读取小记…</p>
        <p v-else-if="!loadError && !matching.length" class="empty">{{ query || activeFilterCount ? '没有找到相关小记，调整筛选条件试试。' : '还没有小记，写下第一条想法吧。' }}</p>
        <p v-if="retainedEdits.length" class="tag-status" role="status">正在编辑的小记会保留在列表中，保存或收起后恢复筛选结果。</p>
        <article v-for="note in visible" :key="note.id" class="note-card" :class="{ 'fixed-height': isNotePreviewExpandable(note.id) && !isNotePreviewExpanded(note.id) && !editingIds.includes(note.id), expanded: isNotePreviewExpanded(note.id) && !editingIds.includes(note.id), highlighted: highlighted === note.id, editing: editingIds.includes(note.id), 'delete-confirming': confirmingDeleteId === note.id }" :data-note-id="note.id"
          tabindex="0" :aria-expanded="isNotePreviewExpandable(note.id) ? isNotePreviewExpanded(note.id) : undefined" :title="isNotePreviewExpandable(note.id) ? isNotePreviewExpanded(note.id) ? '单击收起全文，按 Enter 阅读，双击编辑' : '单击展开全文，按 Enter 阅读，双击编辑' : '双击编辑，按 Enter 阅读'" :aria-label="`小记：${note.title}，${isNotePreviewExpandable(note.id) ? `单击${isNotePreviewExpanded(note.id) ? '收起' : '展开'}全文，` : ''}按 Enter 开始阅读，双击编辑`"
          @click="scheduleCardPreview(note.id, $event)" @dblclick="editNote(note.id, $event)" @keydown.enter.self.prevent="startReading(note.id, $event)" @keydown.esc="cancelDeleteOnEscape(note.id, $event)" @keydown="openNoteMenu($event, note.id)" @contextmenu="openNoteMenu($event, note.id)">
          <div class="note-meta"><span class="author">{{ authorName(note.author) }}</span><time>更新于 {{ timeLabel(note.updated || note.created) }}</time>
            <div class="stage-list" :class="{ 'stage-list-complete': !progressVisible(note.id) }" role="group" aria-label="AI 产出与按需操作">
              <NoteAiStageButton v-for="stage in aiStages" :key="stage" :class="`stage-${workflowProgress(note.id)?.[stage] || 'pending'}`" :hold-enabled="stage === 'title'" :disabled="stage === 'title' ? retryingEnrichment.includes(note.id) : stageDisabled(note.id, stage)" :title="`${stageLabel(stage)}：${stageActionText(note.id, stage)}${stage === 'title' ? '；长按 3 秒一键四连' : ''}`" :aria-label="`${stageLabel(stage)}${stageActionText(note.id, stage)}${stage === 'title' ? '，长按 3 秒一键四连' : ''}`" @activate="onStageClick(note, stage)" @four="retryOrStart(note, 'four')" @charging="chargingNote = $event ? note.id : ''">{{ stageIcon(stage) }}</NoteAiStageButton>
            </div>
            <span v-if="chargingNote === note.id" class="four-charge-hint" role="status">按住 3 秒 · 松开取消</span>
            <span class="note-meta-trailing"><button class="note-four-action" type="button" :disabled="retryingEnrichment.includes(note.id) || fourComplete(note.id)" :aria-label="`一键四连：${note.title}`" title="只补齐未完成的标题、配图、润色、标签" @click.stop="retryOrStart(note, 'four')"><i class="ri-sparkling-line" aria-hidden="true"></i>{{ retryingEnrichment.includes(note.id) ? '接续中…' : fourComplete(note.id) ? '四项已齐备' : '一键四连' }}</button><span v-if="!editingIds.includes(note.id)" class="note-preview-actions"><button class="note-preview-action" type="button" :aria-label="`预览小记：${note.title}`" @click.stop="startReading(note.id, $event)"><i class="ri-eye-line" aria-hidden="true"></i>预览</button><button class="note-preview-choice" type="button" :aria-label="`选择预览方式：${note.title}`" aria-haspopup="menu" @click.stop="openPreviewChoices(note.id, $event)"><i class="ri-arrow-down-s-line" aria-hidden="true"></i></button></span><button v-if="!editingIds.includes(note.id)" class="note-edit-action" type="button" :aria-label="`继续编辑小记：${note.title}`" @click.stop="editNote(note.id)"><i class="ri-edit-line" aria-hidden="true"></i>继续编辑</button><span v-if="highlighted === note.id && !editingIds.includes(note.id)" class="saved">已保存</span><span class="card-quick-actions"><button v-if="cardImages(note).length" type="button" aria-label="复制小记图文" title="复制图文到 ChatGPT" @click.stop="openMediaCopy(note)"><i class="ri-file-copy-2-line" aria-hidden="true"></i></button><button type="button" aria-label="查看原文" title="查看原文" @click.stop="readOriginals(note.id, $event)"><i class="ri-history-line" aria-hidden="true"></i></button><button type="button" :disabled="!pinsReady || pinSaving" :aria-label="pinnedIds.includes(note.id) ? '取消置顶' : '置顶'" :aria-pressed="pinnedIds.includes(note.id)" :title="pinnedIds.includes(note.id) ? '取消置顶' : '置顶'" @click="togglePin(note.id)"><i class="ri-pushpin-line"></i></button><button type="button" aria-label="复制分享链接" title="复制分享链接" @click="copyText(docShareUrl(note.id), '公开分享链接已复制')"><i class="ri-share-forward-line"></i></button><button type="button" class="card-delete-icon" :ref="value => setDeleteButton(note.id, value)" :aria-controls="confirmingDeleteId === note.id ? `note-delete-confirm-${note.id}` : undefined" :aria-label="`删除小记：${note.title}`" :aria-expanded="confirmingDeleteId === note.id" title="删除小记（移入回收站）" :disabled="!!deletingId" @click="toggleDeleteConfirmation(note.id)"><i class="ri-delete-bin-line" aria-hidden="true"></i></button><RouterLink class="full-doc-icon" :to="{ path: `/kb/${note.kbId}`, query: { doc: note.id } }" aria-label="完整文档" title="完整文档"><i class="ri-file-text-line" aria-hidden="true"></i></RouterLink></span><span v-if="noteCounts[note.id] !== undefined && !editingIds.includes(note.id)" class="note-count" :aria-label="`正文 ${noteCounts[note.id]?.toLocaleString('zh-CN')} 字`">{{ noteCounts[note.id]?.toLocaleString('zh-CN') }} 字</span></span></div>
            <p v-if="!editingIds.includes(note.id) && (noteDrafts[note.id] || progressVisible(note.id) && workflowMessage(note.id))" class="workflow-status" role="status">{{ noteDrafts[note.id] ? '草稿已保护 · 有修改待保存' : workflowMessage(note.id) }} <button v-if="!noteDrafts[note.id] && (serverFailed(note.id) || canRetryAutoNoteEnrichment(session.currentUser.id, note.id) && noteEnrichmentStatus[note.id]?.includes('未完成'))" type="button" :disabled="retryingEnrichment.includes(note.id)" @click="retryOrStart(note)">{{ retryingEnrichment.includes(note.id) ? '重试中…' : '重试' }}</button></p>
          <button v-if="query && noteSearchHit(note)" class="note-search-hit" type="button" @click.stop="locateSearchNote(note.id, query, noteSearchHit(note)?.field === '正文')">
            {{ noteSearchHit(note)!.field }}命中 · {{ noteSearchHit(note)!.before }}<mark>{{ noteSearchHit(note)!.match }}</mark>{{ noteSearchHit(note)!.after }} <span>定位</span>
          </button>
          <p v-if="searchFocus.id === note.id && !matching.some(item => item.id === note.id)" class="workflow-status">搜索定位 · 原有筛选已保留，额外显示此条</p>
          <NoteComposer :search-request="String(searchRequest)" :search-term="searchFocus.id === note.id ? searchFocus.term : ''" v-if="editingIds.includes(note.id)" :ref="value => setNoteComposer(note.id, value)" :key="`${session.currentUser.id}:${note.id}`" :note="note" inline autofocus :expanded="expandedIds.includes(note.id)" :initial-fullscreen="editAnchors[note.id]?.fullscreen" :initial-position="editAnchors[note.id]?.position" :initial-viewport-top="editAnchors[note.id]?.viewportTop" :tag-options="availableTags" :workflow-message="workflowMessage(note.id)"
            @tag-created="tagNames = [...new Set([...tagNames, $event])]" @draft-change="onDraftChange(note.id, $event)" @saved="onEdited(note.id, $event)" @close="closeEditing(note.id)" />
          <template v-else>
          <div class="note-heading"><h2>{{ displayedTitle(note) }}</h2><div v-if="displayedTags(note).length" class="card-tags"><button v-for="tag in displayedTags(note)" :key="tag" @click="selectedTag = tag"># {{ tag }}</button></div></div>
          <div class="note-content" :class="{ 'has-images': notePreviews.get(note.id)?.length }" @click="openInlineImage">
            <div class="note-body" :ref="value => setNotePreviewBody(note.id, value)"><MarkdownEditor :search-request="String(searchRequest)" :search-term="searchFocus.id === note.id ? searchFocus.term : ''" :ref="value => setNoteEditor(note.id, value)" :model-value="displayedMarkdown(note) || note.title" :document="displayedContent(note).document" :readonly="true" preview-images image-references note-media-copy :active-image-reference="cardActiveImages[note.id] ?? null" bare advanced allow-h1 :min-height="0" @image-reference="locateCardImage(note.id, $event)" @character-count="onNoteCharacterCount(note.id, $event)" /></div>
            <aside v-if="notePreviews.get(note.id)?.length" class="note-images" :class="{ expanded: isNotePreviewExpanded(note.id) }" aria-label="小记图片总览">
              <span>图片总览 <b>{{ notePreviews.get(note.id)?.length }} 张</b></span>
              <figure v-for="(image, index) in notePreviews.get(note.id)" :key="`${image.src}:${index}`" class="note-image-item" :class="{ active: cardActiveImages[note.id] === index + 1 }" :data-card-image="index + 1" :aria-label="`图 ${index + 1} ${image.alt}`">
              <button class="note-image" type="button" :title="image.alt || `全屏阅读图片 ${index + 1}`" @click.stop="startReading(note.id)">
                <img :src="notePreviewSource(image.src)" :alt="image.alt || `小记图片 ${index + 1}`" loading="lazy">
              </button>
              <figcaption><span>图 {{ index + 1 }}</span><button class="note-image-return" type="button" :aria-label="`返回正文图 ${index + 1}的位置`" @click.stop="returnToCardImageReference(note.id, index + 1)">返回正文</button></figcaption>
              </figure>
            </aside>
          </div>
          <div v-if="isNotePreviewExpandable(note.id)" class="note-read-guide"><span v-if="!isNotePreviewExpanded(note.id)" aria-hidden="true">继续阅读这篇小记</span><button class="note-read-action" type="button" :aria-expanded="isNotePreviewExpanded(note.id)" @click.stop="toggleNotePreview(note.id)"><i :class="isNotePreviewExpanded(note.id) ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'" aria-hidden="true"></i>{{ isNotePreviewExpanded(note.id) ? '收起全文' : '展开全文' }}</button></div>
          </template>
          <Teleport to="body"><div v-if="confirmingDeleteId === note.id" :ref="setDeleteConfirmation" :id="`note-delete-confirm-${note.id}`" class="card-delete-confirm" :data-delete-note-id="note.id" :style="deleteConfirmationStyle" role="dialog" :aria-label="`确认删除小记：${note.title}`">
            <span>将此小记移入回收站？7 天内可恢复。{{ editingIds.includes(note.id) ? '当前未保存的编辑会丢失。' : '' }}</span>
            <span v-if="!data.apiLive" class="card-delete-offline">服务尚未连接，暂时不能删除。</span>
            <p v-if="deleteError?.id === note.id" class="card-delete-error" role="alert">{{ deleteError.message }}</p>
            <div class="card-delete-actions"><button type="button" class="card-delete-cancel" :disabled="!!deletingId" @click="closeDeleteConfirmation(true)">取消</button>
            <button type="button" class="card-delete-submit" :disabled="!!deletingId || !data.apiLive" @click="deleteNote(note.id)">{{ deletingId === note.id ? '删除中…' : '确认删除' }}</button></div>
          </div></Teleport>
        </article>
        <button v-if="matching.length > limit" class="load-more" @click="limit += PAGE_SIZE">加载更多（还有 {{ matching.length - limit }} 条）</button>
        </template>
        <ContextActionMenu ref="contextMenu" />
      </section>
    </div>
    <button v-if="showBackToTop" class="notes-back-top" type="button" aria-label="回到顶部" title="回到顶部" @click="backToTop"><i class="ri-rocket-2-line" aria-hidden="true"></i></button>
    <NoteAiSettingsDialog v-model:open="noteSettingsOpen" />
    <Teleport to="body">
      <div v-if="reading && readingNote" ref="readerDialog" class="notes-reader" :data-page-layout="pagePreferences.pageLayoutStyle" :class="{ 'reader-full': readerMode === 'full', 'reader-narrow': contentWidth === 'narrow', 'directory-open': readerDirectoryOpen }" :inert="previewImage ? true : undefined" role="dialog" aria-modal="true" aria-label="小记阅读模式">
        <header class="reader-topbar">
          <strong><i class="ri-book-open-line" aria-hidden="true"></i> 阅读模式 <small>只读</small></strong>
          <span class="reader-copy-status" role="status" aria-live="polite">{{ readerCopyStatus }}</span>
          <div class="reader-layout-actions">
            <button type="button" aria-label="复制小记分享链接" title="复制当前小记的公开只读分享链接" :disabled="readerCopyBusy" @click="copyReadingNote('share')"><i class="ri-share-forward-line" aria-hidden="true"></i><span>分享</span></button>
            <button type="button" aria-label="复制小记正文" :title="originalsOpen ? '返回当前内容后复制正文' : '复制当前正文（Markdown）'" :disabled="readerCopyBusy || originalsOpen" @click="copyReadingNote('body')"><i class="ri-file-copy-line" aria-hidden="true"></i><span>复制正文</span></button>
            <button v-if="cardImages(readingNote).length" type="button" aria-label="复制当前小记图文" :disabled="originalsOpen" @click="openMediaCopy(readingNote)"><i class="ri-file-copy-2-line" aria-hidden="true"></i><span>复制图文</span></button>
            <button type="button" :aria-pressed="originalsOpen" @click="toggleOriginals">{{ originalsOpen ? '返回当前内容' : '查看原文' }}</button>
            <div class="reader-mode-switch" role="group" aria-label="阅读布局"><button type="button" :aria-pressed="readerMode === 'side'" @click="changeReaderMode('side')"><i class="ri-layout-left-line" aria-hidden="true"></i>侧栏速览</button><button type="button" :aria-pressed="readerMode === 'full'" @click="changeReaderMode('full')"><i class="ri-fullscreen-line" aria-hidden="true"></i>全屏阅读</button></div>
            <NoteWidthSwitch v-if="readerMode === 'full'" v-model="contentWidth" label="阅读宽度" />
            <button ref="readerClose" type="button" aria-label="退出阅读模式" title="退出阅读模式（Esc）" @click="stopReading"><i class="ri-close-line" aria-hidden="true"></i><span>关闭</span></button>
          </div>
        </header>
        <div ref="readerLayout" class="reader-layout" :class="{ resizing: readerWidths.resizing.value }" :style="readerWidths.style.value">
          <aside class="reader-directory" aria-label="当前列表的小记目录"><div class="reader-directory-heading"><strong>小记目录</strong><span>{{ readerNotes.length }} 篇</span><button type="button" class="reader-directory-close" aria-label="收起小记目录" @click="closeReaderLayer"><i class="ri-close-line" aria-hidden="true"></i></button></div><nav aria-label="选择小记"><button v-for="note in readerNotes" :key="note.id" type="button" :aria-current="note.id === readingId ? 'true' : undefined" @click="selectReadingNote(note.id)"><strong>{{ displayedTitle(note) }}</strong><small>{{ authorName(note.author) }} · {{ timeLabel(note.updated || note.created) }}</small></button></nav></aside>
          <div class="reader-resizer reader-directory-resizer" role="separator" tabindex="0" aria-orientation="vertical" aria-label="小记目录宽度" :aria-valuemin="readerWidths.minimum.directory" :aria-valuemax="readerWidths.limit('directory')" :aria-valuenow="readerWidths.widths.value.directory" title="拖拽调整宽度，双击恢复默认；方向键微调" @pointerdown="readerWidths.start('directory', $event)" @keydown="readerWidths.keydown('directory', $event)" @dblclick="readerWidths.reset('directory')"><span></span></div>
          <div class="reader-main" :inert="readerDirectoryOpen ? true : undefined"><button type="button" class="reader-directory-toggle" @click="openReaderDirectory"><i class="ri-list-check" aria-hidden="true"></i>小记目录 <span>{{ readingIndex + 1 }} / {{ readerNotes.length }}</span></button>
            <article :key="readingNote.id" ref="readerPage" class="reader-page" @scroll="readerRestorePending = false">
              <div class="reader-columns"><div class="reader-article"><NoteOriginalReader v-if="originalsOpen" ref="readerOriginal" :doc-id="readingNote.id" :member-id="session.currentUser.id" :current-rev="readingNote.rev" image-references :active-image-reference="readerActiveImage" @reading-content="readerOriginalContent = $event" @image-reference="locateReadingImage" @source-change="changeOriginalSource" @ready="restoreReaderPosition" /><template v-else><div class="reader-meta"><span>{{ authorName(readingNote.author) }}</span><time>{{ timeLabel(readingNote.updated || readingNote.created) }}</time><span>{{ noteCounts[readingNote.id]?.toLocaleString('zh-CN') ?? '' }} 字</span></div>
              <h2>{{ displayedTitle(readingNote) }}</h2>
              <div v-if="displayedTags(readingNote).length" class="reader-tags"><span v-for="tag in displayedTags(readingNote)" :key="tag"># {{ tag }}</span></div>
              <div class="reader-content" @dblclick="editReadingNote"><div class="reader-body"><MarkdownEditor ref="readerEditor" :model-value="displayedMarkdown(readingNote) || readingNote.title" :document="displayedContent(readingNote).document" :readonly="true" preview-images image-references note-media-copy :active-image-reference="readerActiveImage" bare advanced allow-h1 :min-height="0" @image-reference="locateReadingImage" @character-count="restoreReaderPosition" /></div></div></template></div>
              <div class="reader-resizer reader-images-resizer" role="separator" tabindex="0" aria-orientation="vertical" aria-label="图片清单宽度" :aria-valuemin="readerWidths.minimum.images" :aria-valuemax="readerWidths.limit('images')" :aria-valuenow="readerWidths.widths.value.images" title="拖拽调整宽度，双击恢复默认；方向键微调" @pointerdown="readerWidths.start('images', $event)" @keydown="readerWidths.keydown('images', $event)" @dblclick="readerWidths.reset('images')"><span></span></div>
              <NoteReadingImages :key="`${readingNote.id}:${originalsOpen ? originalPositionKey : 'current'}`" ref="readerImagesPanel" :images="readerImages" :active="readerActiveImage" :unavailable="originalsOpen && !readerOriginalContent" @return="returnToImageReference" @fullscreen="openImage" /></div>
            </article>
            <footer class="reader-controls"><button type="button" aria-label="上一篇小记（←）" :disabled="readingIndex <= 0" @click="moveReader(-1)"><i class="ri-arrow-left-line" aria-hidden="true"></i>上一篇</button><span>{{ readingIndex + 1 }} / {{ readerNotes.length }}<small> · ← → 翻篇 · Esc 退出</small></span><button type="button" aria-label="下一篇小记（→）" :disabled="readingIndex >= readerNotes.length - 1" @click="moveReader(1)">下一篇<i class="ri-arrow-right-line" aria-hidden="true"></i></button></footer>
          </div>
        </div>
      </div>
      <div v-if="previewImage" ref="lightbox" class="note-image-lightbox" role="dialog" aria-modal="true"
        :aria-label="previewImage.alt || '小记图片预览'" tabindex="-1" @click.self="closeImage" @keydown.esc.stop.prevent="closeImage" @keydown="trapReaderFocus($event, lightbox)" @contextmenu="openImageMenu">
        <button type="button" class="note-image-close" aria-label="关闭图片预览" @click="closeImage"><i class="ri-close-line"></i></button>
        <img :src="previewImage.src" :alt="previewImage.alt">
      </div>
    </Teleport>
  </main>
</template>

<style scoped>
.notes-page { width: 100%; max-width: none; margin: 0 auto; padding: 12px 28px 24px; box-sizing: border-box; color: var(--wao-text-2); }
.page-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.heading-title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; min-width: 0; }
.daily-note-count { display: inline-flex; align-items: center; gap: 6px; padding: 5px 9px; border: 1px solid var(--wao-line); border-radius: 8px; color: var(--wao-text-2); background: var(--wao-bg-card); font-size: 12px; white-space: nowrap; }
.daily-note-count i { color: var(--wao-primary-text); }
.daily-note-count.error { color: var(--wao-danger-text, #b42318); }
.daily-note-count.error i { color: inherit; }
.daily-note-count button { border: 0; padding: 0; background: transparent; color: var(--wao-primary-text); font: inherit; text-decoration: underline; }
h1 { display: flex; flex: none; white-space: nowrap; gap: 10px; align-items: center; font-size: 18px; margin: 0; color: var(--wao-text); }
h1 i { color: #7a5cc6; }
.page-heading p { margin: 0; color: var(--wao-text-faint); font-size: 13px; }
.page-heading > span { color: var(--wao-text-faint); white-space: nowrap; font-size: 12px; }
.writing-toggle { margin: 0; white-space: nowrap; border: 1px solid var(--wao-line-control); }
.writing-toggle i { margin-right: 6px; }
.notes-workspace { display: grid; grid-template-columns: clamp(240px, var(--notes-writing-width, 33.333333%), calc(100% - 340px)) 20px minmax(0, 1fr); gap: 0; align-items: stretch; }
.writing-resizer { display: flex; align-items: center; justify-content: center; min-height: 80px; cursor: col-resize; touch-action: none; outline: none; }
.writing-resizer span { width: 3px; height: 48px; border-radius: 3px; background: var(--wao-line-control); transition: background .15s, height .15s; }
.writing-resizer:hover span, .writing-resizer:focus-visible span, .resizing .writing-resizer span { height: 80px; background: var(--wao-primary); }
.writing-resizer:focus-visible { box-shadow: inset 0 0 0 2px var(--wao-primary); border-radius: 6px; }
.notes-workspace.resizing { cursor: col-resize; user-select: none; }
.resizing .writing-pane, .resizing .notes-list { pointer-events: none; }
.notes-workspace.writing-collapsed { grid-template-columns: minmax(0, 1fr); }
.writing-collapsed .notes-list { border-left: 0; }
.writing-pane { min-width: 0; position: sticky; top: 20px; }
.writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: min(650px, calc(100dvh - 210px)); max-height: calc(100dvh - 155px); }
.notes-list { min-width: 0; padding: 24px 20px 40px; border: 1px solid var(--wao-line); border-right: 0; border-bottom: 0; background: var(--wao-bg-sub); scroll-padding-block: 24px; }
.list-toolbar, .search, .note-meta, .note-actions, .sort { display: flex; align-items: center; gap: 10px; }
.list-toolbar { margin-bottom: 8px; gap: 8px; flex-wrap: wrap; position: relative; }
button, a, select { color: var(--wao-text-3); font-size: 12px; }
button { cursor: pointer; border: 0; border-radius: 7px; background: transparent; padding: 7px 9px; }
button:hover, button[aria-pressed="true"] { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
button:disabled { cursor: default; opacity: .5; }
.search { flex: 1 1 auto; min-width: 120px; border: 1px solid var(--wao-line-control); border-radius: 8px; padding: 7px 10px; gap: 6px; }
input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: inherit; font-size: 12px; }
.search:focus-within { border-color: var(--wao-primary); }
.filter-control { position: relative; flex: none; }
.filter-toggle { white-space: nowrap; border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); }
.filter-toggle i:first-child { margin-right: 5px; }
.filter-count { display: inline-grid; place-items: center; width: 17px; height: 17px; margin: 0 4px; border-radius: 50%; background: var(--wao-primary); color: white; }
.filter-panel { position: absolute; top: calc(100% + 6px); right: 0; z-index: 10; width: min(340px, calc(100vw - 40px)); max-height: min(68vh, 550px); overflow-y: auto; padding: 15px; border: 1px solid var(--wao-line-control); border-radius: 12px; background: var(--wao-bg-card); box-shadow: 0 12px 38px rgba(30, 24, 55, .15); }
.filter-group { display: grid; gap: 8px; margin-bottom: 14px; }
.filter-group strong { color: var(--wao-text-2); font-size: 12px; }
.filter-options { display: flex; gap: 6px; flex-wrap: wrap; }
.filter-options button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-sub); }
.filter-options button[aria-pressed="true"] { border-color: var(--wao-primary); background: var(--wao-bg-hover); color: var(--wao-primary-text); }
.filter-group select { width: 100%; padding: 8px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-sub); color: var(--wao-text-2); }
.filter-footer { display: flex; justify-content: space-between; border-top: 1px solid var(--wao-line); padding-top: 8px; }
.tags-toolbar { display: flex; align-items: flex-start; gap: 5px; min-width: 0; }
.tags-toolbar button { flex: none; padding: 4px 7px; font-size: 11px; line-height: 1.3; white-space: nowrap; }
.tag-filter-scroll { display: flex; gap: 5px; min-width: 0; overflow-x: auto; padding: 2px 0 5px; }
.tag-filter { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); }
.tag-filter span { color: var(--wao-text-faint); margin-left: 4px; }
.new-tag-toggle { border: 1px dashed var(--wao-line-control); }
.new-tag-form { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.new-tag-form input { flex: 1; min-width: 100px; border: 1px solid var(--wao-line-control); border-radius: 7px; padding: 8px 10px; }
.new-tag-form button { background: var(--wao-primary); color: white; }
.new-tag-form .error { flex-basis: 100%; }
.tag-status { font-size: 12px; color: var(--wao-text-faint); }
.list-summary { display: flex; justify-content: space-between; gap: 10px; margin: 9px 0 12px; font-size: 11px; color: var(--wao-text-faint); }
.note-card { position: relative; box-sizing: border-box; background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: 12px; padding: 17px 19px; margin-bottom: 16px; transition: border-color .18s, box-shadow .18s, transform .18s; }
.note-card.fixed-height { min-height: 300px; height: 300px; max-height: 300px; overflow: hidden; padding-bottom: 36px; }
.note-card.expanded, .note-card.editing { height: auto; max-height: none; overflow: visible; }
.note-card:hover, .note-card:focus-within { border-color: var(--wao-primary); box-shadow: 0 4px 16px rgba(59, 47, 91, .07); }
.note-card.editing:hover, .note-card.editing:focus-within { transform: none; }
.note-card.delete-confirming { transform: none; transition: border-color .18s, box-shadow .18s; }
.note-card.highlighted, .note-card.editing { border-color: var(--wao-primary); }
.note-card:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
.note-meta { font-size: 11px; color: var(--wao-text-faint); margin-bottom: 16px; flex-wrap: wrap; gap: 5px 9px; }
.stage-list { display: inline-flex; align-items: center; gap: 4px; }
.four-charge-hint { color:var(--wao-primary-text); font-size:11px; }
.note-four-action { display:inline-flex; align-items:center; gap:4px; padding:5px 8px; border:0; border-radius:7px; background:var(--wao-bg-hover); color:var(--wao-primary-text); font-size:11px; cursor:pointer; }
.note-four-action:disabled { cursor:default; color:var(--wao-text-faint); background:var(--wao-bg-sub); }
.note-four-action:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
.stage-icon { display: inline-grid; place-items: center; width: 22px; height: 22px; padding: 0; border: 1px solid var(--wao-line-control); border-radius: 50%; font-size: 12px; font-weight: 700; }
.stage-icon:not(:disabled) { cursor: pointer; }
.stage-icon:not(:disabled):hover, .stage-icon:not(:disabled):focus-visible { border-color: var(--wao-primary); color: var(--wao-primary-text); outline: none; box-shadow: 0 0 0 2px var(--wao-bg-hover); }
.stage-pending, .stage-skipped { color: var(--wao-text-2); background: var(--wao-bg-card); }
.stage-done { color: #248455; border-color: #a6dac0; background: #effaf4; }
.stage-running { color: #7453bd; border-color: #c6b4ef; background: #f5f0ff; animation: stage-pulse 1.5s infinite; }
.stage-failed { color: var(--wao-danger-strong); border-color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
.stage-skipped { opacity: 1; }
.stage-list-complete { opacity: 0; visibility: hidden; transition: opacity .16s; }
.note-card:hover .stage-list-complete, .note-card:focus-within .stage-list-complete { opacity: 1; visibility: visible; }
.card-quick-actions { display: inline-flex; gap: 2px; opacity: 0; visibility: hidden; transition: opacity .16s, visibility .16s; }
.note-card:hover .card-quick-actions, .note-card:focus-within .card-quick-actions { opacity: 1; visibility: visible; }
.note-card.delete-confirming .card-quick-actions { opacity: 1; visibility: visible; }
.card-quick-actions button, .card-quick-actions .full-doc-icon { display: inline-grid; place-items: center; width: 24px; height: 24px; padding: 3px; border-radius: 6px; font-size: 14px; text-decoration: none; }
.card-quick-actions .card-delete-icon { color: var(--wao-danger-strong); }
.card-quick-actions .card-delete-icon:hover, .card-quick-actions .card-delete-icon[aria-expanded="true"] { color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
.card-quick-actions .full-doc-icon:hover, .card-quick-actions .full-doc-icon:focus-visible { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
.workflow-status { margin: -6px 0 12px; color: var(--wao-text-faint); font-size: 11px; }
.workflow-status button { color: var(--wao-primary-text); text-decoration: underline; }
@keyframes stage-pulse { 50% { box-shadow: 0 0 0 4px rgba(116,83,189,.14); } }
.note-meta-trailing { display: inline-flex; align-items: center; gap: 12px; margin-left: auto; white-space: nowrap; }
.note-count { color: var(--wao-text-faint); font-variant-numeric: tabular-nums; }
.author { color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.saved { color: var(--wao-success-strong); margin-left: auto; }
.note-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 5px 9px; margin: 0 0 9px; }
.note-card.fixed-height .note-heading { max-height: 52px; overflow: hidden; }
.note-heading h2 { margin: 0; color: var(--wao-text-1); font-size: 16px; line-height: 1.45; font-weight: 600; overflow-wrap: anywhere; }
.note-card.fixed-height .note-heading h2 { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.note-content { min-width: 0; }
.note-content.has-images { display: grid; grid-template-columns: minmax(0, 1fr) minmax(90px, 24%); gap: 12px; align-items: start; }
.note-card.expanded .note-content.has-images { align-items: stretch; }
.note-body { min-height: 0; overflow-wrap: anywhere; line-height: 1.75; font-size: 13px; }
.note-card.fixed-height .note-body { max-height: 170px; overflow: hidden; -webkit-mask-image: linear-gradient(to bottom, #000 calc(100% - 1.6em), transparent); mask-image: linear-gradient(to bottom, #000 calc(100% - 1.6em), transparent); }
.note-body :deep(img.md-img) { display: none; }
.note-images { min-width: 0; max-height: 170px; overflow: auto; display: grid; grid-template-columns: minmax(0, 1fr); align-content: start; gap: 7px; padding-left: 10px; border-left: 1px solid var(--wao-line-control); }
.note-images.expanded { max-height: none; overflow: visible; }
.note-images > span { display: flex; justify-content: space-between; gap: 5px; color: var(--wao-text-faint); font-size: 10px; }
.note-images b { font-weight: 400; white-space: nowrap; }
.note-image { overflow: hidden; padding: 0; border: 1px solid var(--wao-line-control); background: var(--wao-bg-sub); }
.note-image img { display: block; width: 100%; aspect-ratio: 4 / 3; object-fit: cover; }
.note-image-item { margin: 0; min-width: 0; padding: 3px; border: 1px solid transparent; border-radius: 8px; }
.note-image-item.active { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.note-image-item .note-image { display: block; width: 100%; }
.note-image-item figcaption { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 3px; padding-top: 3px; font-size: 11px; color: var(--wao-text-3); }
.note-image-return { padding: 2px 4px; font-size: 11px; color: var(--wao-primary-text); }
.note-image-return:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 1px; }
@media (pointer: coarse) { .note-image-return { min-height: 44px; } }
.note-body :deep(p:first-child) { margin-top: 0; }
.note-body :deep(img.md-img) { max-height: min(32vh, 220px); object-fit: contain; cursor: zoom-in; }
.note-body :deep(.character-count) { display: none !important; }
.note-body :deep(pre), .note-body :deep(table) { max-width: 100%; overflow: auto; }
.note-image-lightbox { position: fixed; inset: 0; z-index: 1200; display: grid; place-items: center; padding: 32px; background: rgba(15, 18, 28, .82); cursor: zoom-out; }
.note-image-lightbox img { max-width: min(96vw, 1600px); max-height: 90vh; object-fit: contain; border-radius: 8px; box-shadow: 0 18px 56px rgba(0, 0, 0, .38); cursor: default; }
.note-image-close { position: fixed; top: 16px; right: 18px; width: 36px; height: 36px; border: 0; border-radius: 50%; background: rgba(255, 255, 255, .16); color: #fff; font-size: 20px; }
.note-image-close:hover { background: rgba(255, 255, 255, .28); color: #fff; }
.card-tags { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.card-tags button { color: var(--wao-primary-text); overflow-wrap: anywhere; max-width: 100%; padding: 3px 7px; }
.card-edit { display: inline-flex; gap: 4px; align-items: center; }
.card-delete { display: inline-flex; gap: 4px; align-items: center; color: var(--wao-danger-strong); }
.card-delete:hover { color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
.card-delete-confirm { position: fixed; z-index: 2300; box-sizing: border-box; display: flex; flex-direction: column; gap: 10px; max-height: calc(100dvh - 24px); overflow: auto; padding: 14px; border: 1px solid var(--wao-line-control); border-radius: 12px; background: var(--wao-bg-card); box-shadow: 0 12px 36px rgba(37,29,60,.18); color: var(--wao-text-2); font-size: 12px; line-height: 1.6; white-space: normal; }
.card-delete-actions { display: flex; justify-content: flex-end; gap: 8px; }
.card-delete-offline { color: var(--wao-danger-strong); }
.card-delete-confirm button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); }
.card-delete-confirm .card-delete-submit { border-color: var(--wao-danger-strong); color: var(--wao-danger-strong); font-weight: 600; }
.card-delete-error { margin: 6px 0 0; color: var(--wao-danger-strong); font-size: 12px; }
.empty { text-align: center; padding: 36px 12px; color: var(--wao-text-faint); font-size: 13px; }
.error { color: var(--wao-danger-strong); font-size: 12px; }
.load-more { display: block; margin: 20px auto; }
.reader-toggle { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); white-space: nowrap; }
.reader-toggle i { margin-right: 5px; }
.note-read-action { position: absolute; right: 13px; bottom: 8px; z-index: 1; display: inline-flex; align-items: center; gap: 5px; padding: 5px 8px; background: var(--wao-bg-card); color: var(--wao-primary-text); border: 1px solid var(--wao-line-control); box-shadow: 0 2px 8px rgba(30,24,55,.08); }
.note-read-action:hover { border-color: var(--wao-primary); }
.note-read-action { transition: border-color .16s, background-color .16s; }
.note-card.expanded .note-read-action { position: static; display: inline-flex; width: fit-content; margin: 12px 0 0 auto; }
.note-read-guide { position: absolute; right: 0; bottom: 0; left: 0; z-index: 1; display: flex; align-items: flex-end; justify-content: flex-end; gap: 12px; min-height: 64px; box-sizing: border-box; padding: 16px 13px 8px; background: linear-gradient(transparent, color-mix(in srgb, var(--wao-bg-card) 90%, transparent)); opacity: 0; pointer-events: none; transition: opacity .16s; }
.note-read-guide > span { padding-bottom: 5px; font-size: 12px; color: var(--wao-text-weak); }
.note-read-guide .note-read-action { position: static; }
.note-card:hover .note-read-guide, .note-card:focus-within .note-read-guide { opacity: 1; pointer-events: auto; }
.note-card.expanded .note-read-guide { position: static; min-height: 0; padding: 0; background: none; opacity: 1; pointer-events: auto; }
@media (hover: none), (pointer: coarse) { .note-read-guide { opacity: 1; pointer-events: auto; } .note-read-action { min-height: 44px; } }
@media (max-width: 400px) { .note-read-guide > span { display: none; } }
@media (prefers-reduced-motion: reduce) { .note-read-guide { transition: none; } }
.notes-reader { position: fixed; inset: 0; z-index: 1150; display: flex; flex-direction: column; background: var(--wao-bg-card); color: var(--wao-text-2); }
.reader-topbar { flex: none; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 20px; border-bottom: 1px solid var(--wao-line); }
.reader-topbar strong { display: flex; align-items: center; gap: 7px; color: var(--wao-text-1); font-size: 15px; }
.reader-topbar small { font-size: 11px; font-weight: 400; color: var(--wao-text-faint); }
.reader-layout-actions, .reader-mode-switch { display: flex; align-items: center; gap: 6px; }
.reader-layout-actions { flex-wrap: wrap; min-width: 0; }
.reader-copy-status { font-size: 12px; color: var(--wao-text-weak); }
.reader-mode-switch { padding: 3px; border-radius: 9px; background: var(--wao-bg-sub); }
.reader-layout-actions button { min-height: 36px; display: inline-flex; align-items: center; justify-content: center; gap: 5px; padding: 6px 10px; border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); white-space: nowrap; }
.reader-mode-switch button { border-color: transparent; background: transparent; font-size: 12px; }
.reader-mode-switch button[aria-pressed='true'] { color: var(--wao-primary-text); background: var(--wao-primary-soft); }
.reader-layout { flex: 1; min-height: 0; display: grid; grid-template-columns: clamp(140px, var(--reader-directory-width, 180px), calc((100vw - 332px) / 2)) 6px minmax(0, 1fr); position: relative; }
.reader-resizer { display: flex; align-items: center; justify-content: center; cursor: col-resize; touch-action: none; outline: none; z-index: 1; }
.reader-resizer span { width: 3px; height: 48px; border-radius: 3px; background: var(--wao-line-control); }
.reader-resizer:hover span, .reader-resizer:focus-visible span, .resizing .reader-resizer span { height: 80px; background: var(--wao-primary); }
.reader-resizer:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: -2px; }
.reader-images-resizer { position: sticky; top: 0; height: calc(100dvh - 150px); }
.reader-layout.resizing { cursor: col-resize; user-select: none; }
.resizing .reader-directory, .resizing .reader-article, .resizing .reader-images { pointer-events: none; }
@media (max-width: 850px) { .reader-resizer { display: none; } }
.reader-directory { min-width: 0; overflow: auto; background: var(--wao-bg-sub); border-right: 1px solid var(--wao-line); padding: 16px 12px; }
.reader-directory-heading { display: flex; align-items: center; gap: 8px; padding: 0 8px 14px; font-size: 13px; }
.reader-directory-heading > span { color: var(--wao-text-faint); font-size: 12px; }
.reader-directory nav { display: grid; gap: 8px; }
.reader-directory nav button { display: block; width: 100%; padding: 13px; text-align: left; border: 1px solid transparent; background: transparent; white-space: normal; overflow-wrap: anywhere; }
.reader-directory nav button[aria-current='true'] { color: var(--wao-primary-text); border-color: var(--wao-primary); background: var(--wao-bg-card); }
.reader-directory nav strong { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; font-weight: 600; line-height: 1.6; }
.reader-directory nav small { display: block; color: var(--wao-text-faint); font-size: 11px; margin-top: 8px; }
.reader-main { min-height: 0; min-width: 0; display: flex; flex-direction: column; }
.reader-page { flex: 1; min-height: 0; overflow: auto; overscroll-behavior: contain; }
.reader-columns { display: grid; grid-template-columns: minmax(0, 1fr) 6px clamp(180px, var(--reader-images-width, 238px), calc((100vw - 332px) / 2)); min-height: 100%; align-items: start; }
.reader-columns > .reader-images { position: sticky; top: 0; max-height: calc(100dvh - 150px); overflow: auto; overscroll-behavior: contain; }
.reader-article { min-width: 0; width: min(100%, 820px); box-sizing: border-box; margin: 0 auto; padding: 28px clamp(24px, 2vw, 32px); }
.reader-full .reader-article { width: min(100%, 1100px); }
.reader-full.reader-narrow .reader-article { width: min(100%, 650px); }
.reader-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; color: var(--wao-text-faint); font-size: 12px; }
.reader-meta span:first-child { color: var(--wao-text-2); font-weight: 650; }
.reader-page h2 { margin: 16px 0 8px; color: var(--wao-text-1); font-size: 25px; line-height: 1.55; overflow-wrap: anywhere; text-wrap: balance; }
.reader-tags { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0 22px; }
.reader-tags span { padding: 4px 9px; border-radius: 999px; background: var(--wao-bg-hover); color: var(--wao-primary-text); font-size: 11px; }
.reader-body { min-width: 0; overflow-wrap: anywhere; font-size: 15px; line-height: 1.95; }
.reader-article :deep(.md-body .tiptap) { font-size: 15px; line-height: 1.95; }
.reader-body :deep(.character-count) { display: none; }
.notes-reader button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.reader-body :deep(pre), .reader-body :deep(table) { max-width: 100%; overflow: auto; }
.reader-controls { flex: none; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 20px; border-top: 1px solid var(--wao-line); }
.reader-controls button { min-height: 36px; background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); white-space: nowrap; }
.reader-controls > span { color: var(--wao-text-faint); font-size: 12px; }
.reader-directory-toggle, .reader-directory-close { display: none; }
@media (max-width: 650px) {
  .reader-topbar { position: relative; padding: 10px 12px; gap: 8px; }
  .reader-topbar strong { min-height: 36px; font-size: 13px; }
  .reader-copy-status { flex: 1; min-width: 0; padding-right: 64px; overflow-wrap: anywhere; }
  .reader-layout-actions { width: 100%; }
  .reader-layout-actions > button[aria-label="退出阅读模式"] { position: absolute; top: 10px; right: 12px; }
  .reader-topbar small { display: none; }
  .reader-layout-actions, .reader-mode-switch { gap: 3px; }
  .reader-layout-actions button { padding: 6px; min-height: 40px; }
  .reader-mode-switch button { font-size: 11px; }
  .reader-layout { grid-template-columns: minmax(0, 1fr); }
  .reader-directory { display: none; }
  .directory-open .reader-directory { display: block; position: absolute; inset: 0; z-index: 2; background: var(--wao-bg-card); }
  .reader-directory-close { display: inline-flex; margin-left: auto; min-height: 40px; align-items: center; }
  .reader-directory-toggle { flex: none; display: flex; gap: 8px; align-items: center; min-height: 44px; padding: 8px 16px; background: var(--wao-bg-sub); border-bottom: 1px solid var(--wao-line); border-radius: 0; }
  .reader-directory-toggle span { color: var(--wao-text-faint); font-size: 12px; }
  .reader-article { padding: 22px 18px; }
  .reader-controls { padding: 10px 12px; }
  .reader-controls small { display: none; }
}
@media (min-width: 651px) and (max-width: 850px) {
  .reader-layout { grid-template-columns: 140px minmax(0, 1fr); }
  .reader-columns { grid-template-columns: minmax(0, 1fr) 190px; }
  .reader-directory { padding: 12px 6px; }
  .reader-directory nav button { padding: 10px 8px; }
  .reader-article { padding: 22px 18px; }
}
@media (max-width: 650px) {
  .reader-layout { grid-template-columns: minmax(0, 1fr); }
  .reader-columns { grid-template-columns: minmax(0, 1fr); }
  .reader-columns > .reader-images { position: static; max-height: none; overflow: visible; }
}

.notes-back-top { position: fixed; right: 28px; bottom: 28px; z-index: 30; display: grid; place-items: center; width: 44px; height: 44px; padding: 0; border: 1px solid color-mix(in srgb, var(--wao-primary) 24%, transparent); border-radius: 50%; background: color-mix(in srgb, var(--wao-bg-card) 88%, transparent); color: var(--wao-primary-text); box-shadow: 0 8px 24px rgba(40, 48, 90, .16); backdrop-filter: blur(10px); font-size: 20px; animation: notes-rocket-in .2s ease-out; }
.notes-back-top:hover { transform: translateY(-2px); box-shadow: 0 12px 28px rgba(40, 48, 90, .22); }
.notes-back-top:focus-visible { outline: 3px solid var(--wao-primary); outline-offset: 3px; }
@keyframes notes-rocket-in { from { opacity: 0; transform: translateY(8px) scale(.86); } to { opacity: 1; transform: translateY(0) scale(1); } }
@media (min-width: 821px) {
  .notes-page { height: 100%; min-height: 0; display: flex; flex-direction: column; overflow: hidden; padding-bottom: 20px; }
  .page-heading { flex: none; }
  .heading-title { gap: 8px; flex-wrap: wrap; }
  .notes-workspace { flex: 1; min-height: 0; align-items: stretch; overflow: hidden; }
  .writing-pane { position: static; min-height: 0; display: flex; flex-direction: column; overflow: auto; overscroll-behavior: contain; }
  .writing-pane :deep(.note-composer:not(.fullscreen)) { flex: 1; min-height: 0; max-height: none; }
  .notes-list { min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
}
@media (max-width: 1100px) { .writing-resizer { display: none; } .notes-page { height: auto; display: block; overflow: visible; padding: 20px; } .notes-workspace { display: grid; gap: 18px; grid-template-columns: minmax(0, 1fr); overflow: visible; } .writing-pane { position: static; min-height: 0; overflow: visible; } .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 330px; max-height: none; } .notes-list { overflow: visible; border-left: 0; border-top: 1px solid var(--wao-line); padding: 24px 10px 40px; } .search { flex: 1 1 50%; } }
@media (max-width: 820px) { .notes-workspace { grid-template-columns: minmax(0, 1fr); gap: 24px; } .writing-pane { position: static; } .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 380px; max-height: none; } .writing-pane :deep(.md-body) { min-height: 230px !important; } .notes-list { border-left: 0; border-top: 1px solid var(--wao-line); padding: 24px 10px 40px; } }
@media (max-width: 600px) { .notes-page { padding: 16px 12px 40px; } .note-card { padding: 14px 12px 16px; } .note-card.fixed-height { height: 286px; min-height: 286px; max-height: 286px; padding: 14px 12px 34px; } .note-card.expanded, .note-card.editing { padding: 14px 12px 34px; } .reader-toggle { padding: 7px; } .reader-toggle span { display: none; } .list-summary { flex-wrap: wrap; } }
@media (max-width: 600px) { .note-content.has-images { grid-template-columns: minmax(0, 1fr) 82px; gap: 8px; } .note-images { max-height: 150px; padding-left: 7px; } }
@media (hover: none) { .stage-list-complete, .card-quick-actions, .note-read-action { opacity: 1; visibility: visible; } .note-card:hover { transform: none; } }
@media (max-width: 600px) {
  .search { flex: 1 1 100%; }
  .filter-control { position: static; }
  .filter-panel { width: 100%; max-width: 340px; }
}
@media (prefers-reduced-motion: reduce) { .note-card, .stage-icon, .notes-back-top { transition: none; animation: none; } }
.search button { flex: none; white-space: nowrap; border: 0; background: transparent; color: var(--wao-primary-text); cursor: pointer; padding: 4px; }
.note-search-hit { width: 100%; text-align: left; white-space: pre-wrap; overflow-wrap: anywhere; border: 0; border-radius: 6px; background: var(--wao-bg-sub); color: var(--wao-text-weak); padding: 8px; margin: 6px 0; cursor: pointer; }
.note-search-hit mark { background: #fff0ae; color: #604a08; }

.note-edit-action { display: inline-flex; align-items: center; gap: 5px; min-height: 32px; color: var(--wao-primary-text); background: var(--wao-bg-hover); font-size: 11px; white-space: nowrap; opacity: 0; visibility: hidden; }
.note-card:hover .note-edit-action, .note-card:focus-within .note-edit-action { opacity: 1; visibility: visible; }
@media (hover: none) { .note-edit-action { opacity: 1; visibility: visible; } }
.notes-page button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@media (max-width: 600px) {
  .note-meta-trailing { flex-wrap: wrap; gap: 6px; max-width: 100%; }
  .note-edit-action { min-height: 40px; padding: 8px 10px; }
  .card-quick-actions { opacity: 1; visibility: visible; gap: 3px; }
  .card-quick-actions button, .card-quick-actions .full-doc-icon { width: 36px; height: 36px; }
  .note-count { margin-left: auto; }
}
</style>

<style scoped>
.note-preview-actions { display: inline-flex; flex: none; opacity: 0; pointer-events: none; transition: opacity .16s; }
.note-card:hover .note-preview-actions, .note-card:focus-within .note-preview-actions { opacity: 1; pointer-events: auto; }
.note-preview-actions button { display: inline-flex; align-items: center; gap: 5px; min-height: 32px; padding: 5px 8px; font-size: 11px; white-space: nowrap; color: var(--wao-primary-text); background: var(--wao-bg-hover); }
.note-preview-action { border-radius: 7px 0 0 7px; }
.note-preview-choice { border-radius: 0 7px 7px 0; border-left: 1px solid var(--wao-line); }
@media (hover: none), (pointer: coarse) { .note-preview-actions { opacity: 1; pointer-events: auto; } .note-preview-actions button { min-height: 40px; } }
</style>
