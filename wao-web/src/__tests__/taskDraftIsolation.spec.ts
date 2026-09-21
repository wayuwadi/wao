import { beforeEach, describe, expect, it } from 'vitest'
import { readTaskDrafts, writeTaskDraft, removeTaskDraft, markTaskDraftDeleted } from '@/utils/taskDraftStorage'
import { TASKS } from '@/data/fixtures'
describe('按任务、成员和窗口隔离草稿', () => {
  beforeEach(() => localStorage.clear())
  it('同一旧稿的多窗口副本一起确认，独立修改仍然保留', () => {
    const draft = { ...TASKS[0]!, id: 'duplicates', desc: '同一旧稿' }
    writeTaskDraft('m1', 'A', draft)
    writeTaskDraft('m1', 'B', draft)
    readTaskDrafts('m1', 'C')
    removeTaskDraft('m1', 'C', draft.id)
    expect(readTaskDrafts('m1', 'D')[draft.id]).toBeUndefined()
  })
  it('恢复其他窗口草稿并采用服务器后，刷新不会再次拾回原副本', () => {
    writeTaskDraft('m1', 'A', { ...TASKS[0]!, id: 'restored', desc: '旧冲突内容' })
    const restored = readTaskDrafts('m1', 'B').restored!
    writeTaskDraft('m1', 'B', { ...restored, desc: '处理后的内容' })
    readTaskDrafts('m1', 'B') // 模拟处理前刷新，恢复来源也必须持久化
    removeTaskDraft('m1', 'B', 'restored')
    expect(readTaskDrafts('m1', 'B').restored).toBeUndefined()
    expect(readTaskDrafts('m1', 'C').restored).toBeUndefined()
  })
  it('恢复期间原窗口有新输入，只清除已确认版本而不删除新输入', () => {
    writeTaskDraft('m1', 'A', { ...TASKS[0]!, id: 'raced', desc: '原版' })
    readTaskDrafts('m1', 'B')
    writeTaskDraft('m1', 'A', { ...TASKS[0]!, id: 'raced', desc: '新输入' })
    removeTaskDraft('m1', 'B', 'raced')
    expect(readTaskDrafts('m1', 'A').raced?.desc).toBe('新输入')
  })
  it('确认删除后所有窗口的旧副本和迟到写入均不恢复', () => {
    const draft = { ...TASKS[0]!, id: 'deleted' }
    writeTaskDraft('m1', 'A', draft)
    markTaskDraftDeleted('m1', draft.id)
    writeTaskDraft('m1', 'B', draft)
    expect(readTaskDrafts('m1', 'A').deleted).toBeUndefined()
    expect(readTaskDrafts('m1', 'C').deleted).toBeUndefined()
    writeTaskDraft('m2', 'A', draft)
    expect(readTaskDrafts('m2', 'A').deleted).toBeDefined()
  })
  it('两个窗口分别编辑不同任务，保存其中一项不会清除另一项', () => {
    writeTaskDraft('m1', 'A', { ...TASKS[0]!, id: 'a', desc: 'A 未保存' })
    writeTaskDraft('m1', 'B', { ...TASKS[0]!, id: 'b', desc: 'B 未保存' })
    expect(Object.keys(readTaskDrafts('m1', 'A'))).toEqual(['a', 'b'])
    removeTaskDraft('m1', 'B', 'b')
    expect(readTaskDrafts('m1', 'A').a?.desc).toBe('A 未保存')
  })
  it('同一任务两个窗口保留各自内容，清理只影响当前窗口', () => {
    writeTaskDraft('m1', 'A', { ...TASKS[0]!, id: 'same', desc: 'A 稿' })
    writeTaskDraft('m1', 'B', { ...TASKS[0]!, id: 'same', desc: 'B 稿' })
    expect(readTaskDrafts('m1', 'A').same?.desc).toBe('A 稿')
    expect(readTaskDrafts('m1', 'B').same?.desc).toBe('B 稿')
    removeTaskDraft('m1', 'B', 'same')
    expect(readTaskDrafts('m1', 'A').same?.desc).toBe('A 稿')
    expect(readTaskDrafts('m2', 'A')).toEqual({})
  })
})
