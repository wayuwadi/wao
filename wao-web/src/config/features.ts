import { TASK_STATUSES } from '@/utils/domain'
import type { TaskStatus } from '@/types'

/** 项目和任务的 AI Run 已永久下线；历史状态继续可读，不提供新的执行入口。 */
export const AI_RUN_ENABLED = false

/**
 * 可供手动选择的任务状态。AI Run 下线后「AI 进行中」不再是人能选的状态；
 * 历史上已处于该状态的任务仍展示它，避免当前值在下拉里凭空消失。
 */
export function selectableTaskStatuses(current?: TaskStatus | null): TaskStatus[] {
  if (AI_RUN_ENABLED) return TASK_STATUSES
  return TASK_STATUSES.filter((status) => status !== 'AI 进行中' || status === current)
}
