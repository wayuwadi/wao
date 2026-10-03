import { describe, expect, it } from 'vitest'
import { inspirationNotes, inspirationExcerpt } from '@/components/home/homeInspiration'
import type { Doc, KnowledgeBase } from '@/types'
function note(id: string, author = 'me', kbId = 'notes'): Doc {
  return { id, title: id, author, kbId, created: '2026-10-01', updated: '2026-10-02', links: [], backlinks: [], blocks: [], content: { version: 1, document: null, markdown: '属于自己的一个真实念头' } } as unknown as Doc
}
const kbs = [{ id: 'notes', notes: true }, { id: 'knowledge', notes: false }] as KnowledgeBase[]
describe('灵感来源边界', () => {
  it('只选当前成员真实小记，不混入他人记录和知识文档', () => {
    expect(inspirationNotes([note('mine'), note('other', 'other'), note('doc', 'me', 'knowledge')], kbs, 'me').map(x => x.id)).toEqual(['mine'])
    expect(inspirationNotes([note('mine')], kbs, '')).toEqual([])
  })
  it('新近的记录在前，空内容不成为灵感卡', () => {
    const empty = { ...note('empty'), content: undefined }
    const recent = { ...note('recent'), updated: '2026-10-03' }
    expect(inspirationNotes([note('old'), empty, recent], kbs, 'me').map(x => x.id)).toEqual(['recent', 'old'])
    expect(inspirationExcerpt(recent)).toBe('属于自己的一个真实念头')
  })
})
