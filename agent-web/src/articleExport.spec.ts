import { describe, expect, it } from 'vitest'
import { articleBlocks, articleDocument, articleFragment } from './articleExport'

describe('图文导出', () => {
  it('把标题、列表、提示和段落转成知识库块', () => {
    expect(articleBlocks('开场\n\n## 小标题\n\n- 第一项\n- 第二项\n\n> 结论')).toEqual([
      { p: '开场' },
      { h: '小标题', hLevel: 2 },
      { ul: ['第一项', '第二项'] },
      { call: '结论' },
    ])
  })

  it('生成可复制的自包含 HTML 并转义用户正文', () => {
    const html = articleDocument({
      title: '我的 <WAO>', content: '正文 & 继续', tags: ['AI'], coverUrl: 'https://wao.example/cover.png',
    })
    expect(html).toContain('<!doctype html>')
    expect(html).toContain('我的 &lt;WAO&gt;')
    expect(html).toContain('正文 &amp; 继续')
    expect(html).toContain('https://wao.example/cover.png')
    expect(articleFragment({ title: '标题', content: '正文', tags: [] })).not.toContain('<html')
  })
})
