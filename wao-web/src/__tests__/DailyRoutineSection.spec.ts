import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
const api = vi.hoisted(()=>({read:vi.fn(),rule:vi.fn(),mark:vi.fn()}))
vi.mock('@/api/routines',()=>({fetchRoutines:api.read,writeRoutine:api.rule,writeOccurrence:api.mark}))
import DailyRoutineSection from '@/components/todo/DailyRoutineSection.vue'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'

const occurrence={templateId:'fbdc7d69-4267-4329-b5d4-e7be1e673c96',revisionId:'r',title:'验收固定事项',period:'MORNING',sortOrder:0,status:'OPEN',version:0,recordedAt:null,virtual:true}
function fixture(date='2026-10-03',status='OPEN') {return{from:date,to:date,templates:[],days:[{date,occurrences:[{...occurrence,status}]}]}}
describe('每日固定事项',()=>{
  beforeEach(()=>{localStorage.clear();vi.clearAllMocks();setActivePinia(createPinia());useDataStore().today='2026-10-03';useSessionStore().accept({accountId:'a',member:{id:'wg',name:'验收',initial:'验',color:'#4358D6',role:'成员'},identities:[],createdAt:''});api.read.mockResolvedValue(fixture());api.mark.mockResolvedValue({operationId:'op',occurrence:{...occurrence,status:'DONE',version:1}})})
  const morning=async(w:ReturnType<typeof mount>)=>{await flushPromises();await w.find('button[aria-pressed]').trigger('click');await flushPromises()}
  it('今日一次动作写独立实例，跳过不冒充完成，完成可恢复',async()=>{
    const w=mount(DailyRoutineSection,{props:{date:'2026-10-03'}});await morning(w)
    api.read.mockResolvedValue(fixture('2026-10-03','DONE'))
    await w.get('button[aria-label="完成固定事项：验收固定事项"]').trigger('click');await flushPromises()
    expect(api.mark).toHaveBeenCalledWith(occurrence.templateId,'2026-10-03',expect.any(String),{expectedVersion:0,status:'DONE'})
    api.read.mockResolvedValue(fixture('2026-10-03','OPEN'))
    await w.findAll('button').find(b=>b.text()==='恢复未做')!.trigger('click');await flushPromises()
    expect(api.mark.mock.calls[api.mark.mock.calls.length-1]![3].status).toBe('OPEN')
  })
  it('过去日只读，未来日不能提前完成',async()=>{
    api.read.mockResolvedValue(fixture('2026-10-02'))
    const w=mount(DailyRoutineSection,{props:{date:'2026-10-02'}});await morning(w)
    expect(w.get('button[aria-label="完成固定事项：验收固定事项"]').attributes('disabled')).toBeDefined()
    api.read.mockResolvedValue(fixture('2026-10-04'));await w.setProps({date:'2026-10-04'});await flushPromises()
    expect(w.text()).toContain('完成与跳过留给当天');expect(api.mark).not.toHaveBeenCalled()
  })
  it('读取失败明确局部报错，重试后显示真实空状态',async()=>{
    api.read.mockRejectedValueOnce(new Error('连接中断'));const w=mount(DailyRoutineSection,{props:{date:'2026-10-03'}});await flushPromises()
    expect(w.get('[role=alert]').text()).toContain('连接中断');expect(w.text()).not.toContain('这个时段还没有固定事项')
    api.read.mockResolvedValue({...fixture(),days:[{date:'2026-10-03',occurrences:[]}]});await w.findAll('button').find(b=>b.text()==='重新加载')!.trigger('click');await flushPromises();expect(w.text()).toContain('这个时段还没有固定事项')
  })
  it('规则草稿按账号保留，收起再展开仍恢复',async()=>{
    const w=mount(DailyRoutineSection,{props:{date:'2026-10-03'}});await flushPromises();await w.findAll('button').find(b=>b.text()==='管理')!.trigger('click')
    await w.get('input[aria-label="固定事项标题"]').setValue('尚未保存的早晨事项');await w.findAll('button').find(b=>b.text()==='收起管理')!.trigger('click');await w.findAll('button').find(b=>b.text()==='管理')!.trigger('click')
    expect((w.get('input[aria-label="固定事项标题"]').element as HTMLInputElement).value).toBe('尚未保存的早晨事项');expect(api.rule).not.toHaveBeenCalled()
  })
})
