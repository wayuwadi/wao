<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { apiPatchDoc } from '@/api/board'
import { legacyProjection, resolvedRichContent } from '@/utils/richDoc'
import type { Doc, RichDocContent } from '@/types'

const props = defineProps<{ note?: Doc; loading?: boolean }>()
const emit = defineEmits<{ saved: [id: string]; close: [] }>()
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const data = useDataStore()
const session = useSessionStore()
const draftKey = props.note
  ? `wao-note-edit-v1:${session.currentUser.id}:${props.note.id}`
  : `wao-quick-note-draft-v1:${session.currentUser.id || 'anonymous'}`
type Draft = { id: string; text: string; content?: RichDocContent; baseRev?: number }
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
  return { id: props.note?.id ?? crypto.randomUUID().replace(/-/g, ''), text: content?.markdown ?? '', content, baseRev: props.note?.rev }
}
const initial = readDraft()
const id = ref(initial.id)
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
const canSave = computed(() => !!text.value.trim() && !saving.value && !props.loading)
watch([text, content], () => {
  if (resetting) return
  error.value = ''
  try {
    localStorage.setItem(draftKey, JSON.stringify({ id: id.value, text: text.value, content: content.value, baseRev } satisfies Draft))
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
        title: text.value.trim().split('\n')[0]!.replace(/^#+\s*/, '').slice(0, 80),
        content: rich, blocks: legacyProjection(rich), expectedRev: baseRev,
      })
      data.applyRemoteDoc(saved)
      savedId = saved.id
    } else {
      savedId = await data.addNote(text.value, '', asCopy ? copyId : id.value, rich)
    }
    if (!savedId) throw new Error('正文为空，请先写点内容')
    try {
      const latest = JSON.parse(localStorage.getItem(draftKey) || 'null') as Draft | null
      // 页面切走又回来时，新组件可能已经继续输入，旧请求不能清掉较新的草稿。
      if (!latest || (latest.id === id.value && latest.text === text.value
        && JSON.stringify(latest.content) === JSON.stringify(content.value))) localStorage.removeItem(draftKey)
    } catch { /* 服务端保存已成功。 */ }
    if (!props.note) {
      resetting = true
      text.value = ''
      content.value = undefined
      id.value = crypto.randomUUID().replace(/-/g, '')
      resetting = false
    }
    status.value = '已保存到服务器，记录就在下方'
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
    const image = await data.uploadImage(file, '小记')
    if (!image) throw new Error('图片上传失败，请重试')
    done(image.url, image.name)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片上传失败，请重试'
    done(null, file.name)
  }
}
</script>

<template>
  <section class="note-composer" :aria-label="note ? '编辑小记' : '记录小记'">
    <div class="composer-heading"><i class="ri-quill-pen-line"></i><b>{{ note ? '继续编辑' : '此刻，想记点什么？' }}</b><span v-if="!note">不用标题，直接开始</span></div>
    <div @keydown.ctrl.enter.prevent="save()" @keydown.meta.enter.prevent="save()">
      <MarkdownEditor :key="id" v-model="text" :document="content?.document" advanced compact
        :readonly="saving" :min-height="120" placeholder="想法、灵感、工作备忘…随手记在这里，也可以粘贴图片"
        @content-change="content = $event" @upload-image="uploadImage" />
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
.note-composer { border: 1px solid var(--wao-line-control); border-radius: 14px; padding: 18px; background: var(--wao-bg-card); }
.note-composer:focus-within { border-color: var(--wao-primary); }
.composer-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.composer-heading i { color: var(--wao-primary); font-size: 20px; }
.composer-heading span, .composer-footer > span { color: var(--wao-text-faint); font-size: 12px; }
.composer-footer { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.composer-footer > span { flex: 1; min-width: 140px; }
button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); color: var(--wao-text-2); border-radius: 8px; padding: 8px 13px; cursor: pointer; }
.save-note { background: var(--wao-primary); border-color: var(--wao-primary); color: white; font-weight: 600; }
button:disabled { opacity: .5; cursor: default; }
.error { color: var(--wao-danger-strong) !important; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 600px) { .note-composer { padding: 12px; } .composer-heading { flex-wrap: wrap; } .composer-heading span { width: 100%; } }
</style>
