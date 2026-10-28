<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiCreateDoc, fetchDocs, fetchKbs } from '@/api/board'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { directoryRows } from '@/utils/knowledgeDirectory'
import { legacyProjection } from '@/utils/richDoc'
import type { Doc, KnowledgeBase, RichDocContent } from '@/types'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'

const props = defineProps<{ taskId: string; subId?: string; title: string; description: string }>()
const data = useDataStore(), session = useSessionStore(), router = useRouter()
const member = session.userId
const picker = ref(false), archive = ref(false), loading = ref(false), saving = ref(false)
const error = ref(''), query = ref(''), kbId = ref(''), folderId = ref('')
const libraries = ref<KnowledgeBase[]>([]), catalog = ref<Doc[]>([])
const ids = computed(() => props.subId ? data.subById(props.taskId, props.subId)?.docs ?? [] : data.taskById(props.taskId)?.docs ?? [])
const receiptKey = `wao-task-archive:${member}:${props.taskId}:${props.subId ?? ''}`
const receipt = ref<{ id: string; kbId: string; title: string } | null>(null)
try {
  const stored = JSON.parse(localStorage.getItem(receiptKey) || 'null')
  if (stored && typeof stored.id === 'string' && typeof stored.kbId === 'string' && typeof stored.title === 'string') receipt.value = stored
} catch { /* leave source intact */ }
const current = () => member === session.userId
const pendingLink = ref<{ id:string; remove:boolean } | null>(null)
const linked = computed(() => ids.value.map(id => {
  const doc = data.docById(id) ?? catalog.value.find(doc => doc.id === id)
  return { id, title:doc?.title || '文档暂不可见', kbId:doc?.kbId ?? data.kbs.find(kb => kb.folders.some(folder => folder.docs.includes(id)))?.id }
}))
const candidates = computed(() => catalog.value.filter(doc => !data.isDocArchived(doc.id) && `${doc.title} ${libraries.value.find(kb => kb.id === doc.kbId)?.name ?? ''}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())))
const folders = computed(() => {
  const kb = libraries.value.find(kb => kb.id === kbId.value)
  return kb ? directoryRows(kb, id => catalog.value.find(doc => doc.id === id)?.title ?? '', {}, '').filter(node => node.kind === 'folder') : []
})
async function load() {
  loading.value = true; error.value = ''
  try {
    const [kbs, docs] = await Promise.all([fetchKbs(), fetchDocs()])
    if (!current()) return
    libraries.value = kbs; catalog.value = docs
  } catch { error.value = '知识库读取失败，请重试' }
  finally { loading.value = false }
}
async function show(mode: 'link' | 'archive') {
  if (mode === 'link') picker.value = true
  else archive.value = true
  await load()
}
async function saveLink(id: string, remove = false) {
  if (!current()) return false
  if (!data.taskById(props.taskId) || (props.subId && !data.subById(props.taskId, props.subId))) throw new Error('任务已不可用，请刷新后重试')
  await data.flushTask(props.taskId)
  if (!current() || data.taskSaveState(props.taskId) === 'error') throw new Error('任务尚未保存，请先处理保存问题')
  const docs = remove ? ids.value.filter(value => value !== id) : [...new Set([...ids.value, id])]
  if (props.subId) data.updateSub(props.taskId, props.subId, { docs })
  else data.patchTask(props.taskId, { docs })
  await data.flushTask(props.taskId)
  if (!current()) return false
  if (data.taskSaveState(props.taskId) !== 'saved') throw new Error('文档关联尚未保存，可重试；原内容仍保留')
  return true
}
async function toggleLink(id: string) {
  if (saving.value || pendingLink.value) return
  pendingLink.value = { id, remove:ids.value.includes(id) }
  await retryLink()
}
async function retryLink() {
  if (saving.value || !pendingLink.value) return
  saving.value = true; error.value = ''
  try { if (await saveLink(pendingLink.value.id, pendingLink.value.remove)) pendingLink.value = null }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '关联保存失败' }
  finally { saving.value = false }
}
function openDoc(id: string, library?: string) {
  if (library) void router.push({ path:`/kb/${library}`, query:{ doc:id } })
  else error.value = '文档暂不可见，请刷新关联列表或检查访问权限'
}
async function saveArchive() {
  if (saving.value || !current()) return
  if (!receipt.value && (!kbId.value || !folders.value.some(folder => folder.id === folderId.value))) return
  saving.value = true; error.value = ''
  try {
    await data.flushTask(props.taskId)
    if (!current()) return
    if (data.taskSaveState(props.taskId) === 'error') throw new Error('请先保存任务，再归档到知识库')
    if (!receipt.value) {
      const title = props.title.trim()
      if (!title) throw new Error('请先填写任务标题')
      const content: RichDocContent = { version:1, document:null, markdown:props.description }
      const doc = await apiCreateDoc({ kbId:kbId.value, folderId:folderId.value, title, content, blocks:legacyProjection(content) })
      // Retain the successful create result before linking, so retry never creates another document.
      const created = { id:doc.id, kbId:doc.kbId ?? kbId.value, title:doc.title }
      // Keep the receipt in memory even when browser storage is unavailable.
      receipt.value = created
      try { localStorage.setItem(receiptKey, JSON.stringify(created)) } catch { /* in-memory retry remains available */ }
      if (!current()) return
      data.applyRemoteDoc(doc)
      const folder = data.kbById(created.kbId)?.folders.find(folder => folder.id === folderId.value)
      if (folder && !folder.docs.includes(doc.id)) folder.docs.push(doc.id)
    }
    if (await saveLink(receipt.value!.id)) archive.value = false
  } catch (reason) {
    error.value = receipt.value ? '文档已保存，任务关联失败；再次保存只会重试关联。' : (reason instanceof Error ? reason.message : '归档失败，任务详情仍保留')
  } finally { saving.value = false }
}
onMounted(() => { if (ids.value.some(id => !data.docById(id))) void load() })
</script>

<template>
  <section class="task-documents" aria-label="关联文档">
    <header><span>关联文档</span><WaoButton variant="ghost" :disabled="saving || !!pendingLink" @click="show('link')"><i class="ri-link" aria-hidden="true"></i>关联文档</WaoButton><WaoButton variant="ghost" :disabled="saving || !!pendingLink" @click="show('archive')"><i class="ri-book-2-line" aria-hidden="true"></i>{{ receipt ? '查看归档' : '归档到知识库' }}</WaoButton></header>
    <WaoButton v-for="doc in linked" :key="doc.id" class="document-link" variant="ghost" @click="openDoc(doc.id, doc.kbId)"><i class="ri-file-text-line" aria-hidden="true"></i><span>{{ doc.title }}</span><i class="ri-arrow-right-up-line" aria-hidden="true"></i></WaoButton>
    <p v-if="!linked.length" class="muted">还没有关联文档</p>
    <p v-if="error && !picker && !archive" role="alert">{{ error }}</p>
    <WaoButton v-if="pendingLink && !picker" variant="ghost" :loading="saving" @click="retryLink">重试保存关联</WaoButton>
    <SettingsDialogShell v-if="picker" title="关联文档" description="选择已有知识库文档；再次点击可解除关联。" width="560px" height="min(600px, calc(100dvh - 48px))" @close="!saving && (picker = false)">
      <div class="document-form">
        <WaoField label="搜索文档" v-slot="field"><input :id="field.id" v-model="query" placeholder="标题或知识库名称" /></WaoField>
        <p v-if="loading" role="status">读取中…</p>
        <p v-if="error" role="alert">{{ error }}<WaoButton v-if="pendingLink" variant="ghost" :loading="saving" @click="retryLink">重试保存关联</WaoButton><WaoButton v-else variant="ghost" @click="load">刷新列表</WaoButton></p>
        <div class="document-options"><WaoButton v-for="doc in candidates" :key="doc.id" variant="ghost" :class="{ linked:ids.includes(doc.id) }" :aria-pressed="ids.includes(doc.id)" :disabled="saving || !!pendingLink || loading" @click="toggleLink(doc.id)"><i :class="ids.includes(doc.id) ? 'ri-checkbox-circle-line' : 'ri-file-text-line'" aria-hidden="true"></i><span>{{ doc.title || '未命名文档' }}<small>{{ libraries.find(kb => kb.id === doc.kbId)?.name }}</small></span></WaoButton></div>
        <p v-if="!loading && !error && !candidates.length" class="muted">没有可关联的文档</p>
      </div>
      <template #footer><WaoButton :disabled="saving" @click="picker = false">完成</WaoButton></template>
    </SettingsDialogShell>
    <SettingsDialogShell v-if="archive" title="归档到知识库" description="保存任务标题与详情，并自动关联文档。原任务详情会保留。" width="520px" height="auto" @close="!saving && (archive = false)">
      <div class="document-form">
        <template v-if="receipt"><p>此任务已有归档，重试关联不会重复创建文档。</p><WaoButton variant="ghost" @click="openDoc(receipt.id, receipt.kbId)">{{ receipt.title }}<i class="ri-arrow-right-up-line"></i></WaoButton></template>
        <template v-else>
          <p class="archive-title">{{ title || '未命名任务' }}</p>
          <WaoField label="知识库" v-slot="field"><select :id="field.id" v-model="kbId" :disabled="loading || saving" @change="folderId = ''"><option value="" disabled>请选择知识库</option><option v-for="kb in libraries" :key="kb.id" :value="kb.id">{{ kb.name }}</option></select></WaoField>
          <WaoField label="目录" v-slot="field"><select :id="field.id" v-model="folderId" :disabled="!kbId || loading || saving"><option value="" disabled>请选择目录</option><option v-for="folder in folders" :key="folder.id" :value="folder.id">{{ '　'.repeat(folder.depth) }}{{ folder.title }}</option></select></WaoField>
          <p v-if="kbId && !folders.length" class="muted">该知识库还没有目录，请先在知识库创建目录。</p>
        </template>
        <p v-if="loading" role="status">读取中…</p><p v-if="error" role="alert">{{ error }}<WaoButton v-if="!receipt" variant="ghost" :disabled="saving" @click="load">刷新列表</WaoButton></p>
      </div>
      <template #footer><WaoButton :disabled="saving" @click="archive = false">取消</WaoButton><WaoButton variant="primary" :loading="saving" :disabled="loading || (!receipt && !folderId)" @click="saveArchive">{{ receipt ? '保存关联' : '保存并关联' }}</WaoButton></template>
    </SettingsDialogShell>
  </section>
</template>

<style scoped>
.task-documents { margin-top:var(--wao-space-3); padding-top:var(--wao-space-2); border-top:1px solid var(--wao-line); }
header { display:flex; align-items:center; flex-wrap:wrap; gap:var(--wao-space-1); color:var(--wao-text-3); font-size:var(--wao-font-caption); }
header > span { margin-right:auto; }
header .wao-button { padding:var(--wao-space-1); font-size:var(--wao-font-caption); }
.document-link { display:flex; justify-content:flex-start; width:100%; padding:var(--wao-space-1); text-align:left; }
.document-link span { flex:1; min-width:0; overflow-wrap:anywhere; }
.document-form { display:grid; gap:var(--wao-space-3); padding:var(--wao-space-3); }
.document-options { display:grid; gap:var(--wao-space-1); }
.document-options .wao-button { justify-content:flex-start; text-align:left; }
.document-options span { min-width:0; overflow-wrap:anywhere; }
.document-options small { display:block; color:var(--wao-text-weak); }
.document-options .linked { background:var(--wao-primary-soft); }
.muted { color:var(--wao-text-weak); font-size:var(--wao-font-caption); }
[role=alert] { color:var(--wao-danger-strong); font-size:var(--wao-font-caption); }
.archive-title { margin:0; font-weight:600; overflow-wrap:anywhere; }
</style>
