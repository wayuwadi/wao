import { describe, expect, it } from 'vitest'
import { diagnoseReminder, isQuietTime } from '@/utils/notificationDiagnosis'
import type { NotificationSettings } from '@/api/notificationSettings'
import type { NotificationReminder } from '@/api/notificationReminders'
const settings: NotificationSettings = {
  canManageSystem: true,
  system: { enabled: true, reminders: true, quietHoursEnabled: true, quietStart: '22:00', quietEnd: '08:00', taskActivity: false, taskSuccess: false, aiRunSuccess: false, aiRunFailure: true, manualTask: true, deliveryFailure: true, automation: true },
  personal: { enabled: true, taskCreated: true, taskCompleted: true, aiRunSuccess: true, aiRunFailure: true, loginActivity: true },
}
const reminder: NotificationReminder = { id: 'r1', title: '午餐', content: '休息', time: '12:00', enabled: true, workdayOnly: false, lastResult: '尚未运行', nextFireAt: '2026-09-20T12:00:00' }
describe('每日提醒已保存配置诊断', () => {
  it.each([['21:59', false], ['22:00', true], ['00:00', true], ['07:59', true], ['08:00', false]])('跨午夜边界 %s', (time, expected) => {
    expect(isQuietTime(settings.system, time as string)).toBe(expected)
  })
  it('普通时段开始包含、结束不包含', () => {
    const policy = { ...settings.system, quietStart: '12:00', quietEnd: '13:00' }
    expect(isQuietTime(policy, '12:00')).toBe(true)
    expect(isQuietTime(policy, '13:00')).toBe(false)
  })
  it('同时解释各层阻断并给出修复位置', () => {
    const result = diagnoseReminder({ ...reminder, enabled: false, time: '23:00', workdayOnly: true }, {
      ...settings, canManageSystem: false, system: { ...settings.system, enabled: false, reminders: false }, personal: { ...settings.personal, enabled: false },
    })
    expect(result.map(x => x.target).filter(Boolean)).toEqual(['notification-system-enabled', 'notification-system-reminders', 'notification-quiet-hours', 'notification-personal'])
    const text = result.map(x => x.text).join(' ')
    expect(text).toContain('已暂停')
    expect(text).toContain('休息日跳过')
    expect(text).toContain('管理员维护')
    expect(text).not.toContain('下一计划检查')
  })
  it('可触发不等于已可见，展示计划与过滤限制', () => {
    const text = diagnoseReminder(reminder, settings).map(x => x.text).join(' ')
    expect(text).toContain('不代表已经执行')
    expect(text).toContain('2026-09-20 12:00:00')
    expect(text).toContain('普通每日提醒不会显示')
  })
  it('未知配置、无计划和异常时间不伪装正常', () => {
    expect(diagnoseReminder(reminder)[0]?.text).toContain('无法判断')
    const text = diagnoseReminder({ ...reminder, time: '--:--', nextFireAt: null }, settings).map(x => x.text).join(' ')
    expect(text).toContain('时间无法识别')
    expect(text).toContain('暂无下一计划时间')
  })
})
