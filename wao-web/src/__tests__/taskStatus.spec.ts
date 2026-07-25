import { describe, expect, it } from 'vitest'
import { isOpenStatus, statusMeta, TASK_STATUSES, TASK_STATUS_DOTS } from '@/utils/domain'

describe('任务状态', () => {
  it('按流转顺序给出五个状态，「阻塞」已下线', () => {
    expect(TASK_STATUSES).toEqual(['待办', '进行中', 'AI 进行中', '待验证', '已完成'])
    expect(TASK_STATUSES).not.toContain('阻塞')
  })

  it('只有「已完成」算做完——待验证还没验收，仍要计入未完成', () => {
    expect(TASK_STATUSES.filter(isOpenStatus)).toEqual(['待办', '进行中', 'AI 进行中', '待验证'])
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
  })
})
