import { onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import type { ProjectItemRefs } from '@/utils/projectWorkItems'
import { useProjectItemActions } from './projectItemActions'
import { labelFeedback } from './taskChangeFeedback'
import { toast } from './toast'

const TASK_DRAG_TYPE = 'application/x-wao-todo-task'

function dropTarget(target: EventTarget | null) {
  return target instanceof Element ? target.closest<HTMLElement>('[data-todo-drop]')?.dataset.todoDrop : undefined
}

export function useTodoSidebarDrop(refs: () => ProjectItemRefs) {
  const data = useDataStore(), session = useSessionStore(), labels = useTaskLabelsStore()
  const actions = useProjectItemActions(refs)
  const hovered = ref<string>()
  const pending = new Set<string>()
  let sourceId: string | undefined
  let generation = 0

  function clear() {
    sourceId = undefined
    hovered.value = undefined
  }

  function reset() { clear(); pending.clear(); generation++ }
  watch(() => labels.identity, reset, { flush: 'sync' })
  onBeforeUnmount(reset)

  function start(id: string, event: DragEvent) {
    clear()
    if (!event.dataTransfer || !refs()[id] || pending.has(actions.sourceId(id))) return
    sourceId = id
    event.dataTransfer.setData(TASK_DRAG_TYPE, id)
    event.dataTransfer.effectAllowed = 'copyMove'
  }

  function destination(event: DragEvent) {
    const id = sourceId, key = dropTarget(event.target)
    if (!id || !key || !event.dataTransfer?.types.includes(TASK_DRAG_TYPE)) return
    const source = refs()[id]
    if (!source || pending.has(source.taskId) || actions.busy(id)) return
    const task = data.taskById(source.taskId)
    if (!task || (source.subId && !data.subById(source.taskId, source.subId))) return
    if (key.startsWith('tag:')) {
      const tag = key.slice(4)
      const before = labels.get(source.taskId, source.subId)
      if (labels.ready && tag && !before.tags.includes(tag)) return { kind: 'tag' as const, key, tag, before, task }
      return
    }
    if (key !== 'inbox' && !key.startsWith('project:')) return
    const project = key === 'inbox' ? '' : key.slice(8)
    const name = key === 'inbox'
      ? (task.owner === session.userId ? '收集箱' : undefined)
      : data.projectById(project)?.name
    if (name && task.project !== project && data.taskSaveState(task.id) !== 'error' && actions.canMoveToList(id)) {
      return { kind: 'list' as const, key, project, name, task }
    }
  }

  function over(event: DragEvent) {
    const target = destination(event)
    hovered.value = target?.key
    if (!target || !event.dataTransfer) return
    event.preventDefault()
    event.dataTransfer.dropEffect = target.kind === 'tag' ? 'copy' : 'move'
  }

  function leave(event: DragEvent) {
    if (dropTarget(event.relatedTarget) !== hovered.value) hovered.value = undefined
  }

  async function drop(event: DragEvent) {
    const id = sourceId
    const target = event.dataTransfer?.getData(TASK_DRAG_TYPE) === id ? destination(event) : undefined
    clear()
    if (!target || !id) return
    event.preventDefault()
    const attempt = generation
    const { task } = target
    pending.add(task.id)
    try {
      if (target.kind === 'tag') {
        const { before, tag } = target
        const after = await labels.save({ ...before, tags: [...before.tags, tag] })
        if (attempt === generation) labelFeedback(before, after)
      } else {
        await actions.save(id, () => data.patchTask(task.id, { project: target.project, groupId: '' }))
        if (attempt !== generation) return
        if (data.taskSaveState(task.id) === 'error') throw new Error(data.taskSaveError(task.id) || '移动尚未保存，草稿已保留')
        toast(`已移动到「${target.name}」`, 'ri-check-line')
      }
    } catch (error) {
      if (attempt !== generation) return
      const response = (error as { response?: { status?: number; data?: { message?: string } } })?.response
      if (target.kind === 'tag' && response?.status === 409) await labels.load()
      if (attempt !== generation) return
      const detail = response?.data?.message || (error instanceof Error ? error.message : '')
      const action = target.kind === 'tag' ? undefined : { label: '查看任务', run: () => {
        if (attempt === generation) actions.open(id)
      } }
      toast(`${target.kind === 'tag' ? '添加标签' : '移动任务'}失败${detail ? '：' + detail : '，请重试'}`, 'ri-error-warning-line', { action })
    } finally {
      if (attempt === generation) pending.delete(task.id)
    }
  }

  return { hovered, clear, start, over, leave, drop }
}
