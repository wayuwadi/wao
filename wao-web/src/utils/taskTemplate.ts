import type { Task } from '@/types'
import type { TaskTemplateContent, TaskTemplateStep } from '@/api/taskTemplates'
import { projectWorkItems, subItemKey } from './projectWorkItems'

/** A private template is a content snapshot, not a reference to a changing task. */
export function taskTemplateSnapshot(tasks: readonly Task[], taskId:string, subId?:string):TaskTemplateContent {
  const projection=projectWorkItems(tasks)
  const id=subId ? subItemKey(taskId,subId) : taskId
  const selected=projection.items.find(task=>task.id===id)
  if(!selected)throw new Error('任务已不存在，请刷新后重试。')
  const children=new Map<string,Task[]>()
  for(const task of projection.items) {
    if(task.parentTaskId && task.project===selected.project) {
      const list=children.get(task.parentTaskId) ?? []
      list.push(task);children.set(task.parentTaskId,list)
    }
  }
  const steps:TaskTemplateStep[]=[], visited=new Set([id])
  function append(parentId:string,parentKey:string | null) {
    for(const child of children.get(parentId) ?? []) {
      if(visited.has(child.id))continue
      visited.add(child.id)
      if(steps.length>=500)throw new Error('模板最多包含 500 个子任务。')
      const key=`n${steps.length}`
      steps.push({key,parentKey,title:child.title,description:child.desc ?? ''})
      append(child.id,key)
    }
  }
  append(id,null)
  const result={name:selected.title,title:selected.title,description:selected.desc ?? '',steps}
  if(!result.title.trim() || result.title.length>200 || result.description.length>20000 || steps.some(step=>!step.title.trim() || step.title.length>200 || step.description.length>20000))throw new Error('任务或子任务的标题、描述不符合模板长度限制。')
  if(JSON.stringify(result).length>1_000_000)throw new Error('模板内容超过大小限制。')
  return result
}
