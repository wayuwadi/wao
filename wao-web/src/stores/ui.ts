import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 项目任务区的三种展示形态。
 *
 * <p>`group` 是按用户自定义分组看：一个项目里同时压着好几个 domain 的事时，
 * 状态看板帮不上忙，得按「这是哪一摊事」来分。
 */
export type TaskView = 'list' | 'board' | 'group'

const TASK_VIEW_KEY = 'wao-task-view-v1'
const CREATOR_FILTER_KEY = 'wao-proj-creator-filter-v1'

/**
 * 读取上一次用过的任务视图。
 *
 * 没有记录（首次打开、清过本地存储）时用看板：日常真正在看的是看板，
 * 每次都从列表开始只会让人多点一次。
 */
function loadTaskView(): TaskView {
  try {
    const raw = localStorage.getItem(TASK_VIEW_KEY)
    if (raw === 'list' || raw === 'board' || raw === 'group') return raw
  } catch {
    /* ignore */
  }
  return 'board'
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

  /** 任务视图偏好，跨页面、跨会话记忆。 */
  const taskView = ref<TaskView>(loadTaskView())

  const modal = ref<'task' | 'project' | 'note' | null>(null)
  const modalProject = ref<string>('') // 新建任务时预选的项目

  const searchOpen = ref(false)
  const searchAI = ref(false)

  const celebrating = ref<string | null>(null)

  /** 快捷键帮助浮层。 */
  const helpOpen = ref(false)

  function openTask(id: string) {
    openTaskId.value = id
  }
  function closeTask() {
    openTaskId.value = null
  }

  function openModal(kind: 'task' | 'project' | 'note', presetProject = '') {
    modal.value = kind
    modalProject.value = presetProject
  }
  function closeModal() {
    modal.value = null
  }

  /** ai=true 时进入知识库语义搜索态。 */
  function openSearch(ai = false) {
    searchAI.value = ai
    searchOpen.value = true
  }
  function closeSearch() {
    searchOpen.value = false
  }

  /** 触发全屏庆祝（mode 由偏好决定，'关闭' 时不触发）。 */
  function celebrate(mode: string = '细腻纸屑') {
    if (mode === '关闭') return
    celebrating.value = mode
  }
  function stopCelebrate() {
    celebrating.value = null
  }

  /** 记住这次选的视图，下次打开项目直接用它。 */
  function setTaskView(view: TaskView) {
    taskView.value = view
    try {
      localStorage.setItem(TASK_VIEW_KEY, view)
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

  return {
    openTaskId, modal, modalProject, searchOpen, searchAI, celebrating, helpOpen, taskView,
    openTask, closeTask, openModal, closeModal, openSearch, closeSearch, celebrate, stopCelebrate,
    setTaskView, creatorFilters, creatorFilterOf, setCreatorFilter, toggleHelp, closeHelp,
  }
})
