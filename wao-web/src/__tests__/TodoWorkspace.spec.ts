import {computed,defineComponent,h,ref} from 'vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import {projectWorkItems} from '@/utils/projectWorkItems'
import {useTaskLabelsStore} from '@/stores/taskLabels'
import { beforeEach,describe,expect,it,vi } from 'vitest'
import { mount,flushPromises,type VueWrapper } from '@vue/test-utils'
import { createPinia,setActivePinia } from 'pinia'
import TodoView from '@/views/TodoView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'
import TaskLabelEditor from '@/components/task/TaskLabelEditor.vue'
import TaskDueDate from '@/components/task/TaskDueDate.vue'
import { shiftTaskDate } from '@/utils/taskWorkspaceFilter'
import { saveTaskLabels } from '@/api/taskLabels'
import { savePreference } from '@/api/favorites'
const testRoute = vi.hoisted(() => ({ query: {} as Record<string,string> }))
vi.mock('vue-router',()=>({useRoute:()=>testRoute,useRouter:()=>({push:vi.fn(),replace:vi.fn()})}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[{taskId:'parent',subId:'child',revision:1,tags:['联调']}]),saveTaskLabels:vi.fn(async(v)=>({...v,revision:v.revision+1}))}))
vi.mock('@/api/favorites',async original=>({...await original<typeof import('@/api/favorites')>(),fetchPreference:vi.fn(async()=>''),savePreference:vi.fn(async()=>{})}))
vi.mock('@/api/smartListVisibility',()=>({fetchSmartListVisibility:vi.fn(async()=>({})),saveSmartListVisibility:vi.fn(async()=>{})}))
const options={global:{stubs:{Teleport:true,MarkdownEditor:true,TaskDocuments:true,TaskDueDate:true}}}
const buttonText = (w:VueWrapper, selector:string, text:string) => w.findAll(selector).find(button => button.text().trim()===text)!
async function switchView(w:VueWrapper, name:string) {
 await w.get('[aria-label="更多视图选项"]').trigger('click')
 await buttonText(w,'[role="menuitemradio"]',name).trigger('click')
 await flushPromises()
}
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
 it('在今天直接回车添加子任务后，保留当前视图和继续输入的入口',async()=>{
  const data=useDataStore()
  data.tasks[0]!.due=data.today
  vi.spyOn(data,'flushTask').mockResolvedValue()
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  const wrapper=mount(TodoView,options);await flushPromises()
  const today=buttonText(wrapper,'.task-navigation button','今天')
  await today.trigger('click');await flushPromises()
  await wrapper.get('[data-task-id="parent"] .task-select').trigger('click')
  const input=wrapper.get('[aria-label="子任务名称"]')
  await input.setValue('今天快速添加')
  await input.trigger('keydown',{key:'Enter'});await flushPromises()
  expect(today.classes()).toContain('active')
  expect(wrapper.get('[aria-label="子任务名称"]').element).toHaveProperty('value','')
  expect(data.taskById('parent')?.subs.some(sub=>sub.text==='今天快速添加')).toBe(true)
 })
 it('三种视图共用任务与保存：看板按状态移动、时间线显示执行区间和截止标记',async()=>{
  const data=useDataStore(), base=data.tasks[0]!
  data.tasks=[
   {...base,id:'range',title:'跨日执行',start:data.today,startAt:data.today+'T15:00',endAt:shiftTaskDate(data.today,2)+'T00:00',due:shiftTaskDate(data.today,3),subs:[]},
   {...base,id:'undated',title:'无日期待办',start:'',startAt:null,endAt:null,due:'',subs:[]},
   {...base,id:'outside',title:'范围外待办',start:'',startAt:null,endAt:null,due:shiftTaskDate(data.today,16),subs:[]},
  ]
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  const wrapper=mount(TodoView,options);await flushPromises()
  await switchView(wrapper,'看板视图')
  const progress=wrapper.findAll('.board-column').find(col=>col.get('h2').text().startsWith('进行中'))!
  await wrapper.get('[data-task-id="range"]').trigger('dragstart',{dataTransfer:{setData:vi.fn(),effectAllowed:''}})
  await progress.trigger('dragover');await progress.trigger('drop');await flushPromises()
  expect(progress.get('[data-task-id="range"]').text()).toContain('跨日执行')
  expect(data.taskById('range')?.status).toBe('进行中')
  await switchView(wrapper,'时间线视图')
  expect(wrapper.get('.timeline-execution').attributes('style')).toContain('2 / 4')
  expect(wrapper.get('.timeline-deadline').attributes('title')).toContain(shiftTaskDate(data.today,3))
  expect(wrapper.get('[data-task-id="undated"]').text()).toContain('无日期待办')
  expect(wrapper.find('[data-task-id="outside"]').exists()).toBe(false)
  await wrapper.get('[aria-label="下一段时间线"]').trigger('click')
  expect(wrapper.find('[data-task-id="outside"]').exists()).toBe(true)
  await switchView(wrapper,'列表视图')
  expect(wrapper.findAll('.checklist-row')).toHaveLength(3)
  wrapper.unmount()
 })
 it('优先级排序、分组和显示偏好可实际使用，关闭同步后只保存本机视图',async()=>{
  const data=useDataStore(), base=data.tasks[0]!
  data.tasks=[
   {...base,id:'low',title:'低优先级',pr:'P3',due:shiftTaskDate(data.today,3),subs:[],desc:'详细描述'},
   {...base,id:'high',title:'高优先级',pr:'P0',subs:[]},
   {...base,id:'done',title:'完成事项',status:'已完成',pr:'P1',subs:[]},
  ]
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  const wrapper=mount(TodoView,options);await flushPromises()
  const ids=()=>wrapper.findAll('.checklist-row').map(row=>row.attributes('data-task-id'))
  await wrapper.get('[aria-label="分组与排序"]').trigger('click')
  await wrapper.get('[data-section="sort"]').trigger('click')
  await buttonText(wrapper,'[role="menuitemradio"]','优先级').trigger('click')
  expect(ids()).toEqual(['high','low'])
  await wrapper.get('[data-section="direction"]').trigger('click')
  await buttonText(wrapper,'[role="menuitemradio"]','从低到高').trigger('click')
  expect(ids()).toEqual(['low','high'])
  await wrapper.get('[data-section="group"]').trigger('click')
  await buttonText(wrapper,'[role="menuitemradio"]','优先级').trigger('click')
  expect(wrapper.findAll('.group-heading').map(group=>group.text())).toEqual(['最高优先级 1','低优先级 1'])
  await wrapper.get('[aria-label="更多视图选项"]').trigger('click')
  await buttonText(wrapper,'[role="menuitemcheckbox"]','显示已完成').trigger('click')
  await buttonText(wrapper,'[role="menuitemcheckbox"]','显示详细').trigger('click')
  expect(ids()).toContain('done')
  expect(wrapper.get('[data-task-id="low"]').text()).toContain('详细描述')
  await buttonText(wrapper,'[role="menuitem"]','显示设置').trigger('click');await flushPromises()
  await wrapper.get('[aria-label="日期显示为"]').setValue('countdown')
  await wrapper.findAll('input[name="todo-detail-style"]')[1]!.setValue(true)
  await wrapper.get('[role="switch"]').trigger('click');await flushPromises()
  await buttonText(wrapper,'button','完成').trigger('click')
  expect(wrapper.get('[data-task-id="low"]').text()).toContain('还有 3 天')
  expect(wrapper.find('.checklist-detail').exists()).toBe(false)
  await wrapper.get('[data-task-id="low"] .task-select').trigger('click')
  expect(useUiStore().openTaskId).toBe('low')
  const writes=vi.mocked(savePreference).mock.calls.filter(([key])=>key==='todo-view-v1:all').length
  await switchView(wrapper,'看板视图')
  expect(vi.mocked(savePreference).mock.calls.filter(([key])=>key==='todo-view-v1:all')).toHaveLength(writes)
  wrapper.unmount()
  const reopened=mount(TodoView,options);await flushPromises()
  expect(reopened.find('.board-columns').exists()).toBe(true)
  reopened.unmount()
 })
 it('今天清单像所有一样展示多级任务，切换视图后保留折叠状态',async()=>{
  const data=useDataStore(), base=data.tasks[0]!
  data.tasks=[{...base,due:data.today,start:'',startAt:undefined,subs:[
   {id:'child',text:'子任务',done:false,due:data.today},
   {id:'grand',parentId:'child',text:'孙任务',done:false,due:data.today},
   {id:'future',text:'未来子任务',done:false,due:shiftTaskDate(data.today,1)},
  ]}]
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='今天')!.trigger('click');await flushPromises()
  const ids=()=>wrapper.findAll('.checklist-row').map(row=>row.attributes('data-task-id'))
  expect(ids()).toEqual(['parent','sub:parent:child','sub:parent:grand'])
  expect(wrapper.get('[data-task-id="parent"]').attributes('style')).toContain('padding-inline-start: 8px')
  expect(wrapper.get('[data-task-id="sub:parent:child"]').attributes('style')).toContain('padding-inline-start: 24px')
  expect(wrapper.get('[data-task-id="sub:parent:grand"]').attributes('style')).toContain('padding-inline-start: 40px')
  await wrapper.get('[data-task-id="parent"] .tree-toggle').trigger('click')
  expect(ids()).toEqual(['parent'])
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='所有')!.trigger('click')
  expect(ids()).toContain('sub:parent:grand')
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='今天')!.trigger('click')
  expect(ids()).toEqual(['parent'])
  await wrapper.get('[data-task-id="parent"] .tree-toggle').trigger('click')
  expect(ids()).toEqual(['parent','sub:parent:child','sub:parent:grand'])
  wrapper.unmount()
 })
 it('今天分组补回跨日期父级，折叠今天父项不隐藏逾期子项且计数不含上下文',async()=>{
  const data=useDataStore(), base=data.tasks[0]!
  data.tasks=[{...base,due:data.today,start:'',startAt:undefined,subs:[
   {id:'child',text:'逾期子任务',done:false,due:shiftTaskDate(data.today,-1)},
   {id:'today-child',text:'今天子任务',done:false,due:data.today},
  ]}]
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='今天')!.trigger('click');await flushPromises()
  const sections=wrapper.findAll('.checklist-tasks section')
  expect(sections).toHaveLength(2)
  expect(sections[0]!.findAll('.checklist-row').map(row=>row.attributes('data-task-id'))).toEqual(['parent','sub:parent:child'])
  expect(sections[0]!.get('.group-heading small').text()).toBe('1')
  expect(sections[1]!.get('.group-heading small').text()).toBe('2')
  await sections[0]!.get('[data-task-id="sub:parent:child"] .task-select').trigger('click')
  await sections[1]!.get('[data-task-id="parent"] .tree-toggle').trigger('click')
  expect(sections[0]!.get('[data-task-id="sub:parent:child"]').classes()).toContain('selected')
  expect(wrapper.get('[aria-label="任务标题"]').element).toHaveProperty('value','逾期子任务')
  expect(sections[1]!.find('[data-task-id="sub:parent:today-child"]').exists()).toBe(false)
  expect(wrapper.get('.count').text()).toBe('3')
  wrapper.unmount()
 })
 it('仅下级任务在今天时补回未排期的完整父任务，不带入未来兄弟任务',async()=>{
  const data=useDataStore(), base=data.tasks[0]!
  data.tasks=[
   {...base,id:'parent',due:'',start:'',startAt:undefined,subs:[]},
   {...base,id:'child-task',parentTaskId:'parent',title:'今天的完整子任务',due:data.today,start:'',startAt:undefined,subs:[]},
   {...base,id:'sibling',parentTaskId:'parent',title:'未来兄弟任务',due:shiftTaskDate(data.today,1),start:'',startAt:undefined,subs:[]},
  ]
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.get('[data-task-id="parent"] .tree-toggle').trigger('click')
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='今天')!.trigger('click');await flushPromises()
  expect(wrapper.findAll('.checklist-row').map(row=>row.attributes('data-task-id'))).toEqual(['parent','child-task'])
  expect(wrapper.get('[data-task-id="child-task"]').attributes('style')).toContain('padding-inline-start: 24px')
  expect(wrapper.get('.group-heading small').text()).toBe('1')
  expect(wrapper.get('.count').text()).toBe('1')
  wrapper.unmount()
 })
 it('今天清单和四象限均包含本人逾期主任务与子项，排除已完成、他人及未来任务',async()=>{
  const data=useDataStore(), base=data.tasks[0]!, yesterday=shiftTaskDate(data.today,-1)
  data.tasks=[
   {...base,id:'overdue',title:'逾期主任务',due:yesterday,start:'',startAt:undefined,subs:[]},
   {...base,id:'parent',title:'无日期父任务',due:'',start:'',startAt:undefined,subs:[{id:'child',text:'逾期子任务',done:false,due:yesterday}]},
   {...base,id:'done',title:'已完成逾期',status:'已完成',due:yesterday,subs:[]},
   {...base,id:'other',title:'他人逾期',owner:'other',due:yesterday,subs:[]},
   {...base,id:'future',title:'未来任务',due:shiftTaskDate(data.today,1),start:'',startAt:undefined,subs:[]},
  ]
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='今天')!.trigger('click');await flushPromises()
  const rows=wrapper.findAll('.checklist-row').map(row=>row.text()).join(' ')
  expect(rows).toContain('逾期主任务');expect(rows).toContain('逾期子任务')
  expect(rows).not.toContain('已完成逾期');expect(rows).not.toContain('他人逾期');expect(rows).not.toContain('未来任务')
  expect(wrapper.get('[data-task-id="overdue"] .overdue').attributes('title')).toBe('已延期 1 天')
  expect(wrapper.get('.count').text()).toBe('2')
  await switchView(wrapper,'四象限')
  const quadrants=wrapper.findAll('.quadrant-task').map(row=>row.text()).join(' ')
  expect(quadrants).toContain('逾期主任务');expect(quadrants).toContain('逾期子任务')
  expect(quadrants).not.toContain('已完成逾期');expect(quadrants).not.toContain('他人逾期');expect(quadrants).not.toContain('未来任务')
  expect(data.taskById('overdue')?.due).toBe(yesterday)
  wrapper.unmount()
 })
 it.each(['parent','child'])('今天清除 %s 日期后保存失败仍保留重试入口，成功才退出筛选且不改其他日期',async(kind)=>{
  const data=useDataStore(), base=data.tasks[0]!, yesterday=shiftTaskDate(data.today,-1)
  data.tasks=[{...base,id:'parent',title:'清除父任务',due:kind==='parent'?yesterday:'',start:'',startAt:undefined,subs:[{id:'child',text:'清除子任务',done:false,due:kind==='child'?yesterday:'2027-01-01'}]}]
  const state=ref<'saving'|'error'|'saved'>('saved')
  vi.spyOn(data,'taskSaveState').mockImplementation(()=>state.value)
  vi.spyOn(data,'taskSaveError').mockReturnValue('版本冲突，请重试')
  vi.spyOn(data,'patchTask').mockImplementation((id,fields)=>{Object.assign(data.taskById(id)!,fields);state.value='saving'})
  vi.spyOn(data,'updateSub').mockImplementation((id,subId,fields)=>{Object.assign(data.subById(id,subId)!,fields);state.value='saving'})
  let finish!:()=>void
  vi.spyOn(data,'flushTask').mockImplementation(()=>new Promise<void>(resolve=>{finish=resolve}))
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.findAll('.task-navigation button').find(b=>b.text()==='今天')!.trigger('click');await flushPromises()
  wrapper.getComponent(TaskDueDate).vm.$emit('change','');await flushPromises()
  expect(wrapper.findComponent(TaskDueDate).exists()).toBe(true)
  expect(kind==='parent'?data.taskById('parent')?.due:data.subById('parent','child')?.due).toBe('')
  state.value='error';finish();await flushPromises()
  expect(wrapper.findComponent(TaskDueDate).exists()).toBe(true)
  expect(wrapper.text()).toContain('版本冲突')
  expect(kind==='parent'?data.subById('parent','child')?.due:data.taskById('parent')?.due).toBe(kind==='parent'?'2027-01-01':'')
  state.value='saved';await flushPromises()
  expect(wrapper.findAll('.checklist-row')).toHaveLength(0)
  wrapper.unmount()
 })
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
  await wrapper.get('[aria-label="更多视图选项"]').trigger('click')
  expect(wrapper.findAll('[role="menuitemradio"]').map(b=>b.text().trim())).toEqual(['列表视图','看板视图','时间线视图','四象限'])
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
 it('搜索包含已完成任务，清空后恢复未完成列表',async()=>{
  const data=useDataStore()
  data.tasks[1]!.status='已完成';data.tasks[1]!.title='历史搜索主任务'
  data.tasks[0]!.subs.push({id:'done-search',text:'历史搜索子任务',done:true})
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  const wrapper=mount(TodoView,options);await flushPromises()
  const rows=()=>wrapper.findAll('.checklist-row').map(r=>r.text()).join(' ')
  expect(rows()).not.toContain('历史搜索')
  expect(wrapper.text()).not.toContain('保存筛选')
  expect(wrapper.get('.task-navigation').text()).not.toContain('筛选器')
  expect(wrapper.find('[aria-label="搜索任务"]').exists()).toBe(true)
  await wrapper.get('[aria-label="搜索任务"]').setValue('历史搜索')
  expect(rows()).toContain('历史搜索子任务')
  await wrapper.get('[aria-label="清空搜索"]').trigger('click')
  expect(rows()).not.toContain('历史搜索')
  await wrapper.get('.completed-toggle').trigger('click')
  expect(rows()).toContain('历史搜索子任务')
  await wrapper.get('.completed-toggle').trigger('click')
  expect(rows()).not.toContain('历史搜索')
  wrapper.unmount()
 })
 it('项目与待办共用更新：子项优先级、标签筛选、新增与完成即时响应',async()=>{
  const data=useDataStore(),labels=useTaskLabelsStore()
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  data.tasks[0]!.pr='P3'
  const project=mount(defineComponent({setup(){const p=computed(()=>projectWorkItems(data.tasks));return()=>h(TaskQuadrantView,{tasks:p.value.items,contextTasks:p.value.items,itemRefs:p.value.refs,selectedIds:[],showDone:false,projectId:'sync-project'})}}),options)
  const todo=mount(TodoView,options);await flushPromises()
  await switchView(todo,'四象限')
  const section=(w:any,n:number)=>w.findAll('.quadrant')[n].text()
  expect(section(project,3)).toContain('独立子任务');expect(section(todo,3)).toContain('独立子任务')
  data.updateSub('parent','child',{pr:'P0'});await flushPromises()
  expect(section(project,0)).not.toContain('独立子任务');expect(section(todo,0)).not.toContain('独立子任务')
  expect(section(project,3)).toContain('独立子任务');expect(section(todo,3)).toContain('独立子任务')
  data.patchTask('parent',{pr:'P1'});await flushPromises()
  expect(section(project,1)).toContain('独立子任务');expect(section(todo,1)).toContain('独立子任务')
  expect(data.subById('parent','child')?.pr).toBe('P0')
  await todo.findAll('.task-navigation button').find(b=>b.text()==='联调')!.trigger('click');await flushPromises()
  await switchView(todo,'四象限')
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
  await switchView(wrapper,'四象限')
  expect(wrapper.findAll('.quadrant-task').some(row=>row.text().includes('共享主任务'))).toBe(true)
  await switchView(wrapper,'列表视图')
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
 it('快速创建失败后修改标题，重试仍用原ID并把新标题送入保存队列',async()=>{
  const data=useDataStore(),base=data.tasks[0]!
  const add=vi.spyOn(data,'addTask').mockImplementation(async(payload,id)=>{
   const task={...base,...payload,id:id!};data.tasks.push(task);return {task,persisted:false}
  })
  const savedTitles:string[]=[]
  vi.spyOn(data,'flushTask').mockImplementation(async id=>{savedTitles.push(data.taskById(id)!.title)})
  vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
  const wrapper=mount(TodoView,options);await flushPromises()
  await wrapper.get('[aria-label="添加任务"]').setValue('第一次标题')
  await wrapper.get('.quick-add').trigger('submit');await flushPromises()
  const id=add.mock.calls[0]![1]!
  expect(wrapper.text()).toContain('任务未保存，请重试')
  await wrapper.get('[aria-label="添加任务"]').setValue('修正后的标题')
  await wrapper.get('.quick-add').trigger('submit');await flushPromises()
  expect(add).toHaveBeenCalledTimes(1)
  expect(data.tasks.filter(task=>task.id===id)).toHaveLength(1)
  expect(savedTitles).toContain('修正后的标题')
  expect(data.taskById(id)?.title).toBe('修正后的标题')
  expect(wrapper.get('[aria-label="添加任务"]').element).toHaveProperty('value','')
  wrapper.unmount()
 })
})

it('恢复搜索且忽略旧查询；笔记保留在清单，放弃可找回，四象限仅保留角落添加',async()=>{
 const data=useDataStore(),base=data.tasks[0]!
 sessionStorage.setItem('wao-task-view-v1:workspace-test:wg:todo:all',JSON.stringify({query:'不存在的旧查询',collapsed:[]}))
 data.tasks=[{...base,id:'normal',title:'普通任务',subs:[]},{...base,id:'note',title:'保留笔记',workItemType:'note',subs:[]},{...base,id:'abandoned',title:'暂时放弃',status:'已放弃',subs:[]}]
 const w=mount(TodoView,options);await flushPromises()
 expect(w.find('[aria-label="搜索任务"]').exists()).toBe(true)
 expect(w.findAll('.checklist-row').map(r=>r.text()).join(' ')).toContain('普通任务')
 expect(w.find('[data-task-id="note"]').exists()).toBe(true)
 expect(w.find('[data-task-id="note"] input[type="checkbox"]').exists()).toBe(false)
 expect(w.find('[data-task-id="abandoned"]').exists()).toBe(false)
 await switchView(w,'四象限')
 expect(w.find('[aria-label="添加任务"]').exists()).toBe(false)
 expect(w.findAll('.quadrant-task').map(r=>r.text()).join(' ')).not.toContain('保留笔记')
 await switchView(w,'列表视图')
 await w.findAll('.task-navigation button').find(b=>b.text()==='已放弃')!.trigger('click');await flushPromises()
 expect(w.find('[data-task-id="abandoned"]').exists()).toBe(true)
 w.unmount()
})

it('当前共享清单的已完成入口保留范围，搜索与四象限均覆盖已完成',async()=>{
 const data=useDataStore(),base=data.tasks[0]!,project=data.projects[0]!
 data.tasks=[{...base,id:'shared-done',project:project.id,owner:'other',title:'共享完成记录',status:'已完成',subs:[]},{...base,id:'outside-done',project:'outside',owner:'wg',title:'其他清单完成记录',status:'已完成',subs:[]}]
 testRoute.query={list:project.id}
 const w=mount(TodoView,options);await flushPromises()
 expect(w.find('[data-task-id="shared-done"]').exists()).toBe(false)
 await w.get('.completed-toggle').trigger('click')
 expect(w.find('[data-task-id="shared-done"]').exists()).toBe(true)
 expect(w.find('[data-task-id="outside-done"]').exists()).toBe(false)
 await w.get('.completed-toggle').trigger('click')
 await w.get('[aria-label="搜索任务"]').setValue('共享完成记录')
 expect(w.find('[data-task-id="shared-done"]').exists()).toBe(true)
 await switchView(w,'四象限')
 expect(w.findAll('.quadrant-task').map(r=>r.text()).join(' ')).toContain('共享完成记录')
 await w.get('[aria-label="清空搜索"]').trigger('click')
 expect(w.findAll('.quadrant-task').map(r=>r.text()).join(' ')).not.toContain('共享完成记录')
 w.unmount()
})
