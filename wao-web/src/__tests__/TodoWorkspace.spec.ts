import {computed,defineComponent,h,ref} from 'vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import {projectWorkItems} from '@/utils/projectWorkItems'
import {useTaskLabelsStore} from '@/stores/taskLabels'
import { beforeEach,describe,expect,it,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import { createPinia,setActivePinia } from 'pinia'
import TodoView from '@/views/TodoView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import TaskLabelEditor from '@/components/task/TaskLabelEditor.vue'
import { saveTaskLabels } from '@/api/taskLabels'
const testRoute = vi.hoisted(() => ({ query: {} as Record<string,string> }))
vi.mock('vue-router',()=>({useRoute:()=>testRoute,useRouter:()=>({push:vi.fn(),replace:vi.fn()})}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[{taskId:'parent',subId:'child',revision:1,tags:['联调']}]),saveTaskLabels:vi.fn(async(v)=>({...v,revision:v.revision+1}))}))
vi.mock('@/api/favorites',async original=>({...await original<typeof import('@/api/favorites')>(),fetchPreference:vi.fn(async()=>''),savePreference:vi.fn(async()=>{})}))
const options={global:{stubs:{Teleport:true,MarkdownEditor:true,TaskDocuments:true,TaskDueDate:true}}}
beforeEach(()=>{
 vi.restoreAllMocks();vi.clearAllMocks()
 testRoute.query = {}
 localStorage.clear();sessionStorage.clear();setActivePinia(createPinia())
 useSessionStore().accept({accountId:'workspace-test',member:{id:'wg',name:'成员',initial:'测',role:'',color:''},identities:[],createdAt:''})
 const data=seedDataStore();data.taskLoadState='loaded';data.apiLive=false
 const source=data.tasks[0]!
 data.tasks=[{...source,id:'parent',title:'主任务',owner:'wg',status:'待办',subs:[{id:'child',text:'独立子任务',done:false,due:data.today}]},{...source,id:'other',title:'其他任务',owner:'wg',status:'待办',subs:[]}]
})
describe('task workspace',()=>{
 it('待办导航收起后保留当前清单并可展开，重新挂载恢复收起状态',async()=>{
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.findAll('.list-entry>button').find(b=>b.text().includes(useDataStore().projects[0]!.name))!.trigger('click')
  const heading=wrapper.get('h1').text()
  await wrapper.get('[aria-label="收起待办导航"]').trigger('click')
  expect(wrapper.get('.task-navigation').isVisible()).toBe(false)
  expect(wrapper.get('h1').text()).toBe(heading)
  wrapper.unmount()
  const restored=mount(TodoView,options);await flushPromises()
  expect(restored.get('.task-navigation').isVisible()).toBe(false)
  await restored.get('[aria-label="展开待办导航"]').trigger('click')
  expect(restored.get('.task-navigation').attributes('style') ?? '').not.toContain('display: none')
  expect(restored.get('[aria-label="收起待办导航"]').attributes('aria-expanded')).toBe('true')
  restored.unmount()
 })
 it('旧表格参数回落到清单，移除表格、批量和新建任务入口',async()=>{
  const project=useDataStore().projects[0]!
  testRoute.query={list:project.id,layout:'table'}
  const wrapper=mount(TodoView,options);await flushPromises()
  expect(wrapper.get('h1').text()).toBe(project.name)
  expect(wrapper.find('.checklist-layout').exists()).toBe(true)
  expect(wrapper.findAll('.layout-switch button').map(b=>b.text())).toEqual(['清单','四象限'])
  expect(wrapper.find('[aria-label="添加任务"]').exists()).toBe(true)
  expect(wrapper.get('.checklist-row').attributes('draggable')).toBe('true')
  wrapper.unmount()
 })
 it('清单拖放事件连接排序和层级保存，刷新组件后保留个人顺序',async()=>{
  const data=useDataStore(), relation=vi.spyOn(data,'moveTask').mockResolvedValue()
  const wrapper=mount(TodoView,options);await flushPromises()
  const target=wrapper.get('[data-task-id="parent"]')
  vi.spyOn(target.element,'getBoundingClientRect').mockReturnValue({top:100,height:40} as DOMRect)
  await wrapper.get('[data-task-id="other"]').trigger('dragstart',{dataTransfer:{setData:vi.fn(),effectAllowed:''}})
  await target.trigger('drop',{clientY:102});await flushPromises()
  expect(wrapper.findAll('.checklist-row')[0]!.attributes('data-task-id')).toBe('other')
  expect(relation).not.toHaveBeenCalled()
  await wrapper.get('[data-task-id="other"]').trigger('dragstart',{dataTransfer:{setData:vi.fn(),effectAllowed:''}})
  await target.trigger('drop',{clientY:120});await flushPromises()
  expect(relation).toHaveBeenCalledWith('other','parent')
  wrapper.unmount()
 })
 it('清单创建失败保留名称，成功选择新清单并支持改名',async()=>{
  const data=useDataStore()
  const create=vi.spyOn(data,'addProject').mockRejectedValueOnce(new Error('offline'))
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.get('[aria-label="新建清单"]').trigger('click')
  await wrapper.get('[aria-label="清单名称"]').setValue('工作清单验收')
  await wrapper.get('#task-list-form').trigger('submit');await flushPromises()
  expect(wrapper.text()).toContain('保存失败')
  expect(wrapper.get('[aria-label="清单名称"]').element).toHaveProperty('value','工作清单验收')
  create.mockRestore()
  await wrapper.get('#task-list-form').trigger('submit');await flushPromises()
  const project=data.projects.find(p=>p.name==='工作清单验收')!
  expect(project).toBeTruthy()
  expect(wrapper.get('h1').text()).toBe('工作清单验收')
  await wrapper.get('[aria-label="清单操作 工作清单验收"]').trigger('click');await flushPromises()
  await wrapper.findAll('[role="menuitem"]').find(b=>b.text()==='重命名清单')!.trigger('click')
  await wrapper.get('[aria-label="清单名称"]').setValue('改名后的清单')
  await wrapper.get('#task-list-form').trigger('submit');await flushPromises()
  expect(data.projectById(project.id)?.name).toBe('改名后的清单')
  wrapper.unmount()
 })
 it('搜索包含已完成主任务及子项，清空恢复默认且不再提供保存筛选',async()=>{
  const data=useDataStore()
  data.tasks[1]!.status='已完成';data.tasks[1]!.title='历史搜索主任务'
  data.tasks[0]!.subs.push({id:'done-search',text:'历史搜索子任务',done:true})
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  const wrapper=mount(TodoView,options);await flushPromises()
  const rows=()=>wrapper.findAll('.checklist-row').map(r=>r.text()).join(' ')
  expect(rows()).not.toContain('历史搜索')
  expect(wrapper.text()).not.toContain('保存筛选')
  expect(wrapper.get('.task-navigation').text()).not.toContain('筛选器')
  await wrapper.get('[aria-label="搜索任务"]').setValue('历史搜索')
  expect(rows()).toContain('历史搜索主任务');expect(rows()).toContain('历史搜索子任务')
  await wrapper.get('[aria-label="搜索任务"]').setValue('')
  expect(rows()).not.toContain('历史搜索')
  wrapper.unmount()
 })
 it('项目与待办共用更新：子项优先级、标签筛选、新增与完成即时响应',async()=>{
  const data=useDataStore(),labels=useTaskLabelsStore()
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  data.tasks[0]!.pr='P3'
  const project=mount(defineComponent({setup(){const p=computed(()=>projectWorkItems(data.tasks));return()=>h(TaskQuadrantView,{tasks:p.value.items,contextTasks:p.value.items,itemRefs:p.value.refs,selectedIds:[],showDone:false,projectId:'sync-project'})}}),options)
  const todo=mount(TodoView,options);await flushPromises()
  await todo.findAll('.layout-switch button').find(b=>b.text()==='四象限')!.trigger('click');await flushPromises()
  const section=(w:any,n:number)=>w.findAll('.quadrant')[n].text()
  expect(section(project,3)).toContain('独立子任务');expect(section(todo,3)).toContain('独立子任务')
  data.updateSub('parent','child',{pr:'P0'});await flushPromises()
  expect(section(project,0)).not.toContain('独立子任务');expect(section(todo,0)).not.toContain('独立子任务')
  expect(section(project,3)).toContain('独立子任务');expect(section(todo,3)).toContain('独立子任务')
  data.patchTask('parent',{pr:'P1'});await flushPromises()
  expect(section(project,1)).toContain('独立子任务');expect(section(todo,1)).toContain('独立子任务')
  expect(data.subById('parent','child')?.pr).toBe('P0')
  await todo.findAll('.task-navigation button').find(b=>b.text()==='联调')!.trigger('click');await flushPromises()
  expect(todo.findAll('.quadrant-task').some((r:any)=>r.text().includes('独立子任务'))).toBe(true)
  await labels.save({taskId:'parent',subId:'child',revision:1,tags:['已交接']});await flushPromises()
  expect(todo.findAll('.quadrant-task')).toHaveLength(0)
  await todo.findAll('.task-navigation button').find(b=>b.text()==='所有')!.trigger('click')
  data.addSub('parent','新增同步子项');await flushPromises()
  expect(project.text()).toContain('新增同步子项');expect(todo.text()).toContain('新增同步子项')
  data.setSubState('parent','child','已完成');await flushPromises()
  expect(project.text()).not.toContain('独立子任务');expect(todo.findAll('.quadrant-task').some((r:any)=>r.text().includes('独立子任务'))).toBe(false)
  project.unmount();todo.unmount()
 })
 it('完成保存失败时保留待办及重试线索，成功后才移出',async()=>{
  vi.useFakeTimers({toFake:['setTimeout','clearTimeout']})
  const data=useDataStore(),state=ref<'saving'|'error'|'saved'>('saved')
  vi.spyOn(data,'taskSaveState').mockImplementation(()=>state.value)
  let finishSave!:()=>void
  vi.spyOn(data,'flushTask').mockResolvedValueOnce(undefined).mockImplementationOnce(()=>{state.value='saving';return new Promise<void>(resolve=>{finishSave=resolve})})
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.get('[aria-label="完成 其他任务"]').setValue(true)
  await vi.advanceTimersByTimeAsync(3000);await flushPromises()
  expect(wrapper.findAll('.checklist-row').some(r=>r.text().includes('其他任务'))).toBe(true)
  state.value='error';finishSave();await flushPromises()
  expect(wrapper.findAll('.checklist-row').some(r=>r.text().includes('其他任务'))).toBe(true)
  state.value='saved';await flushPromises()
  expect(wrapper.findAll('.checklist-row').some(r=>r.text().includes('其他任务'))).toBe(false)
  wrapper.unmount();vi.useRealTimers()
 })

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
 it('收集箱和今天入口新增均不强制填入截止日期',async()=>{
  const data=useDataStore(), add=vi.spyOn(data,'addTask').mockResolvedValue({task:data.tasks[0]!,persisted:true})
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.get('[aria-label="添加任务"]').setValue('无日期任务');await wrapper.get('.quick-add').trigger('submit');await flushPromises()
  expect(add.mock.calls[0]?.[0]).toMatchObject({owner:'wg',project:'',due:''})
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='今天')!.trigger('click')
  await wrapper.get('[aria-label="添加任务"]').setValue('今日安排');await wrapper.get('.quick-add').trigger('submit');await flushPromises()
  expect(add.mock.calls[1]?.[0]).toMatchObject({due:''})
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
  expect(wrapper.findAll('.checklist-row').every(row=>row.attributes('draggable')==='true')).toBe(true)
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
