<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { apiCreateNoteTag, apiPatchDoc, fetchDoc } from '@/api/board'
import { legacyProjection, resolvedRichContent } from '@/utils/richDoc'
import { rebaseNoteDraft } from '@/utils/rebaseNoteDraft'
import { notePolishVersions } from '@/utils/autoNoteEnrichment'
import { firstMarkdownTextLine } from '@/utils/markdownTitle'
import { startAutoNoteEnrichment } from '@/utils/autoNoteEnrichment'
import type { Doc, FileItem, RichDocContent } from '@/types'

const props = defineProps<{ note?: Doc; loading?: boolean; tagOptions?: string[]; inline?: boolean; autofocus?: boolean }>()
const emit = defineEmits<{ saved: [id: string]; close: []; 'tag-created': [name: string] }>()
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const noteEditor = ref<{ removeImageAt: (index: number) => boolean; insertTextAtSelection: (text: string, deleteBefore?: number) => boolean } | null>(null)
const data = useDataStore()
const session = useSessionStore()
const preferences = usePreferenceStore()
const draftKey = props.note
  ? `wao-note-edit-v1:${session.currentUser.id}:${props.note.id}`
  : `wao-quick-note-draft-v1:${session.currentUser.id || 'anonymous'}`
type Draft = { id: string; text: string; content?: RichDocContent; baseRev?: number; tags?: string[]; generatedTitle?: string }
let restored = false
function readDraft(): Draft {
  try {
    const saved = JSON.parse(localStorage.getItem(draftKey) || 'null') as Draft | null
    if (saved && /^[A-Za-z0-9_-]{1,32}$/.test(saved.id) && typeof saved.text === 'string') {
      restored = true
      return saved
    }
  } catch { /* 无可恢复的草稿。 */ }
  const content = props.note ? resolvedRichContent(props.note) : undefined
  return { id: props.note?.id ?? crypto.randomUUID().replace(/-/g, ''), text: content?.markdown ?? '', content, baseRev: props.note?.rev, tags: props.note?.tags ?? [] }
}
const initial = readDraft()
const savedContent = props.note ? resolvedRichContent(props.note) : { version: 1 as const, document: null, markdown: '' }
const savedSnapshot = ref({
  text: props.note ? savedContent.markdown : '',
  document: savedContent.document,
  tags: [...(props.note?.tags ?? [])],
  title: props.note?.title ?? '',
})
const tags = ref<string[]>(initial.tags ?? [])
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
const fullscreen = ref(false)
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
  if (props.inline || !preferences.loaded || preferences.loadError || !setting.enabled
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
})
onBeforeUnmount(() => {
  clearFullscreenReminderTimer()
  if (previousOverflow !== undefined) document.body.style.overflow = previousOverflow
})
function editorKeys(event: KeyboardEvent) {
  if (event.key === 'Escape' && tagSuggestionsOpen.value) { event.preventDefault(); tagSuggestionsOpen.value = false; return }
  if (!fullscreen.value) return
  if (event.key === 'Escape') { event.preventDefault(); fullscreen.value = false }
  if (event.key !== 'Tab') return
  const controls = Array.from(composerElement.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), [contenteditable="true"], textarea, summary, [tabindex="0"]') ?? []).filter(el => el.getClientRects().length)
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
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
// 恢复草稿必须沿用开始编辑时的版本，防止跨设备更新被旧草稿覆盖。
let baseRev = initial.baseRev ?? props.note?.rev ?? 1
const openedRev = props.note?.rev
let baseNote = props.note ? {
  title: props.note.title,
  tags: [...(props.note.tags ?? [])],
  blocks: props.note.blocks,
  content: savedContent,
} : null
const savedPreviewOpen = ref(!!props.note && !!props.inline)
const savedPreviewVersion = ref<'polished' | 'original'>('polished')
const savedPreviewContent = computed(() => props.note ? resolvedRichContent(props.note) : null)
const savedPolish = computed(() => {
  if (!props.note || !savedPreviewContent.value) return null
  const version = notePolishVersions[props.note.id]
  return version?.sourceMarkdown === savedPreviewContent.value.markdown ? version : null
})
const savedPreviewMarkdown = computed(() => savedPreviewVersion.value === 'polished' && savedPolish.value
  ? savedPolish.value.polishedMarkdown : savedPreviewContent.value?.markdown ?? '')
const saving = ref(false)
const error = ref('')
const conflicted = computed(() => /其他窗口更新|版本冲突/.test(error.value))
const copyId = crypto.randomUUID().replace(/-/g, '')
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
watch([text, content, tags, generatedTitle], () => {
  if (resetting) return
  error.value = ''
  try {
    localStorage.setItem(draftKey, JSON.stringify({ id: id.value, text: text.value, content: content.value, baseRev, tags: tags.value, generatedTitle: generatedTitle.value } satisfies Draft))
    draftError.value = false
    status.value = '草稿已保存在此浏览器'
  } catch {
    draftError.value = true
    status.value = '浏览器草稿保存失败，请保持页面打开并重试保存'
  }
}, { flush: 'sync' })

async function save(asCopy = false) {
  if (!canSave.value) return
  saving.value = true
  error.value = ''
  try {
    // 初始化未完成或服务不可达时，不能落成内存小记并提示成功。
    if (!data.apiLive) throw new Error('服务尚未连接，请稍后重试；输入内容已保留')
    // 模型文字已变化而结构化回调尚未到达时，以当前可见输入为准。
    const rich = content.value?.markdown === text.value
      ? content.value : { version: 1 as const, document: null, markdown: text.value }
    let savedId: string | null
    if (props.note && !asCopy) {
      if (data.docPending(props.note.id)) throw new Error('这篇小记在文档页还有未保存编辑，请先完成保存')
      const latest = await fetchDoc(props.note.id)
      if (restored && initial.baseRev != null && initial.baseRev !== openedRev) {
        throw new Error('文档已在其他窗口更新，恢复的旧草稿需先核对；正文仍在')
      }
      const latestRev = latest.rev ?? 1
      const merged = latestRev === baseRev
        ? { title: generatedTitle.value, tags: [...tags.value], content: rich }
        : baseNote && rebaseNoteDraft(baseNote, latest, {
          title: generatedTitle.value, tags: [...tags.value], content: rich,
        })
      if (!merged) throw new Error('文档已在其他窗口更新，正文或同一字段发生冲突；请核对后保留为新小记')
      const saved = await apiPatchDoc(props.note.id, {
        title: (merged.title || latest.title || firstMarkdownTextLine(text.value) || '未命名小记').slice(0, 80),
        content: merged.content, blocks: legacyProjection(merged.content), expectedRev: latestRev,
        tags: merged.tags,
      })
      data.applyRemoteDoc(saved)
      baseRev = saved.rev ?? baseRev
      baseNote = { title: saved.title, tags: [...(saved.tags ?? [])], blocks: saved.blocks, content: resolvedRichContent(saved) }
      savedId = saved.id
    } else {
      const aiDefaults = preferences.noteAiDefaults
      savedId = await data.addNote(text.value, generatedTitle.value, asCopy ? copyId : id.value, rich, [...tags.value], {
        autoTitle: aiDefaults.title && !generatedTitle.value.trim(),
        autoImage: aiDefaults.image,
        autoPolish: aiDefaults.polish,
      })
    }
    if (!savedId) throw new Error('正文为空，请先写点内容')
    try {
      const latest = JSON.parse(localStorage.getItem(draftKey) || 'null') as Draft | null
      // 页面切走又回来时，新组件可能已经继续输入，旧请求不能清掉较新的草稿。
      if (!latest || (latest.id === id.value && latest.text === text.value
        && JSON.stringify(latest.content) === JSON.stringify(content.value)
        && JSON.stringify(latest.tags ?? []) === JSON.stringify(tags.value)
        && (latest.generatedTitle ?? '') === generatedTitle.value)) localStorage.removeItem(draftKey)
    } catch { /* 服务端保存已成功。 */ }
    if (!props.note || asCopy) {
      const saved = data.docById(savedId)
      const allowedTags = [...new Set([...(props.tagOptions ?? []), ...tags.value])]
      if (saved?.rev && allowedTags.length > 0) startAutoNoteEnrichment(session.currentUser.id, savedId, saved.rev, {
        titleEnabled: false,
        imageEnabled: false,
        polishEnabled: false,
        tagsEnabled: true,
        allowedTags,
      })
    }
    if (!props.note) {
      resetting = true
      text.value = ''
      content.value = undefined
      tags.value = []
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
    fullscreenReminderVisible.value = false
    clearFullscreenReminderTimer()
    fullscreen.value = false
    emit('saved', savedId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

defineExpose({ save, dirty })

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
    if (file.size > data.MAX_FILE_BYTES) throw new Error('文件超过 50MB，请选择较小的文件')
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
  <section ref="composerElement" class="note-composer" :class="{ fullscreen, inline, 'reminder-active': fullscreenReminderVisible && !fullscreen }" @keydown="editorKeys" :aria-label="note ? '编辑小记' : '记录小记'">
    <div v-if="!inline" class="composer-heading"><i class="ri-quill-pen-line"></i><b>{{ note ? (inline ? '编辑这条小记' : '继续编辑') : '此刻，想记点什么？' }}</b><button class="fullscreen-toggle" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏编辑'" :title="fullscreen ? '退出全屏（Esc）' : '全屏编辑'" :aria-pressed="fullscreen" :aria-describedby="fullscreenReminderVisible && !fullscreen ? fullscreenReminderId : undefined" @click="fullscreen = !fullscreen"><i :class="fullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i></button></div>
    <div v-if="fullscreenReminderVisible && !fullscreen" :id="fullscreenReminderId" class="fullscreen-reminder" role="status" aria-live="polite">
      <i class="ri-focus-3-line" aria-hidden="true"></i>
      <div class="fullscreen-reminder-copy"><strong>已经写了一会儿，来全屏专心创作？</strong><span>全屏后正文和图片更舒展，当前内容与草稿会保留。</span></div>
      <div class="fullscreen-reminder-actions"><button type="button" class="reminder-enter" @click="enterFullscreen">进入全屏</button><button type="button" @click="remindLater">稍后提醒</button><button type="button" aria-label="关闭全屏提醒" title="关闭提醒" @click="dismissFullscreenReminder"><i class="ri-close-line" aria-hidden="true"></i></button></div>
    </div>
    <div v-if="!note && generatedTitle" class="new-generated-title"><input v-model="generatedTitle" type="text" maxlength="80" aria-label="生成的小记标题"></div>
    <div v-if="fullscreen" class="fullscreen-quick-tags" aria-label="快捷标签"><span>常用标签</span><button v-for="tag in quickChoices" :key="tag" type="button" :class="{ selected: tags.includes(tag) }" :aria-pressed="tags.includes(tag)" :disabled="saving" @click="toggleTag(tag)"># {{ tag }}</button><span class="tag-hint">输入 # 可快速选择</span></div>
    <div v-if="inline && note" class="inline-heading"><input v-model="generatedTitle" type="text" maxlength="80" aria-label="小记标题" :disabled="saving"><button v-for="tag in tags" :key="tag" class="tag-chip" type="button" :disabled="saving" :aria-label="`移除标签 ${tag}`" @click="toggleTag(tag)"># {{ tag }} <i class="ri-close-line"></i></button><button class="add-tag" type="button" :disabled="saving || creatingTag" :aria-label="tagCreateOpen ? '收起新增标签' : '新增标签'" :title="tagCreateOpen ? '收起新增标签' : '新增标签'" @click="toggleTagCreate"><i :class="tagCreateOpen ? 'ri-close-line' : 'ri-add-line'"></i></button><span v-if="tagCreateOpen" class="inline-tag-create"><input v-model="newTag" aria-label="新标签名称" placeholder="新标签名称" maxlength="32" :disabled="saving || creatingTag" @keydown.enter.prevent="createTag" @keydown.esc="tagCreateOpen = false"><button type="button" :disabled="saving || creatingTag || !newTag.trim()" @click="createTag">{{ creatingTag ? '…' : '↵' }}</button><span v-if="tagError" class="error" role="alert">{{ tagError }}</span></span><button class="fullscreen-toggle" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏编辑'" :title="fullscreen ? '退出全屏（Esc）' : '全屏编辑'" :aria-pressed="fullscreen" @click="fullscreen = !fullscreen"><i :class="fullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i></button></div>
    <div class="editor-space" @keydown.capture.exact="onComposerEditorKeydown" @keydown.capture.shift.exact="onComposerEditorKeydown" @keydown.ctrl.enter.prevent="save()" @keydown.meta.enter.prevent="save()">
      <div class="composer-editor-layout"><div class="composer-editor-main">
      <MarkdownEditor :key="id" ref="noteEditor" v-model="text" :document="content?.document" bare advanced allow-h1 polishable link-controls image-controls selection-image preview-images
        :article-image-key="`note:${session.currentUser.id}:${id}`"
        note-title-on-image @generated-title="generatedTitle = $event"
        hide-toolbar :autofocus="autofocus" :menu-z-index="fullscreen ? 2200 : 1000"
        :task-source="taskSource" @task-opened="fullscreen = false"
        :readonly="saving" :min-height="note ? 320 : 360" placeholder="想法、灵感、工作备忘…随手记在这里，也可以粘贴图片"
        @content-change="content = $event" @upload-image="uploadImage" @upload-file="uploadFile">
        <template v-if="!inline" #footer-tags>
          <div class="composer-tags">
            <button v-for="tag in tags" :key="tag" class="tag-chip" type="button" :disabled="saving" :aria-label="`移除标签 ${tag}`" @click="toggleTag(tag)"># {{ tag }} <i class="ri-close-line"></i></button>
            <button class="add-tag" type="button" :disabled="saving || creatingTag" :aria-label="tagCreateOpen ? '收起新增标签' : '新增标签'" :title="tagCreateOpen ? '收起新增标签' : '新增标签'" @click="toggleTagCreate"><i :class="tagCreateOpen ? 'ri-close-line' : 'ri-add-line'"></i></button><span v-if="tagCreateOpen" class="inline-tag-create"><input v-model="newTag" aria-label="新标签名称" placeholder="新标签名称" maxlength="32" :disabled="saving || creatingTag" @keydown.enter.prevent="createTag" @keydown.esc="tagCreateOpen = false"><button type="button" :disabled="saving || creatingTag || !newTag.trim()" @click="createTag">{{ creatingTag ? '…' : '↵' }}</button><span v-if="tagError" class="error" role="alert">{{ tagError }}</span></span>
          </div>
        </template>
      </MarkdownEditor>
      </div></div>
    </div>
    <aside v-if="inline && note" class="saved-preview" aria-label="已保存小记预览">
      <header><strong>已保存预览</strong><span>与上方未保存的编辑草稿独立</span><button type="button" :aria-expanded="savedPreviewOpen" @click="savedPreviewOpen = !savedPreviewOpen">{{ savedPreviewOpen ? '收起预览' : '展开预览' }}</button></header>
      <div v-if="savedPreviewOpen" class="saved-preview-body">
        <div v-if="savedPolish" class="saved-preview-versions" role="group" aria-label="已保存预览版本"><button type="button" :aria-pressed="savedPreviewVersion === 'polished'" @click="savedPreviewVersion = 'polished'">AI 润色</button><button type="button" :aria-pressed="savedPreviewVersion === 'original'" @click="savedPreviewVersion = 'original'">已保存原文</button></div>
        <MarkdownEditor :model-value="savedPreviewMarkdown" :document="savedPreviewVersion === 'polished' && savedPolish ? undefined : savedPreviewContent?.document" readonly bare advanced allow-h1 preview-images :min-height="0" />
      </div>
    </aside>
    <p v-if="error" class="error" role="alert">{{ error }}。正文仍在，可原地重试。</p>
    <div class="composer-footer">
      <span role="status" :class="{ error: draftError }">{{ saving ? '正在保存到服务器…' : status || 'Ctrl / ⌘ + Enter 保存' }}</span>
      <button v-if="note && conflicted" type="button" :disabled="!canSave" @click="save(true)">保留为新小记</button>
      <button v-if="note" type="button" :disabled="saving" @click="emit('close')">收起编辑</button>
      <button class="save-note" type="button" :disabled="!canSave" @click="save()">{{ saving ? '正在保存…' : error ? '重试保存' : note ? '保存修改' : '记下来了' }}</button>
    </div>
    <Teleport to="body"><div v-if="tagSuggestionsOpen" class="tag-suggestion-menu" role="listbox" aria-label="最近常用标签" :style="tagSuggestionStyle"><div>最近常用</div><button v-for="tag in choices.slice(0, 8)" :key="tag" type="button" role="option" :aria-selected="tags.includes(tag)" :disabled="saving || (!tags.includes(tag) && tags.length >= 20)" @mousedown.prevent @click="insertSuggestedTag(tag)"># {{ tag }}<span>{{ tags.includes(tag) ? '已选' : '' }}</span></button><small>按使用频次排序 · Esc 收起</small></div></Teleport>
  </section>
</template>

<style scoped>
.note-composer { position: relative; display: flex; flex-direction: column; min-width: 0; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 14px; padding: 18px; background: var(--wao-bg-card); }
.note-composer.inline:not(.fullscreen) { padding: 0; border: 0; border-radius: 0; }
.inline:not(.fullscreen) .editor-space { min-height: 160px; max-height: 460px; }
.saved-preview { margin-top: 12px; border: 1px solid var(--wao-line-control); border-radius: 10px; background: var(--wao-bg-card); }
.saved-preview header { display: flex; align-items: center; gap: 8px; padding: 8px 10px; font-size: 12px; color: var(--wao-text-3); }
.saved-preview header strong { color: var(--wao-text-1); }
.saved-preview header span { flex: 1; }
.saved-preview button { border: 0; background: transparent; color: var(--wao-primary-text); cursor: pointer; }
.saved-preview-body { max-height: 260px; overflow: auto; border-top: 1px solid var(--wao-line-control); padding: 8px 10px; }
.saved-preview-versions { display: flex; gap: 8px; margin-bottom: 8px; font-size: 12px; }
.saved-preview-versions button[aria-pressed="true"] { font-weight: 700; text-decoration: underline; }
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
.composer-footer > span { color: var(--wao-text-faint); font-size: 12px; }
.composer-footer { flex-shrink: 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.composer-footer > span { flex: 1; min-width: 140px; }
button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); color: var(--wao-text-2); border-radius: 8px; padding: 8px 13px; cursor: pointer; }
.save-note { background: var(--wao-primary); border-color: var(--wao-primary); color: white; font-weight: 600; }
button:disabled { opacity: .5; cursor: default; }
.error { color: var(--wao-danger-strong) !important; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 600px) { .note-composer { padding: 12px; } .composer-heading { flex-wrap: wrap; } .composer-heading span { width: 100%; } .fullscreen-reminder { top:49px; right:8px; width:calc(100% - 16px); gap:7px 8px; padding:10px 11px; } .fullscreen-reminder::before { right:15px; } .fullscreen-reminder-copy strong { font-size:12px; } .fullscreen-reminder-copy span { font-size:11px; } .fullscreen-reminder-actions { gap:4px; } .fullscreen-reminder-actions button { padding:5px 6px; font-size:11px; } }
@media (prefers-reduced-motion: reduce) { .auto-switch,.auto-switch::after { transition:none; } }
.composer-footer > button { min-height: 36px; padding: 7px 14px; font-size: 13px; border-radius: 7px; }
</style>

<style scoped>
.composer-heading b { font-size: 15px; }
.fullscreen-toggle { margin-left: auto; padding: 5px 7px; border: 0; }
.fullscreen-toggle i { font-size: 17px; color: var(--wao-text-3); }
.editor-space { flex: 1; min-height: 0; display: flex; }
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

@media (max-width: 600px) { .note-composer.fullscreen { padding: 12px; } }
</style>
