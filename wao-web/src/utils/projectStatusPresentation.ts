import type { TaskStatus } from '@/types'
export const PROJECT_STATUS_COLORS: Record<TaskStatus, string> = {
  待办: '#338fe5', 收集中: '#d172a8', 进行中: '#f08c00', 'AI 进行中': '#8a5cd6', 待验证: '#d9a13b', 已完成: '#3fb96f',
}
export const projectStatusLabel = (status: TaskStatus) => status === '待办' ? '未开始' : status
