import { describe, expect, it } from 'vitest'
import { automationResult } from '@/utils/automationResult'

describe('自动化历史结果', () => {
  it.each([
    ['成功 · 已发送', undefined, '成功'],
    ['失败 · 无权限', undefined, '失败'],
    ['部分成功 · 2/3', undefined, '部分成功'],
    ['跳过 · 无数据', undefined, '已跳过'],
    ['已取消', undefined, '已取消'],
    ['已入队 · 等待同步', undefined, '已入队'],
    ['成功 · 已创建 AI Run abc', undefined, '已提交执行'],
    ['成功 · 已启动', 'run1', '已提交执行'],
    ['待处理，失败次数为 0', undefined, '结果待确认'],
    ['', undefined, '结果待确认'],
  ])('%s 不推断未发生的结果', (text, id, label) => {
    expect(automationResult(text, id).label).toBe(label)
    if (label !== '成功') expect(automationResult(text, id).bg).not.toBe('var(--wao-success-bg)')
  })
})
