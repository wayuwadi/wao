<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { apiCreateProject, fetchProjects } from '@/api/board'
import { continueAiRun, createAiRuns, fetchTaskAiRuns } from '@/api/ai'
import {
  acceptIdeaResearch,
  addIdeaTask,
  createIdea,
  createIdeaIteration,
  fetchIdea,
  fetchIdeas,
  patchIdea,
  patchIdeaIteration,
  startIdeaResearch,
  transitionIdea,
  unlinkIdeaTask,
} from '@/api/ideas'
import { useSessionStore } from '@/stores/session'
import type {
  Idea,
  IdeaDetail,
  IdeaIterationStatus,
  IdeaPriority,
  IdeaStage,
  Project,
} from '@/types'

const router = useRouter()
const session = useSessionStore()
const stages: Array<{ id: IdeaStage; label: string }> = [
  { id: 'INBOX', label: '收集箱' },
  { id: 'RESEARCHING', label: '看明白' },
  { id: 'THINKING', label: '想明白' },
  { id: 'ALIGNING', label: '说明白' },
  { id: 'MVP_BUILDING', label: '干明白' },
  { id: 'VALIDATING', label: '验证中' },
  { id: 'ACHIEVED', label: '已达成' },
  { id: 'ABANDONED', label: '已放弃' },
]
const activeStages: IdeaStage[] = [
  'RESEARCHING',
  'THINKING',
  'ALIGNING',
  'MVP_BUILDING',
  'VALIDATING',
]
const ideas = ref<Idea[]>([])
const projects = ref<Project[]>([])
const selectedId = ref('')
const selected = computed(() => ideas.value.find((item) => item.id === selectedId.value) ?? null)
const loading = ref(true)
const error = ref('')
const view = ref<'board' | 'list'>('board')
const showQuick = ref(true)
const search = ref('')
const filters = reactive({ stage: '', owner: '', creator: '', priority: '', project: '' })
const quick = reactive({ title: '', rawIdea: '', priority: 'P1' as IdeaPriority })
const creating = ref(false)
const saving = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const dirty = ref(false)
let saveTimer: number | undefined
let pollTimer: number | undefined

const draft = reactive({
  title: '',
  rawIdea: '',
  problem: '',
  targetOutcome: '',
  priority: 'P1' as IdeaPriority,
  ownerId: '',
  projectId: '',
  detail: {} as IdeaDetail,
})
const taskDraft = reactive({
  title: '',
  description: '',
  priority: 'P1' as IdeaPriority,
  requiredMvp: true,
})
const iterationDraft = reactive({ taskId: '', objective: '', acceptanceCriteria: '' })
const closure = reactive({ conclusion: '', evidence: '', reason: '' })

const filtered = computed(() =>
  ideas.value.filter((idea) => {
    const q = search.value.trim().toLowerCase()
    return (
      (!q ||
        `${idea.title} ${idea.rawIdea ?? ''} ${idea.problem ?? ''}`.toLowerCase().includes(q)) &&
      (!filters.stage || idea.stage === filters.stage) &&
      (!filters.owner || idea.ownerId === filters.owner) &&
      (!filters.creator || idea.createdBy === filters.creator) &&
      (!filters.priority || idea.priority === filters.priority) &&
      (!filters.project || idea.projectId === filters.project)
    )
  }),
)
const activeCount = computed(
  () => ideas.value.filter((idea) => activeStages.includes(idea.stage)).length,
)
const stageChips = computed(() => [
  { id: '', label: '全部', count: ideas.value.length },
  ...stages.map((stage) => ({
    ...stage,
    count: ideas.value.filter((idea) => idea.stage === stage.id).length,
  })),
])
const stageGroups = computed(() =>
  stages
    .map((stage) => ({
      ...stage,
      items: filtered.value.filter((idea) => idea.stage === stage.id),
    }))
    .filter((group) => group.items.length > 0),
)
const stageLabel = (stage: IdeaStage) => stages.find((item) => item.id === stage)?.label ?? stage
const memberName = (id: string) => session.members.find((member) => member.id === id)?.name ?? id
const projectName = (id?: string | null) =>
  projects.value.find((project) => project.id === id)?.name ?? '未关联'
const nextStage = computed(() => {
  if (!selected.value) return null
  const index = stages.findIndex((item) => item.id === selected.value?.stage)
  return index >= 0 && index < 6 ? stages[index + 1] : null
})

function copyToDraft(idea: Idea) {
  draft.title = idea.title
  draft.rawIdea = idea.rawIdea ?? ''
  draft.problem = idea.problem ?? ''
  draft.targetOutcome = idea.targetOutcome ?? ''
  draft.priority = idea.priority
  draft.ownerId = idea.ownerId
  draft.projectId = idea.projectId ?? ''
  draft.detail = {
    ...idea.detail,
    meetingParticipants: [...(idea.detail.meetingParticipants ?? [])],
  }
  closure.conclusion = idea.detail.validationConclusion ?? ''
  closure.evidence = idea.detail.validationEvidence ?? ''
  closure.reason = idea.detail.abandonmentReason ?? ''
  const cached = localStorage.getItem(`wao-idea-draft:${idea.id}`)
  if (cached) {
    try {
      Object.assign(draft, JSON.parse(cached))
      dirty.value = true
    } catch {
      /* 忽略损坏草稿 */
    }
  } else dirty.value = false
  saving.value = 'idle'
}

async function load() {
  loading.value = true
  const [ideaResult, projectResult] = await Promise.all([fetchIdeas(), fetchProjects()])
  ideas.value = ideaResult
  projects.value = projectResult
  if (!selectedId.value && ideas.value.length) selectedId.value = ideas.value[0]!.id
  if (selected.value) copyToDraft(selected.value)
  loading.value = false
}

async function refreshSelected(background = false) {
  if (!selectedId.value || (background && dirty.value)) return
  const fresh = await fetchIdea(selectedId.value)
  const activeIterations = fresh.iterations.filter(
    (item) => item.status === 'RUNNING' && item.aiRunId,
  )
  for (const taskId of [...new Set(activeIterations.map((item) => item.taskId))]) {
    const taskRuns = await fetchTaskAiRuns(taskId).catch(() => [])
    for (const iteration of activeIterations.filter((item) => item.taskId === taskId)) {
      const run = taskRuns.find((item) => item.id === iteration.aiRunId)
      if (run?.status === 'succeeded') {
        await patchIdeaIteration(fresh.id, iteration.id, {
          status: 'READY_FOR_REVIEW',
          resultSummary: run.resultSummary ?? run.latestProgress ?? 'AI 已完成',
          reportUrl: run.reportUrl ?? undefined,
        })
      } else if (run && ['failed', 'cancelled'].includes(run.status)) {
        await patchIdeaIteration(fresh.id, iteration.id, {
          status: 'FAILED',
          resultSummary: run.resultSummary ?? run.latestProgress ?? 'AI Run 未成功完成',
        })
      }
    }
  }
  const synchronized = activeIterations.length ? await fetchIdea(selectedId.value) : fresh
  const index = ideas.value.findIndex((item) => item.id === synchronized.id)
  if (index >= 0) ideas.value[index] = synchronized
  else ideas.value.unshift(synchronized)
  copyToDraft(synchronized)
}

async function submitQuick() {
  if (!quick.title.trim()) return
  creating.value = true
  try {
    const created = await createIdea({
      title: quick.title.trim(),
      rawIdea: quick.rawIdea,
      priority: quick.priority,
    })
    ideas.value.unshift(created)
    selectedId.value = created.id
    quick.title = ''
    quick.rawIdea = ''
    showQuick.value = false
    copyToDraft(created)
  } finally {
    creating.value = false
  }
}

function queueSave() {
  if (!selected.value) return
  dirty.value = true
  saving.value = 'idle'
  localStorage.setItem(`wao-idea-draft:${selected.value.id}`, JSON.stringify(draft))
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(saveNow, 800)
}

function updateMeetingParticipants(event: Event) {
  draft.detail.meetingParticipants = (event.target as HTMLInputElement).value
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean)
  queueSave()
}

async function saveNow() {
  const idea = selected.value
  if (!idea || !dirty.value) return
  saving.value = 'saving'
  try {
    const updated = await patchIdea(idea.id, {
      title: draft.title,
      rawIdea: draft.rawIdea,
      problem: draft.problem,
      targetOutcome: draft.targetOutcome,
      priority: draft.priority,
      ownerId: draft.ownerId,
      projectId: draft.projectId,
      detail: draft.detail,
    })
    ideas.value[ideas.value.findIndex((item) => item.id === updated.id)] = updated
    dirty.value = false
    localStorage.removeItem(`wao-idea-draft:${idea.id}`)
    saving.value = 'saved'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
    saving.value = 'error'
  }
}

async function move(stage: IdeaStage) {
  if (!selected.value) return
  await saveNow()
  try {
    const updated = await transitionIdea(selected.value.id, {
      stage,
      validationConclusion: closure.conclusion || undefined,
      validationEvidence: closure.evidence || undefined,
      abandonmentReason: closure.reason || undefined,
    })
    ideas.value[ideas.value.findIndex((item) => item.id === updated.id)] = updated
    copyToDraft(updated)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '阶段推进失败'
  }
}

async function research() {
  if (!selected.value) return
  try {
    await startIdeaResearch(selected.value.id)
    await refreshSelected()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '调研启动失败'
  }
}
async function acceptResearch() {
  if (!selected.value) return
  const updated = await acceptIdeaResearch(selected.value.id)
  ideas.value[ideas.value.findIndex((item) => item.id === updated.id)] = updated
  copyToDraft(updated)
}
async function createProject() {
  if (!selected.value) return
  const project = await apiCreateProject({
    name: draft.title,
    goal: draft.targetOutcome || draft.problem,
    area: 'career',
    visAll: true,
  })
  projects.value.unshift(project)
  draft.projectId = project.id
  queueSave()
  await saveNow()
}
async function createTask() {
  if (!selected.value || !taskDraft.title.trim()) return
  await addIdeaTask(selected.value.id, { ...taskDraft, ownerId: draft.ownerId })
  taskDraft.title = ''
  taskDraft.description = ''
  await refreshSelected()
}
async function removeTask(taskId: string) {
  if (!selected.value) return
  await unlinkIdeaTask(selected.value.id, taskId)
  await refreshSelected()
}
async function createIteration() {
  if (!selected.value || !iterationDraft.taskId || !iterationDraft.objective.trim()) return
  await createIdeaIteration(selected.value.id, { ...iterationDraft })
  iterationDraft.objective = ''
  iterationDraft.acceptanceCriteria = ''
  await refreshSelected()
}
async function launchIteration() {
  if (!selected.value || !iterationDraft.taskId || !iterationDraft.objective.trim()) return
  try {
    const launched = await createAiRuns([iterationDraft.taskId])
    if (!launched.length) throw new Error('该任务已有活动 AI Run')
    await createIdeaIteration(selected.value.id, { ...iterationDraft, aiRunId: launched[0]!.id })
    iterationDraft.objective = ''
    iterationDraft.acceptanceCriteria = ''
    await refreshSelected()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'AI Run 启动失败'
  }
}
async function continueIteration(iteration: Idea['iterations'][number]) {
  if (!selected.value || !iteration.aiRunId) return
  try {
    const run = await continueAiRun(
      iteration.aiRunId,
      `继续优化本轮目标：${iteration.objective}\n验收标准：${iteration.acceptanceCriteria || '以任务验收要求为准'}`,
    )
    await createIdeaIteration(selected.value.id, {
      taskId: iteration.taskId,
      objective: `继续优化：${iteration.objective}`,
      acceptanceCriteria: iteration.acceptanceCriteria ?? undefined,
      aiRunId: run.id,
    })
    await refreshSelected()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '继续优化失败'
  }
}
async function setIteration(id: string, status: IdeaIterationStatus) {
  if (!selected.value) return
  await patchIdeaIteration(selected.value.id, id, { status })
  await refreshSelected()
}

watch(selectedId, (id) => {
  window.clearTimeout(saveTimer)
  const idea = ideas.value.find((item) => item.id === id)
  if (idea) copyToDraft(idea)
})
onMounted(async () => {
  try {
    await session.refreshDirectory()
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    loading.value = false
  }
  pollTimer = window.setInterval(() => refreshSelected(true).catch(() => undefined), 10_000)
})
onBeforeUnmount(() => {
  window.clearTimeout(saveTimer)
  window.clearInterval(pollTimer)
})
</script>

<template>
  <main class="ideas-page">
    <header class="ideas-top">
      <div class="title-line">
        <h1>点子库</h1>
        <span>{{ ideas.length }} 个点子 · {{ activeCount }} 个进行中</span>
      </div>
      <div class="spacer"></div>
      <div class="view-switch" aria-label="视图切换">
        <button :class="{ active: view === 'board' }" @click="view = 'board'">
          <i class="ri-layout-grid-line"></i>看板
        </button>
        <button :class="{ active: view === 'list' }" @click="view = 'list'">
          <i class="ri-list-check-2"></i>列表
        </button>
      </div>
      <button class="btn-primary" @click="showQuick = !showQuick">
        <i :class="showQuick ? 'ri-close-line' : 'ri-add-line'"></i
        >{{ showQuick ? '收起' : '记录点子' }}
      </button>
    </header>

    <form v-if="showQuick" class="quick quick-card" @submit.prevent="submitQuick">
      <div class="quick-title">
        <i class="ri-lightbulb-flash-line"></i>
        <div><strong>快速记录</strong><span>先收进来，稍后再把它想明白</span></div>
      </div>
      <input v-model="quick.title" required maxlength="160" placeholder="用一句话写下点子……" />
      <input v-model="quick.rawIdea" placeholder="补充原始想法（可选）" />
      <select v-model="quick.priority" aria-label="优先级">
        <option>P0</option>
        <option>P1</option>
        <option>P2</option>
      </select>
      <button class="btn-primary" :disabled="creating">
        {{ creating ? '记录中…' : '记下来' }}
      </button>
    </form>

    <section class="toolbar">
      <label class="search-box">
        <i class="ri-search-line"></i>
        <input v-model="search" type="search" placeholder="搜索标题、想法或问题…" />
      </label>
      <select v-model="filters.owner" aria-label="负责人">
        <option value="">全部负责人</option>
        <option v-for="member in session.members" :key="member.id" :value="member.id">
          {{ member.name }}
        </option>
      </select>
      <select v-model="filters.creator" aria-label="创建人">
        <option value="">全部创建人</option>
        <option v-for="member in session.members" :key="member.id" :value="member.id">
          {{ member.name }}
        </option>
      </select>
      <select v-model="filters.priority" aria-label="优先级">
        <option value="">全部优先级</option>
        <option>P0</option>
        <option>P1</option>
        <option>P2</option>
      </select>
      <select v-model="filters.project" aria-label="关联项目">
        <option value="">全部项目</option>
        <option v-for="project in projects" :key="project.id" :value="project.id">
          {{ project.name }}
        </option>
      </select>
    </section>

    <nav class="stage-chips" aria-label="按阶段筛选">
      <button
        v-for="chip in stageChips"
        :key="chip.id || 'all'"
        class="stage-chip"
        :class="{ active: filters.stage === chip.id }"
        @click="filters.stage = chip.id"
      >
        {{ chip.label }}<span>{{ chip.count }}</span>
      </button>
    </nav>

    <p v-if="error" class="error">{{ error }} <button @click="error = ''">关闭</button></p>
    <p v-if="loading" class="empty">加载中……</p>
    <section v-else class="workspace">
      <div class="idea-browser" :class="view">
        <template v-if="view === 'board'">
          <div v-for="group in stageGroups" :key="group.id" class="stage-group">
            <div class="group-head">
              <span class="group-dot"></span>{{ group.label }}
              <span class="group-count">{{ group.items.length }}</span>
            </div>
            <div class="idea-grid">
              <button
                v-for="idea in group.items"
                :key="idea.id"
                class="idea-card"
                :class="{ selected: idea.id === selectedId }"
                @click="selectedId = idea.id"
              >
                <div class="card-top">
                  <span class="idea-icon"><i class="ri-lightbulb-flash-line"></i></span>
                  <strong>{{ idea.title }}</strong>
                  <span class="priority" :class="idea.priority.toLowerCase()">{{
                    idea.priority
                  }}</span>
                </div>
                <p>{{ idea.rawIdea || idea.problem || '还没有补充想法说明' }}</p>
                <div class="card-meta">
                  <span><i class="ri-user-3-line"></i>{{ memberName(idea.ownerId) }}</span>
                  <span><i class="ri-stack-line"></i>{{ projectName(idea.projectId) }}</span>
                </div>
              </button>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="list-head">
            <span>点子</span><span>阶段</span><span>优先级</span><span>负责人</span
            ><span>关联项目</span>
          </div>
          <button
            v-for="idea in filtered"
            :key="idea.id"
            class="idea-row"
            :class="{ selected: idea.id === selectedId }"
            @click="selectedId = idea.id"
          >
            <strong>{{ idea.title }}</strong
            ><span>{{ stageLabel(idea.stage) }}</span
            ><span>{{ idea.priority }}</span
            ><span>{{ memberName(idea.ownerId) }}</span
            ><span>{{ projectName(idea.projectId) }}</span>
          </button>
        </template>
        <div v-if="!filtered.length" class="empty">
          <i class="ri-filter-3-line"></i>
          <div>没有符合条件的点子——试试清除搜索或切换阶段</div>
        </div>
      </div>

      <aside v-if="selected" class="detail">
        <div class="detail-head">
          <div>
            <span class="eyebrow">点子详情 · {{ selected.id }}</span>
            <div class="detail-title-line">
              <h2>{{ selected.title }}</h2>
              <span class="stage-badge">{{ stageLabel(selected.stage) }}</span>
              <span class="priority" :class="selected.priority.toLowerCase()">{{
                selected.priority
              }}</span>
            </div>
          </div>
          <span class="save" :class="saving" @click="saving === 'error' && saveNow()">{{
            saving === 'saving'
              ? '保存中…'
              : saving === 'saved'
                ? '已保存'
                : saving === 'error'
                  ? '保存失败，点此重试'
                  : dirty
                    ? '本地草稿'
                    : ''
          }}</span>
        </div>

        <nav class="stage-nav" aria-label="点子阶段">
          <button
            v-for="stage in stages"
            :key="stage.id"
            :class="{ current: stage.id === selected.stage }"
            @click="move(stage.id)"
          >
            {{ stage.label }}
          </button>
        </nav>

        <section class="panel form-grid basic-info">
          <div class="section-heading wide">
            <div>
              <h3>基本信息</h3>
              <p>随时补充，内容会自动保存为团队共享档案。</p>
            </div>
          </div>
          <label class="wide">标题<input v-model="draft.title" @input="queueSave" /></label>
          <label
            >优先级<select v-model="draft.priority" @change="queueSave">
              <option>P0</option>
              <option>P1</option>
              <option>P2</option>
            </select></label
          >
          <label
            >负责人<select v-model="draft.ownerId" @change="queueSave">
              <option v-for="member in session.members" :key="member.id" :value="member.id">
                {{ member.name }}
              </option>
            </select></label
          >
          <label class="wide"
            >原始想法<textarea v-model="draft.rawIdea" rows="3" @input="queueSave" />
          </label>
          <label class="wide"
            >真实问题<textarea v-model="draft.problem" rows="3" @input="queueSave" />
          </label>
          <label class="wide"
            >目标结果<textarea v-model="draft.targetOutcome" rows="3" @input="queueSave" />
          </label>
        </section>

        <section v-if="selected.stage === 'RESEARCHING'" class="panel">
          <h3>看明白 · AI 调研</h3>
          <p>调研只允许联网搜索，不读取项目仓库、Shell、凭据或个人对话。</p>
          <div class="actions">
            <button class="primary" @click="research">
              {{ selected.researchRuns.length ? '重新调研（新版本）' : '启动调研' }}</button
            ><button
              v-if="
                selected.artifacts.some((a) => a.kind === 'RESEARCH_REPORT') &&
                !selected.detail.researchAcceptedAt
              "
              @click="acceptResearch"
            >
              我已阅读报告
            </button>
          </div>
          <div v-for="run in selected.researchRuns" :key="run.id" class="run">
            <b>{{ run.status }}</b
            ><span>{{ run.error || run.createdAt }}</span>
          </div>
        </section>

        <section
          v-if="['THINKING', 'ALIGNING', 'MVP_BUILDING', 'VALIDATING'].includes(selected.stage)"
          class="panel form-grid"
        >
          <h3 class="wide">{{ stageLabel(selected.stage) }}</h3>
          <template v-if="selected.stage === 'THINKING'">
            <label class="wide"
              >目标用户<textarea v-model="draft.detail.targetUsers" @input="queueSave" />
            </label>
            <label class="wide"
              >核心价值<textarea v-model="draft.detail.coreValue" @input="queueSave" />
            </label>
            <label class="wide"
              >非目标<textarea v-model="draft.detail.nonGoals" @input="queueSave" />
            </label>
            <label class="wide"
              >可行性判断<textarea v-model="draft.detail.feasibility" @input="queueSave" />
            </label>
            <label class="wide"
              >MVP 假设<textarea v-model="draft.detail.mvpHypothesis" @input="queueSave" />
            </label>
            <label class="wide"
              >验收指标<textarea v-model="draft.detail.acceptanceCriteria" @input="queueSave" />
            </label>
          </template>
          <template v-if="selected.stage === 'ALIGNING'">
            <label
              >会议日期<input
                v-model="draft.detail.meetingDate"
                type="datetime-local"
                @input="queueSave"
            /></label>
            <label
              >时长（分钟）<input
                v-model.number="draft.detail.meetingDuration"
                type="number"
                min="1"
                @input="queueSave"
              /><small v-if="(draft.detail.meetingDuration ?? 0) > 20" class="warn"
                >超过 20 分钟，仍可保存</small
              ></label
            >
            <label class="wide"
              >参与人（逗号分隔）<input
                :value="draft.detail.meetingParticipants?.join(',')"
                @input="updateMeetingParticipants"
            /></label>
            <label class="wide"
              >对齐结论<textarea v-model="draft.detail.meetingConclusion" @input="queueSave" />
            </label>
            <label class="wide"
              >补充意见<textarea v-model="draft.detail.meetingNotes" @input="queueSave" />
            </label>
          </template>
          <template v-if="['MVP_BUILDING', 'VALIDATING'].includes(selected.stage)">
            <label class="wide"
              >关联项目<select v-model="draft.projectId" @change="queueSave">
                <option value="">请选择</option>
                <option v-for="project in projects" :key="project.id" :value="project.id">
                  {{ project.name }}
                </option>
              </select></label
            >
            <button v-if="!draft.projectId" class="wide" @click="createProject">
              用点子标题和目标创建项目
            </button>
          </template>
        </section>

        <section class="panel">
          <h3>知识资产</h3>
          <div v-for="artifact in selected.artifacts" :key="artifact.id" class="asset">
            <span>{{ artifact.title }}</span
            ><button @click="router.push(`/kb/kb-idea-assets?doc=${artifact.docId}`)">
              打开文档
            </button>
          </div>
          <p v-if="!selected.artifacts.length" class="muted">
            调研、会议、验证或放弃报告会保存在独立点子文件夹。
          </p>
        </section>

        <section class="panel">
          <h3>MVP 任务</h3>
          <div v-for="task in selected.tasks" :key="task.id" class="task">
            <button
              class="link"
              @click="router.push(`/projects/${selected.projectId}?task=${task.taskId}`)"
            >
              {{ task.title }}</button
            ><span>{{ task.status }}</span
            ><button @click="removeTask(task.taskId)">解除关联</button>
          </div>
          <div v-if="draft.projectId" class="inline-form">
            <input v-model="taskDraft.title" placeholder="新建 MVP 任务" /><input
              v-model="taskDraft.description"
              placeholder="任务说明"
            /><button @click="createTask">创建并关联</button>
          </div>
          <p v-else class="muted">先关联或创建项目，再建立 MVP 任务。</p>
        </section>

        <section class="panel">
          <h3>AI 迭代</h3>
          <div v-for="iteration in selected.iterations" :key="iteration.id" class="iteration">
            <div>
              <b>{{ iteration.objective }}</b>
              <p>{{ iteration.acceptanceCriteria }}</p>
              <p v-if="iteration.resultSummary">{{ iteration.resultSummary }}</p>
              <small>{{ iteration.status }} · {{ iteration.createdAt }}</small>
            </div>
            <div class="actions">
              <button
                v-if="
                  iteration.aiRunId &&
                  ['READY_FOR_REVIEW', 'ACCEPTED', 'REJECTED', 'FAILED'].includes(iteration.status)
                "
                @click="continueIteration(iteration)"
              >
                继续优化</button
              ><button @click="setIteration(iteration.id, 'ACCEPTED')">认可</button
              ><button @click="setIteration(iteration.id, 'REJECTED')">未认可</button>
            </div>
          </div>
          <div v-if="selected.tasks.length" class="inline-form">
            <select v-model="iterationDraft.taskId">
              <option value="">选择任务</option>
              <option v-for="task in selected.tasks" :key="task.taskId" :value="task.taskId">
                {{ task.title }}
              </option></select
            ><input v-model="iterationDraft.objective" placeholder="本轮目标" /><input
              v-model="iterationDraft.acceptanceCriteria"
              placeholder="验收标准"
            />
            <div class="actions">
              <button class="primary" @click="launchIteration">新建 AI Run</button
              ><button @click="createIteration">仅记录</button>
            </div>
          </div>
          <p class="muted">
            AI 完成后进入待人工评价；认可、未认可和继续优化都保留，点子不会被 AI 自动关闭。
          </p>
        </section>

        <section class="panel terminal">
          <h3>人工验证与关闭</h3>
          <textarea v-model="closure.conclusion" placeholder="验证结论（达成时必填）" />
          <textarea v-model="closure.evidence" placeholder="证据或链接（达成时必填）" />
          <textarea v-model="closure.reason" placeholder="放弃原因（启动后放弃必填）" />
          <div class="actions">
            <button v-if="nextStage" class="primary" @click="move(nextStage.id)">
              进入「{{ nextStage.label }}」</button
            ><button
              v-if="!['ACHIEVED', 'ABANDONED'].includes(selected.stage)"
              class="danger"
              @click="move('ABANDONED')"
            >
              放弃并沉淀报告
            </button>
          </div>
        </section>

        <section class="panel timeline">
          <h3>活动时间线</h3>
          <div
            v-for="activity in [...(selected.detail.activity ?? [])].reverse()"
            :key="`${activity.at}-${activity.action}`"
          >
            <i></i>
            <p>
              <b>{{ memberName(activity.by) }}</b> {{ activity.action
              }}<small>{{ activity.at }}</small>
            </p>
          </div>
        </section>
      </aside>
    </section>
  </main>
</template>

<style scoped>
/* 与「项目」管理页共用同一套视觉语言。以下规则覆盖早期原型样式。 */
.ideas-page {
  max-width: var(--wao-content-max);
  margin: 0 auto;
  padding: 16px 24px 40px;
  color: var(--wao-text);
  background: transparent;
  animation: waoFadeUp 0.25s ease;
}
button,
input,
select,
textarea {
  font: inherit;
}
input,
select,
textarea {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid var(--wao-line-control);
  padding: 9px 10px;
}
textarea {
  resize: vertical;
}
.detail-head,
.detail-title-line,
.actions,
.task,
.asset {
  display: flex;
  align-items: center;
}
.detail-head,
.task,
.asset {
  justify-content: space-between;
  gap: 12px;
}
.actions {
  gap: 8px;
  flex-wrap: wrap;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.wide {
  grid-column: 1 / -1;
}
.error {
  margin: 12px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff0f1;
  color: #a21f31;
}
.error button {
  float: right;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.muted {
  color: var(--wao-text-weak);
}
.ideas-top {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.title-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.title-line h1 {
  margin: 0;
  font-size: 20px;
  line-height: 1.4;
}
.title-line span {
  color: var(--wao-text-weak);
  font-size: 12px;
}
.spacer {
  flex: 1;
}
.btn-primary {
  min-height: 33px;
  padding: 0 14px;
  border: 0;
  border-radius: 8px;
  background: var(--wao-primary);
  color: #fff;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary:disabled {
  opacity: 0.65;
  cursor: wait;
}
.view-switch {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 8px;
  padding: 2px;
}
.view-switch button {
  border: 0;
  border-radius: 6px;
  background: transparent;
  min-height: 28px;
  padding: 0 9px;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
}
.view-switch .active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  box-shadow: none;
}
.quick-card {
  margin-top: 14px;
  padding: 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-pop);
  display: grid;
  grid-template-columns: minmax(170px, 1.4fr) minmax(220px, 2fr) minmax(260px, 3fr) 82px auto;
  gap: 10px;
  align-items: center;
}
.quick-title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.quick-title > i {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  display: grid;
  place-items: center;
  font-size: 18px;
}
.quick-title strong,
.quick-title span {
  display: block;
}
.quick-title strong {
  font-size: 13px;
}
.quick-title span {
  margin-top: 2px;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.quick-card input,
.quick-card select,
.toolbar input,
.toolbar select,
.detail input,
.detail select,
.detail textarea {
  border-color: var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.quick-card input:focus,
.quick-card select:focus,
.toolbar input:focus,
.toolbar select:focus,
.detail input:focus,
.detail select:focus,
.detail textarea:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.toolbar {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.toolbar select {
  width: auto;
  min-width: 118px;
  height: 32px;
  padding: 0 28px 0 9px;
  font-size: 12px;
  color: var(--wao-text-3);
}
.search-box {
  position: relative;
  flex: 1 1 230px;
  max-width: 360px;
}
.search-box i {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--wao-text-weak);
}
.search-box input {
  height: 32px;
  padding: 0 10px 0 32px;
}
.stage-chips {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.stage-chip {
  flex: 0 0 auto;
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-control);
  border-radius: 999px;
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.stage-chip span {
  opacity: 0.62;
  font-variant-numeric: tabular-nums;
}
.stage-chip.active {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: #c7d0f1;
  font-weight: 600;
}
.workspace {
  display: block;
  margin-top: 4px;
}
.idea-browser {
  max-height: none;
  overflow: visible;
}
.idea-browser.board {
  display: block;
}
.stage-group {
  margin-top: 14px;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px;
  color: var(--wao-text-3);
  font-size: 12.5px;
  font-weight: 600;
}
.group-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  background: var(--wao-primary);
}
.group-count {
  color: var(--wao-text-faint);
  font-weight: 400;
}
.idea-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  margin-top: 10px;
}
.idea-card {
  min-height: 146px;
  margin: 0;
  padding: 15px 16px 13px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    transform 0.15s ease;
}
.idea-card:hover {
  border-color: #c9cdda;
  box-shadow: 0 3px 12px rgba(16, 18, 28, 0.07);
  transform: translateY(-1px);
}
.idea-card.selected {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.1);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 9px;
}
.idea-icon {
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border-radius: 9px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  display: grid !important;
  place-items: center;
  font-size: 17px !important;
  margin: 0 !important;
}
.card-top strong {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.priority,
.stage-badge {
  flex: 0 0 auto;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 600;
  background: var(--wao-bg-hover-2);
  color: var(--wao-text-3);
}
.priority.p0 {
  background: #fff0f1;
  color: #b42636;
}
.priority.p1 {
  background: #fff6e5;
  color: #986000;
}
.priority.p2 {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
}
.idea-card > p {
  flex: 1;
  margin: 0;
  color: var(--wao-text-3);
  font-size: 12px;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  color: var(--wao-text-weak);
  font-size: 11.5px;
}
.card-meta span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0;
}
.idea-browser.list {
  margin-top: 14px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
  background: var(--wao-bg-card);
}
.list-head,
.idea-row {
  display: grid;
  grid-template-columns: minmax(220px, 2fr) repeat(4, minmax(90px, 1fr));
  gap: 12px;
  align-items: center;
}
.list-head {
  padding: 9px 14px;
  background: var(--wao-bg-hover-2);
  color: var(--wao-text-weak);
  font-size: 11.5px;
}
.idea-row {
  margin: 0;
  padding: 12px 14px;
  border: 0;
  border-top: 1px solid var(--wao-divider);
  border-radius: 0;
  background: transparent;
  color: var(--wao-text-3);
  font-size: 12px;
}
.idea-row:hover {
  background: var(--wao-bg-hover);
}
.idea-row strong {
  color: var(--wao-text);
  font-size: 13px;
}
.idea-row.selected {
  background: var(--wao-primary-soft);
  box-shadow: inset 3px 0 var(--wao-primary);
}
.detail {
  max-height: none;
  margin-top: 20px;
  padding: 20px;
  border-color: var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  overflow: visible;
}
.detail-head {
  align-items: flex-start;
}
.detail-title-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 3px;
  flex-wrap: wrap;
}
.detail-title-line h2 {
  font-size: 19px;
  line-height: 1.45;
}
.detail h2 {
  margin: 0;
  overflow-wrap: anywhere;
}
.eyebrow {
  color: var(--wao-text-faint);
}
.save {
  padding-top: 5px;
  color: var(--wao-text-weak);
}
.stage-nav {
  display: flex;
  overflow-x: auto;
  margin: 16px 0 0;
  padding: 4px;
  border-color: var(--wao-line-control);
  background: var(--wao-bg-hover-2);
  border-radius: 10px;
}
.stage-nav button {
  flex: 1 0 auto;
  border: 0;
  border-radius: 7px;
  padding: 7px 12px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.stage-nav .current {
  background: var(--wao-primary);
  color: #fff;
}
.panel {
  margin-top: 14px;
  padding: 16px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
}
.panel h3 {
  color: var(--wao-text);
}
.panel h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.section-heading h3 {
  margin-bottom: 3px;
}
.section-heading p,
.panel > p {
  margin: 0 0 12px;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.form-grid {
  gap: 12px;
}
label {
  color: var(--wao-text-3);
}
.panel button,
.actions button,
.inline-form button,
.asset button,
.task button {
  border-color: var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
}
.panel button:hover,
.actions button:hover {
  background: var(--wao-bg-hover);
}
.primary {
  background: var(--wao-primary) !important;
  border-color: var(--wao-primary) !important;
}
.run,
.iteration {
  margin-top: 8px;
  padding: 10px;
  border-radius: 8px;
  background: var(--wao-bg-hover-2);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.run span,
.iteration small,
.timeline small {
  display: block;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.iteration p {
  margin: 4px 0;
  color: var(--wao-text-3);
  overflow-wrap: anywhere;
}
.asset,
.task {
  margin-top: 8px;
  padding: 9px;
  border: 1px solid var(--wao-divider);
  border-radius: 8px;
}
.asset span {
  overflow-wrap: anywhere;
}
.link {
  border: 0 !important;
  color: var(--wao-primary-text) !important;
  text-align: left;
}
.inline-form {
  margin-top: 10px;
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
}
.terminal textarea {
  margin-bottom: 8px;
}
.danger {
  color: #bd3140 !important;
  border-color: #efb8bf !important;
}
.warn {
  display: block;
  margin-top: 4px;
  color: #b66a00;
}
.timeline > div {
  display: flex;
  gap: 10px;
}
.timeline i {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--wao-primary);
}
.timeline p {
  margin: 0 0 13px;
}
.empty {
  margin-top: 14px;
  padding: 44px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  font-size: 13px;
}
.empty i {
  font-size: 22px;
  color: var(--wao-text-faint-2);
}
.empty div {
  margin-top: 8px;
}

@media (max-width: 980px) {
  .quick-card {
    grid-template-columns: 1fr 1fr;
  }
  .quick-title {
    grid-column: 1 / -1;
  }
  .detail {
    order: initial;
  }
}
@media (max-width: 760px) {
  .ideas-page {
    padding: 16px 12px 28px;
  }
  .ideas-top .title-line {
    flex: 1 1 100%;
  }
  .ideas-top .spacer {
    display: none;
  }
  .view-switch {
    flex: 1;
  }
  .view-switch button {
    flex: 1;
    justify-content: center;
    min-height: 34px;
  }
  .btn-primary {
    min-height: 36px;
  }
  .quick-card {
    grid-template-columns: 1fr;
  }
  .quick-title {
    grid-column: auto;
  }
  .toolbar {
    gap: 7px;
  }
  .search-box {
    flex-basis: 100%;
    max-width: none;
  }
  .search-box input {
    height: 38px;
  }
  .toolbar select {
    flex: 1 1 calc(50% - 7px);
    min-height: 36px;
  }
  .idea-grid {
    grid-template-columns: 1fr;
  }
  .idea-browser.board {
    overflow: visible;
  }
  .detail {
    margin-top: 16px;
    padding: 13px;
  }
  .detail-head {
    align-items: flex-start;
  }
  .stage-nav {
    position: sticky;
    top: 0;
    z-index: 2;
  }
  .panel {
    padding: 13px;
  }
  .list-head {
    display: none;
  }
  .idea-browser.list {
    border: 0;
    background: transparent;
    overflow: visible;
  }
  .idea-row {
    margin-bottom: 8px;
    border: 1px solid var(--wao-line);
    border-radius: 10px;
    background: var(--wao-bg-card);
    grid-template-columns: 1fr auto;
  }
  .idea-row span:nth-of-type(n + 2) {
    display: none;
  }
  .form-grid,
  .inline-form {
    grid-template-columns: 1fr;
  }
}
</style>
