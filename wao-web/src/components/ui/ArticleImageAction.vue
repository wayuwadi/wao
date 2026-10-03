<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import NoteAiSettingsForm from '@/components/notes/NoteAiSettingsForm.vue'
import { fetchPreference, savePreference } from '@/api/favorites'
import { fetchArticleImage, startArticleImage, type ArticleImageJob } from '@/api/articleImages'
import { ARTICLE_IMAGE_PREFERENCE_KEY, DEFAULT_ARTICLE_IMAGE_PROMPT } from '@/utils/articleImagePrompt'
import { freezeNoteAiInput, retainNoteAiResult, type NoteAiOrigin, type NoteSourceContent } from '@/api/noteOriginals'
import { newPolishRequestId, waitForPolish } from '@/api/polishJobs'
import { fetchTextPolishWorkspace } from '@/api/textPolish'
import { DEFAULT_NOTE_MODEL, resolveNoteModel } from '@/utils/noteAiModel'

const NOTE_MODEL_KEY = 'note-ai-model-v1'
const NOTE_TITLE_PROMPT_KEY = 'note-title-prompt-v1'
const DEFAULT_NOTE_TITLE_PROMPT = '标题准确概括这条小记的主题，简洁自然，方便以后搜索；只返回一个标题。'

const props = defineProps<{
  scopeKey: string
  getText: () => string
  insertImage: (url: string) => boolean
  getCurrentImage?: () => string | null
  generateTitle?: boolean
  noteAiOrigin?: NoteAiOrigin
  getSourceContent?: () => NoteSourceContent
  getImageResult?: (url: string) => NoteSourceContent
  confirmCover?: (url: string | null) => void
}>()
const emit = defineEmits<{ 'generated-title': [title: string]; 'ai-applied': [request: string] }>()

const PREFERENCE_KEY = ARTICLE_IMAGE_PREFERENCE_KEY

const prompt = ref(DEFAULT_ARTICLE_IMAGE_PROMPT)
const draftPrompt = ref(DEFAULT_ARTICLE_IMAGE_PROMPT)
const noteModel = ref(DEFAULT_NOTE_MODEL)
const titlePrompt = ref(DEFAULT_NOTE_TITLE_PROMPT)
const polishPrompt = ref('')
const loadingPrompt = ref(true)
const savingPrompt = ref(false)
const promptError = ref('')
const settingsOpen = ref(false)
const busy = ref(false)
const job = ref<ArticleImageJob | null>(null)
const error = ref('')
const replacement = ref<string | null>(null)
let imageSource: NoteSourceContent | undefined
let imageRequest: string | undefined
async function retainImage(url: string, task: string) {
  if (!props.noteAiOrigin) return
  if (!imageSource || !imageRequest) throw new Error('旧配图缺少可靠原稿关联，请重新生成；当前内容保留')
  const source = imageSource, request = imageRequest, ticket = generation
  if(props.getSourceContent?.().markdown !== imageSource.markdown) throw new Error('正文已更新，旧配图未应用，请重新生成')
  await retainNoteAiResult(imageRequest, task, props.getImageResult?.(url) ?? { ...imageSource, markdown: `![文章总览图](${url})\n\n${imageSource.markdown}`, document: null })
  if(ticket !== generation || request !== imageRequest || props.getSourceContent?.().markdown !== source.markdown) throw new Error('内容或账号已更新，旧配图未应用')
}
const titleStatus = ref('')
let generation = 0

function storageKey() { return `wao-article-image-job-v1:${props.scopeKey}` }
function persistedJob(): string | null {
  try { return localStorage.getItem(storageKey()) } catch { return null }
}
function rememberJob(id: string | null) {
  try {
    if (id) localStorage.setItem(storageKey(), id)
    else localStorage.removeItem(storageKey())
  } catch { /* 浏览器拒绝本地存储时，本页仍可完成生成。 */ }
}
async function loadPrompt() {
  loadingPrompt.value = true
  promptError.value = ''
  try {
    const [imageValue, modelValue, titleValue, workspace] = await Promise.all([
      fetchPreference(PREFERENCE_KEY), fetchPreference(NOTE_MODEL_KEY),
      props.generateTitle ? fetchPreference(NOTE_TITLE_PROMPT_KEY) : Promise.resolve(''),
      props.generateTitle ? fetchTextPolishWorkspace() : Promise.resolve(null),
    ])
    prompt.value = imageValue.trim() || DEFAULT_ARTICLE_IMAGE_PROMPT
    draftPrompt.value = prompt.value
    noteModel.value = resolveNoteModel(modelValue)
    titlePrompt.value = titleValue.trim() || DEFAULT_NOTE_TITLE_PROMPT
    polishPrompt.value = workspace?.prompt || ''
  } catch {
    promptError.value = '暂时无法读取个人配图提示词，请重试'
  } finally { loadingPrompt.value = false }
  if (!promptError.value && persistedJob()) void generate()
}
async function savePrompt() {
  const next = draftPrompt.value.trim()
  if (!next || next.length > 4000) { promptError.value = '请输入 1–4000 字的提示词'; return }
  savingPrompt.value = true
  promptError.value = ''
  try {
    await savePromptValue(next)
    prompt.value = next
    settingsOpen.value = false
  } catch { promptError.value = '保存个人提示词失败，请重试' }
  finally { savingPrompt.value = false }
}
async function savePromptValue(value: string) {
  await savePreference(PREFERENCE_KEY, value === DEFAULT_ARTICLE_IMAGE_PROMPT ? '' : value)
}
function openSettings() {
  draftPrompt.value = prompt.value
  promptError.value = ''
  settingsOpen.value = true
}
async function waitForImage(id: string, current: number) {
  const deadline = Date.now() + 35 * 60_000
  while (current === generation && Date.now() < deadline) {
    const result = await fetchArticleImage(id)
    if (current !== generation) return
    job.value = result
    if (result.status === 'FAILED') { rememberJob(null); throw new Error(result.message) }
    if (result.status === 'SUCCEEDED') {
      if (!result.imageUrl) throw new Error('图床没有返回图片地址，请重试查询')
      const existing = props.getCurrentImage?.()
      if (existing && existing !== result.imageUrl) {
        replacement.value = result.imageUrl
        return
      }
      await retainImage(result.imageUrl, id)
      if(current !== generation) return
      if (!props.insertImage(result.imageUrl)) throw new Error('图片已生成，但编辑器暂不可用；点击继续插入')
      if(props.noteAiOrigin && imageRequest) emit('ai-applied', imageRequest)
      rememberJob(null)
      job.value = null
      return
    }
    await new Promise(resolve => setTimeout(resolve, 1800))
  }
  if (current === generation) throw new Error('仍在生成中，点击按钮可继续查询原任务')
}
async function decideReplacement(accept: boolean) {
  const url = replacement.value
  const current = generation
  if (!url) return
  if(accept) { try { await retainImage(url, job.value?.id ?? '') } catch(cause) { error.value = cause instanceof Error ? cause.message : '原稿保存失败'; return } }
  if(current !== generation) return
  if (accept && !props.insertImage(url)) {
    error.value = '图片已生成，但编辑器暂不可用；点击继续插入'
    return
  }
  if(accept && props.noteAiOrigin && imageRequest) emit('ai-applied', imageRequest)
  replacement.value = null
  job.value = null
  rememberJob(null)
}
async function generate() {
  if (busy.value || loadingPrompt.value || promptError.value) return
  const text = props.getText().trim()
  const oldId = persistedJob()
  if (!oldId && !text) { error.value = '请先写下正文，再生成总览图'; return }
  if (!oldId && text.length > 60000) { error.value = '正文超过 60000 字，请先缩短'; return }
  const current = ++generation
  busy.value = true
  error.value = ''
  // 同一组件内可继续查询；刷新后的旧任务没有完整本地应用上下文，不能重新指定来源。
  if(oldId && props.noteAiOrigin && imageRequest !== oldId) {
    rememberJob(null)
    busy.value = false
    error.value = '旧配图缺少可靠应用上下文，未插入正文；点击总览配图重新生成'
    return
  }
  if(!oldId) { imageRequest = newPolishRequestId(); imageSource = props.getSourceContent?.() }
  try {
    if(props.noteAiOrigin && !oldId) {
      if(!imageSource) throw new Error('完整原稿暂不可读取，请重试')
      if(!imageRequest) throw new Error('来源请求尚未建立，请重试')
      const frozen = await freezeNoteAiInput({ requestId: imageRequest, stableId: props.noteAiOrigin.stableId, docId: props.noteAiOrigin.docId ?? null, sourceRev: props.noteAiOrigin.sourceRev ?? null, stage: 'image', content: imageSource, inputText: text })
      if(current !== generation) return
      props.confirmCover?.(frozen.overviewImageUrl)
    }
    if(current !== generation) return
    if (!oldId && props.generateTitle) {
      const titleRequest = newPolishRequestId(), source = imageSource
      titleStatus.value = '正在生成标题…'
      void (async () => {
        if(props.noteAiOrigin) {
          if(!source) throw new Error('完整原稿暂不可读取')
          await freezeNoteAiInput({ requestId: titleRequest, stableId: props.noteAiOrigin.stableId, docId: props.noteAiOrigin.docId ?? null, sourceRev: props.noteAiOrigin.sourceRev ?? null, stage: 'title', content: source, inputText: text.slice(0, 12_000) })
        }
        if(current !== generation) return
        const result = await waitForPolish(text.slice(0, 12_000), 'NOTE_TITLE', { model: noteModel.value, titlePrompt: titlePrompt.value, requestId: titleRequest })
        const title = result.polished?.trim()
        if(current !== generation || !title) return
        if(props.getText().trim() !== text || props.noteAiOrigin && props.noteAiOrigin.title !== source?.title) { titleStatus.value = '内容或手动标题已更新，旧标题未应用'; return }
        if(props.noteAiOrigin && source) await retainNoteAiResult(titleRequest,result.id,{ ...source,title })
        if(current !== generation || props.getText().trim() !== text || props.noteAiOrigin && props.noteAiOrigin.title !== source?.title) return
        emit('generated-title',title); if(props.noteAiOrigin) emit('ai-applied', titleRequest); titleStatus.value = '标题已生成'
      })().catch(cause => { if(current === generation) titleStatus.value = cause instanceof Error ? cause.message : '标题生成失败，请重试' })
    }
      const id = oldId || (await startArticleImage(text, prompt.value, noteModel.value, imageRequest)).id
    if (current !== generation) return
    rememberJob(id)
    await waitForImage(id, current)
  } catch (cause) {
    if (current === generation) error.value = cause instanceof Error ? cause.message : '配图失败，请重试'
  } finally { if (current === generation) busy.value = false }
}
watch(() => props.scopeKey, () => {
  generation++
  busy.value = false
  job.value = null
  replacement.value = null
  imageSource = undefined
  imageRequest = undefined
  error.value = ''
  if (persistedJob()) void generate()
})
onMounted(async () => {
  await loadPrompt()
})
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="article-image-action">
    <button class="generate-image" type="button" :disabled="busy || loadingPrompt || !!promptError"
      :title="busy ? job?.message || '正在生成文章总览图' : '根据全文生成一张总览图并插到开头'"
      @mousedown.prevent @click="generate">
      <i :class="busy ? 'ri-loader-4-line spin' : 'ri-sparkling-2-line'" aria-hidden="true"></i>
      <span>{{ busy ? '配图中' : persistedJob() ? '继续配图' : '总览配图' }}</span>
    </button>
    <button class="prompt-settings" type="button" :aria-label="generateTitle ? '小记 AI 设置' : '修改个人配图提示词'" :title="generateTitle ? '小记 AI 设置' : '修改个人配图提示词'"
      :disabled="loadingPrompt" @mousedown.prevent @click="openSettings"><i class="ri-settings-3-line" aria-hidden="true"></i><span v-if="generateTitle">设置</span></button>
    <span v-if="error || promptError" class="image-error" role="alert">{{ error || promptError }}
      <button v-if="promptError" type="button" @click="loadPrompt">重试读取</button>
    </span>
    <span v-if="titleStatus" class="title-status" role="status">{{ titleStatus }}</span>
    <Teleport to="body">
      <div v-if="replacement" class="image-prompt-mask" @click.self="decideReplacement(false)">
        <section class="image-prompt-dialog replacement-dialog" role="dialog" aria-modal="true" aria-label="确认替换总览图">
          <h2>要替换原来的总览图吗？</h2>
          <p>先看新图；选择保留原图时，正文不会改变。</p>
          <img :src="replacement" alt="新生成的总览图预览">
          <footer><button type="button" @click="decideReplacement(false)">保留原图</button>
            <button class="primary" type="button" @click="decideReplacement(true)">替换为新图</button></footer>
        </section>
      </div>
      <div v-if="settingsOpen" class="image-prompt-mask" @click.self="settingsOpen = false">
        <section class="image-prompt-dialog" role="dialog" aria-modal="true" aria-labelledby="image-prompt-title">
          <header><h2 id="image-prompt-title">{{ generateTitle ? '小记 AI 设置' : '个人配图提示词' }}</h2><button type="button" aria-label="关闭" @click="settingsOpen = false"><i class="ri-close-line"></i></button></header>
          <NoteAiSettingsForm v-if="generateTitle" @saved="loadPrompt" />
          <template v-else>
            <label class="prompt-field">图片生成提示词<textarea v-model="draftPrompt" maxlength="4000" rows="6" /></label>
            <span v-if="promptError" role="alert">{{ promptError }}</span>
            <footer><button class="primary" type="button" :disabled="savingPrompt" @click="savePrompt">{{ savingPrompt ? '保存中…' : '保存设置' }}</button></footer>
          </template>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.article-image-action { display: inline-flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.model-status { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 12px; color: var(--wao-text-3); }
.generate-image, .prompt-settings { height: 26px; display: inline-flex; align-items: center; justify-content: center; gap: 4px; padding: 0 7px; border: 1px solid var(--wao-divider); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text-weak); font-size: 11px; cursor: pointer; }
.generate-image:hover, .prompt-settings:hover { border-color: var(--wao-primary); color: var(--wao-primary-text); }
.generate-image:disabled { opacity: .6; cursor: default; }
.spin { animation: article-image-spin 1s linear infinite; }
@keyframes article-image-spin { to { transform: rotate(360deg); } }
.image-error { flex-basis: 100%; max-width: min(320px, 80vw); padding: 5px 7px; border: 1px solid var(--wao-danger-strong); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-danger-strong); font-size: 11px; overflow-wrap: anywhere; }
.image-error button { border: 0; background: none; color: inherit; text-decoration: underline; cursor: pointer; }
.image-prompt-mask { position: fixed; inset: 0; z-index: 3000; display: flex; align-items: center; justify-content: center; padding: 16px; background: rgba(12, 22, 45, .48); }
.image-prompt-dialog { width: min(560px, 100%); max-height: min(90vh, 760px); overflow: auto; padding: 20px; border-radius: 12px; background: var(--wao-bg-card); color: var(--wao-text-1); box-shadow: 0 18px 55px rgba(0, 0, 0, .24); }
.image-prompt-dialog header, .image-prompt-dialog footer { display: flex; align-items: center; gap: 8px; }
.image-prompt-dialog header { justify-content: space-between; }
.image-prompt-dialog h2 { margin: 0; font-size: 17px; }
.image-prompt-dialog p { color: var(--wao-text-weak); font-size: 13px; }
.model-choice, .prompt-field { display: grid; gap: 6px; margin: 12px 0; color: var(--wao-text-2); font-size: 12px; }
.model-choice select { padding: 8px; border: 1px solid var(--wao-divider); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-1); }
.prompt-field textarea { box-sizing: border-box; width: 100%; resize: vertical; padding: 9px; border: 1px solid var(--wao-divider); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-1); font: inherit; line-height: 1.5; }
.image-prompt-dialog textarea { box-sizing: border-box; width: 100%; resize: vertical; padding: 10px; border: 1px solid var(--wao-divider); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-1); font: inherit; line-height: 1.6; }
.image-prompt-dialog footer { justify-content: flex-end; flex-wrap: wrap; margin-top: 14px; }
.image-prompt-dialog button { border: 1px solid var(--wao-divider); border-radius: 7px; padding: 6px 10px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.image-prompt-dialog button.primary { border-color: var(--wao-primary); background: var(--wao-primary); color: white; }
.image-prompt-dialog button:disabled { opacity: .6; cursor: default; }
.dialog-error { color: var(--wao-danger-strong); font-size: 12px; }
.title-status { flex-basis: 100%; color: var(--wao-text-weak); font-size: 11px; }
.replacement-dialog img { display: block; max-width: 100%; max-height: 55vh; margin: 12px auto; object-fit: contain; }
</style>
