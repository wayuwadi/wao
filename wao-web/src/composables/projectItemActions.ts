import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { toast } from './toast'
import type { Priority, TaskStatus } from '@/types'
import type { ProjectItemRefs } from '@/utils/projectWorkItems'

/** 展示项的操作始终定位真实任务或内嵌子事项，共用原草稿、冲突和重试链路。 */
export function useProjectItemActions(refs: () => ProjectItemRefs | undefined) {
  const data = useDataStore(), ui = useUiStore()
  const source = (id: string) => refs()?.[id] ?? { taskId: id }
  const sourceId = (id: string) => source(id).taskId
  const isSub = (id: string) => !!source(id).subId
  const open = (id: string) => { const ref = source(id); ui.openTask(ref.taskId, ref.subId) }
  const busy = (id: string) => data.taskSaveState(sourceId(id)) === 'saving' || !!data.movingSubtasks || !!data.movingTask
  async function save(id: string, change?: () => void) {
    if (busy(id)) return
    const ref = source(id)
    if (!data.taskById(ref.taskId) || (ref.subId && !data.subById(ref.taskId, ref.subId))) return
    change?.()
    await data.flushTask(ref.taskId)
  }
  async function priority(id: string, pr: Priority) {
    const ref = source(id)
    await save(id, () => ref.subId ? data.updateSub(ref.taskId, ref.subId, { pr }) : data.patchTask(ref.taskId, { pr }))
  }
  async function status(id: string, status: TaskStatus) {
    const ref = source(id)
    if (ref.subId && !['待办', '进行中', '待验证', '已完成'].includes(status)) {
      toast('普通子事项支持未开始、进行中、待验证和已完成', 'ri-information-line'); return
    }
    await save(id, () => ref.subId
      ? data.setSubState(ref.taskId, ref.subId, status === '待办' ? '未开始' : status as '进行中' | '待验证' | '已完成')
      : data.patchTask(ref.taskId, { status }))
  }
  async function toggle(id: string) {
    const ref = source(id)
    const done = ref.subId ? data.subById(ref.taskId, ref.subId)?.done : data.taskById(ref.taskId)?.status === '已完成'
    await status(id, done ? '待办' : '已完成')
  }
  return { source, sourceId, isSub, open, busy, save, priority, status, toggle }
}
