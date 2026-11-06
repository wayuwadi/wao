import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TaskTimeSettings from '@/components/task/TaskTimeSettings.vue'
import { emptyTaskTimeRule, type TaskTimeState } from '@/api/taskTime'

const api=vi.hoisted(()=>({get:vi.fn(),save:vi.fn(),skip:vi.fn(),retry:vi.fn(),test:vi.fn()}))
vi.mock('@/api/taskTime',()=>({
  fetchTaskTime:api.get,saveTaskTime:api.save,skipTaskOccurrence:api.skip,retryTaskTime:api.retry,testTaskReminder:api.test,
  emptyTaskTimeRule:()=>({revision:0,repeat:'none',interval:1,mode:'scheduled',anchor:'start',until:'',reminderTime:'09:00',remindBefore:[]}),
}))
let session:ReturnType<typeof reactive<{account:{accountId:string};userId:string}>>
const task={id:'t1',title:'测试任务',owner:'me',start:'2026-11-06',due:'2026-11-07',editVersion:'v1'}
const data={taskById:()=>task,flushTask:vi.fn(),taskSaveState:()=> 'saved',hydrate:vi.fn()}
vi.mock('@/stores/session',()=>({useSessionStore:()=>session}))
vi.mock('@/stores/data',()=>({useDataStore:()=>data}))
vi.mock('@/stores/ui',()=>({useUiStore:()=>({openTask:vi.fn()})}))
function state(revision=0):TaskTimeState{return{rule:{...emptyTaskTimeRule(),revision},editable:true,reason:'',nextTaskId:null,nextDate:null,nextReminder:null,lastDeliveredAt:null,error:'',schedulerEnabled:true}}
const button=(w:VueWrapper,text:string)=>w.findAll('button').find(b=>b.text().includes(text))!
async function setup(){const w=mount(TaskTimeSettings,{props:{taskId:'t1'},global:{stubs:{SettingsDialogShell:{template:'<div role="dialog"><slot/><footer><slot name="footer"/></footer></div>'}}}});await button(w,'重复与提醒').trigger('click');await flushPromises();return w}
beforeEach(()=>{localStorage.clear();vi.clearAllMocks();session=reactive({account:{accountId:'a'},userId:'me'});api.get.mockResolvedValue(state());api.save.mockImplementation((_id,r)=>Promise.resolve({...state(r.revision+1),rule:{...r,revision:r.revision+1}}));data.flushTask.mockResolvedValue(undefined);data.hydrate.mockResolvedValue(undefined)})

describe('任务时间规则',()=>{
  it('保存失败后保留草稿，重开恢复同一修订号',async()=>{
    const w=await setup();await w.findAll('select')[1]!.setValue('daily');await w.find('input[type=number]').setValue(3)
    api.save.mockRejectedValue(new Error('离线'));await button(w,'保存时间规则').trigger('click');await flushPromises();expect(w.text()).toContain('离线')
    await button(w,'关闭').trigger('click');await button(w,'重复与提醒').trigger('click');await flushPromises()
    expect((w.find('input[type=number]').element as HTMLInputElement).value).toBe('3')
    expect(JSON.parse(localStorage.getItem('wao-task-time-draft:a:me:t1')!).revision).toBe(0)
    w.unmount()
  })
  it('冲突先读取账号版本，明确保留草稿才更新修订号',async()=>{
    const w=await setup();await w.findAll('select')[1]!.setValue('weekly')
    api.save.mockRejectedValueOnce({response:{status:409,data:{message:'版本冲突'}}});api.get.mockResolvedValue(state(4))
    await button(w,'保存时间规则').trigger('click');await flushPromises();expect(w.text()).toContain('账号设置已有变化')
    expect(button(w,'保存时间规则').attributes('disabled')).toBeDefined()
    await button(w,'保留草稿').trigger('click');await button(w,'保存时间规则').trigger('click');await flushPromises()
    expect(api.save.mock.lastCall?.[1]).toMatchObject({revision:4,repeat:'weekly'});w.unmount()
  })
  it('账号切换忽略旧保存回执，不清除原账号草稿',async()=>{
    const w=await setup();await w.findAll('select')[1]!.setValue('daily')
    let resolve!:(value:TaskTimeState)=>void;api.save.mockImplementation(()=>new Promise<TaskTimeState>(r=>{resolve=r}))
    await button(w,'保存时间规则').trigger('click');await flushPromises();session.userId='other';await nextTick();resolve(state(1));await flushPromises()
    expect(w.find('[role=dialog]').exists()).toBe(false);expect(localStorage.getItem('wao-task-time-draft:a:me:t1')).not.toBeNull();w.unmount()
  })
  it('跳过需显式确认并提交任务和规则版本',async()=>{
    api.get.mockResolvedValue({...state(2),rule:{...emptyTaskTimeRule(),revision:2,repeat:'daily'}});api.skip.mockResolvedValue({...state(2),editable:false,nextTaskId:'next',reason:'本次已结束'})
    const w=await setup();await button(w,'跳过本次').trigger('click');expect(api.skip).not.toHaveBeenCalled()
    await button(w,'确认跳过本次').trigger('click');await flushPromises();expect(api.skip).toHaveBeenCalledWith('t1',2,'v1');expect(w.text()).toContain('打开下一次任务');w.unmount()
  })
  it('读取失败不呈现可写的空规则，关闭不覆盖已有草稿',async()=>{
    const key='wao-task-time-draft:a:me:t1',original=JSON.stringify({...emptyTaskTimeRule(),repeat:'weekly',interval:3})
    localStorage.setItem(key,original);api.get.mockRejectedValue(new Error('读取失败'))
    const w=await setup();expect(w.text()).toContain('读取失败');expect(button(w,'保存时间规则').attributes('disabled')).toBeDefined()
    await button(w,'关闭').trigger('click');expect(localStorage.getItem(key)).toBe(original);w.unmount()
  })
  it('下一次任务读取失败保持窗口并给出重试入口',async()=>{
    api.get.mockResolvedValue({...state(2),editable:false,nextTaskId:'next',reason:'本次已结束'})
    const w=await setup();data.hydrate.mockRejectedValueOnce(new Error('离线'))
    await button(w,'打开下一次任务').trigger('click');await flushPromises()
    expect(w.find('[role=dialog]').exists()).toBe(true);expect(w.text()).toContain('下一次任务读取失败：离线')
    await button(w,'打开下一次任务').trigger('click');await flushPromises();expect(w.find('[role=dialog]').exists()).toBe(false);w.unmount()
  })
})
