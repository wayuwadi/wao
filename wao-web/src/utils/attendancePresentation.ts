import type { AttendanceDay, AttendanceLeave } from '@/api/attendance'

export function leaveDescription(leave: AttendanceLeave) {
  const duration = leave.duration === null ? '' : ` · ${leave.duration} ${leave.durationUnit || ''}`
  return `${leave.title}${duration} · ${leave.startAt.replace('T', ' ')} 至 ${leave.endAt.replace('T', ' ')}`
}

export function attendanceStatus(day: AttendanceDay, today: string, expectedWorkday = day.expectedWorkday) {
  if (day.dayType === 'LEAVE') return 'leave'
  if (day.date > today) return ''
  if (day.anomaly && !/^(未打卡|缺上班卡|缺下班卡)$/.test(day.anomaly)) return 'anomaly'
  // 尚未结束的当天、休息日不因打卡尚未齐全而报警。
  if (expectedWorkday && day.dayType !== 'REST' && day.date < today && (!day.clockInAt || !day.clockOutAt)) return 'missing'
  return ''
}

export function attendanceCaption(day: AttendanceDay, status: string) {
  const manual = day.manualOverride ? ' · 模拟' : ''
  if (day.dayType === 'LEAVE') return `${[...new Set(day.leaves?.map(leave => leave.title) || [])].join('、') || '请假'}${manual}`
  if (day.workHours !== null) return `${day.workHours.toFixed(2)} h${status === 'anomaly' ? ' · 异常' : ''}${manual}`
  if (status === 'missing') return `缺卡${manual}`
  if (status === 'anomaly') return `异常${manual}`
  return ''
}
