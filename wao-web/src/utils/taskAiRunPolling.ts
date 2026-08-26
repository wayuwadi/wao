import type { AiRun } from '@/types'

const ACTIVE_RUN_STATUSES = new Set<AiRun['status']>(['queued', 'claimed', 'running'])
const IDLE_POLL_DELAYS_MS = [15_000, 30_000, 60_000] as const

/**
 * 任务抽屉的 AI 档案轮询档位。
 *
 * 活动运行保持五秒反馈；没有活动运行时逐级退避，既能发现从其他设备发起的新运行，
 * 也不会让一个长期打开的历史档案每五秒永久下载完整记录。失败回到最快空闲档重试。
 */
export function nextTaskAiRunPollDelay(
  runs: Pick<AiRun, 'status'>[],
  unchangedRounds: number,
  failed = false,
): number {
  if (runs.some((run) => ACTIVE_RUN_STATUSES.has(run.status))) return 5_000
  const round = failed ? 0 : Math.max(0, unchangedRounds)
  return IDLE_POLL_DELAYS_MS[Math.min(round, IDLE_POLL_DELAYS_MS.length - 1)]!
}

/** 完整响应指纹：状态之外的进度、报告等档案变化也会让空闲轮询回到最快档。 */
export function taskAiRunFingerprint(runs: AiRun[]): string {
  return JSON.stringify(runs)
}
