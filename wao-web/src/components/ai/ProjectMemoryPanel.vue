<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createProjectMemory, deleteProjectMemory, fetchProjectMemories, updateProjectMemory,
  type SaveProjectMemoryPayload,
} from '@/api/projectMemories'
import { toast } from '@/composables/toast'
import type { ProjectMemory, ProjectMemoryCategory } from '@/types'

const props = defineProps<{ projectId: string }>()
const router = useRouter()
const route = useRoute()
const items = ref<ProjectMemory[]>([])
const loading = ref(true)
const loadError = ref('')
const query = ref('')
const category = ref('')
const editorOpen = ref(false)
const editingId = ref<string | null>(null)
const saveState = ref<'idle' | 'draft' | 'saving' | 'saved' | 'error'>('idle')
const saveError = ref('')
const deleteErrors = reactive<Record<string, string>>({})
const form = reactive<SaveProjectMemoryPayload>({
  category: 'context', title: '', content: '', evidenceLinks: [],
})
const evidenceText = ref('')

const categories: { value: ProjectMemoryCategory; label: string }[] = [
  { value: 'decision', label: '架构决策' },
  { value: 'convention', label: '项目约定' },
  { value: 'pitfall', label: '踩坑' },
  { value: 'verification', label: '已验证命令' },
  { value: 'operation', label: '部署运维' },
  { value: 'context', label: '必要背景' },
]
const categoryLabel = (value: string) => categories.find((item) => item.value === value)?.label ?? value
const draftKey = computed(() => `wao:project-memory-draft:${props.projectId}:${editingId.value ?? 'new'}`)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    items.value = await fetchProjectMemories(props.projectId, query.value.trim(), category.value)
    const selected = typeof route.query.memory === 'string' ? route.query.memory : ''
    if (selected) await nextTick(() => document.getElementById(`memory-${selected}`)?.scrollIntoView({ block: 'center' }))
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '工程记忆加载失败，请重试'
  } finally {
    loading.value = false
  }
}

function blankForm() {
  form.category = 'context'
  form.title = ''
  form.content = ''
  form.evidenceLinks = []
  evidenceText.value = ''
}

function restoreDraft() {
  const raw = localStorage.getItem(draftKey.value)
  if (!raw) return false
  try {
    const saved = JSON.parse(raw) as SaveProjectMemoryPayload & { evidenceText?: string }
    if (saved.category && saved.title !== undefined && saved.content !== undefined) {
      form.category = saved.category
      form.title = saved.title
      form.content = saved.content
      evidenceText.value = saved.evidenceText ?? saved.evidenceLinks?.join('\n') ?? ''
      saveState.value = 'draft'
      return true
    }
  } catch { localStorage.removeItem(draftKey.value) }
  return false
}

function openCreate() {
  editingId.value = null
  blankForm()
  saveState.value = 'idle'
  saveError.value = ''
  restoreDraft()
  editorOpen.value = true
}

function openEdit(memory: ProjectMemory) {
  editingId.value = memory.id
  form.category = memory.category
  form.title = memory.title
  form.content = memory.content
  evidenceText.value = memory.evidenceLinks.join('\n')
  saveState.value = 'idle'
  saveError.value = ''
  restoreDraft()
  editorOpen.value = true
}

function closeEditor() {
  editorOpen.value = false
}

watch([() => form.category, () => form.title, () => form.content, evidenceText], () => {
  if (!editorOpen.value || saveState.value === 'saving' || saveState.value === 'saved') return
  localStorage.setItem(draftKey.value, JSON.stringify({ ...form, evidenceText: evidenceText.value }))
  saveState.value = 'draft'
})

async function save() {
  const title = form.title.trim()
  const content = form.content.trim()
  if (!title || !content) {
    saveState.value = 'error'
    saveError.value = '标题和正文都不能为空'
    return
  }
  const payload: SaveProjectMemoryPayload = {
    category: form.category,
    title,
    content,
    evidenceLinks: evidenceText.value.split('\n').map((link) => link.trim()).filter(Boolean),
  }
  saveState.value = 'saving'
  saveError.value = ''
  try {
    const saved = editingId.value
      ? await updateProjectMemory(props.projectId, editingId.value, payload)
      : await createProjectMemory(props.projectId, payload)
    const index = items.value.findIndex((item) => item.id === saved.id)
    if (index >= 0) items.value.splice(index, 1, saved)
    else items.value.unshift(saved)
    localStorage.removeItem(draftKey.value)
    saveState.value = 'saved'
    toast('工程记忆已保存', 'ri-checkbox-circle-line')
    window.setTimeout(() => { editorOpen.value = false }, 350)
  } catch (error) {
    saveState.value = 'error'
    saveError.value = error instanceof Error ? error.message : '保存失败，请重试'
  }
}

async function remove(memory: ProjectMemory) {
  if (!deleteErrors[memory.id] && !window.confirm(`删除工程记忆「${memory.title}」？`)) return
  deleteErrors[memory.id] = ''
  try {
    await deleteProjectMemory(props.projectId, memory.id)
    items.value = items.value.filter((item) => item.id !== memory.id)
    toast('工程记忆已删除', 'ri-delete-bin-line')
  } catch (error) {
    deleteErrors[memory.id] = error instanceof Error ? error.message : '删除失败，请重试'
  }
}

function openSource(memory: ProjectMemory) {
  if (!memory.sourceTaskId) return
  router.push({
    path: `/projects/${memory.projectId}`,
    query: { task: memory.sourceTaskId, run: memory.sourceRunId || undefined },
  })
}

watch(() => props.projectId, () => void load())
onMounted(load)
</script>

<template>
  <section class="memory-panel">
    <header class="memory-toolbar">
      <div>
        <h2>项目工程记忆</h2>
        <p>只保存可复用、已验证的项目经验；个人生活记忆仍严格私有。</p>
      </div>
      <RouterLink class="primary" :to="{ path: '/memories', query: { scope: 'project', project: props.projectId } }"><i class="ri-external-link-line"></i>打开记忆中心</RouterLink>
    </header>

    <div class="filters">
      <label><i class="ri-search-line"></i><input v-model="query" placeholder="搜索标题或正文" @keyup.enter="load" /></label>
      <select v-model="category" aria-label="记忆分类" @change="load">
        <option value="">全部分类</option>
        <option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>
      <button class="secondary" :disabled="loading" @click="load">{{ loading ? '加载中…' : '搜索' }}</button>
    </div>

    <div v-if="loading" class="state"><i class="ri-loader-4-line spin"></i>正在读取项目记忆…</div>
    <div v-else-if="loadError" class="state error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ loadError }}</span><button @click="load">重试</button>
    </div>
    <div v-else-if="!items.length" class="empty">
      <i class="ri-brain-line"></i><b>还没有已发布的项目记忆</b><span>AI 交付后只会生成候选，由负责人或管理员在记忆中心审核发布。</span>
    </div>
    <div v-else class="memory-list">
      <article v-for="memory in items" :key="memory.id" :id="`memory-${memory.id}`" :class="{ selected: route.query.memory === memory.id }">
        <div class="memory-head">
          <span class="category">{{ categoryLabel(memory.category) }}</span>
          <h3>{{ memory.title }}</h3>
          <button aria-label="编辑记忆" title="编辑" @click="openEdit(memory)"><i class="ri-edit-line"></i></button>
          <button class="danger" aria-label="删除记忆" :title="deleteErrors[memory.id] ? '重试删除' : '删除'" @click="remove(memory)">
            <i :class="deleteErrors[memory.id] ? 'ri-refresh-line' : 'ri-delete-bin-line'"></i>
          </button>
        </div>
        <p>{{ memory.content }}</p>
        <div class="memory-links">
          <button v-if="memory.sourceTaskId" @click="openSource(memory)">
            <i class="ri-route-line"></i>{{ memory.sourceRunId ? '来源 Task / AI Run' : '来源 Task' }}
          </button>
          <a v-for="link in memory.evidenceLinks" :key="link" :href="link" target="_blank" rel="noopener">
            <i class="ri-external-link-line"></i>验收证据
          </a>
          <time v-if="memory.updatedAt">更新于 {{ memory.updatedAt.replace('T', ' ').slice(0, 16) }}</time>
        </div>
        <div v-if="deleteErrors[memory.id]" class="inline-error" role="alert">
          {{ deleteErrors[memory.id] }}；点击右上角重试删除。
        </div>
      </article>
    </div>

    <div v-if="editorOpen" class="editor-mask" @click.self="closeEditor">
      <section class="editor" role="dialog" aria-modal="true" aria-labelledby="memory-editor-title">
        <header><div><h2 id="memory-editor-title">{{ editingId ? '编辑工程记忆' : '新增工程记忆' }}</h2><p>保存后由项目授权成员共享。</p></div><button aria-label="关闭" @click="closeEditor"><i class="ri-close-line"></i></button></header>
        <label>分类<select v-model="form.category"><option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
        <label>标题<input v-model="form.title" maxlength="200" placeholder="一句话说明可复用经验" /></label>
        <label>正文<textarea v-model="form.content" maxlength="5000" rows="8" placeholder="记录结论、适用范围和已验证做法"></textarea></label>
        <label>证据链接（每行一个）<textarea v-model="evidenceText" rows="3" placeholder="PR、提交、部署运行或验收链接"></textarea></label>
        <div class="save-status" :class="saveState">
          <span v-if="saveState === 'draft'"><i class="ri-draft-line"></i>草稿已保存在本机</span>
          <span v-else-if="saveState === 'saving'"><i class="ri-loader-4-line spin"></i>保存中…</span>
          <span v-else-if="saveState === 'saved'"><i class="ri-checkbox-circle-line"></i>已保存</span>
          <span v-else-if="saveState === 'error'" role="alert"><i class="ri-error-warning-line"></i>{{ saveError }}</span>
        </div>
        <footer><button class="secondary" :disabled="saveState === 'saving'" @click="closeEditor">取消</button><button class="primary" :disabled="saveState === 'saving'" @click="save">{{ saveState === 'error' ? '重试保存' : saveState === 'saving' ? '保存中…' : '保存' }}</button></footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.memory-panel { margin-top: 14px; }.memory-toolbar,.memory-head,.filters,.memory-links,.editor header,.editor footer { display:flex;align-items:center; }.memory-toolbar { justify-content:space-between;gap:16px; }.memory-toolbar h2,.editor h2 { margin:0;font-size:16px; }.memory-toolbar p,.editor header p { margin:4px 0 0;color:var(--wao-text-weak);font-size:12px; }.primary,.secondary,.memory-links button,.memory-links a { border-radius:8px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;text-decoration:none; }.primary { min-height:34px;padding:0 13px;border:0;background:var(--wao-primary);color:#fff; }.secondary { min-height:34px;padding:0 12px;border:1px solid var(--wao-line-control);background:var(--wao-bg-card);color:var(--wao-text-2); }.filters { margin-top:14px;gap:8px; }.filters label { flex:1;min-width:180px;display:flex;align-items:center;gap:7px;border:1px solid var(--wao-line-control);background:var(--wao-bg-card);border-radius:9px;padding:0 10px; }.filters input { width:100%;height:34px;border:0;outline:0;background:transparent; }.filters select,.editor select,.editor input,.editor textarea { border:1px solid var(--wao-line-control);background:var(--wao-bg-card);color:var(--wao-text);border-radius:8px;outline:0;font:inherit; }.filters select { height:36px;padding:0 10px; }.state,.empty { margin-top:12px;min-height:170px;border:1px solid var(--wao-line);border-radius:13px;background:var(--wao-bg-card);display:flex;align-items:center;justify-content:center;gap:8px;color:var(--wao-text-weak);font-size:13px; }.state.error { color:var(--wao-danger-strong); }.state button { border:0;background:none;color:var(--wao-primary);cursor:pointer; }.empty { flex-direction:column; }.empty>i { font-size:28px;color:var(--wao-primary); }.empty b { color:var(--wao-text); }.memory-list { margin-top:12px;display:grid;gap:10px;grid-template-columns:repeat(2,minmax(0,1fr)); }.memory-list article { min-width:0;border:1px solid var(--wao-line);border-radius:13px;background:var(--wao-bg-card);padding:15px; }.memory-list article.selected { border-color:var(--wao-primary);box-shadow:0 0 0 3px var(--wao-primary-soft); }.memory-head { gap:7px; }.memory-head h3 { min-width:0;flex:1;margin:0;font-size:14px;overflow-wrap:anywhere; }.category { flex:none;padding:2px 7px;border-radius:99px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:10px; }.memory-head button,.editor header>button { width:28px;height:28px;border:0;border-radius:7px;background:transparent;color:var(--wao-text-weak);cursor:pointer; }.memory-head button:hover { background:var(--wao-bg-hover); }.memory-head .danger:hover { color:var(--wao-danger-strong); }.memory-list article>p { margin:10px 0;color:var(--wao-text-2);font-size:12.5px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere; }.memory-links { gap:8px;flex-wrap:wrap; }.memory-links button,.memory-links a { padding:0;border:0;background:none;color:var(--wao-primary-text);font-size:11px; }.memory-links time { margin-left:auto;color:var(--wao-text-faint);font-size:10px; }.inline-error { margin-top:9px;color:var(--wao-danger-strong);font-size:11px; }.editor-mask { position:fixed;inset:0;z-index:90;background:rgba(25,27,38,.42);display:grid;place-items:center;padding:14px; }.editor { width:min(620px,100%);max-height:calc(100dvh - 28px);overflow:auto;box-sizing:border-box;border-radius:15px;background:var(--wao-bg-card);box-shadow:var(--wao-shadow-lg);padding:20px; }.editor header { justify-content:space-between;margin-bottom:14px; }.editor label { display:grid;gap:6px;margin-top:11px;color:var(--wao-text-2);font-size:12px; }.editor select,.editor input,.editor textarea { box-sizing:border-box;width:100%;padding:9px 10px;resize:vertical; }.editor footer { justify-content:flex-end;gap:8px;margin-top:14px; }.save-status { min-height:22px;margin-top:9px;color:var(--wao-text-weak);font-size:11px; }.save-status span { display:flex;align-items:center;gap:5px; }.save-status.error { color:var(--wao-danger-strong); }.save-status.saved { color:var(--wao-success); }.spin { animation:spin .9s linear infinite; }@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:700px){.memory-toolbar{align-items:flex-start;}.memory-toolbar .primary{flex:none;}.filters{align-items:stretch;flex-wrap:wrap;}.filters label{flex-basis:100%;}.filters select,.filters>.secondary{flex:1;}.memory-list{grid-template-columns:1fr;}.editor{padding:16px 14px;}.memory-links time{width:100%;margin-left:0;}}
</style>
