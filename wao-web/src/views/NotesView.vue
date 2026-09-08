<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { resolvedRichContent } from '@/utils/richDoc'
import { renderMarkdown } from '@/utils/markdown'
import { apiCreateNoteTag, fetchNoteTags, fetchKbs } from '@/api/board'

const data = useDataStore()
const session = useSessionStore()
const query = ref('')
const sort = ref<'updated' | 'created'>('updated')
const selectedTag = ref('')
const tagNames = ref<string[]>([])
const tagsLoading = ref(false)
const tagsError = ref('')
const addingTag = ref(false)
const newTag = ref('')
const creatingTag = ref(false)
const tagError = ref('')
const scope = ref<'all' | 'mine'>('all')
const editId = ref('')
const writingPane = ref<HTMLElement>()
async function editNote(id: string, event?: MouseEvent) {
  if (event?.target instanceof Element && event.target.closest('a, button, input, select, textarea, summary, [contenteditable="true"]')) return
  editId.value = id
  await nextTick()
  writingPane.value?.scrollIntoView?.({ block: 'nearest' })
  writingPane.value?.querySelector<HTMLElement>('[contenteditable="true"], textarea')?.focus()
}
const limit = ref(30)
const highlighted = ref('')
const refreshError = ref('')
const notes = computed(() => {
  const ids = new Set(data.kbs.filter(kb => kb.notes).map(kb => kb.id))
  return Object.values(data.docs).filter(doc => ids.has(doc.kbId ?? '') && !data.isDocArchived(doc.id))
    .sort((a, b) => (Date.parse(b[sort.value]) || Date.parse(b.created) || 0) - (Date.parse(a[sort.value]) || Date.parse(a.created) || 0) || b.id.localeCompare(a.id))
})
const editing = computed(() => editId.value ? data.docs[editId.value] : undefined)
const availableTags = computed(() => [...new Set([...tagNames.value, ...notes.value.flatMap(note => note.tags ?? [])])].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const tagCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const note of notes.value) for (const tag of note.tags ?? []) counts.set(tag, (counts.get(tag) ?? 0) + 1)
  return counts
})
const matching = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return notes.value.filter(doc => (scope.value === 'all' || doc.author === session.currentUser.id)
    && (!selectedTag.value || doc.tags?.includes(selectedTag.value))
    && (!needle || `${doc.title}\n${resolvedRichContent(doc).markdown}\n${(doc.tags ?? []).join(' ')}`.toLocaleLowerCase().includes(needle)))
})
const visible = computed(() => matching.value.slice(0, limit.value))
const loading = computed(() => ['idle', 'loading'].includes(data.loadStates.docs) || ['idle', 'loading'].includes(data.loadStates.knowledge))
const loadError = computed(() => refreshError.value || data.loadErrors.docs || data.loadErrors.knowledge)
watch([query, scope, selectedTag, sort], () => { limit.value = 30 })
function onCreated(id: string) {
  query.value = ''
  selectedTag.value = ''
  editId.value = ''
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
async function loadTags() {
  tagsLoading.value = true
  tagsError.value = ''
  try { tagNames.value = await fetchNoteTags() }
  catch { tagsError.value = '标签读取失败，可重试；正文和已选标签仍保留。' }
  finally { tagsLoading.value = false }
}
async function createTag() {
  if (creatingTag.value) return
  const name = newTag.value.trim()
  if (!name || name.length > 32) { tagError.value = '请输入 1–32 个字符的标签名称'; return }
  creatingTag.value = true
  tagError.value = ''
  try {
    if (!data.apiLive) throw new Error('服务尚未连接，请稍后重试')
    const saved = await apiCreateNoteTag(name)
    tagNames.value = [...new Set([...tagNames.value, saved])]
    newTag.value = ''
    addingTag.value = false
  } catch (cause) { tagError.value = cause instanceof Error ? cause.message : '标签保存失败，请重试' }
  finally { creatingTag.value = false }
}
onMounted(() => { void data.ensureDocsLoaded(); void loadTags() })
</script>

<template>
  <main class="notes-page">
    <header class="page-heading"><div><h1><i class="ri-sticky-note-line"></i>小记</h1><p>随手记录，随时回来。</p></div><span>{{ notes.length }} 条记录</span></header>
    <div class="notes-workspace">
      <aside ref="writingPane" class="writing-pane" aria-label="小记编辑区">
        <div v-if="editing" class="editing-bar"><span>正在编辑已有小记</span><button @click="editId = ''">返回新建</button></div>
        <NoteComposer :key="`${session.currentUser.id}:${editId || 'new'}`" :note="editing" :loading="loading" :tag-options="availableTags" @saved="onCreated" @close="editId = ''" />
      </aside>
      <section class="notes-list" aria-label="小记清单">
        <div class="list-toolbar">
          <div class="filters"><button :aria-pressed="scope === 'all'" @click="scope = 'all'">全部小记</button><button :aria-pressed="scope === 'mine'" @click="scope = 'mine'">我记录的</button></div>
          <label class="search"><i class="ri-search-line"></i><input v-model="query" type="search" placeholder="搜索内容或标签" aria-label="搜索小记内容"></label>
          <label class="sort"><i class="ri-sort-desc"></i><select v-model="sort" aria-label="小记排序"><option value="updated">更新时间排序</option><option value="created">创建时间排序</option></select></label>
          <button class="refresh" :disabled="loading" title="刷新清单" aria-label="刷新小记清单" @click="reload"><i class="ri-refresh-line"></i></button>
        </div>
        <div class="tags-toolbar" aria-label="按标签筛选">
          <button class="tag-filter" :aria-pressed="!selectedTag" @click="selectedTag = ''">全部标签</button>
          <button v-for="tag in availableTags" :key="tag" class="tag-filter" :aria-pressed="selectedTag === tag" @click="selectedTag = selectedTag === tag ? '' : tag">{{ tag }} <span>{{ tagCounts.get(tag) ?? 0 }}</span></button>
          <button class="new-tag-toggle" :aria-expanded="addingTag" @click="addingTag = !addingTag"><i class="ri-add-line"></i>新增标签</button>
        </div>
        <form v-if="addingTag" class="new-tag-form" @submit.prevent="createTag">
          <input v-model="newTag" aria-label="新标签名称" placeholder="标签名称" maxlength="32" :disabled="creatingTag">
          <button type="submit" :disabled="creatingTag || !newTag.trim()">{{ creatingTag ? '正在保存…' : tagError ? '重试新增' : '保存标签' }}</button>
          <span v-if="tagError" class="error" role="alert">{{ tagError }}</span>
        </form>
        <p v-if="tagsLoading" class="tag-status" role="status">正在读取标签…</p>
        <p v-if="tagsError" class="tag-status error" role="alert">{{ tagsError }}<button @click="loadTags">重试标签</button></p>
        <div class="list-summary"><span>{{ selectedTag ? `# ${selectedTag} · ` : '' }}{{ matching.length }} 条小记</span><span>按{{ sort === 'updated' ? '更新' : '创建' }}时间从新到旧</span></div>
        <p v-if="loadError" class="empty error" role="alert">小记读取失败，已有内容和草稿仍保留。<button @click="reload">重试加载</button></p>
        <p v-if="loading" class="empty" role="status">正在读取小记…</p>
        <p v-else-if="!loadError && !matching.length" class="empty">{{ query || selectedTag ? '没有找到相关小记，换个关键词或标签试试。' : '还没有小记，写下第一条想法吧。' }}</p>
        <article v-for="note in visible" :key="note.id" class="note-card" :class="{ highlighted: highlighted === note.id, editing: editId === note.id }" :data-note-id="note.id"
          tabindex="0" title="双击编辑小记，键盘可按 Enter" :aria-label="`小记：${note.title}，双击或按 Enter 编辑`"
          @dblclick="editNote(note.id, $event)" @keydown.enter.self.prevent="editNote(note.id)">
          <div class="note-meta"><span class="author">{{ authorName(note.author) }}</span><time>{{ sort === 'updated' ? '更新于' : '创建于' }} {{ timeLabel(note[sort] || note.created) }}</time><span v-if="highlighted === note.id" class="saved">已保存</span></div>
          <div class="note-body markdown-body" v-html="renderMarkdown(resolvedRichContent(note).markdown || note.title)"></div>
          <div v-if="note.tags?.length" class="card-tags"><button v-for="tag in note.tags" :key="tag" @click="selectedTag = tag"># {{ tag }}</button></div>
          <div class="note-actions"><button class="touch-edit" aria-label="编辑小记" @click="editNote(note.id)"><i class="ri-edit-line"></i>编辑</button><RouterLink :to="{ path: `/kb/${note.kbId}`, query: { doc: note.id } }">完整文档 <i class="ri-arrow-right-up-line"></i></RouterLink></div>
        </article>
        <button v-if="matching.length > limit" class="load-more" @click="limit += 30">加载更多（还有 {{ matching.length - limit }} 条）</button>
      </section>
    </div>
  </main>
</template>

<style scoped>
.notes-page { width: 100%; max-width: 1800px; margin: 0 auto; padding: 24px 28px 48px; box-sizing: border-box; color: var(--wao-text-2); }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 22px; }
h1 { display: flex; gap: 10px; align-items: center; font-size: 22px; margin: 0 0 6px; color: var(--wao-text); }
h1 i { color: #7a5cc6; }
.page-heading p { margin: 0; color: var(--wao-text-faint); font-size: 13px; }
.page-heading > span { color: var(--wao-text-faint); white-space: nowrap; font-size: 12px; }
.notes-workspace { display: grid; grid-template-columns: minmax(300px, 0.85fr) minmax(360px, 1.15fr); gap: 28px; align-items: start; }
.writing-pane { min-width: 0; position: sticky; top: 20px; }
.writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: min(650px, calc(100dvh - 210px)); max-height: calc(100dvh - 155px); }
.notes-list { min-width: 0; padding-left: 26px; border-left: 1px solid var(--wao-line-control); }
.list-toolbar, .filters, .search, .note-meta, .note-actions, .sort { display: flex; align-items: center; gap: 10px; }
.list-toolbar { flex-wrap: wrap; margin-bottom: 16px; gap: 8px; }
.filters { gap: 2px; }
button, a, select { color: var(--wao-text-3); font-size: 12px; }
button { cursor: pointer; border: 0; border-radius: 7px; background: transparent; padding: 7px 9px; }
button:hover, button[aria-pressed="true"] { background: var(--wao-bg-hover); color: var(--wao-primary); }
button:disabled { cursor: default; opacity: .5; }
.search { margin-left: auto; flex: 1; min-width: 140px; border: 1px solid var(--wao-line-control); border-radius: 8px; padding: 7px 10px; gap: 6px; }
input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: inherit; font-size: 12px; }
.search:focus-within { border-color: var(--wao-primary); }
.sort { gap: 3px; }
select { max-width: 130px; padding: 6px 0; border: 0; background: var(--wao-bg-card); cursor: pointer; }
.tags-toolbar { display: flex; gap: 7px; flex-wrap: wrap; max-height: 128px; overflow: auto; padding: 2px 0; }
.tag-filter { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); max-width: 100%; overflow-wrap: anywhere; }
.tag-filter span { color: var(--wao-text-faint); margin-left: 4px; }
.new-tag-toggle { border: 1px dashed var(--wao-line-control); }
.new-tag-form { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.new-tag-form input { flex: 1; min-width: 100px; border: 1px solid var(--wao-line-control); border-radius: 7px; padding: 8px 10px; }
.new-tag-form button { background: var(--wao-primary); color: white; }
.new-tag-form .error { flex-basis: 100%; }
.tag-status { font-size: 12px; color: var(--wao-text-faint); }
.list-summary { display: flex; justify-content: space-between; gap: 10px; margin: 18px 0 12px; font-size: 11px; color: var(--wao-text-faint); }
.note-card { background: var(--wao-bg-card); border: 1px solid var(--wao-line-control); border-radius: 12px; padding: 20px 22px 10px; margin-bottom: 16px; }
.note-card.highlighted, .note-card.editing { border-color: var(--wao-primary); }
.note-card:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 3px; }
.note-meta { font-size: 11px; color: var(--wao-text-faint); margin-bottom: 16px; flex-wrap: wrap; }
.author { color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.saved { color: var(--wao-success-strong); margin-left: auto; }
.note-body { overflow-wrap: anywhere; line-height: 1.8; font-size: 14px; max-height: 500px; overflow: auto; }
.note-body :deep(p:first-child) { margin-top: 0; }
.note-body :deep(img) { max-width: 100%; max-height: 300px; border-radius: 8px; }
.note-body :deep(pre), .note-body :deep(table) { max-width: 100%; overflow: auto; }
.card-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 14px; }
.card-tags button { color: var(--wao-primary); overflow-wrap: anywhere; max-width: 100%; padding: 4px 7px; }
.note-actions { margin-top: 14px; border-top: 1px solid var(--wao-line-control); padding-top: 6px; justify-content: space-between; }
.note-actions a { text-decoration: none; margin-left: auto; }
.touch-edit { display: none; }
@media (hover: none), (pointer: coarse) { .touch-edit { display: inline-flex; gap: 4px; align-items: center; } }
.editing-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 12px; color: var(--wao-text-faint); }
.empty { text-align: center; padding: 36px 12px; color: var(--wao-text-faint); font-size: 13px; }
.error { color: var(--wao-danger-strong); font-size: 12px; }
.load-more { display: block; margin: 20px auto; }
@media (max-width: 1100px) { .notes-page { padding: 20px; } .notes-workspace { gap: 18px; grid-template-columns: minmax(280px, .9fr) minmax(300px, 1.1fr); } .notes-list { padding-left: 18px; } .search { order: 3; flex-basis: 100%; margin: 0; } .sort { margin-left: auto; } }
@media (max-width: 820px) { .notes-workspace { grid-template-columns: minmax(0, 1fr); gap: 24px; } .writing-pane { position: static; } .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 380px; max-height: none; } .writing-pane :deep(.md-body) { min-height: 230px !important; } .notes-list { border-left: 0; padding-left: 0; } }
@media (max-width: 600px) { .notes-page { padding: 16px 12px 40px; } .note-card { padding: 16px 12px 8px; } .sort { margin-left: 0; } .list-summary { flex-wrap: wrap; } }
</style>
