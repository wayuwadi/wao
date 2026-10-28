import type { TaskStatus, Priority } from '@/types'
export const PROJECT_STATUS_COLORS: Record<TaskStatus, string> = {
  待办: '#338fe5', 收集中: '#d172a8', 进行中: '#f08c00', 'AI 进行中': '#8a5cd6', 待验证: '#d9a13b', 已完成: '#3fb96f',
}
export const projectStatusLabel = (status: TaskStatus) => status === '待办' ? '未开始' : status

export const PROJECT_PRIORITY_COLORS: Record<Priority, string> = { P0: '#e5484d', P1: '#f08c00', P2: '#338fe5', P3: '#9aa0ab' }
export const PROJECT_PRIORITY_LABELS: Record<Priority, string> = { P0: '最高优先级', P1: '高优先级', P2: '中优先级', P3: '低优先级' }
