import type { DevelopmentStatus } from '@/api/development'

export const developmentStatusLabels: Record<DevelopmentStatus, string> = {
  QUEUED: '已入队',
  ANALYZING: '分析中',
  WAITING_DECISION: '待确认',
  IMPLEMENTING: '建设中',
  VERIFYING: '验证中',
  PR_READY: 'PR 待合并',
  MERGED: '已合并待发布',
  DEPLOYING: '发布中',
  RELEASED: '已上线待验收',
  FAILED: '失败',
  PAUSED: '已暂停',
  NO_CHANGE: '暂无改动',
}
export function developmentStatusLabel(status: string): string {
  if (status.toUpperCase() === 'PLANNED') return '已规划'
  return developmentStatusLabels[status.toUpperCase() as DevelopmentStatus] ?? status
}
export const developmentFilters = [
  { id: 'all', label: '全部' },
  { id: 'active', label: '进行中' },
  { id: 'decision', label: '待确认' },
  { id: 'failed', label: '失败' },
  { id: 'acceptance', label: '待验收' },
] as const
export type DevelopmentFilter = (typeof developmentFilters)[number]['id']
const activeStatuses: DevelopmentStatus[] = [
  'QUEUED',
  'ANALYZING',
  'IMPLEMENTING',
  'VERIFYING',
  'PR_READY',
  'MERGED',
  'DEPLOYING',
]
export function matchesDevelopmentFilter(
  status: DevelopmentStatus,
  filter: DevelopmentFilter,
): boolean {
  return (
    filter === 'all' ||
    (filter === 'active' && activeStatuses.includes(status)) ||
    (filter === 'decision' && status === 'WAITING_DECISION') ||
    (filter === 'failed' && status === 'FAILED') ||
    (filter === 'acceptance' && status === 'RELEASED')
  )
}
export function safeDevelopmentUrl(url: string | null | undefined): string | undefined {
  if (!url) return undefined
  try {
    const parsed = new URL(url)
    return ['https:', 'http:'].includes(parsed.protocol) ? parsed.href : undefined
  } catch {
    return undefined
  }
}
