import { describe, expect, it } from 'vitest'
import type { Project, Task } from '@/types'
import { decodeTaskFilters, decodeTaskFolders, emptyTaskOrganizationFilter, matchesOrganizedTask, normalizeTaskOrganizationFilter, organizedProjectGroups } from '@/utils/taskOrganization'
const task = (patch: Partial<Task> = {}): Task => ({ id:'t1', title:'计划', project:'p1', status:'待办', pr:'P1', start:'2026-11-07', due:'2026-11-10', ...patch } as Task)

describe('组合筛选与个人清单组织', () => {
  it('清单、优先级、标签与日期取交集，执行安排或截止日期任一落入范围即可', () => {
    const filter = { ...emptyTaskOrganizationFilter(), projects:['p1'], priorities:['P1'], tags:['工作','沟通'], tagMode:'all' as const, from:'2026-11-06',to:'2026-11-08' }
    expect(matchesOrganizedTask(task(),['工作','沟通'],filter,'2026-11-06')).toBe(true)
    expect(matchesOrganizedTask(task(),['工作'],filter,'2026-11-06')).toBe(false)
    expect(matchesOrganizedTask(task({project:'private'}),['工作','沟通'],filter,'2026-11-06')).toBe(false)
    expect(matchesOrganizedTask(task({start:'2026-11-01'}),['工作','沟通'],filter,'2026-11-06')).toBe(false)
    expect(matchesOrganizedTask(task({start:'',due:'2026-11-08'}),['工作','沟通'],filter,'2026-11-06')).toBe(true)
  })
  it('逾期不包含已完成任务，无标签和无日期可组合；空过滤不改变已放弃范围', () => {
    expect(matchesOrganizedTask(task({due:'2026-11-01',status:'已完成'}),[],{...emptyTaskOrganizationFilter(),date:'overdue'},'2026-11-06')).toBe(false)
    const filter={...emptyTaskOrganizationFilter(),date:'undated' as const,tagMode:'none' as const}
    expect(matchesOrganizedTask(task({start:'',due:''}),[],filter,'2026-11-06')).toBe(true)
    expect(matchesOrganizedTask(task({start:'',due:''}),['工作'],filter,'2026-11-06')).toBe(false)
    expect(matchesOrganizedTask(task({status:'已放弃'}),[],emptyTaskOrganizationFilter(),'2026-11-06')).toBe(true)
    expect(matchesOrganizedTask(task({status:'已放弃'}),['工作'],{...emptyTaskOrganizationFilter(),priorities:['P1'],tags:['工作']},'2026-11-06')).toBe(true)
    expect(matchesOrganizedTask(task({status:'已放弃'}),['工作'],{...emptyTaskOrganizationFilter(),priorities:['P0']},'2026-11-06')).toBe(false)
  })
  it('恢复时过滤损坏字段、非法日期及重复预设，不把不可信 JSON 直接交给界面', () => {
    expect(normalizeTaskOrganizationFilter({projects:['p1','p1',42],from:'2026-02-31',priorities:['P1','P9'],date:'anything',status:'anything'})).toEqual({...emptyTaskOrganizationFilter(),projects:['p1'],priorities:['P1']})
    const rows=decodeTaskFilters(JSON.stringify([{id:'a',name:' 工作 ',filter:{date:'week'}},{id:'a',name:'重复'},{id:42,name:'损坏'}]))
    expect(rows).toHaveLength(1);expect(rows[0]).toMatchObject({name:'工作',filter:{date:'week'}})
    expect(decodeTaskFilters('broken')).toEqual([])
  })
  it('文件夹只投影当前授权清单，失效归属回到未分组且不修改业务项目', () => {
    const projects=[{id:'p1',name:'可见'},{id:'p2',name:'新清单'}] as Project[]
    const settings=decodeTaskFolders(JSON.stringify({folders:[{id:'work',name:'工作'}],assignments:{p1:'work',p2:'deleted',secret:'work'}}))
    const groups=organizedProjectGroups(projects,settings)
    expect(groups.map(group=>group.projects.map(p=>p.id))).toEqual([['p1'],['p2']])
    expect(projects).toEqual([{id:'p1',name:'可见'},{id:'p2',name:'新清单'}])
    expect(settings.assignments).toEqual({p1:'work',secret:'work'})
  })
})
