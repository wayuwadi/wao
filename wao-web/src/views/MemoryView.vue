<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProjects } from '@/api/board'
import { fetchAiRunners } from '@/api/ai'
import {
  archiveMemory, createMemory, fetchMemories, fetchMemoryRevisions,
  publishMemoryRevision, rejectMemoryRevision, reviseMemory,
  type MemoryDocument, type MemoryRevision, type MemoryScope, type SaveMemoryPayload,
} from '@/api/unifiedMemories'
import { toast } from '@/composables/toast'
import { useSessionStore } from '@/stores/session'
import type { AiRunner, Project } from '@/types'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const scopes: { value: MemoryScope; label: string; desc: string; icon: string }[] = [
  { value: 'personal', label: '个人', desc: '仅当前账号可见，明确记住后立即发布', icon: 'ri-user-heart-line' },
  { value: 'organization', label: '组织', desc: '成员共享，候选经管理员发布', icon: 'ri-community-line' },
  { value: 'project', label: '项目', desc: '项目经验，候选经负责人发布', icon: 'ri-stack-line' },
  { value: 'machine', label: '机器', desc: '按执行电脑整理，候选经管理员发布', icon: 'ri-computer-line' },
  { value: 'application', label: '应用', desc: '按 AI 应用汇总，候选经管理员发布', icon: 'ri-apps-2-line' },
]
const categoryMap: Record<MemoryScope, { value: string; label: string }[]> = {
  personal: [
    { value: 'preference', label: '偏好习惯' }, { value: 'responsibility', label: '长期职责' },
    { value: 'plan', label: '长期规划' }, { value: 'decision', label: '确认决策' },
    { value: 'context', label: '背景资料' }, { value: 'experience', label: '生活经历' },
  ],
  organization: [
    { value: 'decision', label: '组织决策' }, { value: 'policy', label: '政策制度' },
    { value: 'standard', label: '工作标准' }, { value: 'glossary', label: '统一术语' },
    { value: 'responsibility', label: '职责分工' }, { value: 'context', label: '组织背景' },
  ],
  project: [
    { value: 'decision', label: '架构决策' }, { value: 'convention', label: '项目约定' },
    { value: 'pitfall', label: '踩坑经验' }, { value: 'verification', label: '验证方法' },
    { value: 'operation', label: '部署运维' }, { value: 'context', label: '必要背景' },
  ],
  machine: [
    { value: 'decision', label: '机器决策' }, { value: 'convention', label: '使用约定' },
    { value: 'pitfall', label: '机器踩坑' }, { value: 'verification', label: '验证方法' },
    { value: 'operation', label: '运行维护' }, { value: 'context', label: '机器背景' },
  ],
  application: [
    { value: 'decision', label: '应用决策' }, { value: 'convention', label: '使用约定' },
    { value: 'pitfall', label: '应用踩坑' }, { value: 'verification', label: '验证方法' },
    { value: 'operation', label: '应用维护' }, { value: 'context', label: '应用背景' },
  ],
}

const routeScope = String(route.query.scope || 'personal')
const activeScope = ref<MemoryScope>(['personal', 'organization', 'project', 'machine', 'application'].includes(routeScope)
  ? routeScope as MemoryScope : 'personal')
const projects = ref<Project[]>([])
const projectId = ref(String(route.query.project || ''))
const runners = ref<AiRunner[]>([])
const machineId = ref(String(route.query.machine || ''))
const applicationId = ref(String(route.query.application || ''))
const memories = ref<MemoryDocument[]>([])
const loading = ref(true)
const loadError = ref('')
const query = ref('')
const category = ref('')
const state = ref('')
const editorOpen = ref(false)
const editing = ref<MemoryDocument | null>(null)
const saving = ref(false)
const saveError = ref('')
const restored = ref(false)
const versionsOpen = ref(false)
const versionMemory = ref<MemoryDocument | null>(null)
const versions = ref<MemoryRevision[]>([])
const versionsLoading = ref(false)
const actionId = ref('')
let queryTimer: number | undefined

const form = reactive({ category: 'experience', title: '', content: '', changeSummary: '', evidence: '', expiresAt: '' })
const scopeId = computed(() => {
  if (activeScope.value === 'personal') return session.userId
  if (activeScope.value === 'organization') return session.org.id
  if (activeScope.value === 'project') return projectId.value
  if (activeScope.value === 'machine') return machineId.value
  return applicationId.value
})
const categories = computed(() => categoryMap[activeScope.value])
const selectedProject = computed(() => projects.value.find((item) => item.id === projectId.value))
const selectedRunner = computed(() => runners.value.find((item) => item.id === machineId.value))
const applications = computed(() => [...new Set(runners.value.flatMap((item) => item.engines))]
  .map((id) => ({ id, label: id === 'codex' ? 'Codex' : id === 'claude' ? 'Claude Code' : id === 'qoder' ? 'Qoder' : id })))
const roleAdmin = computed(() => ['管理员', '超级管理员'].includes(session.currentUser.role))
const canReview = computed(() => activeScope.value === 'organization' ? roleAdmin.value
  : activeScope.value === 'project' ? roleAdmin.value || selectedProject.value?.owner === session.userId
    : ['machine', 'application'].includes(activeScope.value) ? roleAdmin.value : false)
const currentScope = computed(() => scopes.find((item) => item.value === activeScope.value)!)
const draftKey = computed(() => `wao:unified-memory-draft:${activeScope.value}:${scopeId.value || 'none'}:${editing.value?.id || 'new'}`)

const categoryLabel = (value: string) => categories.value.find((item) => item.value === value)?.label || value
function stateLabel(item: MemoryDocument) {
  if (item.state === 'archived') return '已废止'
  if (item.revision.state === 'draft') return '待审核'
  if (item.revision.state === 'rejected') return '已驳回'
  if (item.revision.expiresAt && new Date(item.revision.expiresAt) <= new Date()) return '已失效'
  return `正式版 v${item.revision.versionNo}`
}
function formatTime(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
const previousVersion = (index: number) => versions.value[index + 1]

async function setScope(value: MemoryScope) {
  activeScope.value = value
  category.value = ''
  await router.replace({ query: routeQuery(value) })
  await load()
}
function routeQuery(scope: MemoryScope = activeScope.value, entry?: string) {
  return { scope, project: scope === 'project' ? projectId.value || undefined : undefined,
    machine: scope === 'machine' ? machineId.value || undefined : undefined,
    application: scope === 'application' ? applicationId.value || undefined : undefined, entry }
}
async function load() {
  if (!scopeId.value) { memories.value = []; loading.value = false; return }
  loading.value = true
  loadError.value = ''
  try {
    memories.value = await fetchMemories({ scopeType: activeScope.value, scopeId: scopeId.value,
      query: query.value.trim() || undefined, category: category.value || undefined, state: state.value || undefined })
    const entry = String(route.query.entry || '')
    if (entry) await nextTick(() => document.getElementById(`memory-${entry}`)?.scrollIntoView({ block: 'center' }))
  } catch (error) { loadError.value = error instanceof Error ? error.message : '记忆读取失败，请重试' }
  finally { loading.value = false }
}
function clearForm() {
  form.category = categories.value[0]?.value || 'context'; form.title = ''; form.content = ''
  form.changeSummary = ''; form.evidence = ''; form.expiresAt = ''; restored.value = false; saveError.value = ''
}
function restoreDraft() {
  const raw = localStorage.getItem(draftKey.value)
  if (!raw) return
  try { Object.assign(form, JSON.parse(raw)); restored.value = true }
  catch { localStorage.removeItem(draftKey.value) }
}
function openCreate() { editing.value = null; clearForm(); restoreDraft(); editorOpen.value = true }
function openEdit(item: MemoryDocument) {
  editing.value = item; clearForm(); form.category = item.category; form.title = item.revision.title
  form.content = item.revision.content; form.evidence = item.revision.sources.map((source) => source.url).filter(Boolean).join('\n')
  form.expiresAt = item.revision.expiresAt?.slice(0, 16) || ''; restoreDraft(); editorOpen.value = true
}
watch(form, () => { if (editorOpen.value && !saving.value) localStorage.setItem(draftKey.value, JSON.stringify(form)) }, { deep: true })

async function save() {
  if (!scopeId.value || !form.title.trim() || !form.content.trim()) { saveError.value = '请选择有效范围，并填写标题和正文'; return }
  saving.value = true; saveError.value = ''
  const payload: SaveMemoryPayload = { scopeType: activeScope.value, scopeId: scopeId.value,
    category: form.category, title: form.title.trim(), content: form.content.trim(),
    changeSummary: form.changeSummary.trim() || undefined,
    evidenceLinks: form.evidence.split('\n').map((item) => item.trim()).filter(Boolean),
    expiresAt: form.expiresAt ? new Date(form.expiresAt).toISOString().slice(0, 19) : undefined,
    baseRevisionId: editing.value?.currentPublishedRevisionId }
  try {
    const saved = editing.value ? await reviseMemory(editing.value.id, payload) : await createMemory(payload)
    localStorage.removeItem(draftKey.value); editorOpen.value = false
    toast(activeScope.value === 'personal' ? '个人记忆已发布' : '候选已提交，等待审核', 'ri-checkbox-circle-line')
    await router.replace({ query: routeQuery(activeScope.value, saved.id) }); await load()
  } catch (error) { saveError.value = error instanceof Error ? error.message : '保存失败，草稿已保留' }
  finally { saving.value = false }
}
async function review(item: MemoryDocument, action: 'publish' | 'reject') {
  actionId.value = item.revision.id
  try {
    if (action === 'publish') await publishMemoryRevision(item.revision.id); else await rejectMemoryRevision(item.revision.id)
    toast(action === 'publish' ? '候选已发布为正式版本' : '候选已驳回', action === 'publish' ? 'ri-checkbox-circle-line' : 'ri-close-circle-line'); await load()
  } catch (error) { toast(error instanceof Error ? error.message : '审核操作失败', 'ri-error-warning-line') }
  finally { actionId.value = '' }
}
async function archive(item: MemoryDocument) {
  if (!window.confirm(`废止记忆“${item.revision.title}”？历史版本仍会保留。`)) return
  actionId.value = item.id
  try { await archiveMemory(item.id); toast('记忆已废止', 'ri-archive-line'); await load() }
  catch (error) { toast(error instanceof Error ? error.message : '废止失败', 'ri-error-warning-line') }
  finally { actionId.value = '' }
}
async function openVersions(item: MemoryDocument) {
  versionMemory.value = item; versionsOpen.value = true; versionsLoading.value = true
  try { versions.value = await fetchMemoryRevisions(item.id) }
  catch (error) { toast(error instanceof Error ? error.message : '版本历史读取失败', 'ri-error-warning-line') }
  finally { versionsLoading.value = false }
}
function rollback(version: MemoryRevision) {
  if (!versionMemory.value || !window.confirm(`以 v${version.versionNo} 的内容创建一个新版本？`)) return
  editing.value = versionMemory.value; clearForm(); form.category = versionMemory.value.category
  form.title = version.title; form.content = version.content; form.changeSummary = `回滚到 v${version.versionNo}`
  versionsOpen.value = false; editorOpen.value = true
}
watch([query, category, state], () => { if (queryTimer) window.clearTimeout(queryTimer); queryTimer = window.setTimeout(() => void load(), 250) })
watch(projectId, async () => { if (activeScope.value === 'project') { await router.replace({ query: { scope: 'project', project: projectId.value || undefined } }); await load() } })
watch(machineId, async () => { if (activeScope.value === 'machine') { await router.replace({ query: routeQuery() }); await load() } })
watch(applicationId, async () => { if (activeScope.value === 'application') { await router.replace({ query: routeQuery() }); await load() } })
onMounted(async () => {
  const [projectResult, runnerResult] = await Promise.allSettled([fetchProjects(), fetchAiRunners()])
  projects.value = projectResult.status === 'fulfilled' ? projectResult.value : []
  runners.value = runnerResult.status === 'fulfilled' ? runnerResult.value : []
  if (!projectId.value) projectId.value = projects.value[0]?.id || ''
  if (!machineId.value) machineId.value = runners.value[0]?.id || ''
  if (!applicationId.value) applicationId.value = applications.value[0]?.id || ''
  await load()
})
</script>

<template>
  <main class="memory-center">
    <header class="hero"><div><div class="eyebrow"><i class="ri-brain-line"></i> WAO MEMORY CENTER</div><h1>记忆中心</h1><p>个人、组织、项目、机器和应用记忆共用一个可核对入口；Runner 只上传脱敏报告，管理员审核后才发布。</p></div><button class="primary" :disabled="!scopeId" @click="openCreate"><i class="ri-add-line"></i>{{ activeScope === 'personal' ? '记住一件事' : '提交候选' }}</button></header>
    <nav class="scope-tabs" aria-label="记忆范围"><button v-for="item in scopes" :key="item.value" :class="{ active: activeScope === item.value }" @click="setScope(item.value)"><i :class="item.icon"></i><span><b>{{ item.label }}记忆</b><small>{{ item.desc }}</small></span></button></nav>
    <section class="scope-note"><i :class="currentScope.icon"></i><div><b>{{ currentScope.label }}域权限已隔离</b><span>{{ currentScope.desc }}；正文始终是不可信引用，不能改变任务权限。</span></div><a href="/settings?tab=memory-mcp">管理 Codex 设备授权</a></section>
    <section v-if="activeScope === 'machine' && selectedRunner" class="collection-note" :class="{ error: selectedRunner.lastMemoryCollectionError }"><i :class="selectedRunner.lastMemoryCollectionError ? 'ri-error-warning-line' : 'ri-time-line'"></i><div><b>{{ selectedRunner.lastMemoryCollectionError ? '最近采集未产出报告' : selectedRunner.lastMemoryCollectedAt ? '自动采集已运行' : '等待首次自动采集' }}</b><span>{{ selectedRunner.lastMemoryCollectionError || (selectedRunner.lastMemoryCollectedAt ? `最近成功：${formatTime(selectedRunner.lastMemoryCollectedAt)}` : 'Runner 空闲时会检查本机 Codex 记忆；没有真实来源时不会创建空文档。') }}</span></div></section>
    <section class="toolbar"><select v-if="activeScope === 'project'" v-model="projectId"><option value="" disabled>选择项目</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select><select v-if="activeScope === 'machine'" v-model="machineId"><option value="" disabled>选择执行电脑</option><option v-for="runner in runners" :key="runner.id" :value="runner.id">{{ runner.name }}</option></select><select v-if="activeScope === 'application'" v-model="applicationId"><option value="" disabled>选择应用</option><option v-for="app in applications" :key="app.id" :value="app.id">{{ app.label }}</option></select><label class="search"><i class="ri-search-line"></i><input v-model="query" placeholder="搜索标题或正文" /></label><select v-model="category"><option value="">全部分类</option><option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option></select><select v-model="state"><option value="">全部状态</option><option value="active">正式版本</option><option v-if="activeScope !== 'personal'" value="draft">待审核</option><option v-if="activeScope !== 'personal'" value="rejected">已驳回</option><option value="archived">已废止</option></select></section>
    <section v-if="loading" class="state-card"><i class="ri-loader-4-line spin"></i>正在读取{{ currentScope.label }}记忆…</section>
    <section v-else-if="loadError" class="state-card error"><i class="ri-error-warning-line"></i>{{ loadError }}<button @click="load">重试</button></section>
    <section v-else-if="!memories.length" class="state-card"><i class="ri-inbox-archive-line"></i><b>当前范围还没有匹配记忆</b><span>{{ activeScope === 'personal' ? '记录稳定偏好、经历、职责或规划。' : ['machine','application'].includes(activeScope) ? 'Runner 只在发现真实本机记忆并成功生成报告后提交候选。' : '候选审核后才会进入 AI 上下文。' }}</span></section>
    <section v-else class="memory-list"><article v-for="item in memories" :id="`memory-${item.id}`" :key="item.id" :class="{ highlighted: route.query.entry === item.id, candidate: item.revision.state === 'draft' }"><header><span class="category">{{ categoryLabel(item.category) }}</span><span class="status" :class="item.revision.state">{{ stateLabel(item) }}</span><button title="版本历史" @click="openVersions(item)"><i class="ri-history-line"></i></button></header><h2>{{ item.revision.title }}</h2><p>{{ item.revision.content }}</p><div v-if="item.revision.changeSummary" class="change"><i class="ri-git-commit-line"></i>{{ item.revision.changeSummary }}</div><div class="sources"><a v-for="source in item.revision.sources.filter((source) => source.url)" :key="source.url" :href="source.url" target="_blank"><i class="ri-links-line"></i>{{ source.type }}</a><span v-if="item.revision.authorType === 'ai'"><i class="ri-robot-2-line"></i>AI 候选</span><time>{{ formatTime(item.updatedAt) }}</time></div><footer><div v-if="item.revision.state === 'draft' && canReview"><button class="approve" :disabled="actionId === item.revision.id" @click="review(item, 'publish')">发布</button><button class="reject" :disabled="actionId === item.revision.id" @click="review(item, 'reject')">驳回</button></div><span v-else></span><div><button v-if="item.state !== 'archived'" @click="openEdit(item)"><i class="ri-edit-line"></i>修订</button><button v-if="item.state !== 'archived' && (activeScope === 'personal' || canReview)" class="archive" @click="archive(item)"><i class="ri-archive-line"></i>废止</button></div></footer></article></section>

    <div v-if="editorOpen" class="mask" @click.self="editorOpen = false"><section class="editor"><header><div><h2>{{ editing ? '创建修订' : activeScope === 'personal' ? '记住一件事' : '提交记忆候选' }}</h2><p>{{ activeScope === 'personal' ? '保存后立即成为正式版本。' : '保存后进入审核队列，不会直接注入 AI。' }}</p></div><button @click="editorOpen = false"><i class="ri-close-line"></i></button></header><div v-if="restored" class="draft">已恢复本机未保存草稿</div><label>分类<select v-model="form.category"><option v-for="item in categories" :key="item.value" :value="item.value">{{ item.label }}</option></select></label><label>标题<input v-model="form.title" maxlength="200" /></label><label>正文<textarea v-model="form.content" maxlength="20000" rows="9"></textarea><small>{{ form.content.length }} / 20000</small></label><label>变更说明<input v-model="form.changeSummary" maxlength="1000" /></label><label>证据链接（每行一个）<textarea v-model="form.evidence" rows="3"></textarea></label><label>可选失效时间<input v-model="form.expiresAt" type="datetime-local" /></label><div v-if="saveError" class="save-error">{{ saveError }}；草稿仍保存在本机。</div><footer><span>{{ saving ? '保存中，请勿关闭…' : '输入会自动保存为本机草稿' }}</span><div><button @click="editorOpen = false">取消</button><button class="primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : activeScope === 'personal' ? '发布版本' : '提交候选' }}</button></div></footer></section></div>
    <div v-if="versionsOpen" class="mask" @click.self="versionsOpen = false"><section class="versions"><header><div><h2>版本历史</h2><p>{{ versionMemory?.revision.title }}</p></div><button @click="versionsOpen = false"><i class="ri-close-line"></i></button></header><div v-if="versionsLoading" class="version-state">读取版本中…</div><div v-else class="timeline"><article v-for="(version, index) in versions" :key="version.id"><span class="dot"></span><div><header><b>v{{ version.versionNo }} · {{ version.state }}</b><time>{{ formatTime(version.publishedAt || version.createdAt) }}</time></header><p v-if="version.changeSummary">{{ version.changeSummary }}</p><details><summary>查看该版正文</summary><h3>{{ version.title }}</h3><pre>{{ version.content }}</pre></details><details v-if="previousVersion(index)" class="diff"><summary>与 v{{ previousVersion(index)?.versionNo }} 对比</summary><div><section><b>旧版</b><pre>{{ previousVersion(index)?.content }}</pre></section><section><b>本版</b><pre>{{ version.content }}</pre></section></div></details><button v-if="!['draft','rejected'].includes(version.state) && version.id !== versionMemory?.currentPublishedRevisionId" @click="rollback(version)">以此版本创建新修订</button></div></article></div></section></div>
  </main>
</template>

<style scoped>
.memory-center{max-width:1180px;margin:0 auto;padding:28px 26px 50px}.hero{display:flex;align-items:flex-end;justify-content:space-between;gap:22px}.eyebrow{color:var(--wao-primary-text);font-size:11px;font-weight:800;letter-spacing:.12em}.hero h1{margin:7px 0 0;font-size:clamp(30px,4vw,44px)}.hero p{max-width:720px;margin:8px 0 0;color:var(--wao-text-3);font-size:13px;line-height:1.7}.primary{border:0!important;background:var(--wao-primary)!important;color:#fff!important}.hero>button{min-height:40px;padding:0 16px;border-radius:10px;white-space:nowrap}.scope-tabs{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:20px}.scope-tabs button{display:flex;gap:10px;align-items:center;padding:13px;border:1px solid var(--wao-line);border-radius:12px;background:var(--wao-bg-card);color:var(--wao-text);text-align:left}.scope-tabs button>i{font-size:22px;color:var(--wao-primary-text)}.scope-tabs span{display:grid;gap:3px}.scope-tabs b{font-size:13px}.scope-tabs small{color:var(--wao-text-weak);font-size:10px}.scope-tabs button.active{border-color:var(--wao-primary);box-shadow:0 0 0 3px var(--wao-primary-soft)}.scope-note{display:flex;align-items:center;gap:11px;margin-top:12px;padding:11px 13px;border:1px solid var(--wao-success-line);border-radius:11px;background:var(--wao-success-bg)}.scope-note>i{font-size:22px;color:var(--wao-success-strong)}.scope-note div{flex:1;display:grid;gap:2px}.scope-note b{font-size:11px}.scope-note span{color:var(--wao-text-3);font-size:10px}.scope-note a{color:var(--wao-primary-text);font-size:10px}.toolbar{display:flex;gap:8px;margin:14px 0}.toolbar select,.editor select,.editor input,.editor textarea{border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card);color:var(--wao-text);outline:0}.toolbar select{min-height:38px;padding:0 9px}.search{display:flex;align-items:center;gap:7px;flex:1;padding:0 10px;border:1px solid var(--wao-line-control);border-radius:8px;background:var(--wao-bg-card)}.search input{width:100%;border:0;outline:0;background:transparent}.state-card{min-height:280px;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:9px;border:1px solid var(--wao-line);border-radius:14px;background:var(--wao-bg-card);color:var(--wao-text-weak);font-size:12px}.state-card>i{font-size:27px}.state-card button{border:0;background:none;color:var(--wao-primary-text)}.state-card.error{color:var(--wao-danger-strong)}.memory-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:11px}.memory-list>article{min-width:0;padding:15px;border:1px solid var(--wao-line);border-radius:13px;background:var(--wao-bg-card)}.memory-list>article.candidate{border-style:dashed}.memory-list>article.highlighted{border-color:var(--wao-primary);box-shadow:0 0 0 3px var(--wao-primary-soft)}.memory-list article>header,.memory-list article>footer,.sources,.editor>header,.editor>footer,.versions>header,.timeline article header{display:flex;align-items:center}.memory-list article>header{gap:7px}.category,.status{padding:3px 7px;border-radius:99px;background:var(--wao-primary-soft);color:var(--wao-primary-text);font-size:9px}.status{margin-left:auto;background:var(--wao-bg-sub);color:var(--wao-text-3)}.status.draft{background:var(--wao-warning-bg);color:var(--wao-warning-strong)}.memory-list article header button,.versions>header button,.editor>header button{width:28px;height:28px;border:0;background:transparent}.memory-list h2{margin:11px 0 0;font-size:15px}.memory-list article>p{display:-webkit-box;overflow:hidden;margin:8px 0 0;color:var(--wao-text-2);font-size:12px;line-height:1.7;white-space:pre-wrap;-webkit-box-orient:vertical;-webkit-line-clamp:6}.change{margin-top:9px;padding:7px 8px;border-radius:7px;background:var(--wao-bg-sub);font-size:10px}.sources{gap:7px;flex-wrap:wrap;margin-top:10px;color:var(--wao-text-weak);font-size:9px}.sources a{color:var(--wao-primary-text)}.sources time{margin-left:auto}.memory-list article>footer{justify-content:space-between;margin-top:11px;padding-top:9px;border-top:1px solid var(--wao-divider)}.memory-list footer div{display:flex;gap:6px}.memory-list footer button{min-height:29px;padding:0 8px;border:0;border-radius:7px;background:var(--wao-bg-sub);font-size:10px}.memory-list footer .approve{background:var(--wao-success-bg);color:var(--wao-success-strong)}.memory-list footer .reject,.memory-list footer .archive{color:var(--wao-danger-strong)}.mask{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:14px;background:rgba(15,23,42,.46)}.editor,.versions{width:min(680px,100%);max-height:calc(100dvh - 28px);overflow:auto;box-sizing:border-box;padding:19px;border-radius:15px;background:var(--wao-bg-card)}.editor>header,.versions>header{justify-content:space-between}.editor h2,.versions h2{margin:0;font-size:18px}.editor header p,.versions header p{margin:4px 0 0;color:var(--wao-text-weak);font-size:10px}.editor>label{display:grid;gap:5px;margin-top:11px;font-size:11px;font-weight:650}.editor input,.editor select,.editor textarea{box-sizing:border-box;width:100%;padding:9px 10px;font:inherit;font-size:12px;resize:vertical}.editor label>small{text-align:right;color:var(--wao-text-weak);font-size:9px}.draft,.save-error{margin-top:10px;padding:8px 9px;border-radius:7px;background:var(--wao-warning-bg);font-size:10px}.save-error{background:var(--wao-danger-bg);color:var(--wao-danger-strong)}.editor>footer{justify-content:space-between;gap:10px;margin-top:14px;padding-top:11px;border-top:1px solid var(--wao-divider)}.editor footer>span{color:var(--wao-text-weak);font-size:9px}.editor footer div{display:flex;gap:7px}.editor footer button{min-height:35px;padding:0 12px;border:0;border-radius:8px;background:var(--wao-bg-sub)}.version-state{min-height:160px;display:grid;place-items:center}.timeline{margin-top:14px}.timeline>article{display:grid;grid-template-columns:18px 1fr;gap:6px}.dot{width:11px;height:11px;margin-top:5px;border-radius:50%;background:var(--wao-primary)}.timeline article>div{padding-bottom:14px}.timeline article header{justify-content:space-between}.timeline b{font-size:11px}.timeline time{font-size:9px}.timeline pre{max-height:180px;overflow:auto;padding:8px;background:var(--wao-bg-sub);white-space:pre-wrap;font:inherit;font-size:10px}.timeline button{border:0;background:none;color:var(--wao-primary-text);font-size:10px}.spin{animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.diff>div{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px}.diff section{min-width:0;padding:8px;border-radius:7px;background:var(--wao-bg-soft)}.diff section:first-child{background:color-mix(in srgb,var(--wao-danger-bg) 70%,var(--wao-bg-card))}.diff section:last-child{background:color-mix(in srgb,var(--wao-success-bg) 70%,var(--wao-bg-card))}
.scope-tabs{grid-template-columns:repeat(5,1fr)}
.collection-note{display:flex;align-items:center;gap:11px;margin-top:12px;padding:11px 13px;border:1px solid var(--wao-success-line);border-radius:11px;background:var(--wao-success-bg)}
.collection-note>i{font-size:22px;color:var(--wao-success-strong)}.collection-note div{display:grid;flex:1;gap:2px}.collection-note b{font-size:11px}.collection-note span{color:var(--wao-text-3);font-size:10px}.collection-note.error{border-color:var(--wao-danger-line);background:var(--wao-danger-bg)}.collection-note.error>i{color:var(--wao-danger-strong)}
@media(max-width:1100px){.scope-tabs{grid-template-columns:repeat(3,1fr)}}
@media(max-width:760px){.memory-center{padding:18px 12px}.hero{display:grid}.hero>button{width:100%}.scope-tabs{grid-template-columns:1fr}.toolbar{display:grid;grid-template-columns:1fr 1fr}.search{grid-column:1/-1;min-height:38px}.memory-list{grid-template-columns:1fr}.mask{padding:7px;align-items:end}.editor,.versions{max-height:calc(100dvh - 14px)}.editor>footer{align-items:stretch;flex-direction:column}.diff>div{grid-template-columns:1fr}}
</style>
