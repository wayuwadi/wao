import { beforeEach, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import FocusView from '@/views/FocusView.vue'
import { useFocusStore } from '@/stores/focus'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import { fetchFocus } from '@/api/focus'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { focusTask: 'shared', focusSub: 'child' } }) }))
vi.mock('@/api/focus', async original => ({ ...await original<object>(), fetchFocus: vi.fn(), startFocus: vi.fn(), changeFocus: vi.fn() }))
beforeEach(() => {
 localStorage.clear(); setActivePinia(createPinia())
 useSessionStore().accept({ accountId:'a',member:{id:'me',name:'成员',initial:'测',role:'',color:''},identities:[],createdAt:'' })
 const data=seedDataStore();data.tasks=[{ ...data.tasks[0]!,id:'shared',title:'已授权共享任务',owner:'other',parts:[],subs:[{id:'child',text:'共享子任务',done:false}] }]
 vi.mocked(fetchFocus).mockResolvedValue({serverNow:Date.now(),active:null,history:[],stats:{todayCount:0,todayMs:0,totalCount:0,totalMs:0}})
})
it('从子任务入口预选共享子任务，并把笔记和任务引用交给专注流程', async () => {
 const focus=useFocusStore(), start=vi.spyOn(focus,'start').mockResolvedValue()
 const wrapper=mount(FocusView,{global:{stubs:{RouterLink:true}}});await flushPromises()
 expect(wrapper.get('[aria-label="选择关联任务"]').text()).toContain('共享子任务')
 await wrapper.get('textarea').setValue('开始前笔记')
 await wrapper.findAll('button').find(b=>b.text()==='开始')!.trigger('click')
 expect(start).toHaveBeenCalledWith({mode:'pomodoro',minutes:25,taskId:'shared',subId:'child'});expect(focus.note).toBe('开始前笔记')
 await wrapper.findAll('button').find(b=>b.text()==='正计时')!.trigger('click')
 expect(wrapper.get('[role="timer"]').text()).toContain('00:00');wrapper.unmount()
})

it('计时各状态保留相同区域和按钮节点，只显示最近五条记录', async () => {
 const focus=useFocusStore()
 const wrapper=mount(FocusView,{global:{stubs:{RouterLink:true}}});await flushPromises()
 const session={id:'test',mode:'pomodoro' as const,status:'running' as const,revision:1,taskId:'shared',subId:'child',taskTitle:'共享子任务',note:'',noteId:null,startedAt:Date.now(),runningSince:Date.now(),elapsedMs:0,plannedMs:1500000,endedAt:0}
 focus.history=Array.from({length:9},(_,index)=>({...session,id:String(index),status:'finished' as const,taskTitle:`记录${index}`}))
 await flushPromises();expect(wrapper.findAll('.focus-history li')).toHaveLength(5)
 const dial=wrapper.get('.dial-space').element, picker=wrapper.get('.task-picker').element, actions=wrapper.get('.focus-actions').element, note=wrapper.get('textarea').element
 focus.active=session;await flushPromises();expect(wrapper.get('.main-action').text()).toBe('暂停')
 focus.active={...session,status:'paused'};await flushPromises();expect(wrapper.get('.main-action').text()).toBe('继续专注')
 focus.active=null;await flushPromises();expect(wrapper.get('.main-action').text()).toBe('开始')
 expect(wrapper.get('.dial-space').element).toBe(dial);expect(wrapper.get('.task-picker').element).toBe(picker);expect(wrapper.get('.focus-actions').element).toBe(actions);expect(wrapper.get('textarea').element).toBe(note)
 expect(wrapper.get('.end-action').classes()).toContain('action-placeholder');wrapper.unmount()
})
it('任务浮层支持搜索并选择，关闭后仍保留选择', async () => {
 const wrapper=mount(FocusView,{global:{stubs:{RouterLink:true}}});await flushPromises()
 await wrapper.get('[aria-label="选择关联任务"]').trigger('click')
 await wrapper.get('[aria-label="搜索关联任务"]').setValue('已授权')
 await wrapper.findAll('.task-options button').find(item=>item.text()==='已授权共享任务')!.trigger('click')
 expect(wrapper.find('.task-popover').exists()).toBe(false)
 expect(wrapper.get('[aria-label="选择关联任务"]').text()).toContain('已授权共享任务');wrapper.unmount()
})
