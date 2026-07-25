import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '@/utils/markdown'

describe('renderMarkdown', () => {
  it('渲染标题、列表与行内样式', () => {
    const html = renderMarkdown('## 目标\n\n- 第一项\n- 第二项\n\n**重点**与 `代码`')
    expect(html).toContain('<h2>目标</h2>')
    expect(html).toContain('<li>第一项</li>')
    expect(html).toContain('<strong>重点</strong>')
    expect(html).toContain('<code>代码</code>')
  })

  it('单个换行即断行，用户敲一次回车就换行', () => {
    expect(renderMarkdown('第一行\n第二行')).toContain('<br>')
  })

  it('剥掉 script 等危险标签，避免描述内容变成 XSS 入口', () => {
    const html = renderMarkdown('正常文本 <script>alert(1)</script><img src=x onerror="alert(1)">')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
    expect(html).toContain('正常文本')
  })

  it('外链补上 target 与 noopener', () => {
    const html = renderMarkdown('[站点](https://example.com)')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('空内容返回空串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown('   ')).toBe('')
  })
})
