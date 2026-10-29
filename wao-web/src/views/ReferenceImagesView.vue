<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { createAssistantConversation, fetchAssistantStatus, streamChat } from '@/api/assistant'
import { listReferenceImages, uploadReferenceImage, type ReferenceImage } from '@/api/referenceImages'

const session = useSessionStore()
const images = ref<ReferenceImage[]>([])
const selected = ref<string[]>([])
const selectionError = ref('')
const search = ref('')
const prompt = ref('')
const loading = ref(true)
const loadError = ref('')
const dropActive = ref(false)
const uploading = ref(false)
const failedFiles = ref<Array<{ file: File; reason: string }>>([])
const sending = ref(false)
const sendError = ref('')
const answer = ref('')
const conversationId = ref('')
const input = ref<HTMLInputElement | null>(null)
const defaultPrompt = '请参考这些图片，先说出可借鉴的视觉与结构，再结合我的需求给出方案；图片没有展示的交互和数据请标明待确认。'
const draftKey = computed(() => `wao-reference-prompt-v1:${session.userId || 'guest'}`)
const visible = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  return keyword ? images.value.filter(item => item.name.toLocaleLowerCase().includes(keyword)) : images.value
})
const selectedImages = computed(() => selected.value.map(id => images.value.find(image => image.id === id)).filter((image): image is ReferenceImage => !!image))

function errorMessage(cause: unknown, fallback: string) {
  if (cause && typeof cause === 'object' && 'response' in cause) {
    const response = (cause as { response?: { data?: { message?: string } } }).response
    if (response?.data?.message) return response.data.message
  }
  return cause instanceof Error ? cause.message : fallback
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    images.value = await listReferenceImages()
    selected.value = selected.value.filter(id => images.value.some(image => image.id === id))
  } catch (cause) { loadError.value = errorMessage(cause, '加载失败，请重试') }
  finally { loading.value = false }
}

async function upload(files: File[]) {
  const pictures = files.filter(file => file.type.startsWith('image/'))
  if (!pictures.length) return
  uploading.value = true
  for (const file of pictures) {
    try {
      const saved = await uploadReferenceImage(file)
      images.value = [saved, ...images.value.filter(image => image.id !== saved.id)]
      if (selected.value.length < 4 && !selected.value.includes(saved.id)) selected.value.push(saved.id)
      failedFiles.value = failedFiles.value.filter(item => item.file !== file)
    } catch (cause) {
      failedFiles.value.push({ file, reason: errorMessage(cause, '收图失败，请重试') })
    }
  }
  uploading.value = false
}

function chooseFiles(event: Event) {
  const element = event.target as HTMLInputElement
  void upload(Array.from(element.files ?? []))
  element.value = ''
}
function paste(event: ClipboardEvent) {
  if (!event.clipboardData) return
  const files = Array.from(event.clipboardData.files).filter(file => file.type.startsWith('image/'))
  if (files.length) { event.preventDefault(); void upload(files) }
}
function drop(event: DragEvent) {
  event.preventDefault()
  dropActive.value = false
  void upload(Array.from(event.dataTransfer?.files ?? []))
}
function toggle(id: string) {
  if (selected.value.includes(id)) {
    selected.value = selected.value.filter(item => item !== id)
    selectionError.value = ''
    return
  }
  if (selected.value.length < 4) { selected.value.push(id); selectionError.value = '' }
  else selectionError.value = '每次最多选择四张参考图，请先取消一张。'
}
let streamController: AbortController | undefined
onBeforeUnmount(() => streamController?.abort())

async function ask() {
  if (!selected.value.length || sending.value || uploading.value) return
  sending.value = true
  const controller = new AbortController()
  streamController = controller
  sendError.value = ''
  answer.value = ''
  const text = prompt.value.trim() || defaultPrompt
  const imageIds = [...selected.value]
  try {
    const status = await fetchAssistantStatus('local-codex')
    if (!status.configured) throw new Error(status.message)
    const conversation = await createAssistantConversation()
    conversationId.value = conversation.id
    for await (const token of streamChat(text, [], undefined, conversation.id, undefined,
      'local-codex', imageIds, controller.signal)) answer.value += token
    prompt.value = ''
    try { localStorage.removeItem(draftKey.value) } catch { /* 内存输入已清空 */ }
  } catch (cause) {
    if (controller.signal.aborted) return
    sendError.value = errorMessage(cause, '带图提问失败，请重试')
    // 保留当前选择与输入；失败会话可从 AI 对话历史重新打开。
  } finally { sending.value = false }
}
watch(prompt, value => {
  try { if (value) localStorage.setItem(draftKey.value, value); else localStorage.removeItem(draftKey.value) }
  catch { /* 本机存储不可用时仍保留当前页面输入 */ }
})
watch(() => session.userId, () => {
  selected.value = []
  images.value = []
  prompt.value = ''
  answer.value = ''
  conversationId.value = ''
  try { prompt.value = localStorage.getItem(draftKey.value) || '' } catch { /* 继续使用空草稿 */ }
  void load()
})
onMounted(() => {
  try { prompt.value = localStorage.getItem(draftKey.value) || '' } catch { /* 继续使用空草稿 */ }
  void load()
})
</script>

<template>
  <main class="reference-page wao-page-frame" @paste="paste">
    <header class="page-head">
      <div><div class="eyebrow">看见就收，想用就发</div><h1>参考图库</h1><p>把好图留给下一次灵感。选几张图，附一句需求，交给 AI 参考。</p></div>
      <button class="primary-button" type="button" :disabled="uploading" @click="input?.click()">{{ uploading ? '正在收图…' : '收进截图' }}</button>
    </header>
    <section class="capture" :class="{ dragging: dropActive }" aria-label="收集截图区域"
      @dragenter.prevent="dropActive = true" @dragover.prevent="dropActive = true"
      @dragleave.prevent="dropActive = false" @drop="drop">
      <i class="ri-image-add-line" aria-hidden="true"></i>
      <div><strong>粘贴或拖入截图</strong><span>也可以一次选择多张。收图时无需填写资料。</span></div>
      <button class="secondary-button" type="button" :disabled="uploading" @click="input?.click()">选择图片</button>
      <input ref="input" hidden type="file" accept="image/png,image/jpeg,image/gif,image/webp" multiple @change="chooseFiles">
    </section>
    <div v-if="failedFiles.length" class="failure" role="alert">
      <strong>{{ failedFiles.length }} 张图片未收下</strong>
      <div v-for="(item, index) in failedFiles" :key="index" class="failed-item">
        <span>{{ item.file.name }}：{{ item.reason }}</span>
        <button type="button" :disabled="uploading" @click="failedFiles.splice(index, 1); upload([item.file])">重试</button>
      </div>
    </div>
    <div class="library-head"><h2>我的参考图 <small v-if="!loading">{{ images.length }} 张</small></h2>
      <label class="search-label"><span class="sr-only">按文件名找图</span><input v-model="search" type="search" placeholder="按图片名称找图"></label>
    </div>
    <div v-if="loading" class="state">正在加载参考图…</div>
    <div v-else-if="loadError" class="state error" role="alert">{{ loadError }} <button type="button" @click="load">重试加载</button></div>
    <div v-else-if="!images.length" class="state">还没有参考图。粘贴一张截图，就能开始收集。</div>
    <div v-else-if="!visible.length" class="state">没有找到匹配的图片。<button type="button" @click="search = ''">清除搜索</button></div>
    <div v-else class="image-grid">
      <button v-for="image in visible" :key="image.id" type="button" class="image-card"
        :class="{ active: selected.includes(image.id) }" :aria-pressed="selected.includes(image.id)"
        :aria-label="`${selected.includes(image.id) ? '取消选择' : '选择'}参考图 ${image.name}`"
        @click="toggle(image.id)">
        <span class="preview"><img :src="image.url" :alt="image.name" loading="lazy"><b>{{ selected.includes(image.id) ? '✓' : '+' }}</b></span>
        <span class="image-name">{{ image.name }}</span>
      </button>
    </div>
    <section class="composer" aria-label="带图提问">
      <div v-if="selectionError" class="error" role="alert">{{ selectionError }}</div>
      <div class="selected-strip"><span v-if="!selectedImages.length">先点选参考图，最多四张</span>
        <span v-for="image in selectedImages" :key="image.id" class="selected-image"><img :src="image.url" :alt="image.name">{{ image.name }}</span></div>
      <div class="compose-row"><textarea v-model="prompt" :disabled="sending" aria-label="告诉 AI 想做什么"
        maxlength="4000"
        placeholder="例如：参考这些图，帮我设计 WAO 项目首页…"></textarea>
        <button class="primary-button" type="button" :disabled="!selected.length || sending || uploading" @click="ask">{{ sending ? 'AI 正在看图…' : '带图提问' }}</button></div>
      <p>不写文字也可以发送；AI 会收到原图与默认参考要求。</p>
    </section>
    <section v-if="sendError || answer" class="answer" aria-label="AI 回答">
      <h2>{{ sendError ? '提问未完成' : 'AI 的回答' }}</h2>
      <p v-if="sendError" class="error" role="alert">{{ sendError }} <button type="button" @click="ask">重试</button></p>
      <div v-if="answer" class="answer-text">{{ answer }}</div>
    </section>
  </main>
</template>

<style scoped>
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
.page-head { flex-wrap: wrap; }
.page-head > div { flex: 1 1 240px; min-width: 0; }
.page-head > button { flex: none; margin-left: auto; }
.reference-page{padding:28px 32px 190px;background:var(--wao-bg-page);min-height:100%;color:var(--wao-text)}.page-head{display:flex;justify-content:space-between;align-items:center;gap:16px}.eyebrow{font-size:12px;color:var(--wao-primary-text);font-weight:700}.page-head h1{font-size:24px;margin:7px 0}.page-head p{color:var(--wao-text-3);margin:0;line-height:1.6}.primary-button,.secondary-button{border-radius:var(--wao-radius-control);padding:9px 14px;min-height:36px;white-space:nowrap}.primary-button{background:var(--wao-primary);border:1px solid var(--wao-primary);color:#fff}.secondary-button{background:var(--wao-bg-card);border:1px solid var(--wao-line-control);color:var(--wao-text)}button:disabled{opacity:.55;cursor:not-allowed}.capture{border:2px dashed var(--wao-primary-line);background:var(--wao-primary-tint);border-radius:var(--wao-radius-card);margin:24px 0 16px;padding:25px;display:flex;align-items:center;gap:14px}.capture.dragging{background:var(--wao-primary-soft);border-color:var(--wao-primary)}.capture i{font-size:30px;color:var(--wao-primary-text)}.capture div{flex:1}.capture strong{display:block;font-size:16px}.capture span{display:block;color:var(--wao-text-3);font-size:12px;margin-top:4px}.library-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:28px 0 14px}.library-head h2{font-size:18px;margin:0}.library-head small{font-size:12px;color:var(--wao-text-3);font-weight:400}.search-label input{border:1px solid var(--wao-line-control);border-radius:var(--wao-radius-control);padding:8px 11px;min-width:230px;background:var(--wao-bg-card);color:var(--wao-text)}.image-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:14px}.image-card{display:block;text-align:left;border:2px solid var(--wao-line);background:var(--wao-bg-card);border-radius:var(--wao-radius-card);padding:0;overflow:hidden;color:var(--wao-text)}.image-card.active{border-color:var(--wao-primary)}.preview{height:185px;display:grid;place-items:center;background:var(--wao-bg-sub);position:relative}.preview img{max-width:100%;max-height:100%;object-fit:contain}.preview b{position:absolute;top:8px;right:8px;background:var(--wao-bg-card);color:var(--wao-primary-text);border-radius:50%;width:26px;height:26px;display:grid;place-items:center;box-shadow:var(--wao-shadow-card-hover)}.image-name{display:block;padding:10px 12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.state{border:1px dashed var(--wao-line-control);border-radius:var(--wao-radius-card);padding:35px;text-align:center;color:var(--wao-text-3)}.state button,.failure button,.answer button:not(.secondary-button){border:0;background:none;color:var(--wao-primary-text);text-decoration:underline}.error{color:var(--wao-danger-strong)}.failure{background:var(--wao-danger-bg);color:var(--wao-danger-strong);padding:12px;border-radius:var(--wao-radius-control)}.failed-item{display:flex;justify-content:space-between;gap:9px;margin-top:8px}.composer{position:fixed;z-index:4;bottom:18px;right:max(16px,calc((100cqw - 760px)/2));width:min(760px,calc(100cqw - 32px));background:var(--wao-bg-card);border:1px solid var(--wao-line);border-radius:var(--wao-radius-modal);box-shadow:var(--wao-shadow-modal);padding:12px}.selected-strip{display:flex;gap:7px;overflow:auto;color:var(--wao-text-3);font-size:12px;min-height:32px;align-items:center}.selected-image{display:flex;align-items:center;gap:5px;white-space:nowrap;background:var(--wao-primary-soft);color:var(--wao-primary-text);border-radius:6px;padding:4px 7px;max-width:160px;overflow:hidden;text-overflow:ellipsis}.selected-image img{width:25px;height:25px;object-fit:cover}.compose-row{display:flex;align-items:flex-end;gap:8px}.compose-row textarea{flex:1;min-width:0;min-height:49px;max-height:130px;resize:vertical;border:0;background:transparent;color:var(--wao-text);padding:8px;outline:0}.composer p{font-size:11px;color:var(--wao-text-3);margin:6px 3px 0}.answer{margin-top:20px;padding:16px;border:1px solid var(--wao-line);border-radius:var(--wao-radius-card)}.answer h2{font-size:16px;margin:0 0 10px}.answer-text{white-space:pre-wrap;line-height:1.7;margin-bottom:12px}@media(max-width:700px){.reference-page{padding:19px 15px 300px}.page-head{align-items:flex-start}.page-head h1{font-size:21px}.capture{padding:17px;flex-wrap:wrap}.capture div{min-width:0;flex-basis:100%}.library-head{align-items:stretch;flex-direction:column}.search-label input{width:100%;min-width:0;font-size:16px}.image-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}.preview{height:145px}.composer{bottom:calc(116px + env(safe-area-inset-bottom));padding:10px}.compose-row{flex-wrap:wrap}.compose-row textarea{flex-basis:100%}.compose-row button{margin-left:auto}.compose-row textarea{font-size:16px}}
</style>
