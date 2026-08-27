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
  已搁置: { bg: '#F4F1EE', color: '#75685C' },
  进行中: { bg: '#E9EDFB', color: '#3A4CC2' },
  有风险: { bg: '#FBF3E4', color: '#A96A10' },
  已完成: { bg: '#E8F5EE', color: '#257A50' },
  待办: { bg: '#F0F1F4', color: '#5A5E6B' },
  收集中: { bg: '#FAECF4', color: '#A63C7C' },
  'AI 进行中': { bg: '#F1ECFB', color: '#6B3FBF' },
  待验证: { bg: '#FBF3E4', color: '#A96A10' },
}

/** 项目 / 任务状态胶囊配色。 */
export function statusMeta(s: ProjectStatus | TaskStatus): Swatch {
  return STATUS[s] ?? { bg: '#F0F1F4', color: '#5A5E6B' }
}

/**
 * 任务状态流转顺序：待办 → 收集中 → 进行中 → AI 进行中 → 待验证 → 已完成。
 *
 * <p>「收集中」是「先把想法丢出来、请同伴一起补」的阶段：任务一进这个状态，服务端就把它
 * 分享到飞书群并 @ 上协作成员，省掉手动复制链接发群这一串动作。
 *
 * <p>看板列、状态下拉、分布图都读这一份，别再各自写一遍字面量数组。
 */
export const TASK_STATUSES: TaskStatus[] = ['待办', '收集中', '进行中', 'AI 进行中', '待验证', '已完成']

/** 看板 / 分布图上的状态圆点色（比胶囊底色更实，用于小面积着色）。 */
export const TASK_STATUS_DOTS: Record<TaskStatus, string> = {
  待办: '#C9CDDA',
  收集中: '#D172A8',
  进行中: '#4358D6',
  'AI 进行中': '#8A5CD6',
  待验证: '#D9A13B',
  已完成: '#5CB88A',
}

/**
 * 面向需求治理的四个交付阶段。
 *
 * 交付阶段只解释现有任务状态，不再维护第二份可漂移的字段：收集中仍表示方向尚在征集，
 * 待验证仍表示已经产出但没有人工验收，只有已完成才算已验证。
 */
export type TaskDeliveryStage = 'exploration' | 'development' | 'acceptance' | 'verified'

export interface TaskDeliveryStageOption {
  id: TaskDeliveryStage
  label: string
  description: string
  statuses: TaskStatus[]
}

export const TASK_DELIVERY_STAGES: TaskDeliveryStageOption[] = [
  {
    id: 'exploration',
    label: '方向探索',
    description: '正在征集和补充想法，尚未视为已定需求',
    statuses: ['收集中'],
  },
  {
    id: 'development',
    label: '待开发',
    description: '已经进入待办、人工执行或 AI 执行链路',
    statuses: ['待办', '进行中', 'AI 进行中'],
  },
  {
    id: 'acceptance',
    label: '待验收',
    description: '已经产出结果，等待真实用户链路确认',
    statuses: ['待验证'],
  },
  {
    id: 'verified',
    label: '已验证',
    description: '已经由成员人工验收完成',
    statuses: ['已完成'],
  },
]

/** 从唯一权威的任务状态派生交付阶段。 */
export function taskDeliveryStage(status: TaskStatus): TaskDeliveryStage {
  return TASK_DELIVERY_STAGES.find((stage) => stage.statuses.includes(status))!.id
}

/** 返回某个交付阶段覆盖的原始任务状态，供看板按同一规则收窄列。 */
export function taskStatusesForDeliveryStage(stage: TaskDeliveryStage): TaskStatus[] {
  return TASK_DELIVERY_STAGES.find((option) => option.id === stage)!.statuses
}

/** 未完成 = 除「已完成」外的所有状态（含待验证：还没验收就不算做完）。 */
export const isOpenStatus = (s: TaskStatus) => s !== '已完成'

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
