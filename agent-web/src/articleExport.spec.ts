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

  it('按所选语言渲染代码块，并对代码内容做 HTML 转义', () => {
    const content = '```typescript\nconst tag = "<script>"\n```'
    const html = articleFragment({ title: '代码文章', content, tags: [] })

    expect(html).toContain('typescript')
    expect(html).toContain('const tag = &quot;&lt;script&gt;&quot;')
    expect(html).not.toContain('<script>')
    expect(articleBlocks(content)).toEqual([{ p: content }])
  })

  it.each([
    ['流程图', 'flowchart LR\n  A[开始] --> B[完成]', '开始'],
    ['时序图', 'sequenceDiagram\n  用户->>WAO: 保存文章', '保存文章'],
    ['思维导图', 'mindmap\n  root((文章))\n    代码', '文章'],
  ])('把 Mermaid %s渲染成无需脚本的可导出图形', (label, source, expected) => {
    const content = `\`\`\`mermaid\n${source}\n\`\`\``
    const html = articleDocument({ title: '带图文章', content, tags: [] })

    expect(html).toContain(label)
    expect(html).toContain(expected)
    expect(articleBlocks(content)).toEqual([{ p: content }])
  })
})
