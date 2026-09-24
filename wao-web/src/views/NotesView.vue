<script setup lang="ts">
import WeKnoraDocumentStatus from '@/components/weknora/WeKnoraDocumentStatus.vue'
import { useWeKnoraDocuments } from '@/composables/weknoraDocuments'
import { useUiStore } from '@/stores/ui'
import { chatShareLink, docShareUrl } from '@/utils/share'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { defineAsyncComponent, computed, nextTick, onMounted, ref, watch } from 'vue'
import NoteComposer from '@/components/notes/NoteComposer.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { resolvedRichContent } from '@/utils/richDoc'
import { notePreviewImages, type NotePreviewImage } from '@/utils/notePreviewImages'
import { apiCreateNoteTag, apiDeleteNoteTag, apiMergeNoteTag, fetchNoteTags, fetchKbs } from '@/api/board'
import { fetchPreference, savePreference } from '@/api/favorites'

const ui = useUiStore()
const contextMenu = ref<InstanceType<typeof ContextActionMenu> | null>(null)

const pinnedIds = ref<string[]>([])
const pinsReady = ref(false)
const pinSaving = ref(false)
const pinError = ref('')
async function loadPins() {
  pinsReady.value = false
  pinError.value = ''
  try {
    const value: unknown = JSON.parse(await fetchPreference('note-pins-v1') || '[]')
    pinnedIds.value = Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : []
    pinsReady.value = true
  } catch { pinError.value = '置顶设置读取失败，请重试' }
}
async function togglePin(id: string) {
  if (!pinsReady.value || pinSaving.value) return
  pinSaving.value = true
  pinError.value = ''
  const next = pinnedIds.value.includes(id) ? pinnedIds.value.filter(item => item !== id) : [...pinnedIds.value, id]
  try { await savePreference('note-pins-v1', JSON.stringify(next)); pinnedIds.value = next }
  catch { pinError.value = '置顶保存失败，请再次点击重试' }
  finally { pinSaving.value = false }
}

const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const data = useDataStore()
const session = useSessionStore()
const noteEditors = new Map<string, { copyFormatted: () => Promise<void> }>()
const noteCounts = ref<Record<string, number>>({})
const previewImage = ref<NotePreviewImage | null>(null)
const lightbox = ref<HTMLElement | null>(null)
let imageTrigger: HTMLElement | null = null
async function openImage(image: NotePreviewImage, event: MouseEvent) {
  imageTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  previewImage.value = image
  await nextTick()
  lightbox.value?.focus()
}
async function closeImage() {
  previewImage.value = null
  await nextTick()
  imageTrigger?.focus()
  imageTrigger = null
}
function setNoteEditor(id: string, value: unknown) { if (value) noteEditors.set(id, value as { copyFormatted: () => Promise<void> }); else noteEditors.delete(id) }
const query = ref('')
const sort = ref<'updated' | 'created'>('updated')
const selectedTag = ref<string | null>('')
const tagNames = ref<string[]>([])
const tagsLoading = ref(false)
const tagsError = ref('')
const managingTags = ref(false)
const sourceTag = ref('')
const targetTag = ref('')
const tagSaving = ref(false)
async function manageTag(merge: boolean) {
  if (tagSaving.value || !sourceTag.value || (merge && (!targetTag.value || targetTag.value === sourceTag.value))) return
  const source = sourceTag.value, target = targetTag.value
  if (!window.confirm(merge ? `将“${source}”合并到“${target}”？文章保留，重复标签会去重。` : `删除标签“${source}”？只移除标签，不删除文章。`)) return
  tagSaving.value = true
  tagsError.value = ''
  try {
    if (merge) await apiMergeNoteTag(source, target)
    else await apiDeleteNoteTag(source)
    if (selectedTag.value === source) selectedTag.value = merge ? target : ''
    sourceTag.value = ''
    targetTag.value = ''
    await Promise.all([loadTags(), data.refreshDocs()])
  } catch (cause) { tagsError.value = cause instanceof Error ? cause.message : '标签修改失败，请重试' }
  finally { tagSaving.value = false }
}
const addingTag = ref(false)
const newTag = ref('')
const creatingTag = ref(false)
const tagError = ref('')
const editingIds = ref<string[]>([])
const deletingId = ref('')
const confirmingDeleteId = ref('')
const deleteError = ref<{ id: string; message: string } | null>(null)
function closeEditing(id: string) { editingIds.value = editingIds.value.filter(item => item !== id) }
function toggleDeleteConfirmation(id: string) {
  if (deletingId.value) return
  confirmingDeleteId.value = confirmingDeleteId.value === id ? '' : id
  deleteError.value = null
}
function cancelDeleteOnEscape(id: string, event: KeyboardEvent) {
  if (confirmingDeleteId.value !== id || deletingId.value) return
  event.stopPropagation()
  toggleDeleteConfirmation(id)
}
function openNoteMenu(event: MouseEvent | KeyboardEvent, id: string) {
  const note = data.docById(id)
  if (!note || editingIds.value.includes(id)) return
  if (event instanceof KeyboardEvent) {
    if (!(event.shiftKey && event.key === 'F10') && event.key !== 'ContextMenu') return
    event.preventDefault()
  } else {
    if (event.target instanceof Element && event.target.closest('a, button, input, textarea, img, [contenteditable="true"]')) return
    const selection = window.getSelection()
    if (selection && !selection.isCollapsed && selection.anchorNode && event.currentTarget instanceof Node && event.currentTarget.contains(selection.anchorNode)) return
  }
  contextMenu.value?.openAt(event, `小记：${note.title}`, [
    { id: 'edit', label: '编辑小记', run: () => editNote(id) },
    { id: 'copy', label: '复制内容', run: () => noteEditors.get(id)?.copyFormatted() },
    { id: 'link', label: '复制分享链接', run: () => copyText(docShareUrl(id), '公开分享链接已复制') },
    { id: 'chat', label: '分享到聊天', run: () => ui.shareToChat(chatShareLink(note.title, docShareUrl(id))) },
    { id: 'pin', label: pinnedIds.value.includes(id) ? '取消置顶' : '置顶', disabled: !pinsReady.value || pinSaving.value, run: () => togglePin(id) },
    { id: 'delete', label: '移入回收站', danger: true, separatorBefore: true, disabled: !!deletingId.value, run: () => toggleDeleteConfirmation(id) },
  ])
}
async function deleteNote(id: string) {
  if (deletingId.value || confirmingDeleteId.value !== id) return
  if (!data.apiLive) { deleteError.value = { id, message: '服务尚未连接，小记未删除，请稍后重试。' }; return }
  deletingId.value = id
  deleteError.value = null
  try {
    await data.deleteDoc(id)
    confirmingDeleteId.value = ''
    closeEditing(id)
    if (highlighted.value === id) highlighted.value = ''
    toast('小记已移入知识库回收站，7 天内可恢复', 'ri-delete-bin-line')
  } catch {
    deleteError.value = { id, message: '删除失败，小记未改变；若它包含子节点，请先在完整文档中移走后重试。' }
  } finally { deletingId.value = '' }
}
async function editNote(id: string, event?: MouseEvent) {
  if (event?.target instanceof Element && event.target.closest('a, button, input, select, textarea, summary, [contenteditable="true"]')) return
  confirmingDeleteId.value = ''
  if (!editingIds.value.includes(id)) editingIds.value = [...editingIds.value, id]
  await nextTick()
  const card = Array.from(notesList.value?.querySelectorAll<HTMLElement>('[data-note-id]') ?? []).find(el => el.dataset.noteId === id)
  card?.querySelector<HTMLElement>('[contenteditable="true"], textarea')?.focus({ preventScroll: true })
}
const PAGE_SIZE = 10
const limit = ref(PAGE_SIZE)
const notesList = ref<HTMLElement>()
const highlighted = ref('')
const refreshError = ref('')
const notes = computed(() => {
  const ids = new Set(data.kbs.filter(kb => kb.notes).map(kb => kb.id))
  return Object.values(data.docs).filter(doc => ids.has(doc.kbId ?? '') && !data.isDocArchived(doc.id))
    .sort((a, b) => Number(pinnedIds.value.includes(b.id)) - Number(pinnedIds.value.includes(a.id)) || (Date.parse(b[sort.value]) || Date.parse(b.created) || 0) - (Date.parse(a[sort.value]) || Date.parse(a.created) || 0) || b.id.localeCompare(a.id))
})
const availableTags = computed(() => [...new Set([...tagNames.value, ...notes.value.flatMap(note => note.tags ?? [])])].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const tagCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const note of notes.value) for (const tag of note.tags ?? []) counts.set(tag, (counts.get(tag) ?? 0) + 1)
  return counts
})
const matching = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return notes.value.filter(doc => (selectedTag.value === null ? !doc.tags?.length : !selectedTag.value || doc.tags?.includes(selectedTag.value))
    && (!needle || `${doc.title}\n${resolvedRichContent(doc).markdown}\n${(doc.tags ?? []).join(' ')}`.toLocaleLowerCase().includes(needle)))
})
// 筛选、分页或远端刷新不能卸载正在输入的卡片，尤其是浏览器草稿写入失败时。
const retainedEdits = computed(() => notes.value.filter(note => editingIds.value.includes(note.id)
  && !matching.value.slice(0, limit.value).some(item => item.id === note.id)))
const visible = computed(() => [...matching.value.slice(0, limit.value), ...retainedEdits.value])
const visibleImages = computed(() => new Map(visible.value.map(note => [note.id, notePreviewImages(resolvedRichContent(note))])))
const knowledgeSync = useWeKnoraDocuments(() => data.apiLive ? visible.value.map(note => note.id) : [], () => visible.value.map(note => note.updated))
const loading = computed(() => ['idle', 'loading'].includes(data.loadStates.docs) || ['idle', 'loading'].includes(data.loadStates.knowledge))
const loadError = computed(() => refreshError.value || data.loadErrors.docs || data.loadErrors.knowledge)
watch([query, selectedTag, sort], () => {
  limit.value = PAGE_SIZE
  confirmingDeleteId.value = ''
  notesList.value?.scrollTo?.({ top: 0 })
})
function onCreated(id: string) {
  query.value = ''
  selectedTag.value = ''
  limit.value = PAGE_SIZE
  highlighted.value = id
  notesList.value?.scrollTo?.({ top: 0 })
}
function onEdited(sourceId: string, savedId: string) {
  closeEditing(sourceId)
  highlighted.value = savedId
  // 冲突另存副本时展示新记录；普通修改保留当前筛选和滚动位置。
  if (sourceId !== savedId) onCreated(savedId)
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
onMounted(() => void loadPins())
</script>

<template>
  <main class="notes-page">
    <header class="page-heading"><div><h1><i class="ri-sticky-note-line"></i>小记</h1></div><span>{{ notes.length }} 条记录</span></header>
    <div class="notes-workspace">
      <aside class="writing-pane" aria-label="新建小记区">
        <NoteComposer :key="`${session.currentUser.id}:new`" :loading="loading" :tag-options="availableTags" @tag-created="tagNames = [...new Set([...tagNames, $event])]" @saved="onCreated" />
      </aside>
      <section ref="notesList" class="notes-list" aria-label="小记清单">
        <div class="list-toolbar">
          <label class="search"><i class="ri-search-line"></i><input v-model="query" type="search" placeholder="搜索内容或标签" aria-label="搜索小记内容"></label>
          <label class="sort"><i class="ri-sort-desc"></i><select v-model="sort" aria-label="小记排序"><option value="updated">更新时间排序</option><option value="created">创建时间排序</option></select></label>
          <button class="refresh" :disabled="loading" title="刷新清单" aria-label="刷新小记清单" @click="reload"><i class="ri-refresh-line"></i></button>
        </div>
        <div class="tags-toolbar" aria-label="按标签筛选">
          <button class="tag-filter" :aria-pressed="selectedTag === ''" @click="selectedTag = ''">全部标签</button>
          <button v-for="tag in availableTags" :key="tag" class="tag-filter" :aria-pressed="selectedTag === tag" @click="selectedTag = selectedTag === tag ? '' : tag">{{ tag }} <span>{{ tagCounts.get(tag) ?? 0 }}</span></button>
          <button class="tag-filter" :aria-pressed="selectedTag === null" @click="selectedTag = selectedTag === null ? '' : null">无标签 <span>{{ notes.filter(note => !note.tags?.length).length }}</span></button>
          <button class="new-tag-toggle" :aria-expanded="addingTag" @click="addingTag = !addingTag"><i class="ri-add-line"></i>新增标签</button>
          <button :aria-expanded="managingTags" @click="managingTags = !managingTags">管理标签</button>
        </div>
        <div v-if="managingTags" class="new-tag-form" aria-label="标签管理">
          <select v-model="sourceTag" aria-label="待处理标签" :disabled="tagSaving"><option value="">选择标签</option><option v-for="tag in availableTags" :key="tag" :value="tag">{{ tag }}</option></select>
          <button :disabled="tagSaving || !sourceTag" @click="manageTag(false)">删除标签（保留文章）</button>
          <select v-model="targetTag" aria-label="合并到标签" :disabled="tagSaving"><option value="">合并到…</option><option v-for="tag in availableTags.filter(item => item !== sourceTag)" :key="tag" :value="tag">{{ tag }}</option></select>
          <button :disabled="tagSaving || !sourceTag || !targetTag || sourceTag === targetTag" @click="manageTag(true)">{{ tagSaving ? '保存中…' : '合并标签' }}</button>
        </div>
        <form v-if="addingTag" class="new-tag-form" @submit.prevent="createTag">
          <input v-model="newTag" aria-label="新标签名称" placeholder="标签名称" maxlength="32" :disabled="creatingTag">
          <button type="submit" :disabled="creatingTag || !newTag.trim()">{{ creatingTag ? '正在保存…' : tagError ? '重试新增' : '保存标签' }}</button>
          <span v-if="tagError" class="error" role="alert">{{ tagError }}</span>
        </form>
        <p v-if="tagsLoading" class="tag-status" role="status">正在读取标签…</p>
        <p v-if="pinError" class="error" role="alert">{{ pinError }}<button v-if="!pinsReady" @click="loadPins">重试读取置顶</button></p>
        <p v-if="tagsError" class="tag-status error" role="alert">{{ tagsError }}<button @click="loadTags">重试标签</button></p>
        <div class="list-summary"><span>{{ selectedTag === null ? '无标签 · ' : selectedTag ? `# ${selectedTag} · ` : '' }}{{ matching.length }} 条小记</span><span>按{{ sort === 'updated' ? '更新' : '创建' }}时间从新到旧</span></div>
        <p v-if="loadError" class="empty error" role="alert">小记读取失败，已有内容和草稿仍保留。<button @click="reload">重试加载</button></p>
        <p v-if="loading" class="empty" role="status">正在读取小记…</p>
        <p v-else-if="!loadError && !matching.length" class="empty">{{ query || selectedTag ? '没有找到相关小记，换个关键词或标签试试。' : '还没有小记，写下第一条想法吧。' }}</p>
        <p v-if="retainedEdits.length" class="tag-status" role="status">正在编辑的小记会保留在列表中，保存或收起后恢复筛选结果。</p>
        <article v-for="note in visible" :key="note.id" class="note-card" :class="{ highlighted: highlighted === note.id, editing: editingIds.includes(note.id) }" :data-note-id="note.id"
          tabindex="0" title="双击在卡片内编辑，键盘可按 Enter" :aria-label="`小记：${note.title}，双击或按 Enter 在卡片内编辑`"
          @dblclick="editNote(note.id, $event)" @keydown.enter.self.prevent="editNote(note.id)" @keydown.esc="cancelDeleteOnEscape(note.id, $event)" @keydown="openNoteMenu($event, note.id)" @contextmenu="openNoteMenu($event, note.id)">
          <div class="note-meta"><span class="author">{{ authorName(note.author) }}</span><time>{{ sort === 'updated' ? '更新于' : '创建于' }} {{ timeLabel(note[sort] || note.created) }}</time><span class="note-meta-trailing"><span v-if="highlighted === note.id && !editingIds.includes(note.id)" class="saved">已保存</span><span v-if="noteCounts[note.id] !== undefined && !editingIds.includes(note.id)" class="note-count" :aria-label="`正文 ${noteCounts[note.id]?.toLocaleString('zh-CN')} 字`">{{ noteCounts[note.id]?.toLocaleString('zh-CN') }} 字</span></span></div>
          <NoteComposer v-if="editingIds.includes(note.id)" :key="`${session.currentUser.id}:${note.id}`" :note="note" inline autofocus :tag-options="availableTags"
            @tag-created="tagNames = [...new Set([...tagNames, $event])]" @saved="onEdited(note.id, $event)" @close="closeEditing(note.id)" />
          <template v-else>
          <div class="note-body"><MarkdownEditor :ref="value => setNoteEditor(note.id, value)" :model-value="resolvedRichContent(note).markdown || note.title" :document="resolvedRichContent(note).document" :readonly="true" bare advanced allow-h1 :min-height="0" @character-count="noteCounts[note.id] = $event" /></div>
          <div v-if="visibleImages.get(note.id)?.length" class="note-images" aria-label="小记图片">
            <button v-for="(image, index) in visibleImages.get(note.id)" :key="`${index}:${image.src}`" type="button"
              class="note-image-thumb" :aria-label="`查看第 ${index + 1} 张图片大图${image.alt ? `：${image.alt}` : ''}`" @click="openImage(image, $event)">
              <img :src="image.src" :alt="image.alt" loading="lazy" decoding="async">
            </button>
          </div>
          <div v-if="note.tags?.length" class="card-tags"><button v-for="tag in note.tags" :key="tag" @click="selectedTag = tag"># {{ tag }}</button></div>
          <WeKnoraDocumentStatus v-if="data.apiLive" :state="knowledgeSync.states.value[note.id]" :error="knowledgeSync.error.value" @retry="knowledgeSync.refresh" />
          <div class="note-actions">
            <button @click="noteEditors.get(note.id)?.copyFormatted()">复制内容</button>
            <button :disabled="!pinsReady || pinSaving" :aria-pressed="pinnedIds.includes(note.id)" @click="togglePin(note.id)"><i class="ri-pushpin-line"></i>{{ pinnedIds.includes(note.id) ? '取消置顶' : '置顶' }}</button>
            <button @click="copyText(docShareUrl(note.id), '公开分享链接已复制')"><i class="ri-link"></i>复制分享链接</button>
            <button @click="ui.shareToChat(chatShareLink(note.title, docShareUrl(note.id)))"><i class="ri-chat-forward-line"></i>分享到聊天</button>
            <button class="card-edit" aria-label="编辑小记" @click="editNote(note.id)"><i class="ri-edit-line"></i>编辑</button>
            <button class="card-delete" :aria-label="`${confirmingDeleteId === note.id ? '取消删除' : '删除'}小记：${note.title}`" :aria-expanded="confirmingDeleteId === note.id" :title="confirmingDeleteId === note.id ? '收起删除确认' : '移入回收站，7 天内可恢复'"
              :disabled="!!deletingId" @click="toggleDeleteConfirmation(note.id)"><i :class="confirmingDeleteId === note.id ? 'ri-close-line' : 'ri-delete-bin-line'"></i>{{ deletingId === note.id ? '删除中…' : confirmingDeleteId === note.id ? '取消删除' : '删除' }}</button>
            <RouterLink :to="{ path: `/kb/${note.kbId}`, query: { doc: note.id } }">完整文档 <i class="ri-arrow-right-up-line"></i></RouterLink>
          </div>
          <div v-if="confirmingDeleteId === note.id" class="card-delete-confirm" role="group" :aria-label="`确认删除小记：${note.title}`">
            <span>将此小记移入回收站？7 天内可恢复。</span>
            <span v-if="!data.apiLive" class="card-delete-offline">服务尚未连接，暂时不能删除。</span>
            <button type="button" :disabled="!!deletingId" @click="toggleDeleteConfirmation(note.id)">取消</button>
            <button type="button" class="card-delete-submit" :disabled="!!deletingId || !data.apiLive" @click="deleteNote(note.id)">{{ deletingId === note.id ? '删除中…' : '确认删除' }}</button>
          </div>
          <p v-if="deleteError?.id === note.id" class="card-delete-error" role="alert">{{ deleteError.message }}</p>
          </template>
        </article>
        <button v-if="matching.length > limit" class="load-more" @click="limit += PAGE_SIZE">加载更多（还有 {{ matching.length - limit }} 条）</button>
        <ContextActionMenu ref="contextMenu" />
      </section>
    </div>
    <Teleport to="body">
      <div v-if="previewImage" ref="lightbox" class="note-image-lightbox" role="dialog" aria-modal="true"
        :aria-label="previewImage.alt || '小记图片预览'" tabindex="-1" @click.self="closeImage" @keydown.esc.stop="closeImage">
        <button type="button" class="note-image-close" aria-label="关闭图片预览" @click="closeImage"><i class="ri-close-line"></i></button>
        <img :src="previewImage.src" :alt="previewImage.alt">
      </div>
    </Teleport>
  </main>
</template>

<style scoped>
.notes-page { width: 100%; max-width: none; margin: 0 auto; padding: 12px 28px 24px; box-sizing: border-box; color: var(--wao-text-2); }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
h1 { display: flex; gap: 10px; align-items: center; font-size: 18px; margin: 0; color: var(--wao-text); }
h1 i { color: #7a5cc6; }
.page-heading p { margin: 0; color: var(--wao-text-faint); font-size: 13px; }
.page-heading > span { color: var(--wao-text-faint); white-space: nowrap; font-size: 12px; }
.notes-workspace { display: grid; grid-template-columns: minmax(300px, 0.85fr) minmax(360px, 1.15fr); gap: 28px; align-items: start; }
.writing-pane { min-width: 0; position: sticky; top: 20px; }
.writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: min(650px, calc(100dvh - 210px)); max-height: calc(100dvh - 155px); }
.notes-list { min-width: 0; padding: 16px 20px; border-left: 1px solid var(--wao-line); background: var(--wao-bg-sub); }
.list-toolbar, .search, .note-meta, .note-actions, .sort { display: flex; align-items: center; gap: 10px; }
.list-toolbar { flex-wrap: wrap; margin-bottom: 16px; gap: 8px; }
button, a, select { color: var(--wao-text-3); font-size: 12px; }
button { cursor: pointer; border: 0; border-radius: 7px; background: transparent; padding: 7px 9px; }
button:hover, button[aria-pressed="true"] { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
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
.note-meta-trailing { display: inline-flex; align-items: center; gap: 12px; margin-left: auto; white-space: nowrap; }
.note-count { color: var(--wao-text-faint); font-variant-numeric: tabular-nums; }
.author { color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.saved { color: var(--wao-success-strong); margin-left: auto; }
.note-body { overflow-wrap: anywhere; line-height: 1.8; font-size: 14px; max-height: 500px; overflow: auto; }
.note-body :deep(p:first-child) { margin-top: 0; }
.note-body :deep(img.md-img) { display: none !important; }
.note-body :deep(.character-count) { display: none !important; }
.note-body :deep(pre), .note-body :deep(table) { max-width: 100%; overflow: auto; }
.note-images { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.note-image-thumb { width: 112px; height: 76px; padding: 4px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-sub); overflow: hidden; }
.note-image-thumb:hover, .note-image-thumb:focus-visible { border-color: var(--wao-primary); outline: 2px solid var(--wao-primary); outline-offset: 1px; }
.note-image-thumb img { width: 100%; height: 100%; object-fit: contain; display: block; }
.note-image-lightbox { position: fixed; inset: 0; z-index: 1200; display: grid; place-items: center; padding: 32px; background: rgba(15, 18, 28, .82); cursor: zoom-out; }
.note-image-lightbox img { max-width: min(96vw, 1600px); max-height: 90vh; object-fit: contain; border-radius: 8px; box-shadow: 0 18px 56px rgba(0, 0, 0, .38); cursor: default; }
.note-image-close { position: fixed; top: 16px; right: 18px; width: 36px; height: 36px; border: 0; border-radius: 50%; background: rgba(255, 255, 255, .16); color: #fff; font-size: 20px; }
.note-image-close:hover { background: rgba(255, 255, 255, .28); color: #fff; }
.card-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 14px; }
.card-tags button { color: var(--wao-primary-text); overflow-wrap: anywhere; max-width: 100%; padding: 4px 7px; }
.note-actions { margin-top: 14px; border-top: 1px solid var(--wao-line-control); padding-top: 6px; justify-content: space-between; }
.note-actions a { text-decoration: none; margin-left: auto; }
.card-edit { display: inline-flex; gap: 4px; align-items: center; }
.card-delete { display: inline-flex; gap: 4px; align-items: center; color: var(--wao-danger-strong); }
.card-delete:hover { color: var(--wao-danger-strong); background: var(--wao-danger-bg); }
.card-delete-confirm { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; margin-top: 8px; padding: 8px 10px; border-radius: 8px; background: var(--wao-danger-bg); color: var(--wao-text-2); font-size: 12px; }
.card-delete-confirm span:first-child { margin-right: auto; }
.card-delete-confirm button { border: 1px solid var(--wao-line-control); background: var(--wao-bg-card); }
.card-delete-confirm .card-delete-submit { border-color: var(--wao-danger-strong); color: var(--wao-danger-strong); font-weight: 600; }
.card-delete-error { margin: 6px 0 0; color: var(--wao-danger-strong); font-size: 12px; }
.note-actions { flex-wrap: wrap; }
.empty { text-align: center; padding: 36px 12px; color: var(--wao-text-faint); font-size: 13px; }
.error { color: var(--wao-danger-strong); font-size: 12px; }
.load-more { display: block; margin: 20px auto; }
@media (min-width: 821px) {
  .notes-page { height: 100%; min-height: 0; display: flex; flex-direction: column; overflow: hidden; padding-bottom: 20px; }
  .page-heading { flex: none; }
  .notes-workspace { flex: 1; min-height: 0; align-items: stretch; overflow: hidden; }
  .writing-pane { position: static; min-height: 0; display: flex; flex-direction: column; overflow: auto; overscroll-behavior: contain; }
  .writing-pane :deep(.note-composer:not(.fullscreen)) { flex: 1; min-height: 0; max-height: none; }
  .notes-list { min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
}
@media (max-width: 1100px) { .notes-page { padding: 20px; } .notes-workspace { gap: 18px; grid-template-columns: minmax(280px, .9fr) minmax(300px, 1.1fr); } .notes-list { padding-left: 18px; } .search { order: 3; flex-basis: 100%; margin: 0; } .sort { margin-left: auto; } }
@media (max-width: 820px) { .notes-workspace { grid-template-columns: minmax(0, 1fr); gap: 24px; } .writing-pane { position: static; } .writing-pane :deep(.note-composer:not(.fullscreen)) { min-height: 380px; max-height: none; } .writing-pane :deep(.md-body) { min-height: 230px !important; } .notes-list { border-left: 0; border-top: 1px solid var(--wao-line); padding: 16px 10px; } }
@media (max-width: 600px) { .notes-page { padding: 16px 12px 40px; } .note-card { padding: 16px 12px 8px; } .sort { margin-left: 0; } .list-summary { flex-wrap: wrap; } }
</style>
