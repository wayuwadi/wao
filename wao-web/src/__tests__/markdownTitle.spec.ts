import { describe, expect, it } from 'vitest'
import { firstMarkdownTextLine } from '@/utils/markdownTitle'

describe('小记标题提取', () => {
  it('跳过文章开头的总览图，使用正文首行', () => {
    expect(firstMarkdownTextLine('![文章总览图](/api/images/a.png)\n\n# 早起与写作\n下一段'))
      .toBe('早起与写作')
  })
})
