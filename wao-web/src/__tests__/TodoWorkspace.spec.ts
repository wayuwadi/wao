import { beforeEach,describe,expect,it,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import { createPinia,setActivePinia } from 'pinia'
import TodoView from '@/views/TodoView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
vi.mock('vue-router',()=>({useRoute:()=>({query:{}}),useRouter:()=>({push:vi.fn()})}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[{taskId:'parent',subId:'child',revision:1,tags:['联调']}]),saveTaskLabels:vi.fn(async(v)=>({...v,revision:v.revision+1}))}))
vi.mock('@/api/favorites',async original=>({...await original<typeof import('@/api/favorites')>(),fetchPreference:vi.fn(async()=>''),savePreference:vi.fn(async()=>{})}))
const options={global:{stubs:{MarkdownEditor:true,TaskDocuments:true,TaskDueDate:true}}}
beforeEach(()=>{
 localStorage.clear();sessionStorage.clear();setActivePinia(createPinia())
 useSessionStore().accept({accountId:'workspace-test',member:{id:'wg',name:'成员',initial:'测',role:'',color:''},identities:[],createdAt:''})
 const data=seedDataStore();data.taskLoadState='loaded';data.apiLive=false
 const source=data.tasks[0]!
 data.tasks=[{...source,id:'parent',title:'主任务',owner:'wg',status:'待办',subs:[{id:'child',text:'独立子任务',done:false,due:data.today}]},{...source,id:'other',title:'其他任务',owner:'wg',status:'待办',subs:[]}]
})
describe('task workspace',()=>{
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
