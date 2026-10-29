import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest'
import {mount,flushPromises,enableAutoUnmount} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {seedDataStore} from './helpers/seedDataStore'
import TaskQuickActionDialog from '@/components/task/TaskQuickActionDialog.vue'
enableAutoUnmount(afterEach)
beforeEach(()=>{localStorage.clear();setActivePinia(createPinia())})
const stubs={SettingsDialogShell:{template:'<div><slot /></div>'}}
describe('任务快捷操作',()=>{
 it('子任务优先级写回子项，不改父任务',async()=>{
  const data=seedDataStore(),task=data.tasks[0]!;task.subs=[{id:'child',text:'子项',done:false}]
  const update=vi.spyOn(data,'updateSub'),patch=vi.spyOn(data,'patchTask'),flush=vi.spyOn(data,'flushTask').mockResolvedValue(undefined)
  const w=mount(TaskQuickActionDialog,{props:{taskId:task.id,subId:'child',mode:'priority'},global:{stubs}})
  await w.get('select').setValue('P0');await w.get('form').trigger('submit');await flushPromises()
  expect(update).toHaveBeenCalledWith(task.id,'child',{pr:'P0'});expect(patch).not.toHaveBeenCalled();expect(flush).toHaveBeenCalledWith(task.id);expect(w.emitted('close')).toHaveLength(1)
 })
 it('添加下级失败保留输入，重试不重复创建',async()=>{
  const data=seedDataStore(),task=data.tasks[0]!;task.subs=[{id:'child',text:'子项',done:false}]
  const add=vi.spyOn(data,'addSub'),flush=vi.spyOn(data,'flushTask').mockRejectedValueOnce(new Error('保存失败')).mockResolvedValue(undefined)
  const w=mount(TaskQuickActionDialog,{props:{taskId:task.id,subId:'child',mode:'child'},global:{stubs}})
  await w.get('input').setValue('下级任务');await w.get('form').trigger('submit');await flushPromises()
  expect(w.get('[role=alert]').text()).toContain('保存失败');expect(w.emitted('close')).toBeUndefined()
  await w.get('form').trigger('submit');await flushPromises()
  expect(add).toHaveBeenCalledTimes(1);expect(add).toHaveBeenCalledWith(task.id,'下级任务','child');expect(flush).toHaveBeenCalledTimes(2);expect(w.emitted('close')).toHaveLength(1)
 })
})
