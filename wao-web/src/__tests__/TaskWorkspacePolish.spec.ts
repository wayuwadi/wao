import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { seedDataStore } from './helpers/seedDataStore'
import { useSessionStore } from '@/stores/session'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { projectWorkItems } from '@/utils/projectWorkItems'
import TaskBatchActions from '@/components/task/TaskBatchActions.vue'
import TaskQuickActionDialog from '@/components/task/TaskQuickActionDialog.vue'
import TodoView from '@/views/TodoView.vue'
import { clearToasts, useToastHost, runToastAction } from '@/composables/toast'
import { labelFeedback } from '@/composables/taskChangeFeedback'
import { connectTaskChanges } from '@/api/taskChanges'
vi.mock('vue-router',()=>({useRoute:()=>({query:{}}),useRouter:()=>({push:vi.fn()})}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[]),saveTaskLabels:vi.fn(async(v)=>({...v,revision:v.revision+1}))}))
vi.mock('@/api/favorites',async original=>({...await original<typeof import('@/api/favorites')>(),fetchPreference:vi.fn(async()=>''),savePreference:vi.fn(async()=>{})}))
enableAutoUnmount(afterEach)
beforeEach(()=>{
  vi.restoreAllMocks(); localStorage.clear(); sessionStorage.clear(); clearToasts(); setActivePinia(createPinia())
  useSessionStore().accept({accountId:'test-account',member:{id:'wg',name:'测试',initial:'测',role:'',color:''},identities:[],createdAt:''})
})
const stubs={SettingsDialogShell:{template:'<div><slot /></div>'},MarkdownEditor:true,TaskDocuments:true,TaskDueDate:true}
function setupTasks() {
  const data=seedDataStore(); data.apiLive=false;data.taskLoadState='loaded'
  const source=data.tasks[0]!
  data.tasks=[{...source,id:'a',title:'第一项',pr:'P3',status:'待办',owner:'wg',subs:[{id:'child',text:'独立子项',pr:'P0',done:false}]},{...source,id:'b',title:'第二项',status:'待办',owner:'wg',subs:[]}]
  vi.spyOn(data,'flushTask').mockResolvedValue(undefined)
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  return data
}
describe('任务工作台操作连续性',()=>{
  it('切换视图后恢复选中的任务；账号切换不会继承原位置',async()=>{
    setupTasks()
    const wrapper=mount(TodoView,{global:{stubs}});await flushPromises()
    await wrapper.findAll('.task-select').find(b=>b.text().includes('第二项'))!.trigger('click')
    await wrapper.get('[aria-label="搜索任务"]').setValue('第二项')
    wrapper.unmount()
    const reopened=mount(TodoView,{global:{stubs}});await flushPromises()
    expect((reopened.get('[aria-label="搜索任务"]').element as HTMLInputElement).value).toBe('第二项')
    expect((reopened.get('[aria-label="任务标题"]').element as HTMLInputElement).value).toBe('第二项')
    useSessionStore().accept({accountId:'other-account',member:{id:'other',name:'其他成员',initial:'他',role:'',color:''},identities:[],createdAt:''})
    await flushPromises()
    expect((reopened.get('[aria-label="搜索任务"]').element as HTMLInputElement).value).toBe('')
  })
  it('子任务可恢复继承，撤销只恢复优先级且不覆盖后续编辑',async()=>{
    const data=setupTasks()
    const w=mount(TaskQuickActionDialog,{props:{taskId:'a',subId:'child',mode:'priority'},global:{stubs}})
    await w.get('select').setValue('');await w.get('form').trigger('submit');await flushPromises()
    expect(data.subById('a','child')?.pr).toBeUndefined()
    const notice=useToastHost().items.slice(-1)[0]!
    await runToastAction(notice.id)
    expect(data.subById('a','child')?.pr).toBe('P0')
    await w.get('select').setValue('P2');await w.get('form').trigger('submit');await flushPromises()
    const next=useToastHost().items.slice(-1)[0]!
    data.updateSub('a','child',{pr:'P1'})
    await runToastAction(next.id)
    expect(data.subById('a','child')?.pr).toBe('P1')
    expect(next.text).toContain('后续修改')
  })
  it('标签撤销拒绝跨账号以及已被新版本替代的修改',async()=>{
    setupTasks();const labels=useTaskLabelsStore()
    const before={taskId:'a',subId:'',revision:0,tags:['原标签']},after=await labels.save({...before,tags:['新标签']})
    labelFeedback(before,after)
    await labels.save({...after,tags:['其他窗口修改']})
    await runToastAction(useToastHost().items.slice(-1)[0]!.id)
    expect(labels.get('a').tags).toEqual(['其他窗口修改'])
  })
  it('批量标签保留各自原标签，失败项不报成功且保留选择',async()=>{
    const data=setupTasks(),labels=useTaskLabelsStore();await labels.load()
    await labels.save({taskId:'a',subId:'',revision:0,tags:['保留']})
    const save=vi.spyOn(labels,'save');save.mockImplementationOnce(async value=>({...value,revision:2})).mockRejectedValueOnce(new Error('版本冲突'))
    const projection=projectWorkItems(data.tasks)
    const w=mount(TaskBatchActions,{props:{selectedIds:['a','b'],items:projection.items,itemRefs:projection.refs},global:{stubs}})
    await w.findAll('button').find(b=>b.text()==='设置标签')!.trigger('click')
    await w.get('[aria-label="批量标签"]').setValue('联调')
    await w.get('form').trigger('submit');await flushPromises()
    expect(save.mock.calls[0]?.[0].tags).toEqual(['保留','联调'])
    expect(w.emitted('update:selectedIds')?.slice(-1)[0]).toEqual([['b']])
    expect(w.get('[role=alert]').text()).toContain('第二项：版本冲突')
  })
  it('批量优先级精确定位选中的内嵌子项，不改未选中的主任务',async()=>{
    const data=setupTasks(),projection=projectWorkItems(data.tasks)
    const child=projection.items.find(t=>t.title==='独立子项')!
    const w=mount(TaskBatchActions,{props:{selectedIds:[child.id],items:projection.items,itemRefs:projection.refs},global:{stubs}})
    await w.findAll('button').find(b=>b.text()==='优先级')!.trigger('click')
    await w.get('select').setValue('P1');await w.get('form').trigger('submit');await flushPromises()
    expect(data.tasks[0]?.pr).toBe('P3');expect(data.subById('a','child')?.pr).toBe('P1')
  })
  it('实时事件合并刷新，重连补读，退出后释放连接',async()=>{
    vi.useFakeTimers()
    const listeners=new Map<string,()=>void>(),close=vi.fn(),refresh=vi.fn()
    vi.stubGlobal('EventSource',class {addEventListener(name:string,handler:()=>void){listeners.set(name,handler)} close=close})
    const stop=connectTaskChanges(refresh)
    listeners.get('ready')!();listeners.get('changed')!();listeners.get('changed')!()
    await vi.advanceTimersByTimeAsync(151);expect(refresh).toHaveBeenCalledTimes(1)
    listeners.get('changed')!();stop();await vi.advanceTimersByTimeAsync(200)
    expect(close).toHaveBeenCalledOnce();expect(refresh).toHaveBeenCalledTimes(1)
    vi.useRealTimers();vi.unstubAllGlobals()
  })
})
