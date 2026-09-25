<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchArticleImage, startArticleImage } from '@/api/articleImages'

const props = defineProps<{ scopeKey: string; insertImage: (url: string) => boolean }>()
const SELECTED_IMAGE_PROMPT = '请只根据选中的文字生成一张相关图片，呈现其中的场景或核心关系。画面简洁清楚，16:9 横版，不添加原文没有的事实、数字、人物或结论，不加水印。'
type PendingImage = { id: string; source: string; url?: string }
const pending = ref<PendingImage | null>(null)
const openDialog = ref(false)
const busy = ref(false)
const error = ref('')
let generation = 0

function storageKey() { return `wao-note-selection-image-v1:${props.scopeKey}` }
function remember(value: PendingImage | null) {
  try {
    if (value) localStorage.setItem(storageKey(), JSON.stringify(value))
    else localStorage.removeItem(storageKey())
  } catch { /* 草稿仍留在当前页面。 */ }
  pending.value = value
}
function restore() {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey()) || 'null') as PendingImage | null
    if (value && /^ac[a-f0-9]{24}$/i.test(value.id) && typeof value.source === 'string') {
      pending.value = value
      openDialog.value = true
      if (!value.url) void waitForImage(value.id, ++generation)
    }
  } catch { /* 无可恢复任务。 */ }
}
function open(source: string) {
  if (pending.value) { openDialog.value = true; return }
  pending.value = { id: '', source: source.trim().slice(0, 60_000) }
  error.value = ''
  openDialog.value = true
}
async function waitForImage(id: string, current: number) {
  busy.value = true
  try {
    const deadline = Date.now() + 35 * 60_000
    while (current === generation && Date.now() < deadline) {
      const result = await fetchArticleImage(id)
      if (current !== generation) return
      if (result.status === 'FAILED') {
        const source = pending.value?.source || ''
        remember(null)
        pending.value = { id: '', source }
        throw new Error(result.message || '生图失败')
      }
      if (result.status === 'SUCCEEDED') {
        if (!result.imageUrl) throw new Error('图片已生成，但未返回图片地址；请重试查询')
        const value = pending.value
        if (value) remember({ ...value, url: result.imageUrl })
        return
      }
      await new Promise(resolve => setTimeout(resolve, 1800))
    }
    if (current === generation) error.value = '仍在生成中，稍后点击继续查询'
  } catch (cause) {
    if (current === generation) error.value = cause instanceof Error ? cause.message : '生图失败，请重试'
  } finally { if (current === generation) busy.value = false }
}
async function generate() {
  const current = pending.value
  if (!current || busy.value) return
  if (!current.source) { error.value = '请先选中文字'; return }
  error.value = ''
  if (current.id) { void waitForImage(current.id, ++generation); return }
  busy.value = true
  const token = ++generation
  try {
    const job = await startArticleImage(current.source, SELECTED_IMAGE_PROMPT)
    if (token !== generation) return
    remember({ ...current, id: job.id })
    await waitForImage(job.id, token)
  } catch (cause) {
    if (token === generation) error.value = cause instanceof Error ? cause.message : '生图失败，请重试'
  } finally { if (token === generation && !pending.value?.id) busy.value = false }
}
function insert() {
  const url = pending.value?.url
  if (!url) return
  if (!props.insertImage(url)) { error.value = '图片已生成，但编辑器暂不可用；请重试加入'; return }
  remember(null)
  openDialog.value = false
  error.value = ''
}
function discard() { ++generation; remember(null); openDialog.value = false; busy.value = false; error.value = '' }
defineExpose({ open })
onMounted(restore)
onBeforeUnmount(() => { ++generation })
</script>

<template>
  <button v-if="pending && !openDialog" type="button" class="resume-image" @click="openDialog = true">
    <i class="ri-image-line" aria-hidden="true"></i>{{ pending.url ? '图片待加入小记' : pending.id ? '选段生图中 · 查看' : '继续选段生图' }}
  </button>
  <Teleport to="body">
    <div v-if="openDialog && pending" class="selection-image-mask" @click.self="openDialog = false">
      <section class="selection-image-dialog" role="dialog" aria-modal="true" aria-label="选段生图">
        <header><h2>根据选中文字生图</h2><button type="button" aria-label="收起选段生图" @click="openDialog = false"><i class="ri-close-line"></i></button></header>
        <blockquote>{{ pending.source }}</blockquote>
        <p v-if="busy" role="status">图片正在生成，你可以继续编辑；返回后会查询原任务。</p>
        <img v-if="pending.url" :src="pending.url" alt="选段生成图片预览">
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <footer>
          <button v-if="!busy" type="button" @click="discard">{{ pending.url ? '舍弃图片' : '取消' }}</button>
          <button v-if="pending.url" type="button" class="primary" @click="insert">加入小记</button>
          <button v-else-if="!busy" type="button" class="primary" @click="generate">{{ pending.id ? '继续查询' : error ? '重试生图' : '开始生成' }}</button>
          <button v-else type="button" @click="openDialog = false">收起</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.resume-image { margin: 4px 0; padding: 5px 8px; border: 1px solid var(--wao-line-control); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-primary-text); font-size: 11px; cursor: pointer; }
.selection-image-mask { position: fixed; inset: 0; z-index: 3000; display: grid; place-items: center; padding: 16px; background: rgba(12, 22, 45, .48); }
.selection-image-dialog { width: min(560px, 100%); max-height: min(90vh, 760px); overflow: auto; padding: 20px; border-radius: 12px; background: var(--wao-bg-card); color: var(--wao-text-1); box-shadow: 0 18px 55px rgba(0,0,0,.24); }
.selection-image-dialog header, .selection-image-dialog footer { display: flex; align-items: center; gap: 8px; }
.selection-image-dialog header { justify-content: space-between; }
.selection-image-dialog h2 { margin: 0; font-size: 17px; }
.selection-image-dialog blockquote { margin: 14px 0; padding: 10px 12px; border-left: 3px solid var(--wao-primary); background: var(--wao-bg-sub); font-size: 13px; line-height: 1.6; max-height: 115px; overflow: auto; }
.selection-image-dialog p { color: var(--wao-text-weak); font-size: 13px; }
.selection-image-dialog .error { color: var(--wao-danger-strong); }
.selection-image-dialog img { display: block; max-width: 100%; max-height: 50vh; margin: 12px auto; object-fit: contain; }
.selection-image-dialog footer { justify-content: flex-end; flex-wrap: wrap; margin-top: 14px; }
.selection-image-dialog button { border: 1px solid var(--wao-divider); border-radius: 7px; padding: 6px 10px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.selection-image-dialog header button { border: 0; }
.selection-image-dialog button.primary { border-color: var(--wao-primary); background: var(--wao-primary); color: white; }
</style>
