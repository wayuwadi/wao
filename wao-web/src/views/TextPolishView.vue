<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  fetchTextPolishWorkspace,
  runTextPolish,
  saveTextPolishPrompt,
  type TextPolishRecord,
} from '@/api/textPolish'

const PROMPT_DRAFT_KEY = 'wao-text-polish-prompt-draft-v1'
const ORIGINAL_DRAFT_KEY = 'wao-text-polish-original-draft-v1'

const loading = ref(true)
const loadError = ref('')
const prompt = ref('')
const savedPrompt = ref('')
const promptState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const promptError = ref('')
const originalText = ref(readDraft(ORIGINAL_DRAFT_KEY))
const running = ref(false)
const runError = ref('')
const result = ref<TextPolishRecord | null>(null)
const records = ref<TextPolishRecord[]>([])
const copyState = ref<'idle' | 'copied' | 'error'>('idle')
const initialized = ref(false)

const promptDirty = computed(() => prompt.value.trim() !== savedPrompt.value)
const canRun = computed(
  () => originalText.value.trim().length > 0 && prompt.value.trim().length > 0 && !running.value,
)
const runLabel = computed(() => {
  if (running.value) return '正在润色…'
  return promptDirty.value ? '保存提示词并润色' : '开始润色'
})

function readDraft(key: string) {
  try {
    return localStorage.getItem(key) || ''
  } catch {
    return ''
  }
}

function writeDraft(key: string, value: string) {
  try {
    if (value) localStorage.setItem(key, value)
    else localStorage.removeItem(key)
  } catch {
    // 浏览器禁用存储时继续使用当前页面内存，不阻塞核心功能。
  }
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const workspace = await fetchTextPolishWorkspace()
    const serverPrompt = workspace.prompt.trim()
    savedPrompt.value = serverPrompt
    const localPrompt = readDraft(PROMPT_DRAFT_KEY)
    prompt.value = localPrompt && localPrompt.trim() !== serverPrompt ? localPrompt : serverPrompt
    records.value = workspace.records
    initialized.value = true
  } catch (error) {
    loadError.value = errorMessage(error, '润色工作台加载失败')
  } finally {
    loading.value = false
  }
}

async function savePrompt() {
  const value = prompt.value.trim()
  if (!value) {
    promptState.value = 'error'
    promptError.value = '润色提示词不能为空'
    return false
  }
  promptState.value = 'saving'
  promptError.value = ''
  try {
    savedPrompt.value = (await saveTextPolishPrompt(value)).trim()
    prompt.value = savedPrompt.value
    promptState.value = 'saved'
    writeDraft(PROMPT_DRAFT_KEY, '')
    return true
  } catch (error) {
    promptState.value = 'error'
    promptError.value = errorMessage(error, '提示词保存失败，请重试')
    return false
  }
}

async function run() {
  if (!canRun.value) return
  runError.value = ''
  copyState.value = 'idle'
  if (promptDirty.value && !(await savePrompt())) return
  running.value = true
  try {
    const record = await runTextPolish(originalText.value.trim())
    result.value = record
    records.value = [record, ...records.value.filter((item) => item.id !== record.id)]
  } catch (error) {
    runError.value = errorMessage(error, '润色失败，请稍后重试')
  } finally {
    running.value = false
  }
}

async function copyResult() {
  if (!result.value) return
  try {
    await navigator.clipboard.writeText(result.value.polishedText)
    copyState.value = 'copied'
  } catch {
    copyState.value = 'error'
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
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

watch(prompt, (value) => {
  if (!initialized.value) return
  promptState.value = 'idle'
  promptError.value = ''
  writeDraft(PROMPT_DRAFT_KEY, value.trim() === savedPrompt.value ? '' : value)
})
watch(originalText, (value) => writeDraft(ORIGINAL_DRAFT_KEY, value))

onMounted(load)
</script>

<template>
  <main class="polish-page">
    <header class="page-header">
      <div>
        <span class="eyebrow">WRITING TOOL</span>
        <h1><i class="ri-magic-line"></i> 润色</h1>
        <p>把口语化长文整理成清晰、自然、可直接发送的表达，并保留每次前后对照。</p>
      </div>
      <div class="privacy-note"><i class="ri-user-shared-line"></i> 提示词与历史仅当前成员可见</div>
    </header>

    <section v-if="loading" class="state-card">
      <i class="ri-loader-4-line spin"></i>
      <span>正在读取提示词与历史…</span>
    </section>
    <section v-else-if="loadError" class="state-card error-state">
      <i class="ri-error-warning-line"></i>
      <strong>润色工作台加载失败</strong>
      <span>{{ loadError }}</span>
      <button @click="load"><i class="ri-refresh-line"></i> 重试</button>
    </section>

    <template v-else>
      <section class="prompt-card">
        <div class="section-title">
          <div>
            <span>润色方法</span>
            <h2>给机器人一套稳定的表达方法</h2>
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
            :disabled="!promptDirty || promptState === 'saving'"
            @click="savePrompt"
          >
            {{ promptState === 'saving' ? '正在保存…' : '保存提示词' }}
          </button>
        </div>
        <div v-if="promptError" class="inline-error">
          <i class="ri-error-warning-line"></i><span>{{ promptError }}</span>
          <button @click="savePrompt">重试</button>
        </div>
      </section>

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
  color: var(--wao-primary);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 1.2px;
}
h1 {
  margin: 5px 0 6px;
  font-size: 25px;
}
h1 i {
  color: var(--wao-primary);
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
  color: var(--wao-primary);
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
  color: var(--wao-primary);
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
  color: var(--wao-primary);
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
  color: var(--wao-primary);
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
  color: var(--wao-primary);
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
