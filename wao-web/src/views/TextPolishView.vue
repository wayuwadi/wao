<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import {
  fetchTextPolishWorkspace,
  runTextPolish,
  saveTextPolishPrompt,
  type TextPolishRecord,
} from '@/api/textPolish'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const owner = ref('')
let generation = 0
const loading = ref(true)
const loadError = ref('')
const prompt = ref('')
const savedPrompt = ref('')
const promptState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const promptSaving = ref(false)
const promptError = ref('')
const originalText = ref('')
const running = ref(false)
const runError = ref('')
const result = ref<TextPolishRecord | null>(null)
const records = ref<TextPolishRecord[]>([])
const copyState = ref<'idle' | 'copied' | 'error'>('idle')
const initialized = ref(false)
const restoredDraft = ref(false)
const methodOpen = ref(false)
const methodSettings = ref<HTMLDetailsElement | null>(null)
async function showMethod() {
  methodOpen.value = true
  await nextTick()
  methodSettings.value?.querySelector('summary')?.focus()
  methodSettings.value?.scrollIntoView({ block: 'nearest' })
}
const draftErrors = ref({ prompt: false, original: false })
const draftError = computed(() => draftErrors.value.prompt || draftErrors.value.original)
const promptDirty = computed(() => prompt.value.trim() !== savedPrompt.value)
const canRun = computed(() => initialized.value && originalText.value.trim().length > 0
  && prompt.value.trim().length > 0 && !running.value && !promptSaving.value)
const runLabel = computed(() => running.value ? '正在润色…' : promptDirty.value ? '保存方法并润色' : '开始润色')
watch([promptDirty, promptError], ([dirty, error]) => { if (dirty || error) methodOpen.value = true })

type DraftKind = 'prompt' | 'original'
function draftKey(kind: DraftKind) {
  return `wao-text-polish-${kind}-draft-v2:${encodeURIComponent(owner.value)}`
}
function readDraft(kind: DraftKind): string | null {
  if (!owner.value) return null
  try { return localStorage.getItem(draftKey(kind)) }
  catch { draftErrors.value[kind] = true; return null }
}
function writeDraft(kind: DraftKind, value: string | null) {
  if (!owner.value || owner.value !== session.userId) return
  try {
    if (value === null) localStorage.removeItem(draftKey(kind))
    else localStorage.setItem(draftKey(kind), value)
    draftErrors.value[kind] = false
  } catch { draftErrors.value[kind] = true }
}
function persistDrafts() {
  if (!initialized.value) return
  writeDraft('prompt', promptDirty.value ? prompt.value : null)
  writeDraft('original', originalText.value || null)
}
function current(version: number) {
  return version === generation && Boolean(owner.value) && owner.value === session.userId
}
function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

async function load() {
  const version = ++generation
  loading.value = true
  loadError.value = ''
  if (!owner.value) {
    loading.value = false
    loadError.value = '请先登录后使用润色'
    return
  }
  try {
    const workspace = await fetchTextPolishWorkspace()
    if (!current(version)) return
    const localPrompt = readDraft('prompt')
    const localOriginal = readDraft('original')
    savedPrompt.value = workspace.prompt.trim()
    prompt.value = localPrompt ?? savedPrompt.value
    originalText.value = localOriginal ?? ''
    restoredDraft.value = localPrompt !== null || localOriginal !== null
    records.value = workspace.records
    initialized.value = true
  } catch (error) {
    if (current(version)) loadError.value = errorMessage(error, '润色工作台加载失败')
  } finally {
    if (current(version)) loading.value = false
  }
}

async function savePrompt() {
  if (!initialized.value || promptSaving.value) return false
  const version = generation
  const input = prompt.value
  const value = input.trim()
  if (!value) {
    promptState.value = 'error'
    promptError.value = '润色提示词不能为空'
    return false
  }
  promptSaving.value = true
  promptState.value = 'saving'
  promptError.value = ''
  try {
    const saved = (await saveTextPolishPrompt(value)).trim()
    if (!current(version)) return false
    savedPrompt.value = saved
    // 只规范化这次实际提交的文本，保存期间的新编辑保留为未保存草稿。
    if (prompt.value === input) prompt.value = saved
    writeDraft('prompt', promptDirty.value ? prompt.value : null)
    promptState.value = promptDirty.value ? 'idle' : 'saved'
    return true
  } catch (error) {
    if (current(version)) {
      promptState.value = 'error'
      promptError.value = errorMessage(error, '提示词保存失败，请重试')
    }
    return false
  } finally {
    if (current(version)) promptSaving.value = false
  }
}

async function run() {
  if (!canRun.value) return
  const version = generation
  const original = originalText.value.trim()
  running.value = true
  runError.value = ''
  copyState.value = 'idle'
  try {
    if (promptDirty.value && !(await savePrompt())) return
    if (!current(version)) return
    if (promptDirty.value) {
      runError.value = '提示词在保存期间发生了修改，请确认后重新润色；当前输入已保留。'
      return
    }
    const record = await runTextPolish(original)
    if (!current(version)) return
    result.value = record
    records.value = [record, ...records.value.filter((item) => item.id !== record.id)]
  } catch (error) {
    if (current(version)) runError.value = errorMessage(error, '润色失败，请稍后重试')
  } finally {
    if (current(version)) running.value = false
  }
}

async function copyResult() {
  if (!result.value) return
  const version = generation
  try {
    await navigator.clipboard.writeText(result.value.polishedText)
    if (current(version)) copyState.value = 'copied'
  } catch {
    if (current(version)) copyState.value = 'error'
  }
}
function reuse(record: TextPolishRecord) {
  originalText.value = record.originalText
  result.value = record
  runError.value = ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

watch(prompt, (value) => {
  if (!initialized.value) return
  if (!promptSaving.value) promptState.value = 'idle'
  promptError.value = ''
  writeDraft('prompt', value.trim() === savedPrompt.value ? null : value)
}, { flush: 'sync' })
watch(originalText, (value) => {
  if (initialized.value) writeDraft('original', value || null)
}, { flush: 'sync' })
watch(() => session.userId, (member) => {
  // 在读取新成员前清空可见数据；旧异步请求从此失效。
  generation++
  initialized.value = false
  owner.value = member
  prompt.value = ''
  savedPrompt.value = ''
  originalText.value = ''
  result.value = null
  records.value = []
  promptSaving.value = false
  promptState.value = 'idle'
  promptError.value = ''
  runError.value = ''
  running.value = false
  copyState.value = 'idle'
  restoredDraft.value = false
  methodOpen.value = false
  draftErrors.value = { prompt: false, original: false }
  void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <main class="polish-page">
    <header class="page-header">
      <div>
        <span class="eyebrow">WRITING TOOL</span>
        <h1><i class="ri-magic-line"></i> 润色</h1>
        <p>把口语化长文整理成清晰、自然、可直接发送的表达，并保留每次前后对照。</p>
      </div>
      <div class="privacy-note"><i class="ri-user-shared-line"></i> 提示词、历史与本机草稿按当前账号隔离</div>
    </header>

    <section v-if="loading" class="state-card">
      <i class="ri-loader-4-line spin"></i>
      <span>正在读取你的润色方法与历史…</span>
    </section>
    <section v-else-if="loadError" class="state-card error-state">
      <i class="ri-error-warning-line"></i>
      <strong>润色工作台加载失败</strong>
      <span>{{ loadError }}</span>
      <button @click="load"><i class="ri-refresh-line"></i> 重试</button>
    </section>

    <template v-else>
      <div v-if="draftError" class="inline-error" role="alert">
        <span>本机草稿保存不可用，请保持页面打开或复制原文备份。</span>
        <button @click="persistDrafts">重试保存草稿</button>
      </div>
      <p v-else-if="restoredDraft" class="draft-notice" role="status">已恢复当前账号的本机草稿；原文不会跨设备同步。</p>
      <p class="start-hint">粘贴原文后即可开始，将沿用你已保存的润色方法。需要调整表达风格时，可展开下方“润色方法”。</p>
      <section class="workbench-grid">
        <article class="editor-card">
          <div class="section-title compact">
            <div>
              <span>润色前</span>
              <h2>粘贴或输入原文</h2>
            </div>
            <small>{{ originalText.length }} / 12000</small>
          </div>
          <textarea
            v-model="originalText"
            maxlength="12000"
            aria-label="待润色原文"
            placeholder="例如：这件事情吧现在大概就是……"
          ></textarea>
          <div v-if="runError" class="inline-error">
            <i class="ri-error-warning-line"></i><span>{{ runError }}</span>
            <button :disabled="running" @click="run">重试</button>
          </div>
          <button class="run-button" :disabled="!canRun" @click="run">
            <i :class="running ? 'ri-loader-4-line spin' : 'ri-sparkling-2-line'"></i>{{ runLabel }}
          </button>
          <p v-if="promptDirty" class="method-notice">润色方法有未保存修改；开始时会先保存，并用于后续润色。<button type="button" @click="showMethod">查看修改</button></p>
        </article>

        <article class="result-card">
          <div class="section-title compact">
            <div>
              <span>润色后</span>
              <h2>可直接使用的结果</h2>
            </div>
            <button v-if="result" class="copy-button" @click="copyResult">
              <i :class="copyState === 'copied' ? 'ri-check-line' : 'ri-file-copy-line'"></i>
              {{ copyState === 'copied' ? '已复制' : copyState === 'error' ? '复制失败' : '复制' }}
            </button>
          </div>
          <div v-if="running" class="result-empty active">
            <i class="ri-magic-line"></i><strong>机器人正在梳理表达</strong
            ><span>原文会一直保留，完成后自动记录前后内容。</span>
          </div>
          <pre v-else-if="result" class="result-text">{{ result.polishedText }}</pre>
          <div v-else class="result-empty">
            <i class="ri-quill-pen-line"></i><strong>还没有润色结果</strong
            ><span>输入原文后开始润色，这里不会填充演示内容。</span>
          </div>
        </article>
      </section>

      <details ref="methodSettings" class="prompt-card method-settings" :open="methodOpen" @toggle="methodOpen = ($event.target as HTMLDetailsElement).open">
        <summary>润色方法（可选调整）<span>{{ promptDirty ? '有未保存修改' : '沿用已保存的方法' }}</span></summary>
        <div class="section-title">
          <div>
            <span>润色方法</span>
            <h2>希望怎样表达</h2>
          </div>
          <div class="save-status" :class="promptState">
            <template v-if="promptState === 'saving'"
              ><i class="ri-loader-4-line spin"></i> 保存中</template
            >
            <template v-else-if="promptState === 'saved'"
              ><i class="ri-checkbox-circle-line"></i> 已保存</template
            >
            <template v-else-if="promptDirty"><i class="ri-edit-circle-line"></i> 未保存</template>
            <template v-else><i class="ri-cloud-line"></i> 已同步</template>
          </div>
        </div>
        <p class="method-help">可以说明用途和语气，例如“用于工作沟通，先给结论，表达简洁”或“保留我的口语风格”。这里的提示词会保存为你后续润色使用的方法，不必每次填写。</p>
        <textarea
          v-model="prompt"
          maxlength="4000"
          aria-label="润色提示词"
          placeholder="写下希望机器人遵循的润色方法"
        ></textarea>
        <div class="prompt-actions">
          <span>{{ prompt.length }} / 4000</span>
          <button
            class="secondary-button"
            :disabled="!promptDirty || promptSaving || running"
            @click="savePrompt"
          >
            {{ promptState === 'saving' ? '正在保存…' : '保存润色方法' }}
          </button>
        </div>
        <div v-if="promptError" class="inline-error">
          <i class="ri-error-warning-line"></i><span>{{ promptError }}</span>
          <button :disabled="promptSaving || running" @click="savePrompt">重试</button>
        </div>
      </details>

      <section class="history-card">
        <div class="section-title">
          <div>
            <span>历史记录</span>
            <h2>每一次润色都有前后对照</h2>
          </div>
          <small>最近 {{ records.length }} 条</small>
        </div>
        <div v-if="records.length === 0" class="history-empty">
          <i class="ri-history-line"></i><strong>还没有润色记录</strong
          ><span>首次成功润色后会自动保存在这里。</span>
        </div>
        <div v-else class="history-list">
          <details v-for="record in records" :key="record.id">
            <summary>
              <span
                ><time>{{ formatTime(record.createdAt) }}</time
                ><b>{{ record.originalText }}</b></span
              >
              <i class="ri-arrow-down-s-line"></i>
            </summary>
            <div class="history-compare">
              <article>
                <span>润色前</span>
                <pre>{{ record.originalText }}</pre>
              </article>
              <article>
                <span>润色后</span>
                <pre>{{ record.polishedText }}</pre>
              </article>
            </div>
            <div class="history-prompt">
              <i class="ri-command-line"></i><span>{{ record.promptSnapshot }}</span>
            </div>
            <button class="reuse-button" @click="reuse(record)">
              <i class="ri-restart-line"></i> 再次使用这段原文
            </button>
          </details>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.start-hint,.method-help,.method-notice{font-size:13px;color:var(--wao-text-3);line-height:1.7}.start-hint{margin:0 0 16px}.method-settings>summary{cursor:pointer;font-size:14px;font-weight:600;line-height:1.7}.method-settings>summary span{margin-left:12px;font-size:12px;font-weight:400;color:var(--wao-text-3)}.method-settings[open]>summary{margin-bottom:20px}.method-help{margin:0 0 14px}.method-notice button{border:0;background:transparent;color:var(--wao-primary-text);cursor:pointer;text-decoration:underline;font:inherit}.method-settings>summary:focus-visible,.method-notice button:focus-visible{outline:2px solid var(--wao-primary-text);outline-offset:3px}
.draft-notice { color: var(--wao-text-3); font-size: 12px; margin: 0 0 14px; }
.polish-page {
  max-width: none;
  margin: 0 auto;
  padding: 28px 30px 80px;
  color: var(--wao-text);
}
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}
.eyebrow,
.section-title span {
  color: var(--wao-primary-text);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 1.2px;
}
h1 {
  margin: 5px 0 6px;
  font-size: 25px;
}
h1 i {
  color: var(--wao-primary-text);
}
.page-header p {
  margin: 0;
  color: var(--wao-text-3);
  font-size: 12px;
}
.privacy-note {
  padding: 8px 11px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  font-size: 10px;
  white-space: nowrap;
}
.prompt-card,
.editor-card,
.result-card,
.history-card,
.state-card {
  border: 1px solid var(--wao-line);
  border-radius: 15px;
  background: var(--wao-bg-card);
  box-shadow: 0 8px 26px rgba(26, 36, 68, 0.04);
}
.prompt-card,
.editor-card,
.result-card,
.history-card {
  padding: 18px;
}
.section-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}
.section-title h2 {
  margin: 3px 0 0;
  font-size: 14px;
}
.section-title small {
  color: var(--wao-text-weak);
  font-size: 9px;
}
.save-status {
  color: var(--wao-text-weak);
  font-size: 10px;
}
.save-status.saving,
.save-status.saved {
  color: var(--wao-primary-text);
}
.save-status.error {
  color: var(--wao-danger-strong);
}
.prompt-card textarea,
.editor-card textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  outline: 0;
  resize: vertical;
  background: var(--wao-bg-sub);
  color: var(--wao-text);
  font: 11.5px/1.65 inherit;
}
.prompt-card textarea {
  min-height: 145px;
  margin-top: 14px;
  padding: 13px;
}
.editor-card textarea {
  min-height: 310px;
  margin-top: 13px;
  padding: 15px;
}
textarea:focus {
  border-color: color-mix(in srgb, var(--wao-primary) 55%, var(--wao-line));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--wao-primary) 8%, transparent);
}
.prompt-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 9px;
}
.prompt-actions > span {
  color: var(--wao-text-weak);
  font-size: 9px;
}
button {
  font: inherit;
}
.secondary-button,
.copy-button,
.reuse-button,
.state-card button {
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
}
.secondary-button {
  min-width: 102px;
  padding: 7px 11px;
  font-size: 10px;
}
.secondary-button:disabled {
  opacity: 0.4;
  cursor: default;
}
.workbench-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-top: 14px;
}
.compact {
  align-items: center;
}
.run-button {
  width: 100%;
  min-height: 42px;
  margin-top: 12px;
  border: 0;
  border-radius: 10px;
  background: var(--wao-primary);
  color: white;
  cursor: pointer;
  font-weight: 750;
}
.run-button i {
  margin-right: 7px;
}
.run-button:disabled {
  opacity: 0.45;
  cursor: default;
}
.copy-button {
  padding: 6px 9px;
  font-size: 9.5px;
}
.copy-button i {
  margin-right: 4px;
}
.result-text {
  min-height: 310px;
  max-height: 520px;
  box-sizing: border-box;
  margin: 13px 0 0;
  padding: 15px;
  overflow: auto;
  border-radius: 11px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-2);
  font: 11.5px/1.72 inherit;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.result-empty {
  min-height: 310px;
  margin-top: 13px;
  border: 1px dashed var(--wao-line);
  border-radius: 11px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 7px;
  color: var(--wao-text-weak);
  text-align: center;
}
.result-empty i {
  font-size: 27px;
  color: var(--wao-primary-text);
}
.result-empty strong {
  color: var(--wao-text-2);
  font-size: 12px;
}
.result-empty span {
  max-width: 320px;
  font-size: 10px;
}
.result-empty.active i {
  animation: breathe 1.5s ease-in-out infinite;
}
.inline-error {
  margin-top: 9px;
  padding: 9px 10px;
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 10px;
}
.inline-error span {
  flex: 1;
}
.inline-error button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-weight: 700;
}
.history-card {
  margin-top: 14px;
}
.history-empty,
.state-card {
  min-height: 200px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 7px;
  color: var(--wao-text-weak);
  text-align: center;
}
.history-empty i,
.state-card > i {
  color: var(--wao-primary-text);
  font-size: 25px;
}
.history-empty strong,
.state-card strong {
  color: var(--wao-text-2);
  font-size: 12px;
}
.history-empty span,
.state-card span {
  font-size: 10px;
}
.history-list {
  margin-top: 14px;
  display: grid;
  gap: 8px;
}
.history-list details {
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-sub);
  overflow: hidden;
}
.history-list summary {
  padding: 11px 13px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  cursor: pointer;
  list-style: none;
}
.history-list summary::-webkit-details-marker {
  display: none;
}
.history-list summary span {
  min-width: 0;
}
.history-list time,
.history-list b {
  display: block;
}
.history-list time {
  color: var(--wao-primary-text);
  font-size: 8.5px;
}
.history-list b {
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--wao-text-2);
  font-size: 10.5px;
  font-weight: 600;
}
.history-list details[open] summary > i {
  transform: rotate(180deg);
}
.history-compare {
  padding: 0 12px 11px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.history-compare article {
  min-width: 0;
  padding: 11px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
}
.history-compare article > span {
  color: var(--wao-primary-text);
  font-size: 8.5px;
  font-weight: 750;
}
.history-compare pre {
  max-height: 220px;
  margin: 7px 0 0;
  overflow: auto;
  color: var(--wao-text-2);
  font: 10px/1.65 inherit;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.history-prompt {
  margin: 0 12px 10px;
  padding: 9px 10px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--wao-primary) 6%, var(--wao-bg-card));
  display: flex;
  gap: 7px;
  color: var(--wao-text-weak);
  font-size: 9px;
}
.history-prompt span {
  white-space: pre-wrap;
}
.reuse-button {
  margin: 0 12px 12px;
  padding: 7px 9px;
  font-size: 9.5px;
}
.error-state button {
  margin-top: 4px;
  padding: 7px 11px;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes breathe {
  50% {
    transform: scale(1.12);
    opacity: 0.65;
  }
}
@media (max-width: 760px) {
  .polish-page {
    padding: 20px 14px 70px;
  }
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .privacy-note {
    white-space: normal;
  }
  .workbench-grid,
  .history-compare {
    grid-template-columns: 1fr;
  }
  .editor-card textarea,
  .result-text,
  .result-empty {
    min-height: 250px;
  }
}
</style>
