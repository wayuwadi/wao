import { ref } from 'vue'
import { defineStore } from 'pinia'

/** 项目任务区的两种展示形态。 */
export type TaskView = 'list' | 'board'

const TASK_VIEW_KEY = 'wao-task-view-v1'

/**
 * 读取上一次用过的任务视图。
 *
 * 没有记录（首次打开、清过本地存储）时用看板：日常真正在看的是看板，
 * 每次都从列表开始只会让人多点一次。
 */
function loadTaskView(): TaskView {
  try {
    const raw = localStorage.getItem(TASK_VIEW_KEY)
    if (raw === 'list' || raw === 'board') return raw
  } catch {
    /* ignore */
  }
  return 'board'
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

  function toggleHelp() {
    helpOpen.value = !helpOpen.value
  }
  function closeHelp() {
    helpOpen.value = false
  }

  return {
    openTaskId, modal, modalProject, searchOpen, searchAI, celebrating, helpOpen, taskView,
    openTask, closeTask, openModal, closeModal, openSearch, closeSearch, celebrate, stopCelebrate,
    setTaskView, toggleHelp, closeHelp,
  }
})
