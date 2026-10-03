<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import { fetchDocs, fetchKbs } from '@/api/board'
import { useSessionStore } from '@/stores/session'
import { inspirationNotes, inspirationExcerpt, inspirationQuestions } from './homeInspiration'
import type { Doc } from '@/types'

const session = useSessionStore()
const open = ref(false)
const mounted = ref(false)
const writing = ref(false)
const saving = ref(false)
const fullscreen = ref(false)
const loading = ref(false)
const error = ref('')
const mode = ref<'question' | 'memory'>('question')
const index = ref(0)
const notes = ref<Doc[]>([])
const saved = ref(false)
const paper = ref<HTMLElement>()
let generation = 0
const memory = computed(() => notes.value[index.value % Math.max(notes.value.length, 1)])
const question = computed(() => inspirationQuestions[index.value % inspirationQuestions.length])
async function load() {
  const token = ++generation
  const member = session.currentUser.id
  loading.value = true
  error.value = ''
  try {
    const [docs, kbs] = await Promise.all([fetchDocs(), fetchKbs()])
    if (token !== generation || member !== session.currentUser.id) return
    notes.value = inspirationNotes(docs, kbs, member)
  } catch { if (token === generation) error.value = '旧小记暂时没翻到，重试一下。你的输入仍在。' }
  finally { if (token === generation) loading.value = false }
}
async function show(kind: 'question' | 'memory' | 'write') {
  open.value = true
  mounted.value = true
  saved.value = false
  if (kind === 'write') writing.value = true
  else { mode.value = kind; index.value = 0; if (kind === 'memory') void load() }
  await nextTick()
  paper.value?.querySelector<HTMLElement>('button')?.focus()
}
function close() { if (!saving.value && !fullscreen.value) open.value = false }
function changeMode() {
  mode.value = mode.value === 'question' ? 'memory' : 'question'
  index.value = 0
  if (mode.value === 'memory') void load()
  else { generation++; loading.value = false; error.value = '' }
}
watch(() => session.currentUser.id, () => {
  generation++; open.value = false; mounted.value = false; notes.value = []; writing.value = false; loading.value = false; error.value = ''
})
onBeforeUnmount(() => generation++)
defineExpose({ show })
</script>

<template>
  <Modal :open="open" title="给想法留一张纸" :footer="null" :width="620" :keyboard="!saving && !fullscreen" :mask-closable="false" :closable="!saving && !fullscreen" :destroy-on-close="false" @cancel="close">
    <div ref="paper" class="inspiration-paper" @input="saved = false">
      <div class="paper-top"><span><i class="ri-sparkling-line" aria-hidden="true"></i> {{ mode === 'memory' ? '从自己的小记，遇见一个旧念头' : '一个小问题，换个角度想想' }}</span><button type="button" :disabled="loading" @click="changeMode">{{ mode === 'memory' ? '换个问题' : '翻翻旧小记' }}</button></div>
      <div class="paper-prompt" aria-live="polite">
        <p v-if="mode === 'memory' && loading" role="status">小书虫正在翻找你的小记…</p>
        <template v-else-if="mode === 'memory' && error"><p role="alert">{{ error }}</p><button type="button" @click="load">再试一次</button></template>
        <template v-else-if="mode === 'memory' && memory"><small>来自你的小记 · {{ memory.updated.slice(0, 10) }}</small><strong>{{ memory.title || '无标题小记' }}</strong><p>{{ inspirationExcerpt(memory) }}</p></template>
        <template v-else><small v-if="mode === 'memory'">还没有可回顾的小记，先从一个问题开始。</small><p class="question">{{ question }}</p></template>
        <button v-if="!loading && !error" class="another" type="button" :disabled="mode === 'memory' && notes.length === 1" @click="index++"><i class="ri-shuffle-line" aria-hidden="true"></i> 换一张</button>
      </div>
      <button v-if="!writing" class="write-thought" type="button" @click="writing = true"><i class="ri-quill-pen-line" aria-hidden="true"></i> 写下此刻的想法</button>
      <p v-if="saved" class="saved" role="status">已经收进小记了。你也可以继续写。</p>
      <NoteComposer v-if="mounted && writing" :key="session.currentUser.id" draft-scope="home" compact :active="open" autofocus @saving-change="saving = $event" @fullscreen-change="fullscreen = $event" @saved="saved = true" />
      <small class="paper-hint">纸条只是一个开头。关闭后，未保存的输入会保留为草稿。</small>
    </div>
  </Modal>
</template>

<style scoped>
.paper-top { display:flex; gap:12px; align-items:center; justify-content:space-between; flex-wrap:wrap; font-size:12px; color:var(--wao-text-3); }
button { cursor:pointer; border:1px solid var(--wao-line); background:var(--wao-bg-card); color:var(--wao-text); border-radius:18px; padding:7px 12px; }
button:disabled { opacity:.45; cursor:default; }
.paper-prompt { margin:18px 0; padding:22px; border-radius:16px 16px 16px 4px; background:color-mix(in srgb,#ffd85a 18%,var(--wao-bg-card)); border:1px solid color-mix(in srgb,#eab93b 32%,var(--wao-line)); overflow-wrap:anywhere; }
.paper-prompt strong { display:block; margin-top:8px; }.paper-prompt p { white-space:pre-wrap; line-height:1.8; }.question { font-size:20px; font-weight:500; }.another { background:transparent; font-size:12px; }
.write-thought { background:var(--wao-primary-soft); color:var(--wao-primary-text); }.paper-hint { display:block; margin-top:16px; color:var(--wao-text-weak); }.saved { color:var(--wao-primary-text); margin:12px 0; }
:deep(.note-composer:not(.fullscreen)) { margin-top:16px; }:deep(.note-composer:not(.fullscreen) .editor-space) { min-height:160px; max-height:35dvh; overflow:auto; }
@media(pointer:coarse) { button { min-height:44px; } }
</style>
