import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { seedDataStore } from './helpers/seedDataStore'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { projectWorkItems } from '@/utils/projectWorkItems'
import { clearToasts, useToastHost, runToastAction } from '@/composables/toast'
import ProjectChecklistView from '@/components/task/ProjectChecklistView.vue'
import TaskContextMenu from '@/components/task/TaskContextMenu.vue'
import type { Task } from '@/types'
import TaskBatchActions from '@/components/task/TaskBatchActions.vue'
import QuadrantQuickAdd from '@/components/task/QuadrantQuickAdd.vue'
vi.mock('vue-router',()=>({useRoute:()=>({query:{}}),useRouter:()=>({push:vi.fn()})}))
vi.mock('@/api/favorites',async original=>({...await original<object>(),fetchPreference:vi.fn(async()=>''),savePreference:vi.fn(async()=>{})}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[]),saveTaskLabels:vi.fn(async v=>({...v,revision:v.revision+1}))}))
const stubs={SettingsDialogShell:{template:'<div><slot /></div>'},MarkdownEditor:true,TaskDocuments:true,TaskTimeSettings:true}
enableAutoUnmount(afterEach)
beforeEach(()=>{
  vi.restoreAllMocks();localStorage.clear();sessionStorage.clear();clearToasts();setActivePinia(createPinia())
  useSessionStore().accept({accountId:'alignment',member:{id:'wg',name:'测试',initial:'测',role:'',color:''},identities:[],createdAt:''})
  const data=seedDataStore();data.apiLive=false;data.taskLoadState='loaded'
  const base=data.tasks[0]!
  data.tasks=[{...base,id:'a',title:'第一项',owner:'wg',status:'待办',due:'2026-11-06',subs:[{id:'child',text:'子项',done:false,due:'2026-11-07'}]},{...base,id:'b',title:'第二项',owner:'wg',status:'待办',due:'2026-11-08',subs:[]}]
  vi.spyOn(data,'flushTask').mockResolvedValue()
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
})
describe('任务操作完整流程',()=>{
  it('上下键选择任务，Enter进入标题，Escape回原任务行；输入框内不误触完成',async()=>{
    const data=useDataStore(), projection=projectWorkItems(data.tasks)
    const w=mount(ProjectChecklistView,{attachTo:document.body,props:{items:projection.items,contextItems:projection.items,itemRefs:projection.refs,showDone:false,collapsed:[],query:''},global:{stubs}})
    await flushPromises()
    await w.get('[data-task-id="a"]').trigger('keydown',{key:'ArrowDown'})
    await w.get('[data-task-id="sub:a:child"]').trigger('keydown',{key:'Enter'})
    const title=w.get('[aria-label="任务标题"]')
    expect(title.element).toHaveProperty('value','子项')
    expect(document.activeElement).toBe(title.element)
    await title.trigger('keydown',{key:'Enter',ctrlKey:true})
    expect(data.subById('a','child')?.done).toBe(false)
    await title.trigger('keydown',{key:'Escape'})
    expect(document.activeElement).toBe(w.get('[data-task-id="sub:a:child"]').element)
  })
  it('批量改期冻结确认范围，只修改选中的子项且可撤销',async()=>{
    const data=useDataStore(), projection=projectWorkItems(data.tasks)
    const w=mount(TaskBatchActions,{props:{selectedIds:['sub:a:child'],items:projection.items,itemRefs:projection.refs},global:{stubs}})
    await w.findAll('button').find(b=>b.text()==='修改日期')!.trigger('click')
    await w.setProps({selectedIds:['sub:a:child','b'],itemRefs:{...projection.refs,'sub:a:child':{taskId:'b'}}})
    await w.get('[aria-label="批量截止日期"]').setValue('2026-11-10')
    await w.get('form').trigger('submit');await flushPromises()
    expect(data.subById('a','child')?.due).toBe('2026-11-10')
    expect(data.taskById('a')?.due).toBe('2026-11-06')
    expect(data.taskById('b')?.due).toBe('2026-11-08')
    expect(w.emitted('update:selectedIds')?.[0]).toEqual([['b']])
    await runToastAction(useToastHost().items.slice(-1)[0]!.id)
    expect(data.subById('a','child')?.due).toBe('2026-11-07')
  })
  it('批量部分失败保留失败选择，撤销拒绝覆盖后续修改',async()=>{
    const data=useDataStore(), projection=projectWorkItems(data.tasks)
    vi.spyOn(data,'taskSaveState').mockImplementation(id=>id==='b'?'error':'saved')
    const w=mount(TaskBatchActions,{props:{selectedIds:['a','b'],items:projection.items,itemRefs:projection.refs},global:{stubs}})
    await w.findAll('button').find(b=>b.text()==='修改日期')!.trigger('click')
    await w.get('[aria-label="批量截止日期"]').setValue('2026-11-10')
    await w.get('form').trigger('submit');await flushPromises()
    expect(w.emitted('update:selectedIds')?.[0]).toEqual([['b']])
    expect(w.get('[role=alert]').text()).toContain('第二项')
    data.patchTask('a',{due:'2026-11-12'})
    await runToastAction(useToastHost().items.slice(-1)[0]!.id)
    expect(data.taskById('a')?.due).toBe('2026-11-12')
    expect(useToastHost().items.slice(-1)[0]?.text).toContain('后续修改')
  })
  it('批量保存中确认幂等，账号ABA后旧结果不会继续范围或解锁新保存',async()=>{
    const data=useDataStore(), session=useSessionStore(), projection=projectWorkItems(data.tasks)
    const original=structuredClone(JSON.parse(JSON.stringify(data.tasks))) as Task[]
    let first!:()=>void, second!:()=>void
    vi.mocked(data.flushTask).mockImplementationOnce(()=>new Promise<void>(resolve=>{first=resolve})).mockImplementationOnce(()=>new Promise<void>(resolve=>{second=resolve}))
    const patch=vi.spyOn(data,'patchTask')
    const w=mount(TaskBatchActions,{props:{selectedIds:['a','b'],items:projection.items,itemRefs:projection.refs},global:{stubs}})
    await w.findAll('button').find(b=>b.text()==='修改日期')!.trigger('click')
    await w.get('[aria-label="批量截止日期"]').setValue('2026-11-10')
    await w.get('form').trigger('submit');await w.get('form').trigger('submit');await flushPromises()
    expect(patch).toHaveBeenCalledTimes(1)
    session.account={...session.account!,accountId:'other'};session.account={...session.account!,accountId:'alignment'}
    data.tasks=original
    await w.setProps({selectedIds:['b']})
    await w.findAll('button').find(b=>b.text()==='修改日期')!.trigger('click')
    await w.get('[aria-label="批量截止日期"]').setValue('2026-11-20')
    const form=w.get('form')
    await form.trigger('submit');await flushPromises()
    first();await flushPromises()
    expect(w.get('[aria-label="批量截止日期"]').attributes('disabled')).toBeDefined()
    expect(w.emitted('update:selectedIds')).toBeUndefined()
    expect(useToastHost().items).toHaveLength(0)
    expect(patch).toHaveBeenCalledTimes(2)
    second();await flushPromises()
    await form.trigger('submit');await flushPromises()
    expect(data.taskById('b')?.due).toBe('2026-11-20')
    expect(patch).toHaveBeenCalledTimes(2)
    expect(w.emitted('update:selectedIds')).toHaveLength(1)
  })
  it('右键改期账号ABA旧结果不关闭新表单、不生成撤销，也不重复提交',async()=>{
    const data=useDataStore(), session=useSessionStore()
    const original=JSON.parse(JSON.stringify(data.tasks)) as Task[]
    let first!:()=>void, second!:()=>void
    vi.mocked(data.flushTask).mockImplementationOnce(()=>new Promise<void>(resolve=>{first=resolve})).mockImplementationOnce(()=>new Promise<void>(resolve=>{second=resolve}))
    const patch=vi.spyOn(data,'patchTask')
    const w=mount(TaskContextMenu,{global:{stubs}})
    const menu=w.vm as unknown as {edit:(task:Task,field:'date')=>void}
    menu.edit(data.taskById('a')!,'date');await flushPromises()
    await w.get('input[type=date]').setValue('2026-11-10')
    await w.get('form').trigger('submit');await w.get('form').trigger('submit');await flushPromises()
    session.account={...session.account!,accountId:'other'};session.account={...session.account!,accountId:'alignment'}
    data.tasks=original
    menu.edit(data.taskById('b')!,'date');await flushPromises()
    await w.get('input[type=date]').setValue('2026-11-20')
    await w.get('form').trigger('submit');await flushPromises()
    first();await flushPromises()
    expect(w.get('input[type=date]').attributes('disabled')).toBeDefined()
    expect(useToastHost().items).toHaveLength(0)
    expect(patch).toHaveBeenCalledTimes(2)
    second();await flushPromises()
    expect(w.find('form').exists()).toBe(false)
    expect(useToastHost().items).toHaveLength(1)
    expect(data.taskById('b')?.due).toBe('2026-11-20')
  })
  it('复制任务的旧异步回执在账号ABA后不弹通知、不打开旧副本',async()=>{
    const data=useDataStore(), session=useSessionStore(), ui=useUiStore()
    let finish!:(id:string)=>void
    vi.spyOn(data,'taskMenuOperation').mockImplementationOnce(()=>new Promise<string>(resolve=>{finish=resolve}))
    const w=mount(TaskContextMenu,{global:{stubs:{...stubs,Teleport:true}}})
    await w.vm.openAt(new MouseEvent('contextmenu'),data.taskById('b')!)
    await w.get('[aria-label="创建副本"]').trigger('click');await flushPromises()
    session.account={...session.account!,accountId:'other'};session.account={...session.account!,accountId:'alignment'}
    finish('old-copy');await flushPromises()
    expect(w.emitted('created')).toBeUndefined()
    expect(w.emitted('changed')).toBeUndefined()
    expect(ui.openTaskId).toBeNull()
    expect(useToastHost().items).toHaveLength(0)
    expect(data.flushTask).not.toHaveBeenCalled()
  })
  it('已保存任务的标签失败重试不覆盖其他入口的新编辑',async()=>{
    const data=useDataStore(), labels=useTaskLabelsStore();await labels.load()
    vi.spyOn(labels,'save').mockRejectedValueOnce(new Error('标签保存失败')).mockImplementationOnce(async v=>({...v,revision:v.revision+1}))
    const add=vi.spyOn(data,'addTask').mockImplementation(async(payload,id)=>{const task={...data.tasks[0]!,...payload,id:id!};data.tasks.push(task);return {task,persisted:true}})
    const w=mount(QuadrantQuickAdd,{props:{priority:'P1',projectId:'',label:'重要'}})
    await w.get('input').setValue('新任务')
    await w.get('input[placeholder="用逗号分隔"]').setValue('联调')
    await w.get('form').trigger('submit');await flushPromises()
    const id=add.mock.calls[0]![1]!
    data.patchTask(id,{title:'另一窗口的新标题',desc:'不能覆盖的正文'})
    await w.get('form').trigger('submit');await flushPromises()
    expect(add).toHaveBeenCalledTimes(1)
    expect(data.taskById(id)).toMatchObject({title:'另一窗口的新标题',desc:'不能覆盖的正文'})
  })
})
