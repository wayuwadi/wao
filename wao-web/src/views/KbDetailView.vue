<script setup lang="ts">
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import { loadSheetRuntime } from '@/utils/knowledgeSheetRuntime'
import KnowledgeSheet from '@/components/knowledge/KnowledgeSheet.vue'
import PaneResizeHandle from '@/components/ui/PaneResizeHandle.vue'
import KnowledgeIconPicker from '@/components/knowledge/KnowledgeIconPicker.vue'
import NoteWidthSwitch from '@/components/notes/NoteWidthSwitch.vue'
import { useNoteWritingWidth } from '@/composables/noteWritingWidth'
import { apiMoveKnowledgeNode, fetchDoc as fetchMovedDoc } from '@/api/board'
import { directoryParents, directoryRows } from '@/utils/knowledgeDirectory'
import { isColoredKnowledgeIcon } from '@/utils/knowledgeIcons'
/**
 * 知识库详情：左边是文件夹 / 文档树，右边是文档本身。
 *
 * 文档不再有「查看 / 编辑」两态 —— 打开就能写，跟任务描述一个手感（ADR-015）。
 * 输入停顿即自动保存，右上角显示保存状态与此刻同在这篇文档里的人（ADR-017）。
 */
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useTabsStore } from '@/stores/tabs'
import { useDocPresence } from '@/composables/docPresence'
import { openEditorSelectionTask } from '@/composables/editorSelectionTask'
import { toast } from '@/composables/toast'
import { copyToClipboard } from '@/utils/clipboard'
import { docOriginalUrl } from '@/utils/share'
import NoteShareDialog from '@/components/notes/NoteShareDialog.vue'
const shareNoteId = ref('')
import { absoluteTime, relativeTime } from '@/utils/time'
import { blocksToText, textToBlocks } from '@/utils/doc'
import { resolvedRichContent, richHeadings } from '@/utils/richDoc'
import { useNow } from '@/composables/now'
import ResourceMenu from '@/components/ui/ResourceMenu.vue'
import { useKnowledgeFavoritesStore } from '@/stores/knowledgeFavorites'
import SaveHint from '@/components/ui/SaveHint.vue'
import { useFavoritesStore } from '@/stores/favorites'
import {
  apiPatchKb,
  apiCreateDocTemplate,
  apiDeleteDocTemplate,
  apiPatchDocTemplate,
  fetchDocTemplates,
} from '@/api/board'
import type { DocLink, DocTemplate, FileItem, RichDocContent } from '@/types'
// 异步加载，理由同 TaskDrawer：ProseMirror 不进首屏包。
const KnowledgeDrawing = defineAsyncComponent(() => import('@/views/DrawingsView.vue'))
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))

const sheetEditor = ref<InstanceType<typeof KnowledgeSheet> | null>(null)
async function flushSheet() { await sheetEditor.value?.flush() }
onBeforeRouteLeave(flushSheet)
onBeforeRouteUpdate(flushSheet)
const route = useRoute()
const router = useRouter()
const data = useDataStore()
const session = useSessionStore()
const contentWidth = useNoteWritingWidth()
const ui = useUiStore()
const tabs = useTabsStore()
const favorites = useFavoritesStore()
const knowledgeFavorites = useKnowledgeFavoritesStore()
const tocOpen = ref(true)

const kbId = computed(() => String(route.params.id))
const directoryWidth = ref(262)
const directoryCollapsed = ref(false)
const kb = computed(() => data.kbById(kbId.value))

const firstDocId = () => {
  for (const f of kb.value?.folders ?? []) if (f.docs.length) return f.docs[0]!
  return null
}
const docId = ref<string | null>((route.query.doc as string) || null)
// 每次打开另一篇文档默认展开；同篇文档内仍尊重手动收起。
watch(docId, () => { tocOpen.value = true })
const folderOpen = reactive<Record<string, boolean>>({})
const scrollEl = ref<HTMLElement | null>(null)
const toolbarHost = ref<HTMLElement | null>(null)
const outlineTrigger = ref<HTMLButtonElement | null>(null)
function closeOutline() {
  tocOpen.value = false
  outlineTrigger.value?.focus()
}
const titleEl = ref<HTMLInputElement | null>(null)
watch([titleEl, () => route.query.findRequest, () => route.query.find, () => route.query.match, () => route.query.doc], async () => {
  if (route.query.match !== 'title' || typeof route.query.find !== 'string') return
  await nextTick()
  const input = titleEl.value, term = route.query.find
  if (!input || !term) return
  const start = input.value.toLowerCase().indexOf(term.toLowerCase())
  input.scrollIntoView?.({ block: 'center' })
  input.focus()
  if (start >= 0) input.setSelectionRange(start, start + term.length)
})

/** 正在重命名的文件夹 id。 */
const renamingFolder = ref<string | null>(null)
const folderNameDraft = ref('')
const folderRenameSaving = ref(false)
const folderRenameError = ref('')
function startFolderRename(id: string) {
  folderNameDraft.value = kb.value?.folders.find(folder => folder.id === id)?.name ?? ''
  folderRenameError.value = ''
  renamingFolder.value = id
  nextTick(() => treeElement.value?.querySelector<HTMLInputElement>('.folder-input')?.select())
}
const archivedOpen = ref(false)
const archivedLoading = ref(false)
const archivedError = ref(false)
const { peers, setCaretLine, markTyping } = useDocPresence(docId)
/** 相对时间文案要自己走起来，否则「刚刚」会一直停在打开那一刻。 */
const now = useNow()

watch(
  () => [route.params.id, route.query.doc],
  () => {
    flushCurrentDoc()
    docId.value = typeof route.query.doc === 'string' ? route.query.doc : null
  },
)
onMounted(() => {
  void knowledgeFavorites.load()
})


const doc = computed(() => (docId.value && data.docById(docId.value)
  && data.docById(docId.value)?.bodyLoaded !== false ? data.docOf(docId.value) : null))
const base = computed(() => doc.value?.base ?? null)
const pageType = computed(() => base.value?.pageType ?? 'document')
function pageIcon(id: string) { return ({ spreadsheet: 'ri-table-line', drawing: 'ri-artboard-line', document: 'ri-file-text-line' })[data.docById(id)?.pageType ?? 'document'] }
const editingPrivateNote = computed(() => kbId.value === 'kb-notes' || base.value?.kbId === 'kb-notes')
// 同一知识库仍复用一个页签，标题和地址指向当前文档，避免与“小记”模块同名。
watch([() => data.docs[docId.value ?? '']?.title, () => kb.value?.name, docId], () => {
  if (!kb.value) return
  const title = data.docs[docId.value ?? '']?.title
  tabs.setMeta(`kb:${kbId.value}`, { label: docId.value ? title || '未命名文档' : kb.value.name, icon: kb.value.icon })
}, { immediate: true })
const archivedDocs = computed(() => data.archivedDocsOf(kbId.value))
const currentArchived = computed(() => Boolean(docId.value && data.isDocArchived(docId.value)))

let archiveRequest = 0
let archiveLoadingKb = ''
async function loadArchivedDocs() {
  const targetKb = kbId.value
  if (!data.apiLive || (archivedLoading.value && archiveLoadingKb === targetKb)) return
  const request = ++archiveRequest
  archiveLoadingKb = targetKb
  archivedLoading.value = true
  archivedError.value = false
  try {
    const loaded = await data.refreshArchivedDocs(targetKb)
    if (request !== archiveRequest || kbId.value !== targetKb) return
    archivedError.value = !loaded
    if (docId.value && data.isDocArchived(docId.value)) archivedOpen.value = true
  } catch {
    if (request === archiveRequest && kbId.value === targetKb) archivedError.value = true
  } finally {
    if (request === archiveRequest) archivedLoading.value = false
  }
}
watch([() => data.apiLive, kbId], ([live]) => {
  if (live) void loadArchivedDocs()
}, { immediate: true })
const author = computed(() => {
  if (base.value?.systemGenerated) return { name: '系统', initial: '系', color: '#7A5CC6' }
  const a = base.value?.author ?? ''
  if (a === 'auto') return { name: '自动化', initial: '自', color: '#B9821F' }
  return session.memberById(a)
})
/** 正文：改一个字就走去抖写透，不需要「完成」按钮。 */
const body = computed({
  get: () => doc.value?.text ?? '',
  set: (v: string) => {
    if (!docId.value) return
    data.setDocEdit(docId.value, { text: v })
    markTyping()
  },
})
const bodyContent = computed(() => doc.value?.content ?? null)
function onContentChange(content: RichDocContent) {
  if (!docId.value) return
  data.setDocEdit(docId.value, { content })
  markTyping()
}
const currentSaveState = computed(() => docId.value ? data.saveState(`doc:${docId.value}`) : null)
const saving = computed(() => currentSaveState.value?.saving ?? false)
const saveFailed = computed(() => currentSaveState.value?.failed ?? false)
/** 上一次写透成功的时刻，给「自动保存成功 14:32」用。 */
const docSavedAt = computed(() =>
  docId.value ? data.saveState(`doc:${docId.value}`).savedAt : null,
)
const createdLabel = computed(() =>
  base.value?.created ? relativeTime(base.value.created, now.value) : '—',
)
const updatedLabel = computed(() =>
  base.value?.updated ? relativeTime(base.value.updated, now.value) : '—',
)

const TYPE_ICON: Record<string, string> = {
  task: 'ri-checkbox-circle-line',
  doc: 'ri-file-text-line',
}
function linkName(l: DocLink) {
  if (l.type === 'task') return '任务 · ' + (data.taskById(l.id)?.title ?? '')
  return '文档 · ' + (data.docOf(l.id).title ?? '')
}
function goLink(l: DocLink) {
  if (l.type === 'task') ui.openTask(l.id)
  else openDoc(l.id)
}
const docLinks = computed(() =>
  (base.value?.links ?? []).filter(l => l.type !== 'project').map((l) => ({ icon: TYPE_ICON[l.type]!, label: linkName(l), link: l })),
)
/**
 * 任务把文档 id 保存在自己的 docs 中，因此这里按当前成员可见任务动态反查。
 * 不向文档再写一份任务关系，避免一端解除关联后另一端留下过期入口。
 */
const linkedTasks = computed(() => {
  if (!docId.value) return []
  return data.tasks
    .filter((task) => task.docs.includes(docId.value!))
    .map((task) => ({
      id: task.id,
      title: task.title || '未命名任务',
    }))
})
function openLinkedTask(id: string) {
  ui.openTask(id)
}
const backlinks = computed(() =>
  (base.value?.backlinks ?? []).map((b) => ({
    icon: TYPE_ICON[b.type]!,
    label: linkName(b),
    snippet: b.snippet,
    link: b,
  })),
)

/** 目录展示一至三级标题；旧文档标题没有层级时按二级处理。 */
const toc = computed(() => doc.value ? richHeadings(doc.value.content).filter((item) => item.level <= 3) : [])
const activeToc = ref(0)

function isOpen(fid: string) {
  return folderOpen[fid] !== false
}
const allFoldersOpen = computed(() => !!kb.value?.folders.length && Object.keys(directoryParents(kb.value)).every(key => isOpen(key)))
function resetFolderExpansion() {
  for (const id of Object.keys(folderOpen)) delete folderOpen[id]
}
function toggleAllFolders() {
  const expand = !allFoldersOpen.value
  for (const key of Object.keys(kb.value ? directoryParents(kb.value) : {})) folderOpen[key] = expand
}
// 工作区会保留页签实例，重新进入该知识库时仍恢复全部展开。
watch([() => tabs.activeKey, kbId], ([active, current], [previousActive, previousKb]) => {
  if (current !== previousKb || (active === `kb:${current}` && active !== previousActive)) resetFolderExpansion()
})
watch(
  [docId, () => kb.value?.folders.find(folder => folder.docs.includes(docId.value ?? ''))?.id],
  ([, folderId]) => { if (folderId) folderOpen[`f:${folderId}`] = true },
  { immediate: true },
)

async function loadSelectedDoc() {
  const id = docId.value
  if (id && await data.ensureDocLoaded(id) && docId.value === id)
    await data.restoreDocumentDraft(id)
}
watch([docId, () => data.loadStates.docs,
  () => docId.value ? data.docById(docId.value)?.bodyLoaded : undefined,
  () => docId.value ? data.docById(docId.value)?.detailLoaded : undefined], ([id, state]) => {
  if (id && state === 'loaded') void loadSelectedDoc()
}, { immediate: true })

function flushCurrentDoc() {
  if (docId.value && data.docPending(docId.value)) void data.persistDocNow(docId.value)
}
function onVisibilityChange() {
  if (document.visibilityState === 'hidden') flushCurrentDoc()
}
onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('pagehide', flushCurrentDoc)
})
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('pagehide', flushCurrentDoc)
  flushCurrentDoc()
})
function preloadSheet(id: string) {
  if (data.docById(id)?.pageType === 'spreadsheet') void loadSheetRuntime().catch(() => {})
}
async function selectDoc(id: string) {
  if (id === docId.value) return
  await flushSheet()
  flushCurrentDoc()
  docId.value = id
  void router.replace({ query: { ...route.query, doc: id } })
}
let preparingDocumentTask = false
async function addSelectionToTask(text: string) {
  if (!text.trim() || !docId.value || preparingDocumentTask) return
  preparingDocumentTask = true
  try {
    const id = docId.value
    const sourceTitle = doc.value?.title || '未命名文档'
    const sourceUrl = `/kb/${encodeURIComponent(kbId.value)}?doc=${encodeURIComponent(id)}`
    await openEditorSelectionTask(text, { id, title: sourceTitle, url: sourceUrl, docId: id })
  } catch {
    toast('暂时无法准备待办，请重试；文档内容未改变', 'ri-error-warning-line')
  } finally {
    preparingDocumentTask = false
  }
}
function openDoc(id: string) {
  const k = data.kbs.find((kk) => kk.folders.some((f) => f.docs.includes(id)))
  if (k && k.id !== kbId.value) router.push({ path: `/kb/${k.id}`, query: { doc: id } })
  else selectDoc(id)
}
const newDocFolderId = ref<string | null>(null)
const creatingDoc = ref(false)
const templatePickerOpen = ref(false)
async function createDocFrom(templateId?: string, type: 'document' | 'spreadsheet' | 'drawing' = 'document') {
  const folderId = newDocFolderId.value
  if (!folderId || creatingDoc.value) return
  creatingDoc.value = true
  const id = await data.addDoc(kbId.value, folderId, templateId, type)
  creatingDoc.value = false
  if (id) {
    newDocFolderId.value = null
    folderOpen[`f:${folderId}`] = true
    if (newDocParent.value) { await moveNode(newDocParent.value, `d:${id}`); newDocParent.value = null }
    selectDoc(id)
    toast(type === 'drawing' ? '已创建画板，可在草图中打开' : type === 'spreadsheet' ? '已创建表格' : templateId ? '已从模板创建文档' : '已创建空白文档', 'ri-file-add-line')
    // 新文档标题是空的，光标直接落在标题上，敲进去就是自己的标题
    nextTick(() => titleEl.value?.focus())
  } else {
    toast('文档没有保存到服务器，请重试', 'ri-error-warning-line')
  }
}

// ---- 文档模板 ----

const templates = ref<DocTemplate[]>([])
const templateLoading = ref(false)
const templateError = ref('')
const templateManagerOpen = ref(false)
const saveTemplateOpen = ref(false)
const savingTemplate = ref(false)
const templateForm = reactive({ name: '', description: '' })
const editingTemplateId = ref<string | null>(null)
const editingTemplateSaving = ref(false)
const editTemplateForm = reactive({ name: '', description: '', content: '' })
const editTemplateRichContent = ref<RichDocContent | null>(null)
type TemplateEditState = 'saved' | 'dirty' | 'saving' | 'error' | 'conflict'
interface StoredTemplateDraft {
  name: string
  description: string
  content: string
  richContent?: RichDocContent
  templateUpdatedAt: string
  savedAt: string
}
const templateEditState = ref<TemplateEditState>('saved')
const templateDraftRestored = ref(false)
const templateDraftStorageError = ref(false)
const editingTemplateExpectedUpdatedAt = ref('')
const templateConflictRefreshing = ref(false)
let hydratingTemplateEdit = false

const templateEditStatusText = computed(() => {
  if (templateEditState.value === 'conflict') {
    return '服务器已有其他窗口保存的新版本；本机草稿仍在，请选择处理方式'
  }
  if (templateDraftStorageError.value) return '本机草稿暂时无法保存，离开前请先保存到服务器'
  if (templateEditState.value === 'saving') return '正在保存到服务器…'
  if (templateEditState.value === 'error') return '保存失败，本机草稿仍在，可直接重试'
  if (templateEditState.value === 'dirty') {
    return templateDraftRestored.value ? '已恢复本机草稿，尚未同步' : '本机草稿已保存，尚未同步'
  }
  return '已保存到服务器'
})

function templateDraftKey(templateId: string) {
  const memberId = session.userId
  if (!memberId) return null
  return `wao-doc-template-draft-v1:${encodeURIComponent(memberId)}:${encodeURIComponent(templateId)}`
}

function sameAsTemplate(template: DocTemplate) {
  return editTemplateForm.name === template.name
    && editTemplateForm.description === template.description
    && JSON.stringify(editTemplateRichContent.value ?? { markdown: editTemplateForm.content })
      === JSON.stringify(template.content ?? { markdown: blocksToText(template.blocks) })
}

function removeTemplateDraft(templateId: string) {
  const key = templateDraftKey(templateId)
  if (!key) return
  try {
    localStorage.removeItem(key)
  } catch {
    // 服务端保存结果不应被浏览器存储权限错误反转成失败；状态区会提示后续编辑无法落本机。
  }
}

function readTemplateDraft(template: DocTemplate): StoredTemplateDraft | null {
  const key = templateDraftKey(template.id)
  if (!key) return null
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const draft = JSON.parse(raw) as Partial<StoredTemplateDraft>
    if (typeof draft.name !== 'string'
      || typeof draft.description !== 'string'
      || typeof draft.content !== 'string') {
      removeTemplateDraft(template.id)
      return null
    }
    if (draft.name === template.name
      && draft.description === template.description
      && draft.content === blocksToText(template.blocks)) {
      removeTemplateDraft(template.id)
      return null
    }
    return {
      name: draft.name,
      description: draft.description,
      content: draft.content,
      richContent: draft.richContent,
      templateUpdatedAt: typeof draft.templateUpdatedAt === 'string' ? draft.templateUpdatedAt : '',
      savedAt: typeof draft.savedAt === 'string' ? draft.savedAt : '',
    }
  } catch {
    removeTemplateDraft(template.id)
    return null
  }
}

function persistTemplateDraft(template?: DocTemplate) {
  const id = editingTemplateId.value
  const current = template ?? templates.value.find((item) => item.id === id)
  if (!id || !current || hydratingTemplateEdit || editingTemplateSaving.value) return
  if (sameAsTemplate(current)) {
    removeTemplateDraft(id)
    templateEditState.value = 'saved'
    templateDraftRestored.value = false
    templateDraftStorageError.value = false
    return
  }
  const key = templateDraftKey(id)
  if (!key) {
    if (templateEditState.value !== 'conflict') templateEditState.value = 'dirty'
    templateDraftStorageError.value = true
    return
  }
  try {
    localStorage.setItem(key, JSON.stringify({
      name: editTemplateForm.name,
      description: editTemplateForm.description,
      content: editTemplateForm.content,
      richContent: editTemplateRichContent.value ?? undefined,
      templateUpdatedAt: editingTemplateExpectedUpdatedAt.value || current.updatedAt,
      savedAt: new Date().toISOString(),
    } satisfies StoredTemplateDraft))
    if (templateEditState.value !== 'conflict') templateEditState.value = 'dirty'
    templateDraftStorageError.value = false
  } catch {
    if (templateEditState.value !== 'conflict') templateEditState.value = 'dirty'
    templateDraftStorageError.value = true
  }
}

watch(
  [
    () => editTemplateForm.name,
    () => editTemplateForm.description,
    () => editTemplateForm.content,
    () => editTemplateRichContent.value,
  ],
  () => persistTemplateDraft(),
  { flush: 'sync' },
)

async function loadTemplates() {
  if (!data.apiLive) return
  templateLoading.value = true
  templateError.value = ''
  try {
    templates.value = await fetchDocTemplates()
  } catch {
    templateError.value = '模板加载失败，请重试'
  } finally {
    templateLoading.value = false
  }
}
// AppLayout 在子页面挂载后才异步 hydrate；服务端连通的那一刻再拉模板，不能只看首次 mounted。
watch(() => data.apiLive, (live) => {
  if (live) void loadTemplates()
}, { immediate: true })
function openSaveTemplate() {
  templateForm.name = `${doc.value?.title || '未命名文档'}模板`
  templateForm.description = ''
  saveTemplateOpen.value = true
}
async function saveAsTemplate() {
  if (!docId.value || !templateForm.name.trim() || savingTemplate.value) return
  savingTemplate.value = true
  try {
    const created = await apiCreateDocTemplate({
      name: templateForm.name.trim(),
      description: templateForm.description.trim(),
      sourceDocId: docId.value,
      blocks: doc.value?.blocks ?? [],
      content: doc.value?.content,
    })
    templates.value.push(created)
    saveTemplateOpen.value = false
    toast('已另存为模板，可在新建文档时直接选择', 'ri-file-copy-2-line')
  } catch {
    toast('模板没有保存成功，请重试', 'ri-error-warning-line')
  } finally {
    savingTemplate.value = false
  }
}
function mayLeaveTemplateEdit() {
  if (!editingTemplateId.value) return true
  if (editingTemplateSaving.value) return false
  persistTemplateDraft()
  if (!templateDraftStorageError.value || templateEditState.value === 'saved') return true
  return window.confirm('本机草稿无法保存，离开编辑会丢失未保存内容。仍要离开吗？')
}
function closeTemplateManager() {
  if (!mayLeaveTemplateEdit()) return
  editingTemplateId.value = null
  templateManagerOpen.value = false
}
function finishTemplateEdit() {
  if (!mayLeaveTemplateEdit()) return
  editingTemplateId.value = null
}
function beginEditTemplate(template: DocTemplate) {
  if (editingTemplateId.value === template.id) return
  if (editingTemplateId.value && !mayLeaveTemplateEdit()) return
  const draft = readTemplateDraft(template)
  hydratingTemplateEdit = true
  editingTemplateId.value = null
  editTemplateForm.name = draft?.name ?? template.name
  editTemplateForm.description = draft?.description ?? template.description
  editTemplateForm.content = draft?.content ?? blocksToText(template.blocks)
  editTemplateRichContent.value = draft?.richContent ?? template.content ?? null
  editingTemplateExpectedUpdatedAt.value = draft?.templateUpdatedAt || template.updatedAt
  editingTemplateId.value = template.id
  templateEditState.value = draft?.templateUpdatedAt && draft.templateUpdatedAt !== template.updatedAt
    ? 'conflict'
    : draft ? 'dirty' : 'saved'
  templateDraftRestored.value = draft !== null
  templateDraftStorageError.value = false
  hydratingTemplateEdit = false
}
function onTemplateContentChange(value: RichDocContent) {
  editTemplateRichContent.value = value
  editTemplateForm.content = value.markdown
}
async function saveTemplateMeta(template: DocTemplate) {
  if (!editTemplateForm.name.trim()
    || editingTemplateSaving.value
    || templateEditState.value === 'conflict') return
  persistTemplateDraft(template)
  editingTemplateSaving.value = true
  templateEditState.value = 'saving'
  try {
    const updated = await apiPatchDocTemplate(template.id, {
      name: editTemplateForm.name.trim(),
      description: editTemplateForm.description.trim(),
      blocks: textToBlocks(editTemplateForm.content),
      ...(editTemplateRichContent.value ? { content: editTemplateRichContent.value } : {}),
      expectedUpdatedAt: editingTemplateExpectedUpdatedAt.value,
    })
    Object.assign(template, updated)
    hydratingTemplateEdit = true
    editTemplateForm.name = updated.name
    editTemplateForm.description = updated.description
    editTemplateForm.content = blocksToText(updated.blocks)
    editTemplateRichContent.value = updated.content ?? resolvedRichContent({ blocks: updated.blocks })
    editingTemplateExpectedUpdatedAt.value = updated.updatedAt
    hydratingTemplateEdit = false
    removeTemplateDraft(template.id)
    templateEditState.value = 'saved'
    templateDraftRestored.value = false
    templateDraftStorageError.value = false
    toast('模板名称、说明与正文已保存', 'ri-check-line')
  } catch (cause) {
    editingTemplateSaving.value = false
    persistTemplateDraft(template)
    const conflict = cause instanceof Error && cause.message.includes('模板已被其他窗口更新')
    templateEditState.value = conflict ? 'conflict' : 'error'
    toast(conflict ? '服务器模板已有新版本，本机草稿已保留' : '模板修改失败，请重试', 'ri-error-warning-line')
  } finally {
    editingTemplateSaving.value = false
  }
}
async function latestTemplateForConflict(templateId: string) {
  if (templateConflictRefreshing.value) return null
  templateConflictRefreshing.value = true
  try {
    const latest = await fetchDocTemplates()
    templates.value = latest
    return latest.find((item) => item.id === templateId) ?? null
  } catch {
    toast('服务器最新版没有加载成功，请重试', 'ri-error-warning-line')
    return null
  } finally {
    templateConflictRefreshing.value = false
  }
}
async function discardConflictedTemplateDraft(templateId: string) {
  const latest = await latestTemplateForConflict(templateId)
  if (!latest) return
  hydratingTemplateEdit = true
  editTemplateForm.name = latest.name
  editTemplateForm.description = latest.description
  editTemplateForm.content = blocksToText(latest.blocks)
  editTemplateRichContent.value = latest.content ?? resolvedRichContent({ blocks: latest.blocks })
  editingTemplateExpectedUpdatedAt.value = latest.updatedAt
  hydratingTemplateEdit = false
  removeTemplateDraft(templateId)
  templateDraftRestored.value = false
  templateDraftStorageError.value = false
  templateEditState.value = 'saved'
  toast('已载入服务器最新版', 'ri-refresh-line')
}
async function confirmConflictedTemplateOverwrite(templateId: string) {
  const latest = await latestTemplateForConflict(templateId)
  if (!latest) return
  editingTemplateExpectedUpdatedAt.value = latest.updatedAt
  templateEditState.value = 'dirty'
  templateDraftRestored.value = true
  persistTemplateDraft(latest)
  toast('已确认以本机草稿为准，请再次点击保存', 'ri-draft-line')
}
async function removeTemplate(template: DocTemplate) {
  if (!window.confirm(`删除文档模板「${template.name}」？已用该模板创建的文档不会受影响。`)) return
  try {
    await apiDeleteDocTemplate(template.id)
    removeTemplateDraft(template.id)
    templates.value = templates.value.filter((item) => item.id !== template.id)
    toast(`已删除模板「${template.name}」`, 'ri-delete-bin-line')
  } catch {
    toast('模板删除失败，请重试', 'ri-error-warning-line')
  }
}

// ---- 文档树拖拽 ----

const treeQuery = ref('')
// 列表只读取标题及草稿覆盖，避免搜索/排序时重复转换整篇富文本。
function documentTitle(id: string): string {
  return (data.docEdits[id]?.title ?? data.docById(id)?.title) || '未命名文档'
}
const noteTimeOrder = ref<'desc' | 'asc'>('desc')
function compareNoteTime(leftId: string, rightId: string): number {
  const timeOf = (id: string) => {
    const item = data.docById(id)
    const time = Date.parse(item?.updated || item?.created || '')
    return Number.isNaN(time) ? 0 : time
  }
  const difference = timeOf(leftId) - timeOf(rightId)
  return (noteTimeOrder.value === 'desc' ? -difference : difference) || leftId.localeCompare(rightId)
}
function homeDocIds(ids: string[]): string[] {
  return kb.value?.notes ? [...ids].sort(compareNoteTime) : ids
}
const treeElement = ref<HTMLElement | null>(null)
const currentFolderId = computed(
  () => kb.value?.folders.find((folder) => folder.docs.includes(docId.value ?? ''))?.id ?? '',
)
const mixedRows = computed(() => kb.value ? directoryRows(kb.value, documentTitle, folderOpen, treeQuery.value, kb.value.notes ? compareNoteTime : undefined) : [])
const draggingNode = ref<string | null>(null)
const dropTarget = ref<{ key: string; placement: 'before' | 'inside' | 'after' } | null>(null)
const directoryError = ref('')
let pendingMove: { kbId: string; node: string; parent: string; expectedParent: string; beforeSibling?: string | null } | null = null
function startNodeDrag(key: string, event: DragEvent) {
  draggingNode.value = key
  dropTarget.value = null
  event.dataTransfer?.setData('application/x-wao-directory', key)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function endNodeDrag() { draggingNode.value = null; dropTarget.value = null }
function dragLeaveNode(key: string, event: DragEvent) {
  if (dropTarget.value?.key === key && !(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) dropTarget.value = null
}
function dragOverNode(key: string, event: DragEvent) {
  const node = draggingNode.value
  if (!node || node === key || !kb.value || treeSaveState.value === 'saving') { dropTarget.value = null; return }
  const row = event.currentTarget as HTMLElement
  const ratio = (event.clientY - row.getBoundingClientRect().top) / row.getBoundingClientRect().height
  const placement = ratio < .25 ? 'before' : ratio > .75 ? 'after' : 'inside'
  const parents = directoryParents(kb.value)
  const parent = placement === 'inside' ? key : parents[key] ?? ''
  if (node.startsWith('d:') && !parent) { dropTarget.value = null; return }
  let cursor = parent
  while (cursor) {
    if (cursor === node) { dropTarget.value = null; return }
    cursor = parents[cursor] ?? ''
  }
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dropTarget.value = { key, placement }
}
async function dropOnNode(key: string) {
  const target = dropTarget.value
  if (!target || target.key !== key || !kb.value) return
  const parents = directoryParents(kb.value)
  const parent = target.placement === 'inside' ? key : parents[key] ?? ''
  const siblings = Object.keys(parents).filter(candidate => parents[candidate] === parent && candidate !== draggingNode.value)
  const beforeSibling = target.placement === 'before' ? key
    : target.placement === 'after' ? siblings[siblings.indexOf(key) + 1] ?? null : null
  await moveNode(parent, draggingNode.value, beforeSibling)
}
async function moveNode(parent: string, node = draggingNode.value, beforeSibling?: string | null) {
  draggingNode.value = null
  dropTarget.value = null
  if (!kb.value || !node || treeSaveState.value === 'saving') return
  const parents = directoryParents(kb.value)
  let cursor = parent
  const visited = new Set<string>()
  while (cursor) {
    if (cursor === node || visited.has(cursor)) { directoryError.value = '不能移动到自身或后代节点中'; return }
    visited.add(cursor)
    cursor = parents[cursor] ?? ''
  }
  if (parents[node] === parent && beforeSibling === undefined) return
  pendingMove = { kbId: kb.value.id, node, parent, expectedParent: parents[node] ?? '', beforeSibling }
  await persistNodeMove()
}
async function persistNodeMove() {
  if (!pendingMove) return
  const move = { ...pendingMove }
  const targetKb = data.kbById(move.kbId)
  if (!targetKb) return
  directoryError.value = ''
  treeSaveState.value = 'saving'
  try {
    const parents = directoryParents(targetKb)
    const descendants = Object.keys(parents).filter(key => {
      const seen = new Set<string>()
      let cursor = key
      while (cursor && !seen.has(cursor)) {
        if (cursor === move.node) return true
        seen.add(cursor); cursor = parents[cursor] ?? ''
      }
      return false
    }).filter(key => key.startsWith('d:')).map(key => key.slice(2))
    for (const id of descendants) {
      if (data.docPending(id) && (!(await data.persistDocNow(id)) || data.docPending(id))) {
        throw new Error('请先完成文档保存，再重试移动目录')
      }
    }
    const previousFolders = new Map(descendants.map(id => [id, data.docById(id)?.folderId]))
    const saved = await apiMoveKnowledgeNode(targetKb.id, move.node, move.parent, move.expectedParent, move.beforeSibling)
    const currentKb = data.kbById(targetKb.id)
    if (currentKb) Object.assign(currentKb, saved)
    for (const folder of saved.folders) for (const id of folder.docs) {
      const loaded = data.docById(id)
      if (loaded) loaded.folderId = folder.id
    }
    folderOpen[move.parent] = true
    treeSaveState.value = 'saved'
    pendingMove = null
    if (data.apiLive) await Promise.all(descendants.filter(id => previousFolders.get(id)
      && previousFolders.get(id) !== data.docById(id)?.folderId).map(async id => {
        try { data.applyRemoteDoc(await fetchMovedDoc(id)) } catch { /* 协同心跳继续刷新，保留本机正文。 */ }
      }))
  } catch {
    treeSaveState.value = 'error'
    directoryError.value = '目录移动未完成，请先确认文档已保存后重试；若其他窗口已移动请刷新后重新选择'
  }
}
async function locateCurrentDoc() {
  treeQuery.value = ''
  let ancestor = kb.value ? directoryParents(kb.value)[`d:${docId.value}`] : ''
  const visited = new Set<string>()
  while (ancestor && !visited.has(ancestor)) { visited.add(ancestor); folderOpen[ancestor] = true; ancestor = kb.value ? directoryParents(kb.value)[ancestor] : '' }
  await nextTick()
  treeElement.value?.querySelector(`[data-node="d:${docId.value}"]`)?.scrollIntoView({ block: 'nearest' })
}
async function moveCurrentDoc(event: Event) {
  if (docId.value) await moveNode(`f:${(event.target as HTMLSelectElement).value}`, `d:${docId.value}`)
  ;(event.target as HTMLSelectElement).value = currentFolderId.value
}

const treeSaveState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
async function addFolder() {
  const id = await data.addFolder(kbId.value)
  if (id) {
    startFolderRename(id)
    toast('已创建文件夹', 'ri-folder-add-line')
  } else {
    toast('文件夹没有保存到服务器，请重试', 'ri-error-warning-line')
  }
}
function editTitle(e: Event) {
  if (docId.value) data.setDocEdit(docId.value, { title: (e.target as HTMLInputElement).value })
}
async function commitFolderName(folderId: string) {
  if (renamingFolder.value !== folderId || folderRenameSaving.value) return
  const name = folderNameDraft.value.trim()
  if (!name) { folderRenameError.value = '名称不能为空'; return }
  folderRenameSaving.value = true
  const saved = await data.renameFolder(kbId.value, folderId, name)
  folderRenameSaving.value = false
  if (saved) { renamingFolder.value = null; folderRenameError.value = '' }
  else folderRenameError.value = '重命名失败，输入已保留，按回车重试'
}
async function removeFolder(folderId: string) {
  const folder = kb.value?.folders.find((f) => f.id === folderId)
  if (!folder) return
  const archivedCount = archivedDocs.value.filter((item) => item.folderId === folderId).length
  if (!window.confirm(`删除文件夹「${folder.name}」及其中 ${folder.docs.length + archivedCount} 篇文档（含已归档）？删除后会进入回收站，7 天内可恢复。`)) return
  const gone = folder.docs.includes(docId.value ?? '')
  try { await data.deleteFolder(kbId.value, folderId) }
  catch { toast('删除失败，请先移出子分组后重试，目录未改变', 'ri-error-warning-line'); return }
  if (gone) docId.value = firstDocId()
  toast(`已把「${folder.name}」放进回收站，7 天内可恢复`, 'ri-delete-bin-line')
}
/**
 * 删掉整个知识库并回到列表。
 *
 * <p>入口放在这里是因为：新建知识库后会直接进详情页，此前想删只能退回列表、
 * 在卡片上找齿轮 —— 找不到就等于删不掉。删除同样是软删除，7 天内可恢复。
 */
async function removeKb() {
  const target = kb.value
  if (!target) return
  const docTotal = target.folders.reduce((total, folder) => total + folder.docs.length, 0)
    + archivedDocs.value.length
  if (!window.confirm(`删除知识库「${target.name}」及其中 ${docTotal} 篇文档（含已归档）？删除后会进入回收站，7 天内可恢复。`)) return
  const name = target.name
  await data.deleteKb(target.id)
  const next = tabs.close(`kb:${kbId.value}`)
  await router.push(next?.path ?? '/knowledge')
  toast(`已把「${name}」放进回收站，7 天内可恢复`, 'ri-delete-bin-line')
}

async function removeDoc(id: string) {
  const title = data.docOf(id).title || '未命名文档'
  if (!window.confirm(`删除文档「${title}」？删除后会进入回收站，7 天内可恢复。`)) return
  try { await data.deleteDoc(id) }
  catch { toast('删除失败，请先移出子节点后重试，文档未改变', 'ri-error-warning-line'); return }
  if (docId.value === id) docId.value = firstDocId()
  toast(`已把「${title}」放进回收站，7 天内可恢复`, 'ri-delete-bin-line')
}
async function archiveDoc(id: string) {
  const title = data.docOf(id).title || '未命名文档'
  if (!window.confirm(`归档文档「${title}」？正文会长期保留，并可随时从“已归档”恢复。`)) return
  if (await data.archiveDoc(id)) {
    archivedOpen.value = true
    toast(`已归档「${title}」`, 'ri-archive-line')
  } else {
    toast('文档归档失败，内容和目录未改变，请重试', 'ri-error-warning-line')
  }
}
async function unarchiveDoc(id: string) {
  const title = data.docOf(id).title || '未命名文档'
  if (await data.unarchiveDoc(id)) {
    const folderId = data.docById(id)?.folderId
    if (folderId) folderOpen[folderId] = true
    toast(`已把「${title}」恢复到原文件夹`, 'ri-inbox-unarchive-line')
  } else {
    toast('取消归档失败，文档仍在归档中，请重试', 'ri-error-warning-line')
  }
}
function tocGo(index: number) {
  const el = scrollEl.value?.querySelectorAll('.doc-body h1, .doc-body h2, .doc-body h3')[index] as HTMLElement | undefined
  const c = scrollEl.value
  if (el && c)
    c.scrollTop = Math.max(
      0,
      el.getBoundingClientRect().top - c.getBoundingClientRect().top + c.scrollTop - 24,
    )
}
function onDocScroll() {
  const c = scrollEl.value
  const headings = Array.from(c?.querySelectorAll('.doc-body h1, .doc-body h2, .doc-body h3') ?? []) as HTMLElement[]
  if (!c || !headings.length) return
  const top = c.getBoundingClientRect().top + 48
  let passed = -1
  headings.forEach((heading, index) => {
    if (heading.getBoundingClientRect().top <= top) passed = index
  })
  activeToc.value = Math.max(0, passed)
}

/** 分享菜单开关（文档与小记共用同一个入口）。 */
const exportingExcel = ref(false)
async function exportSheetExcel() {
  if (exportingExcel.value) return
  exportingExcel.value = true; moreMenuOpen.value = false
  try {
    if (!sheetEditor.value) throw new Error('表格尚未加载完成')
    await sheetEditor.value.exportExcel(doc.value?.title || '工作簿')
    toast('Excel 已导出', 'ri-download-line')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '导出失败，请重试', 'ri-error-warning-line')
  } finally { exportingExcel.value = false }
}
const shareMenu = ref(false)
const moreMenuOpen = ref(false)
watch(docId, () => { moreMenuOpen.value = false })
async function copyOriginalLink() {
  if (!docId.value) return
  shareMenu.value = false; moreMenuOpen.value = false
  const url = docOriginalUrl(kbId.value, docId.value)
  toast(await copyToClipboard(url) ? '知识库原文链接已复制，可用于定位文档' : '复制失败，请重试', 'ri-links-line')
}
function openDocumentShare() {
  if (!docId.value) return
  shareMenu.value = false
  shareNoteId.value = docId.value
}

/** 粘贴 / 拖入的图片存进图床，再把地址回插到光标处。 */
async function onUploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) {
    toast('图片超过 10MB，已跳过', 'ri-error-warning-line')
    done(null, '')
    return
  }
  const ref = await data.uploadImage(
    file,
    `${editingPrivateNote.value ? '小记' : '文档'} · ${doc.value?.title || '未命名文档'}`,
    { requirePersisted: true },
  )
  if (!ref) toast('图片上传失败，请重试', 'ri-error-warning-line')
  done(ref?.url ?? null, ref?.name ?? file.name)
}
async function onUploadFile(file: File, done: (item: FileItem | null) => void) {
  if (file.size > data.MAX_FILE_BYTES) {
    toast('文件超过 200MB，已跳过', 'ri-error-warning-line')
    done(null)
    return
  }
  const item = await data.uploadFile(file, undefined, true)
  if (!item) toast('文件上传失败，正文仍可继续编辑', 'ri-error-warning-line')
  done(item)
}
async function retryDocSave() {
  if (docId.value && !(await data.persistDocNow(docId.value))) {
    const reason = data.saveState(`doc:${docId.value}`).errorMessage
    toast(reason ? `${reason}；本机草稿保持完整` : '仍未保存到服务器，本机草稿保持完整', 'ri-error-warning-line')
  }
}
async function loadServerConflict() {
  if (docId.value && await data.useServerDoc(docId.value)) toast('已载入服务器版本', 'ri-refresh-line')
}
async function keepConflictCopy() {
  if (!docId.value) return
  const copyId = await data.saveDocConflictCopy(docId.value)
  if (!copyId) {
    toast('副本保存失败，本机草稿仍保留', 'ri-error-warning-line')
    return
  }
  selectDoc(copyId)
  toast('本机草稿已另存为冲突副本', 'ri-file-copy-2-line')
}
function homeDocumentTime(value: string): string {
  const date = new Date(value)
  if (!value || Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  const today = new Date()
  const day = date.toDateString() === today.toDateString() ? '今天' : `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  return `${day} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
const homeFolderOpen = reactive<Record<string, boolean>>({})
const docTotal = computed(() => kb.value?.folders.reduce((total, folder) => total + folder.docs.length, 0) ?? 0)
const homeOrder = ref<'directory' | 'updated' | 'title'>('directory')
const homeSearch = computed(() => treeQuery.value.trim().toLocaleLowerCase())
const homeGroups = computed(() => {
  const groups = (kb.value?.folders ?? []).map(folder => ({
    id: folder.id,
    name: folder.name,
    docs: homeDocIds(folder.docs).filter(id => !homeSearch.value
      || folder.name.toLocaleLowerCase().includes(homeSearch.value)
      || documentTitle(id).toLocaleLowerCase().includes(homeSearch.value)),
  })).filter(folder => !homeSearch.value || folder.docs.length || folder.name.toLocaleLowerCase().includes(homeSearch.value))
  if (homeOrder.value === 'directory') return groups
  const ids = [...new Set(groups.flatMap(folder => folder.docs))]
  const titleOf = documentTitle
  const timeOf = (id: string) => Date.parse(data.docById(id)?.updated || data.docById(id)?.created || '') || 0
  ids.sort((a, b) => homeOrder.value === 'updated'
    ? timeOf(b) - timeOf(a) || titleOf(a).localeCompare(titleOf(b), 'zh-CN', { numeric: true })
    : titleOf(a).localeCompare(titleOf(b), 'zh-CN', { numeric: true }))
  return ids.length ? [{ id: `sorted:${homeOrder.value}`, name: homeOrder.value === 'updated' ? '最近更新' : '按名称', docs: ids }] : []
})
const homeResultCount = computed(() => homeGroups.value.reduce((total, group) => total + group.docs.length, 0))
const allHomeGroupsOpen = computed(() => homeGroups.value.every(group => homeFolderOpen[group.id] !== false))
function toggleHomeGroups() {
  const expand = !allHomeGroupsOpen.value
  for (const group of homeGroups.value) homeFolderOpen[group.id] = expand
}
watch(kbId, () => { treeQuery.value = ''; homeOrder.value = 'directory' })
// 搜索结果始终可见，清空后恢复用户此前的折叠选择。
function isHomeGroupOpen(id: string) { return !!homeSearch.value || homeFolderOpen[id] !== false }
const kbSettingsOpen = ref(false)
const kbIconPickerExpanded = ref(false)
const kbSettings = reactive({ name: '', desc: '', icon: '' })
const kbSettingsBusy = ref(false)
const kbSettingsError = ref('')
const moveDocId = ref<string | null>(null)
const moveFolderId = ref('')
const directoryContext = ref<InstanceType<typeof ContextActionMenu> | null>(null)
const createItems = computed(() => [
  { id: 'doc', label: '新建文档', icon: 'ri-file-text-line' },
  { id: 'spreadsheet', label: '新建表格', icon: 'ri-table-line' },
  { id: 'drawing', label: '新建画板', icon: 'ri-artboard-line' },
  { id: 'template', label: '从模板新建…', icon: 'ri-file-copy-2-line' },
  { id: 'folder', label: '新建分组', icon: 'ri-folder-add-line' },
].filter(item => !kb.value?.notes || !['spreadsheet', 'drawing'].includes(item.id)))
function openDirectoryContext(event: MouseEvent, parent?: string) {
  void directoryContext.value?.openAt(event, '新增内容', createItems.value.map(item => ({ ...item, disabled: creatingDoc.value, run: () => parent ? createNested(item.id, parent) : createAction(item.id) })))
}
const folderItems = [
  { id: 'rename', label: '重命名', icon: 'ri-edit-line' },
  { id: 'delete', label: '移入回收站', icon: 'ri-delete-bin-line', danger: true },
]
const docItems = [
  { id: 'rename', label: '重命名', icon: 'ri-edit-line' },
  { id: 'move', label: '移动…', icon: 'ri-folder-transfer-line' },
  { id: 'template', label: '另存为模板…', icon: 'ri-file-copy-2-line' },
  { id: 'archive', label: '归档', icon: 'ri-archive-line' },
  { id: 'delete', label: '移入回收站', icon: 'ri-delete-bin-line', danger: true },
]
async function goHome() {
  await flushSheet()
  flushCurrentDoc()
  docId.value = null
  const query = { ...route.query }; delete query.doc
  void router.replace({ query })
}
function switchKb(event: Event) {
  flushCurrentDoc()
  void router.push(`/kb/${(event.target as HTMLSelectElement).value}`)
}
async function createNested(action: string, parent: string) {
  if (creatingDoc.value || treeSaveState.value === 'saving' || !kb.value) return
  if (action === 'folder') {
    const id = await data.addFolder(kbId.value)
    if (!id) { toast('分组创建失败，请重试', 'ri-error-warning-line'); return }
    await moveNode(parent, `f:${id}`)
    startFolderRename(id)
    return
  }
  const folderId = parent.startsWith('f:') ? parent.slice(2) : kb.value.folders.find(f => f.docs.includes(parent.slice(2)))?.id
  if (!folderId) return
  await createAction(action, folderId, parent)
}
const newDocParent = ref<string | null>(null)
async function createAction(action: string, folderId?: string, parent?: string) {
  if (creatingDoc.value) return
  newDocParent.value = parent ?? null
  if (action === 'folder') { await addFolder(); return }
  const target = folderId || currentFolderId.value || kb.value?.folders[0]?.id || await data.addFolder(kbId.value)
  if (!target) { toast('创建目录失败，请重试', 'ri-error-warning-line'); return }
  templatePickerOpen.value = action === 'template'
  newDocFolderId.value = target
  if (action === 'doc') await createDocFrom()
  if (action === 'spreadsheet' || action === 'drawing') await createDocFrom(undefined, action)
}
function folderAction(action: string, id: string) {
  if (action === 'rename') startFolderRename(id)
  else if (action === 'delete') void removeFolder(id)
}
async function docAction(action: string, id: string) {
  if (action === 'delete') { await removeDoc(id); return }
  if (action === 'archive') { await archiveDoc(id); return }
  if (action === 'move') {
    moveDocId.value = id
    moveFolderId.value = kb.value?.folders.find((folder) => folder.docs.includes(id))?.id || ''
    return
  }
  selectDoc(id)
  await nextTick()
  if (action === 'rename') { titleEl.value?.focus(); titleEl.value?.select() }
  if (action === 'template') openSaveTemplate()
}
async function confirmMove() {
  if (!moveDocId.value || !moveFolderId.value) return
  await moveNode(`f:${moveFolderId.value}`, `d:${moveDocId.value}`)
  if (treeSaveState.value === 'saved') { folderOpen[`f:${moveFolderId.value}`] = true; moveDocId.value = null }
}
function openKbIconSettings() {
  openKbSettings()
  kbIconPickerExpanded.value = true
}
function openKbSettings() {
  kbIconPickerExpanded.value = false
  kbSettings.name = kb.value?.name || ''
  kbSettings.desc = kb.value?.desc || ''
  kbSettings.icon = kb.value?.icon || 'wao-kb-icon-notebook'
  kbSettingsError.value = ''
  kbSettingsOpen.value = true
}
async function saveKbSettings() {
  if (!kbSettings.name.trim() || kbSettingsBusy.value || !kb.value) return
  kbSettingsBusy.value = true
  try {
    const patch = { name: kbSettings.name.trim(), desc: kbSettings.desc.trim(), icon: kbSettings.icon }
    await apiPatchKb(kbId.value, patch)
    Object.assign(kb.value, patch)
    tabs.setMeta(`kb:${kbId.value}`, { label: patch.name, icon: patch.icon })
    kbSettingsOpen.value = false
  } catch { kbSettingsError.value = '设置保存失败，输入已保留，请重试。' }
  finally { kbSettingsBusy.value = false }
}

</script>

<template>
  <ContextActionMenu ref="directoryContext" />
  <div v-if="!kb" class="missing">知识库不存在。</div>
  <div v-else class="kb-detail wao-page-frame" :class="{ 'directory-collapsed': directoryCollapsed }">
    <button type="button" class="directory-toggle" :title="directoryCollapsed ? '展开知识库目录' : '收起知识库目录'" :aria-label="directoryCollapsed ? '展开知识库目录' : '收起知识库目录'" aria-controls="kb-directory" :aria-expanded="!directoryCollapsed" @click="directoryCollapsed = !directoryCollapsed"><i class="ri-side-bar-line" aria-hidden="true"></i></button>
    <!-- 左栏 -->
    <div v-show="!directoryCollapsed" id="kb-directory" class="side" :style="{ '--directory-width': `${directoryWidth}px` }">
      <PaneResizeHandle :storage-key="`wao:directory-width:${session.currentUser.id}`" label="知识库目录宽度" :default-width="262" :max="520" @resize="directoryWidth = $event" />
      <div class="side-top">
        <button class="back" @click="router.push('/knowledge')">
          <i class="ri-arrow-left-line"></i>全部知识库
        </button>
        <div class="side-kb">
          <div class="side-icon" :style="{ background: isColoredKnowledgeIcon(kb.icon) ? 'transparent' : kb.color }"><i :class="kb.icon"></i></div>
          <select class="kb-switch" :value="kbId" aria-label="切换知识库" @change="switchKb"><option v-for="library in data.kbs" :key="library.id" :value="library.id">{{ library.name }}</option></select>
          <ResourceMenu label="知识库设置" :items="[{ id: 'settings', label: '设置', icon: 'ri-settings-3-line' }, { id: 'templates', label: '模板管理', icon: 'ri-file-copy-2-line' }]" @select="$event === 'settings' ? openKbSettings() : templateManagerOpen = true" />
        </div>

        <p v-if="creatingDoc" class="tree-save">正在创建文档…</p>
        <div v-if="treeSaveState !== 'idle'" class="tree-save" :class="treeSaveState">
          <i :class="treeSaveState === 'saving' ? 'ri-loader-4-line spin' : treeSaveState === 'error' ? 'ri-error-warning-line' : 'ri-check-line'"></i>
          <span>{{ treeSaveState === 'saving' ? '正在保存目录' : treeSaveState === 'error' ? '目录保存失败' : '目录已保存' }}</span>
          <button v-if="treeSaveState === 'error'" @click="persistNodeMove()">重试</button>
        </div>
      </div>

      <div class="tree-search"><label><i class="ri-search-line"></i><input v-model="treeQuery" type="search" aria-label="搜索本知识库文件夹和文档" placeholder="搜索文档" /></label><ResourceMenu label="新增内容" icon="ri-add-line" :items="createItems" @select="createAction($event)" /></div>
      <button class="library-home-link" :class="{ on: !docId }" @click="goHome"><i class="ri-home-4-line"></i>首页</button>
      <div class="directory-heading"><span><i class="ri-list-check"></i>目录</span><div class="directory-actions"><select v-if="kb.notes" v-model="noteTimeOrder" class="note-time-sort" aria-label="小记目录时间排序"><option value="desc">时间倒序</option><option value="asc">时间正序</option></select><button :disabled="!docId" title="定位当前文档" @click="locateCurrentDoc"><i class="ri-focus-3-line"></i></button><button :disabled="!kb.folders.length" :title="allFoldersOpen ? '折叠全部' : '展开全部'" :aria-label="allFoldersOpen ? '折叠全部目录' : '展开全部目录'" :aria-expanded="allFoldersOpen" @click="toggleAllFolders"><i :class="allFoldersOpen ? 'ri-collapse-diagonal-line' : 'ri-expand-diagonal-line'"></i></button></div></div>
      <p v-if="folderRenameError" role="alert">{{ folderRenameError }}</p>
      <div ref="treeElement" class="tree" @contextmenu.prevent="openDirectoryContext($event)">
        <p v-if="directoryError" role="alert">{{ directoryError }}</p>
        <div v-if="draggingNode?.startsWith('f:')" class="root-drop" @dragover.prevent @drop.prevent.stop="moveNode('', draggingNode, null)">拖到这里移至目录顶层</div>
        <p v-if="treeQuery.trim() && !mixedRows.length" class="folder-empty">没有匹配的文件夹或文档</p>
        <div v-for="node in mixedRows" :key="node.key" class="directory-row" :class="[node.kind === 'folder' ? 'folder-row' : 'doc-row', { selected: docId === node.id, 'drag-source': draggingNode === node.key, 'drop-before': dropTarget?.key === node.key && dropTarget.placement === 'before', 'drop-inside': dropTarget?.key === node.key && dropTarget.placement === 'inside', 'drop-after': dropTarget?.key === node.key && dropTarget.placement === 'after' }]"
          :style="{ '--node-depth': node.depth }" @contextmenu.prevent.stop="openDirectoryContext($event, node.key)" :draggable="renamingFolder !== node.id && treeSaveState !== 'saving' && !treeQuery.trim()"
          @dragstart="startNodeDrag(node.key, $event)" @dragend="endNodeDrag" @dragover="dragOverNode(node.key, $event)" @dragleave="dragLeaveNode(node.key, $event)" @drop.prevent.stop="dropOnNode(node.key)">
          <button v-if="node.hasChildren" class="tree-caret" :aria-label="`展开或收起${node.title}`" :aria-expanded="folderOpen[node.key] !== false" @click="folderOpen[node.key] = folderOpen[node.key] === false"><i class="chev" :class="folderOpen[node.key] === false ? 'ri-arrow-right-s-line' : 'ri-arrow-down-s-line'"></i></button>
          <span v-else class="tree-caret-spacer" aria-hidden="true"></span>
          <input v-if="node.kind === 'folder' && renamingFolder === node.id" class="folder-input" v-model="folderNameDraft" :disabled="folderRenameSaving"
            @keydown.enter.prevent="!$event.isComposing && commitFolderName(node.id)" @keydown.esc.prevent="renamingFolder = null; folderRenameError = ''" @blur="commitFolderName(node.id)" />
          <button v-else :class="[node.kind === 'folder' ? 'folder-btn' : 'doc-node', { on: docId === node.id }]" :title="node.title" :aria-label="node.title" :aria-expanded="node.kind === 'folder' ? folderOpen[node.key] !== false : undefined" :data-node="node.key" @pointerenter="preloadSheet(node.id)" @focus="preloadSheet(node.id)" :style="{ minWidth: 0 }" @click="node.kind === 'folder' ? (folderOpen[node.key] = folderOpen[node.key] === false) : selectDoc(node.id)">
            <i v-if="node.kind === 'doc'" :class="pageIcon(node.id)" aria-hidden="true"></i>
            <span :class="node.kind === 'folder' ? 'folder-name' : 'doc-node-title'" :style="{ color: docId === node.id ? 'var(--wao-primary)' : undefined }">{{ node.title }}</span>
          </button>
          <div class="tree-row-actions"><ResourceMenu :label="`${node.title}的更多操作`" :items="node.kind === 'folder' ? folderItems : docItems.filter(item => item.id !== 'template' || !data.docById(node.id)?.pageType || data.docById(node.id)?.pageType === 'document')" @select="node.kind === 'folder' ? folderAction($event, node.id) : docAction($event, node.id)" />
          <ResourceMenu :label="`在${node.title}新增内容`" icon="ri-add-line" :items="createItems" @select="createNested($event, node.key)" /></div>
        </div>

        <section v-if="archivedDocs.length || archivedError || archivedLoading" class="archive-tree">
          <button class="archive-head" @click="archivedOpen = !archivedOpen">
            <i :class="archivedOpen ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'"></i>
            <i class="ri-archive-line"></i>
            <span>已归档</span>
            <span class="folder-count wao-tabular">{{ archivedDocs.length }}</span>
          </button>
          <div v-if="archivedOpen" class="doc-tree archived-list">
            <div v-if="archivedLoading" class="archive-state"><i class="ri-loader-4-line spin"></i>正在读取归档…</div>
            <div v-else-if="archivedError" class="archive-state error">归档读取失败<button @click="loadArchivedDocs">重试</button></div>
            <div v-else-if="!archivedDocs.length" class="archive-state">还没有归档文档</div>
            <template v-else>
              <div v-for="item in archivedDocs" :key="item.id" class="doc-row archived-row">
                <button class="doc-node" :class="{ on: docId === item.id }" @pointerenter="preloadSheet(item.id)" @focus="preloadSheet(item.id)" @click="selectDoc(item.id)">
                  <i :class="[pageIcon(item.id), 'doc-node-icon']"></i>
                  <span class="doc-node-title">{{ item.title || '未命名文档' }}</span>
                </button>
                <button class="row-act" title="恢复到原文件夹" @click="unarchiveDoc(item.id)">
                  <i class="ri-inbox-unarchive-line"></i>
                </button>
              </div>
            </template>
          </div>
        </section>
      </div>

      <div v-if="kb.auto" class="sync-bar">
        <div class="sync-title"><i class="ri-archive-line" aria-hidden="true"></i>飞书群聊 · 历史归档</div>
        <div class="sync-sub">
          同步状态请前往自动化查看 ·
          <button class="link" @click="router.push('/automation')">查看自动化</button>
        </div>
      </div>
    </div>

    <!-- 文档区 -->
    <div class="document-pane" :class="{ 'content-narrow': !directoryCollapsed && contentWidth === 'narrow' && pageType === 'document', 'editor-workspace': pageType === 'spreadsheet' || pageType === 'drawing' }">
          <div v-if="directoryCollapsed && !doc" class="directory-home-bar" aria-hidden="true"></div>
          <div v-if="doc" class="doc-bar">
            <div class="doc-location" :title="`${kb.name} / ${doc.title || '未命名文档'}`">
              <span class="ellip library-name">{{ kb.name }}</span>
              <i class="ri-arrow-right-s-line b-sep"></i>
              <input v-if="pageType !== 'document'" class="sheet-title" :aria-label="pageType === 'drawing' ? '画布名称' : '表格名称'" :value="doc.title" @input="editTitle" />
              <span v-else class="ellip document-name">{{ doc.title || '未命名文档' }}</span>
              <span v-if="base?.auto" class="tag-auto">自动沉淀</span>
              <span v-if="currentArchived" class="tag-archived">已归档</span>
            </div>
            <span v-if="saving || saveFailed || docSavedAt" class="document-save-status"><SaveHint :saving="saving" :saved-at="docSavedAt" :failed="saveFailed" /></span>
            <NoteWidthSwitch v-if="pageType === 'document'" v-model="contentWidth" label="文档宽度" />

            <!-- 协同：只有此刻真的打开着这篇文档的人才会出现 -->
            <span class="collab">
              <span
                class="c-av"
                :title="`${session.currentUser.name}（我）`"
                :style="{ background: session.currentUser.color }"
              >
                {{ session.currentUser.initial }}
              </span>
              <span
                v-for="p in peers"
                :key="p.id"
                class="c-av peer"
                :title="`${p.name} 正在${p.editing ? '编辑' : '查看'}`"
                :style="{ background: p.color }"
                >{{ p.initial }}</span
              >
            </span>
            <span v-if="peers.length" class="realtime"
              ><span class="rt-dot"></span>{{ peers.length + 1 }} 人在线</span
            >

            <label v-if="docId && !currentArchived" class="move-document bar-move">移动到<select :value="currentFolderId" :disabled="treeSaveState === 'saving'" aria-label="移动当前文档到文件夹" @change="moveCurrentDoc"><option v-for="folder in kb.folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label>
            <button v-if="pageType === 'document'" ref="outlineTrigger" class="icon-act outline-toggle" title="文档大纲" aria-label="文档大纲" :aria-expanded="tocOpen" @click="tocOpen = !tocOpen"><i class="ri-layout-right-line"></i><span>大纲</span></button>
            <span class="doc-info" :title="`${author.name} · 创建于 ${absoluteTime(base?.created ?? '')} · 更新于 ${absoluteTime(base?.updated ?? '')}`"><i class="ri-information-line"></i><span class="sr-only">{{ author.name }} · 创建于 {{ createdLabel }} · 更新于 {{ updatedLabel }}</span></span>
            <button v-if="saveFailed && !data.docConflicts[docId!]" class="save-retry" @click="retryDocSave">重试</button>
            <button
              v-if="docId"
              class="icon-act bar-favorite"
              :class="{ faved: favorites.isFav('doc', docId) }"
              :title="favorites.isFav('doc', docId) ? '取消收藏' : '收藏到左侧菜单'"
              @click="favorites.toggle('doc', docId, doc?.title || '未命名文档', kbId)"
            >
              <i :class="favorites.isFav('doc', docId) ? 'ri-star-fill' : 'ri-star-line'"></i>
            </button>
            <button v-if="pageType === 'spreadsheet'" class="icon-act" title="导出 Excel" aria-label="导出 Excel" :disabled="exportingExcel" @click="exportSheetExcel"><i class="ri-file-excel-2-line"></i></button>
            <div v-if="pageType !== 'drawing'" class="share-wrap bar-share">
              <button class="icon-act" title="复制分享链接" @click="moreMenuOpen = false; shareMenu = !shareMenu">
                <i class="ri-links-line"></i>
              </button>
              <div v-if="shareMenu" class="menu-mask" @click="shareMenu = false"></div>
              <div v-if="shareMenu" class="share-menu">
                <div class="share-scope">
                  分享整篇{{ kb.notes ? '小记' : '文档' }} · 默认需登录，限时链接可免登录
                </div>
                <button class="share-item" @click="copyOriginalLink">
                  <i class="ri-file-text-line"></i>
                  <span class="share-main"><b>复制知识库原文链接</b><em>直接定位此文档，需登录并拥有访问权限</em></span>
                </button>
                <button class="share-item" @click="openDocumentShare()">
                  <i class="ri-external-link-line"></i>
                  <span class="share-main"><b>限时分享设置</b><em>生成或撤销链接，可选 1 小时、1 天或 7 天</em></span>
                </button>
              </div>
            </div>
            <div class="doc-more-wrap" :class="{ 'has-save-error': saveFailed }">
              <button class="icon-act" title="更多文档操作" aria-label="更多文档操作" :aria-expanded="moreMenuOpen" @click="shareMenu = false; moreMenuOpen = !moreMenuOpen"><i class="ri-more-2-fill"></i></button>
              <div v-if="moreMenuOpen" class="menu-mask" @click="moreMenuOpen = false"></div>
              <div v-if="moreMenuOpen" class="doc-more-menu" aria-label="更多文档操作">
                <div v-if="saving || saveFailed || docSavedAt" class="more-save"><SaveHint :saving="saving" :saved-at="docSavedAt" :failed="saveFailed" /></div>
                <label v-if="docId && !currentArchived" class="move-document more-move">移动到<select :value="currentFolderId" :disabled="treeSaveState === 'saving'" aria-label="在更多菜单中移动当前文档到文件夹" @change="moveCurrentDoc($event); moreMenuOpen = false"><option v-for="folder in kb.folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label>
                <button v-if="pageType === 'document'" @click="moreMenuOpen = false; openSaveTemplate()"><i class="ri-file-copy-2-line"></i>另存为模板</button>
                <button v-if="docId" @click="moreMenuOpen = false; favorites.toggle('doc', docId, doc?.title || '未命名文档', kbId)"><i :class="favorites.isFav('doc', docId) ? 'ri-star-fill' : 'ri-star-line'"></i>{{ favorites.isFav('doc', docId) ? '取消收藏' : '收藏到左侧菜单' }}</button>
                <button class="more-share" @click="copyOriginalLink"><i class="ri-file-text-line"></i>复制知识库原文链接</button>
                <button v-if="pageType !== 'drawing'" class="more-share" @click="moreMenuOpen = false; openDocumentShare()"><i class="ri-links-line"></i>限时分享设置</button>
                <button v-if="docId" @click="moreMenuOpen = false; currentArchived ? unarchiveDoc(docId) : archiveDoc(docId)"><i :class="currentArchived ? 'ri-inbox-unarchive-line' : 'ri-archive-line'"></i>{{ currentArchived ? '恢复到原文件夹' : '归档文档' }}</button>
                <button class="danger" @click="moreMenuOpen = false; removeDoc(docId!)"><i class="ri-delete-bin-line"></i>删除这篇文档</button>
              </div>
            </div>
          </div>

      <div ref="toolbarHost" class="document-toolbar"></div>
      <div class="document-content">
    <div ref="scrollEl" class="doc-scroll" :class="{ 'library-home-scroll': !docId }" @scroll.passive="onDocScroll">
      <div class="doc-wrap">
        <div class="doc-col">
          <section v-if="!docId" class="library-home">
            <header><button class="library-cover-icon" :style="{ color: kb.color }" title="更换知识库图标" aria-label="更换知识库图标" @click="openKbIconSettings"><i :class="kb.icon" aria-hidden="true"></i></button><div class="library-heading"><h1>{{ kb.name }}</h1><p>{{ docTotal }} 文档</p></div><button class="library-star" :disabled="knowledgeFavorites.saving" :aria-pressed="knowledgeFavorites.ids.includes(kbId)" @click="knowledgeFavorites.toggle(kbId)"><i :class="knowledgeFavorites.ids.includes(kbId) ? 'ri-star-fill' : 'ri-star-line'"></i>{{ knowledgeFavorites.ids.includes(kbId) ? '已常用' : '添加常用' }}</button><button class="library-star" title="知识库设置" @click="openKbSettings"><i class="ri-settings-3-line"></i>设置</button></header>
            <p v-if="kb.desc" class="library-description">{{ kb.desc }}</p>
            <div class="home-tools">
              <label class="home-search"><i class="ri-search-line" aria-hidden="true"></i><input v-model="treeQuery" type="search" aria-label="搜索首页文档" placeholder="搜索文件夹或文档标题" @keydown.esc="treeQuery = ''" /></label>
              <select v-model="homeOrder" aria-label="首页文档排序"><option value="directory">按目录</option><option value="updated">最近更新</option><option value="title">按名称</option></select>
              <button class="library-star" :disabled="creatingDoc" @click="createAction('doc')"><i class="ri-add-line" aria-hidden="true"></i>新建文档</button>
            </div>
            <div v-if="data.loadStates.docs === 'error'" class="home-load-error" role="alert"><span>目录加载失败：{{ data.loadErrors.docs || '请检查网络后重试' }}</span><button class="library-star" @click="data.refreshDocs()">重试</button></div>
            <div v-else-if="data.loadStates.docs !== 'loaded'" class="doc-empty" role="status">正在加载目录…</div>
            <div v-else-if="!docTotal" class="doc-empty">从第一篇文档开始整理知识。</div>
            <div v-if="docTotal" class="home-list-heading"><span class="home-result-count" role="status">{{ homeSearch ? `找到 ${homeResultCount} 篇文档` : `${docTotal} 篇文档` }}</span><button v-if="!homeSearch && homeGroups.length" class="home-collapse" :aria-expanded="allHomeGroupsOpen" @click="toggleHomeGroups">{{ allHomeGroupsOpen ? '折叠全部分组' : '展开全部分组' }}</button><button v-if="homeSearch" class="home-clear-search" @click="treeQuery = ''">清空搜索</button></div>
            <p v-if="homeSearch && !homeGroups.length && data.loadStates.docs === 'loaded'" class="doc-empty">没有匹配的文件夹或文档，试试其他关键词。</p>
            <section v-for="folder in homeGroups" :key="folder.id" class="home-folder">
              <button class="home-folder-title" :aria-expanded="isHomeGroupOpen(folder.id)" :disabled="!!homeSearch" @click="homeFolderOpen[folder.id] = homeFolderOpen[folder.id] === false"><i :class="isHomeGroupOpen(folder.id) ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" aria-hidden="true"></i><span>{{ folder.name }}</span><span class="home-folder-count">{{ folder.docs.length }}</span></button>
              <div v-if="isHomeGroupOpen(folder.id)" class="home-documents"><button v-for="id in folder.docs" :key="id" @pointerenter="preloadSheet(id)" @focus="preloadSheet(id)" @click="selectDoc(id)"><i :class="pageIcon(id)" aria-hidden="true"></i><span class="home-document-name" :title="documentTitle(id)">{{ documentTitle(id) }}</span><time :title="absoluteTime(data.docById(id)?.updated ?? '')">{{ homeDocumentTime(data.docById(id)?.updated ?? '') }}</time></button><p v-if="!folder.docs.length" class="folder-empty">此文件夹还没有文档</p></div>
            </section>
          </section>
          <template v-if="doc">
            <input
              v-if="pageType === 'document'"
              ref="titleEl"
              class="doc-title"
              :value="doc.title"
              placeholder="未命名文档"
              @input="editTitle"
            />

            <div v-if="docLinks.length" class="doc-links">
              <span class="dl-label">关联</span>
              <button v-for="(l, i) in docLinks" :key="i" class="dl-chip" @click="goLink(l.link)">
                <i :class="l.icon"></i><span class="ellip">{{ l.label }}</span>
              </button>
            </div>
            <div v-if="linkedTasks.length" class="doc-links linked-tasks">
              <span class="dl-label">关联任务</span>
              <button
                v-for="task in linkedTasks"
                :key="task.id"
                class="dl-chip"
                :title="`打开任务：${task.title}`"
                @click="openLinkedTask(task.id)"
              >
                <i class="ri-checkbox-circle-line"></i><span class="ellip">{{ task.title }}</span>
              </button>
            </div>
            <div v-if="pageType === 'document'" class="doc-hr"></div>

            <div v-if="docId && data.docConflicts[docId]" class="doc-conflict" role="alert">
              <i class="ri-git-merge-line"></i>
              <span><b>服务器已有另一版</b><small>本机草稿没有被覆盖，请选择载入服务器版，或把本机内容另存为副本。</small></span>
              <button @click="loadServerConflict">载入服务器版</button>
              <button class="primary" @click="keepConflictCopy">本机草稿另存副本</button>
            </div>
            <div v-else-if="docId && data.docDraftErrors[docId]" class="doc-draft-warning" role="alert"><i class="ri-error-warning-line"></i>浏览器暂时无法写入离线草稿；请保持页面打开，并在网络恢复后点击保存重试。</div>
            <div v-if="saveFailed && currentSaveState?.errorMessage && docId && !data.docConflicts[docId]" class="doc-draft-warning" role="alert">保存失败：{{ currentSaveState.errorMessage }}。本机草稿已保留，可点击上方“重试”。</div>

            <KnowledgeSheet v-if="pageType === 'spreadsheet'" ref="sheetEditor" :document-id="docId ?? undefined" :content="bodyContent" :disabled="!!(docId && data.docConflicts[docId])" @change="onContentChange" />
            <KnowledgeDrawing v-else-if="pageType === 'drawing' && base?.drawingId" :key="base.drawingId" :drawing-id="base.drawingId" embedded />
            <MarkdownEditor v-else
              :key="docId ?? 'empty-doc'"
              :search-request="String(route.query.findRequest ?? '')"
              :search-term="route.query.match === 'title' ? '' : typeof route.query.find === 'string' ? route.query.find : ''"
              :model-value="body"
              :document="bodyContent?.document"
              :toolbar-target="toolbarHost"
              class="doc-body"
              bare
              allow-h1
              advanced
              polishable
              link-controls
              image-controls
              :article-image-key="`document:${session.currentUser.id}:${docId}`"
              :note-title-on-image="!!kb?.notes"
              :note-ai-origin="kb?.notes && doc && base ? { stableId: base.id, docId: base.id, sourceRev: base.rev, title: doc.title, blocks: base.blocks, links: base.links } : undefined"
              @generated-title="docId && data.setDocEdit(docId, { title: $event })"
              @ai-applied="docId && data.setDocEdit(docId, { sourceInputIds: [...new Set([...(data.docEdits[docId]?.sourceInputIds ?? []), $event])] })"
              placeholder="开始写作…输入 / 插入内容块，支持 Markdown 和粘贴截图"
              :min-height="360"
              :peers="peers"
              @caret-line="setCaretLine"
              @content-change="onContentChange"
              @add-task="addSelectionToTask"
              @upload-image="onUploadImage"
              @upload-file="onUploadFile"
            />

            <div v-if="backlinks.length" class="backlinks">
              <div class="bl-title"><i class="ri-arrow-go-back-line"></i>反向链接</div>
              <div v-for="(bl, i) in backlinks" :key="i" class="bl-row" @click="goLink(bl.link)">
                <i :class="bl.icon" class="bl-icon"></i>
                <div class="bl-main">
                  <div class="bl-label">{{ bl.label }}</div>
                  <div class="bl-snippet">{{ bl.snippet }}</div>
                </div>
                <i class="ri-arrow-right-s-line bl-chev"></i>
              </div>
            </div>
          </template>
          <div v-else-if="docId" class="doc-empty pad" role="status">
            <template v-if="data.docLoadErrors[docId]">文档正文加载失败：{{ data.docLoadErrors[docId] }} <button @click="loadSelectedDoc">重试</button></template>
            <template v-else>正在加载文档正文…</template>
          </div>
        </div>


      </div>
    </div>

        <aside v-if="doc && tocOpen && pageType === 'document'" class="toc" aria-label="文档大纲" @keydown.esc.stop="closeOutline">
          <div class="toc-label"><span>文档大纲</span><button class="icon-act" aria-label="关闭文档大纲" @click="closeOutline"><i class="ri-close-line"></i></button></div><p v-if="!toc.length" class="folder-empty">添加标题后会在这里显示目录。</p>
          <div class="toc-list">
            <button v-for="(t, i) in toc" :key="`${t.text}-${i}`" class="toc-item" :class="{ sub: t.level === 2, deep: t.level === 3, active: activeToc === i }" @click="tocGo(i)">{{ t.text }}</button>
          </div>
        </aside>
      </div>
    </div>

    <div v-if="kbSettingsOpen" class="modal-backdrop" @click.self="!kbSettingsBusy && (kbSettingsOpen = false)"><section class="template-modal compact" role="dialog" aria-modal="true" aria-label="知识库设置"><header><h2>知识库设置</h2><button :disabled="kbSettingsBusy" aria-label="关闭设置" @click="kbSettingsOpen = false">×</button></header><KnowledgeIconPicker v-model="kbSettings.icon" :disabled="kbSettingsBusy" :initially-expanded="kbIconPickerExpanded" /><label>名称<input :disabled="kbSettingsBusy" v-model="kbSettings.name" maxlength="128" /></label><label>简介<textarea :disabled="kbSettingsBusy" v-model="kbSettings.desc" rows="3"></textarea></label><p v-if="kbSettingsError" role="alert">{{ kbSettingsError }}</p><footer><button v-if="!kb.notes" class="link-button" :disabled="kbSettingsBusy" @click="removeKb">删除知识库</button><button class="btn-primary" :disabled="kbSettingsBusy || !kbSettings.name.trim()" @click="saveKbSettings">{{ kbSettingsBusy ? '保存中…' : '保存' }}</button></footer></section></div>
    <div v-if="moveDocId" class="modal-backdrop" @click.self="moveDocId = null"><section class="template-modal compact" role="dialog" aria-modal="true" aria-label="移动文档"><header><h2>移动文档</h2><button aria-label="关闭移动文档" @click="moveDocId = null">×</button></header><label>目标分组<select v-model="moveFolderId"><option v-for="folder in kb.folders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label><p v-if="treeSaveState === 'error'" role="alert">移动失败，请重试。</p><footer><button class="btn-primary" :disabled="treeSaveState === 'saving'" @click="confirmMove">{{ treeSaveState === 'saving' ? '移动中…' : '移动' }}</button></footer></section></div>
    <div v-if="newDocFolderId && templatePickerOpen" class="modal-backdrop" @click.self="newDocFolderId = null">
      <section class="template-modal" role="dialog" aria-modal="true" aria-label="新建文档">
        <header><div><h2>新建文档</h2><p>选择空白文档，或复用已有模板的正文结构。</p></div><button @click="newDocFolderId = null"><i class="ri-close-line"></i></button></header>
        <div v-if="templateLoading" class="template-state"><i class="ri-loader-4-line spin"></i>正在加载模板…</div>
        <div v-else-if="templateError" class="template-state error">{{ templateError }}<button @click="loadTemplates">重试</button></div>
        <div v-else class="template-grid">
          <button class="template-card blank" :disabled="creatingDoc" @click="createDocFrom()"><i class="ri-file-add-line"></i><b>空白文档</b><span>从空白页面开始写作</span></button>
          <button v-for="item in templates" :key="item.id" class="template-card" :disabled="creatingDoc" @click="createDocFrom(item.id)"><i class="ri-file-copy-2-line"></i><b>{{ item.name }}</b><span>{{ item.description || `${item.blocks.length} 个正文块` }}</span></button>
        </div>
        <footer><button class="link-button" @click="newDocFolderId = null; templateManagerOpen = true">管理模板</button><span v-if="creatingDoc">正在创建并保存…</span></footer>
      </section>
    </div>

    <div v-if="saveTemplateOpen" class="modal-backdrop" @click.self="saveTemplateOpen = false">
      <section class="template-modal compact" role="dialog" aria-modal="true" aria-label="另存为模板">
        <header><div><h2>另存为模板</h2><p>保存当前正文快照，之后新建文档可直接复用。</p></div><button @click="saveTemplateOpen = false"><i class="ri-close-line"></i></button></header>
        <label>模板名称<input v-model="templateForm.name" maxlength="128" autofocus /></label>
        <label>使用说明<textarea v-model="templateForm.description" maxlength="512" rows="3" placeholder="例如：每天复盘时使用"></textarea></label>
        <footer><button class="secondary" @click="saveTemplateOpen = false">取消</button><button class="primary" :disabled="!templateForm.name.trim() || savingTemplate" @click="saveAsTemplate">{{ savingTemplate ? '保存中…' : '保存模板' }}</button></footer>
      </section>
    </div>

    <div v-if="templateManagerOpen" class="modal-backdrop" @click.self="closeTemplateManager">
      <section class="template-modal manager" role="dialog" aria-modal="true" aria-label="文档模板管理">
        <header><div><h2>文档模板管理</h2><p>模板属于当前账号；删除模板不会影响已创建的文档。</p></div><button :disabled="editingTemplateSaving" @click="closeTemplateManager"><i class="ri-close-line"></i></button></header>
        <div v-if="templateLoading" class="template-state"><i class="ri-loader-4-line spin"></i>正在加载模板…</div>
        <div v-else-if="templateError" class="template-state error">{{ templateError }}<button @click="loadTemplates">重试</button></div>
        <div v-else-if="!templates.length" class="template-state empty"><i class="ri-file-copy-2-line"></i><b>还没有模板</b><span>打开任意文档，点击顶部“另存为模板”即可创建。</span></div>
        <div v-else class="template-list">
          <article v-for="item in templates" :key="item.id">
            <template v-if="editingTemplateId === item.id">
              <label>模板名称<input v-model="editTemplateForm.name" maxlength="128" :disabled="editingTemplateSaving" /></label>
              <label>使用说明<textarea v-model="editTemplateForm.description" maxlength="512" rows="2" :disabled="editingTemplateSaving"></textarea></label>
              <label class="template-content-field">模板内容</label>
              <MarkdownEditor
                v-model="editTemplateForm.content"
                :document="editTemplateRichContent?.document"
                class="template-content-editor"
                allow-h1
                advanced
                link-controls
                image-controls
                :readonly="editingTemplateSaving"
                placeholder="模板正文可以为空；支持标题、链接与图片"
                :min-height="180"
                @content-change="onTemplateContentChange"
                @upload-image="onUploadImage"
                @upload-file="onUploadFile"
              />
              <div class="template-edit-footer">
                <span class="template-edit-status" :class="templateEditState">
                  <i :class="templateEditState === 'error' || templateEditState === 'conflict' || templateDraftStorageError ? 'ri-error-warning-line' : templateEditState === 'saved' ? 'ri-checkbox-circle-line' : templateEditState === 'saving' ? 'ri-loader-4-line spin' : 'ri-draft-line'"></i>
                  {{ templateEditStatusText }}
                </span>
                <div v-if="templateEditState === 'conflict'" class="template-row-actions conflict-actions">
                  <button :disabled="templateConflictRefreshing" @click="discardConflictedTemplateDraft(item.id)">载入服务器版</button>
                  <button class="primary" :disabled="templateConflictRefreshing" @click="confirmConflictedTemplateOverwrite(item.id)">{{ templateConflictRefreshing ? '检查中…' : '确认保留本机草稿' }}</button>
                </div>
                <div v-else class="template-row-actions"><button :disabled="editingTemplateSaving" @click="finishTemplateEdit">{{ templateEditState === 'saved' ? '完成' : '稍后继续' }}</button><button class="primary" :disabled="!editTemplateForm.name.trim() || editingTemplateSaving || templateEditState === 'saved'" @click="saveTemplateMeta(item)">{{ editingTemplateSaving ? '保存中…' : templateEditState === 'error' ? '重试保存' : '保存' }}</button></div>
              </div>
            </template>
            <template v-else>
              <div class="template-summary">
                <div class="template-copy"><b>{{ item.name }}</b><span>{{ item.description || `${item.blocks.length} 个正文块` }}</span></div>
                <div class="template-row-actions"><button @click="beginEditTemplate(item)"><i class="ri-edit-line"></i>编辑内容</button><button class="danger" @click="removeTemplate(item)"><i class="ri-delete-bin-line"></i>删除</button></div>
              </div>
              <div class="template-content-preview">
                <MarkdownEditor
                  v-if="item.blocks.length || item.content?.document"
                  :model-value="item.content?.markdown ?? blocksToText(item.blocks)"
                  :document="item.content?.document"
                  readonly
                  bare
                  allow-h1
                  advanced
                  :min-height="0"
                />
                <span v-else>暂无正文内容</span>
              </div>
            </template>
          </article>
        </div>
      </section>
    </div>
  </div>
  <NoteShareDialog v-if="shareNoteId" :key="shareNoteId" :note-id="shareNoteId" :note="data.docs[shareNoteId]?.kbId === 'kb-notes'" :title="data.docs[shareNoteId]?.title" @close="shareNoteId = ''" />
</template>

<style scoped>
/* 同一个按钮始终锚定工作区左上角，切换目录不移动焦点或入口。 */
.directory-toggle { position: absolute; top: 10px; left: var(--wao-space-2); z-index: 2; display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; padding: 0; border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; }
.directory-toggle:hover { background: var(--wao-bg-hover); color: var(--wao-primary-text); }
.directory-toggle:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.side-top > .back { min-height: 28px; margin-left: 40px; }
.directory-collapsed > .document-pane > .doc-bar { padding-left: 52px; }
.directory-home-bar { flex: 0 0 48px; border-bottom: 1px solid var(--wao-line); }
@media (pointer: coarse) {
  .directory-toggle { top: 2px; left: var(--wao-space-1); width: 44px; height: 44px; }
  .side-top > .back { margin-left: 48px; }
  .directory-collapsed > .document-pane > .doc-bar { padding-left: 60px; min-height: 48px; }
}
.editor-workspace .doc-scroll { display: flex; overflow: hidden; }
.editor-workspace .doc-wrap { flex: 1; min-width: 0; min-height: 0; padding: 0; }
.editor-workspace .doc-col { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.sheet-title { width: 220px; min-width: 60px; max-width: 100%; padding: var(--wao-space-1); border: 0; color: var(--wao-text); background: transparent; font: inherit; }

.document-save-status { flex: 0 1 150px; min-width: 0; overflow: hidden; white-space: nowrap; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
.doc-info { color: var(--wao-text-weak); cursor: help; }
.tree-search { padding: 6px 10px !important; }
.tree-search label { padding: 5px 8px !important; background: var(--wao-bg-sub); border: 0 !important; }
.tree-search input { height: 22px; font-size: 12px; }
.doc-row { cursor: grab; }
.doc-row:active { cursor: grabbing; }

.kb-switch { min-width: 0; flex: 1; border: 0; font-size: 15px; font-weight: 600; background: transparent; color: var(--wao-text); padding: 6px 0; text-overflow: ellipsis; }
.library-home-link { display: flex; align-items: center; gap: 10px; margin: 4px 10px; padding: 9px 12px; border: 0; border-radius: 7px; background: transparent; color: var(--wao-text); cursor: pointer; text-align: left; }
.library-home-link.on { background: var(--wao-bg-hover); font-weight: 600; }
.directory-heading { display: flex; align-items: center; justify-content: space-between; padding: 12px 22px 8px; font-size: 14px; color: var(--wao-text-weak); }
.directory-heading > span { display: flex; align-items: center; gap: 10px; }
.library-home-link { font-size: 14px; }
.directory-actions { display: flex; align-items: center; gap: 8px; }
.note-time-sort { max-width: 94px; min-width: 0; padding: 4px 2px; border: 1px solid var(--wao-line-control); border-radius: 6px; background: var(--wao-bg-card); color: var(--wao-text-2); font-size: 11px; cursor: pointer; }
.directory-heading button { border: 0; background: transparent; color: inherit; cursor: pointer; }
/* 首页使用独立纸面与紧凑目录，不改变正文阅读宽度。 */
.doc-scroll.library-home-scroll { background: var(--wao-bg-sub); }
.library-home-scroll .doc-wrap { padding: var(--wao-space-4); }
.library-home-scroll .doc-col { max-width: 1120px; min-width: 0; }
.library-home { padding: var(--wao-space-4); margin: 0 auto; width: 100%; min-height: 0; background: var(--wao-bg-card); border-radius: var(--wao-radius-card); }
.library-home header { display: flex; align-items: center; gap: var(--wao-space-2); flex-wrap: wrap; }
.library-cover-icon { padding: 0; border: 0; border-radius: var(--wao-radius-control); background: transparent; cursor: pointer; font-size: 32px; line-height: 44px; flex-shrink: 0; }
.library-cover-icon:hover { background: var(--wao-bg-hover); }
.library-cover-icon:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 4px; }
.library-heading { flex: 1; min-width: 120px; }
.library-heading h1 { font-size: var(--wao-font-title); line-height: 1.4; margin: 0 0 var(--wao-space-1); color: var(--wao-text); overflow-wrap: anywhere; }
.library-heading p { margin: 0; color: var(--wao-text-3); font-size: var(--wao-font-help); }
.library-description { margin: var(--wao-space-2) 0 0; color: var(--wao-text-3); font-size: var(--wao-font-help); white-space: pre-wrap; overflow-wrap: anywhere; }
.library-star { display: inline-flex; gap: 6px; align-items: center; justify-content: center; min-height: 40px; border: 1px solid var(--wao-line); border-radius: var(--wao-radius-control); padding: 7px 10px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; white-space: nowrap; }
.library-star:hover { background: var(--wao-bg-hover); }
.library-star:disabled { opacity: 0.6; cursor: wait; }
.home-tools { display: flex; align-items: center; gap: var(--wao-space-1); flex-wrap: wrap; margin-top: var(--wao-space-3); }
.home-search { display: flex; align-items: center; gap: var(--wao-space-1); flex: 1 1 200px; min-width: 0; min-height: 40px; padding: 0 var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); color: var(--wao-text-3); }
.home-search:focus-within { border-color: var(--wao-primary); }
.home-search input { width: 100%; min-width: 0; padding: var(--wao-space-1) 0; border: 0; outline: 0; background: transparent; color: var(--wao-text); font: inherit; }
.home-tools select { min-height: 40px; max-width: 100%; padding: var(--wao-space-1); border: 1px solid var(--wao-line-control); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); color: var(--wao-text); font: inherit; }
.home-list-heading { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: var(--wao-space-1); padding: var(--wao-space-2) 0 var(--wao-space-1); color: var(--wao-text-3); font-size: var(--wao-font-help); border-bottom: 1px solid var(--wao-line); }
.home-collapse, .home-clear-search { min-height: 36px; padding: 0 var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: transparent; color: var(--wao-primary-text); cursor: pointer; }
.home-load-error { display: flex; align-items: center; gap: var(--wao-space-2); flex-wrap: wrap; padding: var(--wao-space-2) 0; color: var(--wao-danger); overflow-wrap: anywhere; }
.library-home button:focus-visible, .home-tools select:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
.home-folder { margin: 0; }
.home-folder-title { display: flex; align-items: center; gap: var(--wao-space-1); width: 100%; min-height: 44px; padding: var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: none; color: var(--wao-text); font-size: var(--wao-font-body); text-align: left; cursor: pointer; overflow-wrap: anywhere; }
.home-folder-title > span:first-of-type { min-width: 0; }
.home-folder-count { margin-left: auto; color: var(--wao-text-3); font-size: var(--wao-font-caption); font-variant-numeric: tabular-nums; }
.home-folder-title:hover, .home-documents button:hover { background: var(--wao-bg-hover); }
.home-folder-title i { color: var(--wao-text-3); font-size: 14px; }
.home-documents { padding-left: 26px; }
.home-documents button { display: flex; align-items: center; gap: var(--wao-space-1); width: 100%; min-height: 44px; padding: var(--wao-space-1); border: 0; border-radius: var(--wao-radius-control); background: none; color: var(--wao-text-2); font-size: var(--wao-font-body); text-align: left; cursor: pointer; }
.home-documents button > i { flex-shrink: 0; color: var(--wao-text-3); }
.home-document-name { flex: 1; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.home-documents time { flex-shrink: 0; font-size: var(--wao-font-help); color: var(--wao-text-3); white-space: nowrap; }
@media (max-width: 760px) {
  .library-home-scroll .doc-wrap { padding: 16px 12px 32px; }
  .library-home { padding: 20px 14px; min-height: 0; }
  .library-home header { gap: 12px; }
  .library-heading h1 { font-size: var(--wao-font-title); }
  .library-description { margin-left: 0; }
  .home-tools .home-search { flex-basis: 100%; }
  .home-search input { font-size: 16px; }
  .home-tools select, .library-star, .home-collapse, .home-clear-search { min-height: 44px; }
  .home-documents { padding-left: 16px; }
  .home-document-name { max-width: none; }
  .home-documents time { display: none; }
}

.tree-search { padding:10px 12px; display:grid; gap:7px; font-size:11px; border-bottom:1px solid var(--wao-line); }.tree-search strong { font-weight:500; color:var(--wao-text-weak); }.tree-search label { display:flex; align-items:center; gap:6px; padding:7px; border:1px solid var(--wao-line); border-radius:6px; }.tree-search input { min-width:0; width:100%; border:0; outline:0; background:none; color:var(--wao-text); }.tree-search button { justify-self:start; border:0; background:none; color:var(--wao-primary-text); cursor:pointer; }
.move-document { display:flex; align-items:center; gap:5px; font-size:11px; color:var(--wao-text-weak); }.move-document select { max-width:130px; min-width:70px; padding:5px; border:1px solid var(--wao-line); border-radius:5px; background:var(--wao-bg-card); color:var(--wao-text); }

.missing {
  padding: 60px 24px;
  text-align: center;
  color: var(--wao-text-weak);
}
.kb-detail {
  position: relative;
  display: flex;
  /* 应用壳已经占去顶栏和页签栏；锁定剩余视口后，左右两栏才能各自滚动。 */
  height: calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h));
  min-height: 0;
  overflow: hidden;
  animation: waoFade 0.2s ease;
}
.spacer {
  flex: 1;
}
.ellip {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.save-retry { border: 0; background: transparent; color: var(--wao-danger-strong); font-size: 11px; font-weight: 700; cursor: pointer; }
.doc-conflict { display:flex; align-items:center; gap:10px; margin:0 0 14px; padding:11px 12px; border:1px solid color-mix(in srgb,var(--wao-danger-strong) 35%,var(--wao-line)); border-radius:10px; background:var(--wao-danger-bg); color:var(--wao-danger-strong); }
.doc-conflict > i { font-size:20px; }.doc-conflict > span { min-width:0; flex:1; display:flex; flex-direction:column; }.doc-conflict small { color:var(--wao-text-2); }
.doc-conflict button { min-height:30px; border:1px solid var(--wao-line-control); border-radius:7px; padding:0 10px; background:var(--wao-bg-card); color:var(--wao-text-2); cursor:pointer; white-space:nowrap; }.doc-conflict button.primary { border:0; background:var(--wao-primary); color:#fff; }
.doc-draft-warning { display:flex; align-items:center; gap:7px; margin:0 0 12px; padding:9px 11px; border-radius:8px; background:var(--wao-danger-bg); color:var(--wao-danger-strong); font-size:11px; }
@media (max-width: 760px) { .doc-conflict { align-items:stretch; flex-wrap:wrap; }.doc-conflict > span { flex-basis:calc(100% - 34px); }.doc-conflict button { flex:1; } }

/* 左栏 */
.side {
  position: relative;
  width: min(var(--directory-width, 262px), 35vw);
  flex-shrink: 0;
  border-right: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.side-top {
  padding: 10px 12px 8px;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  color: #7a7d89;
  font-size: 12px;
  cursor: pointer;
  padding: 3px 6px;
  margin-left: -6px;
  border-radius: 6px;
}
.back:hover {
  background: var(--wao-divider);
  color: var(--wao-text-2);
}
.back i {
  font-size: 13px;
}
.side-kb {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.side-icon {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 13px;
}
.kb-del {
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  border-radius: 7px;
  color: var(--wao-text-faint);
  cursor: pointer;
  flex-shrink: 0;
}
.kb-del:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-danger-strong);
}
.side-name {
  flex: 1;
  min-width: 0;
  font-size: 13.5px;
  font-weight: 700;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.new-folder {
  margin-top: 8px;
  width: 100%;
  height: 29px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px dashed #d3d6df;
  background: none;
  border-radius: 8px;
  cursor: pointer;
  color: var(--wao-text-3);
  font-size: 12px;
}
.new-folder:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
}
.new-folder i {
  font-size: 13px;
}
.side-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.tree-save { margin-top: 7px; min-height: 24px; display: flex; align-items: center; gap: 5px; color: var(--wao-text-weak); font-size: 11px; }
.tree-save.saved { color: var(--wao-success-strong); }
.tree-save.error { color: var(--wao-danger-strong); }
.tree-save button { border: 0; background: none; color: inherit; text-decoration: underline; cursor: pointer; padding: 0; }
.tree {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 4px 8px 8px;
}
.folder-row {
  display: flex;
  align-items: center;
  margin-top: 4px;
}
.folder-btn {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 6px;
  cursor: pointer;
  min-width: 0;
}
.folder-btn:hover {
  background: var(--wao-bg-tab);
}
.folder-input {
  flex: 1;
  min-width: 0;
  height: 27px;
  border: 1px solid var(--wao-primary);
  border-radius: 7px;
  padding: 0 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text);
  outline: none;
  background: var(--wao-bg-card);
}
.chev {
  font-size: 14px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.folder-icon {
  font-size: 14px;
  color: #b9a15e;
  flex-shrink: 0;
}
.folder-name {
  flex: 1;
  text-align: left;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.folder-count {
  font-size: 11px;
  color: var(--wao-text-faint);
}
/* 行内操作按钮：平时淡出，鼠标进到这一行才明显起来 */
.row-act {
  width: 22px;
  height: 22px;
  border: none;
  background: none;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-text-faint-2);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.12s;
}
.folder-row:hover .row-act,
.doc-row:hover .row-act {
  opacity: 1;
}
.row-act:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary-text);
}
.row-act.danger:hover {
  background: var(--wao-danger-bg);
  color: #bc4141;
}
.row-act i {
  font-size: 13px;
}
.doc-tree {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 1px 0 2px;
}
.doc-row {
  display: flex;
  align-items: center;
}
.doc-row.dragging { opacity: .38; }
.drag-handle { flex: none; color: var(--wao-text-faint); cursor: grab; font-size: 13px; }
.drag-handle:active { cursor: grabbing; }
.doc-node {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  border-radius: 7px;
  padding: 5.5px 6px 5.5px 28px;
  cursor: pointer;
  background: transparent;
}
.doc-node:hover {
  background: var(--wao-bg-tab);
}
.doc-node.on {
  background: var(--wao-primary-soft);
}
.doc-node-icon {
  font-size: 13px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.doc-node.on .doc-node-icon {
  color: var(--wao-primary-text);
}
.doc-node-title {
  flex: 1;
  text-align: left;
  font-size: 12.5px;
  color: var(--wao-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.doc-node.on .doc-node-title {
  color: var(--wao-primary-text);
  font-weight: 600;
}
.folder-empty {
  padding: 4px 6px 4px 28px;
  font-size: 11.5px;
  color: var(--wao-text-faint-2);
}
.archive-tree {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--wao-divider);
}
.archive-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: 7px;
  background: none;
  padding: 6px;
  color: var(--wao-text-2);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.archive-head:hover { background: var(--wao-bg-tab); }
.archive-head span:first-of-type { flex: 1; text-align: left; }
.archive-state { padding: 7px 8px 7px 28px; color: var(--wao-text-faint); font-size: 11.5px; }
.archive-state.error { color: var(--wao-danger-strong); }
.archive-state button { margin-left: 5px; border: 0; background: none; color: inherit; text-decoration: underline; cursor: pointer; }
.archived-row .doc-node { padding-left: 28px; }
.sync-bar {
  border-top: 1px solid var(--wao-line);
  padding: 10px 12px;
  background: var(--wao-bg-sub);
}
.sync-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--wao-text-2);
}
.sync-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wao-success);
  animation: waoPulse 2s infinite;
}
.sync-sub {
  margin-top: 3px;
  padding-left: 14px;
  font-size: 11px;
  color: var(--wao-text-weak);
}
.link {
  border: none;
  background: none;
  color: var(--wao-primary-text);
  font-size: 11px;
  cursor: pointer;
  padding: 0;
}

/* 桌面悬停显示行操作；键盘焦点、已打开菜单和触屏始终有入口。 */
@media (hover: hover) and (pointer: fine) {
  :is(.folder-row, .doc-row) :deep(.resource-trigger) { opacity: 0; pointer-events: none; }
  :is(.folder-row, .doc-row):is(:hover, :focus-within, :has(.resource-trigger[aria-expanded="true"])) :deep(.resource-trigger) { opacity: 1; pointer-events: auto; }
}
/* 文档区 */
.document-pane { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; background: var(--wao-bg-card); container: document-pane / inline-size; }
.document-pane > .doc-bar { flex: 0 0 auto; padding: 10px 16px; border-bottom: 1px solid var(--wao-divider); }
.document-content { flex: 1; min-height: 0; position: relative; display: flex; }
.document-toolbar { flex: 0 0 auto; }
.doc-scroll {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--wao-bg-card);
}
.doc-wrap {
  display: flex;
  justify-content: center;
  gap: 26px;
  padding: 28px 36px 64px;
}
.doc-col {
  flex: 1;
  min-width: min(420px, 100%);
  max-width: none;
}
.document-pane.content-narrow .doc-scroll:not(.library-home-scroll) .doc-col { max-width: 650px; }
.doc-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--wao-text-weak);
  flex-wrap: nowrap;
  min-width: 0;
}
.doc-location { display: flex; align-items: center; flex: 1 1 auto; min-width: 0; gap: 8px; overflow: hidden; white-space: nowrap; }
.doc-location .library-name { flex: 0 1 auto; max-width: 90px; }
.doc-location .document-name { flex: 1 1 auto; min-width: 0; }
.doc-location :is(.tag-auto, .tag-archived) { flex: none; }
.doc-bar > :is(.collab, .realtime, .bar-move, .outline-toggle, .doc-info, .bar-sync, .save-retry, .bar-favorite, .bar-share, .doc-more-wrap) { flex-shrink: 0; }
.bar-sync { display: inline-flex; max-width: 195px; min-width: 0; overflow: hidden; }
.bar-sync :deep(.weknora-status) { flex-wrap: nowrap; white-space: nowrap; min-width: 0; }
.bar-sync :deep(a) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.doc-more-wrap { position: relative; }
.doc-more-wrap.has-save-error > button { border-color: var(--wao-danger-strong); color: var(--wao-danger-strong); }
.doc-more-menu { position: absolute; right: 0; top: 34px; z-index: 89; display: flex; flex-direction: column; gap: 2px; width: min(280px, calc(100vw - 24px)); max-height: min(70dvh, 440px); overflow-y: auto; padding: 6px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop-2); }
.doc-more-menu > button { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px; border: 0; border-radius: 6px; background: transparent; color: var(--wao-text-2); font-size: 12px; text-align: left; cursor: pointer; }
.doc-more-menu > button:hover { background: var(--wao-bg-hover); }
.doc-more-menu > button.danger { color: var(--wao-danger-strong); }
.doc-more-menu > :is(.more-save, .more-sync, .more-move) { padding: 7px 8px; }
.doc-more-menu .more-move { justify-content: space-between; }
.doc-more-menu .more-sync { border-bottom: 1px solid var(--wao-line); }
.doc-more-menu :deep(.weknora-status) { gap: 6px; }
.more-move, .more-share { display: none !important; }
@container document-pane (max-width: 980px) {
  .document-save-status, .doc-info, .bar-favorite, .bar-move { display: none; }
  .more-move { display: flex !important; }
  .doc-location .library-name { max-width: 60px; }
}
@container document-pane (max-width: 700px) {
  .bar-sync, .realtime, .bar-share { display: none; }
  .more-share { display: flex !important; }
  .doc-location .library-name, .doc-location .b-sep { display: none; }
}
@container document-pane (max-width: 420px) {
  .collab, .outline-toggle span { display: none; }
  .icon-act.outline-toggle { width: 28px; padding: 0; }
}
.b-sep {
  font-size: 13px;
  color: var(--wao-text-faint-2);
}
.tag-auto {
  font-size: 10.5px;
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
  border-radius: 4px;
  padding: 1px 6px;
}
.tag-archived {
  font-size: 10.5px;
  color: var(--wao-text-weak);
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-line-control);
  border-radius: 4px;
  padding: 1px 6px;
}
.collab {
  display: flex;
  align-items: center;
  margin-right: 2px;
}
.c-av {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  color: #fff;
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #fff;
}
.c-av.peer {
  margin-left: -5px;
}
.realtime {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--wao-success-strong);
  white-space: nowrap;
}
.rt-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--wao-success);
  animation: waoPulse 2s infinite;
}
.icon-act {
  width: 28px;
  height: 28px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-act.outline-toggle { width: auto; padding: 0 8px; gap: 4px; white-space: nowrap; flex-shrink: 0; }
.icon-act:hover {
  background: var(--wao-bg-hover);
}
.icon-act.danger:hover {
  background: var(--wao-danger-bg);
  border-color: #f0c9c9;
  color: #bc4141;
}
.icon-act.faved,
.icon-act.faved:hover {
  color: #b9821f;
  border-color: #e5d3a8;
  background: var(--wao-warning-bg);
}
/* 分享菜单（样式与 TaskDrawer 的分享菜单保持一致手感） */
.share-wrap {
  position: relative;
}
.menu-mask {
  position: fixed;
  inset: 0;
  z-index: 88;
}
.share-menu {
  position: absolute;
  right: 0;
  top: 34px;
  z-index: 89;
  width: 236px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  box-shadow: var(--wao-shadow-pop-2);
  padding: 5px;
  animation: waoPop 0.14s ease;
}
.share-scope {
  font-size: 11px;
  color: var(--wao-text-weak);
  padding: 5px 8px 6px;
  border-bottom: 1px solid var(--wao-divider);
  margin-bottom: 3px;
}
.share-item {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  border: none;
  background: none;
  border-radius: 7px;
  padding: 8px;
  cursor: pointer;
  text-align: left;
}
.share-item:hover {
  background: var(--wao-bg-hover-2);
}
.share-item > i {
  font-size: 15px;
  color: var(--wao-text-3);
  margin-top: 1px;
}
.share-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.share-main b {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--wao-text);
}
.share-main em {
  font-size: 11px;
  font-style: normal;
  color: var(--wao-text-weak);
}
.icon-act i {
  font-size: 13px;
}
.doc-title {
  margin-top: 14px;
  width: 100%;
  border: none;
  outline: none;
  font-size: 27px;
  font-weight: 700;
  color: var(--wao-text);
  background: none;
  padding: 2px 0;
  border-radius: 6px;
}
.doc-title:focus {
  background: var(--wao-bg-sub);
  box-shadow: 0 0 0 3px var(--wao-bg-sub);
}
.doc-title::placeholder {
  color: var(--wao-text-faint-2);
}
.doc-meta {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  font-size: 12.5px;
  color: var(--wao-text-weak);
}
.m-av {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: #fff;
  font-size: 10.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.m-name {
  color: var(--wao-text-2);
}
.doc-links {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.dl-label {
  font-size: 12px;
  color: var(--wao-text-faint);
}
.dl-chip {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-sub);
  border-radius: 999px;
  font-size: 12px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 280px;
}
.dl-chip:hover {
  border-color: #c7d0f1;
  color: var(--wao-primary-text);
  background: var(--wao-primary-tint);
}
.dl-chip i {
  font-size: 13px;
}
.doc-hr {
  margin-top: 20px;
  border-top: 1px solid var(--wao-divider);
}
/* 正文按文档的字号排版，比任务描述宽松一档 */
.doc-body {
  margin-top: 12px;
}
.doc-body :deep(.tiptap) {
  font-size: 14.5px;
  line-height: 1.95;
  color: #33363f;
  padding: 4px 0;
}
.doc-body :deep(h2) {
  font-size: 18px;
  margin: 26px 0 6px;
}
.doc-body :deep(h1) {
  font-size: 23px;
  margin: 30px 0 8px;
}
.doc-body :deep(h3) {
  font-size: 16px;
}
.doc-empty {
  margin-top: 26px;
  color: var(--wao-text-faint);
  font-size: 13px;
}
.doc-empty.pad {
  padding: 40px 0;
}
.backlinks {
  margin-top: 36px;
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-divider);
  border-radius: 12px;
  padding: 14px 16px;
}
.bl-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text-2);
}
.bl-title i {
  font-size: 14px;
  color: var(--wao-text-weak);
}
.bl-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px;
  margin: 0 -8px;
  border-radius: 8px;
  cursor: pointer;
}
.bl-row:hover {
  background: var(--wao-bg-hover-2);
}
.bl-icon {
  font-size: 14px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.bl-main {
  flex: 1;
  min-width: 0;
}
.bl-label {
  font-size: 12.5px;
  color: var(--wao-primary-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bl-snippet {
  font-size: 11.5px;
  color: var(--wao-text-weak);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bl-chev {
  font-size: 14px;
  color: var(--wao-text-faint-2);
  flex-shrink: 0;
}

/* TOC */
.toc {
  position: absolute;
  z-index: 30;
  top: 12px;
  right: 12px;
  width: 240px;
  max-width: calc(100% - 24px);
  max-height: calc(100% - 24px);
  box-sizing: border-box;
  overflow: auto;
  padding: 14px;
  border: 1px solid var(--wao-divider);
  border-radius: 10px;
  background: var(--wao-bg-card);
  box-shadow: 0 8px 28px rgb(20 23 35 / 12%);
}
.toc-label {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 11px;
  color: var(--wao-text-weak);
  letter-spacing: 1.5px;
  margin-bottom: 8px;
}
.toc-list {
  border-left: 2px solid var(--wao-divider);
  display: flex;
  flex-direction: column;
}
.toc-item {
  border: none;
  background: none;
  text-align: left;
  font-size: 12px;
  color: var(--wao-text-3);
  cursor: pointer;
  padding: 5px 10px;
  border-left: 2px solid transparent;
  margin-left: -2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.toc-item:hover {
  color: var(--wao-primary-text);
  border-left-color: var(--wao-primary);
}
.toc-item.sub { padding-left: 22px; font-size: 11.5px; }
.toc-item.deep { padding-left: 34px; font-size: 11px; color: var(--wao-text-weak); }
.toc-item.active { color: var(--wao-primary-text); border-left-color: var(--wao-primary); background: var(--wao-primary-soft); }

/* 文档模板弹窗 */
.modal-backdrop { position: fixed; inset: 0; z-index: 1100; display: grid; place-items: center; padding: 20px; background: rgba(13, 17, 35, .56); backdrop-filter: blur(3px); }
.template-modal { width: min(680px, 100%); max-height: calc(100vh - 40px); overflow: auto; border: 1px solid var(--wao-line); border-radius: 18px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-modal); padding: 22px; }
.template-modal.compact { width: min(520px, 100%); }
.template-modal.manager { width: min(780px, 100%); }
.template-modal header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.template-modal h2 { margin: 0; color: var(--wao-text); font-size: 20px; }
.template-modal header p { margin: 5px 0 0; color: var(--wao-text-weak); font-size: 12px; }
.template-modal header > button { width: 32px; height: 32px; flex: none; border: 0; border-radius: 50%; background: var(--wao-bg-sub); color: var(--wao-text-3); cursor: pointer; }
.template-modal label { display: flex; flex-direction: column; gap: 7px; margin-top: 14px; color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.template-modal input, .template-modal textarea { width: 100%; border: 1px solid var(--wao-line-control); border-radius: 9px; background: var(--wao-bg-card); color: var(--wao-text); padding: 10px 11px; font: inherit; resize: vertical; }
.template-modal footer { display: flex; justify-content: flex-end; align-items: center; gap: 9px; min-height: 34px; margin-top: 18px; color: var(--wao-text-weak); font-size: 11px; }
.template-modal footer .link-button { margin-right: auto; border: 0; background: none; color: var(--wao-primary-text); cursor: pointer; }
.template-modal button.primary, .template-modal footer .primary { border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; padding: 9px 14px; cursor: pointer; }
.template-modal button.secondary { border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-2); padding: 8px 14px; cursor: pointer; }
.template-modal button:disabled { opacity: .55; cursor: wait; }
.template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 10px; }
.template-card { min-height: 118px; display: flex; flex-direction: column; align-items: flex-start; gap: 7px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); padding: 15px; text-align: left; cursor: pointer; }
.template-card:hover { border-color: var(--wao-primary); background: var(--wao-primary-tint); }
.template-card > i { font-size: 21px; color: var(--wao-primary-text); }
.template-card > b { color: var(--wao-text); font-size: 13px; }
.template-card > span { color: var(--wao-text-weak); font-size: 11px; line-height: 1.5; }
.template-card.blank { border-style: dashed; }
.template-state { min-height: 160px; display: flex; align-items: center; justify-content: center; gap: 8px; color: var(--wao-text-weak); font-size: 12px; }
.template-state.error { color: var(--wao-danger-strong); }
.template-state.error button { border: 0; background: none; color: inherit; text-decoration: underline; cursor: pointer; }
.template-state.empty { flex-direction: column; }
.template-state.empty i { font-size: 30px; color: var(--wao-text-faint); }
.template-list { display: flex; flex-direction: column; gap: 9px; }
.template-list article { display: grid; gap: 12px; border: 1px solid var(--wao-line); border-radius: 11px; padding: 14px; }
.template-summary { display: flex; align-items: center; gap: 12px; }
.template-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.template-copy b { color: var(--wao-text); font-size: 13px; }
.template-copy span { color: var(--wao-text-weak); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.template-content-preview { max-height: 230px; overflow: auto; padding: 11px 12px; border: 1px solid var(--wao-divider); border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 11px; overscroll-behavior-y: contain; }
.template-content-preview :deep(.tiptap) { font-size: 12px; line-height: 1.65; }
.template-content-field { margin-bottom: -5px; }
.template-content-editor { border: 1px solid var(--wao-line-control); border-radius: 9px; }
.template-content-editor :deep(.tiptap) { max-height: 340px; overflow: auto; overscroll-behavior-y: contain; }
.template-edit-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.template-edit-status { display: inline-flex; align-items: center; gap: 5px; color: var(--wao-warning-strong); font-size: 11px; line-height: 1.45; }
.template-edit-status.saved { color: var(--wao-success-strong); }
.template-edit-status.error, .template-edit-status.conflict { color: var(--wao-danger-strong); }
.template-row-actions { display: flex; justify-content: flex-end; gap: 6px; }
.template-row-actions button { border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: var(--wao-text-3); padding: 6px 9px; cursor: pointer; }
.template-row-actions button.danger { color: var(--wao-danger-strong); }
@media (max-width: 620px) {
  .template-edit-footer { align-items: flex-start; flex-direction: column; }
  .template-row-actions { align-self: flex-end; }
}
.spin { display: inline-block; animation: waoSpin .9s linear infinite; }

@media (max-width: 760px) {
  .kb-detail {
    height: calc(100dvh - 48px - 64px - env(safe-area-inset-bottom));
    flex-direction: column;
  }
  .side {
    width: 100%;
    flex: 0 0 auto;
    max-height: 200px;
    border-right: 0;
    border-bottom: 1px solid var(--wao-line);
  }
  .doc-scroll {
    min-height: 0;
  }
  .doc-wrap {
    padding: 16px 12px 40px;
  }
  .doc-col {
    min-width: 0;
  }
  .doc-head {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .doc-actions {
    width: 100%;
    flex-wrap: wrap;
  }
  .doc-actions button {
    flex: 1;
    justify-content: center;
  }
}
.tree-search { display: flex; align-items: center; gap: 6px; }
.tree-search label { flex: 1; min-width: 0; }

/* 语雀式目录：缩进属于整行，展开位固定宽度，选中和拖放覆盖整行。 */
.tree { padding: 4px 10px 8px; }
.directory-row {
  position: relative;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  width: 100%;
  height: 38px;
  margin: 0;
  padding: 0 4px 0 calc(4px + var(--node-depth) * 18px);
  border-radius: 7px;
  cursor: grab;
}
.directory-row:active { cursor: grabbing; }
.directory-row:hover, .directory-row:focus-within { background: var(--wao-bg-hover); }
.directory-row.selected, .directory-row.selected:hover { background: var(--wao-primary-soft); }
.directory-row.drag-source { opacity: .4; }
.tree-caret, .tree-caret-spacer { flex: 0 0 20px; width: 20px; height: 28px; }
.tree-caret { display: grid; place-items: center; padding: 0; border: 0; border-radius: 5px; background: transparent; cursor: pointer; }
.tree-caret:hover { background: var(--wao-bg-hover-2); }
.directory-row .folder-btn, .directory-row .doc-node {
  flex: 1 1 auto;
  height: 34px;
  padding: 0 4px;
  gap: 0;
  text-align: left;
  background: transparent;
}
.directory-row .folder-btn:hover, .directory-row .doc-node:hover,
.directory-row .doc-node.on { background: transparent; }
.directory-row .folder-name, .directory-row .doc-node-title { font-size: 13px; font-weight: 400; }
.directory-row.selected .doc-node-title { font-weight: 600; }
.tree-row-actions {
  position: absolute;
  right: 3px;
  top: 4px;
  display: flex;
  align-items: center;
  height: 30px;
  padding-left: 8px;
  background: var(--wao-bg-hover);
  border-radius: 6px;
  opacity: 0;
  pointer-events: none;
}
.directory-row.selected .tree-row-actions { background: var(--wao-primary-soft); }
.directory-row:hover .tree-row-actions,
.directory-row:focus-within .tree-row-actions,
.directory-row:has(.resource-trigger[aria-expanded="true"]) .tree-row-actions { opacity: 1; pointer-events: auto; }
.directory-row.drop-before::before, .directory-row.drop-after::after {
  content: '';
  position: absolute;
  z-index: 2;
  left: calc(4px + var(--node-depth) * 18px);
  right: 4px;
  height: 2px;
  border-radius: 2px;
  background: var(--wao-primary);
}
.directory-row.drop-before::before { top: 0; }
.directory-row.drop-after::after { bottom: 0; }
.directory-row.drop-inside { outline: 2px solid var(--wao-primary); outline-offset: -2px; background: var(--wao-primary-soft); }
.root-drop { margin: 2px 0 6px; padding: 9px; border: 1px dashed var(--wao-primary); border-radius: 7px; color: var(--wao-primary-text); font-size: 12px; text-align: center; }
@media (hover: none), (pointer: coarse) { .tree-row-actions { opacity: 1; pointer-events: auto; } }

</style>
