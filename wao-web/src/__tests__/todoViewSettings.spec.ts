import { describe, expect, it } from 'vitest'
import type { Task } from '@/types'
import { defaultTodoView, decodeTodoView, sortTodoTasks, todoTaskGroups, todoTimelinePlacement, todoViewPreferenceKey } from '@/utils/todoViewSettings'
const task = (id:string, fields:Partial<Task>={}) => ({ id, title:id, pr:'P2', due:'', project:'', status:'待办', ...fields }) as Task
const meta = { tags:(id:string) => id==='tagged' ? ['乙','甲'] : [], list:(t:Task) => t.project || '收集箱', priorityUnset:(id:string) => id==='unset', today:'2026-11-04' }
describe('todo presentation rules',()=>{
  it('按真实时刻排序，未知时间始终放最后，并支持反向与稳定相等顺序',()=>{
    const early=task('a',{createdAt:'2026-11-04T09:00:00+08:00'})
    const late=task('b',{createdAt:'2026-11-04T02:00:00Z'})
    const equal=task('c',{createdAt:'2026-11-04T10:00:00'})
    const missing=task('missing')
    expect(sortTodoTasks([late,missing,equal,early],'created','asc',meta).map(t=>t.id)).toEqual(['a','b','c','missing'])
    expect(sortTodoTasks([early,missing,late,equal],'created','desc',meta).map(t=>t.id)).toEqual(['b','c','a','missing'])
    expect(sortTodoTasks([early,late],'modified','desc',meta).map(t=>t.id)).toEqual(['b','a'])
    expect(sortTodoTasks([early,task('edit',{createdAt:'2026-01-01',updatedAt:'2026-11-04T12:00'})],'modified','desc',meta)[0]?.id).toBe('edit')
  })
  it('P0 到 P3 默认从高到低，未设优先级的子项独立排在末尾',()=>{
    const items=[task('low',{pr:'P3'}),task('unset'),task('high',{pr:'P0'})]
    expect(sortTodoTasks(items,'priority','desc',meta).map(t=>t.id)).toEqual(['high','low','unset'])
    expect(sortTodoTasks(items,'priority','asc',meta).map(t=>t.id)).toEqual(['unset','low','high'])
  })
  it('每个实际标签均可分组，无标签独立展示，日期组不会遗漏过去的执行日期',()=>{
    const tagged=task('tagged'), empty=task('empty')
    const groups=todoTaskGroups([tagged,empty],'tags',meta)
    expect(groups.map(g=>g.name)).toEqual(['甲','乙','无标签'])
    expect(groups.slice(0,2).every(g=>g.tasks[0]===tagged)).toBe(true)
    const past=task('past',{start:'2026-11-01'})
    expect(todoTaskGroups([past,task('overdue',{due:'2026-11-03'})],'time',meta).map(g=>g.id)).toEqual(['overdue','2026-11-01'])
  })
  it('时间线分别显示执行和截止，截断跨范围区间并排除午夜结束的下一天',()=>{
    const range=task('range',{startAt:'2026-11-03T09:00',endAt:'2026-11-06T00:00',due:'2026-11-07'})
    expect(todoTimelinePlacement(range,'2026-11-04','2026-11-17')).toEqual({execution:{from:1,to:3},deadline:4,undated:false})
    expect(todoTimelinePlacement(task('empty'),'2026-11-04','2026-11-17').undated).toBe(true)
    expect(todoTimelinePlacement(task('old',{due:'2026-10-03'}),'2026-11-04','2026-11-17')).toEqual({execution:null,deadline:null,undated:false})
  })
  it('偏好解码拒绝损坏、原型字段和旧视图，标签键不进入路径且保持稳定',()=>{
    expect(decodeTodoView('{oops')).toEqual(defaultTodoView())
    expect(decodeTodoView('{"layout":"table","sort":"toString","group":"__proto__"}')).toEqual(defaultTodoView())
    const key=todoViewPreferenceKey('tag:设计/计划'.repeat(10))
    expect(key.length).toBeLessThan(64)
    expect(key).not.toContain('/')
    expect(key).toBe(todoViewPreferenceKey('tag:设计/计划'.repeat(10)))
  })
})
