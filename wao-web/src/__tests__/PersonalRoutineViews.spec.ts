import { beforeEach,afterEach,it,expect,vi } from 'vitest'
import { mount,flushPromises,enableAutoUnmount } from '@vue/test-utils'
import { createPinia,setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useRoutinesStore } from '@/stores/routines'
import { fetchRoutines,saveRoutine,saveHabitLog,newRoutine,type RoutineState } from '@/api/personalRoutines'
import CountdownsView from '@/views/CountdownsView.vue'
import HabitsView from '@/views/HabitsView.vue'
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
vi.mock('@/api/personalRoutines',async original=>({...await original<object>(),fetchRoutines:vi.fn(),saveRoutine:vi.fn(),saveHabitLog:vi.fn()}))
vi.mock('@/api/importantDates',()=>({convertCalendarDate:vi.fn()}))
enableAutoUnmount(afterEach)
const global={stubs:{RouterLink:{props:['to'],template:'<a><slot /></a>'}}}
function state():RoutineState{return{today:'2026-10-29',month:'2026-10',items:[],summaries:[],logs:[]}}
beforeEach(()=>{vi.resetAllMocks();localStorage.clear();setActivePinia(createPinia());useSessionStore().accept({accountId:'me',member:{id:'me',name:'测试',initial:'测',role:'',color:''},identities:[],createdAt:''});vi.mocked(fetchRoutines).mockResolvedValue(state())})
it('创建日期失败保留表单，重试保存成功后关闭',async()=>{
 const wrapper=mount(CountdownsView,{global});await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='添加日期')!.trigger('click');await flushPromises()
 const form=document.querySelector('[role="dialog"]')!;const input=form.querySelector('input')!;input.value='纪念日验收';input.dispatchEvent(new Event('input',{bubbles:true}));await flushPromises()
 vi.mocked(saveRoutine).mockRejectedValueOnce(new Error('暂时离线')).mockImplementation(async i=>({...i,revision:1}))
 const save=()=>Array.from(form.querySelectorAll('button')).find(b=>b.textContent?.trim()==='保存')!.click()
 save();await flushPromises();expect(form.textContent).toContain('暂时离线');expect(useRoutinesStore().drafts.countdown?.name).toBe('纪念日验收')
 save();await flushPromises();expect(saveRoutine).toHaveBeenCalledTimes(2);expect(document.querySelector('[role="dialog"]')).toBeNull()
})
it('习惯数量目标打开日志，保存后显示已完成并可撤销',async()=>{
 const item={...newRoutine('habit','2026-10-29'),name:'喝水',target:3,unit:'杯'};const initial={...state(),items:[item]};vi.mocked(fetchRoutines).mockResolvedValue(initial)
 const wrapper=mount(HabitsView,{global});await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='今日打卡')!.trigger('click');await flushPromises()
 expect(document.querySelector('[role="dialog"]')?.textContent).toContain('当天目标 3');const store=useRoutinesStore();store.logDraft!.note='完成三杯'
 const log={...store.logDraft!,revision:1};vi.mocked(saveHabitLog).mockResolvedValue(log);vi.mocked(fetchRoutines).mockResolvedValue({...initial,logs:[log]})
 Array.from(document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')).find(b=>b.textContent?.trim()==='保存打卡')!.click();await flushPromises();expect(wrapper.text()).toContain('今天已完成 · 撤销');expect(wrapper.text()).toContain('完成三杯')
})
