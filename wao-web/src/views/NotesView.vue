<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { resolvedRichContent } from '@/utils/richDoc'
import { renderMarkdown } from '@/utils/markdown'
import { fetchKbs } from '@/api/board'

const data = useDataStore()
const session = useSessionStore()
const query = ref('')
const scope = ref<'all' | 'mine'>('all')
const editId = ref('')
const limit = ref(30)
const highlighted = ref('')
const refreshError = ref('')
const notes = computed(() => {
  const ids = new Set(data.kbs.filter(kb => kb.notes).map(kb => kb.id))
  return Object.values(data.docs).filter(doc => ids.has(doc.kbId ?? '') && !data.isDocArchived(doc.id))
    .sort((a, b) => (Date.parse(b.created) || 0) - (Date.parse(a.created) || 0) || b.id.localeCompare(a.id))
})
const matching = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return notes.value.filter(doc => (scope.value === 'all' || doc.author === session.currentUser.id)
    && (!needle || `${doc.title}\n${resolvedRichContent(doc).markdown}`.toLocaleLowerCase().includes(needle)))
})
const visible = computed(() => matching.value.slice(0, limit.value))
const loading = computed(() => ['idle', 'loading'].includes(data.loadStates.docs) || ['idle', 'loading'].includes(data.loadStates.knowledge))
const loadError = computed(() => refreshError.value || data.loadErrors.docs || data.loadErrors.knowledge)
watch([query, scope], () => { limit.value = 30 })
function onCreated(id: string) {
  query.value = ''
  scope.value = 'all'
  limit.value = 30
  highlighted.value = id
}
function authorName(id: string) { return session.members.find(member => member.id === id)?.name || '成员' }
function timeLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
async function reload() {
  refreshError.value = ''
  data.loadStates.knowledge = 'loading'
  const [knowledge] = await Promise.allSettled([fetchKbs(), data.refreshDocs()])
  if (knowledge.status === 'fulfilled') {
    data.kbs = knowledge.value
    data.loadStates.knowledge = 'loaded'
    delete data.loadErrors.knowledge
  } else {
    data.loadStates.knowledge = 'error'
    refreshError.value = '知识库读取失败，请重试'
  }
}
onMounted(() => { void data.ensureDocsLoaded() })
</script>

<template>
  <main class="notes-page">
    <header class="page-heading"><div><h1><i class="ri-sticky-note-line"></i>小记</h1><p>随手记录，随时回来。每一个想法都有落脚处。</p></div><span>{{ notes.length }} 条记录</span></header>
    <NoteComposer :key="session.currentUser.id" :loading="loading" @saved="onCreated" />
    <section class="notes-list" aria-label="小记清单">
      <div class="list-toolbar">
        <div class="filters"><button :aria-pressed="scope === 'all'" @click="scope = 'all'">全部小记</button><button :aria-pressed="scope === 'mine'" @click="scope = 'mine'">我记录的</button></div>
        <label class="search"><i class="ri-search-line"></i><input v-model="query" type="search" placeholder="搜索小记内容" aria-label="搜索小记内容"></label>
        <button class="refresh" :disabled="loading" @click="reload">刷新</button>
      </div>
      <p v-if="loadError" class="empty error" role="alert">小记读取失败，已有内容和草稿仍保留。<button @click="reload">重试加载</button></p>
      <p v-if="loading" class="empty" role="status">正在读取小记…</p>
      <p v-else-if="!loadError && !matching.length" class="empty">{{ query ? '没有找到相关小记，换个关键词试试。' : scope === 'mine' ? '还没有你记录的小记，在上方写下第一条吧。' : '还没有小记，在上方写下第一条想法吧。' }}</p>
      <article v-for="note in visible" :key="note.id" class="note-card" :class="{ highlighted: highlighted === note.id }" :data-note-id="note.id">
        <div class="note-meta"><span class="author">{{ authorName(note.author) }}</span><time>{{ timeLabel(note.created) }}</time><span v-if="highlighted === note.id" class="saved">已保存</span></div>
        <NoteComposer v-if="editId === note.id" :key="`${session.currentUser.id}:${note.id}`" :note="note" @close="editId = ''" @saved="editId = ''; highlighted = note.id" />
        <template v-else>
          <div class="note-body markdown-body" v-html="renderMarkdown(resolvedRichContent(note).markdown || note.title)"></div>
          <div class="note-actions"><button @click="editId = note.id"><i class="ri-edit-line"></i>继续编辑</button><RouterLink :to="{ path: `/kb/${note.kbId}`, query: { doc: note.id } }">完整文档 <i class="ri-arrow-right-up-line"></i></RouterLink></div>
        </template>
      </article>
      <button v-if="matching.length > limit" class="load-more" @click="limit += 30">加载更多（还有 {{ matching.length - limit }} 条）</button>
    </section>
  </main>
</template>

<style scoped>
.notes-page { width: min(860px, 100%); margin: 0 auto; padding: 24px 24px 60px; box-sizing: border-box; color: var(--wao-text-2); }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
h1 { display: flex; gap: 10px; align-items: center; font-size: 24px; margin: 0 0 8px; color: var(--wao-text); }
h1 i { color: #7a5cc6; }
.page-heading p { margin: 0; color: var(--wao-text-faint); font-size: 13px; }
.page-heading > span { color: var(--wao-text-faint); white-space: nowrap; font-size: 12px; }
.notes-list { margin-top: 26px; }
.list-toolbar, .filters, .search, .note-meta, .note-actions { display: flex; align-items: center; gap: 12px; }
.list-toolbar { flex-wrap: wrap; margin-bottom: 16px; }
button, a { color: var(--wao-text-3); font-size: 12px; }
button { cursor: pointer; border: 0; border-radius: 7px; background: transparent; padding: 7px 9px; }
button:hover, button[aria-pressed="true"] { background: var(--wao-bg-hover); color: var(--wao-primary); }
.search { margin-left: auto; border: 1px solid var(--wao-line-control); border-radius: 8px; padding: 7px 10px; gap: 6px; }
input { width: 170px; min-width: 0; border: 0; outline: 0; background: transparent; color: inherit; font-size: 12px; }
.search:focus-within { border-color: var(--wao-primary); }
.note-card { background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: 12px; padding: 18px 20px 10px; margin-bottom: 14px; }
.note-card.highlighted { border-color: var(--wao-primary); }
.note-meta { font-size: 11px; color: var(--wao-text-faint); margin-bottom: 14px; flex-wrap: wrap; }
.author { color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.saved { color: var(--wao-success-strong); margin-left: auto; }
.note-body { overflow-wrap: anywhere; line-height: 1.75; font-size: 14px; max-height: 400px; overflow: auto; }
.note-body :deep(p:first-child) { margin-top: 0; }
.note-body :deep(img) { max-width: 100%; max-height: 300px; border-radius: 8px; }
.note-body :deep(pre), .note-body :deep(table) { max-width: 100%; overflow: auto; }
.note-actions { margin-top: 14px; border-top: 1px solid var(--wao-line-control); padding-top: 6px; justify-content: space-between; }
.note-actions a { text-decoration: none; }
.empty { text-align: center; padding: 36px 12px; color: var(--wao-text-faint); font-size: 13px; }
.error { color: var(--wao-danger-strong); }
.load-more { display: block; margin: 20px auto; }
@media (max-width: 600px) { .notes-page { padding: 16px 12px 40px; } .page-heading { align-items: flex-start; } .page-heading p { max-width: 220px; line-height: 1.6; } .search { order: 3; width: 100%; margin: 0; box-sizing: border-box; } input { width: 100%; } .refresh { margin-left: auto; } .note-card { padding: 14px 12px 8px; } }
</style>
