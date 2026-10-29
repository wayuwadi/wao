import { beforeEach,it,expect,vi } from 'vitest'
import { createPinia,setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useSessionStore } from '@/stores/session'
import { useRoutinesStore } from '@/stores/routines'
import { fetchRoutines,saveRoutine,saveHabitLog,newRoutine,habitScheduled,type RoutineState } from '@/api/personalRoutines'
vi.mock('@/api/personalRoutines',async original=>({...await original<object>(),fetchRoutines:vi.fn(),saveRoutine:vi.fn(),saveHabitLog:vi.fn()}))
function login(id='me'){useSessionStore().accept({accountId:id,member:{id,name:id,initial:'测',role:'',color:''},identities:[],createdAt:''})}
function state():RoutineState{return{today:'2026-10-29',month:'2026-10',items:[],summaries:[],logs:[]}}
beforeEach(()=>{vi.resetAllMocks();localStorage.clear();setActivePinia(createPinia());login();vi.mocked(fetchRoutines).mockResolvedValue(state())})
it('成员切换隔离草稿并丢弃在途响应',async()=>{
 const store=useRoutinesStore();let resolve!:(s:RoutineState)=>void
 vi.mocked(fetchRoutines).mockImplementationOnce(()=>new Promise(r=>resolve=r));const pending=store.load()
 store.edit({...newRoutine('habit','2026-10-29'),name:'甲的草稿'});login('other');resolve({...state(),items:[{...newRoutine('habit','2026-10-29'),name:'甲的数据'}]});await pending
 expect(store.state).toBeNull();expect(store.drafts.habit).toBeUndefined();expect(localStorage.getItem('wao-routines-drafts:me:me')).toContain('甲的草稿');expect(localStorage.getItem('wao-routines-drafts:other:other')||'').not.toContain('甲')
 login();expect(store.drafts.habit?.name).toBe('甲的草稿')
})
it('冲突后保留本机草稿，刷新云端版本不覆盖输入，重试仍使用原编号',async()=>{
 const store=useRoutinesStore(),draft={...newRoutine('countdown','2026-10-29'),name:'本机内容',revision:1};store.edit(draft)
 vi.mocked(fetchRoutines).mockResolvedValue({...state(),items:[{...draft,name:'云端内容',revision:2}]});vi.mocked(saveRoutine).mockRejectedValue(new Error('记录已更新'))
 expect(await store.save(store.drafts.countdown!,true)).toBe(false);expect(store.drafts.countdown?.name).toBe('本机内容');expect(store.item(draft.id)?.revision).toBe(2)
 expect(store.error).toBe('记录已更新');expect(JSON.parse(localStorage.getItem('wao-routines-drafts:me:me')!).drafts.countdown.id).toBe(draft.id)
})
it('归档其他记录不删除未完成表单；打卡保存失败保留数量和日志',async()=>{
 const store=useRoutinesStore(),draft=newRoutine('habit','2026-10-29');draft.name='未完成';store.edit(draft)
 const other={...draft,id:'b'.repeat(32),name:'另一项',status:'archived' as const};vi.mocked(saveRoutine).mockResolvedValue(other);await store.save(other)
 expect(store.drafts.habit?.name).toBe('未完成')
 store.logDraft={habitId:draft.id,date:'2026-10-29',revision:0,amount:3,target:3,note:'喝水记录'};vi.mocked(saveHabitLog).mockRejectedValue(new Error('离线'))
 expect(await store.saveLog()).toBe(false);expect(store.logDraft?.note).toBe('喝水记录');expect(store.logDraft?.amount).toBe(3)
})
it('过期读取不覆盖保存后的新状态',async()=>{
 const store=useRoutinesStore();let resolve!:(s:RoutineState)=>void;vi.mocked(fetchRoutines).mockImplementationOnce(()=>new Promise(r=>resolve=r));const pending=store.load()
 const item={...newRoutine('countdown','2026-10-29'),name:'已保存'};vi.mocked(saveRoutine).mockResolvedValue({...item,revision:1});vi.mocked(fetchRoutines).mockResolvedValue({...state(),items:[item]})
 await store.save(item);resolve(state());await pending;await flushPromises();expect(store.state?.items[0]?.name).toBe('已保存')
})
it('星期、间隔与结束日期按日历计算',()=>{
 const item=newRoutine('habit','2026-10-23');item.weekdays=[1,3,5];expect(habitScheduled(item,'2026-10-24')).toBe(false);expect(habitScheduled(item,'2026-10-26')).toBe(true)
 item.frequency='interval';item.intervalDays=3;item.durationDays=7;expect(habitScheduled(item,'2026-10-29')).toBe(true);expect(habitScheduled(item,'2026-11-01')).toBe(false)
})
