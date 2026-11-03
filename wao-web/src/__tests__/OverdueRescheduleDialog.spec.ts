import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import { projectWorkItems } from '@/utils/projectWorkItems'
import OverdueRescheduleDialog from '@/components/todo/OverdueRescheduleDialog.vue'
import { todayIso } from '@/utils/time'
import { shiftDay } from '@/utils/taskDeadline'
enableAutoUnmount(afterEach)
beforeEach(()=>{localStorage.clear();sessionStorage.clear();setActivePinia(createPinia());seedDataStore()})
function setup(){
 const data=useDataStore(), base=data.tasks[0]!, today=todayIso(), yesterday=shiftDay(today,-1)
 data.apiLive=false
 data.tasks=[
  {...base,id:'parent',title:'父任务',status:'待办',due:yesterday,start:'2020-01-01',subs:[
   {id:'late',text:'逾期子项',done:false,due:yesterday},
   {id:'future',text:'未来子项',done:false,due:shiftDay(today,1)},
   {id:'done',text:'已完成子项',done:true,due:yesterday},
  ]},
  {...base,id:'other',title:'另一逾期任务',status:'待办',due:yesterday,subs:[]},
 ]
 const state=new Map<string,'saved'|'error'|'saving'>()
 vi.spyOn(data,'taskSaveState').mockImplementation(id=>state.get(id)??'saved')
 const flush=vi.spyOn(data,'flushTask').mockResolvedValue()
 const patch=vi.spyOn(data,'patchTask').mockImplementation((id,fields)=>{Object.assign(data.taskById(id)!,fields)})
 const update=vi.spyOn(data,'updateSub').mockImplementation((id,subId,fields)=>{Object.assign(data.subById(id,subId)!,fields)})
 const p=projectWorkItems(data.tasks)
 const wrapper=mount(OverdueRescheduleDialog,{props:{items:p.items,itemRefs:p.refs},global:{stubs:{Teleport:true}}})
 const click=async()=>{await wrapper.findAll('button').find(b=>['顺延','重试剩余任务'].includes(b.text()))!.trigger('click');await flushPromises()}
 return {data,state,flush,patch,update,wrapper,click,today,yesterday}
}
describe('顺延至今天',()=>{
 it('确认前不写入，一次保存同源主任务和逾期子项，保留未来、完成与开始日期',async()=>{
  const {data,patch,update,flush,wrapper,click,today}=setup()
  expect(wrapper.text()).toContain('3 项');expect(patch).not.toHaveBeenCalled()
  await click()
  expect(patch).toHaveBeenCalledTimes(2);expect(update).toHaveBeenCalledExactlyOnceWith('parent','late',{due:today})
  expect(flush.mock.calls.map(x=>x[0])).toEqual(['parent','other'])
  expect(data.taskById('parent')?.start).toBe('2020-01-01')
  expect(data.subById('parent','future')?.due).toBe(shiftDay(today,1))
  expect(wrapper.emitted('saved')).toEqual([[3]])
 })
 it('部分保存失败只重试失败源，成功项和同源子项不重复写入',async()=>{
  const {state,flush,patch,update,wrapper,click}=setup()
  flush.mockImplementation(async id=>{if(id==='parent')state.set(id,'error')})
  await click()
  expect(wrapper.emitted('saved')).toBeUndefined();expect(wrapper.text()).toContain('还有 2 项')
  flush.mockImplementation(async id=>{state.set(id,'saved')})
  await click()
  expect(flush.mock.calls.map(x=>x[0])).toEqual(['parent','other','parent'])
  expect(patch).toHaveBeenCalledTimes(2);expect(update).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('saved')).toEqual([[3]])
 })
 it('确认期间日期已变更或任务已完成时跳过，不覆盖最新内容',async()=>{
  const {data,patch,update,wrapper,click,today}=setup()
  data.taskById('parent')!.due=shiftDay(today,4)
  data.subById('parent','late')!.done=true
  await click()
  expect(patch).toHaveBeenCalledExactlyOnceWith('other',{due:today});expect(update).not.toHaveBeenCalled()
  expect(wrapper.emitted('saved')).toEqual([[1]])
 })
 it('既有冲突不被批量操作覆盖，确认范围不因传入列表扩充而改变',async()=>{
  const {data,state,patch,wrapper,click}=setup()
  state.set('parent','error')
  const fresh={...data.tasks[1]!,id:'new',title:'新同步的逾期任务'};data.tasks.push(fresh)
  const p=projectWorkItems(data.tasks);await wrapper.setProps({items:p.items,itemRefs:p.refs})
  await click()
  expect(patch.mock.calls.map(x=>x[0])).toEqual(['other'])
  expect(wrapper.text()).toContain('已有的保存问题');expect(wrapper.emitted('saved')).toBeUndefined()
 })
 it('在途切换成员后不继续写下一项或报告成功',async()=>{
  const {flush,patch,wrapper,click}=setup()
  flush.mockImplementation(async()=>{useSessionStore().accept({accountId:'other',member:{id:'changed',name:'另一个成员',initial:'另',role:'',color:''},identities:[],createdAt:''})})
  await click()
  expect(patch.mock.calls.map(x=>x[0])).toEqual(['parent'])
  expect(wrapper.emitted('saved')).toBeUndefined();expect(wrapper.emitted('close')).toBeTruthy()
 })
})
