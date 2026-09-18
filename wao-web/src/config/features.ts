import { TASK_STATUSES } from '@/utils/domain'
import type { TaskStatus } from '@/types'

/**
 * AI Run 在任务 / 项目里的入口开关。
 *
 * <p>用户要求先下线任务里的 AI Run：入口全部隐藏，代码与接口原样保留，改回 true 即恢复。
 * 首页 AI 控制中心不受此开关影响。
 */
export const AI_RUN_ENABLED = false

/**
 * 可供手动选择的任务状态。AI Run 下线后「AI 进行中」不再是人能选的状态；
 * 历史上已处于该状态的任务仍展示它，避免当前值在下拉里凭空消失。
 */
export function selectableTaskStatuses(current?: TaskStatus | null): TaskStatus[] {
  if (AI_RUN_ENABLED) return TASK_STATUSES
  return TASK_STATUSES.filter((status) => status !== 'AI 进行中' || status === current)
}
