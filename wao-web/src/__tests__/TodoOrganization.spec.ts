import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { fetchPreference } from '@/api/favorites'
import { saveTaskOrganizationPreference as savePreference } from '@/api/taskOrganization'
import TodoOrganization from '@/components/todo/TodoOrganization.vue'
import TodoFolderNavigation from '@/components/todo/TodoFolderNavigation.vue'
import { emptyTaskOrganizationFilter } from '@/utils/taskOrganization'
import type { Project } from '@/types'
vi.mock('@/api/favorites',()=>({fetchPreference:vi.fn()}))
vi.mock('@/api/taskOrganization',()=>({saveTaskOrganizationPreference:vi.fn()}))
const login=(account='a',member='me')=>useSessionStore().accept({accountId:account,member:{id:member,name:member,initial:member,color:'',role:''},identities:[],createdAt:''})
const shell={template:'<section><slot/><slot name="footer"/></section>'}
beforeEach(()=>{vi.resetAllMocks();localStorage.clear();sessionStorage.clear();setActivePinia(createPinia());login();vi.mocked(fetchPreference).mockResolvedValue('');vi.mocked(savePreference).mockResolvedValue()})
it('初始读取失败时可以本地筛选，但不能覆盖账号预设；草稿在关闭重开后保留',async()=>{
  vi.mocked(fetchPreference).mockRejectedValue(new Error('offline'))
  const wrapper=mount(TodoOrganization,{props:{modelValue:emptyTaskOrganizationFilter(),projects:[],tags:[]},global:{stubs:{SettingsDialogShell:shell}}})
  await flushPromises();await wrapper.findAll('button').find(button=>button.text()==='筛选')!.trigger('click')
  await wrapper.get('input[placeholder="例如：本周重要工作"]').setValue('重要任务')
  expect(wrapper.findAll('button').find(button=>button.text()==='保存为筛选')!.attributes('disabled')).toBeDefined()
  expect(savePreference).not.toHaveBeenCalled()
  await wrapper.findAll('button').find(button=>button.text()==='应用筛选')!.trigger('click')
  expect(wrapper.emitted('update:modelValue')).toBeTruthy();wrapper.unmount()
})
it('命名筛选只保存条件，失败保留待同步内容，换账号清空当前筛选',async()=>{
  const wrapper=mount(TodoOrganization,{props:{modelValue:emptyTaskOrganizationFilter(),projects:[],tags:[]},global:{stubs:{SettingsDialogShell:shell}}})
  await flushPromises();await wrapper.findAll('button').find(button=>button.text()==='筛选')!.trigger('click')
  await wrapper.get('input[placeholder="例如：本周重要工作"]').setValue('本周工作')
  vi.mocked(savePreference).mockRejectedValueOnce(new Error('offline'))
  await wrapper.findAll('button').find(button=>button.text()==='保存为筛选')!.trigger('click');await flushPromises()
  expect(JSON.parse(vi.mocked(savePreference).mock.calls[0]![1])[0].name).toBe('本周工作')
  expect(localStorage.getItem('wao-task-organization:a:me:todo-named-filters-v1')).toContain('本周工作')
  expect(wrapper.text()).toContain('尚未同步到账号')
  login('b','other');await flushPromises()
  const updates=wrapper.emitted('update:modelValue') || []
  expect(updates[updates.length-1]?.[0]).toEqual(emptyTaskOrganizationFilter())
  expect(wrapper.find('input[placeholder="例如：本周重要工作"]').exists()).toBe(false);wrapper.unmount()
})
it('文件夹整理只改变当前人的导航偏好，失败后保留项目入口和重试',async()=>{
  const projects=[{id:'p1',name:'工作清单'},{id:'p2',name:'生活清单'}] as Project[]
  vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify({folders:[{id:'work',name:'工作'}],assignments:{p1:'work'}}))
  const wrapper=mount(TodoFolderNavigation,{props:{projects,selection:'project:p1'},global:{stubs:{SettingsDialogShell:shell}}})
  await flushPromises();await wrapper.findAll('.list-entry button').find(button=>button.text()==='工作清单')!.trigger('click')
  expect(wrapper.emitted('select')?.[0]).toEqual(['project:p1'])
  await wrapper.get('[aria-label="整理清单文件夹"]').trigger('click')
  vi.mocked(savePreference).mockRejectedValueOnce(new Error('offline'))
  await wrapper.get('[aria-label="生活清单的文件夹"]').setValue('work');await flushPromises()
  expect(wrapper.findAll('.list-entry')).toHaveLength(2)
  expect(wrapper.text()).toContain('尚未同步到账号')
  expect(JSON.parse(vi.mocked(savePreference).mock.calls[0]![1]).assignments).toEqual({p1:'work',p2:'work'})
  expect(projects.map(project=>project.id)).toEqual(['p1','p2']);wrapper.unmount()
})
it('紧凑工具栏把预设放入筛选弹窗，选择预设后明确应用才修改当前条件',async()=>{
  vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify([{id:'priority',name:'重要事项',filter:{priorities:['P0']}}]))
  const wrapper=mount(TodoOrganization,{props:{compact:true,modelValue:emptyTaskOrganizationFilter(),projects:[],tags:[]},global:{stubs:{SettingsDialogShell:shell}}})
  await flushPromises()
  expect(wrapper.find('[aria-label="已保存筛选"]').exists()).toBe(false)
  await wrapper.findAll('button').find(button=>button.text()==='筛选')!.trigger('click')
  const before=wrapper.emitted('update:modelValue')?.length || 0
  await wrapper.get('[aria-label="已保存筛选"]').setValue('priority')
  expect(wrapper.emitted('update:modelValue')?.length || 0).toBe(before)
  await wrapper.findAll('button').find(button=>button.text()==='应用筛选')!.trigger('click')
  const events=wrapper.emitted('update:modelValue')!
  expect(events[events.length-1]?.[0]).toMatchObject({priorities:['P0']})
  wrapper.unmount()
})
