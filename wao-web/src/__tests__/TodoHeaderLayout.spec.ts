import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TodoListHeader from '@/components/todo/TodoListHeader.vue'
import TodoView from '@/views/TodoView.vue'
import { defaultTodoView, decodeTodoDisplay } from '@/utils/todoViewSettings'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
vi.mock('vue-router',()=>({useRoute:()=>({query:{}}),useRouter:()=>({push:vi.fn(),replace:vi.fn()})}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[]),saveTaskLabels:vi.fn()}))
vi.mock('@/api/favorites',async original=>({...await original<object>(),fetchPreference:vi.fn(async()=>''),savePreference:vi.fn()}))
vi.mock('@/api/smartListVisibility',()=>({fetchSmartListVisibility:vi.fn(async()=>({})),saveSmartListVisibility:vi.fn()}))
enableAutoUnmount(afterEach)
const originalWidth=window.innerWidth
afterEach(()=>{Object.defineProperty(window,'innerWidth',{value:originalWidth,configurable:true})})
beforeEach(()=>{
  localStorage.clear();sessionStorage.clear();setActivePinia(createPinia())
  useSessionStore().accept({accountId:'header-account',member:{id:'me',name:'成员',initial:'测',role:'',color:''},identities:[],createdAt:''})
  const data=seedDataStore();data.tasks=[];data.taskLoadState='loaded';data.apiLive=false
})
const viewOptions={global:{stubs:{ProjectChecklistView:{template:'<div><slot name="list-header" /></div>'},TodoOrganization:{template:'<button aria-label="筛选入口">筛选</button>'},TodoFolderNavigation:true,TaskQuadrantView:true,TaskTimeSettings:true,MarkdownEditor:true,TaskDocuments:true}}}

it('筛选插槽位于标题工具栏内，搜索、批量与视图菜单仍可独立操作',async()=>{
  const wrapper=mount(TodoListHeader,{props:{heading:'所有',count:3,selection:'all',navigationCollapsed:false,creating:false,error:'',view:defaultTodoView(),display:decodeTodoDisplay(null),syncError:'',destination:'收集箱',quick:''},slots:{filters:'<button aria-label="示例筛选">筛选</button>'}})
  expect(wrapper.find('header [aria-label="示例筛选"]').exists()).toBe(true)
  await wrapper.get('[aria-label="搜索任务"]').setValue('名称')
  expect(wrapper.emitted('update:query')?.[0]).toEqual(['名称'])
  await wrapper.get('[aria-label="批量选择"]').trigger('click');expect(wrapper.emitted('batch')).toHaveLength(1)
  expect(wrapper.find('header [aria-label="更多视图选项"]').exists()).toBe(true)
})

it('手机首次打开收起导航，明确展开后重新打开保留选择',async()=>{
  Object.defineProperty(window,'innerWidth',{value:390,configurable:true})
  let wrapper=mount(TodoView,viewOptions);await flushPromises()
  expect(wrapper.get('[aria-label="展开待办导航"]').attributes('aria-expanded')).toBe('false')
  expect(wrapper.findAll('header [aria-label="筛选入口"]')).toHaveLength(1)
  await wrapper.get('[aria-label="展开待办导航"]').trigger('click')
  expect(wrapper.get('[aria-label="收起待办导航"]').attributes('aria-expanded')).toBe('true')
  wrapper.unmount();wrapper=mount(TodoView,viewOptions);await flushPromises()
  expect(wrapper.get('[aria-label="收起待办导航"]').attributes('aria-expanded')).toBe('true')
})

it('桌面首次保留导航，已有明确收起设置不受默认值影响',async()=>{
  Object.defineProperty(window,'innerWidth',{value:1280,configurable:true})
  let wrapper=mount(TodoView,viewOptions);await flushPromises()
  expect(wrapper.find('[aria-label="收起待办导航"]').exists()).toBe(true)
  wrapper.unmount()
  sessionStorage.setItem('wao-task-view-v1:header-account:me:todo',JSON.stringify({navigationCollapsed:true,selection:'all'}))
  wrapper=mount(TodoView,viewOptions);await flushPromises()
  expect(wrapper.find('[aria-label="展开待办导航"]').exists()).toBe(true)
})
