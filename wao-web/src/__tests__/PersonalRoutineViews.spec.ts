import { beforeEach,afterEach,it,expect,vi } from 'vitest'
import { mount,flushPromises,enableAutoUnmount } from '@vue/test-utils'
import { createPinia,setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useRoutinesStore } from '@/stores/routines'
import { usePreferenceStore } from '@/stores/preferences'
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

it.each(['countdown','habit'] as const)('取消添加 %s 后没有草稿提示，再次打开为空表单',async kind=>{
 const wrapper=mount(kind==='habit'?HabitsView:CountdownsView,{global});await flushPromises()
 const add=()=>wrapper.findAll('button').find(b=>b.text()===(kind==='habit'?'添加习惯':'添加日期'))!
 await add().trigger('click');await flushPromises()
 const store=useRoutinesStore();store.drafts[kind]!.name='未确认内容';await flushPromises()
 expect(wrapper.find('.draft-banner').exists()).toBe(false)
 Array.from(document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')).find(b=>b.textContent?.trim()==='取消')!.click();await flushPromises()
 expect(store.drafts[kind]).toBeUndefined();expect(wrapper.find('.draft-banner').exists()).toBe(false)
 expect(saveRoutine).not.toHaveBeenCalled()
 await add().trigger('click');await flushPromises();expect(store.drafts[kind]?.name).toBe('')
 wrapper.unmount();expect(store.drafts[kind]).toBeUndefined()
})

it('习惯月历跟随周起始偏好，切换后日期、完成记录和日志草稿不变',async()=>{
 const item={...newRoutine('habit','2026-10-01'),name:'阅读',revision:1}
 const log={habitId:item.id,date:'2026-10-01',revision:1,amount:1,target:1,note:'读了一章'}
 vi.mocked(fetchRoutines).mockResolvedValue({...state(),items:[item],logs:[log]})
 const preferences=usePreferenceStore();preferences.weekStart='周一'
 const wrapper=mount(HabitsView,{global});await flushPromises()
 expect(wrapper.findAll('.calendar-grid small').map(day=>day.text())).toEqual(['一','二','三','四','五','六','日'])
 expect(wrapper.findAll('.calendar-grid button')[0]!.attributes('aria-label')).toBe('2026-09-28 打卡详情')
 expect(wrapper.findAll('.calendar-grid button')[3]!.attributes('aria-label')).toBe('2026-10-01 打卡详情')
 const store=useRoutinesStore();store.logDraft={...log,note:'尚未保存的日志'}
 preferences.weekStart='周日';await flushPromises()
 expect(wrapper.findAll('.calendar-grid small').map(day=>day.text())).toEqual(['日','一','二','三','四','五','六'])
 expect(wrapper.findAll('.calendar-grid button')[0]!.attributes('aria-label')).toBe('2026-09-27 打卡详情')
 expect(wrapper.findAll('.calendar-grid button')[4]!.classes()).toContain('done')
 expect(store.logDraft.note).toBe('尚未保存的日志')
 expect(saveHabitLog).not.toHaveBeenCalled();expect(saveRoutine).not.toHaveBeenCalled()
})

it('其他分组只显示未分组及其他习惯，清除空筛选保留已有编辑草稿',async()=>{
 const base=newRoutine('habit','2026-10-01')
 const habits=[{...base,id:'empty',name:'随手记录',group:'',revision:1},{...base,id:'other',name:'小目标',group:'其他',revision:1},{...base,id:'health',name:'散步',group:'健康',revision:1}]
 vi.mocked(fetchRoutines).mockResolvedValue({...state(),items:habits})
 const wrapper=mount(HabitsView,{global});await flushPromises()
 const store=useRoutinesStore();store.edit({...habits[0]!,note:'编辑中的备注'})
 const group=wrapper.get<HTMLSelectElement>('[aria-label="习惯分组筛选"]')
 expect(group.findAll('option').filter(option=>option.text()==='其他')).toHaveLength(1)
 await group.setValue('其他')
 expect(wrapper.findAll('.habit-name strong').map(name=>name.text())).toEqual(['随手记录','小目标'])
 await wrapper.get('[aria-label="习惯状态筛选"]').setValue('archived')
 expect(wrapper.get('.wao-empty-state').text()).toContain('没有符合条件的习惯')
 await wrapper.get('.wao-empty-state button').trigger('click')
 expect(wrapper.findAll('.habit-row')).toHaveLength(3)
 expect(store.drafts.habit?.note).toBe('编辑中的备注')
 expect(saveRoutine).not.toHaveBeenCalled()
})

it('倒数日搜索忽略首尾空白，组合筛选无结果时可清除且不改草稿',async()=>{
 const base=newRoutine('countdown','2026-10-29')
 const dates=[{...base,id:'trip',name:'Trip 出发',category:'countdown',revision:1},{...base,id:'birth',name:'生日',category:'birthday',revision:1}]
 vi.mocked(fetchRoutines).mockResolvedValue({...state(),items:dates})
 const wrapper=mount(CountdownsView,{global});await flushPromises()
 const store=useRoutinesStore();store.edit({...dates[0]!,note:'保留日期备注'})
 await wrapper.get('[aria-label="搜索倒数日"]').setValue('  tRiP  ')
 expect(wrapper.findAll('.date-card')).toHaveLength(1)
 expect(wrapper.get('.date-card').text()).toContain('Trip 出发')
 await wrapper.get('[aria-label="日期类型筛选"]').setValue('birthday')
 await wrapper.get('[aria-label="日期状态筛选"]').setValue('trash')
 expect(wrapper.get('.wao-empty-state').text()).toContain('没有符合条件的日期')
 await wrapper.get('.wao-empty-state button').trigger('click')
 expect(wrapper.findAll('.date-card')).toHaveLength(2)
 expect(wrapper.get<HTMLInputElement>('[aria-label="搜索倒数日"]').element.value).toBe('')
 expect(wrapper.get<HTMLSelectElement>('[aria-label="日期状态筛选"]').element.value).toBe('active')
 expect(store.drafts.countdown?.note).toBe('保留日期备注')
 expect(saveRoutine).not.toHaveBeenCalled()
})

it.each([CountdownsView,HabitsView])('日期与习惯读取失败不显示空数据引导',async view=>{
 vi.mocked(fetchRoutines).mockRejectedValue(new Error('读取暂时失败'))
 const wrapper=mount(view,{global});await flushPromises()
 expect(wrapper.get('[role="alert"]').text()).toContain('读取暂时失败')
 expect(wrapper.find('.wao-empty-state').exists()).toBe(false)
 expect(wrapper.find('[role="status"]').exists()).toBe(false)
})
