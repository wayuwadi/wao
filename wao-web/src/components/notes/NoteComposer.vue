<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { apiCreateNoteTag, apiPatchDoc } from '@/api/board'
import { legacyProjection, resolvedRichContent } from '@/utils/richDoc'
import { firstMarkdownTextLine } from '@/utils/markdownTitle'
import type { Doc, FileItem, RichDocContent } from '@/types'

const props = defineProps<{ note?: Doc; loading?: boolean; tagOptions?: string[]; inline?: boolean; autofocus?: boolean }>()
const emit = defineEmits<{ saved: [id: string]; close: []; 'tag-created': [name: string] }>()
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const data = useDataStore()
const session = useSessionStore()
const draftKey = props.note
  ? `wao-note-edit-v1:${session.currentUser.id}:${props.note.id}`
  : `wao-quick-note-draft-v1:${session.currentUser.id || 'anonymous'}`
type Draft = { id: string; text: string; content?: RichDocContent; baseRev?: number; tags?: string[] }
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
const composerElement = ref<HTMLElement>()

let previousOverflow: string | undefined
watch(fullscreen, value => {
  if (value) { previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
  else if (previousOverflow !== undefined) { document.body.style.overflow = previousOverflow; previousOverflow = undefined }
})
onBeforeUnmount(() => { if (previousOverflow !== undefined) document.body.style.overflow = previousOverflow })
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
const taskSource = computed(() => ({
  id: props.note?.id ?? id.value,
  title: props.note?.title || '未保存小记',
  url: props.note ? `/kb/${encodeURIComponent(props.note.kbId ?? '')}?doc=${encodeURIComponent(props.note.id)}` : undefined,
  docId: props.note?.id,
}))
const text = ref(initial.text)
const content = ref<RichDocContent | undefined>(initial.content)
// 恢复草稿必须沿用开始编辑时的版本，防止跨设备更新被旧草稿覆盖。
const baseRev = initial.baseRev ?? props.note?.rev ?? 1
const saving = ref(false)
const error = ref('')
const conflicted = computed(() => /其他窗口更新|版本冲突/.test(error.value))
const copyId = crypto.randomUUID().replace(/-/g, '')
const draftError = ref(false)
const status = ref(restored ? '已恢复草稿，可继续记录' : '')
let resetting = false
const canSave = computed(() => !!text.value.trim() && !saving.value && !creatingTag.value && !props.loading)
watch([text, content, tags], () => {
  if (resetting) return
  error.value = ''
  try {
    localStorage.setItem(draftKey, JSON.stringify({ id: id.value, text: text.value, content: content.value, baseRev, tags: tags.value } satisfies Draft))
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
        title: (firstMarkdownTextLine(text.value) || '未命名小记').slice(0, 80),
        content: rich, blocks: legacyProjection(rich), expectedRev: baseRev, tags: [...tags.value],
      })
      data.applyRemoteDoc(saved)
      savedId = saved.id
    } else {
      savedId = await data.addNote(text.value, '', asCopy ? copyId : id.value, rich, [...tags.value])
    }
    if (!savedId) throw new Error('正文为空，请先写点内容')
    try {
      const latest = JSON.parse(localStorage.getItem(draftKey) || 'null') as Draft | null
      // 页面切走又回来时，新组件可能已经继续输入，旧请求不能清掉较新的草稿。
      if (!latest || (latest.id === id.value && latest.text === text.value
        && JSON.stringify(latest.content) === JSON.stringify(content.value)
        && JSON.stringify(latest.tags ?? []) === JSON.stringify(tags.value))) localStorage.removeItem(draftKey)
    } catch { /* 服务端保存已成功。 */ }
    if (!props.note) {
      resetting = true
      text.value = ''
      content.value = undefined
      tags.value = []
      id.value = crypto.randomUUID().replace(/-/g, '')
      resetting = false
    }
    status.value = '已保存到服务器，清单已更新'
    emit('saved', savedId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

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
  <section ref="composerElement" class="note-composer" :class="{ fullscreen, inline }" @keydown="editorKeys" :aria-label="note ? '编辑小记' : '记录小记'">
    <div v-if="!inline" class="composer-heading"><i class="ri-quill-pen-line"></i><b>{{ note ? (inline ? '编辑这条小记' : '继续编辑') : '此刻，想记点什么？' }}</b><button class="fullscreen-toggle" type="button" :aria-label="fullscreen ? '退出全屏' : '全屏编辑'" :title="fullscreen ? '退出全屏（Esc）' : '全屏编辑'" :aria-pressed="fullscreen" @click="fullscreen = !fullscreen"><i :class="fullscreen ? 'ri-fullscreen-exit-line' : 'ri-fullscreen-line'"></i></button></div>
    <div class="editor-space" @keydown.ctrl.enter.prevent="save()" @keydown.meta.enter.prevent="save()">
      <MarkdownEditor :key="id" v-model="text" :document="content?.document" bare advanced allow-h1 polishable link-controls image-controls
        :article-image-key="`note:${session.currentUser.id}:${id}`"
        :hide-toolbar="inline" :autofocus="autofocus" :menu-z-index="fullscreen ? 2200 : 1000"
        :task-source="taskSource" @task-opened="fullscreen = false"
        :readonly="saving" :min-height="note ? 320 : 360" placeholder="想法、灵感、工作备忘…随手记在这里，也可以粘贴图片"
        @content-change="content = $event" @upload-image="uploadImage" @upload-file="uploadFile" />
    </div>
    <div class="composer-tags">
      <button v-for="tag in tags" :key="tag" class="tag-chip" type="button" :disabled="saving" :aria-label="`移除标签 ${tag}`" @click="toggleTag(tag)"># {{ tag }} <i class="ri-close-line"></i></button>
      <button class="add-tag" type="button" :disabled="saving" :aria-expanded="showTagPicker" @click="showTagPicker = !showTagPicker"><i class="ri-price-tag-3-line"></i> 添加标签</button>
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
    <div class="composer-footer">
      <span role="status" :class="{ error: draftError }">{{ saving ? '正在保存到服务器…' : status || 'Ctrl / ⌘ + Enter 保存' }}</span>
      <button v-if="note && conflicted" type="button" :disabled="!canSave" @click="save(true)">保留为新小记</button>
      <button v-if="note" type="button" :disabled="saving" @click="emit('close')">收起编辑</button>
      <button class="save-note" type="button" :disabled="!canSave" @click="save()">{{ saving ? '正在保存…' : error ? '重试保存' : note ? '保存修改' : '记下来了' }}</button>
    </div>
  </section>
</template>

<style scoped>
.note-composer { display: flex; flex-direction: column; min-width: 0; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 14px; padding: 18px; background: var(--wao-bg-card); }
.note-composer.inline:not(.fullscreen) { padding: 0; border: 0; border-radius: 0; }
.inline:not(.fullscreen) .editor-space { min-height: 160px; max-height: 460px; }
.inline .composer-heading { flex-wrap: wrap; }
.note-composer:focus-within { border-color: var(--wao-primary); }
.composer-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.composer-heading i { color: var(--wao-primary-text); font-size: 20px; }
.composer-footer > span { color: var(--wao-text-faint); font-size: 12px; }
.composer-footer { flex-shrink: 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.composer-footer > span { flex: 1; min-width: 140px; }
button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); color: var(--wao-text-2); border-radius: 8px; padding: 8px 13px; cursor: pointer; }
.save-note { background: var(--wao-primary); border-color: var(--wao-primary); color: white; font-weight: 600; }
button:disabled { opacity: .5; cursor: default; }
.error { color: var(--wao-danger-strong) !important; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 600px) { .note-composer { padding: 12px; } .composer-heading { flex-wrap: wrap; } .composer-heading span { width: 100%; } }
.composer-footer > button { padding: 4px 9px; min-height: 28px; font-size: 12px; border-radius: 6px; }
</style>

<style scoped>
.composer-heading b { font-size: 15px; }
.fullscreen-toggle { margin-left: auto; padding: 5px 7px; border: 0; }
.fullscreen-toggle i { font-size: 17px; color: var(--wao-text-3); }
.editor-space { flex: 1; min-height: 0; display: flex; }
.editor-space :deep(.md) { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.editor-space :deep(.md-editor-wrap) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.editor-space :deep(.md-body) { flex: 1; min-height: 0 !important; overflow: auto; display: flex; flex-direction: column; }
.editor-space :deep(.tiptap) { flex: 1; cursor: text; }
.editor-space :deep(.image-upload) { position: static; align-self: flex-end; flex-shrink: 0; margin-top: 6px; }
.new-tag-form { display: flex; flex-wrap: wrap; gap: 7px; width: 100%; }
.new-tag-form input { min-width: 0; flex: 1; border: 1px solid var(--wao-line-control); border-radius: 6px; padding: 6px; background: var(--wao-bg-card); color: var(--wao-text-2); }
.new-tag-form .error { width: 100%; }
.editor-space :deep(.md-body) { overflow-wrap: anywhere; }
.composer-tags, .tag-picker { display: flex; gap: 7px; flex-wrap: wrap; margin-top: 12px; }
.composer-tags button, .tag-picker button { padding: 5px 8px; font-size: 12px; max-width: 100%; overflow-wrap: anywhere; }
.tag-chip, .tag-picker button[aria-pressed="true"] { color: var(--wao-primary-text); background: var(--wao-bg-hover); }
.tag-picker { padding: 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; max-height: 150px; overflow: auto; }
.tag-picker span { color: var(--wao-text-faint); font-size: 12px; }
.note-composer.fullscreen { position: fixed; inset: 0; z-index: 2100; border-radius: 0; padding: 24px max(24px, calc((100vw - 1100px) / 2)); height: 100dvh; }

@media (max-width: 600px) { .note-composer.fullscreen { padding: 12px; } }
.composer-footer > button { padding: 4px 9px; min-height: 28px; font-size: 12px; border-radius: 6px; }
</style>
