import { describe, expect, it } from 'vitest'
import {
  isOpenStatus,
  statusMeta,
  TASK_DELIVERY_STAGES,
  TASK_STATUSES,
  TASK_STATUS_DOTS,
  taskDeliveryStage,
  taskStatusesForDeliveryStage,
} from '@/utils/domain'

describe('任务状态', () => {
  it('按流转顺序给出六个状态，「阻塞」已下线', () => {
    expect(TASK_STATUSES).toEqual(['待办', '收集中', '进行中', 'AI 进行中', '待验证', '已完成'])
    expect(TASK_STATUSES).not.toContain('阻塞')
  })

  it('只有「已完成」算做完——待验证还没验收，仍要计入未完成', () => {
    expect(TASK_STATUSES.filter(isOpenStatus)).toEqual(['待办', '收集中', '进行中', 'AI 进行中', '待验证'])
    expect(isOpenStatus('已完成')).toBe(false)
  })

  it('每个状态都有自己的胶囊配色与看板圆点色', () => {
    for (const st of TASK_STATUSES) {
      expect(TASK_STATUS_DOTS[st]).toMatch(/^#[0-9A-F]{6}$/i)
      expect(statusMeta(st).bg).toMatch(/^#[0-9A-F]{6}$/i)
    }
    // 新状态不能和「待办」撞成一个灰
    expect(statusMeta('AI 进行中').bg).not.toBe(statusMeta('待办').bg)
    expect(statusMeta('待验证').bg).not.toBe(statusMeta('待办').bg)
    expect(statusMeta('收集中').bg).not.toBe(statusMeta('待办').bg)
  })

  it('六个权威状态唯一派生为四个交付阶段', () => {
    expect(TASK_DELIVERY_STAGES.map((stage) => stage.label)).toEqual(['方向探索', '待开发', '待验收', '已验证'])
    expect(TASK_STATUSES.map((status) => taskDeliveryStage(status))).toEqual([
      'development', 'exploration', 'development', 'development', 'acceptance', 'verified',
    ])
    expect(TASK_DELIVERY_STAGES.flatMap((stage) => taskStatusesForDeliveryStage(stage.id))).toEqual([
      '收集中', '待办', '进行中', 'AI 进行中', '待验证', '已完成',
    ])
  })
})
