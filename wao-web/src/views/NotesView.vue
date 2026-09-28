<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import { docShareUrl } from '@/utils/share'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { defineAsyncComponent, computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import NoteMiningPanel from '@/components/notes/NoteMiningPanel.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { resolvedRichContent } from '@/utils/richDoc'
import { notePreviewImages, notePreviewSource, type NotePreviewImage } from '@/utils/notePreviewImages'
import { firstMarkdownTextLine } from '@/utils/markdownTitle'
import type { Doc } from '@/types'
import ImageCopyMenu from '@/components/ui/ImageCopyMenu.vue'
import { canRetryAutoNoteEnrichment, discardAutoNoteEnrichment, initializeNoteWorkflow, noteEnrichmentProgress, noteEnrichmentStatus, notePolishVersions, retryAutoNoteEnrichment, startAutoNoteEnrichment } from '@/utils/autoNoteEnrichment'
import { apiCreateNoteTag, apiDeleteNoteTag, apiMergeNoteTag, fetchNoteTags, fetchKbs } from '@/api/board'
import { fetchPreference, savePreference } from '@/api/favorites'

const ui = useUiStore()
const contextMenu = ref<InstanceType<typeof ContextActionMenu> | null>(null)

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
const session = useSessionStore()
const noteEditors = new Map<string, { copyFormatted: () => Promise<void> }>()
const noteCounts = ref<Record<string, number>>({})
const previewImage = ref<NotePreviewImage | null>(null)
const imageMenu = ref<{ src: string; x: number; y: number } | null>(null)
function openImageMenu(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof HTMLImageElement) || !target.closest('.note-image-thumb, .note-image-lightbox')) return
  event.preventDefault()
  imageMenu.value = { src: target.dataset.fullSrc || target.currentSrc || target.src, x: event.clientX, y: event.clientY }
}
const lightbox = ref<HTMLElement | null>(null)
let imageTrigger: HTMLElement | null = null
async function openImage(image: NotePreviewImage, event: MouseEvent) {
  imageTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  previewImage.value = image
  await nextTick()
  lightbox.value?.focus()
}
async function closeImage() {
  previewImage.value = null
  await nextTick()
  imageTrigger?.focus()
  imageTrigger = null
}
function setNoteEditor(id: string, value: unknown) { if (value) noteEditors.set(id, value as { copyFormatted: () => Promise<void> }); else noteEditors.delete(id) }
const query = ref('')
const sort = ref<'updatedDesc' | 'createdDesc' | 'updatedAsc'>('updatedDesc')
const imageFilter = ref<'all' | 'yes' | 'no'>('all')
const timeFilter = ref<'all' | 'today' | 'yesterday' | 'earlier'>('all')
const selectedAuthor = ref('')
const filterOpen = ref(false)
const filterControl = ref<HTMLElement>()
const selectedTag = ref<string | null>('')
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
async function manageTag(merge: boolean) {
  if (tagSaving.value || !sourceTag.value || (merge && (!targetTag.value || targetTag.value === sourceTag.value))) return
  const source = sourceTag.value, target = targetTag.value
  if (!window.confirm(merge ? `将“${source}”合并到“${target}”？文章保留，重复标签会去重。` : `删除标签“${source}”？只移除标签，不删除文章。`)) return
  tagSaving.value = true
  tagsError.value = ''
  try {
    if (merge) await apiMergeNoteTag(source, target)
    else await apiDeleteNoteTag(source)
    if (selectedTag.value === source) selectedTag.value = merge ? target : ''
    sourceTag.value = ''
    targetTag.value = ''
    await Promise.all([loadTags(), data.refreshDocs()])
  } catch (cause) { tagsError.value = cause instanceof Error ? cause.message : '标签修改失败，请重试' }
  finally { tagSaving.value = false }
}
const addingTag = ref(false)
const newTag = ref('')
const creatingTag = ref(false)
const tagError = ref('')
const editingIds = ref<string[]>([])
const deletingId = ref('')
const confirmingDeleteId = ref('')
const deleteError = ref<{ id: string; message: string } | null>(null)
function closeEditing(id: string) { editingIds.value = editingIds.value.filter(item => item !== id) }
function toggleDeleteConfirmation(id: string) {
  if (deletingId.value) return
  confirmingDeleteId.value = confirmingDeleteId.value === id ? '' : id
  deleteError.value = null
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
    { id: 'copy', label: '复制内容', run: () => noteEditors.get(id)?.copyFormatted() },
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
    try { localStorage.removeItem(`wao-note-edit-v1:${session.currentUser.id}:${id}`) } catch { /* 文档已进入回收站。 */ }
    confirmingDeleteId.value = ''
    closeEditing(id)
    if (highlighted.value === id) highlighted.value = ''
    toast('小记已移入知识库回收站，7 天内可恢复', 'ri-delete-bin-line')
  } catch {
    deleteError.value = { id, message: '删除失败，小记未改变；若它包含子节点，请先在完整文档中移走后重试。' }
  } finally { deletingId.value = '' }
}
async function editNote(id: string, event?: MouseEvent) {
  if (event?.target instanceof Element && event.target.closest('a, button, input, select, textarea, summary, [contenteditable="true"]')) return
  confirmingDeleteId.value = ''
  if (!editingIds.value.includes(id)) editingIds.value = [...editingIds.value, id]
  await nextTick()
  const card = Array.from(notesList.value?.querySelectorAll<HTMLElement>('[data-note-id]') ?? []).find(el => el.dataset.noteId === id)
  card?.querySelector<HTMLElement>('[contenteditable="true"], textarea')?.focus({ preventScroll: true })
}
const PAGE_SIZE = 10
const limit = ref(PAGE_SIZE)
const notesList = ref<HTMLElement>()
const highlighted = ref('')
const reading = ref(false)
const readingIndex = ref(0)
const miningOpen = ref(false)
const miningDialog = ref<HTMLElement | null>(null)
let miningTrigger: HTMLElement | null = null
const readerNotes = computed(() => matching.value)
const readingNote = computed(() => readerNotes.value[readingIndex.value] ?? null)
function startReading(id?: string) {
  const index = id ? readerNotes.value.findIndex(note => note.id === id) : 0
  if (!readerNotes.value.length) return
  readingIndex.value = Math.max(0, index)
  reading.value = true
}
async function openMiningSource(id: string) {
  await closeMining()
  query.value = ''
  resetFilters()
  limit.value = Math.max(limit.value, notes.value.length)
  await nextTick()
  startReading(id)
}
async function openMining(event: MouseEvent) {
  miningTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  miningOpen.value = true
  await nextTick()
  miningDialog.value?.focus()
}
async function closeMining() {
  miningOpen.value = false
  await nextTick()
  miningTrigger?.focus()
  miningTrigger = null
}
function stopReading() { reading.value = false }
function moveReader(delta: number) {
  if (!readerNotes.value.length) return
  readingIndex.value = Math.min(readerNotes.value.length - 1, Math.max(0, readingIndex.value + delta))
}
function onReaderKeydown(event: KeyboardEvent) {
  if (miningOpen.value && event.key === 'Escape') { event.preventDefault(); void closeMining(); return }
  if (!reading.value || previewImage.value) return
  const target = event.target
  if (target instanceof HTMLElement && target.closest('input, textarea, select, [contenteditable="true"]')) return
  if (event.key === 'Escape') { event.preventDefault(); stopReading(); return }
  if (['ArrowRight', 'ArrowDown', 'PageDown', ' '].includes(event.key)) { event.preventDefault(); moveReader(1); return }
  if (['ArrowLeft', 'ArrowUp', 'PageUp'].includes(event.key)) { event.preventDefault(); moveReader(-1); return }
  if (event.key === 'Home') { event.preventDefault(); readingIndex.value = 0 }
  if (event.key === 'End') { event.preventDefault(); readingIndex.value = Math.max(0, readerNotes.value.length - 1) }
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
const availableTags = computed(() => [...new Set([...tagNames.value, ...notes.value.flatMap(note => note.tags ?? [])])].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const availableAuthors = computed(() => [...new Set(notes.value.map(note => note.author).filter(Boolean))]
  .map(id => ({ id, name: authorName(id) }))
  .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN') || a.id.localeCompare(b.id)))
const tagCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const note of notes.value) for (const tag of note.tags ?? []) counts.set(tag, (counts.get(tag) ?? 0) + 1)
  return counts
})
const matching = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return notes.value.filter(doc => (selectedTag.value === null ? !doc.tags?.length : !selectedTag.value || doc.tags?.includes(selectedTag.value))
    && (!selectedAuthor.value || doc.author === selectedAuthor.value)
    && (imageFilter.value === 'all' || (notePreviewImages(resolvedRichContent(doc)).length > 0) === (imageFilter.value === 'yes'))
    && inTimeRange(doc.updated, timeFilter.value)
    && (!needle || `${doc.title}\n${resolvedRichContent(doc).markdown}\n${validPolish(doc)?.polishedMarkdown ?? ''}\n${(doc.tags ?? []).join(' ')}`.toLocaleLowerCase().includes(needle)))
})
function inTimeRange(value: string, range: typeof timeFilter.value) {
  if (range === 'all') return true
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return range === 'earlier'
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today); yesterday.setDate(yesterday.getDate() - 1)
  return range === 'today' ? date >= today : range === 'yesterday' ? date >= yesterday && date < today : date < yesterday
}
function validPolish(note: { id: string; blocks: Doc['blocks']; content?: Doc['content'] }) {
  const version = notePolishVersions[note.id]
  return version?.sourceMarkdown === resolvedRichContent(note).markdown ? version : null
}
const versions = ref<Record<string, 'polished' | 'original'>>({})
function showPolished(note: Doc) { return !!validPolish(note) && versions.value[note.id] !== 'original' }
function displayedMarkdown(note: Doc) { return showPolished(note) ? validPolish(note)!.polishedMarkdown : resolvedRichContent(note).markdown }
function displayTitle(note: Doc) { return note.title.trim() !== firstMarkdownTextLine(resolvedRichContent(note).markdown).trim() }
function stageIcon(stage: 'title' | 'image' | 'polish') { return { title: 'T', image: '▧', polish: '✦' }[stage] }
function stageLabel(stage: 'title' | 'image' | 'polish') { return { title: '标题', image: '配图', polish: '润色' }[stage] }
function progressVisible(id: string) {
  const steps = noteEnrichmentProgress[id]
  return !!steps && !((steps.title === 'done' || steps.title === 'skipped') && (steps.image === 'done' || steps.image === 'skipped') && (steps.polish === 'done' || steps.polish === 'skipped'))
}
function retryOrStart(note: Doc) {
  if (canRetryAutoNoteEnrichment(session.currentUser.id, note.id)) retryAutoNoteEnrichment(session.currentUser.id, note.id)
  else if (note.rev) startAutoNoteEnrichment(session.currentUser.id, note.id, note.rev, { title: true, image: true })
}
function onStageClick(note: Doc, stage: 'title' | 'image' | 'polish') {
  if (noteEnrichmentProgress[note.id]?.[stage] === 'failed') retryOrStart(note)
}
// 筛选、分页或远端刷新不能卸载正在输入的卡片，尤其是浏览器草稿写入失败时。
const retainedEdits = computed(() => notes.value.filter(note => editingIds.value.includes(note.id)
  && !matching.value.slice(0, limit.value).some(item => item.id === note.id)))
const visible = computed(() => [...matching.value.slice(0, limit.value), ...retainedEdits.value])
const visibleImages = computed(() => new Map(visible.value.map(note => [note.id, notePreviewImages(resolvedRichContent(note))])))
function imagesFor(id: string) { return visibleImages.value.get(id) ?? [] }
const loading = computed(() => !refreshing.value && (['idle', 'loading'].includes(data.loadStates.docs) || ['idle', 'loading'].includes(data.loadStates.knowledge)))
const loadError = computed(() => refreshError.value || data.loadErrors.docs || data.loadErrors.knowledge)
watch([query, selectedTag, sort, imageFilter, timeFilter], () => {
  limit.value = PAGE_SIZE
  confirmingDeleteId.value = ''
  notesList.value?.scrollTo?.({ top: 0 })
})
function onCreated(id: string) {
  query.value = ''
  selectedTag.value = ''
  limit.value = PAGE_SIZE
  highlighted.value = id
  notesList.value?.scrollTo?.({ top: 0 })
}
function onEdited(sourceId: string, savedId: string) {
  closeEditing(sourceId)
  highlighted.value = savedId
  // 冲突另存副本时展示新记录；普通修改保留当前筛选和滚动位置。
  if (sourceId !== savedId) onCreated(savedId)
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
onMounted(() => { void data.ensureDocsLoaded(); void loadTags(); window.addEventListener('scroll', onNotesScroll, { passive: true }) })
onMounted(() => { void initializeNoteWorkflow(session.currentUser.id); window.addEventListener('pointerdown', onOutsideFilter); window.addEventListener('keydown', onReaderKeydown) })
onBeforeUnmount(() => { window.removeEventListener('pointerdown', onOutsideFilter); window.removeEventListener('keydown', onReaderKeydown); window.removeEventListener('scroll', onNotesScroll) })
watch(() => session.currentUser.id, memberId => { void initializeNoteWorkflow(memberId); versions.value = {} })
onMounted(() => void loadPins())
</script>

<template>
  <main class="notes-page" @contextmenu="openImageMenu">
    <ImageCopyMenu v-if="imageMenu" v-bind="imageMenu" @close="imageMenu = null" />
    <header class="page-heading"><div><h1><i class="ri-sticky-note-line"></i>小记</h1></div><span>{{ notes.length }} 条记录</span></header>
    <div class="notes-workspace">
      <aside class="writing-pane" aria-label="新建小记区">
        <NoteComposer :key="`${session.currentUser.id}:new`" :loading="loading" :tag-options="availableTags" @tag-created="tagNames = [...new Set([...tagNames, $event])]" @saved="onCreated" />
      </aside>
      <section ref="notesList" class="notes-list" aria-label="小记清单" @scroll.passive="onNotesScroll">
        <div class="list-toolbar">
          <label class="search"><i class="ri-search-line"></i><input v-model="query" type="search" placeholder="搜索内容或标签" aria-label="搜索小记内容"></label>
          <div ref="filterControl" class="filter-control">
            <button class="filter-toggle" type="button" :aria-expanded="filterOpen" aria-controls="note-filter-panel" @click="filterOpen = !filterOpen"><i class="ri-equalizer-line"></i>筛选与排序<span v-if="activeFilterCount" class="filter-count">{{ activeFilterCount }}</span><i class="ri-arrow-down-s-line"></i></button>
            <div v-if="filterOpen" id="note-filter-panel" class="filter-panel" role="group" aria-label="小记筛选与排序">
              <div class="filter-group"><strong>标签</strong><div class="filter-options"><button type="button" :aria-pressed="selectedTag === ''" @click="selectedTag = ''">全部</button><button type="button" :aria-pressed="selectedTag === null" @click="selectedTag = null">无标签</button><button v-for="tag in availableTags" :key="tag" type="button" :aria-pressed="selectedTag === tag" @click="selectedTag = tag"># {{ tag }}</button></div></div>
              <div class="filter-group"><strong>作者</strong><select v-model="selectedAuthor" aria-label="按作者筛选"><option value="">全部作者</option><option v-for="author in availableAuthors" :key="author.id" :value="author.id">{{ author.name }}</option></select></div>
              <div class="filter-group"><strong>图片</strong><div class="filter-options"><button v-for="option in [{ value: 'all', label: '全部' }, { value: 'yes', label: '有图' }, { value: 'no', label: '无图' }]" :key="option.value" type="button" :aria-pressed="imageFilter === option.value" @click="imageFilter = option.value as typeof imageFilter">{{ option.label }}</button></div></div>
              <div class="filter-group"><strong>更新时间</strong><div class="filter-options"><button v-for="option in [{ value: 'all', label: '不限' }, { value: 'today', label: '今天' }, { value: 'yesterday', label: '昨天' }, { value: 'earlier', label: '以前' }]" :key="option.value" type="button" :aria-pressed="timeFilter === option.value" @click="timeFilter = option.value as typeof timeFilter">{{ option.label }}</button></div></div>
              <div class="filter-group"><strong>排序</strong><select v-model="sort" aria-label="小记排序"><option value="updatedDesc">最近更新</option><option value="createdDesc">最近创建</option><option value="updatedAsc">最早更新</option></select></div>
              <div class="filter-footer"><button type="button" @click="addingTag = !addingTag; filterOpen = false">新增标签</button><button type="button" @click="managingTags = !managingTags; filterOpen = false">管理标签</button><button type="button" @click="resetFilters">清除筛选</button></div>
            </div>
          </div>
          <button class="reader-toggle" type="button" :disabled="!matching.length" @click="startReading()"><i class="ri-book-open-line"></i><span>阅读模式</span></button>
          <button class="reader-toggle mining-toggle" type="button" aria-haspopup="dialog" :aria-expanded="miningOpen" @click="openMining"><i class="ri-sparkling-2-line"></i><span>AI 挖掘</span></button>
          <button class="refresh" :disabled="loading || refreshing" :aria-busy="refreshing" title="刷新清单" aria-label="刷新小记清单" @click="reload"><i :class="refreshing ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i></button>
        </div>
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
        <article v-for="note in visible" :key="note.id" class="note-card fixed-height" :class="{ highlighted: highlighted === note.id, editing: editingIds.includes(note.id) }" :data-note-id="note.id"
          tabindex="0" title="按 Enter 阅读，双击在卡片内编辑" :aria-label="`小记：${note.title}，按 Enter 开始阅读，双击编辑`"
          @dblclick="editNote(note.id, $event)" @keydown.enter.self.prevent="startReading(note.id)" @keydown.esc="cancelDeleteOnEscape(note.id, $event)" @keydown="openNoteMenu($event, note.id)" @contextmenu="openNoteMenu($event, note.id)">
          <div class="note-meta"><span class="author">{{ authorName(note.author) }}</span><time>更新于 {{ timeLabel(note.updated || note.created) }}</time>
            <div v-if="noteEnrichmentProgress[note.id]" class="stage-list" :class="{ 'stage-list-complete': !progressVisible(note.id) }" role="group" aria-label="AI 产出进度">
              <button v-for="stage in (['title', 'image', 'polish'] as const)" :key="stage" type="button" class="stage-icon" :class="`stage-${noteEnrichmentProgress[note.id]?.[stage]}`" :title="`${stageLabel(stage)}：${{ done: '已完成', running: '正在处理', pending: '等待处理', failed: '失败，点击重试', skipped: '已跳过' }[noteEnrichmentProgress[note.id]?.[stage] || 'pending']}`" :aria-label="`${stageLabel(stage)}${{ done: '已完成', running: '正在处理', pending: '等待处理', failed: '失败，点击重试', skipped: '已跳过' }[noteEnrichmentProgress[note.id]?.[stage] || 'pending']}`" :aria-disabled="noteEnrichmentProgress[note.id]?.[stage] !== 'failed'" @click="onStageClick(note, stage)">{{ stageIcon(stage) }}</button>
            </div>
            <div v-if="validPolish(note)" class="version-toggle" role="group" aria-label="阅读版本"><button type="button" :aria-pressed="showPolished(note)" @click="versions[note.id] = 'polished'">润色</button><button type="button" :aria-pressed="!showPolished(note)" @click="versions[note.id] = 'original'">原文</button></div>
            <span class="note-meta-trailing"><span v-if="highlighted === note.id && !editingIds.includes(note.id)" class="saved">已保存</span><span class="card-quick-actions"><button type="button" :disabled="!pinsReady || pinSaving" :aria-label="pinnedIds.includes(note.id) ? '取消置顶' : '置顶'" :aria-pressed="pinnedIds.includes(note.id)" :title="pinnedIds.includes(note.id) ? '取消置顶' : '置顶'" @click="togglePin(note.id)"><i class="ri-pushpin-line"></i></button><button type="button" aria-label="复制分享链接" title="复制分享链接" @click="copyText(docShareUrl(note.id), '公开分享链接已复制')"><i class="ri-share-forward-line"></i></button><button type="button" class="card-delete-icon" :aria-label="`删除小记：${note.title}`" :aria-expanded="confirmingDeleteId === note.id" title="删除小记（移入回收站）" :disabled="!!deletingId" @click="toggleDeleteConfirmation(note.id)"><i class="ri-delete-bin-line" aria-hidden="true"></i></button><RouterLink class="full-doc-icon" :to="{ path: `/kb/${note.kbId}`, query: { doc: note.id } }" aria-label="完整文档" title="完整文档"><i class="ri-arrow-right-up-line" aria-hidden="true"></i></RouterLink></span><span v-if="noteCounts[note.id] !== undefined && !editingIds.includes(note.id)" class="note-count" :aria-label="`正文 ${noteCounts[note.id]?.toLocaleString('zh-CN')} 字`">{{ noteCounts[note.id]?.toLocaleString('zh-CN') }} 字</span></span></div>
          <p v-if="progressVisible(note.id) && noteEnrichmentStatus[note.id]" class="workflow-status" role="status">{{ noteEnrichmentStatus[note.id] }} <button v-if="canRetryAutoNoteEnrichment(session.currentUser.id, note.id) && noteEnrichmentStatus[note.id]?.includes('未完成')" type="button" @click="retryOrStart(note)">重试</button></p>
          <NoteComposer v-if="editingIds.includes(note.id)" :key="`${session.currentUser.id}:${note.id}`" :note="note" inline autofocus :tag-options="availableTags"
            @tag-created="tagNames = [...new Set([...tagNames, $event])]" @saved="onEdited(note.id, $event)" @close="closeEditing(note.id)" />
          <template v-else>
          <div class="note-heading"><h2>{{ note.title }}</h2><div v-if="note.tags?.length" class="card-tags"><button v-for="tag in note.tags" :key="tag" @click="selectedTag = tag"># {{ tag }}</button></div></div>
          <div class="note-content" :class="{ 'has-images': imagesFor(note.id).length }"><div class="note-body"><MarkdownEditor :ref="value => setNoteEditor(note.id, value)" :model-value="displayedMarkdown(note) || note.title" :document="showPolished(note) ? undefined : resolvedRichContent(note).document" :readonly="true" preview-images bare advanced allow-h1 :min-height="0" @character-count="noteCounts[note.id] = $event" /></div><aside v-if="imagesFor(note.id).length" class="note-image-rail" aria-label="小记图片总览"><div class="rail-heading">图片总览 <span>{{ imagesFor(note.id).length }} 张</span></div><div class="note-images"><button v-for="(image, index) in imagesFor(note.id)" :key="`${index}:${image.src}`" type="button" class="note-image-thumb" :class="{ cover: image.alt === '文章总览图' }" :aria-label="`查看第 ${index + 1} 张图片大图${image.alt ? `：${image.alt}` : ''}`" @click="openImage(image, $event)"><img :src="notePreviewSource(image.src)" :data-full-src="image.src" :alt="image.alt" loading="lazy" decoding="async"><span>{{ image.alt || `图片 ${index + 1}` }}</span></button></div></aside></div>
          <button class="note-read-action" type="button" @click="startReading(note.id)"><i class="ri-book-open-line"></i> 阅读</button>
          </template>
          <div v-if="confirmingDeleteId === note.id" class="card-delete-confirm" role="group" :aria-label="`确认删除小记：${note.title}`">
            <span>将此小记移入回收站？7 天内可恢复。{{ editingIds.includes(note.id) ? '当前未保存的编辑会丢失。' : '' }}</span>
            <span v-if="!data.apiLive" class="card-delete-offline">服务尚未连接，暂时不能删除。</span>
            <button type="button" :disabled="!!deletingId" @click="toggleDeleteConfirmation(note.id)">取消</button>
            <button type="button" class="card-delete-submit" :disabled="!!deletingId || !data.apiLive" @click="deleteNote(note.id)">{{ deletingId === note.id ? '删除中…' : '确认删除' }}</button>
          </div>
          <p v-if="deleteError?.id === note.id" class="card-delete-error" role="alert">{{ deleteError.message }}</p>
        </article>
        <button v-if="matching.length > limit" class="load-more" @click="limit += PAGE_SIZE">加载更多（还有 {{ matching.length - limit }} 条）</button>
        <ContextActionMenu ref="contextMenu" />
      </section>
    </div>
    <button v-if="showBackToTop" class="notes-back-top" type="button" aria-label="回到顶部" title="回到顶部" @click="backToTop"><i class="ri-rocket-2-line" aria-hidden="true"></i></button>
    <Teleport to="body">
      <div v-if="miningOpen" class="mining-modal-mask" @click.self="closeMining">
        <section ref="miningDialog" class="mining-modal" role="dialog" aria-modal="true" aria-label="AI 挖掘小记" tabindex="-1">
          <button class="mining-modal-close" type="button" aria-label="关闭 AI 挖掘" title="关闭（Esc）" @click="closeMining"><i class="ri-close-line"></i></button>
          <NoteMiningPanel compact @open-note="openMiningSource" />
        </section>
      </div>
      <div v-if="reading && readingNote" class="notes-reader" role="dialog" aria-modal="true" aria-label="小记阅读模式" @click.self="stopReading">
        <header class="reader-topbar"><button type="button" aria-label="退出阅读模式" title="退出阅读模式（Esc）" @click="stopReading"><i class="ri-close-line"></i></button><span>最近更新 · {{ readingIndex + 1 }} / {{ readerNotes.length }}</span><span class="reader-progress"><i :style="{ width: `${((readingIndex + 1) / readerNotes.length) * 100}%` }"></i></span></header>
        <button class="reader-nav reader-prev" type="button" aria-label="上一篇小记（←）" :disabled="readingIndex === 0" @click="moveReader(-1)"><i class="ri-arrow-left-s-line"></i></button>
        <article :key="readingNote.id" class="reader-page">
          <div class="reader-meta"><span>{{ authorName(readingNote.author) }}</span><time>{{ timeLabel(readingNote.updated || readingNote.created) }}</time><span>{{ noteCounts[readingNote.id]?.toLocaleString('zh-CN') ?? '' }} 字</span></div>
          <h2>{{ readingNote.title }}</h2>
          <div v-if="readingNote.tags?.length" class="reader-tags"><span v-for="tag in readingNote.tags" :key="tag"># {{ tag }}</span></div>
          <div v-if="validPolish(readingNote)" class="reader-version-toggle" role="group" aria-label="阅读版本"><button type="button" :aria-pressed="showPolished(readingNote)" @click="versions[readingNote.id] = 'polished'">润色版</button><button type="button" :aria-pressed="!showPolished(readingNote)" @click="versions[readingNote.id] = 'original'">原文</button></div>
          <div class="reader-content" :class="{ 'has-images': notePreviewImages(resolvedRichContent(readingNote)).length }"><div class="reader-body"><MarkdownEditor :model-value="displayedMarkdown(readingNote) || readingNote.title" :document="showPolished(readingNote) ? undefined : resolvedRichContent(readingNote).document" :readonly="true" preview-images bare advanced allow-h1 :min-height="0" /></div><aside v-if="notePreviewImages(resolvedRichContent(readingNote)).length" class="reader-image-rail" aria-label="小记图片总览"><h3>图片总览 · {{ notePreviewImages(resolvedRichContent(readingNote)).length }} 张</h3><div><button v-for="(image, index) in notePreviewImages(resolvedRichContent(readingNote))" :key="`${index}:${image.src}`" type="button" @click="openImage(image, $event)"><img :src="notePreviewSource(image.src)" :alt="image.alt"><span>{{ image.alt || `图片 ${index + 1}` }}</span></button></div></aside></div>
        </article>
        <button class="reader-nav reader-next" type="button" aria-label="下一篇小记（→）" :disabled="readingIndex >= readerNotes.length - 1" @click="moveReader(1)"><i class="ri-arrow-right-s-line"></i></button>
        <footer class="reader-controls"><button type="button" :disabled="readingIndex === 0" @click="moveReader(-1)"><i class="ri-arrow-left-line"></i> 上一篇</button><span>方向键 / PageUp PageDown 翻页 · Esc 退出</span><button type="button" :disabled="readingIndex >= readerNotes.length - 1" @click="moveReader(1)">下一篇 <i class="ri-arrow-right-line"></i></button></footer>
      </div>
      <div v-if="previewImage" ref="lightbox" class="note-image-lightbox" role="dialog" aria-modal="true"
        :aria-label="previewImage.alt || '小记图片预览'" tabindex="-1" @click.self="closeImage" @keydown.esc.stop="closeImage" @contextmenu="openImageMenu">
        <button type="button" class="note-image-close" aria-label="关闭图片预览" @click="closeImage"><i class="ri-close-line"></i></button>
        <img :src="previewImage.src" :alt="previewImage.alt">
      </div>
    </Teleport>
  </main>
</template>

<style scoped>
.notes-page { width: 100%; max-width: none; margin: 0 auto; padding: 12px 28px 24px; box-sizing: border-box; color: var(--wao-text-2); }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
h1 { display: flex; gap: 10px; align-items: center; font-size: 18px; margin: 0; color: var(--wao-text); }
h1 i { color: #7a5cc6; }
.page-heading p { margin: 0; color: var(--wao-text-faint); font-size: 13px; }
.page-heading > span { color: var(--wao-text-faint); white-space: nowrap; font-size: 12px; }
.notes-workspace { display: grid; grid-template-columns: minmax(300px, 0.85fr) minmax(360px, 1.15fr); gap: 28px; align-items: start; }
.writing-pane { min-width: 0; position: sticky; top: 20px; }
.writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: min(650px, calc(100dvh - 210px)); max-height: calc(100dvh - 155px); }
.notes-list { min-width: 0; padding: 24px 20px 40px; border: 1px solid var(--wao-line); border-right: 0; border-bottom: 0; background: var(--wao-bg-sub); scroll-padding-block: 24px; }
.list-toolbar, .search, .note-meta, .note-actions, .sort { display: flex; align-items: center; gap: 10px; }
.list-toolbar { margin-bottom: 8px; gap: 8px; }
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
.note-card { position: relative; box-sizing: border-box; height: 300px; min-height: 300px; max-height: 300px; overflow: hidden; background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: 12px; padding: 17px 19px 36px; margin-bottom: 16px; transition: border-color .18s, box-shadow .18s, transform .18s; }
.note-card.editing { height: auto; min-height: 300px; max-height: none; overflow: visible; }
.note-card:hover, .note-card:focus-within { border-color: var(--wao-primary); box-shadow: 0 10px 30px rgba(59, 47, 91, .1); transform: translateY(-2px); }
.note-card.editing:hover, .note-card.editing:focus-within { transform: none; }
.note-card.highlighted, .note-card.editing { border-color: var(--wao-primary); }
.note-card:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
.note-meta { font-size: 11px; color: var(--wao-text-faint); margin-bottom: 16px; flex-wrap: wrap; gap: 5px 9px; }
.stage-list { display: inline-flex; align-items: center; gap: 4px; }
.stage-icon { display: inline-grid; place-items: center; width: 22px; height: 22px; padding: 0; border: 1px solid var(--wao-line-control); border-radius: 50%; font-size: 12px; font-weight: 700; }
.stage-icon:disabled { opacity: 1; }
.stage-done { color: #248455; border-color: #a6dac0; background: #effaf4; }
.stage-running { color: #7453bd; border-color: #c6b4ef; background: #f5f0ff; animation: stage-pulse 1.5s infinite; }
.stage-failed { color: var(--wao-danger-strong); border-color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
.stage-skipped { opacity: .55 !important; }
.stage-list-complete, .version-toggle { opacity: 0; visibility: hidden; transition: opacity .16s; }
.note-card:hover .stage-list-complete, .note-card:focus-within .stage-list-complete, .note-card:hover .version-toggle, .note-card:focus-within .version-toggle { opacity: 1; visibility: visible; }
.version-toggle { display: inline-flex; gap: 2px; border-radius: 7px; background: var(--wao-bg-sub); }
.version-toggle button { padding: 3px 6px; font-size: 11px; }
.version-toggle button[aria-pressed="true"] { color: var(--wao-primary-text); background: var(--wao-bg-card); box-shadow: 0 1px 4px rgba(0,0,0,.1); }
.card-quick-actions { display: inline-flex; gap: 2px; opacity: 0; visibility: hidden; transition: opacity .16s, visibility .16s; }
.note-card:hover .card-quick-actions, .note-card:focus-within .card-quick-actions { opacity: 1; visibility: visible; }
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
.note-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 5px 9px; margin: 0 0 9px; max-height: 52px; overflow: hidden; }
.note-heading h2 { margin: 0; color: var(--wao-text-1); font-size: 16px; line-height: 1.45; font-weight: 600; overflow-wrap: anywhere; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.note-content.has-images { display: grid; grid-template-columns: minmax(0, 1fr) 174px; gap: 14px; align-items: start; }
.note-body { min-height: 0; overflow-wrap: anywhere; line-height: 1.75; font-size: 13px; max-height: 170px; overflow: hidden; }
.note-body :deep(p:first-child) { margin-top: 0; }
.note-body :deep(img.md-img) { display: none !important; }
.note-body :deep(.character-count) { display: none !important; }
.note-body :deep(pre), .note-body :deep(table) { max-width: 100%; overflow: auto; }
.note-image-rail { min-width: 0; padding-left: 12px; border-left: 1px solid var(--wao-line); }
.rail-heading { display: flex; justify-content: space-between; gap: 6px; margin-bottom: 8px; color: var(--wao-text-2); font-size: 11px; }
.rail-heading span { color: var(--wao-text-faint); }
.note-images { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.note-image-thumb { min-width: 0; padding: 3px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-sub); overflow: hidden; text-align: left; }
.note-image-thumb.cover { grid-column: 1 / -1; }
.note-image-thumb:hover, .note-image-thumb:focus-visible { border-color: var(--wao-primary); outline: 2px solid var(--wao-primary); outline-offset: 1px; }
.note-image-thumb img { width: 100%; height: 60px; object-fit: contain; display: block; }
.note-image-thumb.cover img { height: 94px; }
.note-image-thumb span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wao-text-faint); font-size: 10px; }
.note-image-lightbox { position: fixed; inset: 0; z-index: 1200; display: grid; place-items: center; padding: 32px; background: rgba(15, 18, 28, .82); cursor: zoom-out; }
.note-image-lightbox img { max-width: min(96vw, 1600px); max-height: 90vh; object-fit: contain; border-radius: 8px; box-shadow: 0 18px 56px rgba(0, 0, 0, .38); cursor: default; }
.note-image-close { position: fixed; top: 16px; right: 18px; width: 36px; height: 36px; border: 0; border-radius: 50%; background: rgba(255, 255, 255, .16); color: #fff; font-size: 20px; }
.note-image-close:hover { background: rgba(255, 255, 255, .28); color: #fff; }
.card-tags { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.card-tags button { color: var(--wao-primary-text); overflow-wrap: anywhere; max-width: 100%; padding: 3px 7px; }
.card-edit { display: inline-flex; gap: 4px; align-items: center; }
.card-delete { display: inline-flex; gap: 4px; align-items: center; color: var(--wao-danger-strong); }
.card-delete:hover { color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
.card-delete-confirm { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; margin-top: 8px; padding: 8px 10px; border-radius: 8px; background: var(--wao-danger-bg); color: var(--wao-text-2); font-size: 12px; }
.card-delete-confirm span:first-child { margin-right: auto; }
.card-delete-confirm button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); }
.card-delete-confirm .card-delete-submit { border-color: var(--wao-danger-strong); color: var(--wao-danger-strong); font-weight: 600; }
.card-delete-error { margin: 6px 0 0; color: var(--wao-danger-strong); font-size: 12px; }
.empty { text-align: center; padding: 36px 12px; color: var(--wao-text-faint); font-size: 13px; }
.error { color: var(--wao-danger-strong); font-size: 12px; }
.load-more { display: block; margin: 20px auto; }
.reader-toggle { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); white-space: nowrap; }
.reader-toggle i { margin-right: 5px; }
.mining-toggle { color: var(--wao-primary-text); }
.mining-modal-mask { position: fixed; inset: 0; z-index: 1160; display: grid; place-items: center; padding: 24px; background: rgba(24, 20, 38, .42); backdrop-filter: blur(5px); }
.mining-modal { position: relative; width: min(980px, calc(100vw - 48px)); max-height: min(84dvh, 760px); overflow: auto; box-sizing: border-box; padding: 28px 20px 20px; border: 1px solid var(--wao-line); border-radius: 22px; background: var(--wao-bg-card); box-shadow: 0 28px 90px rgba(30, 24, 55, .24); outline: none; animation: mining-modal-in .18s ease-out; }
.mining-modal:focus-visible { outline: 3px solid var(--wao-primary); outline-offset: 3px; }
.mining-modal-close { position: sticky; z-index: 2; top: -18px; float: right; display: grid; place-items: center; width: 34px; height: 34px; margin: -10px -10px 0 0; padding: 0; border: 1px solid var(--wao-line-control); border-radius: 50%; background: var(--wao-bg-card); color: var(--wao-text-2); font-size: 19px; box-shadow: 0 3px 12px rgba(30, 24, 55, .1); }
@keyframes mining-modal-in { from { opacity: 0; transform: translateY(10px) scale(.985); } }
.note-read-action { position: absolute; right: 13px; bottom: 8px; z-index: 1; padding: 5px 8px; background: var(--wao-bg-card); color: var(--wao-primary-text); border: 1px solid var(--wao-line-control); box-shadow: 0 2px 8px rgba(30,24,55,.08); }
.note-read-action:hover { border-color: var(--wao-primary); }
.note-read-action { opacity: 0; visibility: hidden; transition: opacity .16s, visibility .16s; }
.note-card:hover .note-read-action, .note-card:focus-within .note-read-action { opacity: 1; visibility: visible; }
.mining-toggle { opacity: 0; visibility: hidden; transition: opacity .16s, visibility .16s; }
.notes-list:hover .mining-toggle, .notes-list:focus-within .mining-toggle { opacity: 1; visibility: visible; }
.notes-reader { position: fixed; inset: 0; z-index: 1150; display: grid; grid-template-rows: 52px minmax(0,1fr) 58px; padding: 0 90px; background: radial-gradient(ellipse at 50% 0%, rgba(103,82,177,.1), transparent 52%), var(--wao-bg-page); color: var(--wao-text-2); }
.reader-topbar { display: flex; align-items: center; gap: 14px; max-width: 1100px; width: 100%; margin: auto; }
.reader-topbar > button { width: 34px; height: 34px; display: grid; place-items: center; font-size: 19px; background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); }
.reader-topbar > span:nth-child(2) { color: var(--wao-text-faint); font-size: 12px; white-space: nowrap; }
.reader-progress { flex: 1; height: 3px; overflow: hidden; border-radius: 9px; background: var(--wao-line-control); }
.reader-progress i { display: block; height: 100%; border-radius: inherit; background: var(--wao-primary); transition: width .25s ease; }
.reader-page { width: min(100%, 1100px); height: 100%; min-height: 0; overflow: auto; box-sizing: border-box; margin: 0 auto; padding: clamp(22px, 5vh, 56px) clamp(20px, 6vw, 86px); border: 1px solid var(--wao-line); border-radius: 20px; background: var(--wao-bg-card); box-shadow: 0 24px 80px rgba(37,29,60,.11); animation: reader-page-in .24s ease both; }
.reader-meta { display: flex; align-items: center; gap: 12px; color: var(--wao-text-faint); font-size: 12px; }
.reader-meta span:first-child { color: var(--wao-text-2); font-weight: 650; }
.reader-page h2 { margin: 20px 0 10px; color: var(--wao-text-1); font-size: clamp(24px, 3vw, 36px); line-height: 1.3; }
.reader-tags { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0 22px; }
.reader-tags span { padding: 4px 9px; border-radius: 999px; background: var(--wao-bg-hover); color: var(--wao-primary-text); font-size: 11px; }
.reader-version-toggle { display: inline-flex; gap: 2px; margin: 0 0 18px; padding: 3px; border-radius: 9px; background: var(--wao-bg-sub); }
.reader-version-toggle button { padding: 5px 10px; }
.reader-version-toggle button[aria-pressed="true"] { background: var(--wao-bg-card); color: var(--wao-primary-text); box-shadow: 0 1px 5px rgba(0,0,0,.1); }
.reader-content { display: grid; grid-template-columns: minmax(0, 1fr); gap: 28px; align-items: start; }
.reader-content.has-images { grid-template-columns: minmax(0, 1fr) minmax(190px, 240px); }
.reader-body { min-width: 0; overflow-wrap: anywhere; font-size: 16px; line-height: 1.95; }
.reader-body :deep(img.md-img) { display: none !important; }
.reader-body :deep(pre), .reader-body :deep(table) { max-width: 100%; overflow: auto; }
.reader-image-rail { position: sticky; top: 0; padding-left: 16px; border-left: 1px solid var(--wao-line); }
.reader-image-rail h3 { margin: 0 0 9px; color: var(--wao-text-faint); font-size: 12px; font-weight: 550; }
.reader-image-rail > div { display: grid; gap: 9px; }
.reader-image-rail button { display: block; min-width: 0; padding: 5px; border: 1px solid var(--wao-line-control); background: var(--wao-bg-sub); text-align: left; }
.reader-image-rail img { display: block; width: 100%; max-height: 170px; object-fit: contain; }
.reader-image-rail button span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wao-text-faint); font-size: 11px; }
.reader-nav { position: absolute; top: 50%; z-index: 2; width: 44px; height: 56px; display: grid; place-items: center; border: 1px solid var(--wao-line); background: var(--wao-bg-card); box-shadow: 0 6px 22px rgba(30,24,55,.1); font-size: 28px; transform: translateY(-50%); }
.reader-nav:disabled { opacity: .25; }
.reader-prev { left: 24px; }
.reader-next { right: 24px; }
.reader-controls { display: flex; align-items: center; justify-content: center; gap: 18px; width: min(100%, 1100px); margin: auto; }
.reader-controls button { background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); }
.reader-controls > span { color: var(--wao-text-faint); font-size: 11px; }
@keyframes reader-page-in { from { opacity: .7; transform: translateX(12px) scale(.995); } to { opacity: 1; transform: translateX(0) scale(1); } }
.notes-back-top { position: fixed; right: 28px; bottom: 28px; z-index: 30; display: grid; place-items: center; width: 44px; height: 44px; padding: 0; border: 1px solid color-mix(in srgb, var(--wao-primary) 24%, transparent); border-radius: 50%; background: color-mix(in srgb, var(--wao-bg-card) 88%, transparent); color: var(--wao-primary-text); box-shadow: 0 8px 24px rgba(40, 48, 90, .16); backdrop-filter: blur(10px); font-size: 20px; animation: notes-rocket-in .2s ease-out; }
.notes-back-top:hover { transform: translateY(-2px); box-shadow: 0 12px 28px rgba(40, 48, 90, .22); }
.notes-back-top:focus-visible { outline: 3px solid var(--wao-primary); outline-offset: 3px; }
@keyframes notes-rocket-in { from { opacity: 0; transform: translateY(8px) scale(.86); } to { opacity: 1; transform: translateY(0) scale(1); } }
@media (min-width: 821px) {
  .notes-page { height: 100%; min-height: 0; display: flex; flex-direction: column; overflow: hidden; padding-bottom: 20px; }
  .page-heading { flex: none; }
  .notes-workspace { flex: 1; min-height: 0; align-items: stretch; overflow: hidden; }
  .writing-pane { position: static; min-height: 0; display: flex; flex-direction: column; overflow: auto; overscroll-behavior: contain; }
  .writing-pane :deep(.note-composer:not(.fullscreen)) { flex: 1; min-height: 0; max-height: none; }
  .notes-list { min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
}
@media (max-width: 1100px) { .notes-page { height: auto; display: block; overflow: visible; padding: 20px; } .notes-workspace { display: grid; gap: 18px; grid-template-columns: minmax(0, 1fr); overflow: visible; } .writing-pane { position: static; min-height: 0; overflow: visible; } .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 330px; max-height: none; } .notes-list { overflow: visible; border-left: 0; border-top: 1px solid var(--wao-line); padding: 24px 10px 40px; } .search { flex: 1 1 50%; } }
@media (max-width: 820px) { .notes-workspace { grid-template-columns: minmax(0, 1fr); gap: 24px; } .writing-pane { position: static; } .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 380px; max-height: none; } .writing-pane :deep(.md-body) { min-height: 230px !important; } .notes-list { border-left: 0; border-top: 1px solid var(--wao-line); padding: 24px 10px 40px; } }
@media (max-width: 600px) { .notes-page { padding: 16px 12px 40px; } .note-card:not(.editing) { height: 286px; min-height: 286px; max-height: 286px; padding: 14px 12px 34px; } .reader-toggle { padding: 7px; } .reader-toggle span { display: none; } .mining-modal-mask { align-items: end; padding: 10px; } .mining-modal { width: 100%; max-height: calc(100dvh - 20px); padding: 24px 6px 10px; border-radius: 18px; } .mining-modal-close { margin-right: 4px; } .list-summary { flex-wrap: wrap; } .notes-reader { grid-template-rows: 48px minmax(0,1fr) 54px; padding: 0 10px; } .reader-page { border-radius: 13px; padding: 20px 15px; } .reader-page h2 { margin-top: 14px; } .reader-content.has-images { grid-template-columns: minmax(0,1fr); } .reader-image-rail { position: static; padding: 12px 0 0; border-left: 0; border-top: 1px solid var(--wao-line); } .reader-image-rail > div { grid-template-columns: repeat(3,minmax(0,1fr)); } .reader-image-rail img { max-height: 90px; } .reader-nav { top: auto; bottom: 62px; width: 34px; height: 40px; transform: none; } .reader-prev { left: 13px; } .reader-next { right: 13px; } .reader-controls { gap: 8px; } .reader-controls > span { display: none; } }
@media (max-width: 520px) { .note-content.has-images { grid-template-columns: 1fr; } .note-image-rail { border-left: 0; border-top: 1px solid var(--wao-line); padding: 10px 0 0; } .note-images { grid-template-columns: repeat(3, minmax(0, 1fr)); } .note-image-thumb.cover { grid-column: auto; } .note-image-thumb.cover img { height: 60px; } }
@media (hover: none) { .stage-list-complete, .version-toggle, .card-quick-actions, .note-read-action, .mining-toggle { opacity: 1; visibility: visible; } .note-card:hover { transform: none; } }
@media (prefers-reduced-motion: reduce) { .note-card, .stage-icon, .notes-back-top, .mining-modal { transition: none; animation: none; } .reader-page { animation: none; } .reader-progress i { transition: none; } }
</style>
