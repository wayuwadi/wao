import { useDataStore } from '@/stores/data'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { useSessionStore } from '@/stores/session'
import { toast } from './toast'
import { TASK_QUADRANTS } from '@/utils/taskQuadrants'
import type { Priority } from '@/types'
import type { TaskLabels } from '@/api/taskLabels'

export interface DeadlineChange { taskId: string; subId?: string; before: string | null | undefined; after: string }
/** Capture versions after the entire batch, including siblings sharing one source row. */
export function deadlineFeedback(changes: DeadlineChange[]) {
  const data=useDataStore(), session=useSessionStore()
  const identity=`${session.account?.accountId}:${session.userId}`
  const effective=changes.filter(change=>(change.before||'')!==change.after)
  if(!effective.length)return
  const versions=new Map(effective.map(change=>[change.taskId,data.taskById(change.taskId)?.editVersion]))
  toast(`已更新 ${effective.length} 项截止日期`, 'ri-check-line', {duration:8000,action:{label:'撤销',run:async()=>{
    if(identity!==`${session.account?.accountId}:${session.userId}`)throw new Error('账号已变化，无法撤销')
    // Validate the entire snapshot before changing anything. Never overwrite a later edit.
    for(const change of effective){
      const task=data.taskById(change.taskId), sub=change.subId?data.subById(change.taskId,change.subId):undefined
      if(!task || (change.subId&&!sub) || task.editVersion!==versions.get(change.taskId) || (change.subId?sub?.due||'':task.due||'')!==change.after || ['saving','error'].includes(data.taskSaveState(change.taskId)))throw new Error('任务已有后续修改，请在详情中调整日期')
    }
    for(const change of effective){
      if(change.subId)data.updateSub(change.taskId,change.subId,{due:change.before||''})
      else data.patchTask(change.taskId,{due:change.before||''})
    }
    for(const id of versions.keys()){
      if(identity!==`${session.account?.accountId}:${session.userId}`)throw new Error('账号已变化，停止撤销')
      await data.flushTask(id)
      if(identity!==`${session.account?.accountId}:${session.userId}`)throw new Error('账号已变化，停止撤销')
      if(data.taskSaveState(id)==='error')throw new Error('撤销尚未保存，草稿已保留，请在任务详情中重试')
    }
  }}})
}

export function priorityFeedback(taskId: string, subId: string | undefined, before: Priority | null | undefined, after: Priority | null | undefined) {
  if (before === after) return
  const data = useDataStore(), session = useSessionStore(), identity = session.account?.accountId
  const version = data.taskById(taskId)?.editVersion
  let undoStarted = false
  const label = after ? `${after} · ${TASK_QUADRANTS.find(q => q.priority === after)?.label}` : '跟随父任务'
  toast(`优先级已改为「${label}」，四象限已更新`, 'ri-check-line', { duration: 8000, action: { label: '撤销', run: async () => {
    const task = data.taskById(taskId), sub = subId ? data.subById(taskId, subId) : undefined
    if (session.account?.accountId !== identity || !task || (subId && !sub)) throw new Error('任务或账号已变化，无法撤销')
    if ((subId ? sub?.pr ?? undefined : task.pr) !== ((undoStarted ? before : after) ?? undefined) || task.editVersion !== version || (!undoStarted && ['saving','error'].includes(data.taskSaveState(taskId)))) throw new Error('任务已有后续修改，请在详情中调整')
    if (!undoStarted) {
      if (subId) data.updateSub(taskId, subId, { pr: before })
      else if (before) data.patchTask(taskId, { pr: before })
      undoStarted = true
    }
    await data.flushTask(taskId)
    if (data.taskSaveState(taskId) === 'error') throw new Error('撤销未保存，请在任务详情中重试')
  } } })
}

export function labelFeedback(before: TaskLabels, after: TaskLabels) {
  if (JSON.stringify(before.tags) === JSON.stringify(after.tags)) return
  const labels = useTaskLabelsStore(), identity = labels.identity
  toast('标签已保存，筛选结果已更新', 'ri-check-line', { duration: 8000, action: { label: '撤销', run: async () => {
    if (labels.identity !== identity) throw new Error('账号已变化，无法撤销')
    if (labels.get(after.taskId, after.subId).revision !== after.revision) throw new Error('标签已有后续修改，请重新编辑')
    await labels.save({ ...after, tags: [...before.tags] })
  } } })
}
