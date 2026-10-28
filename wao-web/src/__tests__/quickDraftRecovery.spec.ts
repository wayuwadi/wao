import { describe, expect, it } from 'vitest'
import { quickDraftRecoveryId, sameQuickDraftContent } from '@/utils/quickDraftRecovery'
import type { Task } from '@/types'

describe('首版试用草稿恢复', () => {
  const draft = { id: 'tquick-12345678-1234-4567-8123-123456789abc', title: '保留正文', desc: '## 记录', subs: [{ text: '保留子项' }] } as Task
  it('稳定恢复为后端允许的 32 字符 ID，原内容不变', () => {
    const before = JSON.stringify(draft)
    expect(quickDraftRecoveryId(draft)).toBe('tq12345678123445678123123456789a')
    expect(quickDraftRecoveryId(draft)?.length).toBe(32)
    expect(JSON.stringify(draft)).toBe(before)
  })
  it('只接受正文和所有可编辑内容一致的已保存副本', () => {
    const saved = { ...draft, id: quickDraftRecoveryId(draft)!, editVersion: 'v2', number: 15 }
    expect(sameQuickDraftContent(draft, saved)).toBe(true)
    expect(sameQuickDraftContent(draft, { ...saved, desc: '其他窗口的新内容' })).toBe(false)
    expect(sameQuickDraftContent(draft, { ...saved, subs: [{ text: '新的子项', done: false }] })).toBe(false)
  })
  it('不重编号已保存或其他来源任务', () => {
    expect(quickDraftRecoveryId({ ...draft, editVersion: 'v1' })).toBeNull()
    expect(quickDraftRecoveryId({ ...draft, number: 18 })).toBeNull()
    expect(quickDraftRecoveryId({ ...draft, id: 'other-long-task-id-12345678901234567890' })).toBeNull()
  })
})
