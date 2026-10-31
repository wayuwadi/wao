<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  archiveMemory,
  createMemory,
  fetchMemories,
  fetchMemory,
  reviseMemory,
  type MemoryDocument,
  type SaveMemoryPayload,
} from '@/api/decisions'
import { toast } from '@/composables/toast'
import { useSessionStore } from '@/stores/session'
import { formatDecisionContent, parseDecisionContent } from '@/utils/decisionContent'
import { useClientPagination } from '@/composables/clientPagination'
import WaoPagination from '@/components/ui/WaoPagination.vue'

const session = useSessionStore()
const decisions = ref<MemoryDocument[]>([])
const loading = ref(true)
const loadError = ref('')
const query = ref('')
const decisionResults = computed(() => decisions.value)
const { page: decisionPage, pageCount: decisionPageCount, visible: visibleDecisions } = useClientPagination(decisionResults, [query])
const editorOpen = ref(false)
const editing = ref<MemoryDocument | null>(null)
const saving = ref(false)
const saveError = ref('')
const hasConflict = ref(false)
const conflictPanel = ref<HTMLElement | null>(null)
const latestDecision = ref<MemoryDocument | null>(null)
const conflictLoading = ref(false)
const conflictError = ref('')
let conflictSequence = 0
const decisionFields = [
  { key: 'title', label: '决策标题' }, { key: 'situation', label: '何时适用' },
  { key: 'choice', label: '具体决定' }, { key: 'reason', label: '为什么' },
] as const
const latestFields = computed(() => latestDecision.value
  ? { title: latestDecision.value.revision.title, ...details(latestDecision.value) } : null)
function clearConflict() {
  conflictSequence += 1
  hasConflict.value = false
  latestDecision.value = null
  conflictLoading.value = false
  conflictError.value = ''
}
async function loadConflict() {
  const target = editing.value
  if (!target || !hasConflict.value) return
  const isCurrent = requestContext()
  const sequence = ++conflictSequence
  conflictLoading.value = true
  conflictError.value = ''
  latestDecision.value = null
  try {
    const latest = await fetchMemory(target.id)
    if (!isCurrent() || sequence !== conflictSequence || editing.value?.id !== target.id) return
    if (latest.id !== target.id || latest.scopeType !== 'personal' || latest.scopeId !== session.userId || latest.category !== 'decision' || !latest.currentPublishedRevisionId) {
      throw new Error('最新记录无法用于比较，请关闭编辑并重新加载核对；当前输入仍保留。')
    }
    latestDecision.value = latest
  } catch (error) {
    if (!isCurrent() || sequence !== conflictSequence) return
    conflictError.value = error instanceof Error ? error.message : '最新版本读取失败，请重试'
  } finally {
    if (isCurrent() && sequence === conflictSequence) conflictLoading.value = false
  }
}
function adoptLatestField(key: typeof decisionFields[number]['key']) {
  if (!latestFields.value || conflictLoading.value || latestDecision.value?.state !== 'active') return
  form[key] = latestFields.value[key]
}
function confirmComparison() {
  const latest = latestDecision.value
  if (!latest || conflictLoading.value || latest.state !== 'active') return
  editing.value = latest
  clearConflict()
  saveError.value = ''
  persistDraft()
  savedNotice.value = '已完成版本核对，当前内容尚未提交。请检查后点击“保存新版本”；若再次发生更新，系统会重新提示核对。'
}
function isVersionConflict(error: unknown) {
  if (!error || typeof error !== 'object') return false
  const value = error as { status?: number; response?: { status?: number } }
  return value.status === 409 || value.response?.status === 409
}
const restored = ref(false)
const draftError = ref('')
const draftSaved = ref(false)
const savedNotice = ref('')
let generation = 0
let loadSequence = 0
let alive = true
function requestContext() {
  const member = session.userId
  const version = generation
  return () => alive && !!member && member === session.userId && version === generation
}
function serializeDraft() {
  return JSON.stringify({ ...form, baseRevisionId: editing.value?.currentPublishedRevisionId ?? null })
}
function persistDraft() {
  if (!editorOpen.value || !session.userId) return false
  try {
    localStorage.setItem(draftKey.value, serializeDraft())
    draftError.value = ''
    draftSaved.value = true
    return true
  } catch {
    draftSaved.value = false
    draftError.value = '本机草稿保存失败，输入仅保留在当前页面；请勿刷新，可复制备份。'
    return false
  }
}
function clearDraft(key: string, expected: string) {
  try {
    if (localStorage.getItem(key) === expected) localStorage.removeItem(key)
  } catch {
    draftError.value = '决策已保存，但本机旧草稿未清理；重新打开时请先核对内容。'
  }
}
const archiveTarget = ref<MemoryDocument | null>(null)
const archiveDialog = ref<HTMLDialogElement | null>(null)
watch(archiveTarget, async target => {
  await nextTick()
  if (target && archiveTarget.value === target && !archiveDialog.value?.open) archiveDialog.value?.showModal()
  else if (!archiveTarget.value) archiveDialog.value?.close()
})
function requestArchive(item: MemoryDocument) {
  if (archivingId.value || !requestContext()()) return
  archiveError.value = null
  archiveTarget.value = item
}
function cancelArchive() {
  if (!archivingId.value) archiveTarget.value = null
}
const archivingId = ref('')
const archiveError = ref<{ item: MemoryDocument; message: string } | null>(null)
const form = reactive({ title: '', situation: '', choice: '', reason: '' })
let queryTimer: number | undefined

const draftKey = computed(() => `wao:decision-draft:${session.userId || 'unknown'}:${editing.value?.id || 'new'}`)

function details(item: MemoryDocument) {
  return parseDecisionContent(item.revision.content)
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  const isCurrent = requestContext()
  const sequence = ++loadSequence
  const requestedQuery = query.value.trim()
  if (!session.userId) {
    decisions.value = []
    loading.value = false
    loadError.value = '无法识别当前账号，请重新登录'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const result = await fetchMemories({ query: requestedQuery || undefined })
    if (!isCurrent() || sequence !== loadSequence || requestedQuery !== query.value.trim()) return
    decisions.value = result
  } catch (error) {
    if (!isCurrent() || sequence !== loadSequence || requestedQuery !== query.value.trim()) return
    loadError.value = error instanceof Error ? error.message : '决策读取失败，请重试'
  } finally {
    if (isCurrent() && sequence === loadSequence && requestedQuery === query.value.trim()) loading.value = false
  }
}

watch(query, () => {
  window.clearTimeout(queryTimer)
  queryTimer = window.setTimeout(() => void load(), 250)
}, { flush: 'sync' })

function resetForm() {
  clearConflict()
  Object.assign(form, { title: '', situation: '', choice: '', reason: '' })
  saveError.value = ''
  restored.value = false
  draftError.value = ''
  draftSaved.value = false
  savedNotice.value = ''
}

function restoreDraft() {
  try {
    const raw = localStorage.getItem(draftKey.value)
    if (!raw) return
    const value: unknown = JSON.parse(raw)
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid draft')
    const draft = value as Record<string, unknown>
    for (const key of ['title', 'situation', 'choice', 'reason'] as const) {
      if (typeof draft[key] !== 'string') throw new Error('invalid draft')
    }
    Object.assign(form, { title: draft.title, situation: draft.situation, choice: draft.choice, reason: draft.reason })
    if (editing.value && typeof draft.baseRevisionId === 'string') {
      editing.value = { ...editing.value, currentPublishedRevisionId: draft.baseRevisionId }
    }
    restored.value = true
    draftSaved.value = true
  } catch {
    draftError.value = '本机草稿暂时无法恢复，原草稿仍保留；请先核对内容。'
  }
}

function openCreate() {
  if (saving.value || !session.userId) return
  editing.value = null
  resetForm()
  restoreDraft()
  editorOpen.value = true
}

function openEdit(item: MemoryDocument) {
  if (saving.value || !session.userId) return
  editing.value = item
  resetForm()
  Object.assign(form, { title: item.revision.title, ...details(item) })
  restoreDraft()
  editorOpen.value = true
}

function closeEditor() {
  if (saving.value) return
  if (draftError.value && !window.confirm('本机草稿可能未保存，请先复制备份。仍要关闭吗？')) return
  clearConflict()
  editorOpen.value = false
}

watch(form, persistDraft, { deep: true, flush: 'sync' })

async function save() {
  const isCurrent = requestContext()
  if (saving.value || hasConflict.value || !isCurrent()) return
  if (!form.title.trim() || !form.choice.trim()) {
    saveError.value = '请填写决策标题和具体决定'
    return
  }
  persistDraft()
  const submitted = JSON.stringify(form)
  const submittedDraft = serializeDraft()
  const key = draftKey.value
  const target = editing.value
  saving.value = true
  saveError.value = ''
  savedNotice.value = ''
  const payload: SaveMemoryPayload = {
    scopeType: 'personal',
    scopeId: session.userId,
    category: 'decision',
    title: form.title.trim(),
    content: formatDecisionContent(form),
    changeSummary: editing.value ? '更新决策内容' : '新增个人决策',
    evidenceLinks: [],
    baseRevisionId: editing.value?.currentPublishedRevisionId,
  }
  try {
    const saved = target ? await reviseMemory(target.id, payload) : await createMemory(payload)
    if (!isCurrent()) return
    const changed = JSON.stringify(form) !== submitted
    if (changed) {
      const latestDraft = serializeDraft()
      editing.value = saved
      const stored = persistDraft()
      // 新建成功后改用真实对象的草稿键，下一次保存更新同一条决策。
      if (stored && key !== draftKey.value) clearDraft(key, latestDraft)
      savedNotice.value = '上一版已保存；你刚补充的内容尚未提交，可继续保存为新版本。'
    } else {
      clearDraft(key, submittedDraft)
      editing.value = saved
      if (!draftError.value) editorOpen.value = false
      else savedNotice.value = '决策已保存，本机清理问题不会导致重复新建。'
      toast(target ? '决策已更新并保留历史版本' : '决策已保存，可在相似情境中检索', 'ri-checkbox-circle-line')
    }
    await load()
  } catch (error) {
    if (!isCurrent()) return
    if (target && isVersionConflict(error)) {
      hasConflict.value = true
      savedNotice.value = ''
      saveError.value = ''
      void loadConflict()
      void nextTick(() => conflictPanel.value?.focus())
    } else saveError.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally {
    if (isCurrent()) saving.value = false
  }
}

async function archive() {
  const item = archiveTarget.value
  if (!item || archivingId.value) return
  const isCurrent = requestContext()
  if (!isCurrent()) return
  archivingId.value = item.id
  archiveError.value = null
  try {
    await archiveMemory(item.id)
    if (!isCurrent()) return
    archiveTarget.value = null
    decisions.value = decisions.value.filter(decision => decision.id !== item.id)
    toast('决策已废止，不再进入默认检索', 'ri-archive-line')
    await load()
  } catch (error) {
    if (!isCurrent()) return
    archiveError.value = { item, message: error instanceof Error ? error.message : '连接失败，请重试' }
  } finally {
    if (isCurrent()) archivingId.value = ''
  }
}

watch(() => session.userId, () => {
  generation += 1
  editorOpen.value = false
  decisions.value = []
  editing.value = null
  saving.value = false
  archivingId.value = ''
  archiveTarget.value = null
  archiveError.value = null
  resetForm()
  query.value = ''
  window.clearTimeout(queryTimer)
  void load()
}, { flush: 'sync' })
onBeforeUnmount(() => { alive = false; window.clearTimeout(queryTimer) })
onMounted(load)
</script>

<template>
  <div class="decisions-page">
    <header class="hero">
      <div>
        <div class="eyebrow"><i class="ri-route-line"></i> 个人选择原则</div>
        <h1>决策管理</h1>
        <p>把一次经历沉淀成下次可直接复用的选择，减少重复权衡和能量消耗。</p>
      </div>
      <button class="primary" type="button" @click="openCreate"><i class="ri-add-line"></i>新增决策</button>
    </header>

    <div v-if="archiveError && !archiveTarget" class="state-card error" role="alert">
      <span>未能确认“{{ archiveError.item.revision.title }}”的废止结果：{{ archiveError.message }}。可重试或重新加载核对。</span>
      <button type="button" :disabled="!!archivingId" @click="requestArchive(archiveError.item)">重试废止</button>
      <button type="button" :disabled="loading" @click="load">重新加载核对</button>
    </div>
    <section class="toolbar">
      <label class="search"><i class="ri-search-line"></i><input v-model="query" placeholder="搜索情境、决定或原因" /></label>
    </section>

    <section v-if="loading" class="state-card"><i class="ri-loader-4-line spin"></i><span>正在读取你的决策…</span></section>
    <section v-else-if="loadError" class="state-card error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ loadError }}</span><button type="button" @click="load">重新加载</button>
    </section>
    <section v-else-if="!decisions.length" class="state-card empty">
      <i class="ri-compass-discover-line"></i>
      <h2>{{ query ? '没有匹配的决策' : '还没有沉淀决策' }}</h2>
      <p>{{ query ? '换个关键词，或清空搜索后查看全部。' : '从一次消耗精力的经历开始，写下下次要怎么选。' }}</p>
      <button v-if="!query" class="primary" type="button" @click="openCreate">记录第一条决策</button>
    </section>

    <section v-else class="decision-list" aria-live="polite">
      <article v-for="item in visibleDecisions" :key="item.id" class="decision-card">
        <header><div><span>确认决策 · v{{ item.revision.versionNo }}</span><h2>{{ item.revision.title }}</h2></div><i class="ri-route-line"></i></header>
        <dl>
          <template v-if="details(item).situation"><dt>何时适用</dt><dd>{{ details(item).situation }}</dd></template>
          <dt>具体决定</dt><dd class="choice">{{ details(item).choice }}</dd>
          <template v-if="details(item).reason"><dt>为什么</dt><dd>{{ details(item).reason }}</dd></template>
        </dl>
        <footer>
          <span>更新于 {{ formatTime(item.updatedAt) }}</span>
          <div><button type="button" :disabled="!!archivingId" @click="openEdit(item)"><i class="ri-edit-line"></i>编辑</button><button class="danger" type="button" :disabled="!!archivingId" @click="requestArchive(item)"><i class="ri-archive-line"></i>{{ archivingId === item.id ? '废止中…' : '废止' }}</button></div>
        </footer>
      </article>
    </section>

    <WaoPagination v-if="!loading && !loadError && decisionPageCount > 1" v-model:page="decisionPage" :page-count="decisionPageCount" :total="decisions.length" aria-label="决策查询结果分页" />

    <Teleport to="body">
      <dialog ref="archiveDialog" class="archive-dialog" aria-labelledby="archive-title" aria-describedby="archive-description" @cancel.prevent="cancelArchive">
        <template v-if="archiveTarget">
          <h2 id="archive-title">废止这条决策？</h2>
          <p class="archive-name">{{ archiveTarget.revision.title }}</p>
          <p id="archive-description">废止后，这条决策将从有效列表和默认检索中移除。历史版本仍会保留，可在记忆中心查看。</p>
          <p v-if="archiveError" class="archive-error" role="alert">未能确认废止结果：{{ archiveError.message }}。可重试，或取消后重新加载核对。</p>
          <p v-if="archivingId" role="status">正在废止，请稍候…</p>
          <footer>
            <button type="button" autofocus :disabled="!!archivingId" @click="cancelArchive">取消</button>
            <button type="button" class="archive-confirm" :disabled="!!archivingId" @click="archive">{{ archivingId ? '废止中…' : archiveError ? '重试废止' : '确认废止' }}</button>
          </footer>
        </template>
      </dialog>
    </Teleport>

    <div v-if="editorOpen" class="mask" @click.self="closeEditor">
      <section class="editor" role="dialog" aria-modal="true" :aria-label="editing ? '编辑决策' : '新增决策'">
        <header><div><h2>{{ editing ? '更新这条决策' : '新增决策' }}</h2><p>写清适用情境，未来检索时才知道何时采用。</p></div><button type="button" aria-label="关闭" :disabled="saving" @click="closeEditor"><i class="ri-close-line"></i></button></header>
        <div v-if="savedNotice" class="draft" role="status">{{ savedNotice }}</div>
        <div v-if="draftError" class="draft" role="alert">{{ draftError }}<button type="button" @click="persistDraft">重试保存本机草稿</button></div>
        <div v-if="restored" class="draft"><i class="ri-history-line"></i>已恢复本机未保存草稿</div>
        <section v-if="hasConflict" ref="conflictPanel" tabindex="-1" class="conflict-panel" aria-label="版本冲突核对">
          <h3>这条决策已有更新，先核对再保存</h3>
          <p role="alert">你的输入没有被替换。请比较最新版本与下方待保存内容，将需要保留的改动合并到表单后确认。</p>
          <p v-if="conflictLoading" role="status">正在读取最新版本…</p>
          <p v-if="conflictError" class="conflict-error" role="alert">{{ conflictError }}；当前输入未改动，可重试读取。</p>
          <template v-if="latestDecision && latestFields">
            <p>最新版本 v{{ latestDecision.revision.versionNo }} · {{ formatTime(latestDecision.updatedAt) }}</p>
            <p v-if="latestDecision.state !== 'active'" role="alert">这条决策已不再有效，不能通过本次冲突处理继续保存。请先备份下方输入，再关闭编辑并重新加载核对。</p>
            <div v-for="field in decisionFields" :key="field.key" class="conflict-field">
              <div><strong>{{ field.label }}</strong><span>{{ form[field.key] === latestFields[field.key] ? '与当前输入相同' : '与当前输入不同' }}</span></div>
              <p tabindex="0" :aria-label="`最新${field.label}`">{{ latestFields[field.key] || '（未填写）' }}</p>
              <button v-if="form[field.key] !== latestFields[field.key] && latestDecision.state === 'active'" type="button" @click="adoptLatestField(field.key)">将最新{{ field.label }}填入表单</button>
            </div>
          </template>
          <div class="conflict-actions">
            <button type="button" :disabled="conflictLoading" @click="loadConflict">{{ conflictLoading ? '读取中…' : '重新读取最新版本' }}</button>
            <button type="button" :disabled="conflictLoading || !latestDecision || latestDecision.state !== 'active'" @click="confirmComparison">已核对，继续编辑当前内容</button>
          </div>
        </section>
        <h3 v-if="hasConflict" class="current-content-title">你的待保存内容</h3>
        <label>决策标题 <span>必填</span><input v-model="form.title" maxlength="120" placeholder="例如：去武汉的出行方式" /></label>
        <label>何时适用<textarea v-model="form.situation" maxlength="5000" rows="3" placeholder="例如：从当前城市去武汉，行程可安排在傍晚时"></textarea></label>
        <label>具体决定 <span>必填</span><textarea v-model="form.choice" maxlength="10000" rows="4" placeholder="例如：优先坐傍晚的高铁，到站后再坐地铁"></textarea></label>
        <label>为什么<textarea v-model="form.reason" maxlength="5000" rows="4" placeholder="记录事实依据和代价，方便以后判断是否仍然适用"></textarea></label>
        <div v-if="saveError" class="save-error" role="alert">{{ saveError }}；{{ draftSaved ? '当前输入仍保存在本机，可直接重试。' : '输入仅在当前页面，请勿刷新，可重试保存。' }}</div>
        <footer><span>{{ saving ? '正在保存提交的版本，可继续补充内容' : draftSaved ? '当前输入已保存为本机草稿' : '输入后自动保存为本机草稿' }}</span><div><button type="button" :disabled="saving" @click="closeEditor">取消</button><button class="primary" type="button" :disabled="saving || hasConflict" @click="save">{{ saving ? '保存中…' : editing ? '保存新版本' : '保存决策' }}</button></div></footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.conflict-panel{margin:8px 22px;padding:14px;border:1px solid var(--wao-line-control);border-radius:var(--wao-radius-control);background:var(--wao-bg-sub)}
.conflict-panel h3,.current-content-title{margin:0 0 8px;font-size:var(--wao-font-body)}
.current-content-title{margin:12px 22px 4px}
.conflict-panel p{margin:8px 0;font-size:var(--wao-font-body);line-height:1.7;overflow-wrap:anywhere;white-space:pre-wrap}
.conflict-field{padding:12px 0;border-top:1px solid var(--wao-line)}
.conflict-field>div{display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px;font-size:var(--wao-font-body)}
.conflict-field span{color:var(--wao-text-2);font-size:var(--wao-font-caption)}
.conflict-field p{max-height:180px;overflow:auto}
.conflict-panel .conflict-error{color:var(--wao-danger-strong)}
.conflict-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.conflict-panel button{min-height:44px;padding:8px 10px;font-size:var(--wao-font-body)}
.conflict-panel button:disabled{opacity:.6;cursor:not-allowed}

.archive-dialog{box-sizing:border-box;width:min(460px,calc(100vw - 28px));max-height:calc(100dvh - 28px);padding:22px;border:1px solid var(--wao-line);border-radius:var(--wao-radius-modal);background:var(--wao-bg-card);color:var(--wao-text);box-shadow:var(--wao-shadow-modal);font-family:var(--wao-font);overflow:auto}
.archive-dialog::backdrop{background:rgba(15,23,42,.52)}
.archive-dialog h2{margin:0 0 14px;font-size:20px}
.archive-dialog p{font-size:13px;line-height:1.7;overflow-wrap:anywhere}
.archive-dialog .archive-name{font-size:14px;font-weight:600}
.archive-dialog #archive-description{color:var(--wao-text-2)}
.archive-dialog .archive-error{padding:10px;border-radius:var(--wao-radius-control);color:var(--wao-danger-strong);background:var(--wao-danger-bg)}
.archive-dialog footer{display:flex;justify-content:flex-end;gap:10px;margin-top:20px}
.archive-dialog button{min-height:44px;padding:9px 16px;border:1px solid var(--wao-line-control);border-radius:var(--wao-radius-control);background:var(--wao-bg-card);color:var(--wao-text);font:inherit;font-size:13px;cursor:pointer}
.archive-dialog .archive-confirm{background:var(--wao-danger-bg);color:var(--wao-danger-strong);border-color:var(--wao-danger-strong)}
.archive-dialog button:focus-visible{outline:2px solid var(--wao-primary);outline-offset:3px}
.archive-dialog button:disabled{opacity:.6;cursor:wait}

.decisions-page{max-width:none;margin:0 auto;padding:32px 28px 56px;color:var(--wao-text)}
.hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;padding:28px;border:1px solid var(--wao-line);border-radius:20px;background:linear-gradient(135deg,color-mix(in srgb,var(--wao-primary) 12%,var(--wao-bg-card)),var(--wao-bg-card))}
.eyebrow{color:var(--wao-primary-text);font-size:var(--wao-font-caption);font-weight:700;letter-spacing:.08em}.hero h1{margin:8px 0 6px;font-size:28px}.hero p{max-width:620px;margin:0;color:var(--wao-text-3);line-height:1.7}
button,.memory-link{border:1px solid var(--wao-line);border-radius:9px;background:var(--wao-bg-card);color:var(--wao-text-2);font:inherit;cursor:pointer}.primary{padding:10px 16px;border-color:var(--wao-primary);background:var(--wao-primary);color:#fff;font-weight:700}.primary i{margin-right:5px}button:disabled{cursor:not-allowed;opacity:.55}button:focus-visible,.memory-link:focus-visible{outline:2px solid var(--wao-primary);outline-offset:3px}
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:20px 0}.search{display:flex;align-items:center;flex:1;max-width:520px;padding:0 12px;border:1px solid var(--wao-line);border-radius:10px;background:var(--wao-bg-card);color:var(--wao-text-3)}.search input{width:100%;padding:10px;border:0;outline:0;background:transparent;color:var(--wao-text)}.memory-link{padding:9px 12px;text-decoration:none}.memory-link i{margin-right:5px}
.state-card{display:flex;align-items:center;justify-content:center;gap:10px;min-height:220px;padding:28px;border:1px dashed var(--wao-line);border-radius:16px;color:var(--wao-text-3);text-align:center}.state-card.error{color:var(--wao-danger-strong)}.state-card.error button{padding:7px 11px}.state-card.empty{flex-direction:column}.state-card.empty>i{font-size:34px;color:var(--wao-primary-text)}.state-card h2,.state-card p{margin:0}.state-card .primary{margin-top:8px}
.decision-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.decision-card{display:flex;min-width:0;flex-direction:column;padding:20px;border:1px solid var(--wao-line);border-radius:16px;background:var(--wao-bg-card);box-shadow:0 6px 24px rgba(31,41,55,.04)}.decision-card>header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.decision-card>header span{color:var(--wao-primary-text);font-size:var(--wao-font-caption);font-weight:700}.decision-card h2{margin:5px 0 0;font-size:var(--wao-font-section);overflow-wrap:anywhere}.decision-card>header>i{font-size:24px;color:var(--wao-primary-text)}
dl{display:grid;gap:6px;margin:20px 0;white-space:pre-wrap;overflow-wrap:anywhere}dt{margin-top:8px;color:var(--wao-text-3);font-size:var(--wao-font-help);font-weight:700}dd{margin:0;line-height:1.65}.choice{padding:10px 12px;border-left:3px solid var(--wao-primary);border-radius:0 8px 8px 0;background:var(--wao-bg-sub);font-weight:600}.decision-card>footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:auto;padding-top:14px;border-top:1px solid var(--wao-line);color:var(--wao-text-3);font-size:var(--wao-font-caption)}.decision-card>footer div{display:flex;gap:7px}.decision-card>footer button{padding:6px 9px}.decision-card>footer .danger{color:var(--wao-danger-strong)}
.mask{position:fixed;z-index:1000;inset:0;display:flex;align-items:center;justify-content:center;padding:20px;background:rgba(15,23,42,.52);backdrop-filter:blur(4px)}.editor{display:flex;width:min(620px,100%);max-height:calc(100dvh - 40px);flex-direction:column;overflow:auto;border:1px solid var(--wao-line);border-radius:18px;background:var(--wao-bg-card);box-shadow:0 24px 70px rgba(0,0,0,.28)}.editor>header{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:20px 22px 12px}.editor h2{margin:0;font-size:var(--wao-font-title)}.editor header p{margin:4px 0 0;color:var(--wao-text-3);font-size:var(--wao-font-caption)}.editor header button{padding:6px;border:0;font-size:var(--wao-font-section)}.editor label{display:grid;gap:6px;padding:8px 22px;color:var(--wao-text-2);font-size:var(--wao-font-help);font-weight:700}.editor label span{color:var(--wao-primary-text);font-size:var(--wao-font-help)}.editor input,.editor textarea{box-sizing:border-box;width:100%;padding:10px 11px;border:1px solid var(--wao-line);border-radius:9px;outline:0;resize:vertical;background:var(--wao-bg-sub);color:var(--wao-text);font:inherit;font-weight:400}.search:focus-within,.editor input:focus,.editor textarea:focus{border-color:var(--wao-primary);box-shadow:0 0 0 2px var(--wao-primary-soft)}.draft,.save-error{margin:4px 22px;padding:9px 11px;border-radius:8px;font-size:var(--wao-font-help)}.draft{background:var(--wao-warning-bg);color:var(--wao-warning-strong)}.save-error{background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.editor>footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:10px;padding:15px 22px;border-top:1px solid var(--wao-line);color:var(--wao-text-3);font-size:var(--wao-font-caption)}.editor>footer div{display:flex;gap:8px}.editor>footer button{padding:8px 12px}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:760px){.decisions-page{padding:18px 12px 36px}.hero{align-items:stretch;flex-direction:column;padding:20px}.hero .primary{width:100%}.toolbar{align-items:stretch;flex-direction:column}.search{box-sizing:border-box;max-width:none}.memory-link{text-align:center}.decision-list{grid-template-columns:1fr}.decision-card>footer{align-items:flex-start;flex-direction:column}.mask{align-items:flex-end;padding:7px}.editor{max-height:calc(100dvh - 14px)}.editor>footer{align-items:stretch;flex-direction:column}.editor>footer div{display:grid;grid-template-columns:1fr 1fr}}
</style>
