import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { projectWorkItems } from '@/utils/projectWorkItems'
import TaskContextMenu from '@/components/task/TaskContextMenu.vue'
import { todayIso } from '@/utils/time'
import * as taskOutput from '@/utils/taskSelectionExport'
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
 it('任务动态从命中的主任务读取，子项明确说明动态范围',async()=>{
  const {data,wrapper}=await setup()
  data.taskById('parent')!.activity=[{icon:'ri-add-line',color:'',text:'添加了子任务',time:'2026-11-06T10:00:00+08:00',by:'me'}]
  await wrapper.get('[aria-label="任务动态"]').trigger('click')
  expect(wrapper.text()).toContain('所属主任务及其子任务的动态')
  expect(wrapper.text()).toContain('添加了子任务')
  expect(wrapper.find('button[type="submit"]').exists()).toBe(false)
 })
 it('主任务更多菜单上传后关联到命中的任务并保存，子项不冒充支持独立附件',async()=>{
  const {data,wrapper,child,patch}=await setup()
  expect(wrapper.find('[aria-label="上传附件"]').exists()).toBe(false)
  await wrapper.vm.openAt(new MouseEvent('contextmenu'),data.tasks[0]!)
  const uploaded={...data.files[0]!,id:'uploaded-file',name:'brief.txt'}
  const upload=vi.spyOn(data,'uploadFile').mockResolvedValue(uploaded)
  patch.mockImplementation((id,fields)=>{Object.assign(data.taskById(id)!,fields)})
  await wrapper.get('[aria-label="上传附件"]').trigger('click')
  const input=wrapper.get('input[type="file"]')
  Object.defineProperty(input.element,'files',{configurable:true,value:[new File(['test'],'brief.txt',{type:'text/plain'})]})
  await input.trigger('change');await flushPromises()
  expect(upload).toHaveBeenCalledTimes(1)
  expect(data.taskById('parent')!.files.some(file=>file.id==='uploaded-file')).toBe(true)
  expect(data.flushTask).toHaveBeenCalledWith('parent')
  await wrapper.vm.openAt(new MouseEvent('contextmenu'),child)
  expect(wrapper.find('[aria-label="上传附件"]').exists()).toBe(false)
 })
 it('导出和打印使用当前子任务范围',async()=>{
  const {wrapper,child}=await setup()
  const download=vi.spyOn(taskOutput,'downloadTaskMarkdown').mockImplementation(()=>{})
  const print=vi.spyOn(taskOutput,'printTaskMarkdown').mockImplementation(()=>{})
  await wrapper.get('[aria-label="导出 Markdown"]').trigger('click')
  expect(download).toHaveBeenCalledOnce()
  expect(download.mock.calls[0]![0]).toBe('测试子项')
  expect(download.mock.calls[0]![1]).toContain('/api/share/tasks/parent/subs/child.md')
  await wrapper.vm.openAt(new MouseEvent('contextmenu'),child)
  await wrapper.get('[aria-label="打印"]').trigger('click')
  expect(print.mock.calls[0]![0]).toBe('测试子项')
 })
})

it('放弃先确认再保存命中的子项，转换和复制沿用统一事务',async()=>{
 const {wrapper,data,child}=await setup()
 const operation=vi.spyOn(data,'taskMenuOperation').mockResolvedValue('parent')
 await wrapper.get('[aria-label="放弃"]').trigger('click');expect(operation).not.toHaveBeenCalled()
 await wrapper.get('form').trigger('submit');await flushPromises()
 expect(operation).toHaveBeenCalledWith('parent','child','abandon')
 await wrapper.vm.openAt(new MouseEvent('contextmenu'),child)
 await wrapper.get('[aria-label="创建副本"]').trigger('click');await flushPromises()
 expect(operation).toHaveBeenCalledWith('parent','child','duplicate')
 await wrapper.vm.openAt(new MouseEvent('contextmenu'),child)
 await wrapper.get('[aria-label="转换为笔记"]').trigger('click');await wrapper.get('form').trigger('submit');await flushPromises()
 expect(operation).toHaveBeenCalledWith('parent','child','note')
})
it('父任务阻止有损笔记转换；已放弃任务提供恢复，笔记提供反向转换',async()=>{
 const {wrapper,data,child}=await setup()
 await wrapper.vm.openAt(new MouseEvent('contextmenu'),data.tasks[0]!)
 expect(wrapper.get('[aria-label="转换为笔记"]').attributes('disabled')).toBeDefined()
 await wrapper.vm.openAt(new MouseEvent('contextmenu'),{...child,status:'已放弃'})
 expect(wrapper.find('[aria-label="重新开始"]').exists()).toBe(true)
 await wrapper.vm.openAt(new MouseEvent('contextmenu'),{...child,workItemType:'note'})
 expect(wrapper.find('[aria-label="转换为任务"]').exists()).toBe(true)
 expect(wrapper.get('[aria-label="添加子任务"]').attributes('disabled')).toBeDefined()
})
