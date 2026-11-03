import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { projectWorkItems } from '@/utils/projectWorkItems'
import TaskContextMenu from '@/components/task/TaskContextMenu.vue'
import { todayIso } from '@/utils/time'
vi.mock('vue-router',()=>({useRouter:()=>({push:vi.fn()})}))
enableAutoUnmount(afterEach)
beforeEach(()=>{localStorage.clear();sessionStorage.clear();setActivePinia(createPinia());seedDataStore()})
async function setup(){
 const data=useDataStore(),base=data.tasks[0]!
 data.tasks=[{...base,id:'parent',due:'2000-01-01',subs:[{id:'child',text:'测试子项',due:'2001-01-01',done:false}]}]
 const p=projectWorkItems(data.tasks), child=p.items.find(t=>t.id!== 'parent')!
 const patch=vi.spyOn(data,'patchTask').mockImplementation(()=>{})
 const update=vi.spyOn(data,'updateSub').mockImplementation(()=>{})
 vi.spyOn(data,'flushTask').mockResolvedValue();vi.spyOn(data,'taskSaveState').mockReturnValue('saved')
 const wrapper=mount(TaskContextMenu,{props:{itemRefs:p.refs},global:{stubs:{Teleport:true}}})
 await wrapper.vm.openAt(new MouseEvent('contextmenu'),child)
 return {data,child,patch,update,wrapper}
}
describe('统一任务菜单',()=>{
 it('日期图标有可访问名称，清除日期只定位右键命中的子项',async()=>{
  const {wrapper,patch,update}=await setup()
  expect(wrapper.findAll('.quick-actions')[0]!.findAll('button').map(b=>b.attributes('aria-label'))).toEqual(['今天','明天','一周后','选择日期','清除日期'])
  await wrapper.get('[aria-label="清除日期"]').trigger('click');await flushPromises()
  expect(update).toHaveBeenCalledExactlyOnceWith('parent','child',{due:''});expect(patch).not.toHaveBeenCalled()
  expect(wrapper.emitted('dateChanging')).toEqual([['sub:parent:child']])
 })
 it('日期快捷键取今天，优先级旗标保存子项独立优先级',async()=>{
  const {wrapper,update,child}=await setup()
  await wrapper.get('[aria-label="今天"]').trigger('click');await flushPromises()
  expect(update).toHaveBeenCalledWith('parent','child',{due:todayIso()})
  await wrapper.vm.openAt(new MouseEvent('contextmenu'),child)
  await wrapper.get('[aria-label="P1 · 高优先级"]').trigger('click');await flushPromises()
  expect(update).toHaveBeenCalledWith('parent','child',{pr:'P1'})
 })
 it('已有冲突不覆盖，失败后提供真实的保存问题入口',async()=>{
  const {data,wrapper,update}=await setup()
  vi.mocked(data.taskSaveState).mockReturnValue('error')
  await wrapper.get('[aria-label="今天"]').trigger('click');await flushPromises()
  expect(update).not.toHaveBeenCalled();expect(wrapper.text()).toContain('已有的保存问题')
  expect(wrapper.findAll('button').some(b=>b.text()==='查看保存问题')).toBe(true)
 })
 it('内嵌子项删除失败后保留真实源 ID，投影移除后仍能重试',async()=>{
  const {data,wrapper}=await setup()
  const remove=vi.spyOn(data,'removeSub')
  vi.mocked(data.flushTask).mockImplementationOnce(async()=>{vi.mocked(data.taskSaveState).mockReturnValue('error')})
  await wrapper.get('[aria-label="删除子任务"]').trigger('click')
  expect(remove).not.toHaveBeenCalled()
  await wrapper.get('form').trigger('submit');await flushPromises()
  expect(remove).toHaveBeenCalledWith('parent','child')
  const p=projectWorkItems(data.tasks);await wrapper.setProps({itemRefs:p.refs})
  vi.mocked(data.flushTask).mockImplementationOnce(async()=>{vi.mocked(data.taskSaveState).mockReturnValue('saved')})
  await wrapper.get('form').trigger('submit');await flushPromises()
  expect(remove).toHaveBeenCalledTimes(1)
  expect(vi.mocked(data.flushTask).mock.calls.map(call=>call[0])).toEqual(['parent','parent'])
  expect(wrapper.find('form').exists()).toBe(false)
 })
 it('关联主任务调用子树迁移接口，不通过全文修改父任务字段',async()=>{
  const {data,wrapper,patch}=await setup()
  data.tasks.push({...data.tasks[0]!,id:'destination',title:'目标主任务',subs:[]})
  const move=vi.spyOn(data,'moveSubtask').mockResolvedValue()
  await wrapper.get('[aria-label="调整主任务"]').trigger('click')
  await wrapper.get('select').setValue('destination')
  await wrapper.get('form').trigger('submit');await flushPromises()
  expect(move).toHaveBeenCalledExactlyOnceWith('parent','child','destination','')
  expect(patch).not.toHaveBeenCalled()
 })
})
