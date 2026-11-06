import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'
import QuadrantQuickAdd from '@/components/task/QuadrantQuickAdd.vue'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
vi.mock('vue-router',()=>({useRoute:()=>({query:{},params:{}})}))
vi.mock('@/components/ui/MarkdownEditor.vue',()=>({__esModule:true,default:{props:['modelValue','readonly'],template:'<div class="editor-stub" :data-readonly="readonly" />'}}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[]),saveTaskLabels:vi.fn(async v=>({...v,revision:v.revision+1}))}))
enableAutoUnmount(afterEach)
const login=(account='a')=>useSessionStore().accept({accountId:account,member:{id:account,name:account,initial:account,role:'',color:''},identities:[],createdAt:''})
beforeEach(()=>{vi.restoreAllMocks();localStorage.clear();setActivePinia(createPinia());login();seedDataStore()})
function mockAdd(){const data=useDataStore();return vi.spyOn(data,'addTask').mockImplementation(async(payload,id)=>{const task={...data.tasks[0]!,...payload,id:id!};data.tasks.push(task);return {task,persisted:true}})}

it('象限已创建但标签失败时锁定标题和属性，仍允许修改标签重试',async()=>{
  const add=mockAdd(),labels=useTaskLabelsStore();await labels.load()
  const save=vi.spyOn(labels,'save').mockRejectedValueOnce(new Error('offline'))
  const wrapper=mount(QuadrantQuickAdd,{attachTo:document.body,props:{priority:'P1',projectId:'',label:'重要'}})
  await wrapper.get('input').setValue('新任务');await wrapper.get('input[placeholder="用逗号分隔"]').setValue('标签')
  wrapper.get<HTMLInputElement>('input').element.focus()
  await wrapper.get('form').trigger('submit');await flushPromises()
  expect(wrapper.get('input').attributes('disabled')).toBeDefined()
  expect(wrapper.get('input[type=date]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('input[placeholder="用逗号分隔"]').attributes('disabled')).toBeUndefined()
  expect(wrapper.text()).toContain('重试标签');expect(add).toHaveBeenCalledTimes(1)
  expect(document.activeElement).toBe(wrapper.get('form button').element)
  await wrapper.get('input[placeholder="用逗号分隔"]').setValue('')
  await wrapper.get('form').trigger('submit');await flushPromises()
  expect(save).toHaveBeenLastCalledWith(expect.objectContaining({tags:[]}))
  expect(add).toHaveBeenCalledTimes(1)
})

it('象限保存标签期间切换账号再切回，旧回执不清空恢复的草稿',async()=>{
  mockAdd();const labels=useTaskLabelsStore();await labels.load()
  let resolve!:()=>void
  vi.spyOn(labels,'save').mockImplementationOnce(v=>new Promise(done=>{resolve=()=>done({...v,revision:v.revision+1})}))
  const wrapper=mount(QuadrantQuickAdd,{props:{priority:'P1',projectId:'',label:'重要'}})
  await wrapper.get('input').setValue('保留任务');await wrapper.get('input[placeholder="用逗号分隔"]').setValue('标签')
  await wrapper.get('form').trigger('submit');await flushPromises()
  login('b');login('a');await flushPromises();resolve();await flushPromises()
  expect(wrapper.get('input').element.value).toBe('保留任务')
  expect(localStorage.getItem('wao-quadrant-draft:a:a::P1')).toContain('保留任务')
})

it('全局录入已保存后只重试标签，账号往返期间旧响应不关闭弹窗',async()=>{
  const add=mockAdd(),labels=useTaskLabelsStore();await labels.load()
  let resolve!:()=>void
  vi.spyOn(labels,'save').mockImplementationOnce(v=>new Promise(done=>{resolve=()=>done({...v,revision:v.revision+1})}))
  const close=vi.spyOn(useUiStore(),'closeModal')
  const wrapper=mount(NewTaskModal)
  await wrapper.get('[aria-label="任务名称"]').setValue('全局任务');await wrapper.get('input[placeholder="用逗号分隔"]').setValue('标签')
  await wrapper.get('.btn-primary').trigger('click');await flushPromises()
  login('b');login('a');await flushPromises();resolve();await flushPromises()
  expect(close).not.toHaveBeenCalled();expect(add).toHaveBeenCalledTimes(1)
  expect(wrapper.get('[aria-label="任务名称"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[aria-label="工作项类型"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('.editor-stub').attributes('data-readonly')).toBe('true')
  expect(wrapper.get('.btn-primary').text()).toBe('重试标签')
})
