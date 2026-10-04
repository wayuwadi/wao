<script setup lang="ts">
import { computed, defineAsyncComponent, defineComponent, h, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { apiCreateNoteTag, apiPatchDoc, fetchDoc } from '@/api/board'
import { legacyProjection, resolvedRichContent } from '@/utils/richDoc'
import { rebaseNoteDraft } from '@/utils/rebaseNoteDraft'
import { latestNoteContent, type NoteDraftSnapshot } from '@/utils/latestNoteContent'
import { readNoteDraft, writeNoteDraft, removeNoteDraft } from '@/utils/noteDraftStorage'
import { firstMarkdownTextLine } from '@/utils/markdownTitle'
import NoteAiSettingsDialog from './NoteAiSettingsDialog.vue'
import NoteWidthSwitch from './NoteWidthSwitch.vue'
import { useNoteWritingWidth } from '@/composables/noteWritingWidth'
import { useSaveBeforeRefresh } from '@/composables/saveBeforeRefresh'
import { captureCompanionTicket, respondWithCompanion } from '@/composables/quietCompanions'
import type { Doc, FileItem, RichDocContent } from '@/types'
import type { NoteSubmission } from '@/api/dayWorkspace'

const props = withDefaults(defineProps<{ note?: Doc; loading?: boolean; tagOptions?: string[]; inline?: boolean; autofocus?: boolean; expanded?: boolean; initialFullscreen?: boolean; initialPosition?: number; initialViewportTop?: number; workflowMessage?: string; searchTerm?: string; searchRequest?: string; draftScope?: string; submitNote?: (request: NoteSubmission) => Promise<Doc>; compact?: boolean; active?: boolean }>(), { active: true })
const emit = defineEmits<{ saved: [id: string]; close: []; 'tag-created': [name: string]; 'draft-change': [draft: NoteDraftSnapshot | null]; 'saving-change': [saving: boolean]; 'fullscreen-change': [fullscreen: boolean] }>()
const MarkdownEditor = defineAsyncComponent({
  loader: () => import('@/components/ui/MarkdownEditor.vue'),
  loadingComponent: defineComponent({ setup: () => () => h('p', { role: 'status' }, '正在加载编辑器…') }),
})
const noteEditor = ref<{ removeImageAt: (index: number) => boolean; insertTextAtSelection: (text: string, deleteBefore?: number) => boolean; appendPlainText: (text: string) => boolean } | null>(null)
const data = useDataStore()
const session = useSessionStore()
const writingWidth = useNoteWritingWidth()
const preferences = usePreferenceStore()
const draftKey = props.note
  ? `wao-note-edit-v1:${session.currentUser.id}:${props.note.id}`
  : `wao-quick-note-draft-v1:${session.currentUser.id || 'anonymous'}${props.draftScope ? ':' + props.draftScope : ''}`
type DraftBase = { title: string; tags: string[]; content: RichDocContent }
type Draft = { sourceInputIds?: string[]; id: string; text: string; content?: RichDocContent; baseRev?: number; baseNote?: DraftBase; tags?: string[]; generatedTitle?: string }
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
const fullscreen = ref(!!props.initialFullscreen)
const aiSettingsOpen = ref(false)
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
  if (event.key === 'Escape' && tagSuggestionsOpen.value) { event.preventDefault(); tagSuggestionsOpen.value = false; return }
  if (!fullscreen.value) return
  if (event.key === 'Escape') { event.preventDefault(); fullscreen.value = false }
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
  if (saving.value || creatingTag.value) return
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
const quickChoices = computed(() => choices.value.slice(0, 6))
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
const content = ref<RichDocContent | undefined>(initial.content)
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
const canSave = computed(() => !!text.value.trim() && !saving.value && !creatingTag.value && !props.loading)
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
watch([text, content, tags, generatedTitle, appliedAiInputs], () => {
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
    writeNoteDraft(draftKey, JSON.stringify({ id: id.value, text: text.value, content: content.value, baseRev,
      baseNote: baseNote ? { title: baseNote.title, tags: baseNote.tags, content: baseNote.content } : undefined,
      tags: tags.value, generatedTitle: generatedTitle.value, sourceInputIds: appliedAiInputs.value } satisfies Draft))
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
}, { flush: 'sync' })

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
  if (!canSave.value) return
  const responseTicket = explicit ? captureCompanionTicket(composerElement.value?.querySelector('.save-note')) : undefined
  saving.value = true
  error.value = ''
  try {
    // 初始化未完成或服务不可达时，不能落成内存小记并提示成功。
    if (!data.apiLive) throw new Error('服务尚未连接，请稍后重试；输入内容已保留')
    // 模型文字已变化而结构化回调尚未到达时，以当前可见输入为准。
    const rich = content.value?.markdown === text.value
      ? content.value : { version: 1 as const, document: null, markdown: text.value }
    const submitted = { text: text.value, content: content.value, tags: [...tags.value], title: generatedTitle.value, sourceInputIds: [...appliedAiInputs.value] }
    let savedId: string | null
    if (props.submitNote) {
      const aiDefaults = preferences.noteAiDefaults
      const saved = await props.submitNote({ docId: id.value, title: submitted.title, content: rich, tags: submitted.tags,
        ...(submitted.sourceInputIds.length ? { sourceInputIds: submitted.sourceInputIds } : {}),
        autoTitle: aiDefaults.title && !submitted.title.trim(), autoImage: aiDefaults.image, autoPolish: aiDefaults.polish })
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
          saved = await apiPatchDoc(props.note.id, {
            title: (merged.title || latest.title || firstMarkdownTextLine(submitted.text) || '未命名小记').slice(0, 80),
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
      savedId = await data.addNote(submitted.text, submitted.title, id.value, rich, submitted.tags, submitted.sourceInputIds.length ? { sourceInputIds: submitted.sourceInputIds } : undefined)

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
        && (latest.generatedTitle ?? '') === submitted.title)) removeNoteDraft(draftKey, raw)
    } catch { /* 服务端保存已成功。 */ }
    if (text.value !== submitted.text || JSON.stringify(tags.value) !== JSON.stringify(submitted.tags)
        || generatedTitle.value !== submitted.title) {
      status.value = '已保存提交内容 · 最新输入已保留，待保存'
      return
    }
    if (!props.note) {
      resetting = true
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

function appendPlainText(value: string) {
  if (saving.value || props.loading) return false
  return noteEditor.value?.appendPlainText(value) ?? false
}
defineExpose({ save, dirty, appendPlainText })
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
    const item = await data.uploadFile(file)
    if (!item) throw new Error('文件上传失败，请重试')
    done(item)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '文件上传失败，请重试'
    done(null)
  }
}
</script>

<template>
  <section ref="composerElement" class="note-composer" :class="{ fullscreen, inline, expanded, 'writing-narrow': writingWidth === 'narrow', 'reminder-active': fullscreenReminderVisible && !fullscreen }" @keydown="editorKeys" :aria-label="note ? '编辑小记' : '记录小记'">
    <div v-if="!inline" class="composer-heading"><i class="ri-quill-pen-line"></i><b>{{ note ? (inline ? '编辑这条小记' : '继续编辑') : '此刻，想记点什么？' }}</b><NoteWidthSwitch v-model="writingWidth" /><button class="fullscreen-toggle" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏编辑'" :title="fullscreen ? '退出全屏（Esc）' : '全屏编辑'" :aria-pressed="fullscreen" :aria-describedby="fullscreenReminderVisible && !fullscreen ? fullscreenReminderId : undefined" @click="fullscreen = !fullscreen"><i :class="fullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i></button></div>
    <div v-if="fullscreenReminderVisible && !fullscreen" :id="fullscreenReminderId" class="fullscreen-reminder" role="status" aria-live="polite">
      <i class="ri-focus-3-line" aria-hidden="true"></i>
      <div class="fullscreen-reminder-copy"><strong>已经写了一会儿，来全屏专心创作？</strong><span>全屏后正文和图片更舒展，当前内容与草稿会保留。</span></div>
      <div class="fullscreen-reminder-actions"><button type="button" class="reminder-enter" @click="enterFullscreen">进入全屏</button><button type="button" @click="remindLater">稍后提醒</button><button type="button" aria-label="关闭全屏提醒" title="关闭提醒" @click="dismissFullscreenReminder"><i class="ri-close-line" aria-hidden="true"></i></button></div>
    </div>
    <div v-if="!note && generatedTitle" class="new-generated-title"><input v-model="generatedTitle" type="text" maxlength="80" aria-label="生成的小记标题"></div>
    <div v-if="inline && note" class="inline-heading"><input v-model="generatedTitle" type="text" maxlength="80" aria-label="小记标题" :disabled="saving"><button v-for="tag in tags" :key="tag" class="tag-chip" type="button" :disabled="saving" :aria-label="`移除标签 ${tag}`" @click="toggleTag(tag)"># {{ tag }} <i class="ri-close-line"></i></button><button class="add-tag" type="button" :disabled="saving || creatingTag" :aria-label="tagCreateOpen ? '收起新增标签' : '新增标签'" :title="tagCreateOpen ? '收起新增标签' : '新增标签'" @click="toggleTagCreate"><i :class="tagCreateOpen ? 'ri-close-line' : 'ri-add-line'"></i></button><span v-if="tagCreateOpen" class="inline-tag-create"><input v-model="newTag" aria-label="新标签名称" placeholder="新标签名称" maxlength="32" :disabled="saving || creatingTag" @keydown.enter.prevent="createTag" @keydown.esc="tagCreateOpen = false"><button type="button" :disabled="saving || creatingTag || !newTag.trim()" @click="createTag">{{ creatingTag ? '…' : '↵' }}</button><span v-if="tagError" class="error" role="alert">{{ tagError }}</span></span><NoteWidthSwitch v-model="writingWidth" /><button class="fullscreen-toggle" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏编辑'" :title="fullscreen ? '退出全屏（Esc）' : '全屏编辑'" :aria-pressed="fullscreen" @click="fullscreen = !fullscreen"><i :class="fullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i></button></div>
    <div class="editor-space" @keydown.capture.exact="onComposerEditorKeydown" @keydown.capture.shift.exact="onComposerEditorKeydown" @keydown.ctrl.enter.prevent="save(true)" @keydown.meta.enter.prevent="save(true)">
      <div v-if="quickChoices.length" class="fullscreen-quick-tags" role="group" aria-label="快捷标签"><i class="ri-price-tag-3-line" aria-hidden="true"></i><button v-for="tag in quickChoices" :key="tag" type="button" :class="{ selected: tags.includes(tag) }" :aria-pressed="tags.includes(tag)" :aria-label="`${tags.includes(tag) ? '移除' : '添加'}标签 ${tag}`" :disabled="saving || creatingTag || (!tags.includes(tag) && tags.length >= 20)" @mousedown.prevent @click="toggleTag(tag)"><span class="quick-tag-hash" aria-hidden="true">#</span>{{ tag }}<i v-if="tags.includes(tag)" class="ri-check-line" aria-hidden="true"></i></button></div>
      <div class="composer-editor-layout"><div class="composer-editor-main">
      <MarkdownEditor :search-term="searchTerm" :search-request="searchRequest" :key="id" ref="noteEditor" v-model="text" :document="content?.document" bare advanced allow-h1 polishable link-controls image-controls image-context-controls selection-image preview-images
        :article-image-key="`note:${session.currentUser.id}:${id}`"
        :article-image-generation="false"
        :note-ai-origin="{ stableId: id, docId: note?.id, sourceRev: baseRev, title: generatedTitle, blocks: note?.blocks ?? [], links: note?.links ?? [] }" note-title-on-image @ai-applied="appliedAiInputs = [...new Set([...appliedAiInputs, $event])]" @generated-title="generatedTitle = $event"
        hide-toolbar :autofocus="autofocus" :initial-position="initialPosition" :initial-viewport-top="initialViewportTop" :menu-z-index="fullscreen ? 2200 : compact ? 1200 : 1000"
        :task-source="taskSource" @task-opened="fullscreen = false"
        :readonly="saving" :min-height="compact ? 200 : note ? 320 : 360" placeholder="想法、灵感、工作备忘…随手记在这里，也可以粘贴图片"
        @content-change="content = $event" @upload-image="uploadImage" @upload-file="uploadFile">
        <template #footer-tags>
          <div class="composer-tags">
            <button class="note-ai-settings" type="button" aria-label="小记 AI 设置" title="小记 AI 设置" @click="aiSettingsOpen = true"><i class="ri-settings-3-line"></i><span>设置</span></button>
            <template v-if="!inline">
            <button v-for="tag in tags" :key="tag" class="tag-chip" type="button" :disabled="saving" :aria-label="`移除标签 ${tag}`" @click="toggleTag(tag)"># {{ tag }} <i class="ri-close-line"></i></button>
            <button class="add-tag" type="button" :disabled="saving || creatingTag" :aria-label="tagCreateOpen ? '收起新增标签' : '新增标签'" :title="tagCreateOpen ? '收起新增标签' : '新增标签'" @click="toggleTagCreate"><i :class="tagCreateOpen ? 'ri-close-line' : 'ri-add-line'"></i></button><span v-if="tagCreateOpen" class="inline-tag-create"><input v-model="newTag" aria-label="新标签名称" placeholder="新标签名称" maxlength="32" :disabled="saving || creatingTag" @keydown.enter.prevent="createTag" @keydown.esc="tagCreateOpen = false"><button type="button" :disabled="saving || creatingTag || !newTag.trim()" @click="createTag">{{ creatingTag ? '…' : '↵' }}</button><span v-if="tagError" class="error" role="alert">{{ tagError }}</span></span>
            </template>
          </div>
        </template>
      </MarkdownEditor>
      </div></div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}。正文仍在，可原地重试。</p>
    <NoteAiSettingsDialog v-model:open="aiSettingsOpen" />
    <div class="composer-footer">
      <span role="status" aria-live="polite" :class="{ error: draftError }">{{ loading ? '正在加载小记…' : saving ? '正在保存到服务器…' : draftError ? status : dirty ? '草稿已保护 · 有修改待保存' : workflowMessage || status || (note ? '已保存，可继续编辑' : 'Ctrl / ⌘ + Enter 保存') }}</span>
      <button v-if="note" type="button" :disabled="saving" @click="emit('close')">收起编辑</button>
      <button class="save-note" type="button" :disabled="!canSave" @click="save(true)">{{ saving ? '正在保存…' : error ? '重试保存' : note ? '保存修改' : '记下来了' }}</button>
    </div>
    <Teleport to="body"><div v-if="tagSuggestionsOpen" class="tag-suggestion-menu" role="listbox" aria-label="最近常用标签" :style="tagSuggestionStyle"><div>最近常用</div><button v-for="tag in choices.slice(0, 8)" :key="tag" type="button" role="option" :aria-selected="tags.includes(tag)" :disabled="saving || (!tags.includes(tag) && tags.length >= 20)" @mousedown.prevent @click="insertSuggestedTag(tag)"># {{ tag }}<span>{{ tags.includes(tag) ? '已选' : '' }}</span></button><small>按使用频次排序 · Esc 收起</small></div></Teleport>
  </section>
</template>

<style scoped>
.note-composer { position: relative; display: flex; flex-direction: column; min-width: 0; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 14px; padding: 18px; background: var(--wao-bg-card); }
.note-composer.inline:not(.fullscreen) { padding: 0; border: 0; border-radius: 0; }
.inline:not(.fullscreen) .editor-space { min-height: 160px; max-height: 460px; }
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
.fullscreen-reminder-copy strong { color:var(--wao-text-1); font-size:13px; font-weight:600; }
.fullscreen-reminder-copy span { color:var(--wao-text-3); font-size:12px; line-height:1.45; }
.fullscreen-reminder-actions { grid-column:2; display:flex; align-items:center; flex-wrap:wrap; gap:5px; }
.fullscreen-reminder-actions button { padding:6px 8px; border:0; background:transparent; color:var(--wao-text-3); font-size:12px; white-space:nowrap; }
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
.inline-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; margin: 0 0 12px; }
.inline-heading .fullscreen-toggle { flex: 0 0 auto; margin-left: auto; padding: 4px 7px; border: 0; color: var(--wao-text-3); }
.inline-heading .fullscreen-toggle:hover { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
.inline-heading input { flex: 0 1 300px; min-width: min(180px, 100%); border: 0; border-bottom: 1px solid transparent; padding: 3px 0; background: transparent; color: var(--wao-text-1); font-size: 17px; font-weight: 600; }
.inline-heading input:focus { outline: none; border-bottom-color: var(--wao-primary); }
.new-generated-title input { width: 100%; margin-bottom: 8px; border: 0; border-bottom: 1px solid var(--wao-line-control); padding: 5px 0; background: transparent; color: var(--wao-text-1); font-size: 16px; font-weight: 600; }
.inline-heading .tag-chip, .inline-heading .add-tag { padding: 3px 7px; font-size: 11px; }
.composer-editor-layout { display: flex; flex: 1; min-width: 0; min-height: 0; }
.composer-editor-main { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; }
.composer-editor-main :deep(img.md-img) { max-height: min(38vh, 320px); object-fit: contain; }
.composer-editor-main :deep(.note-image-node img.md-img) { max-height: none; }
.composer-footer > span { color: var(--wao-text-faint); font-size: 12px; }
.composer-footer { border-top: 1px solid var(--wao-line); padding-top: 12px; flex-shrink: 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.composer-footer > span { flex: 1; min-width: 140px; line-height: 1.6; overflow-wrap: anywhere; }
button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); color: var(--wao-text-2); border-radius: 8px; padding: 8px 13px; cursor: pointer; }
.save-note { background: var(--wao-primary); border-color: var(--wao-primary); color: white; font-weight: 600; }
button:disabled { opacity: .5; cursor: default; }
.error { color: var(--wao-danger-strong) !important; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 600px) { .note-composer { padding: 12px; } .composer-heading { flex-wrap: wrap; } .composer-heading span { width: 100%; } .fullscreen-reminder { top:49px; right:8px; width:calc(100% - 16px); gap:7px 8px; padding:10px 11px; } .fullscreen-reminder::before { right:15px; } .fullscreen-reminder-copy strong { font-size:12px; } .fullscreen-reminder-copy span { font-size:11px; } .fullscreen-reminder-actions { gap:4px; } .fullscreen-reminder-actions button { padding:5px 6px; font-size:11px; } }
@media (prefers-reduced-motion: reduce) { .auto-switch,.auto-switch::after { transition:none; } }
.composer-footer > button { min-height: 36px; padding: 7px 14px; font-size: 13px; border-radius: 7px; }

button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@media (max-width: 600px) {
  .composer-footer > span { flex-basis: 100%; }
  .composer-footer > button { min-height: 44px; }
  .composer-footer .save-note { margin-left: auto; }
}
</style>

<style scoped>
.composer-heading b { font-size: 15px; }
.fullscreen-toggle { margin-left: auto; padding: 5px 7px; border: 0; }
.fullscreen-toggle i { font-size: 17px; color: var(--wao-text-3); }
.editor-space { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.fullscreen-quick-tags { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin: 2px 0 14px; }
.fullscreen-quick-tags > i { color: var(--wao-text-weak); font-size: 14px; }
.fullscreen-quick-tags button { display: inline-flex; align-items: center; gap: 5px; border: 0; border-radius: 20px; padding: 3px 11px; background: var(--wao-bg-sub-2); color: var(--wao-text-3); font-size: 12px; }
.fullscreen-quick-tags button:hover, .fullscreen-quick-tags button.selected { background: var(--wao-primary-soft); color: var(--wao-primary-text); }
.quick-tag-hash { opacity: .6; font-size: 11px; }
@media (pointer: coarse) { .fullscreen-quick-tags button { min-height: 44px; } }
.editor-space :deep(.md) { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.editor-space :deep(.md-editor-wrap) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.editor-space :deep(.md-body) { flex: 1 1 0; min-height: 0 !important; overflow: auto; display: flex; flex-direction: column; cursor: text; }
.editor-space :deep(.tiptap) { flex: 1 0 auto; min-height: 100%; box-sizing: border-box; cursor: text; }
.note-composer:not(.inline) .editor-space :deep(.article-footer button) { min-height: 34px; padding: 6px 10px; font-size: 12px; }
.new-tag-form { display: flex; flex-wrap: wrap; gap: 7px; width: 100%; }
.new-tag-form input { min-width: 0; flex: 1; border: 1px solid var(--wao-line-control); border-radius: 6px; padding: 6px; background: var(--wao-bg-card); color: var(--wao-text-2); }
.new-tag-form .error { width: 100%; }
.editor-space :deep(.md-body) { overflow-wrap: anywhere; }
.composer-tags, .tag-picker { display: flex; gap: 7px; flex-wrap: wrap; }
.composer-tags { min-width: 0; }
.tag-picker { margin-top: 12px; }
.composer-tags button, .tag-picker button { padding: 5px 8px; font-size: 12px; max-width: 100%; overflow-wrap: anywhere; }
.tag-chip, .tag-picker button[aria-pressed="true"] { color: var(--wao-primary-text); background: var(--wao-bg-hover); }
.tag-picker { padding: 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; max-height: 150px; overflow: auto; }
.tag-picker span { color: var(--wao-text-faint); font-size: 12px; }
.note-composer.fullscreen { position: fixed; inset: 0; z-index: 2100; border-radius: 0; padding: 24px max(24px, calc((100vw - 1100px) / 2)); height: 100dvh; }
.note-composer.writing-narrow:not(.fullscreen) { width: 100%; max-width: 650px; margin-inline: auto; }
.note-composer.fullscreen.writing-narrow { padding-inline: max(24px, calc((100vw - 650px) / 2)); }
.composer-heading .note-width-switch, .inline-heading .note-width-switch { margin-left: auto; }
.composer-heading .fullscreen-toggle, .inline-heading .fullscreen-toggle { margin-left: 0; }

@media (max-width: 600px) { .note-composer.fullscreen { padding: 12px; } }
</style>
