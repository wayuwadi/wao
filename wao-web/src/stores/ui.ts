import { ref } from 'vue'
import { defineStore } from 'pinia'

/** 全局 UI 覆盖层：任务抽屉、新建弹窗、全局搜索、庆祝动画等跨屏状态。 */
export const useUiStore = defineStore('ui', () => {
  const openTaskId = ref<string | null>(null)

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

  function toggleHelp() {
    helpOpen.value = !helpOpen.value
  }
  function closeHelp() {
    helpOpen.value = false
  }

  return {
    openTaskId, modal, modalProject, searchOpen, searchAI, celebrating, helpOpen,
    openTask, closeTask, openModal, closeModal, openSearch, closeSearch, celebrate, stopCelebrate,
    toggleHelp, closeHelp,
  }
})
