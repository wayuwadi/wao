<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchArticleImage, startArticleImage } from '@/api/articleImages'
import { newPolishRequestId } from '@/api/polishJobs'

const props = defineProps<{ scopeKey: string; insertImage: (url: string) => boolean }>()
const SELECTED_IMAGE_PROMPT = '请只根据选中的文字生成一张相关图片，呈现其中的场景或核心关系。画面简洁清楚，16:9 横版，不添加原文没有的事实、数字、人物或结论，不加水印。'
type PendingImage = { key: string; id: string; source: string; requestId?: string; url?: string; error?: string; busy?: boolean }
const jobs = ref<PendingImage[]>([])
const selected = ref('')
const pending = computed(() => jobs.value.find(job => job.key === selected.value) ?? null)
const openDialog = ref(false)
const tokens = new Map<string, number>()
const busy = computed(() => pending.value?.busy ?? false)
const error = computed(() => pending.value?.error ?? '')
function storageKey() { return `wao-note-selection-image-v1:${props.scopeKey}` }
function remember() {
  try {
    if (jobs.value.length) localStorage.setItem(storageKey(), JSON.stringify(jobs.value.map(({ key, id, source, requestId, url, error }) => ({ key, id, source, requestId, url, error }))))
    else localStorage.removeItem(storageKey())
  } catch { /* 草稿仍留在当前页面。 */ }
}
function restore() {
  try {
    const stored = JSON.parse(localStorage.getItem(storageKey()) || 'null')
    const values: Partial<PendingImage>[] = Array.isArray(stored) ? stored : stored ? [stored] : []
    jobs.value = values.filter(value => typeof value.source === 'string' && typeof value.id === 'string' &&
      (!value.id || /^(?:ac[a-f0-9]{24}|[a-f0-9]{32})$/i.test(value.id))).map(value => ({ ...value, key: value.key || newPolishRequestId(), source: value.source!, id: value.id!, busy: false }))
    selected.value = jobs.value[0]?.key ?? ''
    openDialog.value = jobs.value.length > 0
    for (const job of jobs.value) if (job.id && !job.url) void waitForImage(job)
  } catch { /* 无可恢复任务。 */ }
}
function open(source: string) {
  const text = source.trim().slice(0, 60_000)
  let job = jobs.value.find(item => item.source === text && !item.error)
  if (!job) { job = { key: newPolishRequestId(), id: '', source: text }; jobs.value.push(job) }
  selected.value = job.key
  openDialog.value = true
}
async function waitForImage(job: PendingImage) {
  const token = (tokens.get(job.key) ?? 0) + 1
  tokens.set(job.key, token); job.busy = true; job.error = ''
  try {
    const deadline = Date.now() + 35 * 60_000
    while (tokens.get(job.key) === token && Date.now() < deadline) {
      const result = await fetchArticleImage(job.id)
      if (tokens.get(job.key) !== token) return
      if (result.status === 'FAILED') {
        job.id = ''; job.requestId = undefined
        throw new Error(result.message || '生图失败')
      }
      if (result.status === 'SUCCEEDED') {
        if (!result.imageUrl) throw new Error('图片已生成，但未返回图片地址；请重试查询')
        job.url = result.imageUrl; remember(); return
      }
      await new Promise(resolve => setTimeout(resolve, 1800))
    }
    if (tokens.get(job.key) === token) job.error = '仍在生成中，稍后点击继续查询'
  } catch (cause) {
    if (tokens.get(job.key) === token) { job.error = cause instanceof Error ? cause.message : '生图失败，请重试'; remember() }
  } finally { if (tokens.get(job.key) === token) job.busy = false }
}
async function generate() {
  const job = pending.value
  if (!job || job.busy) return
  if (!job.source) { job.error = '请先选中文字'; return }
  if (job.id) { void waitForImage(job); return }
  job.error = ''; job.busy = true
  job.requestId ||= newPolishRequestId(); remember()
  const token = (tokens.get(job.key) ?? 0) + 1; tokens.set(job.key, token)
  try {
    const result = await startArticleImage(job.source, SELECTED_IMAGE_PROMPT, undefined, job.requestId)
    if (tokens.get(job.key) !== token) return
    job.id = result.id; remember()
    await waitForImage(job)
  } catch (cause) {
    if (tokens.get(job.key) === token) { job.error = cause instanceof Error ? cause.message : '生图失败，请重试'; remember() }
  } finally { if (!job.id) job.busy = false }
}
function removeCurrent() {
  const job = pending.value
  if (!job) return
  tokens.delete(job.key)
  jobs.value = jobs.value.filter(item => item.key !== job.key)
  selected.value = jobs.value[0]?.key ?? ''; remember()
  openDialog.value = jobs.value.length > 0
}
function insert() {
  const job = pending.value
  if (!job?.url) return
  if (!props.insertImage(job.url)) { job.error = '图片已生成，但编辑器暂不可用；请重试加入'; return }
  removeCurrent()
}
function discard() { removeCurrent() }
defineExpose({ open })
onMounted(restore)
onBeforeUnmount(() => tokens.clear())
</script>

<template>
  <button v-if="pending && !openDialog" type="button" class="resume-image" @click="openDialog = true">
    <i class="ri-image-line" aria-hidden="true"></i>选段图片 · {{ jobs.length }} 项
  </button>
  <Teleport to="body">
    <div v-if="openDialog && pending" class="selection-image-mask" @click.self="openDialog = false">
      <section class="selection-image-dialog" role="dialog" aria-modal="true" aria-label="选段生图">
        <header><h2>根据选中文字生图</h2><button type="button" aria-label="收起选段生图" @click="openDialog = false"><i class="ri-close-line"></i></button></header>
        <nav v-if="jobs.length > 1" class="image-jobs" aria-label="选段图片任务">
          <button v-for="(job, index) in jobs" :key="job.key" type="button" :aria-pressed="selected === job.key" @click="selected = job.key">
            图片 {{ index + 1 }} · {{ job.url ? '待加入' : job.busy ? '生成中' : job.error ? '待重试' : '待生成' }}
          </button>
        </nav>
        <blockquote>{{ pending.source }}</blockquote>
        <p v-if="busy" role="status">图片正在生成。收起后可以继续编辑、选择另一段文字同时生图；各项结果分别保留。</p>
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
.image-jobs { display: flex; gap: 6px; overflow-x: auto; padding: 12px 0 2px; }
.image-jobs button { flex: 0 0 auto; font-size: 12px; }
.image-jobs button[aria-pressed="true"] { border-color: var(--wao-primary); color: var(--wao-primary-text); background: var(--wao-primary-soft); }
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
