<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { fetchAiRunners } from '@/api/ai'
import {
  createSession,
  getSession,
  listSessions,
  pauseSession,
  saveSessionPreference,
  sessionWorkflows,
  type AiSessionItem,
  type SessionDetail,
  type SessionPreference,
} from '@/api/aiSessions'
import type { AiRun, AiRunner, AiWorkflow } from '@/types'
import AiSessionConversation from '@/components/ai/AiSessionConversation.vue'
import { createVisibilityPoller } from '@/composables/visibilityPolling'
import { toast } from '@/composables/toast'

const route = useRoute(),
  router = useRouter(),
  account = useSessionStore()
const selectedId = computed(() =>
  typeof route.query.session === 'string' ? route.query.session : '',
)
const isNew = computed(() => route.query.new === '1')
const page = ref(1),
  total = ref(0),
  rows = ref<AiSessionItem[]>([])
const q = ref(''),
  status = ref(''),
  engine = ref(''),
  archived = ref(false)
const listLoading = ref(false),
  listError = ref(''),
  detailLoading = ref(false),
  detailError = ref('')
const detail = ref<SessionDetail | null>(null),
  busy = ref(false),
  actionError = ref('')
const rename = ref(false),
  titleDraft = ref(''),
  mobileList = ref(false)
const workflows = ref<AiWorkflow[]>([]),
  runners = ref<AiRunner[]>([]),
  setupError = ref(''),
  setupLoading = ref(false),
  setupReady = ref(false),
  setupCheckedAt = ref('')
let setupSequence = 0
const form = reactive({
  goal: '',
  projectId: '',
  runnerId: '',
  mode: 'direct',
  reasoningEffort: 'medium',
})
const creating = ref(false),
  createError = ref(''),
  newDraftError = ref('')
let listSequence = 0,
  detailSequence = 0,
  disposed = false,
  draftReady = false,
  ticks = 0
const newDraftKey = computed(() => `wao:ai-session-new:${account.userId}`)
const active = (s: string) => ['queued', 'claimed', 'running'].includes(s)
const labels: Record<string, string> = {
  queued: '排队中',
  claimed: '准备中',
  running: '运行中',
  succeeded: '执行结束',
  failed: '需处理',
  cancelled: '已暂停',
}
const title = computed(
  () => detail.value?.preference.title || detail.value?.run.taskTitle || '会话',
)
const selectedActive = computed(() => !!detail.value && active(detail.value.run.status))
const sessionState = computed(() =>
  (labels[detail.value?.run.status || ''] || '') +
    (detail.value?.run.deliveryStage === 'not_required' ? ' · 本轮无需部署' : ''),
)
const maxPage = computed(() => Math.max(1, Math.ceil(total.value / 25)))
const activityTime = (value: string) => {
  const date = new Date(value.replace(' ', 'T'))
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}
async function loadList() {
  const seq = ++listSequence
  listLoading.value = true
  try {
    const result = await listSessions({
      q: q.value,
      status: status.value,
      engine: engine.value,
      archived: archived.value,
      page: page.value,
    })
    if (disposed || seq !== listSequence) return
    rows.value = result.items
    total.value = result.total
    listError.value = ''
    const last = Math.max(1, Math.ceil(result.total / 25))
    if (page.value > last) {
      page.value = last
      void loadList()
      return
    }
    if (!selectedId.value && !isNew.value && result.items[0])
      await router.replace({ path: '/ai-sessions', query: { session: result.items[0].id } })
  } catch (e) {
    if (seq === listSequence) listError.value = e instanceof Error ? e.message : '会话列表读取失败'
  } finally {
    if (seq === listSequence) listLoading.value = false
  }
}
async function loadDetail() {
  const id = selectedId.value,
    seq = ++detailSequence
  if (!id || isNew.value) {
    detail.value = null
    return
  }
  detailLoading.value = !detail.value
  try {
    const result = await getSession(id)
    if (disposed || seq !== detailSequence || selectedId.value !== id) return
    if (busy.value && detail.value) result.preference = detail.value.preference
    detail.value = result
    detailError.value = ''
  } catch (e) {
    if (seq === detailSequence) detailError.value = e instanceof Error ? e.message : '会话读取失败'
  } finally {
    if (seq === detailSequence) detailLoading.value = false
  }
}
const renameKey = () => `wao:ai-session-name:${account.userId}:${selectedId.value}`
function beginRename() {
  rename.value = true
  try {
    titleDraft.value = localStorage.getItem(renameKey()) ?? title.value
  } catch {
    titleDraft.value = title.value
    actionError.value = '无法读取本机会话名称草稿'
  }
}
function saveNameDraft() {
  try {
    localStorage.setItem(renameKey(), titleDraft.value)
  } catch {
    actionError.value = '本机名称草稿保存失败，请保留页面'
  }
}
function cancelRename() {
  rename.value = false
  try {
    localStorage.removeItem(renameKey())
  } catch {
    /* explicit cancellation */
  }
}
function select(id: string) {
  mobileList.value = false
  void router.push({ path: '/ai-sessions', query: { session: id } })
}
function startNew() {
  mobileList.value = false
  void router.push({ path: '/ai-sessions', query: { new: '1' } })
}
function changePage(delta: number) {
  page.value += delta
  void loadList()
}
function search() {
  page.value = 1
  void loadList()
}
watch([status, engine, archived], search)
watch([selectedId, isNew], () => {
  detail.value = null
  detailError.value = ''
  rename.value = false
  actionError.value = ''
  void loadDetail()
})
async function setup() {
  const seq = ++setupSequence
  const owner = account.userId
  setupReady.value = false
  setupLoading.value = true
  setupError.value = ''
  try {
    const [flows, nodes] = await Promise.all([sessionWorkflows(), fetchAiRunners()])
    if (disposed || seq !== setupSequence || owner !== account.userId) return
    workflows.value = flows
    runners.value = nodes
    if (!form.projectId) form.projectId = flows[0]?.projectId || ''
    setupReady.value = true
    setupCheckedAt.value = new Date().toLocaleTimeString()
  } catch (e) {
    if (!disposed && seq === setupSequence && owner === account.userId)
      setupError.value = e instanceof Error ? e.message : '执行配置读取失败'
  } finally {
    if (!disposed && seq === setupSequence && owner === account.userId) setupLoading.value = false
  }
}
function restoreDraft() {
  draftReady = false
  try {
    const saved = JSON.parse(localStorage.getItem(newDraftKey.value) || '{}')
    for (const key of ['goal', 'projectId', 'runnerId', 'mode', 'reasoningEffort'] as const)
      if (typeof saved[key] === 'string') form[key] = saved[key]
  } catch {
    newDraftError.value = '草稿读取失败，请检查输入'
  }
  draftReady = true
}
watch(
  form,
  () => {
    if (!draftReady || !account.userId) return
    try {
      localStorage.setItem(newDraftKey.value, JSON.stringify(form))
      newDraftError.value = ''
    } catch {
      newDraftError.value = '本机草稿保存失败，请保留页面'
    }
  },
  { deep: true },
)
watch(
  () => account.userId,
  () => {
    ++listSequence
    ++detailSequence
    rows.value = []
    detail.value = null
    Object.assign(form, {
      goal: '',
      projectId: '',
      runnerId: '',
      mode: 'direct',
      reasoningEffort: 'medium',
    })
    restoreDraft()
    void setup()
    void loadList()
    void loadDetail()
  },
)
const projectAvailable = computed(() => workflows.value.some((flow) => flow.projectId === form.projectId))
const runnerAvailable = computed(() => !form.runnerId || runners.value.some((node) => node.id === form.runnerId))
const canLaunch = computed(() => setupReady.value && !setupLoading.value && !setupError.value && projectAvailable.value && runnerAvailable.value && !!form.goal.trim() && !creating.value)
async function launch() {
  if (!canLaunch.value) return
  creating.value = true
  createError.value = ''
  try {
    const run = await createSession({
      goal: form.goal.trim(),
      projectId: form.projectId,
      runnerId: form.runnerId || null,
      planMode: form.mode === 'plan',
      goalMode: form.mode === 'goal',
      reasoningEffort: form.reasoningEffort,
    })
    form.goal = ''
    select(run.id)
    void loadList()
  } catch (e) {
    createError.value = e instanceof Error ? e.message : '派发失败，草稿已保留，请重试'
  } finally {
    creating.value = false
  }
}
async function preference(changes: Partial<SessionPreference>) {
  if (!detail.value || busy.value) return
  const id = detail.value.run.id
  busy.value = true
  actionError.value = ''
  try {
    const saved = await saveSessionPreference(id, { ...detail.value.preference, ...changes })
    ++detailSequence
    if (detail.value?.run.id === id) {
      detail.value.preference = saved
      if ('title' in changes) cancelRename()
    }
    await loadList()
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '保存失败，请重试'
  } finally {
    busy.value = false
  }
}
async function pause() {
  if (!detail.value || busy.value) return
  const id = detail.value.run.id
  busy.value = true
  actionError.value = ''
  try {
    updated(await pauseSession(id))
    await loadList()
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '暂停失败，请重试'
  } finally {
    busy.value = false
  }
}
function updated(run: AiRun) {
  if (detail.value?.run.id === run.id) {
    ++detailSequence
    detail.value.run = run
  }
  void loadList()
}
async function copyId() {
  try {
    await navigator.clipboard.writeText(detail.value?.run.sessionId || '')
    toast('会话 ID 已复制')
  } catch {
    actionError.value = '复制失败，可选中下方会话 ID 手动复制'
  }
}
const poller = createVisibilityPoller(
  async () => {
    ticks++
    await loadDetail()
    if (ticks % 3 === 0) await loadList()
  },
  () => (selectedActive.value ? 5000 : 15000),
)
onMounted(() => {
  restoreDraft()
  void setup()
  void loadList()
  void loadDetail()
  poller.start(false)
})
onBeforeUnmount(() => {
  disposed = true
  ++listSequence
  ++detailSequence
  poller.stop()
})
</script>

<template>
  <section
    class="sessions-page"
    :class="{ 'show-mobile-list': mobileList }"
    aria-label="AI 会话工作区"
  >
    <aside class="session-sidebar">
      <header>
        <div>
          <span class="eyebrow">WAO · AI RUN</span>
          <h1>AI 会话</h1>
        </div>
        <button class="new-session" @click="startNew"><i class="ri-add-line"></i> 新会话</button>
      </header>
      <p class="sidebar-intro">任务与对话集中管理，服务器持续执行。</p>
      <form class="search" @submit.prevent="search">
        <input
          v-model="q"
          maxlength="160"
          placeholder="搜索任务、会话名或 session ID"
          aria-label="搜索会话"
        /><button aria-label="搜索" type="submit"><i class="ri-search-line"></i></button>
      </form>
      <div class="filters">
        <select v-model="status" aria-label="会话状态">
          <option value="">全部状态</option>
          <option value="active">运行中</option>
          <option value="succeeded">执行结束</option>
          <option value="failed">需处理</option>
          <option value="cancelled">已暂停</option>
        </select>
        <select v-model="engine" aria-label="AI 工具">
          <option value="">全部 AI</option>
          <option value="codex">Codex</option>
          <option value="claude">Claude Code</option>
          <option value="qoder">Qoder</option>
        </select>
      </div>
      <div class="list-tabs">
        <button :class="{ chosen: !archived }" @click="archived = false">会话</button
        ><button :class="{ chosen: archived }" @click="archived = true">已归档</button
        ><button
          class="refresh"
          :disabled="listLoading"
          aria-label="刷新会话列表"
          @click="loadList"
        >
          <i class="ri-refresh-line"></i>
        </button>
      </div>
      <div v-if="listError" class="error" role="alert">
        {{ listError }} <button @click="loadList">重试</button>
      </div>
      <div class="session-list" aria-label="会话列表" :aria-busy="listLoading">
        <p v-if="listLoading && !rows.length" class="placeholder">正在读取会话…</p>
        <p v-else-if="!rows.length" class="placeholder">
          {{
            q || status || engine
              ? '没有符合筛选的会话，试试其它条件。'
              : archived
                ? '暂无归档会话。'
                : '还没有会话，从新会话开始。'
          }}
        </p>
        <button
          v-for="row in rows"
          :key="row.id"
          class="session-row"
          :class="{ selected: row.id === selectedId }"
          :aria-current="row.id === selectedId ? 'true' : undefined"
          @click="select(row.id)"
        >
          <div class="row-title">
            <i v-if="row.pinned" class="ri-pushpin-fill"></i><strong>{{ row.title }}</strong
            ><time>{{ activityTime(row.updatedAt) }}</time>
          </div>
          <p>{{ row.preview || '等待第一条 AI 反馈' }}</p>
          <div class="row-meta">
            <span :class="{ running: active(row.status), failed: row.status === 'failed' }">{{
              labels[row.status] || row.status
            }}</span
            ><span>{{ row.engine }} · {{ row.projectName }}</span>
          </div>
        </button>
      </div>
      <footer class="pagination">
        <button
          :disabled="page === 1 || listLoading"
          @click="changePage(-1)"
        >
          上一页</button
        ><span>{{ page }} / {{ maxPage }} · {{ total }} 条</span
        ><button
          :disabled="page >= maxPage || listLoading"
          @click="changePage(1)"
        >
          下一页
        </button>
      </footer>
    </aside>
    <main class="session-main">
      <button class="mobile-back" @click="mobileList = true">
        <i class="ri-arrow-left-line"></i> 会话列表
      </button>
      <div v-if="isNew || (!selectedId && !listLoading)" class="new-panel">
        <div class="new-heading">
          <i class="ri-chat-history-line"></i>
          <h2>开始一段持续的工作</h2>
          <p>说清目标，交给执行电脑。离开页面后任务继续运行，回来直接接着聊。</p>
        </div>
        <form @submit.prevent="launch">
          <p v-if="setupLoading" class="hint" role="status">正在检查可用工作流与执行电脑…目标内容可继续填写。</p>
          <div v-if="setupError" class="error" role="alert">
            配置读取失败：{{ setupError }}。尚未派发，目标草稿已保留。 <button type="button" @click="setup">重新加载配置</button>
          </div>
          <p v-if="setupReady" class="hint">配置检查于 {{ setupCheckedAt }} <button type="button" @click="setup" :disabled="creating || setupLoading">刷新配置</button></p>
          <label
            >项目工作流<select
              v-model="form.projectId"
              :disabled="creating || setupLoading"
              required
            >
              <option value="" disabled>选择项目</option>
              <option v-if="form.projectId && !projectAvailable" :value="form.projectId" disabled>原选择暂不可用，请重新选择项目</option>
              <option v-for="flow in workflows" :key="flow.projectId" :value="flow.projectId">
                {{ flow.projectName }} · {{ flow.config?.defaultEngine || 'Codex' }}
              </option>
            </select></label
          >
          <label
            >目标或问题<textarea
              v-model="form.goal"
              :readonly="creating"
              maxlength="12000"
              required
              placeholder="描述这次要做的事；后续直接在同一个会话中追加要求。"
              aria-label="新会话内容"
            ></textarea>
          </label>
          <div class="new-options">
            <label
              >执行电脑<select v-model="form.runnerId" :disabled="creating || setupLoading || !setupReady">
                <option value="">自动分配空闲电脑</option>
                <option v-if="!runnerAvailable" :value="form.runnerId" disabled>原执行电脑不可用，请重新选择</option>
                <option v-for="node in runners" :key="node.id" :value="node.id">
                  {{ node.name }} · {{ node.online ? '在线' : '离线，等待上线' }}
                </option>
              </select></label
            >
            <label
              >执行方式<select v-model="form.mode" :disabled="creating">
                <option value="direct">直接执行 · 问答与明确的小任务</option>
                <option value="plan">先规划再执行</option>
                <option value="goal">持续目标 · 复杂长任务</option>
              </select></label
            >
            <label
              >推理强度<select v-model="form.reasoningEffort" :disabled="creating">
                <option value="low">低 · 简单问答</option>
                <option value="medium">中 · 日常任务</option>
                <option value="high">高 · 复杂任务</option>
              </select></label
            >
          </div>
          <p class="hint">
            新 Codex 会话默认
            GPT-6。纯问答建议直接执行；复杂开发可选持续目标。仍由已接入的服务器执行。
          </p>
          <p v-if="setupReady && !setupLoading && !workflows.length" class="error">
            暂无可用项目工作流，请先在首页的 AI 工作流中配置项目。
          </p>
          <div class="new-footer">
            <small role="status">{{
              createError ||
              newDraftError ||
              (form.goal ? '草稿已保存在本机' : '新会话会自动关联一条 WAO 任务')
            }}</small
            ><button
              class="primary"
              :disabled="!canLaunch"
            >
              {{ creating ? '正在派发…' : createError ? '重试派发' : '发送并开始' }}
              <i class="ri-arrow-up-line"></i>
            </button>
          </div>
        </form>
      </div>
      <p v-else-if="detailLoading" class="placeholder">正在读取会话与历史…</p>
      <div v-else-if="!detail && detailError" class="error">
        {{ detailError }} <button @click="loadDetail">重试</button>
      </div>
      <template v-else-if="detail">
        <header class="conversation-header">
          <div class="heading">
            <h2>{{ title }}</h2>
            <p>
              {{ detail.run.projectName }} <span>·</span>
              {{ detail.run.runnerName || '等待分配执行电脑' }} <span>·</span> {{ sessionState }}
            </p>
          </div>
          <div class="session-actions">
            <button :disabled="busy" @click="preference({ pinned: !detail.preference.pinned })">
              {{ detail.preference.pinned ? '取消置顶' : '置顶' }}</button
            ><button :disabled="busy" @click="beginRename">改名</button
            ><button
              :disabled="busy"
              @click="preference({ archived: !detail.preference.archived })"
            >
              {{ detail.preference.archived ? '移回会话' : '归档' }}</button
            ><button v-if="selectedActive" :disabled="busy" @click="pause">暂停执行</button>
          </div>
          <form v-if="rename" class="rename" @submit.prevent="preference({ title: titleDraft })">
            <input
              v-model="titleDraft"
              @input="saveNameDraft"
              maxlength="160"
              :readonly="busy"
              aria-label="会话名称"
            /><button :disabled="busy">{{ busy ? '保存中…' : '保存名称' }}</button
            ><button type="button" @click="cancelRename">取消</button>
          </form>
          <div class="session-identity">
            <code>{{ detail.run.sessionId || '会话建立后自动绑定 session ID' }}</code
            ><button v-if="detail.run.sessionId" @click="copyId">复制 ID</button
            ><RouterLink
              :to="{
                path: `/projects/${detail.run.projectId}`,
                query: { task: detail.run.taskId },
              }"
              >关联任务 ↗</RouterLink
            >
          </div>
          <div v-if="detail.preference.archived" class="hint">
            此会话已归档；历史和服务器会话仍然保留，可随时继续对话。
          </div>
          <div v-if="actionError || detailError" class="error" role="alert">
            {{ actionError || detailError }}
            <button v-if="detailError" @click="loadDetail">重试同步</button>
          </div>
        </header>
        <AiSessionConversation
          :key="`${account.userId}:${detail.run.id}`"
          :run="detail.run"
          :prompt="detail.prompt"
          @updated="updated"
          @new-session="startNew"
        />
      </template>
    </main>
  </section>
</template>

<style scoped>
.sessions-page {
  height: calc(100dvh - 145px);
  min-height: 520px;
  display: grid;
  grid-template-columns: 310px minmax(0, 1fr);
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  overflow: hidden;
  background: var(--wao-bg-card);
  color: var(--wao-text);
}
button,
input,
select,
textarea {
  font: inherit;
}
button {
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.session-sidebar {
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
}
.session-sidebar header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 18px 0;
}
.eyebrow {
  font-size: 10px;
  letter-spacing: 1.5px;
  color: var(--wao-text-weak);
}
h1 {
  font-size: 21px;
  margin: 5px 0 0;
}
.new-session {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  padding: 8px 10px;
  color: var(--wao-primary);
  border-radius: 8px;
  font-size: 12px;
}
.sidebar-intro {
  font-size: 11px;
  color: var(--wao-text-3);
  padding: 0 18px;
  margin: 12px 0 18px;
}
.search {
  display: flex;
  margin: 0 14px 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 8px;
}
.search input {
  min-width: 0;
  width: 100%;
  border: 0;
  background: transparent;
  padding: 10px;
  color: var(--wao-text);
  font-size: 12px;
}
.search button {
  border: 0;
  background: transparent;
  color: var(--wao-text-3);
  padding: 8px;
}
.filters {
  display: flex;
  gap: 8px;
  padding: 0 14px;
}
.filters select {
  min-width: 0;
  flex: 1;
  border: 1px solid var(--wao-line);
  border-radius: 7px;
  padding: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  font-size: 12px;
}
.list-tabs {
  display: flex;
  gap: 5px;
  padding: 14px;
}
.list-tabs button {
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text-3);
  padding: 6px 10px;
  font-size: 12px;
}
.list-tabs .chosen {
  background: var(--wao-primary-soft);
  color: var(--wao-primary);
}
.list-tabs .refresh {
  margin-left: auto;
}
.session-list {
  overflow: auto;
  flex: 1;
  min-height: 0;
  padding: 0 8px 8px;
}
.session-row {
  display: block;
  width: 100%;
  border: 1px solid transparent;
  text-align: left;
  padding: 13px 12px;
  border-radius: 10px;
  background: transparent;
  color: var(--wao-text);
  margin-bottom: 4px;
}
.session-row:hover {
  background: var(--wao-bg-card);
}
.session-row.selected {
  border-color: var(--wao-primary);
  background: var(--wao-primary-soft);
}
.row-title {
  display: flex;
  gap: 6px;
  align-items: start;
}
.row-title strong {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.row-title time {
  font-size: 10px;
  color: var(--wao-text-weak);
  white-space: nowrap;
  margin-top: 3px;
}
.session-row p {
  font-size: 11px;
  color: var(--wao-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin: 7px 0;
}
.row-meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 10px;
  color: var(--wao-text-weak);
}
.row-meta span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-meta .running {
  color: var(--wao-primary);
}
.row-meta .failed {
  color: var(--wao-danger-strong);
}
.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  border-top: 1px solid var(--wao-line);
  font-size: 11px;
  color: var(--wao-text-3);
}
.pagination button {
  border: 0;
  background: transparent;
  color: var(--wao-text-2);
  font-size: 11px;
}
.session-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.conversation-header {
  padding: 18px 24px 12px;
  border-bottom: 1px solid var(--wao-line);
  flex-shrink: 0;
}
.heading h2 {
  font-size: 17px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  margin: 0 0 4px;
}
.heading p {
  font-size: 11px;
  color: var(--wao-text-3);
  margin: 0 0 12px;
}
.heading span {
  margin: 0 6px;
}
.session-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.session-actions button,
.rename button {
  border: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  color: var(--wao-text-2);
  border-radius: 7px;
  padding: 6px 10px;
  font-size: 11px;
}
.session-identity {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
  font-size: 10px;
  color: var(--wao-text-weak);
}
.session-identity code {
  overflow-wrap: anywhere;
}
.session-identity a,
.session-identity button {
  color: var(--wao-primary);
  background: transparent;
  border: 0;
  font-size: 10px;
}
.rename {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.rename input {
  min-width: 0;
  flex: 1;
  padding: 8px;
  border-radius: 7px;
  border: 1px solid var(--wao-line-control);
  color: var(--wao-text);
  background: var(--wao-bg-card);
}
.placeholder {
  padding: 24px;
  color: var(--wao-text-3);
  font-size: 13px;
}
.error {
  font-size: 12px;
  color: var(--wao-danger-strong);
  padding: 10px;
  overflow-wrap: anywhere;
}
.error button {
  margin-left: 6px;
}
.new-panel {
  overflow: auto;
  padding: 36px clamp(20px, 6%, 80px);
}
.new-heading {
  margin-bottom: 28px;
}
.new-heading > i {
  font-size: 30px;
  color: var(--wao-primary);
}
.new-heading h2 {
  font-size: 24px;
  margin: 12px 0;
}
.new-heading p,
.hint {
  font-size: 12px;
  line-height: 1.7;
  color: var(--wao-text-3);
}
.new-panel label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 18px;
  color: var(--wao-text-2);
}
.new-panel select,
.new-panel textarea {
  box-sizing: border-box;
  max-width: 100%;
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-sub);
  color: var(--wao-text);
  font-size: 13px;
}
.new-panel textarea {
  min-height: 180px;
  resize: vertical;
  line-height: 1.8;
}
.new-options {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.new-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.new-footer small {
  font-size: 11px;
  color: var(--wao-text-3);
}
.primary {
  background: var(--wao-primary);
  border: 0;
  color: #fff;
  padding: 12px 20px;
  border-radius: 9px;
  white-space: nowrap;
}
.mobile-back {
  display: none;
}
@media (max-width: 1000px) {
  .sessions-page {
    grid-template-columns: 260px minmax(0, 1fr);
  }
  .new-options {
    grid-template-columns: 1fr;
  }
  .conversation-header {
    padding: 14px;
  }
  .new-panel {
    padding: 24px;
  }
}
@media (max-width: 700px) {
  .sessions-page {
    display: flex;
    min-height: 0;
    height: calc(100dvh - 150px);
    border-radius: 10px;
  }
  .session-sidebar {
    display: none;
    width: 100%;
    border: 0;
  }
  .session-main {
    width: 100%;
  }
  .show-mobile-list .session-sidebar {
    display: flex;
  }
  .show-mobile-list .session-main {
    display: none;
  }
  .mobile-back {
    display: block;
    align-self: flex-start;
    border: 0;
    background: transparent;
    padding: 10px 14px;
    color: var(--wao-primary);
    font-size: 12px;
  }
  .conversation-header {
    padding: 0 12px 10px;
  }
  .heading h2 {
    font-size: 14px;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .session-identity {
    gap: 6px;
  }
  .session-actions {
    gap: 5px;
  }
  .new-panel {
    padding: 16px;
  }
  .new-heading h2 {
    font-size: 20px;
  }
  .new-footer {
    align-items: flex-start;
  }
}
</style>
