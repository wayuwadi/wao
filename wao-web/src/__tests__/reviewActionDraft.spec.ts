import { beforeEach, describe, expect, it } from 'vitest'
import { prepareReviewActionDraft, readReviewActionDraft, reviewActionDraftKey, reviewActionSuggestions, writeReviewActionDraft } from '@/utils/reviewActionDraft'

describe('回顾行动草稿', () => {
  beforeEach(() => localStorage.clear())
  it('按完整列表项保留多行参考，不把嵌套内容误拆成独立承诺', () => {
    expect(reviewActionSuggestions('- 第一项问题\n  补充上下文\n  - 嵌套例子\n- 第二项问题')).toEqual(['第一项问题\n补充上下文\n- 嵌套例子', '第二项问题'])
    expect(reviewActionSuggestions('第一段问题\n\n第二段问题')).toEqual(['第一段问题', '第二段问题'])
  })
  it('重复进入恢复原草稿，不覆盖修改过的目标', () => {
    const source = { id: 'same', periodLabel: '周回顾', text: '原始问题' }
    writeReviewActionDraft('a', { source, form: { title: '已确认的目标', vision: '', startDate: '2026-09-19', targetDate: '2026-09-26', reminderEnabled: false, reminderTime: null, items: [{ title: '具体动作', weekdays: [1] }] } })
    prepareReviewActionDraft('a', { ...source, text: '另一次点击' })
    expect(readReviewActionDraft('a', 'same')?.form?.title).toBe('已确认的目标')
    expect(readReviewActionDraft('a', 'same')?.source.text).toBe('原始问题')
    expect(readReviewActionDraft('b', 'same')).toBeNull()
  })
  it('损坏草稿不被默认数据静默覆盖', () => {
    localStorage.setItem(reviewActionDraftKey('a', 'bad'), '{broken')
    expect(() => prepareReviewActionDraft('a', { id: 'bad', periodLabel: '周回顾', text: '原文' })).toThrow()
    expect(localStorage.getItem(reviewActionDraftKey('a', 'bad'))).toBe('{broken')
  })
})
