import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AttendanceTrend from '@/components/calendar/AttendanceTrend.vue'
import type { AttendanceDay } from '@/api/attendance'
const day=(date:string, hours:number|null, dayType:AttendanceDay['dayType']='WORKDAY'):AttendanceDay=>({date,clockInAt:hours===null?null:`${date}T09:00`,clockOutAt:hours===null?null:`${date}T21:00`,workHours:hours,dayType,manualOverride:false,anomaly:null,expectedWorkday:true,workWeekLabel:'工作日'})
describe('工时趋势',()=>{
  it('请假和缺卡保留空档，单个点可通过键盘读取，周均排除无出勤周',async()=>{
    const wrapper=mount(AttendanceTrend,{props:{days:[day('2026-09-01',11),day('2026-09-02',null,'LEAVE'),day('2026-09-03',9),day('2026-09-04',null)],weeks:[{from:'2026-09-01',to:'2026-09-07',label:'工作日',averageHours:10,validDays:2},{from:'2026-09-08',to:'2026-09-14',label:'工作日',averageHours:0,validDays:0}]}})
    expect(wrapper.findAll('.trend-point')).toHaveLength(2)
    expect(wrapper.findAll('.trend-line')).toHaveLength(2)
    await wrapper.find('.trend-point').trigger('focus')
    expect(wrapper.find('.trend-reading').text()).toContain('11.00')
    await wrapper.findAll('.trend-modes button')[1]!.trigger('click')
    expect(wrapper.findAll('.trend-point')).toHaveLength(1)
    expect(wrapper.find('.trend-reading').text()).toContain('10.00')
    await wrapper.setProps({days:[],weeks:[]})
    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无完整工时记录')
  })
})
