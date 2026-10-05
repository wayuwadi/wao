import { shallowRef } from 'vue'
import type { NotificationCenterSnapshot } from '@/api/notificationCenter'

/** 只转发原通知中心成功读取的快照，首页不增加轮询或写入已读。 */
export const notificationSnapshot = shallowRef<{
  member: string
  value: NotificationCenterSnapshot
} | null>(null)
export function publishNotificationSnapshot(member: string, value: NotificationCenterSnapshot) {
  if (member) notificationSnapshot.value = { member, value }
}
