import { beforeEach, describe, expect, it } from 'vitest'
import { readTaskDrafts, writeTaskDraft, removeTaskDraft } from '@/utils/taskDraftStorage'
import { TASKS } from '@/data/fixtures'
describe('按任务、成员和窗口隔离草稿', () => {
  beforeEach(() => localStorage.clear())
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
