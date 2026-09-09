<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchRhythmReview, saveRhythmReview, type RhythmReview } from '@/api/workdayRhythm'

const props = defineProps<{ date: string; memberId: string }>()
const key = `wao-rhythm-review-draft:${props.memberId}:${props.date}`
const entries = ref<string[]>(['', '', ''])
const revision = ref(0)
const state = ref<'loading' | 'idle' | 'dirty' | 'saving' | 'saved' | 'error'>('loading')
const error = ref('')
const localError = ref('')
const latest = ref<RhythmReview | null>(null)
let ready = false
let alive = true
let restored = false

function remember() {
  try {
    localStorage.setItem(key, JSON.stringify({ revision: revision.value, entries: entries.value }))
    localError.value = ''
  } catch {
    localError.value = '本机草稿保存失败，请保持页面打开并保存到账号'
  }
}
function changed() {
  state.value = 'dirty'
  remember()
}
function pad(value: string[]) {
  return [...value, ...Array(Math.max(0, 3 - value.length)).fill('')]
}
function restore() {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return
    const draft = JSON.parse(raw) as RhythmReview
    if (
      !Number.isSafeInteger(draft.revision) ||
      draft.revision < 0 ||
      !Array.isArray(draft.entries) ||
      draft.entries.length > 20 ||
      draft.entries.some((value) => typeof value !== 'string' || value.length > 1000)
    ) {
      localError.value = '本机草稿格式无法读取，请保留浏览器数据并联系维护人员'
      return
    }
    revision.value = draft.revision
    entries.value = pad(draft.entries)
    restored = true
  } catch {
    localError.value = '本机草稿无法读取，请保持页面打开'
  }
}
async function load() {
  if (state.value === 'saving') return
  state.value = 'loading'
  error.value = ''
  try {
    const result = await fetchRhythmReview(props.date)
    if (!alive) return
    ready = true
    if (restored) {
      if (revision.value !== result.revision) latest.value = result
      state.value = 'dirty'
    } else {
      entries.value = pad(result.entries)
      revision.value = result.revision
      state.value = result.revision ? 'saved' : 'idle'
    }
  } catch (cause) {
    if (alive) {
      error.value = cause instanceof Error ? cause.message : '复盘读取失败，请重试'
      state.value = 'error'
    }
  }
}
async function save() {
  if (!ready || state.value === 'loading' || state.value === 'saving' || latest.value) return
  state.value = 'saving'
  error.value = ''
  remember()
  restored = true
  try {
    const result = await saveRhythmReview(props.date, {
      revision: revision.value,
      entries: [...entries.value],
    })
    if (!alive) return
    revision.value = result.revision
    state.value = 'saved'
    restored = false
    try {
      localStorage.removeItem(key)
      localError.value = ''
    } catch {
      localError.value = '账号已保存，本机旧草稿清理失败'
    }
  } catch (cause) {
    if (!alive) return
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
    state.value = 'error'
  }
}
async function refreshLatest() {
  restored = true
  await load()
}
function reviewedLatest() {
  if (!latest.value) return
  revision.value = latest.value.revision
  latest.value = null
  changed()
}
function add() {
  if (entries.value.length < 20) {
    entries.value.push('')
    changed()
  }
}
function remove(index: number) {
  entries.value.splice(index, 1)
  changed()
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (['dirty', 'saving', 'error'].includes(state.value) && ready) event.preventDefault()
}
onMounted(() => {
  restore()
  void load()
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  alive = false
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section class="review-section" aria-labelledby="today-review-title">
    <header>
      <h2 id="today-review-title">今日复盘</h2>
      <button
        type="button"
        :disabled="!ready || state === 'loading' || state === 'saving' || !!latest"
        @click="save"
      >
        {{
          state === 'saving' ? '保存中…' : state === 'error' && ready ? '重试保存复盘' : '保存复盘'
        }}
      </button>
    </header>
    <p class="review-hint">随时记下做得好的、遇到的痛点和下次改进。</p>
    <p v-if="state === 'loading'" role="status">正在读取今日复盘…</p>
    <div v-if="error" class="review-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" @click="ready ? refreshLatest() : load()">
        {{ ready ? '读取最新版本（保留草稿）' : '重试读取' }}
      </button>
    </div>
    <template v-if="ready || restored">
      <fieldset :disabled="!ready || state === 'loading' || state === 'saving'">
        <label v-for="(_, index) in entries" :key="index" class="review-row">
          <span>{{ index + 1 }}</span>
          <textarea
            v-model="entries[index]"
            rows="2"
            maxlength="1000"
            :aria-label="`今日复盘第 ${index + 1} 条`"
            placeholder="记录刚发生的事和你的感受"
            @input="changed"
          />
          <button
            v-if="index >= 3"
            type="button"
            :aria-label="`删除复盘第 ${index + 1} 条`"
            @click="remove(index)"
          >
            ×
          </button>
        </label>
        <button v-if="entries.length < 20" class="review-add" type="button" @click="add">
          ＋ 添加复盘
        </button>
      </fieldset>
      <div v-if="latest" class="review-conflict" role="alert">
        <b>其他页面已更新，以下是账号最新内容。请先核对并将要保留的文字合入上方草稿。</b>
        <ol>
          <li v-for="(item, index) in latest.entries" :key="index">{{ item || '（空白条目）' }}</li>
        </ol>
        <button type="button" @click="reviewedLatest">已核对，保留上方草稿继续保存</button>
      </div>
      <p class="review-status" role="status">
        {{
          state === 'saved'
            ? '复盘已保存到账号，可在历史记录查看'
            : state === 'dirty'
              ? localError
                ? '待保存 · 本机草稿未保存'
                : '待保存 · 草稿已保存在本机'
              : state === 'idle'
                ? '今天还没有复盘，写下第一条吧'
                : state === 'error'
                  ? '尚未保存，请重试；当前草稿保留'
                  : ''
        }}
      </p>
    </template>
    <p v-if="localError" class="review-error" role="alert">{{ localError }}</p>
  </section>
</template>

<style scoped>
.review-section {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--wao-line);
  flex-shrink: 0;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
h2 {
  font-size: 16px;
  margin: 0;
}
button {
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  padding: 5px 8px;
  color: var(--wao-text);
  background: var(--wao-bg-card);
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.review-hint,
.review-status {
  font-size: 12px;
  color: var(--wao-text-secondary);
  line-height: 1.6;
}
fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.review-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 8px 0;
}
.review-row > span {
  padding-top: 6px;
  font-size: 12px;
}
textarea {
  min-width: 0;
  width: 100%;
  resize: vertical;
  box-sizing: border-box;
  border: 1px solid var(--wao-line);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border-radius: 8px;
  padding: 8px;
  font: inherit;
  font-size: 13px;
  line-height: 1.6;
}
.review-error {
  color: #a84545;
  font-size: 12px;
  line-height: 1.6;
}
.review-conflict {
  padding: 10px;
  border: 1px solid #d8ad68;
  border-radius: 8px;
  font-size: 12px;
}
.review-conflict li {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
