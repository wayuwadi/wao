import { describe, expect, it } from 'vitest'
import { attendanceCaption, attendanceStatus } from '@/utils/attendancePresentation'
import type { AttendanceDay } from '@/api/attendance'
const day: AttendanceDay = { date:'2026-09-02', clockInAt:null, clockOutAt:null, dayType:'WORKDAY', anomaly:null, manualOverride:false, workHours:null, expectedWorkday:true, workWeekLabel:'工作日' }

describe('考勤日期状态', () => {
  it('过去应出勤缺卡标记整格，今天、未来、休息和请假不误报', () => {
    expect(attendanceStatus(day, '2026-09-03')).toBe('missing')
    expect(attendanceStatus(day, day.date)).toBe('')
    expect(attendanceStatus(day, '2026-09-01')).toBe('')
    expect(attendanceStatus(day, '2026-09-03', false)).toBe('')
    expect(attendanceStatus({...day, dayType:'REST'}, '2026-09-03')).toBe('')
    expect(attendanceStatus({...day, dayType:'LEAVE'}, '2026-09-03')).toBe('leave')
  })
  it('工时、请假和异常有文字，不只依赖颜色', () => {
    expect(attendanceCaption({...day, workHours:10.5}, '')).toBe('10.50 h')
    expect(attendanceCaption({...day, workHours:10.5}, 'anomaly')).toContain('异常')
    expect(attendanceCaption(day, 'missing')).toBe('缺卡')
    expect(attendanceCaption({...day, dayType:'LEAVE', manualOverride:true}, 'leave')).toBe('请假 · 模拟')
  })
})
