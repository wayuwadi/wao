import { ref } from 'vue'
import { defineStore } from 'pinia'
import { usePreferenceStore } from '@/stores/preferences'

/**
 * 项目任务区的四种展示形态。
 *
 * <p>`group` 是按用户自定义分组看：一个项目里同时压着好几个 domain 的事时，
 * 状态看板帮不上忙，得按「这是哪一摊事」来分。
 */
export type TaskView = 'list' | 'board' | 'group' | 'quadrant'

export interface DocumentTaskSeed {
  draftKey: string
  title: string
  desc: string
  sourceTitle: string
  sourceUrl: string
  sourceDocId?: string
}

const TASK_VIEW_KEY = 'wao-task-view-v1'
const CREATOR_FILTER_KEY = 'wao-proj-creator-filter-v1'

/**
 * 读取上一次用过的任务视图。
 *
 * 没有记录（首次打开、清过本地存储）时用看板：日常真正在看的是看板，
 * 每次都从列表开始只会让人多点一次。
 */
function asTaskView(value: unknown): TaskView | null {
  return value === 'list' || value === 'board' || value === 'group' || value === 'quadrant' ? value : null
}

/**
 * 任务视图必须按项目记忆。旧版本把一个字符串写在同一个 key 下，导致在项目 A 切到
 * 分组后，项目 B 也被迫切到分组；读取时兼容该旧值，首次为任一项目选择视图后再写成映射。
 */
function loadTaskViews(): Record<string, TaskView> {
  try {
    const raw = localStorage.getItem(TASK_VIEW_KEY)
    const legacy = asTaskView(raw)
    if (legacy) return { __legacy__: legacy }
    const parsed: unknown = JSON.parse(raw || '{}')
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const views: Record<string, TaskView> = {}
      for (const [projectId, view] of Object.entries(parsed)) {
        const valid = asTaskView(view)
        if (valid) views[projectId] = valid
      }
      return views
    }
  } catch {
    /* ignore */
  }
  return {}
}

/** 读取各项目记住的「创建人筛选」，坏数据当没存过。 */
function loadCreatorFilters(): Record<string, string> {
  try {
    const raw = JSON.parse(localStorage.getItem(CREATOR_FILTER_KEY) || '{}')
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
      const out: Record<string, string> = {}
      for (const [k, v] of Object.entries(raw)) if (typeof v === 'string' && v) out[k] = v
      return out
    }
  } catch {
    /* ignore */
  }
  return {}
}

/** 全局 UI 覆盖层：任务抽屉、新建弹窗、全局搜索、庆祝动画等跨屏状态。 */
export const useUiStore = defineStore('ui', () => {
  const openTaskId = ref<string | null>(null)

  /** 任务视图偏好，按项目、跨会话记忆。 */
  const taskViews = ref<Record<string, TaskView>>(loadTaskViews())

  const modal = ref<'task' | 'project' | 'note' | null>(null)
  const modalProject = ref<string>('') // 新建任务时预选的项目
  const modalTaskSeed = ref<DocumentTaskSeed | null>(null)
  const modalDue = ref<string>('') // 新建任务时预设的截止日期（ISO，日历双击带入）
  const modalStartAt = ref<string>('') // 日/周时间轴框选后的开始时间
  const modalEndAt = ref<string>('') // 日/周时间轴框选后的结束时间

  const searchOpen = ref(false)

  const celebrating = ref<string | null>(null)

  /** 快捷键帮助浮层。 */
  const helpOpen = ref(false)

  /** AI 助手侧边栏。 */
  const assistantOpen = ref(false)
  /** 由页面动作带入助手的新会话草稿；助手读取后立即清空，避免重复覆盖用户输入。 */
  const assistantDraft = ref('')

  /** 共享聊天悬浮框；最小化只改变可见性，组件和未发送草稿都继续保留。 */
  const chatOpen = ref(false)
  /** 从历史链接或首页活动带入的复盘请求；序号保证重复打开同一份复盘也会响应。 */
  const chatReviewId = ref('')
  const chatReviewRequestVersion = ref(0)

  function openTask(id: string) {
    openTaskId.value = id
  }
  function closeTask() {
    openTaskId.value = null
  }

  function openModal(
    kind: 'task' | 'project' | 'note',
    presetProject = '',
    presetDue = '',
    presetStartAt = '',
    presetEndAt = '',
  ) {
    modalTaskSeed.value = null
    modal.value = kind
    modalProject.value = presetProject
    modalDue.value = presetDue
    modalStartAt.value = presetStartAt
    modalEndAt.value = presetEndAt
  }
  function closeModal() {
    modal.value = null
  }

  function openSearch() {
    searchOpen.value = true
  }
  function closeSearch() {
    searchOpen.value = false
  }

  /** 触发全屏庆祝（mode 由偏好决定，'关闭' 时不触发）。 */
  function celebrate(mode?: string) {
    mode ??= usePreferenceStore().celebration
    if (mode === '关闭') return
    celebrating.value = mode
  }
  function stopCelebrate() {
    celebrating.value = null
  }

  /** 某项目上次使用的视图；旧全局值只作为一次性的兼容回落。 */
  function taskViewOf(projectId: string): TaskView {
    return taskViews.value[projectId] ?? taskViews.value.__legacy__ ?? 'board'
  }

  /** 记住某项目这次选的视图，不影响其他项目。 */
  function setTaskView(projectId: string, view: TaskView) {
    const { __legacy__: _legacy, ...current } = taskViews.value
    taskViews.value = { ...current, [projectId]: view }
    try {
      localStorage.setItem(TASK_VIEW_KEY, JSON.stringify(taskViews.value))
    } catch {
      /* 隐私模式下写不了本地存储也不该影响切换 */
    }
  }

  /** 各项目任务列表的「创建人筛选」，跨会话记忆（BUG：此前刷新即丢）。 */
  const creatorFilters = ref<Record<string, string>>(loadCreatorFilters())

  function creatorFilterOf(projectId: string): string {
    return creatorFilters.value[projectId] ?? ''
  }
  function setCreatorFilter(projectId: string, creatorId: string) {
    if (creatorId) creatorFilters.value[projectId] = creatorId
    else delete creatorFilters.value[projectId]
    try {
      localStorage.setItem(CREATOR_FILTER_KEY, JSON.stringify(creatorFilters.value))
    } catch {
      /* 写不进本地存储时筛选仍在本次会话内生效 */
    }
  }

  function toggleHelp() {
    helpOpen.value = !helpOpen.value
  }
  function closeHelp() {
    helpOpen.value = false
  }

  function openAssistant(draft = '') {
    if (draft.trim()) assistantDraft.value = draft
    assistantOpen.value = true
  }
  function consumeAssistantDraft() {
    assistantDraft.value = ''
  }
  function closeAssistant() {
    assistantOpen.value = false
  }
  function toggleAssistant() {
    assistantOpen.value = !assistantOpen.value
  }

  function openChat(reviewId?: string) {
    chatOpen.value = true
    if (reviewId !== undefined) {
      chatReviewId.value = reviewId === '1' ? '' : reviewId
      chatReviewRequestVersion.value += 1
    }
  }
  function closeChat() {
    chatOpen.value = false
  }
  function toggleChat() {
    if (chatOpen.value) closeChat()
    else openChat()
  }

  return {
    openTaskId, modal, modalProject, modalTaskSeed, modalDue, modalStartAt, modalEndAt,
    searchOpen, celebrating, helpOpen, assistantOpen, assistantDraft,
    chatOpen, chatReviewId, chatReviewRequestVersion, taskViews,
    openTask, closeTask, openModal, closeModal, openSearch, closeSearch, celebrate, stopCelebrate,
    taskViewOf, setTaskView, creatorFilters, creatorFilterOf, setCreatorFilter, toggleHelp, closeHelp,
    openAssistant, closeAssistant, toggleAssistant, consumeAssistantDraft,
    openChat, closeChat, toggleChat,
  }
})
