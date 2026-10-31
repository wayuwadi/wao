<script setup lang="ts">
import { coalescedDraftWriter } from '@/utils/coalescedDraftWriter'
import DigitalIdentitySettings from '@/components/ui/DigitalIdentitySettings.vue'
import type { IdentitySelection } from '@/api/digitalMembers'
import { computed, defineAsyncComponent, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { apiCreateNoteTag, apiPatchDoc, fetchDoc } from '@/api/board'
import { saveCompanionDraft } from '@/api/noteAiCompanion'
import { legacyProjection, resolvedRichContent } from '@/utils/richDoc'
import { rebaseNoteDraft } from '@/utils/rebaseNoteDraft'
import { latestNoteContent, type NoteDraftSnapshot } from '@/utils/latestNoteContent'
import { readNoteDraft, writeNoteDraft, removeNoteDraft } from '@/utils/noteDraftStorage'
import NoteWidthSwitch from './NoteWidthSwitch.vue'
import NoteImageNavigator from '@/components/notes/NoteImageNavigator.vue'
import NoteReadingImages from './NoteReadingImages.vue'
import { useNoteImageMode } from '@/composables/noteImageMode'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import NoteMediaCopyDialog from './NoteMediaCopyDialog.vue'
import { notePreviewImages, type NotePreviewImage } from '@/utils/notePreviewImages'
import NoteTagContextMenu from './NoteTagContextMenu.vue'
import NoteComposerTags from './NoteComposerTags.vue'
import BookExcerptDialog from './BookExcerptDialog.vue'
import { replaceTagNames, useNoteTagChanges } from '@/utils/noteTagChanges'
import { useNoteWritingWidth } from '@/composables/noteWritingWidth'
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import { useMobileVisualViewport } from '@/composables/mobileVisualViewport'
import { captureCompanionTicket, respondWithCompanion } from '@/composables/quietCompanions'
import type { Doc, FileItem, RichDocContent } from '@/types'
import type { NoteSubmission } from '@/api/dayWorkspace'

const props = withDefaults(defineProps<{ note?: Doc; loading?: boolean; tagOptions?: string[]; inline?: boolean; autofocus?: boolean; focusRequest?: number; expanded?: boolean; initialFullscreen?: boolean; initialPosition?: number; initialViewportTop?: number; workflowMessage?: string; searchTerm?: string; searchRequest?: string; draftScope?: string; submitNote?: (request: NoteSubmission) => Promise<Doc>; compact?: boolean; contained?: boolean; active?: boolean }>(), { active: true })
const emit = defineEmits<{ saved: [id: string]; close: []; 'tag-created': [name: string]; 'draft-change': [draft: NoteDraftSnapshot | null]; 'saving-change': [saving: boolean]; 'fullscreen-change': [fullscreen: boolean]; 'exit-cancelled': []; 'image-reference': [index: number] }>()
const MarkdownEditor = defineAsyncComponent({
  loader: () => import('@/components/ui/MarkdownEditor.vue'),
  loadingComponent: defineComponent({ setup: () => () => h('p', { role: 'status' }, '正在加载编辑器…') }),
})
const noteEditor = ref<{ focusImageReference?: (index: number) => boolean; renameImageAt?: (index: number, event: MouseEvent) => boolean; dismissTransientUi?: () => boolean; removeImageAt: (index: number) => boolean; insertTextAtSelection: (text: string, deleteBefore?: number) => boolean; appendPlainText: (text: string) => boolean; appendBookExcerpt?: (text: string, source: string, reflection: string) => boolean } | null>(null)
const excerptDialog = ref<InstanceType<typeof BookExcerptDialog>>()
const excerptOpen = ref(false)
function insertExcerpt(text: string, source: string, reflection: string) {
  if (saving.value || props.loading) return false
  return noteEditor.value?.appendBookExcerpt?.(text, source, reflection) ?? false
}
const data = useDataStore()
const session = useSessionStore()
const writingWidth = useNoteWritingWidth()
const preferences = usePreferenceStore()
const { fullscreenStyle, isMobile } = useMobileVisualViewport()
const draftKey = props.note
  ? `wao-note-edit-v1:${session.currentUser.id}:${props.note.id}`
  : `wao-quick-note-draft-v1:${session.currentUser.id || 'anonymous'}${props.draftScope ? ':' + props.draftScope : ''}`
type DraftBase = { title: string; tags: string[]; content: RichDocContent }
type Draft = { identitySelection?: IdentitySelection; companionMessageId?: string; sourceInputIds?: string[]; id: string; text: string; content?: RichDocContent; baseRev?: number; baseNote?: DraftBase; tags?: string[]; generatedTitle?: string }
let restored = false
function readDraft(): Draft {
  try {
    const saved = JSON.parse(readNoteDraft(draftKey) || 'null') as Draft | null
    if (saved && /^[A-Za-z0-9_-]{1,32}$/.test(saved.id) && typeof saved.text === 'string') {
      restored = true
      return saved
    }
  } catch { /* 无可恢复的草稿。 */ }
  const content = props.note ? latestNoteContent(props.note) : undefined
  return { id: props.note?.id ?? crypto.randomUUID().replace(/-/g, ''), text: content?.markdown ?? '', content, baseRev: props.note?.rev, tags: props.note?.tags ?? [] }
}
const initial = readDraft()
const identitySelection = ref<IdentitySelection>(initial.identitySelection && ['default','explicit','none'].includes(initial.identitySelection.mode) ? initial.identitySelection : { mode: 'default' })
const companionMessageId = ref(initial.companionMessageId || '')
const savedContent = props.note ? latestNoteContent(props.note) : { version: 1 as const, document: null, markdown: '' }
const savedSnapshot = ref({
  text: props.note ? savedContent.markdown : '',
  document: savedContent.document,
  tags: [...(props.note?.tags ?? [])],
  title: props.note?.title ?? '',
})
const tags = ref<string[]>(initial.tags ?? props.note?.tags ?? [])
const tagCreateOpen = ref(false)
const newTag = ref('')
const creatingTag = ref(false)
const tagError = ref('')
const tagUsageKey = `wao-note-tag-usage-v1:${session.currentUser.id}`
const tagMenu = ref<InstanceType<typeof NoteTagContextMenu>>()
const tagManaging = ref(false)
type TagUsage = Record<string, { count: number; lastUsed: number }>
function readTagUsage(): TagUsage {
  try { return JSON.parse(localStorage.getItem(tagUsageKey) || '{}') as TagUsage } catch { return {} }
}
function recordTagUse(tag: string) {
  const usage = readTagUsage(), previous = usage[tag] ?? { count: 0, lastUsed: 0 }
  usage[tag] = { count: previous.count + 1, lastUsed: Date.now() }
  try { localStorage.setItem(tagUsageKey, JSON.stringify(usage)) } catch { /* 不影响标签选择。 */ }
}
async function createTag() {
  if (creatingTag.value || saving.value || tags.value.length >= 20) return
  const name = newTag.value.trim()
  if (!name || name.length > 32) { tagError.value = '请输入 1–32 个字符的标签名称'; return }
  creatingTag.value = true
  tagError.value = ''
  try {
    if (!data.apiLive) throw new Error('服务尚未连接，请稍后重试')
    const saved = await apiCreateNoteTag(name)
    if (!tags.value.includes(saved)) tags.value = [...tags.value, saved]
    recordTagUse(saved)
    emit('tag-created', saved)
    newTag.value = ''
    tagCreateOpen.value = false
  } catch (cause) { tagError.value = cause instanceof Error ? cause.message : '标签保存失败，请重试' }
  finally { creatingTag.value = false }
}
async function toggleTagCreate() {
  tagCreateOpen.value = !tagCreateOpen.value
  if (tagCreateOpen.value) { await nextTick(); composerElement.value?.querySelector<HTMLInputElement>('.inline-tag-create input')?.focus() }
}
const imageRailStacked = ref(window.innerWidth <= 650 || !!props.compact)
let imageLayoutObserver: ResizeObserver | undefined
onMounted(() => {
  const root = composerElement.value
  if (!root || typeof ResizeObserver === 'undefined') return
  imageLayoutObserver = new ResizeObserver(entries => {
    const width = entries[0]?.contentRect.width
    if (width != null) imageRailStacked.value = width <= 600
  })
  imageLayoutObserver.observe(root)
})
onBeforeUnmount(() => imageLayoutObserver?.disconnect())
const imageMode = useNoteImageMode()
const draftImages = computed(() => notePreviewImages(content.value ?? { version: 1, markdown: text.value }))
const activeImage = ref<number | null>(null)
const imagesPanel = ref<{ locate: (index: number) => void } | null>(null)
const imagePreview = ref<NotePreviewImage | null>(null)
const galleryImages = ref<NotePreviewImage[]>([])
const galleryIndex = ref(0)
function changeGallery(index: number) { galleryIndex.value = index; imagePreview.value = galleryImages.value[index] ?? null }
const imageClose = ref<HTMLButtonElement | null>(null)
let imageTrigger: HTMLElement | null = null
watch(imageMode, () => { activeImage.value = null })
function locateDraftImage(index: number) { activeImage.value = index; if (props.contained) emit('image-reference', index); else imagesPanel.value?.locate(index) }
function returnToDraftImage(index: number) { activeImage.value = index; noteEditor.value?.focusImageReference?.(index) }
function renameDraftImage(index: number, event: MouseEvent) {
  if (saving.value || props.loading) return
  noteEditor.value?.renameImageAt?.(index, event)
}
async function openDraftImage(image: NotePreviewImage, event: MouseEvent, index?: number) {
  imageTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  galleryImages.value = draftImages.value.map(item => ({ ...item }))
  galleryIndex.value = index ?? Math.max(0, galleryImages.value.findIndex(item => item.src === image.src))
  imagePreview.value = image
  await nextTick()
  imageClose.value?.focus({ preventScroll: true })
}
async function closeDraftImage() {
  imagePreview.value = null
  await nextTick()
  if (imageTrigger?.isConnected) imageTrigger.focus({ preventScroll: true })
  imageTrigger = null
}
useOverlayDismiss(() => !!imagePreview.value, closeDraftImage)
function imagePreviewKeys(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = [...(imageClose.value?.closest('[role="dialog"]')?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons[buttons.length - 1]?.focus() }
  else if (!event.shiftKey && document.activeElement === buttons[buttons.length - 1]) { event.preventDefault(); buttons[0]?.focus() }
}

const fullscreen = ref(!!props.initialFullscreen)
watch(fullscreen, value => emit('fullscreen-change', value), { flush: 'sync' })
const fullscreenReminderVisible = ref(false)
const composerElement = ref<HTMLElement>()
let fullscreenReminderTimer: ReturnType<typeof setTimeout> | undefined
let fullscreenReminderDismissed = false

function clearFullscreenReminderTimer() {
  if (fullscreenReminderTimer) clearTimeout(fullscreenReminderTimer)
  fullscreenReminderTimer = undefined
}

function scheduleFullscreenReminder() {
  clearFullscreenReminderTimer()
  const setting = preferences.noteFullscreenReminder
  if (props.active === false || props.inline || !preferences.loaded || preferences.loadError || !setting.enabled
      || !text.value.trim() || !dirty.value || fullscreen.value || fullscreenReminderDismissed) return
  fullscreenReminderTimer = setTimeout(() => {
    fullscreenReminderTimer = undefined
    if (preferences.noteFullscreenReminder.enabled && dirty.value && text.value.trim() && !fullscreen.value) {
      fullscreenReminderVisible.value = true
    }
  }, setting.delayMinutes * 60_000)
}

function enterFullscreen() {
  fullscreenReminderVisible.value = false
  clearFullscreenReminderTimer()
  fullscreen.value = true
}

function remindLater() {
  fullscreenReminderVisible.value = false
  fullscreenReminderDismissed = false
  scheduleFullscreenReminder()
}

function dismissFullscreenReminder() {
  fullscreenReminderVisible.value = false
  fullscreenReminderDismissed = true
  clearFullscreenReminderTimer()
}

let previousOverflow: string | undefined
watch(fullscreen, value => {
  if (value) { previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
  else if (previousOverflow !== undefined) { document.body.style.overflow = previousOverflow; previousOverflow = undefined }
}, { immediate: true })
onBeforeUnmount(() => {
  clearFullscreenReminderTimer()
  if (previousOverflow !== undefined) document.body.style.overflow = previousOverflow
})
function editorKeys(event: KeyboardEvent) {
  if (event.defaultPrevented) return
  if (event.key === 'Escape' && tagSuggestionsOpen.value) { event.preventDefault(); tagSuggestionsOpen.value = false; return }
  if (event.key === 'Escape' && tagCreateOpen.value) { event.preventDefault(); event.stopPropagation(); tagCreateOpen.value = false; return }
  if (event.key === 'Escape' && noteEditor.value?.dismissTransientUi?.()) { event.preventDefault(); event.stopPropagation(); return }
  if (event.key === 'Escape' && (props.note || fullscreen.value)) {
    event.preventDefault(); event.stopPropagation()
    if (!saving.value && !creatingTag.value && !tagManaging.value) void requestExit()
    return
  }
  if (!fullscreen.value) return
  if (event.key !== 'Tab' || event.defaultPrevented) return
  const controls = Array.from(composerElement.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), [contenteditable="true"], textarea, summary, [tabindex="0"]') ?? []).filter(el => el.getClientRects().length)
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch(() => props.active, active => {
  if (active !== false) return
  fullscreen.value = false
  tagSuggestionsOpen.value = false
  fullscreenReminderVisible.value = false
  clearFullscreenReminderTimer()
})
function toggleTag(tag: string) {
  if (saving.value || creatingTag.value || tagManaging.value) return
  if (tags.value.includes(tag)) tags.value = tags.value.filter(item => item !== tag)
  else if (tags.value.length < 20) { tags.value = [...tags.value, tag]; recordTagUse(tag) }
}
const choices = computed(() => {
  const usage = readTagUsage()
  return [...new Set([...(props.tagOptions ?? []), ...tags.value])].sort((a, b) =>
    (usage[b]?.count ?? 0) - (usage[a]?.count ?? 0)
    || (usage[b]?.lastUsed ?? 0) - (usage[a]?.lastUsed ?? 0)
    || a.localeCompare(b, 'zh-CN'))
})
const quickChoices = computed(() => [...new Set([...choices.value.slice(0, 6), ...tags.value])])
const tagSuggestionsOpen = ref(false)
const tagSuggestionStyle = ref<Record<string, string>>({})
function onComposerEditorKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && tagSuggestionsOpen.value) { event.preventDefault(); tagSuggestionsOpen.value = false; return }
  if (event.key !== '#') return
  window.setTimeout(() => {
    const selection = window.getSelection()
    const range = selection?.rangeCount ? selection.getRangeAt(0) : null
    const rect = range && typeof range.getBoundingClientRect === 'function' ? range.getBoundingClientRect() : null
    const left = Math.max(12, Math.min((rect?.left ?? 20), window.innerWidth - 260))
    const top = Math.min((rect?.bottom ?? 80) + 6, window.innerHeight - 220)
    tagSuggestionStyle.value = { left: `${left}px`, top: `${top}px` }
    tagSuggestionsOpen.value = true
  }, 0)
}
function insertSuggestedTag(tag: string) {
  noteEditor.value?.insertTextAtSelection?.(`#${tag} `, 1)
  toggleTag(tag)
  tagSuggestionsOpen.value = false
}
const appliedAiInputs = ref<string[]>(initial.sourceInputIds ?? [])
const id = ref(initial.id)
const fullscreenReminderId = computed(() => `note-fullscreen-guide-${id.value}`)
const taskSource = computed(() => ({
  id: props.note?.id ?? id.value,
  title: props.note?.title || '未保存小记',
  url: props.note ? `/kb/${encodeURIComponent(props.note.kbId ?? '')}?doc=${encodeURIComponent(props.note.id)}` : undefined,
  docId: props.note?.id,
}))
const text = ref(initial.text)
const characterCount = ref(0)
const content = ref<RichDocContent | undefined>(initial.content)
watch(() => JSON.stringify(draftImages.value), () => { activeImage.value = null })
const generatedTitle = ref(initial.generatedTitle || props.note?.title || '')
// 保留开始编辑时的快照，后台元信息可合并，本次输入优先。
let baseRev = initial.baseRev ?? props.note?.rev ?? 1
const restoredBase = initial.baseNote
const validRestoredBase = restoredBase && typeof restoredBase.title === 'string'
  && Array.isArray(restoredBase.tags) && restoredBase.tags.every(tag => typeof tag === 'string')
  && restoredBase.content?.version === 1 && typeof restoredBase.content.markdown === 'string'
let baseNote = props.note && (!restored || initial.baseRev != null && initial.baseRev === props.note.rev) ? {
  title: props.note.title,
  tags: [...(props.note.tags ?? [])],
  blocks: props.note.blocks,
  content: savedContent,
} : restored && initial.baseRev != null && validRestoredBase ? {
  ...restoredBase, blocks: legacyProjection(restoredBase.content),
} : null
const copyDraftContent = ref<RichDocContent | null>(null)
function copyDraft() {
  copyDraftContent.value = JSON.parse(JSON.stringify(content.value?.markdown === text.value
    ? content.value : { version: 1, document: null, markdown: text.value })) as RichDocContent
}
const saving = ref(false)
watch(saving, value => emit('saving-change', value), { flush: 'sync' })
const error = ref('')
const draftError = ref(false)
const status = ref(restored ? '已恢复草稿，可继续记录' : '')
let resetting = false
const dirty = computed(() => text.value !== savedSnapshot.value.text
  || JSON.stringify(content.value?.document ?? null) !== JSON.stringify(savedSnapshot.value.document ?? null)
  || JSON.stringify(tags.value) !== JSON.stringify(savedSnapshot.value.tags)
  || generatedTitle.value !== savedSnapshot.value.title)
useNoteTagChanges(change => {
  // 标签管理只改元信息，不能重置用户尚未保存的正文或标题。
  savedSnapshot.value.tags = replaceTagNames(savedSnapshot.value.tags, change)
  if (baseNote) baseNote.tags = replaceTagNames(baseNote.tags ?? [], change)
  tags.value = replaceTagNames(tags.value, change)
  const usage = readTagUsage()
  if (usage[change.source]) {
    if (change.target) {
      const previous = usage[change.target]
      usage[change.target] = { count: (previous?.count ?? 0) + usage[change.source]!.count,
        lastUsed: Math.max(previous?.lastUsed ?? 0, usage[change.source]!.lastUsed) }
    }
    delete usage[change.source]
    try { localStorage.setItem(tagUsageKey, JSON.stringify(usage)) } catch { /* 不阻断输入。 */ }
  }
})
const canSave = computed(() => !!text.value.trim() && !saving.value && !creatingTag.value && !tagManaging.value && !props.loading)
const exitOpen = ref(false)
const exitSaveButton = ref<HTMLButtonElement>()
const exitDiscardButton = ref<HTMLButtonElement>()
let exitReturnFocus: HTMLElement | null = null
async function requestExit() {
  if (exitOpen.value) return
  exitReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  exitOpen.value = true
  await nextTick()
  exitSaveButton.value?.focus()
}
function cancelExit() {
  if (saving.value) return
  exitOpen.value = false
  emit('exit-cancelled')
  void nextTick(() => exitReturnFocus?.focus())
}
function exitKeys(event: KeyboardEvent) {
  event.stopPropagation()
  if (event.key === 'Escape') { event.preventDefault(); cancelExit() }
  if (event.key === 'Tab') {
    event.preventDefault()
    const next = document.activeElement === exitSaveButton.value ? exitDiscardButton.value : exitSaveButton.value
    next?.focus()
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    if (document.activeElement === exitDiscardButton.value) void discardAndExit()
    else void saveAndExit()
  }
}
async function saveAndExit() {
  if (saving.value) return
  if (!canSave.value) { error.value = '正文为空或尚在加载，请完成内容后再保存'; return }
  await save(true)
  if (!error.value && !dirty.value) exitOpen.value = false
}
async function discardAndExit() {
  if (saving.value) return
  try { removeNoteDraft(draftKey) }
  catch { error.value = '清除未保存草稿失败，请重试'; return }
  resetting = true
  const persisted = props.note ? data.docById(props.note.id) ?? props.note : undefined
  const rich = persisted ? latestNoteContent(persisted) : undefined
  text.value = rich?.markdown ?? ''
  content.value = rich
  tags.value = [...(persisted?.tags ?? [])]
  generatedTitle.value = persisted?.title ?? ''
  appliedAiInputs.value = []
  companionMessageId.value = ''
  if (!persisted) id.value = crypto.randomUUID().replace(/-/g, '')
  savedSnapshot.value = { text: text.value, document: rich?.document ?? null, tags: [...tags.value], title: generatedTitle.value }
  resetting = false
  error.value = ''
  emit('draft-change', null)
  exitOpen.value = false
  fullscreen.value = false
  if (props.note) emit('close')
}
watch(
  [dirty, text, content, tags, generatedTitle, fullscreen, () => preferences.loaded,
    () => preferences.loadError, () => preferences.noteFullscreenReminder.enabled,
    () => preferences.noteFullscreenReminder.delayMinutes],
  () => {
    fullscreenReminderVisible.value = false
    fullscreenReminderDismissed = false
    scheduleFullscreenReminder()
  },
  { flush: 'post' },
)
function persistCurrentDraft() {
  if (resetting) return
  error.value = ''
  if (props.note && !dirty.value) {
    try { removeNoteDraft(draftKey) } catch { /* 服务器中已有相同内容。 */ }
    status.value = '已保存，可继续编辑'
    draftError.value = false
    emit('draft-change', null)
    return
  }
  try {
    writeNoteDraft(draftKey, JSON.stringify({ identitySelection: identitySelection.value, id: id.value, text: text.value, content: content.value, baseRev,
      baseNote: baseNote ? { title: baseNote.title, tags: baseNote.tags, content: baseNote.content } : undefined,
      tags: tags.value, generatedTitle: generatedTitle.value, sourceInputIds: appliedAiInputs.value, companionMessageId: companionMessageId.value } satisfies Draft))
    draftError.value = false
    status.value = '草稿已保存在此浏览器'
  } catch {
    draftError.value = true
    status.value = '浏览器草稿保存失败，请保持页面打开并重试保存'
  }
  if (props.note) emit('draft-change', dirty.value ? {
    content: content.value?.markdown === text.value ? content.value : { version: 1, document: null, markdown: text.value },
    title: baseNote?.title === generatedTitle.value ? undefined : generatedTitle.value,
    tags: JSON.stringify(baseNote?.tags) === JSON.stringify(tags.value) ? undefined : [...tags.value],
  } : null)
}
const draftWriter = coalescedDraftWriter(persistCurrentDraft)
watch([text, content, tags, generatedTitle, appliedAiInputs, companionMessageId, identitySelection], () => {
  if (!resetting) draftWriter.schedule()
}, { flush: 'sync' })
onBeforeUnmount(() => draftWriter.flush())
function flushDraftWhenHidden() { if (document.hidden) draftWriter.flush() }
onMounted(() => { window.addEventListener('pagehide', draftWriter.flush); document.addEventListener('visibilitychange', flushDraftWhenHidden) })
onBeforeUnmount(() => { window.removeEventListener('pagehide', draftWriter.flush); document.removeEventListener('visibilitychange', flushDraftWhenHidden) })

watch(() => props.note ? { note: props.note, rich: latestNoteContent(props.note) } : null, latest => {
  if (!latest || dirty.value || saving.value) return
  resetting = true
  text.value = latest.rich.markdown
  content.value = latest.rich
  tags.value = [...(latest.note.tags ?? [])]
  generatedTitle.value = latest.note.title
  savedSnapshot.value = { text: text.value, document: latest.rich.document, tags: [...tags.value], title: generatedTitle.value }
  baseRev = latest.note.rev ?? baseRev
  baseNote = { ...latest.note, tags: [...(latest.note.tags ?? [])], content: latest.rich }
  resetting = false
}, { flush: 'sync' })

async function save(explicit = false) {
  draftWriter.flush()
  if (!canSave.value) return
  if (!props.note && !companionMessageId.value && identitySelection.value.mode === 'explicit' && (!identitySelection.value.subjectId || !identitySelection.value.versionId)) { error.value = '请选择成员与已确认的形象版本'; return }
  const responseTicket = explicit ? captureCompanionTicket(composerElement.value?.querySelector('.save-note')) : undefined
  saving.value = true
  error.value = ''
  try {
    // 初始化未完成或服务不可达时，不能落成内存小记并提示成功。
    if (!data.apiLive) throw new Error('服务尚未连接，请稍后重试；输入内容已保留')
    // 模型文字已变化而结构化回调尚未到达时，以当前可见输入为准。
    const rich = content.value?.markdown === text.value
      ? content.value : { version: 1 as const, document: null, markdown: text.value }
    const submitted = { identitySelection: { ...identitySelection.value }, text: text.value, content: content.value, tags: [...tags.value], title: generatedTitle.value, sourceInputIds: [...appliedAiInputs.value], companionMessageId: companionMessageId.value }
    let savedId: string | null
    if (props.submitNote && !submitted.companionMessageId) {
      const aiDefaults = preferences.noteAiDefaults
      const saved = await props.submitNote({ docId: id.value, title: submitted.title, content: rich, tags: submitted.tags,
        ...(submitted.sourceInputIds.length ? { sourceInputIds: submitted.sourceInputIds } : {}),
        autoTitle: aiDefaults.title && !submitted.title.trim(), autoImage: aiDefaults.image, autoPolish: aiDefaults.polish, ...(submitted.identitySelection.mode === 'default' ? {} : { identitySelection: submitted.identitySelection }) })
      data.applyRemoteDoc(saved)
      savedId = saved.id
    } else if (props.note) {
      if (data.docPending(props.note.id)) throw new Error('这篇小记在文档页还有未保存编辑，请先完成保存')
      let saved: Doc | undefined
      for (let attempt = 0; attempt < 3; attempt++) {
        const latest = await fetchDoc(props.note.id)
        const latestRev = latest.rev ?? 1
        const merged = rebaseNoteDraft(baseNote, { ...latest, content: latestNoteContent(latest) },
          { title: submitted.title, tags: submitted.tags, content: rich })
        try {
          saved = submitted.companionMessageId ? await saveCompanionDraft({ messageId: submitted.companionMessageId, docId: props.note.id, existing: true, expectedRev: latestRev, title: (merged.title || latest.title || '未命名小记').slice(0,80), content: merged.content, blocks: legacyProjection(merged.content), tags: merged.tags }) : await apiPatchDoc(props.note.id, {
            title: merged.title.slice(0, 80),
            sourceInputIds: submitted.sourceInputIds, content: merged.content, blocks: legacyProjection(merged.content), expectedRev: latestRev, tags: merged.tags,
          })
          break
        } catch (cause) {
          if (!(cause instanceof Error) || !/其他窗口更新|版本冲突/.test(cause.message)) throw cause
          if (attempt === 2) throw new Error('小记仍在更新，最新输入已保留，请稍后重试保存')
        }
      }
      if (!saved) throw new Error('保存未完成，请重试')
      data.applyRemoteDoc(saved)
      baseRev = saved.rev ?? baseRev
      baseNote = { title: saved.title, tags: [...(saved.tags ?? [])], blocks: saved.blocks, content: resolvedRichContent(saved) }
      savedId = saved.id
    } else {
      if(submitted.companionMessageId){const saved=await saveCompanionDraft({ messageId: submitted.companionMessageId, docId:id.value, existing:false, expectedRev:1, title:submitted.title, content:rich, blocks:legacyProjection(rich), tags:submitted.tags });data.applyRemoteDoc(saved);savedId=saved.id}
      else savedId = await data.addNote(submitted.text, submitted.title, id.value, rich, submitted.tags, submitted.sourceInputIds.length || submitted.identitySelection.mode !== 'default' ? { ...(submitted.sourceInputIds.length ? { sourceInputIds: submitted.sourceInputIds } : {}), ...(submitted.identitySelection.mode === 'default' ? {} : { identitySelection: submitted.identitySelection }) } : undefined)

    }
    if (!savedId) throw new Error('正文为空，请先写点内容')
    appliedAiInputs.value = appliedAiInputs.value.filter(request => !submitted.sourceInputIds.includes(request))
    try {
      const raw = readNoteDraft(draftKey)
      const latest = JSON.parse(raw || 'null') as Draft | null
      // 页面切走又回来时，新组件可能已经继续输入，旧请求不能清掉较新的草稿。
      if (!latest || (latest.id === id.value && latest.text === submitted.text
        && JSON.stringify(latest.content) === JSON.stringify(submitted.content)
        && JSON.stringify(latest.tags ?? []) === JSON.stringify(submitted.tags)
        && (latest.generatedTitle ?? '') === submitted.title && JSON.stringify(latest.identitySelection ?? { mode: 'default' }) === JSON.stringify(submitted.identitySelection))) removeNoteDraft(draftKey, raw)
    } catch { /* 服务端保存已成功。 */ }
    if (text.value !== submitted.text || JSON.stringify(tags.value) !== JSON.stringify(submitted.tags)
        || generatedTitle.value !== submitted.title || JSON.stringify(identitySelection.value) !== JSON.stringify(submitted.identitySelection)) {
      status.value = '已保存提交内容 · 最新输入已保留，待保存'
      return
    }
    draftWriter.cancel()
    if (!props.note) {
      resetting = true
      companionMessageId.value = ''
      identitySelection.value = { mode: 'default' }
      text.value = ''
      content.value = undefined
      tags.value = []
      appliedAiInputs.value = []
      generatedTitle.value = ''
      id.value = crypto.randomUUID().replace(/-/g, '')
      resetting = false
      savedSnapshot.value = { text: '', document: null, tags: [], title: '' }
    } else {
      const saved = data.docById(savedId) ?? props.note
      const savedRich = resolvedRichContent(saved)
      resetting = true
      companionMessageId.value = ''
      text.value = savedRich.markdown
      content.value = savedRich
      tags.value = [...(saved.tags ?? [])]
      generatedTitle.value = saved.title
      resetting = false
      savedSnapshot.value = {
        text: savedRich.markdown,
        document: savedRich.document,
        tags: [...tags.value],
        title: saved.title,
      }
    }
    status.value = '已保存到服务器，清单已更新'
    if (props.note) emit('draft-change', null)
    fullscreenReminderVisible.value = false
    clearFullscreenReminderTimer()
    fullscreen.value = false
    respondWithCompanion('note', responseTicket)
    emit('saved', savedId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

function appendPlainText(value: string, messageId?: string) {
  if (saving.value || props.loading) return false
  const inserted=noteEditor.value?.appendPlainText(value) ?? false
  if(inserted && messageId)companionMessageId.value = messageId
  return inserted
}
function focusImageReference(index: number) { return noteEditor.value?.focusImageReference?.(index) ?? false }
defineExpose({ save, dirty, appendPlainText, requestExit, focusImageReference })
useSaveBeforeRefresh(async () => {
  if (saving.value) throw new Error('小记正在保存，请完成后再刷新')
  if (!dirty.value) return
  await save()
  if (dirty.value || error.value) throw new Error(error.value || '小记尚未保存，输入已保留，请先完成保存')
})

async function uploadImage(file: File, done: (url: string | null, name: string) => void) {
  try {
    if (!data.apiLive) throw new Error('服务尚未连接，请稍后重试上传')
    if (file.size > data.MAX_IMAGE_BYTES) throw new Error('图片超过 10MB，请选择较小的图片')
    const image = await data.uploadImage(file, '小记', { requirePersisted: true })
    if (!image) throw new Error('图片上传失败，请重试')
    done(image.url, image.name)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片上传失败，请重试'
    done(null, file.name)
  }
}

async function uploadFile(file: File, done: (item: FileItem | null) => void) {
  try {
    if (!data.apiLive) throw new Error('服务尚未连接，请稍后重试上传')
    if (file.size > data.MAX_FILE_BYTES) throw new Error('文件超过 200MB，请选择较小的文件')
    const item = await data.uploadFile(file, undefined, true)
    if (!item) throw new Error('文件上传失败，请重试')
    done(item)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '文件上传失败，请重试'
    done(null)
  }
}
</script>

<template>
  <section ref="composerElement" class="note-composer" :style="fullscreen ? fullscreenStyle : undefined" :class="{ fullscreen, inline, expanded, contained, 'writing-narrow': fullscreen && writingWidth === 'narrow', 'reminder-active': fullscreenReminderVisible && !fullscreen, 'has-image-rail': !contained && imageMode === 'references' && !!draftImages.length, 'images-stacked': imageRailStacked }" :inert="imagePreview || copyDraftContent || excerptOpen ? true : undefined" @keydown.capture="editorKeys" :aria-label="note ? '编辑小记' : '记录小记'">
    <div v-if="!inline" class="composer-heading"><i class="ri-quill-pen-line"></i><input v-model="generatedTitle" class="composer-title-input" aria-label="小记标题（可选）" :placeholder="note ? '小记标题' : '此刻，想记点什么？'" maxlength="80" :disabled="saving" /><NoteWidthSwitch v-if="fullscreen" v-model="writingWidth" /><button v-if="!contained" class="fullscreen-toggle" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏编辑'" :title="fullscreen ? '退出全屏（Esc）' : '全屏编辑'" :aria-pressed="fullscreen" :aria-describedby="fullscreenReminderVisible && !fullscreen ? fullscreenReminderId : undefined" @click="fullscreen = !fullscreen"><i :class="fullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i></button></div>
    <div v-if="fullscreenReminderVisible && !fullscreen" :id="fullscreenReminderId" class="fullscreen-reminder" role="status" aria-live="polite">
      <i class="ri-focus-3-line" aria-hidden="true"></i>
      <div class="fullscreen-reminder-copy"><strong>已经写了一会儿，来全屏专心创作？</strong><span>全屏后正文和图片更舒展，当前内容与草稿会保留。</span></div>
      <div class="fullscreen-reminder-actions"><button type="button" class="reminder-enter" @click="enterFullscreen">进入全屏</button><button type="button" @click="remindLater">稍后提醒</button><button type="button" aria-label="关闭全屏提醒" title="关闭提醒" @click="dismissFullscreenReminder"><i class="ri-close-line" aria-hidden="true"></i></button></div>
    </div>
    <div v-if="inline && note" class="inline-heading">
      <input v-model="generatedTitle" type="text" maxlength="80" aria-label="小记标题" :disabled="saving">
      <button class="add-tag" type="button" :disabled="saving || creatingTag" :aria-label="tagCreateOpen ? '收起新增标签' : '新增标签'" :title="tagCreateOpen ? '收起新增标签' : '新增标签'" @click="toggleTagCreate"><i :class="tagCreateOpen ? 'ri-close-line' : 'ri-add-line'"></i></button>
      <button v-if="!contained" class="fullscreen-toggle" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏编辑'" :title="fullscreen ? '退出全屏（Esc）' : '全屏编辑'" :aria-pressed="fullscreen" @click="fullscreen = !fullscreen"><i :class="fullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i></button>
      <slot name="heading-actions" />
      <NoteWidthSwitch v-if="fullscreen" v-model="writingWidth" />
    </div>
    <div class="editor-space" @keydown.capture.exact="onComposerEditorKeydown" @keydown.capture.shift.exact="onComposerEditorKeydown" @keydown.ctrl.enter.prevent="save(true)" @keydown.meta.enter.prevent="save(true)">
      <NoteTagContextMenu ref="tagMenu" :choices="choices" :disabled="saving || creatingTag" @saving-change="tagManaging = $event" />
      <div class="composer-editor-layout"><div class="composer-editor-main">
      <MarkdownEditor :search-term="searchTerm" :search-request="searchRequest" :key="id" ref="noteEditor" v-model="text" :document="content?.document" bare advanced allow-h1 polishable link-controls image-controls image-context-controls selection-image preview-images note-media-copy
        :image-references="contained || imageMode === 'references'" :active-image-reference="activeImage" @image-reference="locateDraftImage"
        :article-image-key="`note:${session.currentUser.id}:${id}`"
        :article-image-generation="false" :article-image-upload="false"
        :note-ai-origin="{ stableId: id, docId: note?.id, sourceRev: baseRev, title: generatedTitle, blocks: note?.blocks ?? [], links: note?.links ?? [] }" note-title-on-image @ai-applied="appliedAiInputs = [...new Set([...appliedAiInputs, $event])]" @generated-title="generatedTitle = $event"
        :hide-character-count="inline" @character-count="characterCount = $event" hide-toolbar :autofocus="autofocus" :focus-request="loading ? 0 : focusRequest" :initial-position="initialPosition" :initial-viewport-top="initialViewportTop" :menu-z-index="fullscreen || contained ? 2200 : compact ? 1200 : 1000"
        :task-source="taskSource" @task-opened="fullscreen = false"
        :readonly="saving" :min-height="compact ? 200 : note ? 320 : 360" :placeholder="focusRequest ? '先写一句，就很好。想法、点子、提示词、想建设的功能……' : '想法、灵感、工作备忘…随手记在这里，也可以粘贴图片'"
        @content-change="content = $event" @upload-image="uploadImage" @upload-file="uploadFile">

      </MarkdownEditor>
      </div><NoteReadingImages v-if="!contained && imageMode === 'references' && draftImages.length" ref="imagesPanel" class="composer-images" :images="draftImages" :active="activeImage" :editable="!saving && !loading" @rename="renameDraftImage" @return="returnToDraftImage" @fullscreen="openDraftImage" /></div>
      <NoteComposerTags v-if="!inline" add-button v-model="newTag" :choices="quickChoices" :selected="tags" :saving="saving" :creating="creatingTag" :create-open="tagCreateOpen" :error="tagError" @toggle="toggleTag" @create="createTag" @toggle-create="toggleTagCreate" @close-create="tagCreateOpen = false" @menu="(event, tag) => tagMenu?.open(event, tag)" />
    </div>
    <Teleport to="body"><div v-if="imagePreview" class="composer-image-lightbox" role="dialog" aria-modal="true" :aria-label="imagePreview.alt || '小记图片预览'" @click.self="closeDraftImage" @keydown="imagePreviewKeys"><button ref="imageClose" type="button" aria-label="关闭图片预览" @click="closeDraftImage"><i class="ri-close-line" aria-hidden="true"></i></button><img :src="imagePreview.src" :alt="imagePreview.alt" /><NoteImageNavigator :images="galleryImages" :index="galleryIndex" @change="changeGallery" /></div></Teleport>
    <p v-if="error" class="error" role="alert">{{ error }}。正文仍在，可原地重试。</p>
    <Teleport to="body">
      <div v-if="exitOpen" class="note-exit-mask" @keydown="exitKeys">
        <section class="note-exit-dialog" role="dialog" aria-modal="true" aria-label="保存修改并退出？">
          <i class="ri-save-3-line" aria-hidden="true"></i><h3>保存修改并退出？</h3>
          <p>保存后返回刚才的预览状态。</p>
          <p v-if="error" class="error" role="alert">{{ error }}。输入已保留，请重试。</p>
          <div><button ref="exitDiscardButton" type="button" :disabled="saving" @click="discardAndExit">不保存，退出</button><button ref="exitSaveButton" type="button" class="exit-save" :disabled="saving" @click="saveAndExit">{{ saving ? '正在保存…' : '保存并退出 ↵' }}</button></div>
        </section>
      </div>
    </Teleport>
    <NoteMediaCopyDialog v-if="copyDraftContent" :content="copyDraftContent" :title="generatedTitle" @close="copyDraftContent = null" />
    <div class="composer-footer">
      <NoteComposerTags v-if="inline" v-model="newTag" :choices="quickChoices" :selected="tags" :saving="saving" :creating="creatingTag" :create-open="tagCreateOpen" :error="tagError" @toggle="toggleTag" @create="createTag" @toggle-create="toggleTagCreate" @close-create="tagCreateOpen = false" @menu="(event, tag) => tagMenu?.open(event, tag)" />
      <span role="status" aria-live="polite" :class="{ error: draftError }">{{ loading ? '正在加载小记…' : saving ? '正在保存到服务器…' : draftError ? status : dirty ? '草稿已保护 · 有修改待保存' : workflowMessage || status || (note ? '已保存，可继续编辑' : isMobile ? '随手写，点“记下来了”保存' : 'Ctrl / ⌘ + Enter 保存') }}</span>
      <span v-if="inline" class="composer-word-count" aria-label="当前正文字数" title="正文字符数（含标点，不含空白、格式标记及图片附件地址）">{{ characterCount.toLocaleString('zh-CN') }} 字</span>
      <button v-if="note" type="button" :disabled="saving" @click="contained ? requestExit() : emit('close')">{{ contained ? '退出编辑' : '收起编辑' }}</button>
      <span v-if="!note && companionMessageId" class="derived-image-hint">对话草稿不自动配图，保存后可手动生成。</span>
      <DigitalIdentitySettings v-if="!note && !companionMessageId" v-model="identitySelection" execution="save" :disabled="loading || saving" />
      <button type="button" class="import-excerpt" aria-label="粘贴为书摘" title="粘贴为书摘" :disabled="loading || saving" @click="excerptDialog?.openDialog()"><i class="ri-double-quotes-l" aria-hidden="true"></i><span>书摘</span></button>
      <button v-if="!note" type="button" class="copy-draft" aria-label="复制给 Codex" title="复制给 Codex" :disabled="loading || saving || !text.trim()" @click="copyDraft"><i class="ri-file-copy-2-line" aria-hidden="true"></i><span> 复制给 Codex</span></button>
      <button class="save-note" type="button" :disabled="!canSave" @click="save(true)">{{ saving ? '正在保存…' : error ? '重试保存' : note ? '保存修改' : '记下来了' }}</button>
    </div>
    <BookExcerptDialog ref="excerptDialog" :draft-key="`${draftKey}:excerpt`" :insert-excerpt="insertExcerpt" @open-change="excerptOpen = $event" />
    <Teleport to="body"><div v-if="tagSuggestionsOpen" class="tag-suggestion-menu" role="listbox" aria-label="最近常用标签" :style="tagSuggestionStyle"><div>最近常用</div><button v-for="tag in choices.slice(0, 8)" :key="tag" type="button" role="option" :aria-selected="tags.includes(tag)" :disabled="saving || (!tags.includes(tag) && tags.length >= 20)" @mousedown.prevent @click="insertSuggestedTag(tag)"># {{ tag }}<span>{{ tags.includes(tag) ? '已选' : '' }}</span></button><small>按使用频次排序 · Esc 收起</small></div></Teleport>
  </section>
</template>

<style scoped>
.composer-footer :deep(.identity-trigger) { min-width:36px; height:36px; }
@media(max-width:650px) { .composer-footer :deep(.identity-trigger) { min-width:44px; height:44px; } }
.import-excerpt { display:inline-flex; align-items:center; justify-content:center; gap:5px; }
@media(max-width:760px) {
  .composer-footer > .import-excerpt, .composer-footer > .copy-draft { min-width:44px; padding:8px; }
  .composer-footer > .copy-draft span { display:none; }
  .composer-footer > span[role="status"] { flex:1 1 100%; }
  .composer-footer > .save-note { margin-left:auto; }
}
.note-exit-mask { position:fixed; inset:0; z-index:2500; display:grid; place-items:center; background:rgb(24 28 46 / .28); padding:20px; }
.note-exit-dialog { width:min(380px,100%); box-sizing:border-box; padding:26px; border-radius:16px; background:var(--wao-bg-card); box-shadow:0 20px 70px rgb(20 24 45 / .18); }
.note-exit-dialog > i { color:var(--wao-primary); font-size:22px; }
.note-exit-dialog h3 { margin:14px 0 8px; color:var(--wao-text-1); font-size:18px; }
.note-exit-dialog p { color:var(--wao-text-2); font-size:13px; line-height:1.7; }
.note-exit-dialog > div { display:flex; justify-content:flex-end; gap:10px; margin-top:24px; }
.note-exit-dialog button { padding:9px 14px; border:1px solid var(--wao-line-control); border-radius:8px; background:var(--wao-bg-card); color:var(--wao-text-1); cursor:pointer; }
.note-exit-dialog .exit-save { background:var(--wao-primary); border-color:var(--wao-primary); color:white; }
.note-exit-dialog button:focus-visible { outline:2px solid var(--wao-primary); outline-offset:3px; }
.note-exit-dialog button:disabled { opacity:.55; cursor:wait; }
.note-composer { position: relative; display: flex; flex-direction: column; min-width: 0; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 14px; padding: 18px; background: var(--wao-bg-card); }
.note-composer.inline:not(.fullscreen) { padding: 0; border: 0; border-radius: 0; }
.inline:not(.fullscreen) .editor-space { min-height: 200px; max-height: 460px; }
.inline.expanded:not(.fullscreen) .editor-space { max-height: none; }
.inline.expanded:not(.fullscreen) .editor-space :deep(.md-body) { overflow: visible; flex-basis: auto; }
.inline .composer-heading { flex-wrap: wrap; }
.note-composer:focus-within { border-color: var(--wao-primary); }
.note-composer:not(:focus-within) :deep(.article-footer) { max-height: 0; padding-top: 0; overflow: hidden; opacity: 0; visibility: hidden; pointer-events: none; }
:deep(.article-footer) { max-height: 72px; transition: max-height .18s ease, padding .18s ease, opacity .16s ease, visibility .16s; }
.composer-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.fullscreen-reminder { position:absolute; z-index:10; top:55px; right:12px; display:grid; grid-template-columns:28px minmax(0,1fr); gap:8px 10px; width:min(420px, calc(100% - 24px)); box-sizing:border-box; margin:0; padding:13px 14px; border:1px solid color-mix(in srgb, var(--wao-primary) 24%, var(--wao-line-control)); border-radius:13px; background:var(--wao-bg-card); color:var(--wao-text-2); box-shadow:0 14px 34px color-mix(in srgb, var(--wao-text-1) 14%, transparent); transform-origin:top right; animation:fullscreen-guide-enter .28s cubic-bezier(.2,.8,.2,1) both; }
.fullscreen-reminder::before { content:""; position:absolute; top:-6px; right:17px; width:10px; height:10px; border-top:1px solid color-mix(in srgb, var(--wao-primary) 24%, var(--wao-line-control)); border-left:1px solid color-mix(in srgb, var(--wao-primary) 24%, var(--wao-line-control)); background:var(--wao-bg-card); transform:rotate(45deg); }
.fullscreen-reminder > i { display:grid; width:28px; height:28px; place-items:center; border-radius:50%; background:color-mix(in srgb, var(--wao-primary) 12%, var(--wao-bg-card)); color:var(--wao-primary-text); font-size:17px; animation:fullscreen-guide-spark 2.4s ease-in-out 2; }
.fullscreen-reminder-copy { display:flex; min-width:0; flex-direction:column; gap:3px; }
.fullscreen-reminder-copy strong { color:var(--wao-text-1); font-size:var(--wao-font-body); font-weight:600; }
.fullscreen-reminder-copy span { color:var(--wao-text-3); font-size:var(--wao-font-body); line-height:1.45; }
.fullscreen-reminder-actions { grid-column:2; display:flex; align-items:center; flex-wrap:wrap; gap:5px; }
.fullscreen-reminder-actions button { padding:6px 8px; border:0; background:transparent; color:var(--wao-text-3); font-size:var(--wao-font-body); white-space:nowrap; }
.fullscreen-reminder-actions .reminder-enter { border-radius:7px; background:var(--wao-primary); color:white; font-weight:600; }
.fullscreen-reminder-actions button:hover { background:var(--wao-bg-hover); color:var(--wao-primary-text); }
.fullscreen-reminder-actions .reminder-enter:hover { background:var(--wao-primary-hover); color:white; }
.composer-heading .fullscreen-toggle { position:relative; }
.reminder-active .composer-heading .fullscreen-toggle { z-index:11; color:var(--wao-primary-text); background:color-mix(in srgb, var(--wao-primary) 9%, var(--wao-bg-card)); }
.reminder-active .composer-heading .fullscreen-toggle::after { content:""; position:absolute; inset:-4px; border:1px solid color-mix(in srgb, var(--wao-primary) 62%, transparent); border-radius:10px; pointer-events:none; animation:fullscreen-guide-pulse 1.35s ease-out 3; }
@keyframes fullscreen-guide-enter { from { opacity:0; transform:translateY(-5px) scale(.985); } to { opacity:1; transform:translateY(0) scale(1); } }
@keyframes fullscreen-guide-spark { 0%,100% { transform:rotate(0) scale(1); } 35% { transform:rotate(-12deg) scale(1.08); } 70% { transform:rotate(8deg) scale(1); } }
@keyframes fullscreen-guide-pulse { 0% { opacity:.85; transform:scale(.92); } 100% { opacity:0; transform:scale(1.22); } }
@media (prefers-reduced-motion: reduce) { .fullscreen-reminder, .fullscreen-reminder > i, .reminder-active .composer-heading .fullscreen-toggle::after { animation:none; } }
.composer-heading i { color: var(--wao-primary-text); font-size: 20px; }
.inline-heading { display: flex; align-items: center; flex-wrap: nowrap; gap: 6px; margin: 0 0 12px; min-width: 0; }
.inline-heading .fullscreen-toggle { flex: 0 0 auto; margin-left: auto; padding: 4px 7px; border: 0; color: var(--wao-text-3); }
.inline-heading .fullscreen-toggle:hover { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
.inline-heading > input { flex: 1; width: 0; min-width: 0; border: 0; border-bottom: 1px solid var(--wao-line-control); padding: 3px 0; background: transparent; color: var(--wao-text-1); font-size: var(--wao-font-section); font-weight: 600; }
.inline-heading input:focus { outline: none; border-bottom-color: var(--wao-primary); }
.new-generated-title input { width: 100%; margin-bottom: 8px; border: 0; border-bottom: 1px solid var(--wao-line-control); padding: 5px 0; background: transparent; color: var(--wao-text-1); font-size: var(--wao-font-card-title); font-weight: 600; }
.inline-heading .tag-chip, .inline-heading .add-tag { padding: 3px 7px; font-size: var(--wao-font-caption); }
.composer-editor-layout { display: flex; flex: 1; min-width: 0; min-height: 0; }
.composer-images { flex: 0 0 clamp(180px, 28%, 300px); align-self: stretch; min-height: 0; overflow: auto; margin-left: 16px; padding: 12px; border: 1px solid var(--wao-line); border-radius: 10px; }
.images-stacked.has-image-rail .composer-editor-layout { flex-direction: column; }
.images-stacked .composer-images { flex: 0 1 auto; width: 100%; max-height: 220px; margin: 14px 0 0; }
.images-stacked.has-image-rail .composer-editor-main { min-height: 160px; }
.fullscreen.images-stacked.has-image-rail .composer-editor-main { min-height: 80px; }
.fullscreen.images-stacked .composer-images { max-height: min(220px, calc(var(--note-visible-height, 100dvh) * .28)); }
.composer-image-lightbox { position: fixed; inset: 0; z-index: 2400; display: grid; place-items: center; padding: 32px; background: rgba(15, 18, 28, .82); }
.composer-image-lightbox > img { max-width: 100%; max-height: calc(100dvh - 64px); object-fit: contain; }
.composer-image-lightbox > button { position: absolute; top: 16px; right: 20px; border: 0; padding: 10px; color: white; background: rgba(255,255,255,.16); font-size: 22px; }
.composer-editor-main { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; }
.composer-editor-main :deep(img.md-img) { max-height: min(38vh, 320px); object-fit: contain; }
.composer-editor-main :deep(.note-image-node img.md-img) { max-height: none; }
.composer-footer > span { color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.composer-footer { border-top: 1px solid var(--wao-line); padding-top: 12px; flex-shrink: 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.composer-footer > span { flex: 1; min-width: 140px; line-height: 1.6; overflow-wrap: anywhere; }
button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); color: var(--wao-text-2); border-radius: 8px; padding: 8px 13px; cursor: pointer; }
.save-note { background: var(--wao-primary); border-color: var(--wao-primary); color: white; font-weight: 600; }
button:disabled { opacity: .5; cursor: default; }
.error { color: var(--wao-danger-strong) !important; font-size: var(--wao-font-help); overflow-wrap: anywhere; }
@media (max-width: 600px) { .note-composer { padding: 12px; } .composer-heading { flex-wrap: wrap; } .composer-heading span { width: 100%; } .fullscreen-reminder { top:49px; right:8px; width:calc(100% - 16px); gap:7px 8px; padding:10px 11px; } .fullscreen-reminder::before { right:15px; } .fullscreen-reminder-copy strong { font-size:var(--wao-font-body); } .fullscreen-reminder-copy span { font-size:var(--wao-font-body); } .fullscreen-reminder-actions { gap:4px; } .fullscreen-reminder-actions button { padding:5px 6px; font-size:var(--wao-font-body); } }
@media (prefers-reduced-motion: reduce) { .auto-switch,.auto-switch::after { transition:none; } }
.composer-footer > button { min-height: 36px; padding: 7px 14px; font-size: var(--wao-font-body); border-radius: 7px; }

button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@media (max-width: 600px) {
  .composer-footer > span { flex-basis: 100%; }
  .composer-footer > button { min-height: 44px; }
  .composer-footer .save-note { margin-left: auto; }
}
</style>

<style scoped>
.composer-heading b { font-size: var(--wao-font-body); }
.fullscreen-toggle { margin-left: auto; padding: 5px 7px; border: 0; }
.fullscreen-toggle i { font-size: 17px; color: var(--wao-text-3); }
.editor-space { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.fullscreen-quick-tags { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin: 2px 0 14px; }
.fullscreen-quick-tags > i { color: var(--wao-text-weak); font-size: 14px; }
.fullscreen-quick-tags button { display: inline-flex; align-items: center; gap: 5px; border: 0; border-radius: 20px; padding: 3px 11px; background: var(--wao-bg-sub-2); color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.fullscreen-quick-tags button:hover, .fullscreen-quick-tags button.selected { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.quick-tag-hash { opacity: .6; font-size: var(--wao-font-caption); }
@media (pointer: coarse) { .fullscreen-quick-tags button { min-height: 44px; } }
.editor-space :deep(.md) { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.editor-space :deep(.md-editor-wrap) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.editor-space :deep(.md-body) { flex: 1 1 0; min-height: 0 !important; overflow: auto; display: flex; flex-direction: column; cursor: text; }
.editor-space :deep(.tiptap) { flex: 1 0 auto; min-height: 100%; box-sizing: border-box; cursor: text; }
.note-composer:not(.inline) .editor-space :deep(.article-footer button) { min-height: 34px; padding: 6px 10px; font-size: var(--wao-font-body); }
.new-tag-form { display: flex; flex-wrap: wrap; gap: 7px; width: 100%; }
.new-tag-form input { min-width: 0; flex: 1; border: 1px solid var(--wao-line-control); border-radius: 6px; padding: 6px; background: var(--wao-bg-card); color: var(--wao-text-2); }
.new-tag-form .error { width: 100%; }
.editor-space :deep(.md-body) { overflow-wrap: anywhere; }
.composer-tags, .tag-picker { display: flex; gap: 7px; flex-wrap: wrap; }
.composer-tags { min-width: 0; }
.tag-picker { margin-top: 12px; }
.composer-tags button, .tag-picker button { padding: 5px 8px; font-size: var(--wao-font-caption); max-width: 100%; overflow-wrap: anywhere; }
.tag-chip, .tag-picker button[aria-pressed="true"] { color: var(--wao-primary-text); background: var(--wao-bg-hover); }
.tag-picker { padding: 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; max-height: 150px; overflow: auto; }
.tag-picker span { color: var(--wao-text-3); font-size: var(--wao-font-caption); }
.note-composer.fullscreen { position: fixed; inset: 0; z-index: 2100; border-radius: 0; padding: 24px max(24px, calc((100vw - 1100px) / 2)); height: 100dvh; }
.note-composer.fullscreen.writing-narrow { padding-inline: max(24px, calc((100vw - 650px) / 2)); }
.note-composer.fullscreen.has-image-rail { padding-inline: max(24px, calc((100vw - 1440px) / 2)); }
.note-composer.fullscreen.has-image-rail .composer-editor-main { max-width: 1100px; }
.note-composer.fullscreen.has-image-rail.writing-narrow .composer-editor-main { max-width: 650px; }
.note-composer.fullscreen.has-image-rail.writing-narrow { padding-inline: max(24px, calc((100vw - 970px) / 2)); }
.composer-heading .note-width-switch, .inline-heading .note-width-switch { margin-left: 0; }
.composer-heading { flex-wrap: wrap; }
.inline-heading .note-width-switch { flex: none; }
@media (max-width: 600px) { .note-composer.fullscreen.has-image-rail { padding: 12px; } }
.composer-heading .fullscreen-toggle, .inline-heading .fullscreen-toggle { margin-left: 0; }

@media (max-width: 760px) {
  .note-composer.fullscreen, .note-composer.fullscreen.writing-narrow, .note-composer.fullscreen.has-image-rail, .note-composer.fullscreen.has-image-rail.writing-narrow {
    top: var(--note-visible-top, 0px);
    bottom: auto;
    height: var(--note-visible-height, 100dvh);
    padding: max(12px, env(safe-area-inset-top)) max(12px, env(safe-area-inset-right)) max(12px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left));
  }
  .note-composer .editor-space :deep(.md-body .tiptap) { font-size: var(--wao-font-card-title); line-height: 1.7; }
  /* 正文由 flex 填满输入区，不继承包含边框的最小高度产生空白滚动。 */
  .note-composer .editor-space :deep(.md-body .tiptap) { min-height: 0; }
  .fullscreen-toggle { min-width: 44px; min-height: 44px; }
  .mobile-empty-image-mode { display: none; }
  .composer-heading { gap: 6px; margin-bottom: 4px; }
  .composer-heading > b { flex: 1; }
  .composer-heading > .note-image-mode-switch { order: 4; margin: 0; }
  .composer-heading > .note-width-switch { order: 5; }
  .composer-footer { gap: 8px; padding-top: 8px; margin-top: 8px; }
  .composer-footer > span { flex: 1 1 120px; min-width: 0; }
  .composer-footer > button { min-height: 44px; }
  .fullscreen .composer-footer > span { flex: 1 1 120px; }
  .fullscreen-quick-tags { gap: 6px; margin-bottom: 8px; }
  .fullscreen-quick-tags { flex-wrap: nowrap; overflow-x: auto; flex-shrink: 0; }
  .fullscreen-quick-tags button { min-height: 44px; flex-shrink: 0; }
}
</style>

<style scoped>
.note-composer.contained { min-height: 60dvh; }
.note-composer.inline .composer-footer > .composer-tags { flex: 0 0 100%; margin: 0; padding-bottom: 2px; }
.note-composer .composer-footer > .composer-word-count { flex: 0 0 auto; min-width: 0; white-space: nowrap; font-variant-numeric: tabular-nums; }
.note-composer.inline .composer-footer { margin-top: 10px; gap: 8px 10px; }
.note-composer:not(.inline) .composer-tags { order: -1; }
.note-composer.inline .inline-heading .add-tag { flex: none; min-width: 30px; min-height: 30px; padding: 4px 7px; border: 0; color: var(--wao-text-3); }
@media (max-width: 600px) { .inline-heading:has(.note-width-switch) { flex-wrap: wrap; } .inline-heading .note-width-switch { flex-basis: 100%; justify-content: flex-end; } }
@media (pointer: coarse) { .note-composer.inline .inline-heading .add-tag { min-width: 44px; min-height: 44px; } }
.fullscreen-quick-tags .inline-tag-create { display:flex; flex-wrap:wrap; gap:6px; min-width:0; }
.fullscreen-quick-tags .inline-tag-create input { min-width:0; width:140px; padding:6px; border:1px solid var(--wao-line-control); border-radius:6px; background:var(--wao-bg-card); color:var(--wao-text); }
.fullscreen-quick-tags .add-tag { flex:none; padding:4px 9px; border-radius:7px; }
</style>

<style scoped>
.composer-title-input{flex:1;min-width:0;width:100%;font:inherit;font-size:var(--wao-font-body);font-weight:600;border:0;border-bottom:1px solid var(--wao-line-control);padding:7px 0;background:transparent;color:var(--wao-text-1);outline:none}.composer-title-input::placeholder{color:var(--wao-text-2);opacity:1}.composer-title-input:focus{border-bottom-color:var(--wao-primary)}@media(max-width:600px){.composer-title-input{font-size:var(--wao-font-card-title)}}
</style>

<style scoped>.composer-image-lightbox > img{max-height:calc(100dvh - 150px)}</style>
