<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchPreference, savePreference } from '@/api/favorites'
import { fetchArticleImage, startArticleImage, type ArticleImageJob } from '@/api/articleImages'

const props = defineProps<{
  scopeKey: string
  getText: () => string
  insertImage: (url: string) => boolean
}>()

const PREFERENCE_KEY = 'article-overview-image-prompt-v1'
const DEFAULT_ARTICLE_IMAGE_PROMPT = `请为这篇文章制作一张适合放在开头、分享时一眼能看懂的 16:9 横版总览图。先理解全文，提炼一个中心主题与最多 3–5 个重要观点，并用视觉关系、场景或图形把它们连接起来。画面简洁、有层次，中文只保留必要的短标题与关键词，手机上也清楚。不要逐段照搬文字，不添加文章没有的事实、数字、人物或结论，不要水印、品牌标志和无关装饰。`

const prompt = ref(DEFAULT_ARTICLE_IMAGE_PROMPT)
const draftPrompt = ref(DEFAULT_ARTICLE_IMAGE_PROMPT)
const loadingPrompt = ref(true)
const savingPrompt = ref(false)
const promptError = ref('')
const settingsOpen = ref(false)
const busy = ref(false)
const job = ref<ArticleImageJob | null>(null)
const error = ref('')
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
    prompt.value = (await fetchPreference(PREFERENCE_KEY)).trim() || DEFAULT_ARTICLE_IMAGE_PROMPT
    draftPrompt.value = prompt.value
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
    await savePreference(PREFERENCE_KEY, next === DEFAULT_ARTICLE_IMAGE_PROMPT ? '' : next)
    prompt.value = next
    settingsOpen.value = false
  } catch { promptError.value = '保存个人提示词失败，请重试' }
  finally { savingPrompt.value = false }
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
      if (!props.insertImage(result.imageUrl)) throw new Error('图片已生成，但编辑器暂不可用；点击继续插入')
      rememberJob(null)
      job.value = null
      return
    }
    await new Promise(resolve => setTimeout(resolve, 1800))
  }
  if (current === generation) throw new Error('仍在生成中，点击按钮可继续查询原任务')
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
  try {
    const id = oldId || (await startArticleImage(text, prompt.value)).id
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
    <button class="prompt-settings" type="button" aria-label="修改个人配图提示词" title="修改个人配图提示词"
      :disabled="loadingPrompt" @mousedown.prevent @click="openSettings"><i class="ri-settings-3-line" aria-hidden="true"></i></button>
    <span v-if="error || promptError" class="image-error" role="alert">{{ error || promptError }}
      <button v-if="promptError" type="button" @click="loadPrompt">重试读取</button>
    </span>
    <Teleport to="body">
      <div v-if="settingsOpen" class="image-prompt-mask" @click.self="settingsOpen = false">
        <section class="image-prompt-dialog" role="dialog" aria-modal="true" aria-labelledby="image-prompt-title">
          <header><h2 id="image-prompt-title">个人配图提示词</h2><button type="button" aria-label="关闭" @click="settingsOpen = false"><i class="ri-close-line"></i></button></header>
          <p>用于小记和文档的文章总览图；只影响你的账号。正文会在点击生成时单独提供给模型。</p>
          <textarea v-model="draftPrompt" aria-label="个人配图提示词" maxlength="4000" rows="7"></textarea>
          <span v-if="promptError" class="dialog-error" role="alert">{{ promptError }}</span>
          <footer><button type="button" @click="draftPrompt = DEFAULT_ARTICLE_IMAGE_PROMPT">恢复默认</button>
            <button type="button" @click="settingsOpen = false">取消</button>
            <button class="primary" type="button" :disabled="savingPrompt || !draftPrompt.trim()" @click="savePrompt">{{ savingPrompt ? '保存中…' : '保存个人提示词' }}</button></footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.article-image-action { display: inline-flex; align-items: center; gap: 4px; }
.generate-image, .prompt-settings { height: 26px; display: inline-flex; align-items: center; justify-content: center; gap: 4px; padding: 0 7px; border: 1px solid var(--wao-divider); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text-weak); font-size: 11px; cursor: pointer; }
.generate-image:hover, .prompt-settings:hover { border-color: var(--wao-primary); color: var(--wao-primary-text); }
.generate-image:disabled { opacity: .6; cursor: default; }
.spin { animation: article-image-spin 1s linear infinite; }
@keyframes article-image-spin { to { transform: rotate(360deg); } }
.image-error { position: absolute; right: 0; bottom: 31px; max-width: min(320px, 80vw); padding: 5px 7px; border: 1px solid var(--wao-danger-strong); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-danger-strong); font-size: 11px; }
.image-error button { border: 0; background: none; color: inherit; text-decoration: underline; cursor: pointer; }
.image-prompt-mask { position: fixed; inset: 0; z-index: 3000; display: flex; align-items: center; justify-content: center; padding: 16px; background: rgba(12, 22, 45, .48); }
.image-prompt-dialog { width: min(560px, 100%); max-height: min(90vh, 760px); overflow: auto; padding: 20px; border-radius: 12px; background: var(--wao-bg-card); color: var(--wao-text-1); box-shadow: 0 18px 55px rgba(0, 0, 0, .24); }
.image-prompt-dialog header, .image-prompt-dialog footer { display: flex; align-items: center; gap: 8px; }
.image-prompt-dialog header { justify-content: space-between; }
.image-prompt-dialog h2 { margin: 0; font-size: 17px; }
.image-prompt-dialog p { color: var(--wao-text-weak); font-size: 13px; }
.image-prompt-dialog textarea { box-sizing: border-box; width: 100%; resize: vertical; padding: 10px; border: 1px solid var(--wao-divider); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-1); font: inherit; line-height: 1.6; }
.image-prompt-dialog footer { justify-content: flex-end; flex-wrap: wrap; margin-top: 14px; }
.image-prompt-dialog button { border: 1px solid var(--wao-divider); border-radius: 7px; padding: 6px 10px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.image-prompt-dialog button.primary { border-color: var(--wao-primary); background: var(--wao-primary); color: white; }
.image-prompt-dialog button:disabled { opacity: .6; cursor: default; }
.dialog-error { color: var(--wao-danger-strong); font-size: 12px; }
</style>
