import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { capturePayload, emptyCapture, recognizeTaskTime } from '@/utils/taskCapture'
import TaskCaptureFields from '@/components/task/TaskCaptureFields.vue'
import { useDataStore } from '@/stores/data'
beforeEach(()=>setActivePinia(createPinia()))
describe('快速记录时间识别',()=>{
  it('识别中文日期时间并保持截止日期独立',()=>{
    const result=recognizeTaskTime('明天下午三点交周报','2026-11-06')!
    expect(result).toMatchObject({start:'2026-11-07',startAt:'2026-11-07T15:00',endAt:'2026-11-07T16:00',title:'交周报'})
    expect(capturePayload({...emptyCapture(),...result})).toMatchObject({due:'',start:'2026-11-07',startAt:'2026-11-07T15:00'})
  })
  it('跨月、跨年、半点与周末按上海日历语义识别',()=>{
    expect(recognizeTaskTime('后天晚上十一点半值班','2026-12-30')).toMatchObject({start:'2027-01-01',endAt:'2027-01-02T00:30'})
    expect(recognizeTaskTime('下周一复盘','2026-11-08')?.start).toBe('2026-11-09')
    expect(recognizeTaskTime('周日散步','2026-11-06')?.start).toBe('2026-11-08')
    expect(recognizeTaskTime('周天散步','2026-11-06')?.start).toBe('2026-11-08')
    expect(recognizeTaskTime('明天下午三点二十五分开会','2026-11-06')?.startAt).toBe('2026-11-07T15:25')
    expect(recognizeTaskTime('今天上午十二点发布','2026-11-06')?.startAt).toBe('2026-11-06T00:00')
  })
  it('拒绝不存在的日期与时段，普通标题不自动安排',()=>{
    expect(recognizeTaskTime('2026-02-30计划','2026-11-06')).toBeNull()
    expect(recognizeTaskTime('明天25:00开会','2026-11-06')).toBeNull()
    expect(recognizeTaskTime('明天3:99开会','2026-11-06')).toBeNull()
    expect(recognizeTaskTime('提交周报','2026-11-06')).toBeNull()
    expect(capturePayload(emptyCapture())).toMatchObject({due:'',start:'',startAt:null,endAt:null})
    expect(()=>capturePayload({...emptyCapture(),startAt:'2026-11-06T15:00',endAt:'2026-11-06T14:00'})).toThrow('结束时间')
  })
  it('预览不修改数据，忽略保留原文，采用后显式更新模型',async()=>{
    useDataStore().today='2026-11-06'
    const wrapper=mount(TaskCaptureFields,{props:{title:'明天下午三点交周报',modelValue:emptyCapture()}})
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await wrapper.findAll('button').find(b=>b.text()==='忽略')!.trigger('click')
    expect(wrapper.find('.time-preview').exists()).toBe(false)
    expect(wrapper.emitted('title')).toBeUndefined()
    await wrapper.setProps({title:'后天下午三点交周报'})
    await wrapper.findAll('button').find(b=>b.text()==='采用')!.trigger('click')
    expect(wrapper.emitted('title')?.[0]).toEqual(['交周报'])
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toMatchObject({start:'2026-11-08',startAt:'2026-11-08T15:00',due:''})
    wrapper.unmount()
  })
  it('手动编辑发出新对象，适配全局弹窗计算属性且不直接篡改传入模型',async()=>{
    const original=emptyCapture()
    const wrapper=mount(TaskCaptureFields,{props:{title:'普通任务',modelValue:original}})
    await wrapper.get('input[type=date]').setValue('2026-11-09')
    expect(original.due).toBe('')
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toMatchObject({due:'2026-11-09'})
    wrapper.unmount()
  })
  it('已保存任务锁定属性但保留标签重试入口；设计演示仅显示传入的合成清单',async()=>{
    const wrapper=mount(TaskCaptureFields,{props:{title:'明天交周报',modelValue:emptyCapture(),saved:true,projects:[{id:'demo',name:'合成清单'}]}})
    expect(wrapper.find('.time-preview').exists()).toBe(false)
    expect(wrapper.get('[role=status]').text()).toContain('任务已保存')
    expect(wrapper.findAll('select')[0]!.findAll('option').map(o=>o.text())).toEqual(['收集箱','合成清单'])
    for(const input of wrapper.findAll('select,input[type=date],input[type=datetime-local]')) expect(input.attributes('disabled')).toBeDefined()
    await wrapper.get('input[placeholder="用逗号分隔"]').setValue('待同步')
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toMatchObject({tags:'待同步'})
    wrapper.unmount()
  })
})
