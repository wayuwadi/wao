<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchPreference, savePreference } from '@/api/favorites'
import { fetchArticleImage, startArticleImage, type ArticleImageJob } from '@/api/articleImages'
import { ARTICLE_IMAGE_PREFERENCE_KEY, DEFAULT_ARTICLE_IMAGE_PROMPT } from '@/utils/articleImagePrompt'
import { waitForPolish } from '@/api/polishJobs'
import { fetchTextPolishWorkspace, saveTextPolishPrompt } from '@/api/textPolish'
import { DEFAULT_NOTE_MODEL, resolveNoteModel } from '@/utils/noteAiModel'
import { fetchCodexModels, type CodexModelCatalog } from '@/api/codexModels'

const NOTE_MODEL_KEY = 'note-ai-model-v1'
const NOTE_TITLE_PROMPT_KEY = 'note-title-prompt-v1'
const DEFAULT_NOTE_TITLE_PROMPT = '标题准确概括这条小记的主题，简洁自然，方便以后搜索；只返回一个标题。'

const props = defineProps<{
  scopeKey: string
  getText: () => string
  insertImage: (url: string) => boolean
  getCurrentImage?: () => string | null
  generateTitle?: boolean
}>()
const emit = defineEmits<{ 'generated-title': [title: string] }>()

const PREFERENCE_KEY = ARTICLE_IMAGE_PREFERENCE_KEY

const prompt = ref(DEFAULT_ARTICLE_IMAGE_PROMPT)
const draftPrompt = ref(DEFAULT_ARTICLE_IMAGE_PROMPT)
const noteModel = ref(DEFAULT_NOTE_MODEL)
const draftModel = ref(DEFAULT_NOTE_MODEL)
const modelCatalog = ref<CodexModelCatalog | null>(null)
const modelsLoading = ref(false)
const modelError = ref('')
let modelTimer: ReturnType<typeof setInterval> | undefined
async function refreshModels() {
  if (modelsLoading.value) return
  modelsLoading.value = true
  modelError.value = ''
  try { modelCatalog.value = await fetchCodexModels() }
  catch { modelError.value = '无法更新模型目录；已有选择保留，请稍后重试' }
  finally { modelsLoading.value = false }
}
const titlePrompt = ref(DEFAULT_NOTE_TITLE_PROMPT)
const draftTitlePrompt = ref(DEFAULT_NOTE_TITLE_PROMPT)
const polishPrompt = ref('')
const draftPolishPrompt = ref('')
const loadingPrompt = ref(true)
const savingPrompt = ref(false)
const promptError = ref('')
const settingsOpen = ref(false)
watch(settingsOpen, open => {
  if (modelTimer) clearInterval(modelTimer)
  if (open && props.generateTitle) {
    void refreshModels()
    modelTimer = setInterval(() => void refreshModels(), 60_000)
  }
})
onBeforeUnmount(() => { if (modelTimer) clearInterval(modelTimer) })
const busy = ref(false)
const job = ref<ArticleImageJob | null>(null)
const error = ref('')
const replacement = ref<string | null>(null)
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
    draftModel.value = noteModel.value
    titlePrompt.value = titleValue.trim() || DEFAULT_NOTE_TITLE_PROMPT
    draftTitlePrompt.value = titlePrompt.value
    polishPrompt.value = workspace?.prompt || ''
    draftPolishPrompt.value = polishPrompt.value
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
async function saveSettings() {
  const image = draftPrompt.value.trim()
  const title = draftTitlePrompt.value.trim()
  const polish = draftPolishPrompt.value.trim()
  if (!image || image.length > 4000 || (props.generateTitle && (!title || title.length > 4000 || !polish || polish.length > 4000))) {
    promptError.value = '提示词不能为空，且每项最多 4000 字'; return
  }
  if (draftModel.value !== noteModel.value && !modelCatalog.value?.models.some(m => m.id === draftModel.value)) {
    promptError.value = '请选择本机 Codex 目录中的模型'; return
  }
  savingPrompt.value = true
  promptError.value = ''
  try {
    await savePromptValue(image)
    if (props.generateTitle) {
      await Promise.all([savePreference(NOTE_MODEL_KEY, draftModel.value),
        savePreference(NOTE_TITLE_PROMPT_KEY, title === DEFAULT_NOTE_TITLE_PROMPT ? '' : title),
        saveTextPolishPrompt(polish)])
    }
    prompt.value = image; draftPrompt.value = image
    noteModel.value = draftModel.value
    titlePrompt.value = title; polishPrompt.value = polish
    settingsOpen.value = false
  } catch (cause) { promptError.value = cause instanceof Error ? cause.message : '设置保存失败，请重试' }
  finally { savingPrompt.value = false }
}
async function savePromptValue(value: string) {
  await savePreference(PREFERENCE_KEY, value === DEFAULT_ARTICLE_IMAGE_PROMPT ? '' : value)
}
function openSettings() {
  draftPrompt.value = prompt.value
  draftModel.value = noteModel.value
  draftTitlePrompt.value = titlePrompt.value
  draftPolishPrompt.value = polishPrompt.value
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
      if (!props.insertImage(result.imageUrl)) throw new Error('图片已生成，但编辑器暂不可用；点击继续插入')
      rememberJob(null)
      job.value = null
      return
    }
    await new Promise(resolve => setTimeout(resolve, 1800))
  }
  if (current === generation) throw new Error('仍在生成中，点击按钮可继续查询原任务')
}
function decideReplacement(accept: boolean) {
  const url = replacement.value
  if (!url) return
  if (accept && !props.insertImage(url)) {
    error.value = '图片已生成，但编辑器暂不可用；点击继续插入'
    return
  }
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
  if (!oldId && props.generateTitle) {
    titleStatus.value = '正在生成标题…'
    void waitForPolish(text.slice(0, 12_000), 'NOTE_TITLE', {
      model: noteModel.value, titlePrompt: titlePrompt.value,
    }).then(result => {
      const title = result.polished?.trim().slice(0, 30)
      if (current !== generation) return
      if (props.getText().trim() !== text) { titleStatus.value = '正文已更新，保留当前标题'; return }
      if (title) { emit('generated-title', title); titleStatus.value = `标题已生成：${title}` }
      else titleStatus.value = '标题生成失败，可稍后重试'
    }).catch(() => { if (current === generation) titleStatus.value = '标题生成失败，可稍后重试' })
  }
  try {
      const id = oldId || (await startArticleImage(text, prompt.value, noteModel.value)).id
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
          <p>{{ generateTitle ? '这些设置只影响当前账号的小记标题、配图与润色。本机 AI 使用已有 Codex 登录；失败可重试，保留当前内容。' : '用于文章总览图；只影响你的账号。正文会在点击生成时单独提供给模型。' }}</p>
          <label v-if="generateTitle" class="model-choice"><span>标题、配图与润色模型</span><select v-model="draftModel">
            <option v-if="!modelCatalog?.models.some(m => m.id === draftModel)" :value="draftModel" disabled>{{ draftModel }} · 已保留，待确认可用</option>
            <option v-for="model in modelCatalog?.models || []" :key="model.id" :value="model.id">{{ model.label }}{{ model.id === DEFAULT_NOTE_MODEL ? ' · 默认' : '' }}</option>
          </select></label>
          <div v-if="generateTitle" class="model-status" role="status">
            <span>{{ modelsLoading ? '正在更新模型目录…' : modelError || modelCatalog?.message }}</span>
            <button type="button" :disabled="modelsLoading" @click="refreshModels">刷新模型</button>
          </div>
          <label v-if="generateTitle" class="prompt-field"><span>标题文案提示词</span><textarea v-model="draftTitlePrompt" maxlength="4000" rows="3" /></label>
          <label v-if="generateTitle" class="prompt-field"><span>正文润色提示词</span><textarea v-model="draftPolishPrompt" maxlength="4000" rows="5" /></label>
          <label class="prompt-field"><span>图片生成提示词</span><textarea v-model="draftPrompt" maxlength="4000" rows="6" /></label>
          <span v-if="promptError" class="dialog-error" role="alert">{{ promptError }}</span>
          <footer><button type="button" @click="draftPrompt = DEFAULT_ARTICLE_IMAGE_PROMPT">恢复图片默认</button>
            <button v-if="generateTitle" type="button" @click="draftTitlePrompt = DEFAULT_NOTE_TITLE_PROMPT">恢复标题默认</button>
            <button type="button" @click="settingsOpen = false">取消</button>
            <button class="primary" type="button" :disabled="savingPrompt || !draftPrompt.trim()" @click="generateTitle ? saveSettings() : savePrompt()">{{ savingPrompt ? '保存中…' : '保存设置' }}</button></footer>
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
