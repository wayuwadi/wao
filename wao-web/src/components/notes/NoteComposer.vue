<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { apiCreateNoteTag, apiPatchDoc } from '@/api/board'
import { legacyProjection, resolvedRichContent } from '@/utils/richDoc'
import { firstMarkdownTextLine } from '@/utils/markdownTitle'
import { startAutoNoteEnrichment } from '@/utils/autoNoteEnrichment'
import { notePreviewImages, notePreviewSource, type NotePreviewImage } from '@/utils/notePreviewImages'
import ImageCopyMenu from '@/components/ui/ImageCopyMenu.vue'
import type { Doc, FileItem, RichDocContent } from '@/types'

const props = defineProps<{ note?: Doc; loading?: boolean; tagOptions?: string[]; inline?: boolean; autofocus?: boolean }>()
const emit = defineEmits<{ saved: [id: string]; close: []; 'tag-created': [name: string] }>()
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const noteEditor = ref<{ removeImageAt: (index: number) => boolean } | null>(null)
const data = useDataStore()
const session = useSessionStore()
const preferences = usePreferenceStore()
const draftKey = props.note
  ? `wao-note-edit-v1:${session.currentUser.id}:${props.note.id}`
  : `wao-quick-note-draft-v1:${session.currentUser.id || 'anonymous'}`
type Draft = { id: string; text: string; content?: RichDocContent; baseRev?: number; tags?: string[]; generatedTitle?: string; autoTitle?: boolean; autoPolish?: boolean; autoImage?: boolean }
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
const showTagPicker = ref(false)
const newTag = ref('')
const creatingTag = ref(false)
const tagError = ref('')
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
    emit('tag-created', saved)
    newTag.value = ''
  } catch (cause) { tagError.value = cause instanceof Error ? cause.message : '标签保存失败，请重试' }
  finally { creatingTag.value = false }
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
  else if (tags.value.length < 20) tags.value = [...tags.value, tag]
}
const choices = computed(() => [...new Set([...(props.tagOptions ?? []), ...tags.value])])
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
const editorImages = computed(() => notePreviewImages(content.value ?? { version: 1, document: null, markdown: text.value }))
const previewImage = ref<NotePreviewImage | null>(null)
const imageMenu = ref<{ src: string; x: number; y: number } | null>(null)
const lightbox = ref<HTMLElement | null>(null)
let imageTrigger: HTMLElement | null = null
async function openImage(image: NotePreviewImage, event: MouseEvent) {
  imageTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  previewImage.value = image
  await nextTick()
  lightbox.value?.focus()
}
function openImageMenu(src: string, event: MouseEvent) {
  event.preventDefault()
  event.stopPropagation()
  imageMenu.value = { src, x: event.clientX, y: event.clientY }
}
async function closeImage() {
  previewImage.value = null
  await nextTick()
  imageTrigger?.focus()
  imageTrigger = null
}
const autoPolish = ref(initial.autoPolish === true)
const autoImage = ref(initial.autoImage === true)
const autoTitle = ref(initial.autoTitle === true)
// 恢复草稿必须沿用开始编辑时的版本，防止跨设备更新被旧草稿覆盖。
const baseRev = initial.baseRev ?? props.note?.rev ?? 1
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
watch([text, content, tags, generatedTitle, autoTitle, autoPolish, autoImage], () => {
  if (resetting) return
  error.value = ''
  try {
    localStorage.setItem(draftKey, JSON.stringify({ id: id.value, text: text.value, content: content.value, baseRev, tags: tags.value, generatedTitle: generatedTitle.value, autoTitle: autoTitle.value, autoPolish: autoPolish.value, autoImage: autoImage.value } satisfies Draft))
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
    const rich = content.value ?? { version: 1 as const, document: null, markdown: text.value }
    let savedId: string | null
    if (props.note && !asCopy) {
      if (data.docPending(props.note.id)) throw new Error('这篇小记在文档页还有未保存编辑，请先完成保存')
      const saved = await apiPatchDoc(props.note.id, {
        title: (generatedTitle.value || props.note.title || firstMarkdownTextLine(text.value) || '未命名小记').slice(0, 80),
        content: rich, blocks: legacyProjection(rich), expectedRev: baseRev, tags: [...tags.value],
      })
      data.applyRemoteDoc(saved)
      savedId = saved.id
    } else {
      savedId = await data.addNote(text.value, generatedTitle.value, asCopy ? copyId : id.value, rich, [...tags.value])
    }
    if (!savedId) throw new Error('正文为空，请先写点内容')
    try {
      const latest = JSON.parse(localStorage.getItem(draftKey) || 'null') as Draft | null
      // 页面切走又回来时，新组件可能已经继续输入，旧请求不能清掉较新的草稿。
      if (!latest || (latest.id === id.value && latest.text === text.value
        && JSON.stringify(latest.content) === JSON.stringify(content.value)
        && JSON.stringify(latest.tags ?? []) === JSON.stringify(tags.value)
        && (latest.generatedTitle ?? '') === generatedTitle.value
        && (latest.autoPolish === true) === autoPolish.value
        && (latest.autoTitle === true) === autoTitle.value
        && (latest.autoImage === true) === autoImage.value)) localStorage.removeItem(draftKey)
    } catch { /* 服务端保存已成功。 */ }
    if (!props.note || asCopy) {
      const saved = data.docById(savedId)
      if (saved?.rev && (autoTitle.value || autoImage.value || autoPolish.value)) startAutoNoteEnrichment(session.currentUser.id, savedId, saved.rev, {
        title: !!generatedTitle.value,
        titleEnabled: autoTitle.value,
        image: rich.markdown.startsWith('![文章总览图]('),
        imageEnabled: autoImage.value,
        polishEnabled: autoPolish.value,
      })
    }
    if (!props.note) {
      resetting = true
      text.value = ''
      content.value = undefined
      tags.value = []
      generatedTitle.value = ''
      autoPolish.value = false
      autoImage.value = false
      autoTitle.value = false
      id.value = crypto.randomUUID().replace(/-/g, '')
      resetting = false
      savedSnapshot.value = { text: '', document: null, tags: [], title: '' }
    } else {
      savedSnapshot.value = {
        text: text.value,
        document: content.value?.document ?? null,
        tags: [...tags.value],
        title: generatedTitle.value,
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
    <div v-if="inline && note" class="inline-heading"><input v-model="generatedTitle" type="text" maxlength="80" aria-label="小记标题" :disabled="saving"><button v-for="tag in tags" :key="tag" class="tag-chip" type="button" :disabled="saving" :aria-label="`移除标签 ${tag}`" @click="toggleTag(tag)"># {{ tag }} <i class="ri-close-line"></i></button><button class="add-tag" type="button" :disabled="saving" :aria-expanded="showTagPicker" @click="showTagPicker = !showTagPicker"><i class="ri-price-tag-3-line"></i> 添加标签</button><button class="fullscreen-toggle" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏编辑'" :title="fullscreen ? '退出全屏（Esc）' : '全屏编辑'" :aria-pressed="fullscreen" @click="fullscreen = !fullscreen"><i :class="fullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i></button></div>
    <div class="editor-space" @keydown.ctrl.enter.prevent="save()" @keydown.meta.enter.prevent="save()">
      <div class="composer-editor-layout" :class="{ 'has-images': editorImages.length }"><div class="composer-editor-main">
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
            <button class="add-tag" type="button" :disabled="saving" :aria-expanded="showTagPicker" @click="showTagPicker = !showTagPicker"><i class="ri-price-tag-3-line"></i> 添加标签</button>
          </div>
        </template>
      </MarkdownEditor>
      </div><aside v-if="editorImages.length" class="composer-image-rail" aria-label="小记图片总览"><div class="rail-heading">图片总览 <span>{{ editorImages.length }} 张</span></div><div class="rail-images"><div v-for="(image, index) in editorImages" :key="`${index}:${image.src}`" class="rail-image-cell" :class="{ cover: image.alt === '文章总览图' }"><button type="button" class="rail-image" :aria-label="`查看图片：${image.alt || `第 ${index + 1} 张`}`" @click="openImage(image, $event)" @contextmenu="openImageMenu(image.src, $event)"><img :src="notePreviewSource(image.src)" :data-full-src="image.src" :alt="image.alt" loading="lazy" decoding="async"><span>{{ image.alt || `图片 ${index + 1}` }}</span></button><button type="button" class="rail-remove" :disabled="saving" :aria-label="`移除图片：${image.alt || `第 ${index + 1} 张`}`" title="移除图片，可撤销" @click="noteEditor?.removeImageAt(index)"><i class="ri-close-line" aria-hidden="true"></i></button></div></div></aside></div>
    </div>
    <div v-if="showTagPicker" class="tag-picker" aria-label="选择小记标签">
      <button v-for="tag in choices" :key="tag" type="button" :disabled="saving || (!tags.includes(tag) && tags.length >= 20)" :aria-pressed="tags.includes(tag)" @click="toggleTag(tag)"># {{ tag }}</button>
      <form class="new-tag-form" @submit.prevent="createTag">
        <input v-model="newTag" aria-label="编辑区新标签名称" placeholder="新标签名称" maxlength="32" :disabled="saving || creatingTag || tags.length >= 20">
        <button type="submit" :disabled="saving || creatingTag || !newTag.trim() || tags.length >= 20">{{ creatingTag ? '正在保存…' : tagError ? '重试新增' : '新增标签' }}</button>
        <span v-if="tagError" class="error" role="alert">{{ tagError }}</span>
      </form>
      <span v-if="tags.length >= 20">每篇最多 20 个标签</span>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}。正文仍在，可原地重试。</p>
    <div v-if="!note" class="auto-enrichment-choices" role="group" aria-label="保存后自动生成选项">
      <label><input v-model="autoTitle" type="checkbox" :disabled="saving">保存后自动生成标题</label>
      <label><input v-model="autoPolish" type="checkbox" :disabled="saving">保存后自动润色正文</label>
      <label><input v-model="autoImage" type="checkbox" :disabled="saving">保存后自动生成图片</label>
    </div>
    <div class="composer-footer">
      <span role="status" :class="{ error: draftError }">{{ saving ? '正在保存到服务器…' : status || 'Ctrl / ⌘ + Enter 保存' }}</span>
      <button v-if="note && conflicted" type="button" :disabled="!canSave" @click="save(true)">保留为新小记</button>
      <button v-if="note" type="button" :disabled="saving" @click="emit('close')">收起编辑</button>
      <button class="save-note" type="button" :disabled="!canSave" @click="save()">{{ saving ? '正在保存…' : error ? '重试保存' : note ? '保存修改' : '记下来了' }}</button>
    </div>
    <ImageCopyMenu v-if="imageMenu" v-bind="imageMenu" @close="imageMenu = null" />
    <Teleport to="body"><div v-if="previewImage" ref="lightbox" class="composer-image-lightbox" role="dialog" aria-modal="true" :aria-label="previewImage.alt || '小记图片预览'" tabindex="-1" @click.self="closeImage" @keydown.esc.stop="closeImage"><button type="button" aria-label="关闭图片预览" @click="closeImage"><i class="ri-close-line"></i></button><img :src="previewImage.src" :alt="previewImage.alt" @contextmenu="openImageMenu(previewImage.src, $event)"></div></Teleport>
  </section>
</template>

<style scoped>
.note-composer { position: relative; display: flex; flex-direction: column; min-width: 0; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 14px; padding: 18px; background: var(--wao-bg-card); }
.note-composer.inline:not(.fullscreen) { padding: 0; border: 0; border-radius: 0; }
.inline:not(.fullscreen) .editor-space { min-height: 160px; max-height: 460px; }
.inline .composer-heading { flex-wrap: wrap; }
.note-composer:focus-within { border-color: var(--wao-primary); }
.note-composer:not(:focus-within) .auto-enrichment-choices { max-height: 0; margin-top: 0; overflow: hidden; opacity: 0; visibility: hidden; pointer-events: none; }
.auto-enrichment-choices { max-height: 48px; transition: max-height .18s ease, margin .18s ease, opacity .16s ease, visibility .16s; }
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
.composer-editor-layout.has-images { display: grid; grid-template-columns: minmax(0, 1fr) 140px; gap: 14px; }
.composer-editor-main { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; }
.composer-editor-layout.has-images .composer-editor-main :deep(img.md-img) { display: none !important; }
.composer-image-rail { min-width: 0; padding-left: 12px; border-left: 1px solid var(--wao-line); }
.rail-heading { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 8px; color: var(--wao-text-2); font-size: 11px; }
.rail-heading span { color: var(--wao-text-faint); }
.rail-images { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.rail-image-cell { position: relative; min-width: 0; }
.rail-image-cell.cover { grid-column: 1 / -1; }
.rail-image { width: 100%; min-width: 0; padding: 3px; border: 1px solid var(--wao-line-control); border-radius: 7px; overflow: hidden; text-align: left; }
.rail-remove { position: absolute; top: 3px; right: 3px; width: 22px; height: 22px; padding: 1px; border-radius: 5px; background: var(--wao-bg-card); opacity: 0; }
.rail-image-cell:hover .rail-remove, .rail-image-cell:focus-within .rail-remove { opacity: 1; }
.rail-remove:disabled { opacity: .5; }
.rail-image img { display: block; width: 100%; height: 62px; object-fit: contain; }
.rail-image-cell.cover img { height: 94px; }
.rail-image span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }
.composer-image-lightbox { position: fixed; inset: 0; z-index: 3000; display: grid; place-items: center; padding: 32px; background: rgba(15, 18, 28, .82); }
.composer-image-lightbox img { max-width: min(96vw, 1600px); max-height: 90vh; object-fit: contain; }
.composer-image-lightbox button { position: fixed; top: 16px; right: 18px; color: white; background: rgba(255,255,255,.16); }
.composer-footer > span { color: var(--wao-text-faint); font-size: 12px; }
.auto-enrichment-choices { display:flex; align-items:center; flex-wrap:wrap; gap:8px 16px; margin-top:10px; color:var(--wao-text-3); font-size:12px; }
.auto-enrichment-choices label { display:inline-flex; align-items:center; gap:6px; cursor:pointer; }
.auto-enrichment-choices input { accent-color:var(--wao-primary); }
.composer-footer { flex-shrink: 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.composer-footer > span { flex: 1; min-width: 140px; }
button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); color: var(--wao-text-2); border-radius: 8px; padding: 8px 13px; cursor: pointer; }
.save-note { background: var(--wao-primary); border-color: var(--wao-primary); color: white; font-weight: 600; }
button:disabled { opacity: .5; cursor: default; }
.error { color: var(--wao-danger-strong) !important; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 600px) { .note-composer { padding: 12px; } .composer-heading { flex-wrap: wrap; } .composer-heading span { width: 100%; } .fullscreen-reminder { top:49px; right:8px; width:calc(100% - 16px); gap:7px 8px; padding:10px 11px; } .fullscreen-reminder::before { right:15px; } .fullscreen-reminder-copy strong { font-size:12px; } .fullscreen-reminder-copy span { font-size:11px; } .fullscreen-reminder-actions { gap:4px; } .fullscreen-reminder-actions button { padding:5px 6px; font-size:11px; } }
@media (max-width: 700px) { .composer-editor-layout.has-images { grid-template-columns: 1fr; } .composer-image-rail { border-left: 0; border-top: 1px solid var(--wao-line); padding: 10px 0 0; } .rail-images { grid-template-columns: repeat(3, minmax(0, 1fr)); } .rail-image-cell.cover { grid-column: auto; } .rail-image-cell.cover img { height: 62px; } }
@media (hover: none) { .rail-remove { opacity: 1; } }
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
