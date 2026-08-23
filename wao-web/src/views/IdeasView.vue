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
    <header class="page-head">
      <div>
        <h1>点子库</h1>
        <p>{{ ideas.length }} 个点子 · {{ activeCount }} 个进行中</p>
      </div>
      <div class="view-switch">
        <button :class="{ active: view === 'board' }" @click="view = 'board'">看板</button
        ><button :class="{ active: view === 'list' }" @click="view = 'list'">列表</button>
      </div>
    </header>

    <form class="quick" @submit.prevent="submitQuick">
      <input v-model="quick.title" required maxlength="160" placeholder="先记下一句话……" />
      <input v-model="quick.rawIdea" placeholder="原始想法（可稍后补充）" />
      <select v-model="quick.priority">
        <option>P0</option>
        <option>P1</option>
        <option>P2</option>
      </select>
      <button class="primary" :disabled="creating">{{ creating ? '记录中' : '记下来' }}</button>
    </form>

    <section class="filters">
      <input v-model="search" type="search" placeholder="搜索标题、想法、问题" />
      <select v-model="filters.stage">
        <option value="">全部阶段</option>
        <option v-for="stage in stages" :key="stage.id" :value="stage.id">{{ stage.label }}</option>
      </select>
      <select v-model="filters.owner">
        <option value="">全部负责人</option>
        <option v-for="member in session.members" :key="member.id" :value="member.id">
          {{ member.name }}
        </option>
      </select>
      <select v-model="filters.creator">
        <option value="">全部创建人</option>
        <option v-for="member in session.members" :key="member.id" :value="member.id">
          {{ member.name }}
        </option>
      </select>
      <select v-model="filters.priority">
        <option value="">全部优先级</option>
        <option>P0</option>
        <option>P1</option>
        <option>P2</option>
      </select>
      <select v-model="filters.project">
        <option value="">全部项目</option>
        <option v-for="project in projects" :key="project.id" :value="project.id">
          {{ project.name }}
        </option>
      </select>
    </section>

    <p v-if="error" class="error">{{ error }} <button @click="error = ''">关闭</button></p>
    <p v-if="loading" class="empty">加载中……</p>
    <section v-else class="workspace">
      <div class="idea-browser" :class="view">
        <template v-if="view === 'board'">
          <div v-for="stage in stages" :key="stage.id" class="column">
            <h2>
              {{ stage.label }}
              <small>{{ filtered.filter((idea) => idea.stage === stage.id).length }}</small>
            </h2>
            <button
              v-for="idea in filtered.filter((item) => item.stage === stage.id)"
              :key="idea.id"
              class="idea-card"
              :class="{ selected: idea.id === selectedId }"
              @click="selectedId = idea.id"
            >
              <strong>{{ idea.title }}</strong
              ><span>{{ idea.priority }} · {{ memberName(idea.ownerId) }}</span
              ><small>{{ projectName(idea.projectId) }}</small>
            </button>
          </div>
        </template>
        <template v-else>
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
        <p v-if="!filtered.length" class="empty">没有符合筛选条件的点子</p>
      </div>

      <aside v-if="selected" class="detail">
        <div class="detail-head">
          <div>
            <span class="eyebrow">{{ selected.id }}</span>
            <h2>{{ selected.title }}</h2>
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

        <section class="form-grid">
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
.ideas-page {
  padding: 24px;
  color: var(--text-primary, #172033);
  min-height: 100%;
  background: #f7f8fb;
}
.page-head,
.detail-head,
.actions,
.task,
.asset {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.page-head h1,
.detail h2 {
  margin: 0;
}
.page-head p,
.muted {
  color: #778096;
  margin: 5px 0;
}
.view-switch,
.stage-nav {
  display: flex;
  background: #fff;
  border: 1px solid #e4e7ee;
  border-radius: 10px;
  padding: 3px;
}
.view-switch button,
.stage-nav button {
  border: 0;
  background: transparent;
  padding: 7px 12px;
  border-radius: 7px;
  white-space: nowrap;
}
.view-switch .active,
.stage-nav .current {
  background: #6558e8;
  color: #fff;
}
.quick,
.filters {
  display: grid;
  grid-template-columns: 2fr 3fr 80px 100px;
  gap: 10px;
  margin-top: 18px;
}
.filters {
  grid-template-columns: 2fr repeat(5, minmax(100px, 1fr));
  margin: 12px 0 18px;
}
.quick input,
.quick select,
.filters input,
.filters select,
input,
select,
textarea {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid #dfe3eb;
  border-radius: 8px;
  padding: 9px 10px;
  background: #fff;
  font: inherit;
}
.primary {
  background: #6558e8 !important;
  color: #fff;
  border-color: #6558e8 !important;
}
.danger {
  color: #bd3140 !important;
  border-color: #efb8bf !important;
}
.error {
  background: #fff0f1;
  color: #a21f31;
  padding: 10px;
  border-radius: 8px;
}
.error button {
  float: right;
}
.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(390px, 42%);
  gap: 16px;
  align-items: start;
}
.idea-browser {
  min-width: 0;
  max-height: calc(100vh - 240px);
  overflow: auto;
}
.idea-browser.board {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.column {
  min-width: 235px;
  width: 235px;
  background: #eef0f5;
  border-radius: 10px;
  padding: 10px;
}
.column h2 {
  font-size: 14px;
  margin: 3px 3px 10px;
}
.column h2 small {
  float: right;
  color: #8991a2;
}
.idea-card,
.idea-row {
  width: 100%;
  text-align: left;
  border: 1px solid #e2e5ec;
  background: white;
  border-radius: 9px;
  padding: 11px;
  margin-bottom: 8px;
  color: inherit;
}
.idea-card strong,
.idea-card span,
.idea-card small {
  display: block;
}
.idea-card strong,
.detail h2,
.asset span,
.iteration p {
  overflow-wrap: anywhere;
}
.idea-card span {
  font-size: 12px;
  color: #656f83;
  margin: 8px 0;
}
.idea-card small {
  color: #969dad;
}
.idea-card.selected,
.idea-row.selected {
  border-color: #6558e8;
  box-shadow: 0 0 0 2px #6558e822;
}
.idea-row {
  display: grid;
  grid-template-columns: 2fr repeat(4, 1fr);
  gap: 12px;
  align-items: center;
}
.detail {
  background: #fff;
  border: 1px solid #e1e4eb;
  border-radius: 12px;
  padding: 18px;
  max-height: calc(100vh - 180px);
  overflow: auto;
}
.eyebrow {
  font-size: 11px;
  color: #9299aa;
}
.save {
  font-size: 12px;
  color: #7c8494;
}
.save.error {
  color: #bd3140;
  cursor: pointer;
}
.stage-nav {
  overflow-x: auto;
  margin: 16px 0;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.wide {
  grid-column: 1/-1;
}
label {
  font-size: 12px;
  color: #687185;
}
label input,
label textarea,
label select {
  margin-top: 5px;
  color: #172033;
}
.panel {
  border-top: 1px solid #edf0f4;
  padding: 17px 0;
}
.panel h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.panel button,
.actions button,
.inline-form button,
.asset button,
.task button {
  border: 1px solid #dfe3eb;
  background: #fff;
  border-radius: 7px;
  padding: 7px 10px;
}
.run,
.iteration {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 10px;
  background: #f7f8fb;
  border-radius: 8px;
  margin-top: 8px;
}
.run span {
  font-size: 12px;
  color: #70798c;
}
.asset,
.task {
  border: 1px solid #edf0f4;
  border-radius: 8px;
  padding: 9px;
  margin-top: 8px;
}
.link {
  border: 0 !important;
  color: #5648d9;
  text-align: left;
}
.inline-form {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  margin-top: 10px;
}
.iteration {
  align-items: center;
}
.iteration p {
  margin: 4px 0;
  color: #657086;
}
.iteration small,
.timeline small {
  display: block;
  color: #9aa1af;
}
.terminal textarea {
  margin-bottom: 8px;
}
.warn {
  display: block;
  color: #b66a00;
  margin-top: 4px;
}
.timeline > div {
  display: flex;
  gap: 10px;
}
.timeline i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #6558e8;
  margin-top: 7px;
}
.timeline p {
  margin: 0 0 13px;
}
.empty {
  text-align: center;
  color: #9299a8;
  padding: 30px;
}
@media (max-width: 1000px) {
  .workspace {
    grid-template-columns: 1fr;
  }
  .detail {
    max-height: none;
  }
  .idea-browser {
    max-height: 440px;
  }
  .filters {
    grid-template-columns: 1fr 1fr 1fr;
  }
  .quick {
    grid-template-columns: 1fr 1fr 80px;
  }
  .quick button {
    grid-column: 1/-1;
  }
  .detail {
    order: -1;
  }
}
@media (max-width: 640px) {
  .ideas-page {
    padding: 12px;
  }
  .page-head {
    align-items: flex-start;
  }
  .quick,
  .filters,
  .form-grid,
  .inline-form {
    grid-template-columns: 1fr;
  }
  .wide {
    grid-column: auto;
  }
  .workspace {
    display: block;
  }
  .idea-browser {
    margin-top: 12px;
  }
  .idea-browser.board {
    overflow-x: auto;
  }
  .detail {
    max-height: none;
    padding: 13px;
  }
  .stage-nav {
    position: sticky;
    top: 0;
    z-index: 2;
  }
  .idea-row {
    grid-template-columns: 1fr auto;
  }
  .idea-row span:nth-of-type(n + 2) {
    display: none;
  }
}
</style>
