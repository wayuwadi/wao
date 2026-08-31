import type { AiRun } from '@/types'

/** 用紧凑但保留秒级差异的中文格式展示真实耗时。 */
export function formatDuration(milliseconds: number): string {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000))
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  const restSeconds = seconds % 60
  if (minutes < 60) return `${minutes} 分 ${restSeconds} 秒`
  const hours = Math.floor(minutes / 60)
  const restMinutes = minutes % 60
  if (hours < 24) return `${hours} 小时 ${restMinutes} 分`
  const days = Math.floor(hours / 24)
  return `${days} 天 ${hours % 24} 小时`
}

/** 首次有效领取到终态；滚动升级期间兼容尚未返回 claimedAt 的旧 API。 */
export function totalRunDuration(run: AiRun, now: Date = new Date()): string {
  const start = run.claimedAt || run.startedAt
  if (!start) return '尚未领取'
  const started = Date.parse(start)
  const ended = run.finishedAt ? Date.parse(run.finishedAt) : now.getTime()
  if (!Number.isFinite(started) || !Number.isFinite(ended)) return '时间记录异常'
  return formatDuration(ended - started)
}

/** 只展示 Runner 单调时钟实测值，不拿 Token、目标计时或墙上时间代替。 */
export function aiExecutionDuration(run: AiRun): string {
  if (run.aiExecutionDurationMs != null) return formatDuration(run.aiExecutionDurationMs)
  return ['queued', 'claimed', 'running'].includes(run.status) ? '结束后记录' : '旧记录未记录'
}
