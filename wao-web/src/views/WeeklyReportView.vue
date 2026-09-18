<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchAssistantStatus, type AssistantStatus } from '@/api/assistant'
import { generateWeeklyReport, type WeeklyReport } from '@/api/weeklyReport'
import { copyText } from '@/utils/clipboard'
import { useSessionStore } from '@/stores/session'

type DraftState = 'idle' | 'saving' | 'saved' | 'error'

interface StoredDraft {
  records: string
  report: WeeklyReport | null
  candidate: WeeklyReport | null
  updatedAt: string
}

const EMPTY_REPORT: WeeklyReport = { completed: '', progress: '', risks: '', nextPlan: '' }
const SECTIONS: Array<{ key: keyof WeeklyReport; title: string; hint: string }> = [
  { key: 'completed', title: '本周完成', hint: '已经明确完成或交付的事项；没有就标记待补充。' },
  { key: 'progress', title: '进展与结果', hint: '仍在推进的事项与原文明确提到的结果。' },
  { key: 'risks', title: '问题风险', hint: '已经出现的问题、阻塞或风险；没有信息就待补充。' },
  { key: 'nextPlan', title: '下周计划', hint: '只写原始记录中明确承诺的下一步。' },
]

const session = useSessionStore()
const records = ref('')
const report = ref<WeeklyReport>({ ...EMPTY_REPORT })
const candidate = ref<WeeklyReport | null>(null)
const generating = ref(false)
const generationError = ref('')
const statusLoading = ref(true)
const assistantStatus = ref<AssistantStatus>({ configured: false, message: '正在检查本地 Codex…' })
const draftState = ref<DraftState>('idle')
const restoredAt = ref('')
let saveTimer: ReturnType<typeof setTimeout> | null = null
let readyForAutosave = false

const storageKey = computed(() => `wao-weekly-report-draft-v1:${session.userId || 'unknown'}`)
const hasReport = computed(() => Object.values(report.value).some((value) => value.trim()))
const canGenerate = computed(
  () => records.value.trim().length > 0 && !generating.value && assistantStatus.value.configured,
)

function readDraft() {
  try {
    const raw = localStorage.getItem(storageKey.value)
    if (!raw) return
    const stored = JSON.parse(raw) as Partial<StoredDraft>
    records.value = typeof stored.records === 'string' ? stored.records : ''
    if (stored.report) report.value = normalizeReport(stored.report)
    if (stored.candidate) candidate.value = normalizeReport(stored.candidate)
    restoredAt.value = typeof stored.updatedAt === 'string' ? stored.updatedAt : ''
    draftState.value = 'saved'
  } catch {
    draftState.value = 'error'
  }
}

function normalizeReport(value: Partial<WeeklyReport>): WeeklyReport {
  return {
    completed: typeof value.completed === 'string' ? value.completed : '',
    progress: typeof value.progress === 'string' ? value.progress : '',
    risks: typeof value.risks === 'string' ? value.risks : '',
    nextPlan: typeof value.nextPlan === 'string' ? value.nextPlan : '',
  }
}

function persistDraft() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = null
  try {
    const updatedAt = new Date().toISOString()
    const value: StoredDraft = {
      records: records.value,
      report: hasReport.value ? report.value : null,
      candidate: candidate.value,
      updatedAt,
    }
    localStorage.setItem(storageKey.value, JSON.stringify(value))
    restoredAt.value = updatedAt
    draftState.value = 'saved'
  } catch {
    draftState.value = 'error'
  }
}

function scheduleDraftSave() {
  if (!readyForAutosave) return
  draftState.value = 'saving'
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(persistDraft, 450)
}

async function loadAssistantStatus() {
  statusLoading.value = true
  try {
    assistantStatus.value = await fetchAssistantStatus()
  } catch (cause) {
    assistantStatus.value = {
      configured: false,
      message: cause instanceof Error ? cause.message : 'AI 状态读取失败，请重试',
    }
  } finally {
    statusLoading.value = false
  }
}

async function generate() {
  if (!canGenerate.value) return
  generating.value = true
  generationError.value = ''
  try {
    const generated = await generateWeeklyReport(records.value.trim())
    if (hasReport.value) candidate.value = generated
    else report.value = generated
  } catch (cause) {
    generationError.value =
      cause instanceof Error ? cause.message : '周报生成失败，请保留原始记录后重试'
  } finally {
    generating.value = false
  }
}

function useCandidate() {
  if (!candidate.value) return
  report.value = { ...candidate.value }
  candidate.value = null
}

function discardCandidate() {
  candidate.value = null
}

function reportText(value = report.value): string {
  return SECTIONS.map(({ key, title }) => {
    const content = value[key].trim() || '【待补充：请补充这一栏的信息】'
    return `## ${title}\n\n${content}`
  }).join('\n\n')
}

function copyReport() {
  if (hasReport.value) void copyText(reportText(), '周报全文已复制')
}

function formatSavedAt(value: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

watch([records, report, candidate], scheduleDraftSave, { deep: true })

onMounted(async () => {
  readDraft()
  await nextTick()
  readyForAutosave = true
  void loadAssistantStatus()
})
onBeforeUnmount(() => {
  readyForAutosave = false
  if (saveTimer) persistDraft()
})
</script>

<template>
  <main class="report-page">
    <header class="page-head">
      <div>
        <span class="eyebrow"><i class="ri-file-list-3-line"></i> 只整理你提供的事实</span>
        <h1>一句话生成结构化周报</h1>
        <p>输入一句话或多条工作记录，生成后逐段修改，再复制最终版本。</p>
      </div>
      <div class="draft-pill" :class="draftState">
        <i
          :class="
            draftState === 'saving'
              ? 'ri-loader-4-line spin'
              : draftState === 'error'
                ? 'ri-error-warning-line'
                : 'ri-save-3-line'
          "
        ></i>
        <span v-if="draftState === 'saving'">草稿保存中…</span>
        <button v-else-if="draftState === 'error'" type="button" @click="persistDraft">
          草稿保存失败 · 重试
        </button>
        <span v-else-if="draftState === 'saved'"
          >草稿已保存{{ formatSavedAt(restoredAt) ? ` · ${formatSavedAt(restoredAt)}` : '' }}</span
        >
        <span v-else>开始输入后自动保存</span>
      </div>
    </header>

    <section class="truth-note">
      <i class="ri-shield-check-line"></i>
      <div>
        <b>事实边界</b
        ><span>系统不会补写项目名、完成状态、数据指标或下周承诺；缺少的信息会标成“待补充”。</span>
      </div>
    </section>

    <div class="workspace">
      <section class="source-card">
        <div class="card-title">
          <div>
            <span>01</span>
            <div><b>原始工作记录</b><small>始终保留在这里，方便逐条核对</small></div>
          </div>
          <em>{{ records.length }} / 8000</em>
        </div>
        <textarea
          v-model="records"
          maxlength="8000"
          placeholder="例如：修复登录后偶发白屏，补了回归测试；新的报表接口还在联调，下周继续。"
        ></textarea>
        <div class="source-help">
          <i class="ri-lightbulb-flash-line"></i>一句话可以直接生成；多条记录建议每行写一条。
        </div>

        <div v-if="!assistantStatus.configured" class="ai-state error">
          <i :class="statusLoading ? 'ri-loader-4-line spin' : 'ri-error-warning-line'"></i>
          <span>{{ assistantStatus.message }}</span>
          <button type="button" :disabled="statusLoading" @click="loadAssistantStatus">重试</button>
        </div>
        <div v-else class="ai-state ready">
          <i class="ri-checkbox-circle-line"></i>{{ assistantStatus.message }}
        </div>

        <button class="generate-button" type="button" :disabled="!canGenerate" @click="generate">
          <i :class="generating ? 'ri-loader-4-line spin' : 'ri-sparkling-2-line'"></i>
          {{
            generating ? '正在核对事实并组织周报…' : hasReport ? '生成候选版本' : '生成结构化周报'
          }}
        </button>
        <p v-if="hasReport" class="regenerate-tip">
          再次生成只会产生候选版本，不会覆盖右侧正在编辑的内容。
        </p>
        <div v-if="generationError" class="generation-error">
          <i class="ri-error-warning-line"></i><span>{{ generationError }}</span>
          <button type="button" :disabled="generating" @click="generate">保留记录并重试</button>
        </div>
      </section>

      <section class="result-card">
        <div class="card-title result-title">
          <div>
            <span>02</span>
            <div>
              <b>最终周报</b
              ><small>{{
                hasReport ? '逐段修改，修改会自动保存' : '生成后可在每个栏目中继续编辑'
              }}</small>
            </div>
          </div>
          <button type="button" :disabled="!hasReport" @click="copyReport">
            <i class="ri-file-copy-line"></i>复制全文
          </button>
        </div>

        <div v-if="!hasReport && generating" class="result-empty busy">
          <span class="loader-orbit"><i class="ri-sparkling-2-line"></i></span>
          <b>正在把记录整理成四个栏目</b><small>只会使用左侧原文中的事实，通常需要一点时间。</small>
        </div>
        <div v-else-if="!hasReport" class="result-empty">
          <span><i class="ri-draft-line"></i></span>
          <b>这里会出现可编辑的周报</b><small>原始记录不会消失，生成失败也可以原地重试。</small>
        </div>
        <div v-else class="section-list">
          <label v-for="section in SECTIONS" :key="section.key" class="report-section">
            <span
              ><b>{{ section.title }}</b
              ><small>{{ section.hint }}</small></span
            >
            <textarea v-model="report[section.key]" maxlength="5000"></textarea>
          </label>
        </div>
      </section>
    </div>

    <section v-if="candidate" class="candidate-card" aria-live="polite">
      <header>
        <div>
          <span>候选版本</span>
          <h2>新结果已就绪，当前编辑没有被覆盖</h2>
        </div>
        <div>
          <button type="button" @click="discardCandidate">保留当前版本</button
          ><button class="accept" type="button" @click="useCandidate">采用候选版本</button>
        </div>
      </header>
      <div class="candidate-grid">
        <article v-for="section in SECTIONS" :key="section.key">
          <b>{{ section.title }}</b>
          <pre>{{ candidate[section.key] }}</pre>
        </article>
      </div>
    </section>
  </main>
</template>

<style scoped>
.report-page {
  max-width: none;
  margin: 0 auto;
  padding: 24px 28px 56px;
  color: var(--wao-text);
}
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow {
  color: #267c65;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1.8px;
  text-transform: uppercase;
}
.eyebrow i {
  margin-right: 4px;
}
h1 {
  margin: 5px 0 2px;
  font-size: 25px;
}
.page-head p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 13px;
}
.draft-pill {
  min-height: 32px;
  border: 1px solid var(--wao-line);
  border-radius: 999px;
  padding: 0 11px;
  color: var(--wao-text-weak);
  background: var(--wao-bg-card);
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  white-space: nowrap;
}
.draft-pill.saved {
  border-color: #b7d8cb;
  color: #267c65;
}
.draft-pill.error {
  border-color: #e7b8b0;
  color: #a43d32;
}
.draft-pill button {
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
  font: inherit;
}
.truth-note {
  margin-top: 18px;
  min-height: 56px;
  border: 1px solid #c8ded5;
  border-radius: 12px;
  padding: 10px 14px;
  background: var(--wao-success-bg);
  color: #315e51;
  display: flex;
  align-items: center;
  gap: 10px;
}
.truth-note > i {
  font-size: 20px;
  color: #267c65;
}
.truth-note div {
  display: grid;
}
.truth-note b {
  font-size: 12px;
}
.truth-note span {
  margin-top: 2px;
  color: #69887e;
  font-size: 10.5px;
}
.workspace {
  margin-top: 14px;
  display: grid;
  grid-template-columns: minmax(310px, 0.78fr) minmax(480px, 1.22fr);
  gap: 14px;
  align-items: start;
}
.source-card,
.result-card,
.candidate-card {
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-sm);
  overflow: hidden;
}
.source-card {
  padding: 18px;
}
.result-card {
  min-height: 540px;
  padding: 18px;
}
.card-title {
  min-height: 39px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.card-title > div {
  display: flex;
  align-items: center;
  gap: 9px;
}
.card-title > div > span {
  width: 29px;
  height: 29px;
  border-radius: 9px;
  background: #dcefe7;
  color: #267c65;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 750;
}
.card-title > div > div {
  display: grid;
}
.card-title b {
  font-size: 13px;
}
.card-title small {
  color: var(--wao-text-faint);
  font-size: 10px;
}
.card-title em {
  color: var(--wao-text-faint);
  font-style: normal;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}
.source-card > textarea {
  width: 100%;
  min-height: 260px;
  margin-top: 13px;
  border: 1px solid var(--wao-line-control);
  border-radius: 11px;
  padding: 12px 13px;
  background: var(--wao-bg-page);
  color: var(--wao-text);
  resize: vertical;
  outline: none;
  font: inherit;
  font-size: 13px;
  line-height: 1.7;
}
.source-card > textarea:focus,
.report-section textarea:focus {
  border-color: #68aa92;
  box-shadow: 0 0 0 3px rgba(65, 151, 120, 0.11);
}
.source-help {
  margin-top: 7px;
  color: var(--wao-text-faint);
  font-size: 10.5px;
}
.source-help i {
  margin-right: 4px;
  color: #9a721f;
}
.ai-state {
  min-height: 38px;
  margin-top: 16px;
  border-radius: 9px;
  padding: 8px 10px;
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 10.5px;
}
.ai-state.ready {
  background: #edf7f2;
  color: #267c65;
}
.ai-state.error {
  background: var(--wao-danger-bg);
  color: #9f4037;
}
.ai-state span {
  flex: 1;
}
.ai-state button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
  text-decoration: underline;
}
.generate-button {
  width: 100%;
  min-height: 42px;
  margin-top: 10px;
  border: 0;
  border-radius: 10px;
  background: #267c65;
  color: #fff;
  font: inherit;
  font-weight: 650;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
}
.generate-button:hover {
  background: #1f6755;
}
.generate-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.regenerate-tip {
  margin: 7px 3px 0;
  color: var(--wao-text-faint);
  font-size: 10px;
  line-height: 1.5;
}
.generation-error {
  margin-top: 10px;
  border: 1px solid #ecc5be;
  border-radius: 9px;
  padding: 9px;
  background: var(--wao-danger-bg);
  color: #a33c32;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px;
  font-size: 10.5px;
}
.generation-error button {
  grid-column: 2;
  justify-self: start;
  border: 0;
  padding: 0;
  background: none;
  color: inherit;
  cursor: pointer;
  font: inherit;
  font-weight: 650;
}
.result-title {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--wao-divider);
}
.result-title > button {
  min-height: 32px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 11px;
  background: var(--wao-bg-card);
  color: #267c65;
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}
.result-title > button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.result-title > button i {
  margin-right: 4px;
}
.result-empty {
  min-height: 425px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}
.result-empty > span {
  width: 66px;
  height: 66px;
  margin-bottom: 15px;
  border-radius: 20px;
  background: #eaf5f0;
  color: #39816a;
  display: grid;
  place-items: center;
  font-size: 26px;
}
.result-empty b {
  font-size: 14px;
}
.result-empty small {
  max-width: 330px;
  margin-top: 5px;
  color: var(--wao-text-faint);
  font-size: 10.5px;
}
.loader-orbit {
  animation: breathe 1.4s ease-in-out infinite;
}
@keyframes breathe {
  50% {
    transform: scale(1.08);
    box-shadow: 0 0 0 12px rgba(57, 129, 106, 0.07);
  }
}
.section-list {
  padding-top: 12px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.report-section {
  min-width: 0;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  overflow: hidden;
  background: var(--wao-bg-page);
}
.report-section > span {
  min-height: 54px;
  padding: 9px 10px 7px;
  display: grid;
  border-bottom: 1px solid var(--wao-divider);
}
.report-section b {
  font-size: 12px;
}
.report-section small {
  margin-top: 2px;
  color: var(--wao-text-faint);
  font-size: 9.5px;
  line-height: 1.4;
}
.report-section textarea {
  width: 100%;
  min-height: 143px;
  border: 0;
  padding: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  resize: vertical;
  outline: none;
  font: inherit;
  font-size: 12px;
  line-height: 1.65;
}
.candidate-card {
  margin-top: 14px;
  padding: 17px;
  border-color: #b8d6ca;
}
.candidate-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
}
.candidate-card header span {
  color: #267c65;
  font-size: 9.5px;
  font-weight: 750;
  letter-spacing: 1.4px;
}
.candidate-card h2 {
  margin: 2px 0 0;
  font-size: 15px;
}
.candidate-card header > div:last-child {
  display: flex;
  gap: 7px;
}
.candidate-card button {
  min-height: 33px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}
.candidate-card button.accept {
  border-color: #267c65;
  background: #267c65;
  color: #fff;
}
.candidate-grid {
  margin-top: 13px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.candidate-grid article {
  min-width: 0;
  border-radius: 9px;
  padding: 10px;
  background: var(--wao-bg-page);
}
.candidate-grid b {
  color: #267c65;
  font-size: 11px;
}
.candidate-grid pre {
  max-height: 150px;
  margin: 5px 0 0;
  overflow: auto;
  white-space: pre-wrap;
  color: var(--wao-text-weak);
  font: inherit;
  font-size: 10.5px;
  line-height: 1.55;
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 900px) {
  .workspace {
    grid-template-columns: 1fr;
  }
  .source-card > textarea {
    min-height: 180px;
  }
  .result-card {
    min-height: 460px;
  }
}
@media (max-width: 620px) {
  .report-page {
    padding: 16px 12px 92px;
  }
  .page-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .page-head p {
    line-height: 1.5;
  }
  .draft-pill {
    align-self: flex-start;
  }
  .truth-note {
    align-items: flex-start;
  }
  .workspace {
    margin-top: 10px;
  }
  .source-card,
  .result-card {
    padding: 13px;
    border-radius: 13px;
  }
  .section-list,
  .candidate-grid {
    grid-template-columns: 1fr;
  }
  .candidate-card header {
    align-items: flex-start;
    flex-direction: column;
  }
  .candidate-card header > div:last-child {
    width: 100%;
  }
  .candidate-card button {
    flex: 1;
  }
  .result-empty {
    min-height: 320px;
  }
}
</style>
