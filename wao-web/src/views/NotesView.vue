<script setup lang="ts">
import { usePreferenceStore } from '@/stores/preferences'
import { useNoteDisplayPreferences } from '@/composables/noteDisplayPreferences'
import NoteOriginalReader from '@/components/notes/NoteOriginalReader.vue'
import NoteReadingImages from '@/components/notes/NoteReadingImages.vue'
import { imageDisplayName } from '@/utils/imageDisplayName'
import NoteWidthSwitch from '@/components/notes/NoteWidthSwitch.vue'
import NoteAiStageButton from '@/components/notes/NoteAiStageButton.vue'
import NoteVisualRecap from '@/components/notes/NoteVisualRecap.vue'
import NoteAiCompanion from '@/components/notes/NoteAiCompanion.vue'
import type { CompanionStage } from '@/api/noteAiCompanion'
import NoteMediaCopyDialog from '@/components/notes/NoteMediaCopyDialog.vue'
import NoteTrashDialog from '@/components/notes/NoteTrashDialog.vue'
import NoteImageNavigator from '@/components/notes/NoteImageNavigator.vue'
import { useNoteWritingWidth } from '@/composables/noteWritingWidth'
import { publishNoteTagChange, replaceCachedNoteTags, replaceTagNames, useNoteTagChanges } from '@/utils/noteTagChanges'
import NoteAiSettingsDialog from '@/components/notes/NoteAiSettingsDialog.vue'
import { startSavedNoteStage } from '@/api/noteAiWorkflows'
import { newPolishRequestId } from '@/api/polishJobs'
import { useRoute } from 'vue-router'
import { useReleaseNoteExperience } from '@/composables/releaseNoteExperience'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { contentHit, documentSearchText } from '@/utils/contentSearch'
import NoteShareDialog from '@/components/notes/NoteShareDialog.vue'
const shareNoteId = ref('')
function openNoteShare(id: string) { shareNoteId.value = id }
import { copyText, copyToClipboard } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { defineAsyncComponent, computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import NoteDiscoverySavedList from '@/components/notes/NoteDiscoverySavedList.vue'
import { fetchDiscoverySource, clearDiscoveryLegacyCache } from '@/api/noteDiscoveries'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { resolvedRichContent } from '@/utils/richDoc'
import { notePreviewImages, notePreviewSource, type NotePreviewImage } from '@/utils/notePreviewImages'
import type { Doc, RichDocContent } from '@/types'
import ImageCopyMenu from '@/components/ui/ImageCopyMenu.vue'
import { discardAutoNoteEnrichment, initializeNoteWorkflow, noteEnrichmentProgress, noteEnrichmentStatus, notePolishVersions } from '@/utils/autoNoteEnrichment'
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
import { writingFocusPending } from '@/composables/writingJourney'

const contextMenu = ref<InstanceType<typeof ContextActionMenu> | null>(null)
const mediaCopyContent = ref<RichDocContent | null>(null)
const mediaCopyNote = ref({ id: '', title: '' })
const trashOpen = ref(false)
const readerMore = ref<HTMLDetailsElement | null>(null)
const readerMenuAnchor = ref<{ id: string; element: HTMLElement } | null>(null)
function closeReaderMore() { if (readerMore.value) readerMore.value.open = false }
const noteDisplay = useNoteDisplayPreferences()
function openMediaCopy(note: Doc) {
  mediaCopyNote.value = { id: note.id, title: displayedTitle(note) }
  mediaCopyContent.value = JSON.parse(JSON.stringify(displayedContent(note))) as RichDocContent
}
const writingOpen = ref(true)
const visualRecapId = ref('')
const visualRecapNote = computed(() => notes.value.find(n => n.id === visualRecapId.value))
const companion = ref<InstanceType<typeof NoteAiCompanion> | null>(null)
const newComposer = ref<InstanceType<typeof NoteComposer> | null>(null)
const writingFocusRequest = ref(0)
watch(writingFocusPending, request => {
  if (!request) return
  writingOpen.value = true
  writingFocusRequest.value = request
  writingFocusPending.value = 0
}, { immediate: true })
const companionNoteId = ref('')
const companionExplicitId = ref('')
const readerCompanionOpen = ref(false)
const companionEntry = ref<'results' | 'chat' | 'runs' | 'discovery'>('results')
async function closeReaderCompanion() {
  readerCompanionOpen.value = false
  await nextTick()
  const trigger = reading.value ? readerDialog.value?.querySelector<HTMLButtonElement>('.reader-companion-toggle')
    : Array.from(notesList.value?.querySelectorAll<HTMLElement>('[data-note-id]') ?? []).find(card => card.dataset.noteId === companionNoteId.value)?.querySelector<HTMLButtonElement>('.note-more-actions')
  trigger?.focus({ preventScroll: true })
}
function openReaderCompanion() { if (!readingNote.value) return; selectCompanion(readingNote.value.id); readerCompanionOpen.value = true }
function companionUpdated(doc: Doc) { delete notePolishVersions[doc.id]; data.applyRemoteDoc(doc); void loadServerWorkflows() }
function companionRun(stage: CompanionStage | 'four') { const note = notes.value.find(n => n.id === companionNoteId.value); if (note) void retryOrStart(note, stage) }
async function appendCompanion(text: string, messageId: string) { await stopReading(); writingOpen.value = true; await nextTick(); if (!newComposer.value?.appendPlainText(text, messageId)) toast('未能带入草稿，请从伴读复制回答；已有输入仍保留') }
function selectCompanion(id: string, entry: typeof companionEntry.value = 'results') { companionExplicitId.value = id; companionNoteId.value = id; companionEntry.value = entry; readerCompanionOpen.value = true }
async function editCompanionNote(id: string) { readerCompanionOpen.value = false; if (reading.value && readingId.value === id) await beginReadingEdit(); else await editNote(id) }
async function appendExistingCompanion(id: string, text: string, messageId: string) {
  await stopReading()
  const member = session.currentUser.id
  if (!(await data.ensureDocLoaded(id)) || member !== session.currentUser.id) { toast('原小记暂时无法读取，回答仍在伴读中保留'); return }
  const index = matching.value.findIndex(n => n.id === id)
  if (index < 0) { toast('原小记被当前筛选隐藏，请清除筛选后再附加；回答仍保留'); return }
  limit.value = Math.max(limit.value, index + 1)
  if (!expandedIds.value.includes(id)) expandedIds.value.push(id)
  await editNote(id)
  if (!noteComposers.get(id)?.appendPlainText?.(text, messageId)) toast('未能带入草稿，请从伴读复制回答；原文未改变')
}
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
type NoteEditorHandle = { focusSearch?: (text: string) => Promise<void>; copyFormatted: () => Promise<void>; positionAtPoint: (left: number, top: number) => { position: number; viewportTop: number } | null; focusImageReference: (index: number) => boolean }
const noteEditors = new Map<string, NoteEditorHandle>()
const readerEditor = ref<NoteEditorHandle | null>(null)
const editAnchors = ref<Record<string, { position: number; viewportTop: number; fullscreen?: boolean } | undefined>>({})
type NoteComposerHandle = { dirty: boolean; save: () => Promise<void>; appendPlainText?: (text: string, messageId?: string) => boolean }
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
      const key = stage as 'title' | 'image' | 'polish' | 'tags' | 'thinking'
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
const previewImages = ref<NotePreviewImage[]>([])
const previewImageIndex = ref(0)
function changePreviewImage(index: number) {
  previewImageIndex.value = index
  previewImage.value = previewImages.value[index] ?? null
  imageMenu.value = null
}
const imageMenu = ref<{ src: string; x: number; y: number } | null>(null)
function openImageMenu(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof HTMLImageElement) || !target.closest('.note-image-lightbox')) return
  event.preventDefault()
  imageMenu.value = { src: target.dataset.fullSrc || target.currentSrc || target.src, x: event.clientX, y: event.clientY }
}
const lightbox = ref<HTMLElement | null>(null)
let imageTrigger: HTMLElement | null = null
async function openImage(image: NotePreviewImage, event: MouseEvent, images: NotePreviewImage[] = readerImages.value, index?: number) {
  imageTrigger = event.currentTarget instanceof HTMLButtonElement ? event.currentTarget : event.target instanceof HTMLImageElement ? event.target : event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  previewImages.value = images.length ? images.map(item => ({ ...item })) : [{ ...image }]
  previewImageIndex.value = index ?? Math.max(0, previewImages.value.findIndex(item => item.src === image.src))
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
const showSystemArticles = ref(true)
const filterOpen = ref(false)
const filterControl = ref<HTMLElement>()
const pageActionsOpen = ref(false)
const pageActionsControl = ref<HTMLElement>()
const pageActionsButton = ref<HTMLButtonElement>()
function closePageActions() { pageActionsOpen.value = false; pageActionsButton.value?.focus() }
function choosePageAction(action: 'settings' | 'trash') {
  closePageActions()
  if (action === 'settings') noteSettingsOpen.value = true
  else trashOpen.value = true
}
useOverlayDismiss(() => pageActionsOpen.value, closePageActions, event => {
  if (!pageActionsControl.value?.contains(event.target as Node)) pageActionsOpen.value = false
})
const selectedTag = ref<string | null>('')
const noteListPreferenceLoaded = ref(false)
let noteListPreferenceMember = ''
function noteListPreferenceKey(member: string) { return `wao-note-list-preferences-v3:${member}` }
function restoreNoteListPreferences(member: string) {
  noteListPreferenceLoaded.value = false
  noteListPreferenceMember = member
  resetFilters()
  query.value = ''
  searchFocus.value = { id: '', term: '' }
  filterOpen.value = false
  try {
    const current = member ? localStorage.getItem(noteListPreferenceKey(member)) : null
    const previous = member && current === null ? localStorage.getItem(`wao-note-list-preferences-v2:${member}`) : null
    const saved = member ? JSON.parse(current ?? previous ?? 'null') as Partial<{ sort: string; imageFilter: string; timeFilter: string; selectedAuthor: string; selectedTag: string | null; showSystemArticles: boolean }> | null : null
    if (saved?.sort && ['updatedDesc', 'createdDesc', 'updatedAsc'].includes(saved.sort)) sort.value = saved.sort as typeof sort.value
    if (saved?.imageFilter && ['all', 'yes', 'no'].includes(saved.imageFilter)) imageFilter.value = saved.imageFilter as typeof imageFilter.value
    if (saved?.timeFilter && ['all', 'today', 'yesterday', 'earlier'].includes(saved.timeFilter)) timeFilter.value = saved.timeFilter as typeof timeFilter.value
    if (current !== null && typeof saved?.selectedAuthor === 'string') selectedAuthor.value = saved.selectedAuthor
    if (saved?.selectedTag === null || typeof saved?.selectedTag === 'string') selectedTag.value = saved.selectedTag
    if (typeof saved?.showSystemArticles === 'boolean') showSystemArticles.value = saved.showSystemArticles
  } catch { /* 忽略损坏的列表偏好，使用默认筛选。 */ }
  noteListPreferenceLoaded.value = !!member
}
watch(() => session.currentUser.id, restoreNoteListPreferences, { immediate: true, flush: 'sync' })
watch([sort, imageFilter, timeFilter, selectedAuthor, selectedTag, showSystemArticles], () => {
  const member = session.currentUser.id
  if (!noteListPreferenceLoaded.value || !member || member !== noteListPreferenceMember) return
  try { localStorage.setItem(noteListPreferenceKey(member), JSON.stringify({ sort: sort.value, imageFilter: imageFilter.value, timeFilter: timeFilter.value, selectedAuthor: selectedAuthor.value, selectedTag: selectedTag.value, showSystemArticles: showSystemArticles.value })) }
  catch { /* 偏好无法持久化时仍可在当前页面使用筛选。 */ }
}, { flush: 'sync' })
const activeFilterCount = computed(() => Number(!!selectedTag.value || selectedTag.value === null)
  + Number(selectedAuthor.value !== '') + Number(imageFilter.value !== 'all') + Number(timeFilter.value !== 'all') + Number(sort.value !== 'updatedDesc') + Number(!showSystemArticles.value))
const filterSummary = computed(() => [selectedTag.value === null ? '无标签' : selectedTag.value ? `# ${selectedTag.value}` : '',
  selectedAuthor.value === '__me__' ? '我的小记' : selectedAuthor.value ? `作者：${authorName(selectedAuthor.value)}` : '全部作者',
  showSystemArticles.value ? '' : '已隐藏系统文章',
  imageFilter.value === 'yes' ? '有图' : imageFilter.value === 'no' ? '无图' : '',
  { today: '今天', yesterday: '昨天', earlier: '以前', all: '' }[timeFilter.value],
  { updatedDesc: '最近更新', createdDesc: '最近创建', updatedAsc: '最早更新' }[sort.value]].filter(Boolean).join(' · '))
function resetFilters() { selectedTag.value = ''; selectedAuthor.value = ''; imageFilter.value = 'all'; timeFilter.value = 'all'; sort.value = 'updatedDesc'; showSystemArticles.value = true }
function onOutsideFilter(event: PointerEvent) {
  if (!filterControl.value?.contains(event.target as Node)) filterOpen.value = false
  if (readerMore.value?.open && !readerMore.value.contains(event.target as Node)) closeReaderMore()
}
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
const mobileActionsId = ref('')
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
const readerDeleteButton = ref<HTMLElement | null>(null)
function setReaderDeleteButton(value: unknown) { readerDeleteButton.value = value instanceof HTMLElement ? value : null }
function deleteAnchor(id: string) { return reading.value ? readerMenuAnchor.value?.id === id ? readerMenuAnchor.value.element : readerMore.value?.querySelector<HTMLElement>('summary') ?? readerDeleteButton.value : deleteButtons.get(id) }
const deleteConfirmationStyle = ref<Record<string, string>>({ visibility: 'hidden' })
function setDeleteConfirmation(value: unknown) { deleteConfirmation.value = value instanceof HTMLElement ? value : null }
function setDeleteButton(id: string, value: unknown) {
  if (value instanceof HTMLElement) deleteButtons.set(id, value)
  else deleteButtons.delete(id)
}
function positionDeleteConfirmation() {
  const anchor = deleteAnchor(confirmingDeleteId.value)
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
  const anchor = deleteAnchor(confirmingDeleteId.value)
  confirmingDeleteId.value = ''
  if (restoreFocus) anchor?.focus({ preventScroll: true })
}
function onOutsideDeleteConfirmation(event: PointerEvent) {
  const target = event.target as Node | null
  if (!target || !confirmingDeleteId.value || deleteConfirmation.value?.contains(target)
      || deleteAnchor(confirmingDeleteId.value)?.contains(target)) return
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
  if (!note) return
  const moreButton = event.currentTarget instanceof HTMLElement && event.currentTarget.matches('.note-more-actions') ? event.currentTarget : undefined
  if (editingIds.value.includes(id)) {
    if (!moreButton) return
    contextMenu.value?.openAt(event, `小记：${note.title}`, [
      { id: 'link', label: '分享设置', run: () => openNoteShare(id) },
      { id: 'pin', label: pinnedIds.value.includes(id) ? '取消置顶' : '置顶', disabled: !pinsReady.value || pinSaving.value, run: () => togglePin(id) },
    ], moreButton)
    return
  }
  if (event instanceof KeyboardEvent) {
    if (!(event.shiftKey && event.key === 'F10') && event.key !== 'ContextMenu') return
    event.preventDefault()
  } else {
    if (event.target instanceof Element && event.target.closest('a, button, input, textarea, img, [contenteditable="true"]') && !(event.currentTarget instanceof Element && event.currentTarget.matches('.note-more-actions'))) return
    const selection = window.getSelection()
    if (selection && !selection.isCollapsed && selection.anchorNode && event.currentTarget instanceof Node && event.currentTarget.contains(selection.anchorNode)) return
  }
  contextMenu.value?.openAt(event, `小记：${note.title}`, [
    { id: 'read', label: '全屏阅读', run: () => startReading(id, undefined, 'full') },
    { id: 'side', label: '侧栏速览', run: () => startReading(id, undefined, 'side') },
    ...(note.author === session.currentUser.id ? [
      { id: 'visual-recap', label: '图文整理', disabled: hasPendingNoteDraft(note), run: () => { visualRecapId.value = id } },
      { id: 'ai-results', label: 'AI 改了什么', run: () => selectCompanion(id) },
      { id: 'ai-chat', label: '聊聊这篇', run: () => selectCompanion(id, 'chat') },
    ] : []),
    { id: 'edit', label: '编辑小记', run: () => editNote(id) },
    { id: 'copy', label: '复制内容', run: () => { const note = data.docById(id); if (note) openMediaCopy(note) } },
    { id: 'link', label: '分享设置', run: () => openNoteShare(id) },
    { id: 'pin', label: pinnedIds.value.includes(id) ? '取消置顶' : '置顶', disabled: !pinsReady.value || pinSaving.value, run: () => togglePin(id) },
    { id: 'delete', label: '移入回收站', danger: true, separatorBefore: true, disabled: !!deletingId.value, run: () => toggleDeleteConfirmation(id) },
  ], moreButton)
}
function openReaderNoteMenu(event: MouseEvent | KeyboardEvent, id: string) {
  if (event instanceof KeyboardEvent && !(event.shiftKey && event.key === 'F10') && event.key !== 'ContextMenu') return
  event.preventDefault(); event.stopPropagation()
  const note = data.docById(id)
  if (!note || !reading.value) return
  if (event.currentTarget instanceof HTMLElement) readerMenuAnchor.value = { id, element: event.currentTarget }
  const inReader = (action: () => void) => { if (!guardReadingEdit(action)) action() }
  contextMenu.value?.openAt(event, `小记：${displayedTitle(note)}`, [
    { id: 'read', label: '在这里阅读', run: () => selectReadingNote(id) },
    { id: 'edit', label: '就地编辑', run: () => inReader(() => { void selectReadingNote(id).then(() => { if (readingId.value === id) void beginReadingEdit() }) }) },
    { id: 'copy', label: '复制给 Codex', run: () => openMediaCopy(note) },
    { id: 'share', label: '分享设置', run: () => openNoteShare(id) },
    { id: 'pin', label: pinnedIds.value.includes(id) ? '取消置顶' : '置顶', disabled: !pinsReady.value || pinSaving.value, run: () => togglePin(id) },
    { id: 'delete', label: '移入回收站', danger: true, separatorBefore: true, disabled: !!deletingId.value, run: () => inReader(() => void toggleDeleteConfirmation(id)) },
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
const readerEditing = ref(false)
const readingComposer = ref<InstanceType<typeof NoteComposer> | null>(null)
const readingEditAnchor = ref<{ position: number; viewportTop: number }>()
let afterReadingEdit: (() => void) | undefined
async function beginReadingEdit(event?: MouseEvent) {
  if (originalsOpen.value || !readingNote.value || readerEditing.value || event?.target instanceof Element && event.target.closest('a, button, img')) return
  if (pendingCardClickId === readingId.value) clearCardClickTimer()
  rememberReaderPosition()
  readingEditAnchor.value = event ? readerEditor.value?.positionAtPoint?.(event.clientX, event.clientY) ?? undefined : undefined
  readerEditing.value = true
  await nextTick()
}
function editReadingNote(event: MouseEvent) { void beginReadingEdit(event) }
async function finishReadingEdit(savedId?: string) {
  const id = readingId.value
  readerEditing.value = false
  if (savedId) { delete noteDrafts.value[id]; highlighted.value = savedId; void loadServerWorkflows() }
  readerRestorePending = true
  await nextTick()
  restoreReaderPosition()
  const next = afterReadingEdit; afterReadingEdit = undefined; next?.()
}
function guardReadingEdit(next: () => void) {
  if (!readerEditing.value) return false
  afterReadingEdit = next
  void readingComposer.value?.requestExit()
  return true
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
  return content ? withThinkingImage(notePreviewImages(content), originalsOpen.value ? '' : readingNote.value?.id ?? '') : []
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
  else if (readerEditing.value) readingComposer.value?.focusImageReference(index)
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
  if (kind === 'share') { openNoteShare(note.id); return }
  const request = ++readerCopyRequest
  const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  readerCopyBusy.value = true
  readerCopyStatus.value = '正在复制…'
  const text = displayedMarkdown(note)
  const copied = await copyToClipboard(text)
  if (request !== readerCopyRequest) return
  readerCopyBusy.value = false
  readerCopyStatus.value = copied ? '正文已复制' : '复制失败，请重试或手动选中复制'
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
  if (id === readingId.value) return
  if (guardReadingEdit(() => void selectReadingNote(id))) return
  if (!readerNotes.value.some(note => note.id === id)) return
  rememberReaderPosition()
  readingId.value = id
  closeReaderMore()
  readerMenuAnchor.value = null
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
  if (guardReadingEdit(() => void stopReading())) return
  closeDeleteConfirmation()
  rememberReaderPosition()
  readerWidths.stop()
  reading.value = false
  readerCompanionOpen.value = false
  readerDirectoryOpen.value = false
  restoreReaderBackground()
  await nextTick()
  if (readerTrigger?.isConnected) readerTrigger.focus({ preventScroll: true })
  else document.querySelector<HTMLElement>('.reader-toggle')?.focus({ preventScroll: true })
  readerTrigger = null
}
function closeReaderLayer() {
  if (readerMore.value?.open) { closeReaderMore(); readerMore.value?.querySelector<HTMLElement>('summary')?.focus(); return }
  if (readerCompanionOpen.value) { void closeReaderCompanion(); return }
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
  const buttons = [...container.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), textarea, [contenteditable="true"], [tabindex="0"]')].filter(el => el.getClientRects().length && !el.closest('[inert]'))
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
watch(() => session.currentUser.id, () => { trashOpen.value = false; mediaCopyContent.value = null; previewImage.value = null; readerEditing.value = false; afterReadingEdit = undefined; readerCompanionOpen.value = false; discoverySourceRequest++; clearDiscoveryLegacyCache(); void stopReading(); readerPositions.clear(); readingId.value = ''; readerMode.value = 'side' })
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
async function openCompanionSource(id: string, quote?: string, rev?: number) {
  const member = session.currentUser.id
  if (!(await data.ensureDocLoaded(id)) || member !== session.currentUser.id) return
  if (reading.value) await selectReadingNote(id)
  else await startReading(id)
  await nextTick()
  if (quote) await readerEditor.value?.focusSearch?.(quote)
  if (rev !== undefined && data.docById(id)?.rev !== rev) toast(`原文现已更新；这条回答引用的是 v${rev}，请对照查看`)
}
function moveReader(delta: number) {
  if (!readerNotes.value.length) return
  const index = Math.min(readerNotes.value.length - 1, Math.max(0, readingIndex.value + delta))
  void selectReadingNote(readerNotes.value[index]!.id)
}
function onReaderKeydown(event: KeyboardEvent) {
  if (event.target instanceof Element && event.target.closest('.wao-context-menu, .reader-more')) return
  if (event.defaultPrevented || event.isComposing || readerEditing.value || confirmingDeleteId.value) return
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
function onNotesScroll() { showBackToTop.value = window.scrollY > 480 || (notesList.value?.scrollTop ?? 0) > 480 }
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
const availableAuthors = computed(() => [...new Set(notes.value.map(note => note.systemGenerated ? '__system__' : note.author).filter(Boolean))]
  .map(id => ({ id, name: authorName(id) }))
  .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN') || a.id.localeCompare(b.id)))
const matching = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return notes.value.filter(doc => (selectedTag.value === null ? !doc.tags?.length : !selectedTag.value || doc.tags?.includes(selectedTag.value))
    && (showSystemArticles.value || !doc.systemGenerated)
    && (selectedAuthor.value === '__me__' ? !!session.currentUser.id && doc.author === session.currentUser.id : !selectedAuthor.value || (doc.systemGenerated ? '__system__' : doc.author) === selectedAuthor.value)
    && (imageFilter.value === 'all' || (cardImages(doc).length > 0) === (imageFilter.value === 'yes'))
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
function withThinkingImage(images: NotePreviewImage[], id: string): NotePreviewImage[] {
  const result = serverWorkflows.value[id]?.thinkingImage
  return result ? [...images, { src: result.imageUrl, alt: `思考图 · ${result.title}`, derived: true, sourceRev: result.sourceRev, current: result.current, outline: result.outline, questions: result.questions }] : images
}
async function continueThinking(text: string) {
  openReaderCompanion(); await nextTick(); await companion.value?.seedThinkingQuestion(text)
}
function cardImages(note: Doc): NotePreviewImage[] {
  return withThinkingImage(notePreviewImages(displayedContent(note)), note.id)
}
function stageIcon(stage: 'title' | 'image' | 'polish' | 'tags' | 'thinking') { return { title: 'T', image: '▧', polish: '✦', tags: '#', thinking: '◇' }[stage] }
function stageLabel(stage: 'title' | 'image' | 'polish' | 'tags' | 'thinking') { return { title: '标题', image: '配图', polish: '润色', tags: '标签', thinking: '思考图' }[stage] }
function workflowProgress(id: string) {
  const server = serverWorkflows.value[id]
  if (!server) return noteEnrichmentProgress[id]
  const state = (value: NoteAiWorkflowStatus['title']) => value.toLowerCase() as 'pending' | 'running' | 'done' | 'failed' | 'skipped'
  const local = noteEnrichmentProgress[id]
  const combined = { title: state(server.title), image: state(server.image), polish: state(server.polish), tags: server.tags ? state(server.tags) : local?.tags ?? 'skipped', thinking: server.thinking ? state(server.thinking) : 'skipped' }
  return combined
}
function progressVisible(id: string) {
  const steps = workflowProgress(id)
  return !!steps && Object.values(steps).some(value => value === 'running' || value === 'pending' || value === 'failed') || /失败|超时|未完成/.test(noteEnrichmentStatus[id] || '')
}
function stageDisabled(id: string, stage: 'title' | 'image' | 'polish' | 'tags' | 'thinking') {
  return retryingEnrichment.value.includes(id) || workflowProgress(id)?.[stage] === 'running'
    || !!serverWorkflows.value[id] && ['PENDING', 'RUNNING'].includes(serverWorkflows.value[id]![stage] ?? '')
}
function stageActionText(id: string, stage: 'title' | 'image' | 'polish' | 'tags' | 'thinking') {
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
  return !!server && (server.title === 'FAILED' || server.image === 'FAILED' || server.polish === 'FAILED' || server.tags === 'FAILED' || server.thinking === 'FAILED')
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
async function retryOrStart(note: Doc, stage?: 'title' | 'image' | 'polish' | 'tags' | 'thinking' | 'four') {
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
function onStageClick(note: Doc, stage: 'title' | 'image' | 'polish' | 'tags' | 'thinking') {
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
useReleaseNoteExperience({
  loadState: () => data.loadStates.docs === 'loaded' && data.loadStates.knowledge === 'loaded' ? 'loaded' : 'loading',
  openSettings: () => { noteSettingsOpen.value = true },
  read: async () => {
    const note = matching.value[0] ?? notes.value[0]
    if (!note) return false
    await startReading(note.id)
    return true
  },
  ai: () => {
    const note = notes.value.find(item => item.author === session.currentUser.id)
    if (!note) return false
    selectCompanion(note.id)
    return true
  },
})
watch([() => route.query.findRequest, () => route.query.note, () => route.query.find, () => data.loadStates.docs, () => data.loadStates.knowledge], async () => {
  const id = typeof route.query.note === 'string' ? route.query.note : ''
  const term = typeof route.query.find === 'string' ? route.query.find : ''
  const key = `${id}:${term}:${route.query.findRequest ?? ''}`
  if (id && data.loadStates.docs === 'loaded' && data.loadStates.knowledge === 'loaded' && key !== lastSearchRoute) {
    lastSearchRoute = key
    const member = session.currentUser.id
    const loaded = await data.ensureDocLoaded(id)
    if (member !== session.currentUser.id || route.query.note !== id || lastSearchRoute !== key) return
    if (!loaded) { lastSearchRoute = ''; refreshError.value = data.docLoadErrors[id] || '小记读取失败，请重试'; return }
    await locateSearchNote(id, term, route.query.match !== 'title')
  }
}, { immediate: true })

function onCreated(id: string) {
  companionNoteId.value = id
  companionExplicitId.value = ''
  void companion.value?.refresh()
  query.value = ''
  selectedTag.value = ''
  limit.value = PAGE_SIZE
  highlighted.value = id
  notesList.value?.scrollTo?.({ top: 0 })
  void loadServerWorkflows()
}
function onEdited(sourceId: string, savedId: string) {
  delete noteDrafts.value[sourceId]
  closeEditing(sourceId)
  highlighted.value = savedId
  void loadServerWorkflows()
}
function authorName(id: string) { return id === '__system__' ? '系统' : session.members.find(member => member.id === id)?.name || '成员' }
function noteAuthorName(note: Doc) { return note.systemGenerated ? '系统' : authorName(note.author) }
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
function selectDiscoveryMode(mode: 'notes' | 'saved' | 'legacy') { discoveryMode.value = mode; filterOpen.value = false }
watch(() => session.currentUser.id, () => { discoveryMode.value = 'notes' })
onMounted(() => void loadPins())
</script>

<template>
  <main class="notes-page wao-page-frame" @contextmenu="openImageMenu">
    <NoteVisualRecap v-if="visualRecapNote" :key="`${session.currentUser.id}:${visualRecapNote.id}`" :note-id="visualRecapNote.id" :rev="visualRecapNote.rev ?? 0" :blocked="hasPendingNoteDraft(visualRecapNote)" @close="visualRecapId = ''" />
    <ImageCopyMenu v-if="imageMenu" v-bind="imageMenu" @close="imageMenu = null" />
    <NoteMediaCopyDialog v-if="mediaCopyContent" :content="mediaCopyContent" :note-id="mediaCopyNote.id" :title="mediaCopyNote.title" @close="mediaCopyContent = null" />
    <NoteShareDialog v-if="shareNoteId" :key="shareNoteId" :note-id="shareNoteId" :title="data.docs[shareNoteId]?.title" @close="shareNoteId = ''" />
    <NoteTrashDialog v-if="trashOpen" :key="session.currentUser.id" :kb-name="data.kbs.find(kb => kb.id === 'kb-notes')?.name || '小记'" @close="trashOpen = false" @restored="reload" />
    <header class="page-heading"><div class="heading-title"><h1><i class="ri-sticky-note-line"></i>小记</h1><span v-if="todayCountError" class="daily-note-count error" role="alert"><i class="ri-error-warning-line" aria-hidden="true"></i>今日统计读取失败 <button type="button" @click="reload">重试</button></span><span v-else-if="todayCountLoading" class="daily-note-count" role="status"><i class="ri-loader-4-line spin" aria-hidden="true"></i>今日统计中…</span><span v-else class="daily-note-count" title="今日新建小记的正文字符数，不含空白、格式标记和图片"><i class="ri-quill-pen-line" aria-hidden="true"></i><span class="daily-count-label">今日写字 </span>{{ todayCharacters.toLocaleString('zh-CN') }} 字</span><button class="writing-toggle writing-region-toggle" type="button" :aria-label="writingOpen ? '收起记录区' : '展开记录区 · 继续写'" :title="writingOpen ? '收起记录区' : '展开记录区 · 继续写'" :aria-expanded="writingOpen" aria-controls="notes-writing-pane" @click="writingOpen = !writingOpen"><i :class="writingOpen ? 'ri-layout-left-line' : 'ri-quill-pen-line'" aria-hidden="true"></i><span>{{ writingOpen ? '收起记录区' : '展开记录区 · 继续写' }}</span></button><button class="writing-toggle notes-settings-toggle" type="button" aria-label="小记设置" @click="noteSettingsOpen = true"><i class="ri-settings-3-line" aria-hidden="true"></i><span>小记设置</span></button><button class="writing-toggle notes-trash-toggle" type="button" aria-label="小记回收站" @click="trashOpen = true"><i class="ri-delete-bin-line" aria-hidden="true"></i><span>回收站</span></button><div ref="pageActionsControl" class="notes-page-actions"><button ref="pageActionsButton" type="button" aria-label="小记更多操作" aria-haspopup="true" :aria-expanded="pageActionsOpen" aria-controls="notes-page-actions" @click="pageActionsOpen = !pageActionsOpen"><i class="ri-more-line" aria-hidden="true"></i></button><div v-if="pageActionsOpen" id="notes-page-actions" class="notes-page-actions-panel" role="group" aria-label="小记更多操作"><button type="button" @click="choosePageAction('settings')"><i class="ri-settings-3-line" aria-hidden="true"></i>小记设置</button><button type="button" @click="choosePageAction('trash')"><i class="ri-delete-bin-line" aria-hidden="true"></i>回收站</button></div></div></div><span>{{ notes.length }} 条记录</span></header>
    <div ref="workspace" class="notes-workspace" :inert="readerCompanionOpen ? true : undefined" :style="writingStyle" :class="{ 'writing-collapsed': !writingOpen, resizing }">
      <aside v-show="writingOpen" id="notes-writing-pane" class="writing-pane" aria-label="新建小记区">
        <NoteComposer ref="newComposer" :key="`${session.currentUser.id}:new`" :focus-request="writingFocusRequest" :loading="loading" :tag-options="availableTags" @tag-created="tagNames = [...new Set([...tagNames, $event])]" @saved="onCreated" />
      </aside>
      <div v-show="writingOpen" class="writing-resizer" role="separator" tabindex="0" aria-label="调整记录区宽度" aria-orientation="vertical" aria-controls="notes-writing-pane" :aria-valuenow="writingPercentage" aria-valuemin="25" aria-valuemax="60" :aria-valuetext="`记录区宽度 ${writingPercentage}%`" title="拖拽调整宽度，双击恢复默认宽度；方向键微调" @pointerdown="startWritingResize" @keydown="resizeWritingByKey" @dblclick="resetWritingWidth"><span aria-hidden="true"></span></div>
      <section ref="notesList" class="notes-list" aria-label="小记清单" :aria-busy="loading || refreshing" @scroll.passive="onNotesScroll">
        <div class="list-toolbar">
          <label class="search"><i class="ri-search-line"></i><input v-model="query" type="search" placeholder="搜索内容或标签" aria-label="搜索小记内容"><button v-if="query" type="button" aria-label="清除小记查找" @click="query = ''">清除</button></label>
          <div ref="filterControl" class="filter-control">
            <button class="filter-toggle" type="button" :aria-expanded="filterOpen" aria-controls="note-filter-panel" @click="filterOpen = !filterOpen"><i class="ri-equalizer-line"></i>筛选与排序<span v-if="activeFilterCount" class="filter-count">{{ activeFilterCount }}</span><i class="ri-arrow-down-s-line"></i></button>
            <div v-if="filterOpen" id="note-filter-panel" class="filter-panel" role="group" aria-label="小记筛选与排序">
              <div class="filter-group system-filter"><strong>系统文章</strong><button type="button" class="system-visibility" role="switch" aria-label="显示系统文章" :aria-checked="showSystemArticles" @click="showSystemArticles = !showSystemArticles"><span>显示系统文章</span><span class="system-switch-track" aria-hidden="true"></span></button></div>
              <div class="filter-group"><strong>标签</strong><div class="filter-options"><button type="button" :aria-pressed="selectedTag === ''" @click="selectedTag = ''">全部</button><button type="button" :aria-pressed="selectedTag === null" @click="selectedTag = null">无标签</button><button v-for="tag in availableTags" :key="tag" type="button" :aria-pressed="selectedTag === tag" @click="selectedTag = tag"># {{ tag }}</button></div></div>
              <div class="filter-group"><strong>作者</strong><select v-model="selectedAuthor" aria-label="按作者筛选"><option value="__me__">我的小记（当前账号）</option><option value="">全部作者</option><option v-for="author in availableAuthors" :key="author.id" :value="author.id">{{ author.name }}</option></select></div>
              <div class="filter-group"><strong>图片</strong><div class="filter-options"><button v-for="option in [{ value: 'all', label: '全部' }, { value: 'yes', label: '有图' }, { value: 'no', label: '无图' }]" :key="option.value" type="button" :aria-pressed="imageFilter === option.value" @click="imageFilter = option.value as typeof imageFilter">{{ option.label }}</button></div></div>
              <div class="filter-group"><strong>更新时间</strong><div class="filter-options"><button v-for="option in [{ value: 'all', label: '不限' }, { value: 'today', label: '今天' }, { value: 'yesterday', label: '昨天' }, { value: 'earlier', label: '以前' }]" :key="option.value" type="button" :aria-pressed="timeFilter === option.value" @click="timeFilter = option.value as typeof timeFilter">{{ option.label }}</button></div></div>
              <div class="filter-group"><strong>排序</strong><select v-model="sort" aria-label="小记排序"><option value="updatedDesc">最近更新</option><option value="createdDesc">最近创建</option><option value="updatedAsc">最早更新</option></select></div>
              <div class="filter-group"><strong>查看</strong><div class="filter-options"><button type="button" :aria-pressed="discoveryMode === 'notes'" @click="selectDiscoveryMode('notes')">小记</button><button type="button" :aria-pressed="discoveryMode === 'saved'" @click="selectDiscoveryMode('saved')">已收藏的发现</button><button type="button" :aria-pressed="discoveryMode === 'legacy'" @click="selectDiscoveryMode('legacy')">旧版生成内容</button></div></div>
              <div class="filter-footer"><button type="button" @click="addingTag = !addingTag; filterOpen = false">新增标签</button><button type="button" @click="managingTags = !managingTags; filterOpen = false">管理标签</button><button type="button" @click="resetFilters">清除筛选</button></div>
            </div>
          </div>
          <button class="reader-toggle" type="button" aria-label="阅读模式" :disabled="!matching.length" @click="startReading(undefined, $event)"><i class="ri-book-open-line" aria-hidden="true"></i><span>阅读模式</span></button>
          <button class="refresh" :disabled="loading || refreshing" :aria-busy="loading || refreshing" title="刷新清单" aria-label="刷新小记清单" @click="reload"><i :class="loading || refreshing ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i></button>
        </div>
        <NoteDiscoverySavedList v-if="discoveryMode !== 'notes'" :mode="discoveryMode" :member-id="session.currentUser.id" @source="openDiscoverySource" />
        <template v-else>
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
        <p v-if="!loading && !loadError && !matching.length" class="empty">{{ query || activeFilterCount ? '没有找到相关小记，调整筛选条件试试。' : '还没有小记，写下第一条想法吧。' }}</p>
        <p v-if="retainedEdits.length" class="tag-status" role="status">正在编辑的小记会保留在列表中，保存或收起后恢复筛选结果。</p>
        <article v-for="note in visible" :key="note.id" class="note-card" :class="{ 'fixed-height': isNotePreviewExpandable(note.id) && !isNotePreviewExpanded(note.id) && !editingIds.includes(note.id), expanded: isNotePreviewExpanded(note.id) && !editingIds.includes(note.id), highlighted: highlighted === note.id, editing: editingIds.includes(note.id), 'delete-confirming': confirmingDeleteId === note.id, 'mobile-actions-open': mobileActionsId === note.id, 'system-generated': note.systemGenerated && noteDisplay.settings.value.distinguishSystem }" :style="{ '--note-system-color': noteDisplay.settings.value.systemColor }" :data-note-id="note.id"
          tabindex="0" :aria-expanded="isNotePreviewExpandable(note.id) ? isNotePreviewExpanded(note.id) : undefined" :title="isNotePreviewExpandable(note.id) ? isNotePreviewExpanded(note.id) ? '单击收起全文，按 Enter 阅读，双击编辑' : '单击展开全文，按 Enter 阅读，双击编辑' : '双击编辑，按 Enter 阅读'" :aria-label="`小记：${note.title}，${isNotePreviewExpandable(note.id) ? `单击${isNotePreviewExpanded(note.id) ? '收起' : '展开'}全文，` : ''}按 Enter 开始阅读，双击编辑`"
          @click="scheduleCardPreview(note.id, $event)" @dblclick="editNote(note.id, $event)" @keydown.enter.self.prevent="startReading(note.id, $event)" @keydown.esc="cancelDeleteOnEscape(note.id, $event)" @keydown="openNoteMenu($event, note.id)" @contextmenu="openNoteMenu($event, note.id)">
          <div v-if="!editingIds.includes(note.id)" class="note-meta">
            <div class="note-heading"><h2 :title="displayedTitle(note)">{{ displayedTitle(note) }}</h2></div>



            <span v-if="chargingNote === note.id" class="four-charge-hint" role="status">按住 3 秒 · 松开取消</span>
            <span class="note-meta-trailing"><span class="card-action-slot"><span class="card-identity"><span class="author" :class="{ 'system-author': note.systemGenerated }" :title="note.systemGenerated ? 'Skill 生成的系统文章' : undefined"><i v-if="note.systemGenerated" class="ri-sparkling-line" aria-hidden="true"></i>{{ noteAuthorName(note) }}</span><time :title="timeLabel(note.updated || note.created)">{{ new Date(note.updated || note.created).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }}</time></span><span class="card-hover-actions"><div class="stage-list" :class="{ 'stage-list-complete': !progressVisible(note.id), 'stage-list-open': mobileActionsId === note.id }" role="group" aria-label="AI 产出与按需操作">
              <NoteAiStageButton v-for="stage in aiStages" :key="stage" :class="`stage-${workflowProgress(note.id)?.[stage] || 'pending'}`" hold-enabled :disabled="stageDisabled(note.id, stage)" :title="`${stageLabel(stage)}：${stageActionText(note.id, stage)}；长按 3 秒一键四连`" :aria-label="`${stageLabel(stage)}${stageActionText(note.id, stage)}，长按 3 秒一键四连`" @activate="onStageClick(note, stage)" @four="retryOrStart(note, 'four')" @charging="chargingNote = $event ? note.id : ''">{{ stageIcon(stage) }}</NoteAiStageButton>
              <NoteAiStageButton :disabled="stageDisabled(note.id, 'thinking')" :title="`思考图：${stageActionText(note.id, 'thinking')}`" :aria-label="`思考图${stageActionText(note.id, 'thinking')}`" @activate="onStageClick(note, 'thinking')"><i class="ri-lightbulb-line" aria-hidden="true"></i></NoteAiStageButton>
            </div><span class="card-quick-actions"><button type="button" aria-label="复制小记图文" title="复制正文、原图或交给 Codex" @click.stop="openMediaCopy(note)"><i class="ri-file-copy-2-line" aria-hidden="true"></i></button><button type="button" class="card-delete-icon" :ref="value => setDeleteButton(note.id, value)" :aria-label="`删除小记：${note.title}`" :aria-expanded="confirmingDeleteId === note.id" title="删除小记（移入回收站）" :disabled="!!deletingId" @click.stop="toggleDeleteConfirmation(note.id)"><i class="ri-delete-bin-line" aria-hidden="true"></i></button></span></span></span><span v-if="highlighted === note.id && !editingIds.includes(note.id)" class="saved" role="status">已保存</span><span v-if="noteCounts[note.id] !== undefined && !editingIds.includes(note.id)" class="note-count">{{ noteCounts[note.id]?.toLocaleString('zh-CN') }} 字</span>
              <button class="note-more-actions" type="button" :aria-label="`小记操作：${note.title}`" aria-haspopup="menu" @click.stop="openNoteMenu($event, note.id)"><i class="ri-more-line" aria-hidden="true"></i></button>
              <button class="note-touch-ai" type="button" aria-label="显示 AI 操作" :aria-expanded="mobileActionsId === note.id" @click.stop="mobileActionsId = mobileActionsId === note.id ? '' : note.id"><i class="ri-sparkling-line" aria-hidden="true"></i></button>
            </span>
          </div>

          <button v-if="query && noteSearchHit(note)" class="note-search-hit" type="button" @click.stop="locateSearchNote(note.id, query, noteSearchHit(note)?.field === '正文')">
            {{ noteSearchHit(note)!.field }}命中 · {{ noteSearchHit(note)!.before }}<mark>{{ noteSearchHit(note)!.match }}</mark>{{ noteSearchHit(note)!.after }} <span>定位</span>
          </button>
          <p v-if="searchFocus.id === note.id && !matching.some(item => item.id === note.id)" class="workflow-status">搜索定位 · 原有筛选已保留，额外显示此条</p>
          <NoteComposer :search-request="String(searchRequest)" :search-term="searchFocus.id === note.id ? searchFocus.term : ''" v-if="editingIds.includes(note.id)" :ref="value => setNoteComposer(note.id, value)" :key="`${session.currentUser.id}:${note.id}`" :note="note" inline autofocus :expanded="expandedIds.includes(note.id)" :initial-fullscreen="editAnchors[note.id]?.fullscreen" :initial-position="editAnchors[note.id]?.position" :initial-viewport-top="editAnchors[note.id]?.viewportTop" :tag-options="availableTags" :workflow-message="workflowMessage(note.id)"
            @tag-created="tagNames = [...new Set([...tagNames, $event])]" @draft-change="onDraftChange(note.id, $event)" @saved="onEdited(note.id, $event)" @close="closeEditing(note.id)">
            <template #heading-actions><button class="note-more-actions" type="button" :aria-label="`小记操作：${note.title}`" aria-haspopup="menu" @click.stop="openNoteMenu($event, note.id)"><i class="ri-more-line" aria-hidden="true"></i></button></template>
          </NoteComposer>
          <template v-else>
          <div class="note-content" :class="{ 'has-images': notePreviews.get(note.id)?.length }" @click="openInlineImage">
            <div class="note-body" :ref="value => setNotePreviewBody(note.id, value)"><MarkdownEditor :search-request="String(searchRequest)" :search-term="searchFocus.id === note.id ? searchFocus.term : ''" :ref="value => setNoteEditor(note.id, value)" :model-value="displayedMarkdown(note) || note.title" :document="displayedContent(note).document" :readonly="true" preview-images image-references note-media-copy :active-image-reference="cardActiveImages[note.id] ?? null" bare advanced allow-h1 :min-height="0" @image-reference="locateCardImage(note.id, $event)" @character-count="onNoteCharacterCount(note.id, $event)" /></div>
            <aside v-if="notePreviews.get(note.id)?.length" class="note-images" :class="{ expanded: isNotePreviewExpanded(note.id) }" aria-label="小记图片总览">
              <span>图片总览 <b>{{ notePreviews.get(note.id)?.length }} 张</b></span>
              <figure v-for="(image, index) in notePreviews.get(note.id)" :key="`${image.src}:${index}`" class="note-image-item" :class="{ active: cardActiveImages[note.id] === index + 1 }" :data-card-image="index + 1" :aria-label="`图 ${index + 1} ${imageDisplayName(image.alt, index + 1)}`">
              <button class="note-image" type="button" :aria-label="`放大图 ${index + 1}：${imageDisplayName(image.alt, index + 1)}`" :title="imageDisplayName(image.alt, index + 1)" @click.stop="openImage(image, $event, cardImages(note), index)">
                <img :src="notePreviewSource(image.src)" :alt="imageDisplayName(image.alt, index + 1)" loading="lazy">
              </button>
              <figcaption><span>图 {{ index + 1 }}</span><button class="note-image-return" type="button" :aria-label="`返回正文图 ${index + 1}的位置`" @click.stop="returnToCardImageReference(note.id, index + 1)">返回正文</button></figcaption>
              </figure>
            </aside>
          </div>
          <div class="note-card-footer"><p v-if="!editingIds.includes(note.id) && (noteDrafts[note.id] || progressVisible(note.id))" :title="workflowMessage(note.id)" class="workflow-status compact-workflow" role="status"><template v-if="noteDrafts[note.id]">草稿已保护 · 有修改待保存</template><button v-else type="button" @click.stop="selectCompanion(note.id)">{{ workflowMessage(note.id) || (serverFailed(note.id) ? '整理未完成 · 查看并重试' : '整理中 · 查看进度') }}</button></p><div v-if="displayedTags(note).length" class="card-tags"><button v-for="tag in displayedTags(note)" :key="tag" @click.stop="selectedTag = tag"># {{ tag }}</button></div><div v-if="isNotePreviewExpandable(note.id)" class="note-read-guide"><button class="note-read-action" type="button" :aria-expanded="isNotePreviewExpanded(note.id)" @click.stop="toggleNotePreview(note.id)"><i :class="isNotePreviewExpanded(note.id) ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'" aria-hidden="true"></i>{{ isNotePreviewExpanded(note.id) ? '收起全文' : '展开全文' }}</button></div></div>
          </template>
          <Teleport to="body"><div v-if="confirmingDeleteId === note.id && !reading" :ref="setDeleteConfirmation" :id="`note-delete-confirm-${note.id}`" class="card-delete-confirm" :data-delete-note-id="note.id" :style="deleteConfirmationStyle" role="dialog" :aria-label="`确认删除小记：${note.title}`">
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
    <NoteAiCompanion v-if="readerCompanionOpen" focused :initial-tab="companionEntry" :overlay-open="readerCompanionOpen" @close-overlay="closeReaderCompanion" ref="companion" :notes="notes" :active-id="companionNoteId" :explicitly-selected="companionExplicitId === companionNoteId && !!companionNoteId" :blocked="!!companionNoteId && !!notes.find(n => n.id === companionNoteId && hasPendingNoteDraft(n))" @select="id => selectCompanion(id)" @updated="companionUpdated" @run="companionRun" @source="openDiscoverySource" @open-note="openCompanionSource" @edit-note="editCompanionNote" @append="appendCompanion" @append-existing="appendExistingCompanion" @settings="noteSettingsOpen = true" />
    <NoteAiSettingsDialog v-model:open="noteSettingsOpen" />
    <Teleport to="body">
      <div v-if="reading && confirmingDeleteId" :ref="setDeleteConfirmation" class="card-delete-confirm" :style="deleteConfirmationStyle" role="dialog" aria-label="确认删除当前小记">
        <strong>{{ data.docById(confirmingDeleteId)?.title }}</strong>
        <span>将此小记移入回收站？7 天内可恢复。</span><p v-if="deleteError" role="alert">{{ deleteError.message }}</p>
        <div class="card-delete-actions"><button class="card-delete-cancel" type="button" :disabled="!!deletingId" @click="closeDeleteConfirmation(true)">取消</button><button class="card-delete-submit" type="button" :disabled="!!deletingId || !data.apiLive" @click="deleteNote(confirmingDeleteId)">{{ deletingId ? '删除中…' : '确认删除' }}</button></div>
      </div>
      <div v-if="reading && readingNote" ref="readerDialog" class="notes-reader" :data-page-layout="pagePreferences.pageLayoutStyle" :class="{ 'reader-full': readerMode === 'full', 'reader-narrow': contentWidth === 'narrow', 'directory-open': readerDirectoryOpen }" :inert="previewImage || readerCompanionOpen ? true : undefined" role="dialog" aria-modal="true" aria-label="小记阅读模式">
        <header class="reader-topbar">
          <strong><i class="ri-book-open-line" aria-hidden="true"></i> 阅读模式 <small>{{ readerEditing ? '编辑中' : '只读' }}</small></strong>
          <span class="reader-copy-status" role="status" aria-live="polite">{{ readerCopyStatus }}</span>
          <div class="reader-layout-actions">
            <div class="reader-mode-switch" role="group" aria-label="阅读布局"><button type="button" :aria-pressed="readerMode === 'side'" @click="changeReaderMode('side')"><i class="ri-layout-left-line" aria-hidden="true"></i>侧栏速览</button><button type="button" :aria-pressed="readerMode === 'full'" @click="changeReaderMode('full')"><i class="ri-fullscreen-line" aria-hidden="true"></i>全屏阅读</button></div>
            <button v-if="readingNote.author === session.currentUser.id" type="button" :disabled="readerEditing || hasPendingNoteDraft(readingNote)" @click="visualRecapId = readingNote.id"><i class="ri-layout-masonry-line" aria-hidden="true"></i>图文整理</button>
            <NoteWidthSwitch v-if="readerMode === 'full'" v-model="contentWidth" label="阅读宽度" />
            <button type="button" aria-label="复制当前小记图文" :disabled="originalsOpen" @click="openMediaCopy(readingNote)"><i class="ri-file-copy-2-line" aria-hidden="true"></i><span>复制</span></button>
            <details ref="readerMore" class="reader-more"><summary tabindex="0" aria-label="更多阅读操作"><i class="ri-more-line" aria-hidden="true"></i><span>更多</span></summary><div class="reader-more-panel" @click="closeReaderMore">
            <button v-if="readingNote.author === session.currentUser.id" class="reader-companion-toggle" type="button" @click="openReaderCompanion"><i class="ri-sparkling-2-line" aria-hidden="true"></i><span>AI 改了什么</span></button>
            <button type="button" aria-label="小记分享设置" title="主动生成限时分享链接或撤销已有链接" :disabled="readerCopyBusy" @click="copyReadingNote('share')"><i class="ri-share-forward-line" aria-hidden="true"></i><span>分享</span></button>
            <button type="button" aria-label="复制小记正文" :title="originalsOpen ? '返回当前内容后复制正文' : '复制当前正文（Markdown）'" :disabled="readerCopyBusy || originalsOpen" @click="copyReadingNote('body')"><i class="ri-file-copy-line" aria-hidden="true"></i><span>复制正文</span></button>
            <button v-if="readingNote.author === session.currentUser.id" type="button" :disabled="readerEditing" @click="selectCompanion(readingNote.id, 'chat')"><i class="ri-chat-3-line" aria-hidden="true"></i><span>聊聊这篇</span></button>
            <button type="button" :ref="setReaderDeleteButton" :aria-label="`删除小记：${readingNote.title}`" :disabled="!!deletingId" @click="guardReadingEdit(() => void toggleDeleteConfirmation(readingId)) || toggleDeleteConfirmation(readingId)"><i class="ri-delete-bin-line" aria-hidden="true"></i><span>删除</span></button>
            <button v-if="!readerEditing" type="button" :aria-pressed="originalsOpen" @click="toggleOriginals">{{ originalsOpen ? '返回当前内容' : '查看原文' }}</button>
            </div></details>
            <button ref="readerClose" type="button" aria-label="退出阅读模式" title="退出阅读模式（Esc）" @click="stopReading"><i class="ri-close-line" aria-hidden="true"></i><span>关闭</span></button>
          </div>
        </header>
        <div ref="readerLayout" class="reader-layout" :class="{ resizing: readerWidths.resizing.value }" :style="readerWidths.style.value">
          <aside class="reader-directory" aria-label="当前列表的小记目录"><div class="reader-directory-heading"><strong>小记目录</strong><span>{{ readerNotes.length }} 篇</span><button type="button" class="reader-directory-close" aria-label="收起小记目录" @click="closeReaderLayer"><i class="ri-close-line" aria-hidden="true"></i></button></div><nav aria-label="选择小记"><button v-for="note in readerNotes" :key="note.id" type="button" :aria-current="note.id === readingId ? 'true' : undefined" @click="selectReadingNote(note.id)" @contextmenu.prevent.stop="openReaderNoteMenu($event, note.id)" @keydown="openReaderNoteMenu($event, note.id)"><strong>{{ displayedTitle(note) }}</strong><small>{{ noteAuthorName(note) }} · {{ timeLabel(note.updated || note.created) }}</small></button></nav></aside>
          <div class="reader-resizer reader-directory-resizer" role="separator" tabindex="0" aria-orientation="vertical" aria-label="小记目录宽度" :aria-valuemin="readerWidths.minimum.directory" :aria-valuemax="readerWidths.limit('directory')" :aria-valuenow="readerWidths.widths.value.directory" title="拖拽调整宽度，双击恢复默认；方向键微调" @pointerdown="readerWidths.start('directory', $event)" @keydown="readerWidths.keydown('directory', $event)" @dblclick="readerWidths.reset('directory')"><span></span></div>
          <div class="reader-main" :inert="readerDirectoryOpen ? true : undefined"><button type="button" class="reader-directory-toggle" @click="openReaderDirectory"><i class="ri-list-check" aria-hidden="true"></i>小记目录 <span>{{ readingIndex + 1 }} / {{ readerNotes.length }}</span></button>
            <article :key="readingNote.id" ref="readerPage" class="reader-page" @scroll="readerRestorePending = false">
              <div class="reader-columns"><div class="reader-article"><NoteComposer v-if="readerEditing" ref="readingComposer" :key="`${session.currentUser.id}:${readingNote.id}:reading`" :note="readingNote" inline contained expanded autofocus :initial-position="readingEditAnchor?.position" :initial-viewport-top="readingEditAnchor?.viewportTop" :tag-options="availableTags" @draft-change="onDraftChange(readingNote.id, $event)" @tag-created="tagNames = [...new Set([...tagNames, $event])]" @saved="finishReadingEdit($event)" @image-reference="locateReadingImage" @close="finishReadingEdit()" @exit-cancelled="afterReadingEdit = undefined" /><NoteOriginalReader v-else-if="originalsOpen" ref="readerOriginal" :doc-id="readingNote.id" :member-id="session.currentUser.id" :current-rev="readingNote.rev" image-references :active-image-reference="readerActiveImage" @reading-content="readerOriginalContent = $event" @image-reference="locateReadingImage" @source-change="changeOriginalSource" @ready="restoreReaderPosition" /><template v-else><div class="reader-meta"><span>{{ noteAuthorName(readingNote) }}</span><time>{{ timeLabel(readingNote.updated || readingNote.created) }}</time><span>{{ noteCounts[readingNote.id]?.toLocaleString('zh-CN') ?? '' }} 字</span></div>
              <h2>{{ displayedTitle(readingNote) }}</h2>
              <div v-if="displayedTags(readingNote).length" class="reader-tags"><span v-for="tag in displayedTags(readingNote)" :key="tag"># {{ tag }}</span></div>
              <div class="reader-content" @dblclick="editReadingNote"><div class="reader-body"><MarkdownEditor ref="readerEditor" :model-value="displayedMarkdown(readingNote) || readingNote.title" :document="displayedContent(readingNote).document" :readonly="true" preview-images image-references note-media-copy :active-image-reference="readerActiveImage" bare advanced allow-h1 :min-height="0" @image-reference="locateReadingImage" @character-count="restoreReaderPosition" /></div></div></template></div>
              <div class="reader-resizer reader-images-resizer" role="separator" tabindex="0" aria-orientation="vertical" aria-label="图片清单宽度" :aria-valuemin="readerWidths.minimum.images" :aria-valuemax="readerWidths.limit('images')" :aria-valuenow="readerWidths.widths.value.images" title="拖拽调整宽度，双击恢复默认；方向键微调" @pointerdown="readerWidths.start('images', $event)" @keydown="readerWidths.keydown('images', $event)" @dblclick="readerWidths.reset('images')"><span></span></div>
              <NoteReadingImages :key="`${readingNote.id}:${originalsOpen ? originalPositionKey : 'current'}`" ref="readerImagesPanel" :images="readerImages" :active="readerActiveImage" :unavailable="originalsOpen && !readerOriginalContent" @question="continueThinking" @return="returnToImageReference" @fullscreen="(image, event, index) => openImage(image, event, readerImages, index)" /></div>
            </article>
            <footer class="reader-controls"><button type="button" aria-label="上一篇小记（←）" :disabled="readingIndex <= 0" @click="moveReader(-1)"><i class="ri-arrow-left-line" aria-hidden="true"></i>上一篇</button><span>{{ readingIndex + 1 }} / {{ readerNotes.length }}<small> · ← → 翻篇 · Esc 退出</small></span><button type="button" aria-label="下一篇小记（→）" :disabled="readingIndex >= readerNotes.length - 1" @click="moveReader(1)">下一篇<i class="ri-arrow-right-line" aria-hidden="true"></i></button></footer>
          </div>
        </div>
      </div>
      <div v-if="previewImage" ref="lightbox" class="note-image-lightbox" role="dialog" aria-modal="true"
        :aria-label="previewImage.alt || '小记图片预览'" tabindex="-1" @click.self="closeImage" @keydown.esc.stop.prevent="closeImage" @keydown="trapReaderFocus($event, lightbox)" @contextmenu="openImageMenu">
        <button type="button" class="note-image-close" aria-label="关闭图片预览" @click="closeImage"><i class="ri-close-line"></i></button>
        <img :src="previewImage.src" :alt="previewImage.alt">
        <NoteImageNavigator :images="previewImages" :index="previewImageIndex" @change="changePreviewImage" />
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
h1 { display: flex; flex: none; white-space: nowrap; gap: 10px; align-items: center; font-size: var(--wao-font-title); margin: 0; color: var(--wao-text);  font-weight: var(--wao-weight-heading); line-height: var(--wao-line-heading);}
h1 i { color: #7a5cc6; }
.page-heading p { margin: 0; color: var(--wao-text-faint); font-size: 13px; }
.page-heading > span { color: var(--wao-text-faint); white-space: nowrap; font-size: 12px; }
.writing-toggle { margin: 0; white-space: nowrap; border: 1px solid var(--wao-line-control); }
.writing-toggle i { margin-right: 6px; }
.notes-workspace { display: grid; grid-template-columns: clamp(240px, var(--notes-writing-width, clamp(33.333333%, 600px, 60%)), calc(100% - 340px)) 20px minmax(0, 1fr); gap: 0; align-items: stretch; }
.writing-resizer { display: flex; align-items: center; justify-content: center; min-height: 80px; cursor: col-resize; touch-action: none; outline: none; }
.writing-resizer span { width: 3px; height: 48px; border-radius: 3px; background: var(--wao-line-control); transition: background .15s, height .15s; }
.writing-resizer:hover span, .writing-resizer:focus-visible span, .resizing .writing-resizer span { height: 80px; background: var(--wao-primary); }
.writing-resizer:focus-visible { box-shadow: inset 0 0 0 2px var(--wao-primary); border-radius: 6px; }
.notes-workspace.resizing { cursor: col-resize; user-select: none; }
.resizing .writing-pane, .resizing .notes-list { pointer-events: none; }
.notes-workspace.writing-collapsed { grid-template-columns: minmax(0, 1fr); }
.writing-collapsed .notes-list { border-left: 0; }
.writing-pane :deep(.composer-footer > button) { flex-shrink: 0; white-space: nowrap; }
.writing-pane { min-width: 0; position: sticky; top: 20px; }
.companion-receipt { color:var(--wao-primary-text); opacity:0; visibility:hidden; }
.companion-receipt.unread, .note-card:hover .companion-receipt, .note-card:focus-within .companion-receipt { opacity:1; visibility:visible; }
.writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: min(650px, calc(100dvh - 210px)); max-height: calc(100dvh - 155px); }
.notes-list { min-width: 0; padding: 0 20px 24px; border: 1px solid var(--wao-line); border-right: 0; border-bottom: 0; background: var(--wao-bg-sub); scroll-padding-block: 24px; }
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
.tags-toolbar button { flex: none; padding: 4px 7px; font-size: var(--wao-font-caption); line-height: 1.3; white-space: nowrap; }
.tag-filter-scroll { display: flex; gap: 5px; min-width: 0; overflow-x: auto; padding: 2px 0 5px; }
.tag-filter { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); }
.tag-filter span { color: var(--wao-text-faint); margin-left: 4px; }
.new-tag-toggle { border: 1px dashed var(--wao-line-control); }
.new-tag-form { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.new-tag-form input { flex: 1; min-width: 100px; border: 1px solid var(--wao-line-control); border-radius: 7px; padding: 8px 10px; }
.new-tag-form button { background: var(--wao-primary); color: white; }
.new-tag-form .error { flex-basis: 100%; }
.tag-status { font-size: 12px; color: var(--wao-text-faint); }
.list-summary { display: flex; justify-content: space-between; gap: 10px; margin: 9px 0 12px; font-size: var(--wao-font-caption); color: var(--wao-text-3); }
.note-card { position: relative; box-sizing: border-box; background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: 12px; padding: 17px 19px; margin-bottom: 16px; transition: border-color .18s, box-shadow .18s, transform .18s; }
.note-card.system-generated { background: color-mix(in srgb, var(--note-system-color) 3%, var(--wao-bg-card)); border-color: color-mix(in srgb, var(--note-system-color) 12%, var(--wao-line-control)); }
.author.system-author { display: inline-flex; align-items: center; gap: 4px; color: var(--wao-primary-text); background: color-mix(in srgb, var(--wao-primary) 13%, var(--wao-bg-card)); padding: 3px 7px; border-radius: 6px; }
.system-visibility { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; color: var(--wao-text-1); border: 0; background: transparent; padding: 4px 0; cursor: pointer; text-align: left; }
.system-switch-track { flex: none; width: 36px; height: 21px; border-radius: 11px; background: var(--wao-line-control); position: relative; }
.system-switch-track::after { content: ''; position: absolute; top: 3px; left: 3px; width: 15px; height: 15px; border-radius: 50%; background: var(--wao-bg-card); }
.system-visibility[aria-checked="true"] .system-switch-track { background: var(--wao-primary); }
.system-visibility[aria-checked="true"] .system-switch-track::after { left: 18px; }
.system-visibility:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; border-radius: 4px; }
.note-card.fixed-height { min-height: 300px; height: 300px; max-height: 300px; overflow: hidden; padding-bottom: 36px; }
.note-card.expanded, .note-card.editing { height: auto; max-height: none; overflow: visible; }
.note-card:hover, .note-card:focus-within { border-color: var(--wao-primary); box-shadow: 0 4px 16px rgba(59, 47, 91, .07); }
.note-card.editing:hover, .note-card.editing:focus-within { transform: none; }
.note-card.delete-confirming { transform: none; transition: border-color .18s, box-shadow .18s; }
.note-card.highlighted, .note-card.editing { border-color: var(--wao-primary); }
.note-card:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
.note-mobile-actions { display: none; }
.note-meta { font-size: var(--wao-font-caption); color: var(--wao-text-3); margin-bottom: 16px; flex-wrap: wrap; gap: 5px 9px; }
.stage-list { display: inline-flex; align-items: center; gap: 4px; }
.four-charge-hint { color:var(--wao-primary-text); font-size: var(--wao-font-caption); }
.note-four-action { display:inline-flex; align-items:center; gap:4px; padding:5px 8px; border:0; border-radius:7px; background:var(--wao-bg-hover); color:var(--wao-primary-text); font-size: var(--wao-font-caption); cursor:pointer; opacity:0; visibility:hidden; }
.note-card:hover .note-four-action, .note-card:focus-within .note-four-action, .note-four-action.is-running { opacity:1; visibility:visible; }
@media (hover: none), (pointer: coarse) { .note-four-action { opacity:1; visibility:visible; } }
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
.workflow-status { margin: -6px 0 12px; color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.workflow-status button { color: var(--wao-primary-text); text-decoration: underline; }
@keyframes stage-pulse { 50% { box-shadow: 0 0 0 4px rgba(116,83,189,.14); } }
.note-meta-trailing { display: inline-flex; align-items: center; gap: 12px; margin-left: auto; white-space: nowrap; }
.note-count { color: var(--wao-text-3); font-variant-numeric: tabular-nums; }
.author { color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.saved { color: var(--wao-success-strong); margin-left: auto; }
.note-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 5px 9px; margin: 0 0 9px; }
.note-card.fixed-height .note-heading { max-height: none; overflow: hidden; }
.note-heading h2 { margin: 0; color: var(--wao-text-1); font-size:var(--wao-font-card-title); line-height: 1.45; font-weight: 600; overflow-wrap: anywhere; }
.note-card.fixed-height .note-heading h2 { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.note-content { min-width: 0; }
.note-content.has-images { display: grid; grid-template-columns: minmax(0, 1fr) minmax(90px, 24%); gap: 12px; align-items: start; }
.note-card.expanded .note-content.has-images { align-items: stretch; }
.note-body { min-height: 0; overflow-wrap: anywhere; line-height: 1.75; font-size: var(--wao-font-body); }
.note-card.fixed-height .note-body { max-height: 170px; overflow: hidden; -webkit-mask-image: linear-gradient(to bottom, #000 calc(100% - 1.6em), transparent); mask-image: linear-gradient(to bottom, #000 calc(100% - 1.6em), transparent); }
.note-body :deep(img.md-img) { display: none; }
.note-images { min-width: 0; max-height: 170px; overflow: auto; display: grid; grid-template-columns: minmax(0, 1fr); align-content: start; gap: 7px; padding-left: 10px; border-left: 1px solid var(--wao-line-control); }
.note-images.expanded { max-height: none; overflow: visible; }
.note-images > span { display: flex; justify-content: space-between; gap: 5px; color: var(--wao-text-faint); font-size: var(--wao-font-caption); }
.note-images b { font-weight: 400; white-space: nowrap; }
.note-image { overflow: hidden; padding: 0; border: 1px solid var(--wao-line-control); background: var(--wao-bg-sub); }
.note-image img { display: block; width: 100%; aspect-ratio: 4 / 3; object-fit: cover; }
.note-image-item { margin: 0; min-width: 0; padding: 3px; border: 1px solid transparent; border-radius: 8px; }
.note-image-item.active { border-color: var(--wao-primary); background: var(--wao-primary-soft); }
.note-image-item .note-image { display: block; width: 100%; }
.note-image-item figcaption { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 3px; padding-top: 3px; font-size: var(--wao-font-caption); color: var(--wao-text-3); }
.note-image-return { padding: 2px 4px; font-size: var(--wao-font-caption); color: var(--wao-primary-text); }
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
.reader-topbar small { font-size: var(--wao-font-caption); font-weight: 400; color: var(--wao-text-3); }
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
.reader-directory nav small { display: block; color: var(--wao-text-faint); font-size: var(--wao-font-caption); margin-top: 8px; }
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
.reader-tags span { padding: 4px 9px; border-radius: 999px; background: var(--wao-bg-hover); color: var(--wao-primary-text); font-size: var(--wao-font-caption); }
.reader-body { min-width: 0; overflow-wrap: anywhere; font-size: var(--wao-font-reading); line-height: 1.8; }
.reader-article :deep(.md-body .tiptap) { font-size: var(--wao-font-reading); line-height: 1.8; }
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
  .reader-mode-switch button { font-size: var(--wao-font-caption); }
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
@media (max-width: 1100px) { .writing-resizer { display: none; } .notes-page { height: auto; display: block; overflow: visible; padding: 20px; } .notes-workspace { display: grid; gap: 18px; grid-template-columns: minmax(0, 1fr); overflow: visible; } .writing-pane { position: static; min-height: 0; overflow: visible; } .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 330px; max-height: none; } .notes-list { overflow: visible; border-left: 0; border-top: 1px solid var(--wao-line); padding: 0 10px 24px; } .search { flex: 1 1 50%; } }
@media (max-width: 820px) { .notes-workspace { grid-template-columns: minmax(0, 1fr); gap: 24px; } .writing-pane { position: static; } .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 380px; max-height: none; } .writing-pane :deep(.note-composer:not(.fullscreen) .md-body) { min-height: 230px !important; } .notes-list { border-left: 0; border-top: 1px solid var(--wao-line); padding: 0 10px 24px; } }
@media (max-width: 760px) {
  .page-heading { gap: 8px; }
  .heading-title { gap: 8px; flex-wrap: wrap; }
  .writing-toggle, .toolbar button, .reader-toggle { min-height: 44px; }
  .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 300px; }
  .writing-pane :deep(.note-composer:not(.fullscreen) .md-body) { min-height: 120px !important; }
  .notes-list { padding: 0 0 24px; }
  .notes-page .note-body :deep(.md-body .tiptap), .notes-page .reader-article :deep(.md-body .tiptap) { font-size: 16px; line-height: 1.7; }
}
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

.note-edit-action { display: inline-flex; align-items: center; gap: 5px; min-height: 32px; color: var(--wao-primary-text); background: var(--wao-bg-hover); font-size: var(--wao-font-caption); white-space: nowrap; opacity: 0; visibility: hidden; }
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
@media(max-width:600px){.notes-settings-toggle{padding:8px;min-width:40px}.notes-settings-toggle span{display:none}}
</style>

<style scoped>
.companion-resizer { display:none; }
@media(max-width:760px){.writing-pane :deep(.ai-companion.mobile-open){max-height:none;min-height:0}}
@media(max-width:1100px){.writing-pane{display:flex;flex-direction:column;gap:14px}.writing-pane :deep(.ai-companion){flex:none;max-height:660px}.writing-pane :deep(.note-composer:not(.fullscreen)){max-height:none}}
@media(hover:none),(pointer:coarse){.companion-receipt{opacity:1;visibility:visible;min-height:44px}}
@media(max-width:600px){.notes-settings-toggle{padding:8px;min-width:40px}.notes-settings-toggle span{display:none}}
</style>

<style scoped>
.note-preview-actions { display: inline-flex; flex: none; opacity: 0; pointer-events: none; transition: opacity .16s; }
.note-card:hover .note-preview-actions, .note-card:focus-within .note-preview-actions { opacity: 1; pointer-events: auto; }
.note-preview-actions button { display: inline-flex; align-items: center; gap: 5px; min-height: 32px; padding: 5px 8px; font-size: var(--wao-font-caption); white-space: nowrap; color: var(--wao-primary-text); background: var(--wao-bg-hover); }
.note-preview-action { border-radius: 7px 0 0 7px; }
.note-preview-choice { border-radius: 0 7px 7px 0; border-left: 1px solid var(--wao-line); }
@media (hover: none), (pointer: coarse) { .note-preview-actions { opacity: 1; pointer-events: auto; } .note-preview-actions button { min-height: 40px; } }
/* 手机先读内容；次要操作按需展开，保留原有按钮与状态。 */
@media (max-width: 760px) {
  .note-meta { gap: 4px 8px; margin-bottom: 8px; }
  .note-meta > time { flex: 1; min-width: 0; }
  .note-mobile-actions { display: inline-flex; align-items: center; justify-content: center; margin-left: auto; width: 44px; height: 44px; padding: 0; font-size: 20px; flex: none; }
  .note-meta .stage-list, .note-meta-trailing { display: none; }
  .mobile-actions-open .stage-list { display: flex; order: 2; width: 100%; opacity: 1; visibility: visible; }
  .mobile-actions-open .stage-list :deep(.stage-icon) { width: 44px; height: 44px; }
  .mobile-actions-open .note-meta-trailing { display: flex; order: 3; flex-wrap: wrap; width: 100%; gap: 6px; margin: 0; }
  .mobile-actions-open .note-meta-trailing button, .mobile-actions-open .full-doc-icon { min-width: 44px; min-height: 44px; }
  .mobile-actions-open .note-four-action, .mobile-actions-open .note-edit-action, .mobile-actions-open .card-quick-actions { opacity: 1; visibility: visible; }
  .mobile-actions-open .note-preview-actions { opacity: 1; pointer-events: auto; }
  .mobile-actions-open .card-quick-actions { flex-wrap: wrap; max-width: 100%; }
  .note-card.fixed-height { height: auto; min-height: 0; max-height: none; }
  .note-content.has-images { display: flex; flex-direction: column; gap: 12px; }
  .note-content.has-images > .note-body { width: 100%; }
  .note-images, .note-images.expanded { display: flex; flex-wrap: nowrap; align-items: flex-start; gap: 8px; width: 100%; max-height: none; overflow-x: auto; padding: 8px 0 0; border-left: 0; border-top: 1px solid var(--wao-line); }
  .note-images > span { flex: 0 0 44px; flex-direction: column; padding-top: 5px; }
  .note-image-item { flex: 0 0 96px; }
  .note-image-return { min-width: 44px; min-height: 44px; }
  .note-read-guide { position: static; margin-top: 8px; }
  .note-read-guide > span { display: none; }
  .note-read-action { min-height: 44px; opacity: 1; visibility: visible; }
  .note-read-guide { opacity: 1; visibility: visible; pointer-events: auto; }
  .list-toolbar .search { min-height: 44px; }
  .list-toolbar > button { min-width: 44px; min-height: 44px; }
}
@media(max-width:600px){.notes-settings-toggle{padding:8px;min-width:40px}.notes-settings-toggle span{display:none}}
</style>

<style scoped>
.writing-pane { display:flex; flex-direction:column; gap:0; }
.writing-pane :deep(.note-composer:not(.fullscreen)) { flex:1; }
.list-toolbar { position:sticky; top:0; z-index:20; margin-top:0; padding:16px 0 12px; background:var(--wao-bg-sub); }
.note-meta { position:relative; flex-wrap:nowrap; min-width:0; height:28px; margin-bottom:8px; gap:6px; }
.note-meta > time { min-width:0; flex:1 1 auto; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.note-meta > .author { flex:none; }
.note-meta .stage-list { flex:none; }
.note-meta-trailing { flex:none; flex-wrap:nowrap; gap:6px; margin-left:auto; }
.note-more-actions { width:28px; height:28px; padding:0; flex:none; border:0; background:transparent; }
.note-heading { flex-wrap:nowrap; min-width:0; }
.note-heading h2 { min-width:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.card-tags { flex-wrap:nowrap; max-width:35%; overflow:hidden; white-space:nowrap; }
.four-charge-hint { position:absolute; top:30px; right:0; z-index:3; padding:4px 8px; border-radius:6px; background:var(--wao-bg-card); box-shadow:0 2px 8px color-mix(in srgb,var(--wao-text) 12%,transparent); }
.note-touch-ai { display:none; }
.reader-article :deep(.note-composer.contained) { border:0; padding:0; min-height:65dvh; }
@media (min-width:1101px) {.writing-pane { display:flex; overflow:hidden; } .writing-pane :deep(.note-composer:not(.fullscreen)) { height:100%; min-height:0; max-height:none; box-sizing:border-box; } .writing-pane :deep(.editor-space), .writing-pane :deep(.md-body) { min-height:0!important; max-height:none; }}
@media (max-width:1100px) {.list-toolbar { top:0; margin-top:0; } }
@media (max-width:760px) {.note-meta { height:32px; } .note-meta-trailing, .mobile-actions-open .note-meta-trailing { display:flex; width:auto; order:0; flex-wrap:nowrap; gap:2px; margin-left:auto; } .note-count { display:none; } .note-touch-ai { display:inline-grid; place-items:center; min-width:40px; min-height:40px; border:0; background:transparent; padding:0; } .note-more-actions { width:40px;height:40px; } .note-meta .stage-list { display:none; } .note-meta .stage-list-open { position:absolute; display:flex; width:auto; right:0; top:36px; z-index:5; padding:6px; border:1px solid var(--wao-line); border-radius:8px; background:var(--wao-bg-card); visibility:visible; opacity:1; } .stage-list-open :deep(.stage-icon) { min-width:44px; min-height:44px; } .card-tags { display:none; } .note-meta > time { font-size: var(--wao-font-caption); } }
@media(max-width:600px){.notes-settings-toggle{padding:8px;min-width:40px}.notes-settings-toggle span{display:none}}
</style>

<style scoped>
.note-card { container-type:inline-size; }
.note-meta-trailing .card-quick-actions { gap:2px; }
@container (max-width:560px) { .note-meta-trailing .card-quick-actions, .note-meta-trailing .saved { display:none; } }
@media(max-width:760px) {.note-meta-trailing .card-quick-actions { display:none; }}
@media(max-width:600px){.notes-settings-toggle{padding:8px;min-width:40px}.notes-settings-toggle span{display:none}}
</style>

<style scoped>
.note-meta .compact-workflow { flex:0 1 180px; margin:0; min-width:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-size: var(--wao-font-caption); }
.compact-workflow button { display:block; width:100%; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; text-align:left; }
@container(max-width:560px){.note-meta .compact-workflow{max-width:90px}}
@media(max-width:760px){.note-meta .compact-workflow{max-width:100px}}
@media(max-width:600px){.notes-settings-toggle{padding:8px;min-width:40px}.notes-settings-toggle span{display:none}}
</style>

<style scoped>
/* 标题独占首行，作者和操作共用次行，避免长作者挤压标题。 */
.note-meta{display:grid;grid-template-columns:minmax(0,1fr);align-items:center;gap:var(--wao-space-1);height:auto;min-height:32px;margin-bottom:var(--wao-space-2);position:relative;min-width:0}
.note-meta .note-heading{display:block;min-width:0;margin:0;width:100%;overflow:hidden}
.note-meta .note-heading h2{display:block;margin:0;width:100%;font-size:var(--wao-font-card-title);line-height:var(--wao-line-heading);white-space:normal;overflow:visible;text-overflow:ellipsis;-webkit-line-clamp:unset}
.note-card.fixed-height .note-meta .note-heading h2 { display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden; }
/* 身份和悬浮操作在同一个网格单元替换；保存状态、字数独立排列。 */
.card-action-slot{display:grid;flex:1;min-width:0;align-items:center}
.card-identity,.card-hover-actions{grid-area:1/1;min-width:0;display:flex;align-items:center;justify-content:flex-start;gap:7px}
.card-identity{overflow:hidden;white-space:nowrap;font-size:var(--wao-font-caption);color:var(--wao-text-3)}.card-identity time{flex:none}.card-identity .author{flex:0 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;font-weight:400}
.card-hover-actions{visibility:hidden;opacity:0;pointer-events:none;gap:8px}
.note-meta-trailing{display:flex;min-width:0;width:100%;margin:0;gap:6px;justify-content:flex-end;flex-wrap:nowrap}
.note-meta-trailing .saved,.note-meta-trailing .note-count{flex:none;margin:0;white-space:nowrap}
.note-meta-trailing .saved{font-size:var(--wao-font-caption)}
.note-meta .stage-list{position:static;visibility:inherit;opacity:1;gap:4px;margin:0;flex:none}
.note-meta-trailing .card-quick-actions{display:flex;opacity:1;visibility:inherit;pointer-events:inherit;flex:none}
.note-card:hover .card-identity,.note-card:focus-within .card-identity{visibility:hidden;opacity:0}
.note-card:hover .card-hover-actions,.note-card:focus-within .card-hover-actions{visibility:visible;opacity:1;pointer-events:auto}

.four-charge-hint{position:absolute;right:0;top:33px;z-index:2;background:var(--wao-bg-card);border-radius:6px;padding:3px 7px}
.note-card-footer{display:flex;align-items:center;justify-content:space-between;gap:10px;position:relative;min-height:28px;padding:var(--wao-space-1) 0 0;margin:0;background:var(--wao-bg-card);opacity:1;pointer-events:auto;transition:opacity .16s;z-index:1}
.note-card:hover .note-card-footer,.note-card:focus-within .note-card-footer{opacity:1;pointer-events:auto}
.note-card.fixed-height .note-card-footer{position:absolute;inset:auto 0 0;padding:22px 16px 9px;margin:0;background:linear-gradient(transparent,var(--wao-bg-card) 42%);z-index:1}
.note-card-footer .card-tags{display:flex;flex:1;min-width:0;max-width:none;gap:5px;flex-wrap:nowrap;overflow:auto;white-space:nowrap;scrollbar-width:thin}.note-card-footer .card-tags button{flex:none;overflow:hidden;text-overflow:ellipsis;max-width:180px;font-size:var(--wao-font-caption)}
.note-card-footer .note-read-guide,.note-card.expanded .note-card-footer .note-read-guide{position:static;inset:auto;min-height:0;margin:0;padding:0;background:none;opacity:1;pointer-events:inherit;flex:none}
.note-card-footer .compact-workflow{min-width:0;max-width:160px;margin:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:var(--wao-font-caption)}.compact-workflow button{color:var(--wao-primary-text);font:inherit;border:0;padding:0;background:transparent;cursor:pointer}
.notes-toolbar{margin-top:0}.note-image-lightbox img{max-height:calc(100dvh - 150px)}
@container(max-width:700px){.card-identity{max-width:100%}.note-meta .stage-list{position:static}.note-meta-trailing .card-quick-actions{display:none}}
@media(max-width:760px){.note-meta{grid-template-columns:minmax(0,1fr) 88px;min-height:44px}.card-identity{display:none}.note-meta .stage-list{display:none}.note-meta .stage-list-open{position:absolute;display:flex;top:100%;right:0;opacity:1;visibility:visible}.note-meta-trailing{display:flex;width:100%;gap:2px}.card-action-slot{min-width:0;flex:0}.card-hover-actions{visibility:visible;opacity:1}.note-card-footer .compact-workflow{max-width:90px}.notes-trash-toggle{min-width:40px;padding:8px}.notes-trash-toggle span{display:none}}
@media(hover:none),(pointer:coarse){.note-card-footer{opacity:1;pointer-events:auto}.note-card-footer .card-tags button{min-height:36px}.card-action-slot .card-identity{display:none}.card-hover-actions{opacity:1;visibility:visible;pointer-events:auto}}
</style>

<style scoped>@media(max-width:600px){.heading-title{gap:6px}.daily-count-label{display:none}.daily-note-count{padding:5px 7px}.heading-title h1{font-size: var(--wao-font-title); line-height: var(--wao-line-heading); font-weight: var(--wao-weight-heading);}.heading-title .writing-toggle{font-size: var(--wao-font-caption);padding:8px 7px}.notes-trash-toggle{width:36px;min-width:36px}.notes-settings-toggle{min-width:36px}}</style>

<style scoped>
.reader-layout-actions{gap:10px;flex-wrap:nowrap}.reader-mode-switch{background:var(--wao-bg-sub);border:1px solid var(--wao-line);padding:3px;border-radius:9px;gap:2px}.reader-mode-switch button{border:0;background:transparent}.reader-mode-switch button[aria-pressed="true"]{background:var(--wao-bg-card);box-shadow:0 1px 4px rgb(20 24 45 / .06)}.reader-more{position:relative;flex:none}.reader-more summary{display:flex;align-items:center;gap:5px;min-height:36px;box-sizing:border-box;padding:6px 10px;border:1px solid var(--wao-line-control);border-radius:7px;cursor:pointer;font-size:12px;color:var(--wao-text-3);list-style:none}.reader-more summary::-webkit-details-marker{display:none}.reader-more summary:focus-visible{outline:2px solid var(--wao-primary);outline-offset:2px}.reader-more-panel{position:absolute;right:0;top:calc(100% + 8px);width:190px;padding:6px;display:grid;gap:2px;border:1px solid var(--wao-line-control);border-radius:10px;background:var(--wao-bg-card);box-shadow:var(--wao-shadow-pop);z-index:100}.reader-more-panel button{justify-content:flex-start;border:0;min-height:38px;background:transparent}.reader-more-panel button:hover{background:var(--wao-bg-hover)}.reader-more-panel button:has(.ri-delete-bin-line){color:var(--wao-danger-strong);border-top:1px solid var(--wao-line);border-radius:0;margin-top:4px}.reader-copy-status{max-width:200px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}@media(max-width:760px){.reader-layout-actions{gap:6px;flex-wrap:wrap;padding-right:44px}.reader-mode-switch button{padding:6px 8px}.reader-more-panel{right:0;max-height:55dvh;overflow:auto}.reader-copy-status{display:none}}
</style>

<style scoped>
.notes-page-actions { display:none; position:relative; }
.notes-page-actions-panel { position:absolute; top:calc(100% + 6px); right:0; z-index:20; display:grid; width:160px; padding:6px; border:1px solid var(--wao-line-control); border-radius:10px; background:var(--wao-bg-card); box-shadow:var(--wao-shadow-pop); }
.notes-page-actions-panel button { display:flex; align-items:center; gap:8px; text-align:left; }
@media(max-width:760px) {
  .page-heading { margin-bottom:8px; gap:4px; }
  .heading-title { flex:1 1 100%; flex-wrap:nowrap; gap:6px; }
  .heading-title h1 { font-size: var(--wao-font-title); gap:6px;  font-weight: var(--wao-weight-heading); line-height: var(--wao-line-heading);}
  .heading-title .daily-note-count { min-width:0; padding:5px 6px; font-size: var(--wao-font-caption); white-space:normal; }
  .daily-count-label, .heading-title .notes-settings-toggle, .heading-title .notes-trash-toggle, .writing-region-toggle span { display:none; }
  .heading-title .writing-region-toggle { flex:none; margin-left:auto; min-width:44px; min-height:44px; padding:8px; }
  .writing-region-toggle i { margin:0; }
  .notes-page-actions { display:block; flex:none; }
  .notes-page-actions button { min-width:44px; min-height:44px; }
  .filter-toggle, .filter-options button, .filter-group select, .system-visibility, .list-toolbar > button, .reader-layout-actions button, .reader-more summary, .workflow-status button { min-height:44px; }
  .note-touch-ai, .note-more-actions { min-width:44px; min-height:44px; }
  .search { min-height:44px; box-sizing:border-box; }
  .search input { font-size:16px; }
  .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height:270px; }
}
</style>
