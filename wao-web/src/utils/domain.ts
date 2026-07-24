/** 域展示工具 —— 与原型 helper（fmt / dueInfo / st / pc）逐一对齐。 */
import type { Priority, TaskStatus, ProjectStatus } from '@/types'

/** ISO(YYYY-MM-DD) → “M月D日”（无前导零）。 */
export function fmtDate(iso: string): string {
  if (!iso) return '—'
  return `${+iso.slice(5, 7)}月${+iso.slice(8, 10)}日`
}

export interface DueInfo {
  label: string
  color: string
}

/** 截止信息：逾期红 / 今天·明天橙 / 其余弱灰。 */
export function dueInfo(iso: string, today: string): DueInfo {
  if (!iso) return { label: '—', color: '#9A9DA9' }
  const d = Math.round((+new Date(iso) - +new Date(today)) / 86400000)
  if (d < 0) return { label: `逾期 ${-d} 天`, color: '#BC4141' }
  if (d === 0) return { label: '今天', color: '#A96A10' }
  if (d === 1) return { label: '明天', color: '#A96A10' }
  return { label: fmtDate(iso), color: '#5A5E6B' }
}

export interface Swatch {
  bg: string
  color: string
}

const STATUS: Record<string, Swatch> = {
  规划中: { bg: '#F0F1F4', color: '#5A5E6B' },
  进行中: { bg: '#E9EDFB', color: '#3A4CC2' },
  有风险: { bg: '#FBF3E4', color: '#A96A10' },
  已完成: { bg: '#E8F5EE', color: '#257A50' },
  待办: { bg: '#F0F1F4', color: '#5A5E6B' },
  阻塞: { bg: '#FBEBEB', color: '#BC4141' },
}

/** 项目 / 任务状态胶囊配色。 */
export function statusMeta(s: ProjectStatus | TaskStatus): Swatch {
  return STATUS[s] ?? { bg: '#F0F1F4', color: '#5A5E6B' }
}

const PRIORITY: Record<Priority, Swatch> = {
  P0: { bg: '#FBEBEB', color: '#BC4141' },
  P1: { bg: '#FBF3E4', color: '#A96A10' },
  P2: { bg: '#E9EDFB', color: '#3A4CC2' },
  P3: { bg: '#F0F1F4', color: '#5A5E6B' },
}

/** 优先级胶囊配色。 */
export function priorityMeta(p: Priority): Swatch {
  return PRIORITY[p] ?? PRIORITY.P3
}
