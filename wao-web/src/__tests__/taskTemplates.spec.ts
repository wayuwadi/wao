import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import { createTaskFromTemplate, fetchTaskTemplates, saveTaskTemplate } from '@/api/taskTemplates'
import { taskTemplateSnapshot } from '@/utils/taskTemplate'
import TaskTemplateSaveDialog from '@/components/task/TaskTemplateSaveDialog.vue'
import TaskTemplatePicker from '@/components/task/TaskTemplatePicker.vue'
import type { TaskTemplate } from '@/api/taskTemplates'

vi.mock('@/api/taskTemplates',()=>({fetchTaskTemplates:vi.fn(),saveTaskTemplate:vi.fn(),deleteTaskTemplate:vi.fn(),createTaskFromTemplate:vi.fn()}))
enableAutoUnmount(afterEach)
beforeEach(()=>{
  vi.resetAllMocks();localStorage.clear();sessionStorage.clear();setActivePinia(createPinia())
  useSessionStore().accept({accountId:'account',member:{id:'me',name:'成员',initial:'成',role:'',color:''},identities:[],createdAt:''})
  const data=seedDataStore(),base=data.tasks[0]!
  data.taskLoadState='loaded'
  data.tasks=[{...base,id:'root',title:'父任务',desc:'父说明',status:'进行中',subs:[
    {id:'child',text:'子任务',desc:'子说明',done:true,due:'2026-12-01',owner:'other',comments:[{who:'other',time:'今天',text:'旧评论'}]},
    {id:'nested',parentId:'child',text:'孙任务',done:false},
  ],comments:[]}]
})
const options={global:{stubs:{Teleport:true}}}
describe('任务模板',()=>{
  it('快照只保留选中分支内容和层级，不带状态、日期、负责人及评论',()=>{
    const data=useDataStore()
    const content=taskTemplateSnapshot(data.tasks,'root','child')
    expect(content.title).toBe('子任务')
    expect(content.steps.map(step=>step.title)).toEqual(['孙任务'])
    expect(JSON.stringify(content)).not.toContain('旧评论')
    const root=taskTemplateSnapshot(data.tasks,'root')
    expect(root.steps).toHaveLength(2)
    expect(root.steps[1]!.parentKey).toBe(root.steps[0]!.key)
    expect(root.steps[0]).toMatchObject({title:'子任务',description:'子说明'})
    expect(JSON.stringify(root)).not.toContain('2026-12-01')
    expect(JSON.stringify(root)).not.toContain('旧评论')
  })
  it('模板保存失败后重试沿用同一操作编号和内容',async()=>{
    vi.mocked(saveTaskTemplate).mockRejectedValueOnce(new Error('离线')).mockImplementationOnce(async(id,content)=>({id,content}))
    const w=mount(TaskTemplateSaveDialog,{props:{taskId:'root'},...options})
    await w.get('[aria-label="模板名称"]').setValue('例行事项')
    await w.get('form').trigger('submit');await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('重试')
    expect(w.get('[aria-label="模板名称"]').attributes('disabled')).toBeDefined()
    await w.get('form').trigger('submit');await flushPromises()
    expect(saveTaskTemplate).toHaveBeenCalledTimes(2)
    expect(vi.mocked(saveTaskTemplate).mock.calls[0]).toEqual(vi.mocked(saveTaskTemplate).mock.calls[1])
    expect(w.emitted('close')).toBeTruthy()
  })
  it('模板草稿无法保存到本机时不发送请求，也不能误关丢失编号',async()=>{
    vi.mocked(saveTaskTemplate).mockImplementation(async(id,content)=>({id,content}))
    const w=mount(TaskTemplateSaveDialog,{props:{taskId:'root'},...options})
    const storage=vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('quota')})
    await w.get('form').trigger('submit');await flushPromises()
    expect(saveTaskTemplate).not.toHaveBeenCalled()
    expect(w.get('[role="alert"]').text()).toContain('尚未提交')
    expect(w.findAll('button').find(button=>button.text()==='关闭')!.attributes('disabled')).toBeDefined()
    storage.mockRestore()
    await w.get('form').trigger('submit');await flushPromises()
    expect(saveTaskTemplate).toHaveBeenCalledTimes(1)
  })
  it('两个窗口保存同一模板时不会相互清除重试记录',async()=>{
    const waiting=new Map<string,{resolve:(template:TaskTemplate)=>void;reject:(error:Error)=>void}>()
    vi.mocked(saveTaskTemplate).mockImplementation(id=>new Promise((resolve,reject)=>waiting.set(id,{resolve,reject})))
    const first=mount(TaskTemplateSaveDialog,{props:{taskId:'root'},...options})
    const second=mount(TaskTemplateSaveDialog,{props:{taskId:'root'},...options})
    await first.get('form').trigger('submit')
    await second.get('form').trigger('submit');await flushPromises()
    const calls=vi.mocked(saveTaskTemplate).mock.calls
    const [firstId,firstContent]=calls[0]!, [secondId]=calls[1]!
    expect(firstId).not.toBe(secondId)
    waiting.get(firstId)!.resolve({id:firstId,content:firstContent});await flushPromises()
    waiting.get(secondId)!.reject(new Error('回复丢失'));await flushPromises()
    const prefix='wao-task-template-save:account:me:root::'
    const keys=Object.keys(localStorage).filter(key=>key.startsWith(prefix))
    expect(keys).toEqual([`${prefix}${secondId}`])
    const reopened=mount(TaskTemplateSaveDialog,{props:{taskId:'root'},...options})
    vi.mocked(saveTaskTemplate).mockImplementationOnce(async(id,content)=>({id,content}))
    await reopened.get('form').trigger('submit');await flushPromises()
    expect(vi.mocked(saveTaskTemplate).mock.calls[2]?.[0]).toBe(secondId)
    expect(localStorage.getItem(keys[0]!)).toBeNull()
  })
  it('创建回复丢失后重开窗口，重试沿用编号且不覆盖本地后续修改',async()=>{
    const data=useDataStore(), base=data.tasks[0]!, content=taskTemplateSnapshot(data.tasks,'root')
    const template:TaskTemplate={id:'a'.repeat(32),content:{...content,name:'我的模板'}}
    vi.mocked(fetchTaskTemplates).mockResolvedValue([template])
    vi.mocked(createTaskFromTemplate).mockRejectedValueOnce(new Error('回复丢失'))
    let w=mount(TaskTemplatePicker,{props:{projectId:'p1',destination:'工作'},...options});await flushPromises()
    await w.get('[aria-label="使用模板 我的模板"]').trigger('click');await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('回复丢失')
    const first=vi.mocked(createTaskFromTemplate).mock.calls[0]!
    const id=first[0]
    data.tasks.push({...base,id,title:'后来修改的本地标题',subs:[]})
    vi.mocked(createTaskFromTemplate).mockResolvedValueOnce({...base,id,title:'服务器当前标题',subs:[]})
    w.unmount()
    w=mount(TaskTemplatePicker,{props:{projectId:'p1',destination:'工作'},...options});await flushPromises()
    expect(w.text()).toContain('有一项创建尚未确认')
    await w.findAll('button').find(button=>button.text()==='重试创建')!.trigger('click');await flushPromises()
    expect(vi.mocked(createTaskFromTemplate).mock.calls[1]).toEqual(first)
    expect(data.taskById(id)?.title).toBe('后来修改的本地标题')
    expect(w.emitted('close')).toBeTruthy()
  })
  it('两个窗口同时创建时各自保留回执，先完成的不会清除另一项',async()=>{
    const data=useDataStore(), base=data.tasks[0]!, content=taskTemplateSnapshot(data.tasks,'root')
    vi.mocked(fetchTaskTemplates).mockResolvedValue([{id:'a'.repeat(32),content:{...content,name:'我的模板'}}])
    const waiting = new Map<string, { resolve:(task:typeof base)=>void; reject:(error:Error)=>void }>()
    vi.mocked(createTaskFromTemplate).mockImplementation(id=>new Promise((resolve,reject)=>waiting.set(id,{resolve,reject})))
    const first=mount(TaskTemplatePicker,{props:{projectId:'p1',destination:'工作'},...options})
    const second=mount(TaskTemplatePicker,{props:{projectId:'p1',destination:'工作'},...options})
    await flushPromises()
    await first.get('[aria-label="使用模板 我的模板"]').trigger('click')
    await second.get('[aria-label="使用模板 我的模板"]').trigger('click');await flushPromises()
    const [firstId,secondId]=vi.mocked(createTaskFromTemplate).mock.calls.map(call=>call[0])
    expect(firstId).not.toBe(secondId)
    waiting.get(firstId!)!.resolve({...base,id:firstId!,subs:[]});await flushPromises()
    waiting.get(secondId!)!.reject(new Error('回复丢失'));await flushPromises()
    const keys=Object.keys(localStorage).filter(key=>key.startsWith('wao-task-template-create:account:me:'))
    expect(keys).toEqual([`wao-task-template-create:account:me:${secondId}`])
    const reopened=mount(TaskTemplatePicker,{props:{projectId:'p1',destination:'工作'},...options});await flushPromises()
    expect(reopened.text()).toContain('有一项创建尚未确认')
    vi.mocked(createTaskFromTemplate).mockResolvedValueOnce({...base,id:secondId!,subs:[]})
    await reopened.findAll('button').find(button=>button.text()==='重试创建')!.trigger('click');await flushPromises()
    const calls=vi.mocked(createTaskFromTemplate).mock.calls
    expect(calls[calls.length-1]?.[0]).toBe(secondId)
    expect(localStorage.getItem(keys[0]!)).toBeNull()
  })
  it('仍可恢复旧版单条创建草稿',async()=>{
    const base=useDataStore().tasks[0]!, id='b'.repeat(32), templateId='a'.repeat(32)
    localStorage.setItem('wao-task-template-create:account:me',JSON.stringify({id,projectId:'p1',templateId,title:'旧草稿'}))
    vi.mocked(fetchTaskTemplates).mockResolvedValue([])
    vi.mocked(createTaskFromTemplate).mockResolvedValue({...base,id,subs:[]})
    const wrapper=mount(TaskTemplatePicker,{props:{projectId:'p1',destination:'工作'},...options});await flushPromises()
    expect(wrapper.text()).toContain('旧草稿')
    await wrapper.findAll('button').find(button=>button.text()==='重试创建')!.trigger('click');await flushPromises()
    expect(createTaskFromTemplate).toHaveBeenCalledWith(id,'account',templateId,'p1')
    expect(localStorage.getItem('wao-task-template-create:account:me')).toBeNull()
  })
  it('创建草稿无法落盘时不调用新建任务接口',async()=>{
    const data=useDataStore(), content=taskTemplateSnapshot(data.tasks,'root')
    vi.mocked(fetchTaskTemplates).mockResolvedValue([{id:'a'.repeat(32),content:{...content,name:'我的模板'}}])
    const w=mount(TaskTemplatePicker,{props:{projectId:'p1',destination:'工作'},...options});await flushPromises()
    const storage=vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('quota')})
    await w.get('[aria-label="使用模板 我的模板"]').trigger('click');await flushPromises()
    expect(createTaskFromTemplate).not.toHaveBeenCalled()
    expect(w.get('[role="alert"]').text()).toContain('任务尚未创建')
    storage.mockRestore()
  })
})
