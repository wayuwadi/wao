import { beforeEach,describe,expect,it,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import { createPinia,setActivePinia } from 'pinia'
import TodoView from '@/views/TodoView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import TaskLabelEditor from '@/components/task/TaskLabelEditor.vue'
import { saveTaskLabels } from '@/api/taskLabels'
vi.mock('vue-router',()=>({useRoute:()=>({query:{}}),useRouter:()=>({push:vi.fn()})}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[{taskId:'parent',subId:'child',revision:1,tags:['联调']}]),saveTaskLabels:vi.fn(async(v)=>({...v,revision:v.revision+1}))}))
vi.mock('@/api/favorites',async original=>({...await original<typeof import('@/api/favorites')>(),fetchPreference:vi.fn(async()=>''),savePreference:vi.fn(async()=>{})}))
const options={global:{stubs:{MarkdownEditor:true,TaskDocuments:true,TaskDueDate:true}}}
beforeEach(()=>{
 vi.clearAllMocks()
 localStorage.clear();sessionStorage.clear();setActivePinia(createPinia())
 useSessionStore().accept({accountId:'workspace-test',member:{id:'wg',name:'成员',initial:'测',role:'',color:''},identities:[],createdAt:''})
 const data=seedDataStore();data.taskLoadState='loaded';data.apiLive=false
 const source=data.tasks[0]!
 data.tasks=[{...source,id:'parent',title:'主任务',owner:'wg',status:'待办',subs:[{id:'child',text:'独立子任务',done:false,due:data.today}]},{...source,id:'other',title:'其他任务',owner:'wg',status:'待办',subs:[]}]
})
describe('task workspace',()=>{
 it('标签浮层可取消勾选，失败保留草稿并允许重试',async()=>{
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.findAll('.task-navigation>button').find(b=>b.text().includes('联调'))!.trigger('click');await flushPromises()
  const editor=wrapper.findComponent(TaskLabelEditor)
  await editor.get('button').trigger('click');await flushPromises()
  expect(editor.get('[role="dialog"]').attributes('aria-label')).toBe('编辑任务标签')
  await editor.get('.suggestions button').trigger('click')
  expect(editor.get('input').element).toHaveProperty('value','')
  await editor.get('input').setValue('会议')
  vi.mocked(saveTaskLabels).mockRejectedValueOnce(new Error('offline'))
  await editor.get('form').trigger('submit');await flushPromises()
  expect(editor.get('[role="alert"]').text()).toContain('草稿已保留')
  expect(editor.get('input').element).toHaveProperty('value','会议')
  await editor.get('form').trigger('submit');await flushPromises()
  expect(vi.mocked(saveTaskLabels).mock.calls.slice(-1)[0]?.[0]).toMatchObject({taskId:'parent',subId:'child',tags:['会议']})
  wrapper.unmount()
 })
 it('收集箱新增不强制安排今天，今天入口才带日期',async()=>{
  const data=useDataStore(), add=vi.spyOn(data,'addTask').mockResolvedValue({task:data.tasks[0]!,persisted:true})
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.get('[aria-label="添加任务"]').setValue('无日期任务');await wrapper.get('.quick-add').trigger('submit');await flushPromises()
  expect(add.mock.calls[0]?.[0]).toMatchObject({owner:'wg',project:'',due:''})
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='今天')!.trigger('click')
  await wrapper.get('[aria-label="添加任务"]').setValue('今日安排');await wrapper.get('.quick-add').trigger('submit');await flushPromises()
  expect(add.mock.calls[1]?.[0]).toMatchObject({due:data.today})
  wrapper.unmount()
 })
 it('个人汇总不混入他人任务，显式项目清单仍保留共享能力',async()=>{
  const data=useDataStore(), base=data.tasks[0]!, project=data.projects[0]!
  data.tasks=[
   {...base,id:'mine',title:'本人负责',owner:'wg',parts:[],createdBy:'other',project:project.id,subs:[]},
   {...base,id:'parent',title:'共享主任务',owner:'other',parts:[],createdBy:'other',project:project.id,subs:[{id:'child',text:'共享子任务',owner:'other',done:false,due:data.today}]},
   {...base,id:'closed',title:'共享已完成',owner:'other',parts:[],createdBy:'other',project:project.id,status:'已完成',subs:[]},
  ]
  const wrapper=mount(TodoView,options);await flushPromises()
  const nav=(name:string)=>wrapper.findAll('.task-navigation button').find(b=>b.text()===name)!
  const rows=()=>wrapper.findAll('.checklist-row').map(row=>row.text()).join('\n')
  expect(rows()).toContain('本人负责');expect(rows()).not.toContain('共享主任务');expect(rows()).not.toContain('共享子任务')
  expect(wrapper.get('.count').text()).toBe('1')
  expect(wrapper.findAll('.task-navigation button').some(b=>b.text()==='指派给我')).toBe(false)
  expect(wrapper.findAll('.task-navigation button').some(b=>b.text()==='联调')).toBe(false)
  await nav(project.name).trigger('click');await flushPromises()
  expect(rows()).toContain('共享主任务');expect(rows()).toContain('共享子任务')
  await wrapper.findAll('.layout-switch button').find(b=>b.text()==='四象限')!.trigger('click');await flushPromises()
  expect(wrapper.findAll('.quadrant-task').some(row=>row.text().includes('共享主任务'))).toBe(true)
  await wrapper.findAll('.layout-switch button').find(b=>b.text()==='清单')!.trigger('click')
  await nav('所有').trigger('click');await flushPromises()
  expect(rows()).toContain('本人负责');expect(rows()).not.toContain('共享主任务');expect(rows()).not.toContain('共享子任务')
  await nav('已完成').trigger('click');await flushPromises()
  expect(rows()).not.toContain('共享已完成');expect(rows()).not.toContain('共享主任务')
  // 授权任务源撤回后，清单及标签不保留旧的共享任务。
  data.tasks=data.tasks.filter(t=>t.id==='mine');await flushPromises()
  expect(rows()).not.toContain('共享子任务');expect(wrapper.findAll('.task-navigation button').some(b=>b.text()==='联调')).toBe(false)
  wrapper.unmount()
 })
 it('未指派子项随本人父项，显式他人子项不进入个人列表，切换成员重新收敛',async()=>{
  const data=useDataStore(),base=data.tasks[0]!
  data.tasks=[{...base,parts:['other'],subs:[{id:'child',text:'我的未指派子项',done:false},{id:'delegated',text:'交给他人的子项',owner:'other',done:false}]}]
  const wrapper=mount(TodoView,options);await flushPromises()
  const rows=()=>wrapper.findAll('.checklist-row').map(r=>r.text()).join('\n')
  expect(rows()).toContain('我的未指派子项');expect(rows()).not.toContain('交给他人的子项')
  useSessionStore().accept({accountId:'second-account',member:{id:'other',name:'另一成员',initial:'另',role:'',color:''},identities:[],createdAt:''})
  await flushPromises()
  expect(rows()).not.toContain('我的未指派子项');expect(rows()).toContain('交给他人的子项')
  expect(wrapper.get('.count').text()).toBe('1')
  wrapper.unmount()
 })
 it('filters child labels while retaining ancestry and selecting the matching child',async()=>{
  const wrapper=mount(TodoView,options);await flushPromises()
  const tag=wrapper.findAll('.task-navigation>button').find(b=>b.text().includes('联调'))!
  await tag.trigger('click');await flushPromises()
  expect(wrapper.findAll('.checklist-row')).toHaveLength(2)
  expect(wrapper.get('[aria-label="任务标题"]').element).toHaveProperty('value','独立子任务')
  expect(wrapper.get('.workspace-main').text()).not.toContain('其他任务')
  wrapper.unmount()
 })
 it('keeps daily review and completed navigation accessible',async()=>{
  const wrapper=mount(TodoView,options);await flushPromises()
  expect(wrapper.text()).toContain('每日回顾');expect(wrapper.text()).toContain('已完成')
  expect(wrapper.findAll('.checklist-row').every(row=>row.attributes('draggable')==='false')).toBe(true)
  wrapper.unmount()
 })
 it('retries an existing failed creation without inserting a second task',async()=>{
  const data=useDataStore(), existing=data.tasks[0]!
  localStorage.setItem('wao-todo-quick:workspace-test:wg',JSON.stringify({title:'未保存标题',id:existing.id}))
  const add=vi.spyOn(data,'addTask'),flush=vi.spyOn(data,'flushTask').mockResolvedValue(undefined)
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.get('.quick-add').trigger('submit');await flushPromises()
  expect(add).not.toHaveBeenCalled();expect(flush).toHaveBeenCalledWith(existing.id)
  wrapper.unmount()
 })
})
