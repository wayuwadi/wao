import { beforeEach, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import FocusView from '@/views/FocusView.vue'
import { useFocusStore } from '@/stores/focus'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import { fetchFocus } from '@/api/focus'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { task: 'shared', sub: 'child' } }) }))
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
 expect(wrapper.get<HTMLSelectElement>('select').element.value).toBe('sub:shared:child')
 await wrapper.get('textarea').setValue('开始前笔记')
 await wrapper.findAll('button').find(b=>b.text()==='开始')!.trigger('click')
 expect(start).toHaveBeenCalledWith({mode:'pomodoro',minutes:25,taskId:'shared',subId:'child'});expect(focus.note).toBe('开始前笔记')
 await wrapper.findAll('button').find(b=>b.text()==='正计时')!.trigger('click')
 expect(wrapper.get('[role="timer"]').text()).toContain('00:00');wrapper.unmount()
})
