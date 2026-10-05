import type { NotificationCenterSnapshot, NotificationEvent } from '@/api/notificationCenter'

export function livingNotification(
  snapshot: NotificationCenterSnapshot | undefined,
  seen: ReadonlySet<string>,
  now: number,
): NotificationEvent | undefined {
  if (!snapshot || snapshot.unreadCount <= 0) return
  const readAt = snapshot.readAt ? Date.parse(snapshot.readAt) : now - 24 * 60 * 60_000
  if (!Number.isFinite(readAt)) return
  return snapshot.events
    .filter((event) => {
      const at = Date.parse(event.occurredAt)
      return (
        !seen.has(event.id) &&
        ['TASK_COMPLETED', 'AI_RUN', 'INTERNAL'].includes(event.type) &&
        event.severity !== 'WARNING' &&
        safeLivingPath(event.targetPath) &&
        Number.isFinite(at) &&
        at > readAt &&
        at <= now &&
        now - at < 24 * 60 * 60_000
      )
    })
    .sort((a, b) => Date.parse(b.occurredAt) - Date.parse(a.occurredAt))[0]
}
export function safeLivingPath(path: string | null): path is string {
  return !!path && path.startsWith('/') && !path.startsWith('//') && !/[\\\s]/.test(path)
}
export function livingNotificationText(event: NotificationEvent): string {
  return event.type === 'TASK_COMPLETED'
    ? '有一项任务完成了，结果放在这里。'
    : event.type === 'AI_RUN'
      ? '有一项 AI 运行结束了，可以看看结果。'
      : '有一条新通知，我把原文放在这里。'
}
