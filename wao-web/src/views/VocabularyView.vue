<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import {
  captureVocabulary,
  deleteVocabulary,
  fetchVocabulary,
  reviewVocabulary,
  updateVocabulary,
  type VocabularyEntry,
  type VocabularyState,
} from '@/api/vocabulary'
import { useClientPagination } from '@/composables/clientPagination'
import WaoPagination from '@/components/ui/WaoPagination.vue'

type Filter = 'ALL' | VocabularyState
type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'error'

const CAPTURE_DRAFT_KEY = 'wao-vocabulary-capture-draft'
const EDIT_DRAFT_PREFIX = 'wao-vocabulary-edit-draft:'

const entries = ref<VocabularyEntry[]>([])
const loading = ref(true)
const loadError = ref('')
const captureTerm = ref(localStorage.getItem(CAPTURE_DRAFT_KEY) ?? '')
const capturing = ref(false)
const captureMessage = ref('')
const captureError = ref('')
const search = ref('')
const filter = ref<Filter>('ALL')
const selectedId = ref('')
const saveState = ref<SaveState>('idle')
const saveError = ref('')
const editor = reactive({ meaning: '', context: '', state: 'LEARNING' as VocabularyState })
const reviewEntry = ref<VocabularyEntry | null>(null)
const reviewRevealed = ref(false)
const reviewing = ref(false)
const reviewError = ref('')
const captureInput = ref<HTMLInputElement | null>(null)
const loadingEditor = ref(false)

const selected = computed(
  () => entries.value.find((entry) => entry.id === selectedId.value) ?? null,
)
const filteredEntries = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return entries.value.filter((entry) => {
    if (filter.value !== 'ALL' && entry.state !== filter.value) return false
    return (
      !query ||
      `${entry.term}\n${entry.meaning}\n${entry.context}`.toLocaleLowerCase().includes(query)
    )
  })
})
const { page: entryPage, pageCount: entryPageCount, visible: visibleEntries } = useClientPagination(filteredEntries, [search, filter])
const learningCount = computed(
  () => entries.value.filter((entry) => entry.state === 'LEARNING').length,
)
const masteredCount = computed(
  () => entries.value.filter((entry) => entry.state === 'MASTERED').length,
)
const saveLabel = computed(
  () =>
    ({
      idle: '已同步',
      dirty: '有未保存修改',
      saving: '正在保存',
      saved: '已保存',
      error: '保存失败',
    })[saveState.value],
)

watch(captureTerm, (value) => {
  if (value) localStorage.setItem(CAPTURE_DRAFT_KEY, value)
  else localStorage.removeItem(CAPTURE_DRAFT_KEY)
})

watch(
  editor,
  () => {
    if (!selected.value || loadingEditor.value || saveState.value === 'saving') return
    saveState.value = 'dirty'
    localStorage.setItem(editDraftKey(selected.value.id), JSON.stringify(editor))
  },
  { deep: true },
)

async function loadEntries() {
  loading.value = true
  loadError.value = ''
  try {
    entries.value = await fetchVocabulary()
    if (selectedId.value && !entries.value.some((entry) => entry.id === selectedId.value))
      selectedId.value = ''
  } catch (error) {
    loadError.value = messageOf(error, '单词本读取失败')
  } finally {
    loading.value = false
  }
}

async function capture() {
  if (!captureTerm.value.trim() || capturing.value) return
  capturing.value = true
  captureMessage.value = ''
  captureError.value = ''
  try {
    const result = await captureVocabulary(captureTerm.value)
    upsert(result.entry)
    captureTerm.value = ''
    captureMessage.value = result.created
      ? `已收进单词本：${result.entry.term}`
      : `又遇见一次：${result.entry.term}（共 ${result.entry.encounterCount} 次）`
    selectEntry(result.entry)
    await nextTick()
    captureInput.value?.focus()
  } catch (error) {
    captureError.value = messageOf(error, '收集失败，草稿仍在本机')
  } finally {
    capturing.value = false
  }
}

function selectEntry(entry: VocabularyEntry) {
  loadingEditor.value = true
  selectedId.value = entry.id
  const raw = localStorage.getItem(editDraftKey(entry.id))
  let draft: Partial<typeof editor> | null = null
  if (raw) {
    try {
      draft = JSON.parse(raw) as Partial<typeof editor>
    } catch {
      localStorage.removeItem(editDraftKey(entry.id))
    }
  }
  editor.meaning = typeof draft?.meaning === 'string' ? draft.meaning : entry.meaning
  editor.context = typeof draft?.context === 'string' ? draft.context : entry.context
  editor.state =
    draft?.state === 'MASTERED'
      ? 'MASTERED'
      : draft?.state === 'LEARNING'
        ? 'LEARNING'
        : entry.state
  saveState.value = raw ? 'dirty' : 'idle'
  saveError.value = ''
  void nextTick(() => {
    loadingEditor.value = false
  })
}

async function saveEntry() {
  const entry = selected.value
  if (!entry || saveState.value === 'saving') return
  saveState.value = 'saving'
  saveError.value = ''
  try {
    const saved = await updateVocabulary(entry.id, { ...editor })
    upsert(saved)
    localStorage.removeItem(editDraftKey(entry.id))
    saveState.value = 'saved'
  } catch (error) {
    saveError.value = messageOf(error, '保存失败，本机草稿已保留')
    saveState.value = 'error'
  }
}

function openReview(entry?: VocabularyEntry) {
  const target =
    entry ??
    filteredEntries.value.find((item) => item.state === 'LEARNING') ??
    filteredEntries.value[0]
  if (!target) return
  reviewEntry.value = target
  reviewRevealed.value = false
  reviewError.value = ''
}

async function finishReview(state: VocabularyState) {
  if (!reviewEntry.value || reviewing.value) return
  reviewing.value = true
  reviewError.value = ''
  try {
    const saved = await reviewVocabulary(reviewEntry.value.id, state)
    upsert(saved)
    if (selectedId.value === saved.id) {
      loadingEditor.value = true
      editor.state = saved.state
      const draftKey = editDraftKey(saved.id)
      if (localStorage.getItem(draftKey)) localStorage.setItem(draftKey, JSON.stringify(editor))
      void nextTick(() => {
        loadingEditor.value = false
      })
    }
    reviewEntry.value = null
  } catch (error) {
    reviewError.value = messageOf(error, '复习结果保存失败，请重试')
  } finally {
    reviewing.value = false
  }
}

async function removeEntry(entry: VocabularyEntry) {
  if (!window.confirm(`删除“${entry.term}”及其学习记录？`)) return
  try {
    await deleteVocabulary(entry.id)
    entries.value = entries.value.filter((item) => item.id !== entry.id)
    localStorage.removeItem(editDraftKey(entry.id))
    if (selectedId.value === entry.id) selectedId.value = ''
  } catch (error) {
    saveError.value = messageOf(error, '删除失败，请重试')
    saveState.value = 'error'
  }
}

function upsert(entry: VocabularyEntry) {
  entries.value = [entry, ...entries.value.filter((item) => item.id !== entry.id)]
}

function editDraftKey(id: string) {
  return `${EDIT_DRAFT_PREFIX}${id}`
}
function messageOf(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}
function formatTime(value: string | null) {
  return value
    ? new Date(value).toLocaleString('zh-CN', {
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '还未复习'
}

onMounted(loadEntries)
</script>

<template>
  <main class="vocabulary-page">
    <section class="capture-card">
      <div class="capture-copy">
        <span class="eyebrow">个人英语成长</span>
        <h1>遇见一个，记下一个</h1>
        <p>复制陌生单词或短语到这里。重复记录会累计遇见次数，不会生成重复项。</p>
      </div>
      <form class="capture-form" @submit.prevent="capture">
        <label for="vocabulary-term">单词或短语</label>
        <div class="capture-row">
          <input
            id="vocabulary-term"
            ref="captureInput"
            v-model="captureTerm"
            maxlength="128"
            autocomplete="off"
            placeholder="粘贴英文，例如 resilient"
            :disabled="capturing"
          />
          <button :disabled="capturing || !captureTerm.trim()">
            <i :class="capturing ? 'ri-loader-4-line spin' : 'ri-add-line'"></i>
            {{ capturing ? '正在记录' : '记下来' }}
          </button>
        </div>
        <small v-if="captureTerm">输入草稿已保存在本机</small>
        <p v-if="captureMessage" class="form-message success">{{ captureMessage }}</p>
        <p v-if="captureError" class="form-message error">
          {{ captureError }} <button type="submit">重试</button>
        </p>
      </form>
    </section>

    <section class="summary-row" aria-label="学习概览">
      <article>
        <strong>{{ entries.length }}</strong
        ><span>全部记录</span>
      </article>
      <article>
        <strong>{{ learningCount }}</strong
        ><span>学习中</span>
      </article>
      <article>
        <strong>{{ masteredCount }}</strong
        ><span>已掌握</span>
      </article>
      <button :disabled="!entries.length" @click="openReview()">
        <i class="ri-refresh-line"></i>开始复习
      </button>
    </section>

    <section class="notebook">
      <aside class="entry-panel">
        <div class="list-toolbar">
          <label class="search-box"
            ><i class="ri-search-line"></i
            ><input v-model="search" placeholder="搜索单词、释义或语境"
          /></label>
          <div class="filters" aria-label="掌握状态筛选">
            <button
              v-for="item in [
                ['ALL', '全部'],
                ['LEARNING', '学习中'],
                ['MASTERED', '已掌握'],
              ] as const"
              :key="item[0]"
              :class="{ active: filter === item[0] }"
              @click="filter = item[0]"
            >
              {{ item[1] }}
            </button>
          </div>
        </div>

        <div v-if="loading" class="list-state">
          <i class="ri-loader-4-line spin"></i>正在读取你的单词本
        </div>
        <div v-else-if="loadError" class="list-state error-state">
          <span>{{ loadError }}</span
          ><button @click="loadEntries">重试读取</button>
        </div>
        <div v-else-if="!entries.length" class="list-state empty-state">
          <i class="ri-book-marked-line"></i><strong>单词本还是空的</strong
          ><span>从上方粘贴今天遇到的第一个陌生词吧。</span>
        </div>
        <div v-else-if="!filteredEntries.length" class="list-state empty-state">
          <i class="ri-search-eye-line"></i><strong>没有匹配记录</strong
          ><span>换一个搜索词或筛选条件。</span>
        </div>
        <div v-else class="entry-list">
          <button
            v-for="entry in visibleEntries"
            :key="entry.id"
            class="entry-row"
            :class="{ active: selectedId === entry.id }"
            @click="selectEntry(entry)"
          >
            <span class="word-icon">{{ entry.term.slice(0, 1).toUpperCase() }}</span>
            <span class="entry-main">
              <strong>{{ entry.term }}</strong>
              <small>{{ entry.meaning || '还没有写下自己的理解' }}</small>
              <em>遇见 {{ entry.encounterCount }} 次 · 复习 {{ entry.reviewCount }} 次</em>
            </span>
            <span class="state-pill" :class="entry.state.toLowerCase()">{{
              entry.state === 'MASTERED' ? '已掌握' : '学习中'
            }}</span>
          </button>
        </div>
        <WaoPagination v-if="!loading && !loadError && entryPageCount > 1" v-model:page="entryPage" :page-count="entryPageCount" :total="filteredEntries.length" aria-label="词条查询结果分页" />
      </aside>

      <article v-if="selected" class="entry-detail">
        <header>
          <div>
            <span>学习卡片</span>
            <h2>{{ selected.term }}</h2>
            <small>上次遇见 {{ formatTime(selected.lastEncounteredAt) }}</small>
          </div>
          <button class="danger-button" title="删除单词" @click="removeEntry(selected)">
            <i class="ri-delete-bin-line"></i>
          </button>
        </header>
        <div class="detail-meta">
          <span
            >遇见 <strong>{{ selected.encounterCount }}</strong> 次</span
          ><span
            >复习 <strong>{{ selected.reviewCount }}</strong> 次</span
          ><span>{{ formatTime(selected.lastReviewedAt) }}</span>
        </div>
        <label
          >我的理解<textarea
            v-model="editor.meaning"
            maxlength="1000"
            rows="4"
            placeholder="用自己的话写下含义，记得更牢"
          ></textarea>
        </label>
        <label
          >遇见它的语境<textarea
            v-model="editor.context"
            maxlength="2000"
            rows="5"
            placeholder="粘贴原句、工作场景或联想笔记"
          ></textarea>
        </label>
        <fieldset>
          <legend>掌握状态</legend>
          <label><input v-model="editor.state" type="radio" value="LEARNING" />学习中</label
          ><label><input v-model="editor.state" type="radio" value="MASTERED" />已掌握</label>
        </fieldset>
        <footer>
          <span class="save-state" :class="saveState"
            ><i
              :class="
                saveState === 'saving'
                  ? 'ri-loader-4-line spin'
                  : saveState === 'error'
                    ? 'ri-error-warning-line'
                    : 'ri-cloud-line'
              "
            ></i
            >{{ saveLabel }}</span
          >
          <button class="review-button" @click="openReview(selected)">
            <i class="ri-refresh-line"></i>复习这条
          </button>
          <button
            class="save-button"
            :disabled="saveState === 'saving' || (saveState !== 'dirty' && saveState !== 'error')"
            @click="saveEntry"
          >
            {{ saveState === 'error' ? '重试保存' : '保存笔记' }}
          </button>
        </footer>
        <p v-if="saveError" class="detail-error">{{ saveError }}</p>
        <p class="source-note">
          <i class="ri-information-line"></i
          >没有接入可靠词典数据源，因此不会自动编造释义；这里保存的是你自己的理解与语境。
        </p>
      </article>
      <article v-else class="entry-detail detail-empty">
        <i class="ri-book-open-line"></i><strong>选择一条记录继续学习</strong
        ><span>补充自己的理解和语境，或者开始一次主动回忆。</span>
      </article>
    </section>

    <div v-if="reviewEntry" class="review-mask" @click.self="reviewEntry = null">
      <section class="review-card" role="dialog" aria-modal="true" aria-label="单词复习">
        <button class="close-review" title="关闭复习" @click="reviewEntry = null">
          <i class="ri-close-line"></i>
        </button>
        <span class="eyebrow">主动回忆</span>
        <h2>{{ reviewEntry.term }}</h2>
        <p v-if="!reviewRevealed" class="recall-tip">先在心里想一想它是什么意思、在哪里见过。</p>
        <button v-if="!reviewRevealed" class="reveal-button" @click="reviewRevealed = true">
          <i class="ri-eye-line"></i>显示我的笔记
        </button>
        <div v-else class="review-notes">
          <div>
            <span>我的理解</span>
            <p>{{ reviewEntry.meaning || '还没有填写个人释义' }}</p>
          </div>
          <div>
            <span>语境</span>
            <p>{{ reviewEntry.context || '还没有记录语境' }}</p>
          </div>
          <p>这次记住了吗？本次选择会计入复习次数。</p>
          <div class="review-actions">
            <button :disabled="reviewing" @click="finishReview('LEARNING')">还要再学</button
            ><button :disabled="reviewing" @click="finishReview('MASTERED')">这次记住了</button>
          </div>
          <p v-if="reviewError" class="detail-error">{{ reviewError }}</p>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.vocabulary-page {
  box-sizing: border-box;
  min-height: 100%;
  padding: 22px;
  background: var(--wao-bg-page);
  color: var(--wao-text);
}
.capture-card {
  max-width: var(--wao-content-max);
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(260px, 0.8fr) minmax(360px, 1.2fr);
  gap: 28px;
  align-items: center;
  padding: 24px 28px;
  border: 1px solid var(--wao-primary-line);
  border-radius: 16px;
  background: linear-gradient(135deg, var(--wao-bg-card), var(--wao-primary-tint));
  box-shadow: var(--wao-shadow-card-hover);
}
.eyebrow {
  color: var(--wao-primary-text);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.12em;
}
.capture-copy h1 {
  margin: 6px 0;
  font-size: clamp(24px, 3vw, 34px);
  letter-spacing: -0.04em;
}
.capture-copy p,
.source-note {
  margin: 0;
  color: var(--wao-text-3);
  font-size: 12px;
  line-height: 1.7;
}
.capture-form {
  display: grid;
  gap: 7px;
}
.capture-form > label {
  font-size: 11px;
  font-weight: 650;
}
.capture-row {
  display: flex;
  gap: 8px;
}
.capture-row input,
.search-box input,
textarea {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
  outline: none;
}
.capture-row input {
  height: 46px;
  padding: 0 14px;
  border-radius: 10px;
  font-size: 16px;
}
.capture-row input:focus,
.search-box:focus-within,
textarea:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px var(--wao-primary-soft);
}
.capture-row button,
.summary-row > button,
.save-button,
.reveal-button,
.review-actions button {
  border: 0;
  border-radius: 9px;
  background: var(--wao-primary);
  color: #fff;
  font-weight: 650;
  cursor: pointer;
}
.capture-row button {
  min-width: 112px;
  padding: 0 14px;
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.capture-form small {
  color: var(--wao-text-weak);
  font-size: 9px;
}
.form-message {
  margin: 2px 0 0;
  font-size: 11px;
}
.form-message.success {
  color: var(--wao-success-strong);
}
.form-message.error,
.detail-error {
  color: var(--wao-danger-strong);
}
.form-message button {
  border: 0;
  background: transparent;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.summary-row {
  max-width: var(--wao-content-max);
  margin: 14px auto;
  display: grid;
  grid-template-columns: repeat(3, 1fr) auto;
  gap: 10px;
}
.summary-row article {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 12px 15px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-card);
}
.summary-row strong {
  font-size: 20px;
}
.summary-row span {
  color: var(--wao-text-weak);
  font-size: 10px;
}
.summary-row > button {
  padding: 0 17px;
}
.summary-row > button i {
  margin-right: 6px;
}
.notebook {
  max-width: var(--wao-content-max);
  min-height: 560px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(340px, 0.85fr) minmax(420px, 1.15fr);
  overflow: hidden;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
}
.entry-panel {
  min-width: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--wao-line);
}
.list-toolbar {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-bottom: 1px solid var(--wao-line);
}
.search-box {
  height: 38px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  color: var(--wao-text-weak);
}
.search-box input {
  height: 100%;
  padding: 0;
  border: 0;
  box-shadow: none;
}
.filters {
  display: flex;
  gap: 5px;
}
.filters button {
  min-height: 30px;
  padding: 0 10px;
  border: 0;
  border-radius: 999px;
  background: var(--wao-bg-hover-2);
  color: var(--wao-text-3);
  font-size: 10px;
  cursor: pointer;
}
.filters button.active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 650;
}
.entry-list {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
}
.entry-row {
  width: 100%;
  min-height: 82px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 13px;
  border: 0;
  border-bottom: 1px solid var(--wao-divider);
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.entry-row:hover,
.entry-row.active {
  background: var(--wao-primary-tint);
}
.entry-row.active {
  box-shadow: inset 3px 0 var(--wao-primary);
}
.word-icon {
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-size: 17px;
  font-weight: 750;
}
.entry-main {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 3px;
}
.entry-main strong,
.entry-main small,
.entry-main em {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.entry-main strong {
  font-size: 14px;
}
.entry-main small {
  color: var(--wao-text-3);
  font-size: 10px;
}
.entry-main em {
  color: var(--wao-text-weak);
  font-size: 9px;
  font-style: normal;
}
.state-pill {
  padding: 4px 7px;
  border-radius: 999px;
  font-size: 9px;
  white-space: nowrap;
}
.state-pill.learning {
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
}
.state-pill.mastered {
  background: var(--wao-success-bg);
  color: var(--wao-success-strong);
}
.list-state {
  min-height: 300px;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.empty-state {
  flex-direction: column;
}
.empty-state i {
  font-size: 30px;
  color: var(--wao-primary-text);
}
.empty-state strong {
  color: var(--wao-text);
  font-size: 13px;
}
.error-state {
  flex-direction: column;
  color: var(--wao-danger-strong);
}
.error-state button {
  border: 1px solid currentColor;
  border-radius: 7px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.entry-detail {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 16px;
  padding: 24px;
}
.entry-detail header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.entry-detail header span {
  color: var(--wao-primary-text);
  font-size: 9px;
  font-weight: 700;
}
.entry-detail h2 {
  margin: 4px 0;
  font-family: var(--wao-font-mono);
  font-size: 30px;
}
.entry-detail header small {
  color: var(--wao-text-weak);
  font-size: 9px;
}
.danger-button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.danger-button:hover {
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
}
.detail-meta {
  display: flex;
  gap: 16px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-3);
  font-size: 10px;
}
.entry-detail > label {
  display: grid;
  gap: 7px;
  font-size: 11px;
  font-weight: 650;
}
.entry-detail textarea {
  resize: vertical;
  padding: 10px 11px;
  border-radius: 9px;
  line-height: 1.65;
}
.entry-detail fieldset {
  display: flex;
  gap: 16px;
  padding: 10px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
}
.entry-detail legend {
  padding: 0 5px;
  color: var(--wao-text-3);
  font-size: 10px;
}
.entry-detail fieldset label {
  font-size: 11px;
}
.entry-detail footer {
  display: flex;
  align-items: center;
  gap: 8px;
}
.save-state {
  margin-right: auto;
  color: var(--wao-text-weak);
  font-size: 10px;
}
.save-state i {
  margin-right: 5px;
}
.save-state.dirty,
.save-state.saving {
  color: var(--wao-primary-text);
}
.save-state.error {
  color: var(--wao-danger-strong);
}
.save-state.saved {
  color: var(--wao-success-strong);
}
.review-button {
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
}
.save-button {
  min-height: 36px;
  padding: 0 14px;
}
.detail-error {
  margin: -7px 0 0;
  font-size: 10px;
}
.source-note {
  padding: 9px 11px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  font-size: 10px;
}
.source-note i {
  margin-right: 5px;
}
.detail-empty {
  place-content: center;
  place-items: center;
  color: var(--wao-text-weak);
  text-align: center;
}
.detail-empty i {
  font-size: 42px;
  color: var(--wao-primary-text);
}
.detail-empty strong {
  color: var(--wao-text);
}
.review-mask {
  position: fixed;
  z-index: 100;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 16px;
  background: rgba(16, 18, 28, 0.52);
}
.review-card {
  position: relative;
  box-sizing: border-box;
  width: min(520px, 100%);
  min-height: 340px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 17px;
  padding: 34px;
  border-radius: 18px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  text-align: center;
}
.review-card h2 {
  margin: 0;
  font-family: var(--wao-font-mono);
  font-size: clamp(30px, 8vw, 48px);
}
.close-review {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.recall-tip {
  color: var(--wao-text-3);
}
.reveal-button {
  min-height: 42px;
  padding: 0 18px;
}
.review-notes {
  width: 100%;
  display: grid;
  gap: 11px;
  text-align: left;
}
.review-notes > div:not(.review-actions) {
  padding: 10px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
}
.review-notes span {
  color: var(--wao-text-weak);
  font-size: 9px;
}
.review-notes p {
  margin: 4px 0 0;
  white-space: pre-wrap;
  line-height: 1.65;
}
.review-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.review-actions button {
  min-height: 42px;
}
.review-actions button:first-child {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 760px) {
  .vocabulary-page {
    padding: 12px 10px 92px;
  }
  .capture-card {
    grid-template-columns: 1fr;
    gap: 16px;
    padding: 18px 16px;
  }
  .capture-copy h1 {
    font-size: 25px;
  }
  .capture-row {
    align-items: stretch;
  }
  .capture-row button {
    min-width: 88px;
  }
  .summary-row {
    grid-template-columns: repeat(3, 1fr);
  }
  .summary-row article {
    display: grid;
    gap: 1px;
    padding: 9px;
  }
  .summary-row > button {
    grid-column: 1 / -1;
    min-height: 42px;
  }
  .notebook {
    min-height: 0;
    grid-template-columns: 1fr;
    overflow: visible;
  }
  .entry-panel {
    max-height: 540px;
    border-right: 0;
    border-bottom: 1px solid var(--wao-line);
  }
  .entry-detail {
    padding: 19px 15px;
  }
  .entry-detail footer {
    flex-wrap: wrap;
  }
  .save-state {
    flex-basis: 100%;
  }
  .review-button,
  .save-button {
    flex: 1;
    min-height: 42px;
  }
  .detail-meta {
    flex-wrap: wrap;
    gap: 7px 13px;
  }
  .review-card {
    max-height: calc(100dvh - 32px);
    overflow-y: auto;
    padding: 28px 18px;
  }
}
</style>
