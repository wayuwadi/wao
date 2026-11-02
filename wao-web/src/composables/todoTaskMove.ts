import { ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { projectWorkItems, subItemKey, type ProjectItemRefs } from '@/utils/projectWorkItems'
import type { Task } from '@/types'

/** Display keys never reach write APIs; resolve embedded subtasks before moving. */
export function useTodoTaskMove(projection: () => { items: Task[]; refs: ProjectItemRefs }, scope: () => string) {
  const data = useDataStore(), session = useSessionStore(), order = useTaskOrderStore()
  const busy = ref(false), error = ref('')
  async function move(sourceKey: string, targetKey: string | null, side: 'before' | 'inside' | 'after') {
    if (busy.value || data.movingTask || data.movingSubtasks || sourceKey === targetKey) return
    const view = projection(), source = view.refs[sourceKey], target = targetKey ? view.refs[targetKey] : undefined
    if (!source || (targetKey && !target)) return
    error.value = ''
    const member = session.userId, orderScope = scope()
    const checkMember = () => { if (session.userId !== member) throw new Error('成员已切换，请在原成员下重试') }
    const sourceTask = data.taskById(source.taskId), targetTask = target && data.taskById(target.taskId)
    if (targetTask && sourceTask?.project !== targetTask.project) { error.value = '请在同一清单内调整父子层级和顺序'; return }
    let cursor = targetKey
    const seen = new Set<string>()
    while (cursor && !seen.has(cursor)) {
      if (cursor === sourceKey) { error.value = '不能移动到自己的子任务中'; return }
      seen.add(cursor); cursor = view.refs[cursor]?.parentKey ?? null
    }
    busy.value = true
    try {
      await order.load(orderScope); checkMember()
      if (!order.state(orderScope).loaded) throw new Error('请先重试读取任务顺序')
      let movedKey = sourceKey, anchorKey = targetKey
      const sameParent = source.parentKey === target?.parentKey
      if (target && side !== 'inside' && sameParent) {
        // Personal ordering does not alter task content or hierarchy.
      } else if (!target) {
        if (source.subId) await data.promoteSubtask(source.taskId, source.subId)
        else if (sourceTask?.parentTaskId) await data.moveTask(source.taskId, null)
      } else if (source.subId && !target.subId && side !== 'inside') {
        const promoted = await data.promoteSubtask(source.taskId, source.subId); checkMember()
        movedKey = promoted.id
        await data.moveTask(promoted.id, targetTask?.parentTaskId ?? null)
      } else if (source.subId) {
        const parentSub = target.subId && side === 'inside' ? target.subId : target.subId ? data.subById(target.taskId, target.subId)?.parentId ?? null : null
        await data.moveSubtask(source.taskId, source.subId, target.taskId, parentSub)
        movedKey = subItemKey(target.taskId, source.subId)
      } else {
        let targetId = target.taskId
        if (target.subId) {
          if (data.subById(target.taskId, target.subId)?.parentId) throw new Error('请先将目标子事项移为顶层任务，再调整完整任务层级')
          targetId = (await data.promoteSubtask(target.taskId, target.subId)).id; checkMember()
          await data.moveTask(targetId, target.taskId); checkMember()
          anchorKey = targetId
        }
        await data.moveTask(source.taskId, side === 'inside' ? targetId : data.taskById(targetId)?.parentTaskId ?? null)
      }
      checkMember()
      if (anchorKey && side !== 'inside') {
        const ids = projectWorkItems(data.tasks).items.map(task => task.id)
        await order.move(orderScope, ids, movedKey, anchorKey, side === 'after')
      }
    } catch (e) { if (member === session.userId) error.value = e instanceof Error ? e.message : '移动失败，请重试' }
    finally { busy.value = false }
  }
  return { busy, error, move }
}
