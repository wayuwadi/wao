import { describe, expect, it } from 'vitest'
import { tianrenReadingBody } from '@/components/tianren/tianrenReadingBody'

const headings = ['卦象事实', '《易经》卦意（AI 概括）', '变与不变（AI 概括）', '当日状态观察（AI 延伸）', '《黄帝内经》生活观（AI 概括）', '今日调养提示（AI 延伸）', '今日一件小事', '留给自己的问题', '边界提醒']
describe('tianrenReadingBody', () => {
  it('仅去重完整受控首节事实，剩余八节原文完整保留', () => {
    const source = headings.map((title, index) => `## ${title}\n第${index + 1}节正文\n\n`).join('')
    expect(tianrenReadingBody(source)).toBe(source.slice(source.indexOf('## 《易经》')))
    expect(tianrenReadingBody(source)).toContain('第9节正文')
  })
  it.each(['## 卦象事实\n我的自定义事实\n\n## 我的建议\n不能丢掉', '开场说明\n\n' + headings.map(title => `## ${title}\n正文\n\n`).join(''), '```markdown\n## 卦象事实\n```\n正文', '## 卦象事实\n事实\n\n## 《易经》卦意（AI 概括）\n部分章节'])('异常或历史自定义正文完整保留：%s', source => {
    expect(tianrenReadingBody(source)).toBe(source)
  })
})
