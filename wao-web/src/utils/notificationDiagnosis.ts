import type { NotificationReminder } from '@/api/notificationReminders'
import type { NotificationSettings, SystemNotificationPolicy } from '@/api/notificationSettings'

export function isQuietTime(policy: SystemNotificationPolicy, time: string): boolean {
  if (!policy.quietHoursEnabled) return false
  return policy.quietStart < policy.quietEnd
    ? time >= policy.quietStart && time < policy.quietEnd
    : time >= policy.quietStart || time < policy.quietEnd
}

type Finding = { text: string; target?: string; action?: string }

/** Read-only explanation of saved daily reminder rules; never predicts delivery success. */
export function diagnoseReminder(reminder: NotificationReminder | undefined, settings?: NotificationSettings): Finding[] {
  if (!reminder || !settings) return [{ text: '配置尚未完整读取，无法判断；请重新加载通知设置。' }]
  const findings: Finding[] = []
  const policy = settings.system
  if (!reminder.enabled) findings.push({ text: '本条提醒已暂停，不会自动触发；需要时启用本条提醒并保存。' })
  if (!policy.enabled) findings.push({ text: '系统通知总开关已关闭，执行时会跳过。', target: 'notification-system-enabled', action: '检查总开关' })
  if (!policy.reminders) findings.push({ text: '站内提醒类型已关闭，执行时会跳过。', target: 'notification-system-reminders', action: '检查提醒类型' })
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(reminder.time)) {
    findings.push({ text: '提醒时间无法识别，请由管理员检查并保存有效时间。' })
  } else if (isQuietTime(policy, reminder.time)) {
    findings.push({ text: `已保存的 ${reminder.time} 落在沉默时段 ${policy.quietStart}–${policy.quietEnd}；届时会跳过，不补发。`, target: 'notification-quiet-hours', action: '检查沉默时段' })
  }
  if (!findings.length) findings.push({ text: '已保存的开关与时段允许触发；这不代表已经执行或通知已可见。' })
  if (reminder.enabled) findings.push({ text: reminder.nextFireAt
    ? `下一计划检查：${reminder.nextFireAt.replace('T', ' ')}（上海时间）。计划时间不代表发送成功。`
    : '暂无下一计划时间；请由管理员检查提醒配置和调度状态。' })
  if (reminder.workdayOnly) findings.push({ text: '自动执行时还会检查中国工作日，休息日跳过；手动试发不受工作日限制。' })
  findings.push({ text: '通过策略检查并执行成功后保存为 WAO 站内记录，不发送到飞书群。当前通知中心仅展示 AI 结果和提及，普通每日提醒不会显示。' })
  if (!settings.personal.enabled) findings.push({ text: '你的站内通知中心已关闭，不显示动态或未读数。', target: 'notification-personal', action: '检查个人偏好' })
  if (!settings.canManageSystem) findings.push({ text: '系统策略与提醒由管理员维护，你可以调整自己的站内偏好。' })
  return findings
}
